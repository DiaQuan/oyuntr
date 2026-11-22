# PS4 Remote Play integration design

## Goal

Receive a PS4 Remote Play video stream inside OyunTR, then pass frames to OyunTR-owned OCR, translation, and dubbing components. The product UI, storage, processing pipeline, and Android integration remain original OyunTR work. Nino and CouchSubs are not code dependencies or source material.

## Proposed boundary

```text
Android TV UI
    ↓ connect / disconnect / register / input
OyunTR RemotePlayGateway (Kotlin contract)
    ↓ OyunTR-owned JNI session and registration adapter
chiaki-ng public C session API (AGPL-3.0; separate third-party component)
    ↓ compressed video samples + audio sink + session events
OyunTR-owned decoder and media pipeline → OCR → Turkish translation → optional AI dubbing
```

The adapter owns native sessions on a worker thread, translates events into a small OyunTR event model, and copies video samples into Kotlin during the native callback. Android UI code does not depend on chiaki-ng types. `AndroidConsoleCredentialVault` stores registration key material with Android Keystore AES-GCM encryption and binds ciphertext to the console ID; never write credentials to logs.

## Verified upstream facts

- chiaki-ng's repository identifies the project as AGPL-3.0. Any decision to distribute a linked/bundled build needs an explicit license-compliance plan, including corresponding source and notices as applicable. See [chiaki-ng repository](https://github.com/chiaki-ng/chiaki-ng) and [project license overview](https://chiaki-ng.github.io/chiaki-ng/).
- The reviewed pinned C header exposes `chiaki_session_init`, `chiaki_session_start`, `chiaki_session_stop`, `chiaki_session_join`, `chiaki_session_fini`, `chiaki_session_set_controller_state`, and setters for event, video-sample, and audio-sink callbacks. These are low-level C APIs, not an Android Kotlin SDK. See [`session.h` at the pinned revision](https://github.com/chiaki-ng/chiaki-ng/blob/43728af2e639fecb8a7af1cd68c29820a40de973/lib/include/chiaki/session.h).
- For a local PS4 session, `ChiakiConnectInfo` carries the console host, 16-byte registration key, 16-byte `morning` value, video profile, and PS4/PS5 flag. The current session initializer selects its PS4 target internally. OyunTR now obtains the registration values through its local registration screen and saves them encrypted.
- The same pinned public headers expose `chiaki_regist_start`, `chiaki_regist_stop`, and `chiaki_regist_fini`, with a completion callback returning a `ChiakiRegisteredHost`. OyunTR now has a manual local registration screen that supplies a host, console PIN, pairing PIN and 8-byte PSN account ID to this API, then saves the returned 16-byte session values in the credential vault. Automatic console discovery and PSN account-ID retrieval are still absent. See [`regist.h` at the pinned revision](https://github.com/chiaki-ng/chiaki-ng/blob/43728af2e639fecb8a7af1cd68c29820a40de973/lib/include/chiaki/regist.h).
- `ChiakiVideoSampleCallback` receives a byte buffer, its size, loss/recovery metadata, and user data; its boolean result reports whether the sample was pushed into the decoder. That is compressed decoder input, not an already-decoded bitmap or RGBA frame. OyunTR copies the bytes into Kotlin and feeds its H.264 `MediaCodec` decoder; actual sample compatibility still needs a device test.
- chiaki-ng's Android build adds a dedicated native JNI library and links it against `chiaki-lib`; it disables the desktop GUI and configures Android native dependencies separately. Its Android frontend also uses the NDK MediaCodec APIs for video output, but that frontend implementation is not copied into OyunTR. See the [upstream Android CMake target](https://github.com/chiaki-ng/chiaki-ng/blob/main/android/app/CMakeLists.txt).
- Upstream Android config enables `CHIAKI_ENABLE_ANDROID` through CMake and specifies compile SDK 35, min SDK 24, NDK `28.2.13676358`, CMake 3.22.1, and Java/Kotlin 17. These are the current upstream app's build settings, not a compatibility promise for OyunTR. See [`android/app/build.gradle`](https://github.com/chiaki-ng/chiaki-ng/blob/main/android/app/build.gradle).
- Upstream's Android build disables some features and uses external native dependencies; the starter CMake configures third-party targets as well as `chiaki-lib`, but this has not been run. The integration still needs a validated decoder, audio, crypto, and transitive dependency build.
- For manual remote networking, upstream documents PS4 UDP 987 for discovery and UDP/TCP 9295 plus UDP 9296–9297 for Remote Play. These are connection requirements, not a complete registration or protocol implementation. See [remote connection docs](https://chiaki-ng.github.io/chiaki-ng/setup/remoteconnection/).
- Android PSN/hole-punch support has platform-specific work beyond the basic session API: the upstream tracker has an open PR for Android CA trust, certificate-chain recovery, default-route address selection, and socket polling. Treat Android-over-PSN as a later milestone and pin/check upstream status before relying on it. See [PR #854](https://github.com/chiaki-ng/chiaki-ng/pull/854) and [issue #853](https://github.com/chiaki-ng/chiaki-ng/issues/853).

## Integration decisions still needed

1. The starter pins chiaki-ng revision `43728af2e639fecb8a7af1cd68c29820a40de973` through CMake FetchContent. Before distributing a build, audit that exact revision's `COPYING`, `LICENSES`, build scripts, and bundled dependencies.
2. Choose a license/compliance model for OyunTR distribution. Do not assume an AGPL native library can be combined with a differently licensed closed application without consequences.
3. Validate the H.264 `MediaCodec` path against actual callback samples, including NAL framing, codec configuration, recovery after dropped frames, timestamps, output format, and Surface handoff.
4. Add PS4 discovery and a user-friendly source for the PSN Account ID; test registration cancellation, PIN errors, sleep/wake, and failure recovery.
5. Validate minimum Android TV device/API level and ABI matrix with an actual NDK build and target hardware.

## Implemented in this milestone

- `ChiakiNativeGateway` serializes native session operations on a worker, validates host and 16-byte registration fields, maps lifecycle/PIN events, forwards controller state and PIN, and waits for teardown completion.
- `chiaki_session_jni.cpp` is OyunTR-authored adapter code for session init/start/stop/join/fini, events, controller input, and PIN submission. It calls only the reviewed public C API; no upstream Android UI or JNI source is included.
- The pinned chiaki-ng audio sink now feeds its public Opus decoder to OyunTR PCM callbacks, which write to Android `AudioTrack`. Opus is fetched and built as a pinned static dependency by OyunTR's CMake `FindOpus.cmake`. These source changes are not compiled; network jitter, output latency, and device support still need tests.
- Android TV key events and joystick/gamepad axes now create `ControllerState` updates and call `RemotePlayCoordinator.sendControllerState` while the session is connected. No paired physical controller test has been run.
- The same JNI bridge calls chiaki-ng's public registration start/stop/finalize API and returns registration bytes to OyunTR for encrypted storage.
- `RemotePlayActivity` connects manual local pairing, secure credential retrieval, H.264 decoder output, `PixelCopy` frame sampling, OCR, translation and the subtitle overlay in source. The full path is not yet built or tested.
- `AndroidConsoleCredentialVault` encrypts the two 16-byte local-registration values using a Keystore AES-GCM key, authenticates the console ID, and supports load/remove. It does not perform console registration or store a PSN password.
- `CMakeLists.txt` pins the public library source revision and configures upstream third-party and shared-library targets without adding the upstream Android UI target. This recipe is not yet built or verified and may need adaptation to the pinned upstream build graph.
- Controller state includes the 16 PS button bits, signed stick axes, and 0–255 analog trigger values mapped to fields in chiaki-ng's public header. See [`controller.h` at the pinned revision](https://github.com/chiaki-ng/chiaki-ng/blob/43728af2e639fecb8a7af1cd68c29820a40de973/lib/include/chiaki/controller.h).

## Not verified or not implemented

The manual local registration UI, session JNI adapter, H.264 MediaCodec decoder, Opus/PCM audio output, controller input path, SurfaceView frame capture, OCR, Turkish translation and overlay are wired together in source. None has been compiled or run against a PS4/Android TV in this environment. Automatic console discovery, ordinary-user PSN account-ID retrieval, PSN internet/hole-punch connectivity, a released APK, and an actual working connection remain unimplemented or unverified. Do not treat source wiring as a confirmed usable stream.

## Product-shell work in this workspace

- A native Android TV home screen, navigation-focusable actions, Gradle project files, and a Leanback launcher entry. Its PS4 action opens the local setup/player screen; this does not mean a successful connection has been verified.
- `RemotePlayGateway` defines an OyunTR-owned provider boundary for connection lifecycle, normalized controller input, state notifications, decoded video output, and failures. `RemotePlayCoordinator` validates transitions, rejects stale callbacks, and waits for asynchronous teardown completion.
- OyunTR-owned provider interfaces define OCR output, Turkish translation requests, speech synthesis requests, and a bounded in-memory translation cache.
- The subtitle module now has a bundled Latin-script ML Kit recognizer, per-line language tag extraction, lower-screen subtitle grouping, game-aware cached Turkish translation, overlap dropping to avoid an OCR backlog, a two-line Android overlay and a SurfaceView capture source. Google documents the bundled OCR artifact and on-demand translation model API: [Text Recognition for Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [Translation for Android](https://developers.google.com/ml-kit/language/translation/android).
- `RemotePlayActivity` connects local pairing, the credential vault, session coordinator, decoder callback and live bitmap subtitle pipeline in source. The user enters the PS4 host and pairing PIN plus the 8-byte PSN Account ID as Base64 or 16-character hexadecimal. Automatic account-ID retrieval and discovery still need work.
- `AndroidVideoDecoder` queues H.264 access units into Android `MediaCodec`; `SurfaceSubtitleFrameSource` samples the `SurfaceView` with `PixelCopy` at 2 frames per second to keep OCR load bounded. The entire native/Android path remains unbuilt and untested.
