# WebRTC Vendor Artifact

Place the pinned Google WebRTC binary at:

- `WebRTC.xcframework`

Recommended from repository root:

```bash
bash tools/build_libwebrtc_ios_7559.sh
```

This script builds `branch-heads/7559_173`, patches `rtc_base/ssl_roots.h` from
the current root bundle, strips dSYMs for repository-friendly size, copies the
artifact here, and updates checksum.

Then run:

```bash
./scripts/update_webrtc_checksum.sh
```

The app build includes a pre-build checksum verification step. If the artifact is missing,
the app compiles in local stub mode (no real media/call transport).
