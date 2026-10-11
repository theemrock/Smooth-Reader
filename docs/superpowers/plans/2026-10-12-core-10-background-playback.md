# CORE-10 Background Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the single Reader TTS session into a started and bound media playback foreground service that survives UI lifecycle changes.

**Architecture:** A pure coordinator applies focus/foreground lifecycle policy around the existing controller. `ReaderPlaybackService` owns the coordinator, controller, TTS adapter, MediaSession, and notification; `ReaderViewModel` binds and projects its state.

**Tech Stack:** Kotlin, Android Service/Local Binder, platform MediaSession and Notification.MediaStyle, coroutines/StateFlow, JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-12-core-10-background-playback-design.md`

## Global Constraints

- Work only in `C:\Projects\SmoothReader` on the existing `main` branch; preserve all pre-existing working-tree changes and the commit ahead of `origin/main`.
- Keep one `TtsEngine`, one `ReaderPlaybackController`, existing `ReadingPosition(paragraphIndex, characterOffset)`, and progress format.
- Use platform MediaSession/MediaStyle; do not add Media3, wake locks, polling, keepalive, watchdogs, or another TTS engine.
- Return `START_NOT_STICKY`; never auto-play after process death.
- Do not change CORE-11 pagination or visual tracking behavior and do not redesign Reader UI.
- Do not push commits.

## Review Focus

- A same-book rebind during active speech must not stop, seek, prepare, or create another session.
- Audio-focus denial or loss must leave controller and media state paused rather than speaking without focus.
- Stale callbacks from a replaced book/session must not advance or persist the new book.
- Explicit Reader exit, notification Stop, and task removal must release foreground/media/TTS resources exactly once.
- Activity recreation while playing must retain service ownership and expose the active snapshot to the new ViewModel.

---

### Task 1: Idempotent controller session contract

**Files:**
- Modify: `app/src/test/java/com/theemrock/smoothreader/ReaderPlaybackControllerTest.kt`
- Modify: `app/src/main/java/com/theemrock/smoothreader/reader_playback_controller.kt`

**Interfaces:**
- Consumes: existing `prepare(bookId, source, initialPosition)` and `PlaybackSnapshot`.
- Produces: `prepare` is a no-op for the already prepared book and returns `Boolean` indicating whether a new session was created; `hasSession(bookId: String): Boolean`.

- [ ] Add tests `preparingSameBookKeepsActiveSessionAndPosition` and `preparingDifferentBookInvalidatesOldCallback` with literal expected positions and speech counts.
- [ ] Run `:app:testDebugUnitTest --tests '*ReaderPlaybackControllerTest'` and confirm the new contract fails for the expected reason.
- [ ] Implement the minimal idempotent prepare/session query behavior without changing chunk progression.
- [ ] Re-run the targeted controller tests and confirm they pass.
- [ ] Commit only the controller and its tests.

### Task 2: Testable playback lifecycle coordinator

**Files:**
- Create: `app/src/main/java/com/theemrock/smoothreader/reader_playback_coordinator.kt`
- Create: `app/src/test/java/com/theemrock/smoothreader/ReaderPlaybackCoordinatorTest.kt`

**Interfaces:**
- Consumes: `ReaderPlaybackController.play`, `pause`, `clear`, `state`.
- Produces: `PlaybackAudioFocus`, `PlaybackForeground`, and `ReaderPlaybackCoordinator` methods `play()`, `pause(reason)`, `stop(reason)`, `onAudioFocusLost(reason)`.

- [ ] Add tests proving foreground promotion precedes focus/play, denied focus does not play, pause abandons focus while preserving session, and Stop clears playback and requests service shutdown.
- [ ] Run the coordinator test class and confirm failure because the coordinator contract is absent.
- [ ] Implement the smallest pure Kotlin coordinator satisfying the tests.
- [ ] Run controller and coordinator tests and confirm all pass.
- [ ] Commit coordinator and tests.

### Task 3: Foreground service, MediaSession, notification, and audio focus

**Files:**
- Create: `app/src/main/java/com/theemrock/smoothreader/reader_playback_service.kt`
- Create: `app/src/main/res/drawable/ic_reader_notification.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Task 1 controller session contract and Task 2 coordinator.
- Produces: `ReaderPlaybackService.LocalBinder` exposing `state`, `prepareSession`, playback commands, and session identity; intent actions Play/Pause/Stop; Android media lifecycle.

- [ ] Add any pure contract assertions needed to coordinator tests for MediaSession callback semantics before Android wiring.
- [ ] Run those tests and confirm the added behavior fails first.
- [ ] Implement service ownership, local Binder, `START_NOT_STICKY`, `mediaPlayback` foreground promotion, platform MediaSession/MediaStyle notification, PendingIntents, and AudioFocusRequest.
- [ ] Add manifest permissions/service declaration and monochrome notification icon.
- [ ] Run targeted unit tests and `:app:compileDebugKotlin`; fix only CORE-10 compile failures.
- [ ] Commit service, manifest, icon, and directly related tests.

### Task 4: ViewModel binding, rebind, exit, and full verification

**Files:**
- Modify: `app/src/main/java/com/theemrock/smoothreader/reader_viewmodel.kt`
- Modify: `app/src/main/java/com/theemrock/smoothreader/reader_screen.kt`
- Modify: `app/src/main/java/com/theemrock/smoothreader/app_nav_host.kt` only if needed for notification return/explicit exit.
- Modify: `app/src/main/java/com/theemrock/smoothreader/MainActivity.kt` only if needed for notification return.

**Interfaces:**
- Consumes: `ReaderPlaybackService.LocalBinder` and service intent actions.
- Produces: ViewModel bind/unbind and command forwarding; explicit Reader exit cleanup; same-book rebind without prepare/restart.

- [ ] Add focused JVM tests for any extracted connection/session decision logic, especially same-book rebind and pending user Play.
- [ ] Run the focused tests and confirm failure for the missing behavior.
- [ ] Replace ViewModel controller/TTS ownership with service binding while retaining existing UI projection and pagination boundaries.
- [ ] Route Play/Pause/speed/sound/manual seek/exit commands to the binder/service; make `onCleared` unbind only.
- [ ] Wire system Back and the existing Reader back control to explicit playback cleanup without changing layout.
- [ ] Run all unit tests with `:app:testDebugUnitTest` and build with `:app:assembleDebug --offline --console=plain` using Android Studio JBR.
- [ ] If ADB reports a connected device, install and run applicable background/media checks; report every scenario not actually exercised.
- [ ] Commit the ViewModel/UI integration and verified fixes.

### Task 5: Final review and delivery evidence

**Files:**
- Review all CORE-10 changes and the task ledger; modify only for Critical/Important findings with RED-GREEN coverage.

**Interfaces:**
- Consumes: Tasks 1-4 and the specification.
- Produces: reviewed commits, fresh full verification, final Git and Context Mode measurements.

- [ ] Build a whole-branch review package from the pre-CORE-10 commit through HEAD and request one fresh code review.
- [ ] Re-grade findings; fix Critical/Important items with failing tests first and record deferred Minor findings.
- [ ] Run a fresh complete unit suite and offline debug build.
- [ ] Capture final Git status, commit hashes, ADB evidence, and `ctx_stats`; compare with the 7.2 KB / 0-token starting measurement.
