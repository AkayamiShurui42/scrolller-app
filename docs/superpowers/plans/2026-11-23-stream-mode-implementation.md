# Reddit Media Stream Mode V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Deliver a smooth, full-width Stream layout with bounded media resources, correct media/gallery binding, direct post controls and safe read/unread semantics, without affecting Fullscreen, Search or the existing user data.

**Architecture:** Keep MainActivity as the sole owner of feed, action, navigation and read persistence. Keep GridPostAdapter as a presentation layer. Extract testable StreamRowSnapshot and StreamViewportPolicy decisions and a bounded StreamPlaybackController; preserve separate Fullscreen player ownership.

**Tech Stack:** Android Java 17, RecyclerView, GridLayoutManager, AndroidX DiffUtil or AsyncListDiffer, Media3 ExoPlayer/PlayerView 1.11.0, Glide 4.16.0, JUnit 4, GitHub Actions, optional Android emulator/ADB.

**Spec:** docs/superpowers/specs/2026-11-23-stream-mode-design.md

## Global Constraints

- Base execution on fix/reddit-media-search-resilience-20261119 (source diagnostic tag search-resilience-v7-20261119; build.gradle 3.9.33/80 as inspected on 2026-11-23). Recheck branch HEAD and the user's *installed* build before touching code.
- Create a NEW isolated feature branch. Do not merge or overwrite the older fix branches automatically.
- Preserve applicationId com.crimson.redditmedia and existing signing config and certificate SHA-256 191e5f6808941797362d844076f428cee94af60ae2929eecc70fc1c8e4da38b9. Do not clear app storage or change identity.
- Keep Stream's stored layout mode value "grid" and its UI name "Stream"; keep one full-width column by default.
- Preserve the RedgifsResolver cached callback protection: never notify RecyclerView while it is computing a layout, and always recheck current post identity.
- No changes to search, filter semantics, Random reservoir, Reddit transport or Fullscreen's player implementation unless a separately reproduced regression absolutely requires one.
- Work test-first for each behavior. Confirm failing test, make smallest fix, confirm passing test and overall suite, then commit before starting the next task.
- Never call a build or bug fix "verified on device" without emulator/device evidence. Track CI pass, emulator pass and phone pass separately.

## File Responsibilities

- Modify android/app/src/main/java/com/scrolller/adblock/GridPostAdapter.java: Stream row binding, per-row interactions, render and recycle lifecycle, compatibility with incremental list updates.
- Modify android/app/src/main/java/com/scrolller/adblock/MainActivity.java: Stream viewport/anchor coordination, relay existing app actions, save/read/hide integration, layout handoff. Avoid unrelated edits to this very large file.
- Create android/app/src/main/java/com/scrolller/adblock/StreamRowSnapshot.java: immutable comparison snapshot for a RedditPost's stable identity and display-relevant properties.
- Create android/app/src/main/java/com/scrolller/adblock/StreamViewportPolicy.java: deterministic active-row selection and dwell qualification for tall and short posts.
- Create android/app/src/main/java/com/scrolller/adblock/StreamPlaybackController.java: pooled Stream-only player ownership and first-frame poster lifecycle.
- Test android/app/src/test/java/com/scrolller/adblock/StreamRowSnapshotTest.java.
- Test android/app/src/test/java/com/scrolller/adblock/StreamViewportPolicyTest.java.
- Create Android integration tests under android/app/src/androidTest/java/com/scrolller/adblock/ when an emulator runner and fixture injection are in place.
- Extend .github/workflows/redview-android-apk.yml only to run deterministic unit/smoke tests; isolate new emulator checks into .github/workflows/stream-mode-android-qa.yml so signing and release artifact flow is not disrupted.

## Review Focus: Five High-Risk Inputs

1. **Cached RedGIFs resolves during onBind:** no synchronous adapter update while RecyclerView computes layout; pin in Task 2 integration test.
2. **Old Glide/network callback arrives after a filter/search/feed change:** must not modify the new holder/post; pin in Task 2 integration test.
3. **An image is taller than the viewport:** eligibility uses smaller of media/item height and viewport height so full-height viewing is possible; pin in Task 5 unit test.
4. **Video-to-image or layout switch before the first frame:** old frame never flashes, no stray active player or audio; pin in Task 3 integration test.
5. **Saved/hidden item is reclassified while Stream is moving:** no sudden position jump or duplicate read persistence, Undo still works; pin in Task 4/5 integration tests.

---

### Task 1: Baseline + Deterministic Row Updates

**Files:**
- Create android/app/src/main/java/com/scrolller/adblock/StreamRowSnapshot.java
- Create android/app/src/test/java/com/scrolller/adblock/StreamRowSnapshotTest.java
- Modify android/app/src/main/java/com/scrolller/adblock/GridPostAdapter.java
- Modify android/app/src/main/java/com/scrolller/adblock/MainActivity.java

**Interfaces:**
- StreamRowSnapshot.fromPost(RedditPost post) -> StreamRowSnapshot; exposes stable post ID and immutable media/display field values needed by DiffUtil.
- StreamRowSnapshot.hasSameContent(StreamRowSnapshot other) -> boolean.
- GridPostAdapter.setPosts(List<RedditPost> posts) remains caller-compatible; GridPostAdapter.appendPosts(List<RedditPost> posts) remains callable from MainActivity.
- Optional GridPostAdapter.indexOfPostId(String id) -> int to restore layout anchor by stable identity.

- [ ] Capture source HEAD, diagnostic tag, a test fixture with >=100 mixed media posts, Stream screenshots, and a baseline logcat/gfxinfo scroll session. Include one test with Search results to avoid conflating Stream with source failures.
- [ ] Write StreamRowSnapshotTest: unchanged ID/content compares equal; same ID with changed resolved RedGIFs URL or Saved state compares changed; distinct IDs are different even if URLs match; null poster is safe; no mutable RedditPost reference can retroactively rewrite the prior snapshot.
- [ ] Run cd android && ./gradlew --no-daemon :app:testDebugUnitTest to demonstrate relevant new tests fail before implementation.
- [ ] Implement StreamRowSnapshot and make GridPostAdapter use identity/content-aware updates (DiffUtil/AsyncListDiffer) with immutable snapshots and latest-generation guards. Do not call notifyDataSetChanged for every replacement. Preserve existing post list semantics and stable IDs, with a collision-safe ID strategy or explicitly remove unsafe stable-ID assumptions.
- [ ] Implement anchored scroll restoration: capture first meaningfully visible post ID plus decorated top offset before replacement or leaving Stream, then restore that ID when still eligible; if absent, fall back to nearest valid position. Do not assume pager index is the grid anchor.
- [ ] Re-run :app:testDebugUnitTest; add instrumentation assertion that append/filter/layout switching does not duplicate rows or jump from the same eligible anchor.
- [ ] Commit Task 1 separately with a message such as "fix(stream): update rows incrementally and preserve anchor".

### Task 2: Viewholder/Glide/RedGIFs Recycle Safety

**Files:**
- Modify android/app/src/main/java/com/scrolller/adblock/GridPostAdapter.java
- Test android/app/src/androidTest/java/com/scrolller/adblock/StreamMediaBindingTest.java

**Interfaces:**
- Each Holder receives a binding generation token (post ID + monotonically increasing bind revision).
- Each delayed media callback checks the currently bound ID/revision; gallery media and Glide request targets are cancelled/cleared on recycle.

- [ ] Write failing integration cases: recycle image A into image B before image A loads; RedGIFs cached resolve during bind; RedGIFs network miss completes after the post was filtered out; no stale media shown and no IllegalStateException.
- [ ] Run emulator integration tests and record expected failure evidence; if Android emulator unavailable, stop at unit/source evidence and keep the integration gate explicitly pending.
- [ ] Guard delayed callbacks with holder identity and generation; cancel Glide targets and video overlays on bind/recycle; clear old views without suppressing the current poster. Keep cached RedGIFs notify deferral and recalculate current post index.
- [ ] Re-run integration tests and rapid A/B recycling (forward/back), then all local unit tests.
- [ ] Commit Task 2 with a dedicated diff and review.

### Task 3: Bounded Stream Playback and First-Frame Handoff

**Files:**
- Create android/app/src/main/java/com/scrolller/adblock/StreamPlaybackController.java
- Modify android/app/src/main/java/com/scrolller/adblock/GridPostAdapter.java
- Modify android/app/src/main/java/com/scrolller/adblock/MainActivity.java
- Test android/app/src/test/java/com/scrolller/adblock/StreamViewportPolicyTest.java (active-row selection)
- Test android/app/src/androidTest/java/com/scrolller/adblock/StreamPlaybackTest.java

**Interfaces:**
- StreamPlaybackController.activate(String postId, String url, boolean redgifs, PlayerView view, ImageView cover, boolean playWhenReady) -> void.
- StreamPlaybackController.deactivate(String postId) -> void; StreamPlaybackController.releaseAll() -> void.
- StreamViewportPolicy.selectActiveIndex(List<VisibleItem> items, int viewportTop, int viewportBottom) -> int. VisibleItem records row index and decorated top/bottom bounds.
- Only the selected visible row is eligible for playback. At most one Stream player is active; regular videos remain paused until a user action, while GIF behavior remains muted by default.

- [ ] Write failing active-row unit cases: one centered row, two partially visible rows, very tall row, none eligible, edge/tie behavior.
- [ ] Write failing device cases: 20 consecutive video tiles never produce more than one active Stream playback; video-to-image does not retain old frame; fast Stream -> Fullscreen -> Stream does not leak/sound-play; source-specific RedGIFs headers preserved; first video starts normally.
- [ ] Refactor the Grid adapter so a Holder no longer creates its own ExoPlayer. Controller owns a bounded pool using existing HighQualityPlayerFactory; it attaches a correctly prepared source only to the selected Holder and shows the incoming preview until its rendered first-frame callback matches the current post.
- [ ] Keep PlayerView alpha at normal value; do not repeat the earlier alpha-zero experiment that caused a phone-reported first-video failure. Unbind/release on detach, background, layout exit or error.
- [ ] Run unit tests, integration tests and an emulator video/GIF/RedGIFs mixture; collect player counts/meminfo and frame timings.
- [ ] Commit Task 3 separately.

### Task 4: Gallery Navigation and Direct Post Actions

**Files:**
- Modify android/app/src/main/java/com/scrolller/adblock/GridPostAdapter.java
- Modify android/app/src/main/java/com/scrolller/adblock/MainActivity.java
- Test android/app/src/androidTest/java/com/scrolller/adblock/StreamActionsTest.java

**Interfaces:**
- Expand GridPostAdapter.Listener with explicit post-identity-based actions: onOpenFullscreen(String postId), onSave(RedditPost post), onHide(RedditPost post), onComments(RedditPost post), onShare(RedditPost post), onOpenSubreddit(String name), onOpenUser(RedditPost post).
- MainActivity implements these by delegating to its existing methods and authenticated Reddit transport; no new API calls in GridPostAdapter.
- Gallery controls use the existing imageUrls list, change only within the bound tile, and keep a 1-based position indicator.

- [ ] Write failing integration cases: gallery next/previous maintains correct media/indicator; rapid recycle does not restore gallery A to post B; Save/Unsave and Hide/Undo operate on correct post ID; comments, author, subreddit, share dispatch correctly; opening Fullscreen after feed reorder still lands on matching post ID.
- [ ] Implement inline gallery index state keyed by post ID, and keep image aspect-fit presentation unchanged.
- [ ] Add compact tile controls without making every tap trigger Fullscreen and without blocking vertical scrolling. Keep existing saved/login-required behavior, error feedback and read/hide exclusions.
- [ ] Convert Fullscreen handoff to resolve the current post ID against the present filtered list at click time, rather than relying only on stale adapter index.
- [ ] Run integration tests and re-test Stream -> Fullscreen -> Stream navigation; verify the same post ID and prior Stream anchor.
- [ ] Commit Task 4 separately.

### Task 5: Stream Viewed/Unread Eligibility and Undo

**Files:**
- Create/modify android/app/src/main/java/com/scrolller/adblock/StreamViewportPolicy.java
- Modify android/app/src/main/java/com/scrolller/adblock/MainActivity.java
- Test android/app/src/test/java/com/scrolller/adblock/StreamViewportPolicyTest.java
- Test android/app/src/androidTest/java/com/scrolller/adblock/StreamReadStateTest.java

**Interfaces:**
- StreamViewportPolicy.qualifies(int visiblePx, int itemHeightPx, int viewportHeightPx, long continuousVisibleMs, boolean mediaRendered) -> boolean.
- Proposed threshold: visiblePx / min(itemHeightPx, viewportHeightPx) >= 0.60 and uninterrupted visibility >= 1000 ms and mediaRendered=true. Validate empty/nonpositive dimensions safely.
- MainActivity handles qualified post ID by reusing catalogSessionRead / persistViewedPost / showReadUndoHud and the existing saved/hidden rules. Never classify in onBindViewHolder.

- [ ] Write failing unit tests: tall image occupying 70% of viewport qualifies after 1000 ms; 50% does not; 999 ms does not; fast scroll interruption resets timer; unloaded/failed media does not qualify; malformed dimensions are ineligible.
- [ ] Write failing integration test: long Stream scroll marks only actually rendered and qualified posts; a saved post is exempt; accepted unread entries remain stable in the active Stream list; undo reverses classification; repeat exposure does not create duplicate hide records.
- [ ] Implement viewport dwell tracker driven by visible position and scroll state, with lifecycle cleanup and post-ID keyed state. Consult existing ReadHideStore behavior before choosing whether to persist at tile exit or session boundary; do not silently alter Fullscreen semantics.
- [ ] Run unit and instrumented tests. Record phone acceptance of the proposed threshold, and revise it explicitly if the user wants a different read policy.
- [ ] Commit Task 5 separately.

### Task 6: CI Coverage, Performance Comparison and Acceptance

**Files:**
- Create .github/workflows/stream-mode-android-qa.yml (separate optional emulator workflow)
- Optionally modify .github/workflows/redview-android-apk.yml only to include deterministic unit/smoke checks
- Test android/app/src/androidTest/java/com/scrolller/adblock/StreamModeSmokeTest.java
- Add a dated test-results Markdown file under docs/superpowers/reviews/ with baseline/final evidence

**Interfaces:**
- A fixture-driven Stream smoke test exercises 100+ mixed item transitions with stable IDs and no real-network dependency; a separate live-provider smoke test reports provider HTTP failures as external conditions.

- [ ] Write a failing StreamModeSmokeTest and agree on required fixtures: image, tall image, GIF, HLS/MP4, RedGIFs cached and unresolved, gallery, missing poster, bad URL, saved and hidden post.
- [ ] Add emulator test invocation on supported API levels using an available Android SDK runner, with UI hierarchy/screenshots and logcat on failure. Do not require an emulator that this environment cannot actually provide.
- [ ] Run cd android && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug; confirm all tests and build steps, then run existing debug certificate verification exactly as CI does.
- [ ] Compare baseline/final gfxinfo, Perfetto when available, media player instances and heap growth across repeated 100+ item scrolls. Report device, refresh rate, build tag, measurement methodology, duration, and pass/fail per test, not unsupported universal performance claims.
- [ ] Device matrix: emulator API 35/36 if available; user's Android 17 phone is the authoritative real-device playback/120 Hz gate. Test rapid swipes, Stream/Fullscreen, portrait/landscape, keyboard/Search, Random refill, filter changes, app background/resume, connectivity loss, and RedGIFs provider errors.
- [ ] Verify no repeated content, stale images, old-frame flash, new RecyclerView exception or ANR in the current process. Review historical OS crashes by timestamp/PID/build tag rather than treating old reports as new failures.
- [ ] Publish source-only changes and CI artifact on a separate branch; preserve signing and app data; do not merge or pronounce phone acceptance until actual phone test evidence is supplied.
- [ ] Record results and unresolved issues; obtain a fresh review before merging or starting any optional 2–4-column/masonry work.

## Release Gates

- Gate A: Tasks 1–3 pass source/unit and relevant emulator tests; Stream can scroll and show videos without unbounded player creation, stale frame, duplicates or a layout-computation crash.
- Gate B: Task 4 has correct galleries and actions by post ID, with no lost navigation context.
- Gate C: Task 5 has read/undo parity with accepted dwell behavior and saved exemptions.
- Gate D: Task 6 CI/build/sign success and phone QA, with evidence attached. A compiled APK alone is not a completed feature.

## Separate Follow-on Plan

Only after Stream V1 passes Gates A–D, prepare a new design for optional 1–4 columns, staggered/masonry full-image resizing, spacing and video autoplay rules. Keep one-column full-width as default, and do not implement this follow-on without a separate decision.

## Execution Handoff

- Follow the plan task by task in a new isolated branch from the checked source ref.
- Recommended implementation method: native execution with a fresh code-review gate per milestone, since the UI and media lifecycle share mutable list/view state and require device verification. Parallel subagents are useful for independent tests/review, but should not concurrently edit MainActivity/GridPostAdapter.
- Before execution: confirm this design and the proposed Stream read threshold. No production code was changed while drafting this plan.
