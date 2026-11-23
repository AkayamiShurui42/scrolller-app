# Stream Mode V1 Design Specification

Date: 2026-11-23
Project: AkayamiShurui42/scrolller-app
Reference source: fix/reddit-media-search-resilience-20261119
Status: Implementation proposal, not an implemented or device-verified feature.

## User goal

Bring the unfinished Stream layout to reliable, useful feature parity with the existing Fullscreen feed without regressing Search, Random, media playback, filtering, saved/unread classification, the RedGIFs RecyclerView crash repair, or existing on-device data.

## Verified source baseline

- MainActivity.java: Settings -> Layout displays "Fullscreen" and "Stream"; stored values remain "fullscreen" and "grid". Stream uses a one-column GridLayoutManager and GridPostAdapter; Fullscreen uses ViewPager2 and PostPagerAdapter.
- GridPostAdapter.java: every video/GIF streaming tile creates an ExoPlayer via HighQualityPlayerFactory.create, prepares it, and releases on detach/recycle. GIFs start muted; other video starts paused. GIF images are loaded via Glide. A GALLERY displays its first image; badges and a Fullscreen button exist. A cached RedGIFs callback can resolve during bind, with notifications deferred until RecyclerView is not computing layout.
- GridPostAdapter.setPosts clears and repopulates the list and calls notifyDataSetChanged. It has stable IDs based on id.hashCode. In MainActivity, applyLayoutVisibility also calls notifyDataSetChanged on reentering Stream.
- MainActivity stores read classifications through catalogSessionRead / persistViewedPost / ReadHideStore, but markVisiblePostRead explicitly returns when not in Fullscreen. Pager onPageSelected and swipe drive the current read and undo behavior. Stream currently lacks equivalent dwell-based viewing semantics.
- MainActivity provides existing onSave, onComments, onShare, onOpenExternal, onOpenSubreddit, onOpenUser, and read/hide/undo methods. Stream's adapter listener currently exposes only onPostClicked(position).
- Latest inspected Gradle app identity is com.crimson.redditmedia, versionName 3.9.33, versionCode 80, Java 17, compile/target SDK 36, minSDK 24, Media3 1.11.0, Glide 4.16.0, JUnit 4.13.2. This is source configuration, not proof of the APK installed on the user's phone.
- Existing .github/workflows/redview-android-apk.yml runs deterministic search smoke tests, :app:testDebugUnitTest, :app:assembleDebug and strict original-certificate verification before artifact upload. It does not prove Stream interactions on a physical device.

## Scope and priorities

### Milestone A: Stream reliability (mandatory before other feature work)

1. Replace full-list refreshes with identity/content-aware incremental list updates, ensuring stale callbacks cannot overwrite newer feeds.
2. Preserve visible post ID and top offset across append, filter change, returning from Fullscreen, and layout toggles when the same post remains eligible.
3. Bound video playback so Stream cannot create one ExoPlayer for every simultaneously visible video. Keep image/video preview visible during transitions and release/detach resources promptly.
4. Prevent recycled views or delayed RedGIFs/Glide results from showing media belonging to a prior post.
5. Preserve existing one-column full-width aspect-fit presentation, sort, content/read filters, Random sampling, and all Fullscreen behavior.

### Milestone B: Stream media and actions

1. Inline gallery navigation for multi-image posts, with a current-image indicator, without replacing Fullscreen gallery behavior.
2. Stream video play/pause and mute/unmute, muted by default, with reliable first-frame/preview handoff. Animated GIF remains controllable.
3. Save/Unsave, Hide/Restore where applicable, subreddit, author, comments, share, and Fullscreen action availability on the tile, using MainActivity's existing action logic. Do not duplicate Reddit API requests in an adapter.
4. Clear fallback for missing media or unavailable host; never silently show the previous post's image.
5. Tapping a tile opens the correct post in Fullscreen, with the pager positioned by current ID rather than an index that may have shifted.

### Milestone C: Read/unread parity and proof

1. Propose conservative Stream "viewed" eligibility: one post is eligible after at least 1000 ms continuous exposure of at least 60% of the smaller of the item height and the usable viewport height; only actual, rendered media counts. This accommodates very tall images, avoids treating prefetch/bind as viewing, and is a proposed threshold awaiting phone acceptance.
2. Apply existing saved-post exemptions and the existing ReadHideStore and undo path. Do not remove a viewed tile in the middle of scrolling.
3. Persist at the existing navigation/session boundary, or on leaving a confirmed viewed Stream tile as appropriate; avoid duplicate hide persistence.
4. Verify both layouts treat save/read/hidden consistently and preserve account/signature/app data.

### Milestone D: Optional layout density (separate design/approval)

- Preserve one-column full-width Stream as the default.
- Consider 2–4 columns, masonry, spacing, and media-autoplay rules only after A–C are accepted. Those options should be scoped in a separate plan, not slipped into stability work.

## Architecture

MainActivity continues to own feed fetching, filtering, user actions, navigation and read persistence. Stream's adapter owns only rendering/binding, stable row identity, and view lifecycle. A small StreamPlaybackController owns at most one active playback session, using the existing HighQualityPlayerFactory and media family headers; it must never modify Fullscreen's PostPagerAdapter pool. Pure-Java StreamRowSnapshot and StreamViewportPolicy helpers isolate update/visibility decisions for deterministic unit tests.

## Non-goals

- No changes to Search algorithms, Reddit auth/session transport, archive source selection or Random ordering.
- No new content category or filter rules in this milestone.
- No changing applicationId, debug signing certificate, or clearing user data.
- No rewriting Fullscreen player handoff.
- No forced autoplay with audio and no background playback.
- No claimed device performance or API reliability without measured evidence.

## Cross-cutting acceptance

- Source branch/build fingerprint and exact installed APK diagnostic tag recorded at test start.
- No RecyclerView layout-computation adapter-notification crash on cached RedGIFs.
- No duplicated entries, stale preview flashes, disappearing thumbnails or wrong post in Fullscreen.
- At most one active Stream playback session and no retained playback when exiting Stream.
- A 100+ mixed-post rapid-scroll session with repeat forward/back, filter/sort switch, Search -> Stream, and layout switching, with crash-free app and stable list anchor (when anchor remains eligible).
- Images and galleries maintain aspect ratio; videos/GIFs respect controls and audio defaults.
- Saved, hidden and viewed transitions remain consistent with current session/persistence rules and Undo.
- Android unit/smoke tests pass in CI; emulator UI evidence and actual user-phone acceptance reported separately.
- The developer must report regressions or unverified gates explicitly rather than treating Gradle BUILD SUCCESSFUL as full acceptance.

## Risk and review questions

- Viewport dwell threshold is proposed, not previously user-ratified; adjust based on device behavior and desired read semantics.
- Remote providers can fail with 403/451 or bad thumbnails; preserve explicit source diagnostics and retain other result layers.
- Fullscreen's poster handoff and Stream's media resources must remain separate.
- Very tall images and rotation change eligibility and anchor calculations.
- Release or detach media resources without breaking resumed playback, preloading, or silent GIF behavior.
