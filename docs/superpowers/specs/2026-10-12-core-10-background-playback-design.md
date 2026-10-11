# CORE-10 Background Playback Design

## Goal

Keep one Android TextToSpeech playback session advancing from canonical `ReadingPosition` while the Reader activity is backgrounded, locked, recreated, or shown in split screen. Playback must not depend on Compose, pagination, or a frame clock.

## Existing baseline

CORE-09 is partially present in the working tree. `ReaderPlaybackController` already owns `SpeechEngine`, `SpokenChunk`, generation/session validation, canonical chunk progression, and progress persistence. Its tests cover TTS readiness, stale callbacks, pause/resume, speed, sound, and UI-independent `onDone` progression. The remaining defect is ownership: `ReaderViewModel` constructs and releases the controller and `TtsEngine`, so activity/view-model lifecycle changes can stop playback.

## Architecture

`ReaderPlaybackService` is a same-process started and bound foreground service. It constructs exactly one `TtsEngine` and one `ReaderPlaybackController` in `onCreate`, exposes a local Binder with the controller `StateFlow`, and releases all owned resources only when the service ends. `ReaderViewModel` binds, forwards commands, and projects playback state into the existing UI state; it no longer owns or releases TTS.

The service is promoted with `startForegroundService` only after a visible user presses Play. A same-book bind or prepare is idempotent and attaches to the active controller session without resetting position, generation, or speech. A different book replaces the session and invalidates old callbacks.

## Playback data flow

The ViewModel continues loading parsed paragraphs and the saved canonical position. Once the binder is connected it passes the book id, `ReadingSource`, initial position, title, and optional author to the service. The controller remains the only component that turns TTS callbacks into canonical position updates and the next chunk. Its persistence callback writes the existing `ReadingProgress` format through `ProgressStore`.

Playback state flows from the controller through the Binder to the ViewModel. Pagination remains a UI projection: visible Reader instances may react to canonical position, while an absent UI is not required for `onDone -> persist -> nextReadingChunk -> speakChunk`.

## Foreground and media behavior

The manifest declares a non-exported `mediaPlayback` foreground service and the required foreground-service permissions. The service returns `START_NOT_STICKY`, so process recreation does not speak automatically.

A platform `MediaSession` and `Notification.MediaStyle` expose Play, Pause, and Stop. Metadata uses the real book title and optional author. The notification uses a monochrome Reader/book vector, has no fake duration or progress, and opens the existing application task. Session playback state and notification actions reflect the controller snapshot.

Before playing, the service enters foreground promptly and requests `AUDIOFOCUS_GAIN` with media/speech attributes. A denied request leaves playback paused. Focus loss pauses safely and abandons focus; no watchdog, polling, wake lock, Media3 dependency, or second TTS engine is introduced.

## Lifecycle and navigation

Home, lock, app switching, and activity recreation only unbind UI clients; a started service remains owner of the active session. Rebinding to the same book observes current state without `prepare` or restart.

Pause preserves canonical position. Explicit Reader exit and the service Stop action clear playback and stop the service. `onTaskRemoved` performs the same cleanup. Service destruction releases controller/TTS, audio focus, MediaSession, notification, and coroutine scope. System Back and the existing Reader back control share the explicit-exit path. ResumeScreen remains manual and never autoplays.

## Testing

Pure JVM tests cover same-book prepare idempotency and the service coordinator contract: foreground-before-play, audio-focus denial, pause, Stop cleanup, and callback/session preservation. Existing controller tests remain regression coverage for canonical progression, generation, Sound OFF, speed, and pause/resume. Final verification runs the complete unit suite and offline debug build. A connected device, if available, is used for install and practical media/background checks; unperformed long-duration scenarios are reported as unverified.

## Scope boundaries

CORE-11 pagination and visual tracking defects are untouched. Existing UI layout, progress schema, READ/LISTEN sharing, navigation destinations, and dependency set remain unchanged.
