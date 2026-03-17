# Shared Layout Algorithm with Cross-Platform Conformance Tests

## Technical Design Document

**Date:** 2026-03-15
**Status:** Proposal
**Scope:** Formalize existing cross-platform layout parity and add conformance testing
**Platforms:** Web SPA, iOS, Android
**Participants supported:** 1-4 (designed to grow)

---

## 1. Motivation

All three Serenada clients already share an identical `computeStageLayout()` algorithm that uses harmonic-mean-based row packing to compute optimal tile arrangements for multi-party video calls. The algorithm was ported line-by-line across TypeScript, Kotlin, and Swift, and produces identical output for the same inputs.

This is a strong foundation. Rather than replacing it with a declarative JSON template system (see `docs/video-call-layout/` for that proposal), this design formalizes what already works:

- Extract the shared algorithm into a well-documented, testable contract
- Add cross-platform conformance test fixtures that all three clients run
- Extend the algorithm to cover layout paths not yet handled (content sharing, focus/pinned modes)
- Keep the algorithm adaptive to actual viewport sizes and video aspect ratios

### Why not the declarative spec approach?

The declarative bundle in `docs/video-call-layout/` is well-crafted, but introduces significant complexity for the current state of the project:

| Concern | Declarative spec | Shared algorithm |
|---------|-----------------|------------------|
| Viewport handling | 6 hardcoded buckets | Actual measured dimensions |
| Aspect ratio awareness | Fixed cover/contain | Per-tile aspect ratio optimization |
| Spec maintenance | ~69KB JSON, 18 templates | ~80 lines of algorithm |
| JSON parsing on native | Complex discriminated union deserialization | Not needed |
| Layout changes | Edit JSON + schema + 3 engines + 108 test cases | Edit algorithm + update fixtures |
| Adaptability | Fixed templates per environment class | Continuous optimization across all viewport sizes |

The declarative approach becomes valuable at scale (8+ participants, webinar stage layouts, remote-tunable layout rules). For 1-4 participants with uniform grid/focus/content layouts, the algorithmic approach is simpler and more adaptive.

---

## 2. Current State

### 2.1 What exists today

**Multi-party stage layout** (`computeStageLayout`) is implemented identically in:
- Web: `client/src/pages/CallRoom.tsx:236-323`
- Android: `client-android/.../ui/CallScreen.kt:1279-1373`
- iOS: `client-ios/.../Screens/CallScreen.swift:810-915`

**Algorithm summary:**
1. Accept a list of tile specs (each with a `cid` and `aspectRatio`), available width/height, and gap
2. Generate candidate row configurations (e.g., for 3 tiles: `[[0,1,2]]`, `[[0,1],[2]]`, `[[0],[1,2]]`, `[[0],[1],[2]]`)
3. For each candidate, compute row heights from aspect ratio sums, scale to fit viewport
4. Score by harmonic mean of short edges, penalizing small minimum short edges
5. Select the best-scoring configuration
6. Return pixel-precise tile dimensions

**Shared constants across all platforms:**
- `MIN_STAGE_TILE_ASPECT = 9/16`
- `MAX_STAGE_TILE_ASPECT = 16/9`
- `DEFAULT_STAGE_TILE_ASPECT = 16/9`
- `gap = 12` (px/dp/pt)
- `outerPadding = 16` (px/dp/pt)

**Layout modes currently in use:**
- **1:1 mode** (`isMultiParty == false`): Full-screen remote video, draggable local pip
- **Multi-party mode** (`isMultiParty == true`): `computeStageLayout` grid with fixed local pip at bottom-right

**Not yet handled by a shared algorithm:**
- Content sharing layout (screen share replaces local video track; no separate content region)
- Focus/pinned participant layout
- World camera as a content source with its own layout region

### 2.2 What is already consistent

The algorithm itself is identical across platforms. What varies slightly:
- Platform-specific rounding (`Math.floor` vs `floor` vs `Int` truncation)
- Aspect ratio change detection thresholds on Android (quantized to 0.05 steps with hysteresis)
- Local pip sizing and positioning (hard-coded per platform, not part of the shared algorithm)

---

## 3. Design

### 3.1 Core principle: shared algorithm, platform-native rendering

```
Application State
      │
      ▼
CallScene (platform-specific → normalized)
      │
      ▼
Layout Algorithm (shared logic, per-platform implementation)
      │
      ▼
LayoutResult (normalized output)
      │
      ▼
Platform Renderer (native UI primitives)
```

The algorithm remains implemented natively in each language (TypeScript, Kotlin, Swift). It is **not** a shared library or compiled module. Cross-platform parity is enforced by:
1. Identical algorithm logic (the code is a direct port)
2. Shared conformance test fixtures that all platforms run

### 3.2 Unified input model: `CallScene`

Normalize the runtime state into a common structure before passing it to the layout algorithm.

```typescript
interface CallScene {
  // Viewport
  viewportWidth: number;    // actual measured width in px/dp/pt
  viewportHeight: number;   // actual measured height in px/dp/pt
  safeAreaInsets: Insets;    // top, bottom, left, right

  // Participants
  participants: Participant[];
  localParticipantId: string;

  // State
  activeSpeakerId: string | null;
  pinnedParticipantId: string | null;
  contentSource: ContentSource | null;

  // Layout context
  layoutMode: LayoutMode;   // derived, not user-set

  // User preferences (toggled at runtime, not derived)
  userPrefs: UserLayoutPrefs;
}

interface Participant {
  id: string;
  role: "local" | "remote";
  videoEnabled: boolean;
  videoAspectRatio: number | null;  // actual track aspect ratio, null if no video
}

interface ContentSource {
  type: "screenShare" | "worldCamera" | "compositeCamera";
  ownerParticipantId: string;
  aspectRatio: number | null;
}

type LayoutMode = "solo" | "pair" | "grid" | "focus" | "content";

// User-togglable state that affects rendering
interface UserLayoutPrefs {
  swappedLocalAndRemote: boolean;  // pair mode: pip and main swapped
  dominantFit: "cover" | "contain"; // pair/focus/content: fit mode for dominant tile
}

interface Insets {
  top: number;
  bottom: number;
  left: number;
  right: number;
}
```

**Mode derivation** (deterministic, same on all platforms):

```
if participants.length == 1:
    mode = solo
else if contentSource != null:
    mode = content
else if pinnedParticipantId != null:
    mode = focus
else if participants.length == 2:
    mode = pair
else:
    mode = grid
```

The `solo` mode covers the "waiting for others" state. The `pair` mode preserves the current 1:1 full-screen layout which is distinct from the multi-party grid.

**Content source precedence:** If multiple participants are sharing simultaneously, the `contentSource` field reflects the **last one to start sharing** (last-writer-wins). The signaling layer is responsible for tracking this; the layout algorithm only sees a single `contentSource` or `null`.

### 3.3 Unified output model: `LayoutResult`

```typescript
interface LayoutResult {
  mode: LayoutMode;
  tiles: TileLayout[];
  localPip: PipLayout | null;  // null in focus and content modes (local is in the filmstrip)
}

interface TileLayout {
  id: string;                  // participant ID or content source ID
  type: "participant" | "contentSource";
  frame: Rect;                 // absolute position in viewport coordinates
  fit: "cover" | "contain";
  cornerRadius: number;
  zOrder: number;              // 0 = base layer, higher = on top
}

interface PipLayout {
  participantId: string;
  frame: Rect;
  fit: "cover" | "contain";
  cornerRadius: number;
  anchor: "topLeft" | "topRight" | "bottomLeft" | "bottomRight";
  zOrder: number;
}

interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}
```

### 3.4 Layout algorithms by mode

#### 3.4.1 Solo mode

Used when the local participant is alone in the room (waiting for others). The main viewport area is reserved for invite controls and room information. The local participant's self-video is shown as a pip.

```
Main area: reserved for invite/room UI (not managed by the layout algorithm)
Local pip at bottomRight anchor
  width = viewport.width * pipWidthFraction
  aspectRatio = localVideoAspectRatio (clamped to 9:16..16:9)
  inset from edges = pipInset
  fit = "cover"
```

The layout algorithm produces only a `localPip` in this mode with an empty `tiles` array. The call screen UI is responsible for rendering invite controls in the main area.

#### 3.4.2 Pair mode

Preserves the existing 1:1 layout: dominant video fills the screen with a pip overlay for the other participant.

**Default (not swapped):**
```
Remote tile fills available area (viewport minus safe area insets)
fit = userPrefs.dominantFit (default "cover")
Local pip at bottomRight anchor
  width = viewport.width * pipWidthFraction
  aspectRatio = localVideoAspectRatio (clamped to 9:16..16:9)
  inset from edges = pipInset
  fit = "cover"
```

**Swapped (`userPrefs.swappedLocalAndRemote == true`):**
```
Local tile fills available area
fit = userPrefs.dominantFit (default "cover")
Remote pip at bottomRight anchor
  same sizing as local pip above
  fit = "cover"
```

**Swap interaction:** Tapping the pip swaps which participant is dominant and which is the pip. This is a user preference toggle (`swappedLocalAndRemote`), already implemented on Android and web for 1:1 calls.

**Dominant fit toggle:** The user can toggle `userPrefs.dominantFit` between `"cover"` and `"contain"` for the dominant (full-screen) tile. This is the existing fit/fill toggle on Android and web.

This matches the current behavior on all three platforms.

#### 3.4.3 Grid mode (existing algorithm, formalized)

This is the current `computeStageLayout()` algorithm, unchanged. It handles remote participants in an optimized grid. The local participant is always shown as a pip overlay, never in the grid.

**Inputs:**
- Remote participant tiles with actual video aspect ratios (clamped to 9:16..16:9, default 16:9)
- Available width and height (viewport minus safe area insets minus padding)
- Gap between tiles

**Algorithm (unchanged from current):**
1. Generate candidate row configurations for N remote tiles
2. For each candidate:
   a. Compute base row heights from aspect ratio sums
   b. Scale rows to fit available height
   c. Score using harmonic mean of short edges
3. Select best configuration using the existing multi-criteria comparison
4. Return pixel-precise tile dimensions

**Local pip:** Fixed position at bottomRight, same as pair mode. The local participant is always a pip in grid mode.

**Extension point for 5+ participants:** Add more candidate row configurations. The algorithm generalizes naturally — for N tiles, generate candidate configurations from `partition(N)` and score them identically. The current hardcoded candidates for 1-3 tiles simply enumerate small partitions exhaustively.

#### 3.4.4 Focus mode (new)

When a participant is pinned, show them as the dominant tile with others in a secondary filmstrip. The local participant appears in the filmstrip (not as a pip).

**Portrait orientation:**
```
┌──────────────────┐
│                  │
│   Primary tile   │  ratio: 3/4 of available height
│   (pinned)       │
│                  │
├──────────────────┤
│ [S1] [S2] [ME]  │  ratio: 1/4 of available height
└──────────────────┘
```

**Landscape orientation:**
```
┌──────────────┬──────┐
│              │ [S1] │
│  Primary     ├──────┤
│  tile        │ [S2] │  secondary strip: 1/4 of available width
│  (pinned)    ├──────┤
│              │ [ME] │
└──────────────┴──────┘
```

**Algorithm:**
1. Determine orientation from viewport aspect ratio (width/height > 1 = landscape)
2. Split available area into primary region and secondary strip
3. Primary: pinned participant fills the primary region, `fit = userPrefs.dominantFit` (default `"cover"`)
4. Secondary: remaining participants arranged in a **simple equal-division filmstrip**
   - Horizontal strip in portrait (tiles side by side)
   - Vertical strip in landscape (tiles stacked)
   - Tile sizes computed by dividing strip length equally, respecting gap
   - All tiles use `fit = "cover"`
5. No local pip — the local participant is always included in the secondary filmstrip

**Dominant fit toggle:** The user can toggle `userPrefs.dominantFit` between `"cover"` and `"contain"` for the primary (pinned) tile, consistent with pair and content modes.

**Primary/secondary split ratio:** 3:1 by default. If the secondary strip would make tiles smaller than `thumbnailMinSize` (72px on phone, 96px on tablet, 120px on desktop), increase the secondary strip proportion to meet the minimum.

**Who goes in the secondary strip:**
- All participants except the pinned participant, **including the local participant**
- Stable join order, local participant last

#### 3.4.5 Content mode (new)

When a screen share, world camera, or composite camera is active, show the content as the dominant area with participants in a secondary filmstrip. The local participant appears in the filmstrip (not as a pip).

The layout structure is identical to focus mode, but:
- Primary region: content source with `fit = userPrefs.dominantFit` (default `"contain"`, preserving content aspect ratio)
- Secondary strip: all participants **except the content owner**, with `fit = "cover"` — the content owner's video is already represented by the primary content tile, so including them in the filmstrip would be redundant
- No local pip — the local participant is always in the secondary filmstrip (unless they are the content owner, in which case they only appear as the primary content tile)

**Dominant fit toggle:** The user can toggle `userPrefs.dominantFit` between `"cover"` and `"contain"` for the content area, consistent with pair and focus modes.

**Multiple concurrent content sources:** If multiple participants share content simultaneously, the **last one to start sharing** takes the primary content stage. Previous content sources are dropped from the layout (not queued).

**Content source types are layout-equivalent:** `screenShare`, `worldCamera`, and `compositeCamera` all produce the same layout structure. The composite camera mode (which combines selfie + world camera into a single stream) is treated identically to world camera for layout purposes.

This matches the treatment described in the declarative spec proposal: "worldCamera and screenShare intentionally use the same layout policy."

### 3.5 Local participant behavior summary

| Mode | Local participant | Dominant fit toggle | Pip swap |
|------|------------------|--------------------|---------:|
| Solo | Pip (main area for invite UI) | N/A | N/A |
| Pair | Pip (or dominant if swapped) | Yes | Yes |
| Grid | Pip | N/A | N/A |
| Focus | In secondary filmstrip | Yes (primary tile) | N/A |
| Content | In secondary filmstrip | Yes (content area) | N/A |

### 3.6 Shared constants

All platforms use the same constants. These live in a single reference table documented here and replicated in each client.

```
LAYOUT_CONSTANTS = {
  // Tile spacing
  gap:             { phone: 8, tablet: 12, desktop: 12 }
  outerPadding:    { phone: 8, tablet: 16, desktop: 16 }
  cornerRadius:    { phone: 12, tablet: 14, desktop: 16 }

  // Aspect ratio clamping
  minTileAspect:   9/16   (0.5625)
  maxTileAspect:   16/9   (1.7778)
  defaultTileAspect: 16/9

  // Local pip
  pipWidthFraction:  { phone: 0.24, tablet: 0.20, desktop: 0.16 }
  pipAspectRatio:    3/4   (portrait video default)
  pipInset:          { phone: 12, tablet: 16, desktop: 20 }
  pipCornerRadius:   12

  // Focus/content mode
  primaryRatio:      0.75   (3/4 of the available space for the dominant tile)
  thumbnailMinSize:  { phone: 72, tablet: 96, desktop: 120 }

  // Scoring thresholds (grid mode)
  harmonicShortEdgeTolerance: 6   (px)
  minShortEdgeTolerance:      1   (px)
}
```

**Device group classification** — a simple function, not a viewport bucketing system:

```
function deviceGroup(viewportWidth, viewportHeight):
  shortSide = min(viewportWidth, viewportHeight)
  if shortSide < 600:
    return "phone"
  else if shortSide < 1024:
    return "tablet"
  else:
    return "desktop"
```

This replaces the 6-bucket environment classification from the declarative proposal. The layout algorithm uses actual viewport dimensions for geometry, and only uses device group for selecting token values (gap, padding, etc.).

**Orientation** — derived from the viewport, not from platform sensor:

```
function orientation(viewportWidth, viewportHeight):
  return viewportWidth > viewportHeight ? "landscape" : "portrait"
```

### 3.7 Transitions

Transition behavior stays platform-native. This design does not prescribe animation implementation, but does prescribe **when** transitions should be animated vs. snapped:

| Event | Transition |
|-------|-----------|
| Participant join/leave | Animate tile resize/reposition (200-250ms) |
| Mode change (grid → focus, etc.) | Animate (250-300ms) |
| Orientation change | Animate (250-320ms) |
| Content source start/stop | Animate primary area (250ms) |
| Viewport resize (window drag) | Snap (no animation, recompute immediately) |

Each platform uses its native animation system:
- Web: CSS transforms with `transition` or `requestAnimationFrame`
- iOS: SwiftUI `.animation()` or UIKit `UIView.animate`
- Android: Compose `animateDpAsState` or property animations

### 3.8 Stable participant ordering

Participants should maintain consistent visual positions across layout updates. The ordering rule (same on all platforms):

1. Remote participants in **join order** (order they appear in the participants array)
2. Local participant **last**

Reorder triggers (participant positions recalculated):
- Participant joined
- Participant left
- Pin/unpin action
- Mode change

Do NOT reorder on:
- Active speaker changes (avoid visual jitter)
- Video enable/disable toggles

---

## 4. Conformance Testing

### 4.1 Test fixture format

A single JSON file containing canonical test cases. Each case specifies an input `CallScene` and the expected `LayoutResult` with normalized frame coordinates.

```json
{
  "version": 1,
  "cases": [
    {
      "id": "grid_3p_phone_portrait",
      "description": "3 participants, phone portrait, all 16:9 video",
      "scene": {
        "viewportWidth": 390,
        "viewportHeight": 844,
        "safeAreaInsets": { "top": 0, "bottom": 0, "left": 0, "right": 0 },
        "participants": [
          { "id": "A", "role": "remote", "videoEnabled": true, "videoAspectRatio": 1.778 },
          { "id": "B", "role": "remote", "videoEnabled": true, "videoAspectRatio": 1.778 },
          { "id": "ME", "role": "local", "videoEnabled": true, "videoAspectRatio": 1.778 }
        ],
        "localParticipantId": "ME",
        "activeSpeakerId": null,
        "pinnedParticipantId": null,
        "contentSource": null
      },
      "expected": {
        "mode": "grid",
        "tileCount": 2,
        "tiles": [
          {
            "id": "A",
            "type": "participant",
            "normalizedFrame": { "x": 0.021, "y": 0.034, "width": 0.959, "height": 0.451 },
            "fit": "cover"
          },
          {
            "id": "B",
            "type": "participant",
            "normalizedFrame": { "x": 0.021, "y": 0.500, "width": 0.959, "height": 0.451 },
            "fit": "cover"
          }
        ],
        "localPip": {
          "participantId": "ME",
          "anchor": "bottomRight"
        }
      }
    }
  ]
}
```

**Normalized frames** use coordinates in `[0, 1]` relative to viewport dimensions:
```
normalizedFrame.x = frame.x / viewportWidth
normalizedFrame.y = frame.y / viewportHeight
normalizedFrame.width = frame.width / viewportWidth
normalizedFrame.height = frame.height / viewportHeight
```

### 4.2 Tolerance

Cross-platform rounding differences (floor vs truncation vs rounding) can cause small coordinate deltas.

- **Strict tolerance**: normalized coordinate delta <= 0.005 (within 0.5% of viewport)
- **Relaxed tolerance**: normalized coordinate delta <= 0.01 (within 1% of viewport)

Use strict for same-platform regression testing, relaxed for cross-platform conformance.

### 4.3 Test matrix

The conformance suite covers these dimensions:

| Dimension | Values |
|-----------|--------|
| Mode | solo, pair, grid, focus, content |
| Participant count | 1, 2, 3, 4 |
| Viewport | 390x844, 844x390, 768x1024, 1024x768, 1280x720, 1600x900 |
| Aspect ratios | all 16:9, all 4:3, mixed (16:9 + 4:3 + 9:16) |
| Content source | none, screenShare, worldCamera, compositeCamera |
| Pinned participant | none, remote, local |
| Safe area insets | none, top+bottom (47+34, simulating iPhone notch/home indicator) |

Not every combination is needed. The recommended case set:

**Grid mode (core algorithm validation):**
- 2 participants x 6 viewports x 3 aspect ratio combos = 36 cases
- 3 participants x 6 viewports x 3 aspect ratio combos = 54 cases
  (These are the highest-value cases: the grid algorithm's row-packing decisions vary most with 3 tiles across different viewports and aspect ratios.)

**Pair mode:**
- 2 participants x 6 viewports = 12 cases (straightforward, but validates pip placement)

**Focus mode:**
- 3-4 participants x 6 viewports x 2 pin variants (remote, local) = 24-48 cases

**Content mode:**
- 2-4 participants x 6 viewports x 3 content types = 54-108 cases
  (Since all three content types produce the same layout, a representative subset is sufficient: test all three types on one viewport, then only `screenShare` across the rest.)

**Safe area insets:**
- A representative subset (grid + focus + content, phone portrait only) with notch insets = ~12 cases

**Recommended starting set: ~100-120 cases**, focused on the grid and focus modes where cross-platform divergence is most likely.

### 4.4 Test runner per platform

Each platform runs the same fixtures through its native layout algorithm and asserts the output matches expected normalized frames within tolerance.

**Web (Vitest):**
```typescript
import cases from './fixtures/layout_conformance_v1.json';

describe('layout conformance', () => {
  for (const testCase of cases) {
    it(testCase.id, () => {
      const result = computeLayout(testCase.scene);
      assertMode(result, testCase.expected.mode);
      assertTileCount(result, testCase.expected.tileCount);
      assertNormalizedFrames(result, testCase.expected.tiles, STRICT_TOLERANCE);
    });
  }
});
```

**Android (JUnit):**
```kotlin
@RunWith(Parameterized::class)
class LayoutConformanceTest(private val case: TestCase) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = loadFixtures("layout_conformance_v1.json")
    }

    @Test fun conformance() {
        val result = computeLayout(case.scene)
        assertEquals(case.expected.mode, result.mode)
        assertEquals(case.expected.tileCount, result.tiles.size)
        assertNormalizedFrames(result, case.expected.tiles, STRICT_TOLERANCE)
    }
}
```

**iOS (XCTest):**
```swift
final class LayoutConformanceTests: XCTestCase {
    func testAllCases() throws {
        let cases = try loadFixtures("layout_conformance_v1.json")
        for testCase in cases {
            let result = computeLayout(testCase.scene)
            XCTAssertEqual(result.mode, testCase.expected.mode, testCase.id)
            XCTAssertEqual(result.tiles.count, testCase.expected.tileCount, testCase.id)
            assertNormalizedFrames(result, testCase.expected.tiles,
                                  tolerance: strictTolerance, testCase.id)
        }
    }
}
```

### 4.5 Fixture generation

A reference implementation (the TypeScript version, since it can run in CI without platform tooling) generates the canonical fixtures:

```bash
cd client
npx tsx tests/layout/generate_fixtures.ts > tests/layout/fixtures/layout_conformance_v1.json
```

The generator runs the layout algorithm against the full test matrix and writes normalized output. When the algorithm changes intentionally, regenerate fixtures, review the diff, and commit together with the code change.

### 4.6 CI integration

**On every PR (fast, all platforms):**
- Run conformance tests against existing fixtures
- Fail if any case exceeds strict tolerance

**When fixtures are regenerated:**
- PR diff shows exactly which layout decisions changed and by how much
- Reviewer inspects the fixture diff alongside the algorithm change

---

## 5. Implementation Plan

### Phase 1: Extract and test the existing grid algorithm

**Goal:** Get conformance tests running on all three platforms without changing any layout behavior.

1. **Extract `computeLayout()` wrapper** on each platform that:
   - Accepts a `CallScene` input
   - Calls the existing `computeStageLayout()` for grid mode
   - Returns a `LayoutResult` with normalized frames
   - Handles pair/solo modes trivially (full-frame tile + pip)

2. **Create the fixture JSON** with grid and pair mode cases generated from the TypeScript reference.

3. **Add conformance test runners** to each platform's test suite:
   - Web: `client/src/tests/layout/layout_conformance.test.ts`
   - Android: `client-android/app/src/test/java/.../LayoutConformanceTest.kt`
   - iOS: `client-ios/Tests/LayoutConformanceTests.swift`

4. **Add fixture file** at `tests/layout/fixtures/layout_conformance_v1.json` (single source of truth)
   - Symlink from each platform's test resources directory
   - Each platform's test runner reads from the symlink

**Deliverable:** All three platforms pass the same conformance suite. Zero behavior change.

### Phase 2: Add focus mode

**Goal:** Implement pinned-participant focus layout on all three platforms.

1. Add mode derivation logic (`pinnedParticipantId != null → focus`)
2. Implement the primary + secondary strip layout described in section 3.4.4
3. Add focus mode conformance cases to the fixture file
4. Ship once all platforms pass

### Phase 3: Add content mode

**Goal:** Implement screen-share / world-camera / composite-camera content layout.

Currently, screen sharing replaces the local video track and the tile's fit changes to `contain`. This phase adds a proper content layout with a dominant content region and participant filmstrip.

1. Add `contentSource` to the `CallScene` model on each platform
2. Implement the content layout described in section 3.4.5
3. Add content mode conformance cases
4. Wire up screen share, world camera, and composite camera to produce a `contentSource` rather than replacing the local video track
5. Implement last-writer-wins for multiple concurrent content sources

**Note:** This changes user-visible behavior. The screen share will get its own large region instead of replacing the local video. The composite camera mode (currently handled as a camera mode switch) will also enter content layout.

### Phase 4: Scale beyond 3 tiles in grid mode

**Goal:** Support 4+ remote participants with optimal grid layouts.

The current algorithm hardcodes candidate row configurations for 1-3 tiles. To support 4+:

1. Add candidate configurations for 4 tiles: `[[0,1,2,3]]`, `[[0,1],[2,3]]`, `[[0,1,2],[3]]`, `[[0],[1,2,3]]`, `[[0,1],[2],[3]]`, `[[0],[1],[2,3]]`, `[[0],[1],[2],[3]]`
2. For 5+ tiles, generate candidates programmatically using integer partitions of N
3. Add conformance cases for 4-participant grids
4. Consider capping candidate generation for large N (e.g., only test configurations with <= 4 rows)

---

## 6. File Structure

The canonical fixture file lives at the repo root. Each platform symlinks to it so their test runners can find it without copying.

```
tests/
  layout/
    fixtures/
      layout_conformance_v1.json          ← single source of truth
    generate_fixtures.ts                  ← TypeScript fixture generator

client/
  src/
    layout/
      computeLayout.ts                    ← extracted layout algorithm
      computeLayout.test.ts               ← conformance test runner
      fixtures -> ../../../tests/layout/fixtures   ← symlink
    pages/
      CallRoom.tsx                        ← imports from layout/computeLayout

client-android/
  app/src/main/java/.../layout/
    ComputeLayout.kt                      ← extracted layout algorithm
  app/src/test/
    resources/
      fixtures -> ../../../../tests/layout/fixtures  ← symlink
    java/.../layout/
      LayoutConformanceTest.kt            ← conformance test runner

client-ios/
  Sources/Core/Layout/
    ComputeLayout.swift                   ← extracted layout algorithm
  Tests/
    Fixtures -> ../../tests/layout/fixtures  ← symlink
    LayoutConformanceTests.swift          ← conformance test runner
```

If symlinks cause issues with a platform's build system (e.g., Xcode project references), a build-phase copy script is an acceptable fallback, but the source of truth remains `tests/layout/fixtures/`.

---

## 7. Migration from Current Code

The extraction is mechanical and low-risk:

1. **Move** `computeStageLayout()` and its types (`StageTileSpec`, `StageTileLayout`, `StageRowLayout`) into a dedicated layout module on each platform
2. **Add** a `computeLayout(scene: CallScene) -> LayoutResult` wrapper that handles mode selection and delegates to the appropriate algorithm
3. **Update** call screens to import from the new module instead of defining the function inline
4. **No behavior change** — the same algorithm runs with the same inputs, just from a different file

---

## 8. Comparison with Declarative Spec Approach

| Aspect | This proposal | Declarative spec |
|--------|--------------|-----------------|
| **Adaptive to viewport** | Computes optimal layout for any viewport size | 6 fixed environment buckets |
| **Aspect ratio aware** | Uses actual video aspect ratios per tile | Fixed cover/contain per region |
| **Remote tunability** | Algorithm changes require client release | Could update JSON spec remotely |
| **Spec maintenance** | ~80 lines of algorithm + test fixtures | ~69KB JSON + schema + 3 engines |
| **Extensibility to 8+ participants** | Algorithmic candidate generation | Add more templates |
| **Layout diversity** | All grids use the same optimizing algorithm | Each template can have unique topology |
| **Implementation effort** | Extract existing code + add tests | Rewrite layout system on all 3 platforms |

The declarative approach should be reconsidered when:
- The product needs distinct layout topologies per device class (e.g., phone shows filmstrip, desktop shows sidebar)
- Participant counts exceed what exhaustive candidate generation can handle efficiently (~8-10)
- Layout rules need to be updated without a client release (A/B testing, rapid iteration)

---

## 9. Risks and Mitigations

**Risk: Grid algorithm produces bad layouts for some viewport/aspect-ratio combinations.**
Mitigation: The conformance test matrix includes mixed aspect ratios and edge-case viewports. Bad layouts will surface as fixture mismatches during development.

**Risk: Focus and content modes introduce platform divergence during implementation.**
Mitigation: Focus and content modes are simpler than grid mode (deterministic split, no optimization loop). The conformance fixtures catch divergence before merge.

**Risk: 4+ participant candidate generation is slow.**
Mitigation: Integer partitions of N grow manageably — there are 11 partitions of 5, 22 of 6. For N > 8, cap to configurations with <= 4 rows. The algorithm runs once per layout change, not per frame.

**Risk: Content mode changes user-visible screen-share behavior.**
Mitigation: Content mode introduces a new layout where screen share gets its own large region instead of replacing the local video track. This is an intentional product change. Roll out behind a feature flag if incremental validation is needed. The conformance infrastructure from phases 1-2 ensures the layout algorithm itself is correct before the behavior ships.

---

## 10. Resolved Design Decisions

These were open questions in the initial draft, now resolved:

1. **Local participant placement:** Always a pip in solo, pair, and grid modes. In focus and content modes, the local participant appears in the secondary filmstrip alongside other participants (not as a pip).

2. **Secondary strip algorithm:** Uses a simple equal-division filmstrip, not the grid optimization algorithm. Equal division is more predictable and easier to match across platforms.

3. **Composite camera mode:** Treated identically to world camera mode for layout purposes — it produces a `contentSource` of type `"compositeCamera"` and triggers content mode layout.

4. **Fixture file location:** Single canonical file at `tests/layout/fixtures/layout_conformance_v1.json`. Each platform uses a symlink to reference it. No duplication.

5. **Solo mode:** The main viewport area is reserved for invite controls / room UI. The local participant's self-video is shown only as a pip.

6. **Pip swap in pair mode:** Tapping the pip swaps which participant is dominant (full-screen) and which is the pip. This is a `userPrefs.swappedLocalAndRemote` toggle, already implemented on Android and web.

7. **Dominant fit toggle:** In all modes with a dominant content piece (pair, focus, content), the user can toggle between `"cover"` and `"contain"` for the dominant tile via `userPrefs.dominantFit`. Already implemented for 1:1 on Android and web.

8. **Multiple concurrent content sources:** Last one to start sharing wins the content stage. Previous content sources are dropped.
