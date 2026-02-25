# Postmortem: WiFi Low-Latency Lock Causing ~1.5s Audio Playout Delay

**Date:** 2026-02-24
**Affected component:** Android client (`client-android`)
**Fix commit:** `35f57c5` (v0.1.10)

## Problem Statement

In Android-to-browser WebRTC calls, audio playout delay started low and climbed to ~1-1.6 seconds within the first 10 seconds of a call. The delay was audible in real conversation, not just a stats artifact. It occurred in both audio-only and video call scenarios. Rebooting the phone temporarily fixed it, but the issue returned.

Network metrics appeared healthy: RTT was 4-22ms, reported jitter ~2ms, and packet loss near zero — ruling out a simple network-latency problem.

## Investigation Timeline

### Phase 1: Initial Hypotheses (Ruled Out)

**Camera not released when video disabled.** The first theory was that `WebRtcEngine.toggleVideo()` only called `localVideoTrack?.setEnabled(false)` without stopping the camera capturer, and the resulting CPU load (~120-145% camera provider, ~80-100% app) was starving the audio thread. However, the user confirmed that **calls work fine with video enabled after a reboot**, and a prior attempt to stop the camera when video was disabled had no effect.

**App-level resource leaks.** A thorough code audit of `WebRtcEngine`, `CallManager`, and `CallAudioSessionController` confirmed that all WebRTC resources (PeerConnectionFactory, AudioDeviceModule, EglBase, PeerConnection, tracks, capturers) are fully recreated per call and properly disposed on cleanup. No singleton state accumulates between calls.

### Phase 2: System-Level Investigation

With app-level causes ruled out, investigation shifted to the Android system.

**Device profile:**
- Samsung A065, Snapdragon 8 Gen 1 (taro platform), Android 16 (API 36)
- WiFi: 5GHz 802.11ac, RSSI -28dBm, 866Mbps link speed
- No VPN, no network filters

**AudioFlinger state** revealed:
- FastMixer: `underruns=105`, `overruns=2020`, max jitter `542ms` (on a 4ms mix period)
- DiracSound (Samsung audio enhancement) effect active on primary output
- Massive audio route churn from proximity sensor (dozens of speaker-earpiece switches per session)
- Ring app (com.ringapp) holding 15 concurrent audio sessions

**AAudio MMAP** was unreliable: 40 exclusive mode attempts with only 5 successes. WebRTC was falling back to the legacy AudioTrack path through AudioFlinger.

### Phase 3: Controlled Experiments

Each experiment was built, deployed, and tested in a live call:

| # | Experiment | Result |
|---|---|---|
| A | Disable proximity-based audio routing | No change (issue predated the feature) |
| B | Set video transceiver to INACTIVE when video off | No change |
| C | Kill Ring app (`am force-stop com.ringapp`) | No change |
| D | Disable low-latency mode (`setUseLowLatency(false)`) | No change |
| E | Switch to software AEC/NS (disable hardware) | No change |

None of these changed the behavior. The delay appeared within 10 seconds regardless of configuration, indicating the problem existed before the call even started.

### Phase 4: Diagnostic Instrumentation

Custom logging was added to the `inbound-rtp` stats collection in `WebRtcEngine` to capture per-sample jitter buffer metrics:

```kotlin
// Added to inbound-rtp stat processing
Log.w("WebRtcEngine", "AUDIO_DIAG inbound-rtp: " +
    "jitter=...ms target=...ms minPlayout=...ms avgBufDelay=...ms ...")
```

This revealed the critical data:

```
jitter=46.0ms target=1206ms minPlayout=1206ms avgBufDelay=1007ms
concealed=71888 total=1427040 pktsRcvd=1461 pktsLost=0
```

Key observations:
- **`minPlayout=1206ms`**: Something was setting a 1.2-second *minimum* playout delay, forcing NetEq to buffer that much regardless of conditions.
- **`jitter=46ms`**: Abnormally high for a -28dBm 5GHz WiFi link (should be <5ms). This was the averaged value; spikes were much larger.
- **5% concealment with 0% packet loss**: Packets arrived but *after* their playout deadline — they were too late, so NetEq generated concealment audio instead.
- **15,379 transport feedback failures**: `"Failed to lookup send time for 15379 packets. Packets reordered or send time history too small?"` — packets arriving so late that their send history had been pruned.

The `stream_synchronization.cc` logs showed the audio delay was already ~1400ms on the very first log entry — it didn't "climb," it started high.

### Phase 5: Network-Level Discovery

The WebRTC stats pointed to a packet delivery problem, not an audio processing problem. WiFi system logs revealed the cause:

```
dumpsys wifi | grep CMD_RSSI_POLL
```

```
2026-02-24T00:44:31.234 - CMD_RSSI_POLL was running for 1477 ms
2026-02-24T00:44:47.770 - CMD_RSSI_POLL was running for 1478 ms
2026-02-24T00:45:04.287 - CMD_RSSI_POLL was running for 1451 ms
2026-02-24T00:45:24.315 - CMD_RSSI_POLL was running for 1446 ms
2026-02-24T00:45:40.853 - CMD_RSSI_POLL was running for 1476 ms
2026-02-24T00:46:06.397 - CMD_RSSI_POLL was running for 1432 ms
```

**WiFi RSSI polling operations were taking 1.4-1.5 seconds each.** These should take 10-50ms. The prolonged duration indicates the WiFi chip was performing full off-channel scans during each poll — leaving the active 5GHz channel for ~1.5 seconds to scan other frequencies. During this time, all incoming UDP packets were buffered by the driver and delivered in a burst when the chip returned.

The pattern correlated with the WiFi lock: before the call (without lock), polls alternated between ~500ms and ~1500ms. After the `WIFI_MODE_FULL_LOW_LATENCY` lock was acquired at call start, polls became consistently ~1500ms.

### Phase 6: Confirmation

**Test on cellular (5G):** Delay disappeared completely. This confirmed the issue was WiFi-specific.

**Test with WiFi lock disabled:** On the same WiFi network, removing the `acquireWifiLock()` call produced:

```
jitter=1.0ms target=27ms minPlayout=27ms avgBufDelay=36ms
concealed=0 total=1319040 pktsRcvd=1376 pktsLost=0
```

Stream synchronization audio delay dropped from ~1600ms to ~112ms.

## Root Cause

`WIFI_MODE_FULL_LOW_LATENCY` on this Samsung/Qualcomm device **paradoxically triggers prolonged off-channel WiFi scans** (~1.5s each, every ~16s) instead of suppressing them. The intended behavior of this lock is to:

- Disable background/roaming scans
- Minimize power-save packet buffering
- Optimize for latency over power consumption

Samsung's implementation appears to do the opposite — possibly changing the scan schedule to use longer, less frequent scans that are more disruptive than the default pattern.

**The causal chain:**

```
WIFI_MODE_FULL_LOW_LATENCY lock acquired
  -> WiFi chip performs ~1.5s off-channel scans every ~16s
    -> All incoming UDP packets buffered during scan
      -> Packets delivered in burst when chip returns to channel
        -> WebRTC sees 1.5s jitter spike
          -> NetEq grows jitter buffer target to ~1.2s
            -> Audio playout delayed by 1-1.6 seconds
              -> stream_synchronization delays video to match
```

**Why reboot temporarily fixed it:** The WiFi driver's scan scheduling state resets on reboot. The lock's effect on the driver may also leave residual state that persists across app process restarts but not device reboots.

## Fix

Disabled WiFi lock acquisition during calls in `CallManager.acquirePerformanceLocks()`. The CPU wake lock is retained to prevent device sleep.

```kotlin
private fun acquirePerformanceLocks() {
    acquireCpuWakeLock()
    // Wi-Fi low-latency lock disabled: on some devices (notably Samsung with Qualcomm WiFi),
    // WIFI_MODE_FULL_LOW_LATENCY triggers prolonged off-channel scans (~1.5s) instead of
    // suppressing them, causing massive UDP packet jitter and ~1s+ audio playout delay.
}
```

## Before/After Comparison

| Metric | Before (with WiFi lock) | After (without WiFi lock) |
|---|---|---|
| RTP jitter | 46ms | 1ms |
| Jitter buffer target | 1206ms | 27ms |
| Avg buffer delay | 1007ms | 36ms |
| Audio sync delay | 1605ms | 112ms |
| Concealed samples | 5% | 0% |
| Transport feedback failures | 15,379 | 0 |
| Packets lost | 0 | 0 |

## Lessons Learned

1. **Android WiFi power management APIs are not uniformly implemented.** `WIFI_MODE_FULL_LOW_LATENCY` is an optimization hint, not a guarantee. OEM WiFi drivers may interpret it differently or even counterproductively. Testing on specific hardware is essential.

2. **High jitter buffer delay with zero packet loss is the signature of packet batching.** When packets arrive in bursts but none are dropped, the network path is fine but something in the delivery stack is buffering. On mobile devices, WiFi power management is the most common cause.

3. **The diagnostic stats that mattered most were not in the app's original telemetry.** The `jitterBufferTargetDelay`, `jitterBufferMinimumDelay`, and `concealedSamples` stats from WebRTC's `inbound-rtp` immediately showed that NetEq was being *forced* to a high target (not gradually growing), and that packets were arriving too late rather than being lost. Adding these to the in-call diagnostics panel would accelerate future debugging.

4. **"Works after reboot" almost always points to system-level state.** When the app's own resources are properly lifecycle-managed (confirmed by code audit), the accumulated state lives in a system service, driver, or kernel module. WiFi driver scan state is an easy-to-overlook example.

5. **Transport feedback failures are an early warning signal.** The `"Failed to lookup send time for N packets"` log from `transport_feedback_adapter.cc` appeared in every affected call and directly indicated packet delivery disruption — but was never surfaced to the user or collected as a metric.
