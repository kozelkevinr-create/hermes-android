# Matrix Sessions — Design

**Date:** 2026-10-19
**Status:** Phase 0 (spike), Phase 1a (account, client manager), Phase 2 (room list) and Phase 3 (read-only timeline, text sending, edits, read receipts, images) implemented; Phase 1b (release ABI splits) and phases 4-7 planned
**Branch:** `feature/matrix-sessions`

## Goal

The **Chats tab becomes a Matrix room list**, modelled on Element X: every room is a persistent chat with the Hermes bot. The user starts a new conversation either by creating a new room or by sending `/new` inside an existing one. The existing gateway session list moves to **Settings → Sessions** (`gateway_sessions`), because Mission Control and cron still rely on gateway sessions. The user chose this over an in-tab Matrix | Gateway toggle, which was the earlier plan and has been removed.

Encrypted and unencrypted rooms must both work, so the client uses the matrix-rust-sdk Kotlin bindings (`org.matrix.rustcomponents:sdk-android`, Apache-2.0) rather than a hand-rolled REST client.

## Server constraints (generic)

- Self-hosted Synapse with native sliding sync advertised (`org.matrix.simplified_msc3575`). Password login only.
- LAN-only. Plain HTTP and HTTPS are both served; HTTPS sits behind a reverse proxy with a **private self-signed CA**. The CA certificate must never be bundled in the APK or committed; the user imports it on the device.
- The bot is an ordinary Matrix user. Direct rooms (two members or fewer) are answered without an @mention. The bot supports E2EE (optional mode), so both encrypted and plain DMs are valid.

## Phases

| # | Phase | Scope |
|---|-------|-------|
| 0 | Spike | SDK dependency, API notes, TLS/cleartext findings, debug-only test screen (this doc) |
| 1 | Account setup | Homeserver, password login, bot MXID, CA import (always with `disableBuiltInRootCertificates()`), encrypted session storage, store passphrase in Android Keystore |
| 2 | Room list (done) | Chats tab is the Matrix room list only; gateway sessions under Settings → Sessions |
| 3 | Room timeline | Paginate, live diffs, edits, send text/images, read receipts |
| 4 | Command wrapper | Palette of Hermes messaging commands (`/new /stop /model /usage /retry /undo /title /status /help /personality /approve /deny` ...); native New session -> `/new`, model picker -> `/model`, stop -> `/stop`; approval prompts get approve / always / deny buttons that send the matching reactions |
| 5 | New-room sheet | Encrypted toggle (default from a setting); create DM + invite bot |
| 6 | E2EE UX | Recovery key, SAS emoji verification, unable-to-decrypt states |
| 7 | Background | Background sync + notifications (reuse `GatewayConnectionService`; UnifiedPush later) |

## Phase 0 results

### SDK version and build

- Chosen: **26.09.28** (Maven Central lists 26.10.15 as newest; 26.09.28 is about three weeks old). Compiles and packages with the current toolchain (Kotlin 2.3.10, AGP 9.1, compileSdk 37) with no JNA / coroutines / stdlib conflicts. Its POM brings kotlin-stdlib 1.9.25, coroutines 1.7.3 and JNA 5.18.1 (aar); Gradle's resolution picks the app's newer versions.
- **Extra required dependency:** the SDK's native TLS uses `rustls-platform-verifier`, whose JVM half (`org.rustls:rustls-platform-verifier:0.1.1`) is *not* in the SDK AAR. It is served from the crate's own Maven archive, added to `settings.gradle.kts` restricted to group `org.rustls`. The app must also define the JNI shim `org.matrix.rustsdk.Android.init(Context)` (debug source set) and call it before `initPlatform`.
- The SDK and verifier are `debugImplementation` only; release builds are unchanged.

### APK size (`:app:assembleDebug`)

| Build | Size |
|-------|------|
| Before SDK | 28.9 MB |
| SDK, all 4 ABIs (arm64-v8a, armeabi-v7a, x86, x86_64) | 282 MB |
| SDK, debug ABI filter arm64-v8a + x86_64 | 164.7 MB |

`libmatrix_sdk_ffi.so` per ABI (uncompressed / gzip): arm64-v8a 64 MB / 23.4 MB, x86_64 70 MB / 25.6 MB, armeabi-v7a 42 MB, x86 74 MB. A single-ABI debug APK is therefore about 93 MB (arm64) or 99 MB (x86_64); a compressed release split is roughly 28.9 MB + 23 MB (arm64). Phase 1 must ship per-ABI splits or an app bundle (and decide whether to drop 32-bit ABIs) before the SDK enters release builds. The debug build type currently carries `ndk.abiFilters` for the two 64-bit ABIs.

### Real API (from the generated UniFFI bindings, 26.09.28)

All types are in `org.matrix.rustcomponents.sdk` unless noted. Methods marked `suspend` are Kotlin suspend functions (UniFFI async); the rest are blocking-fast calls. Listeners are callback interfaces invoked on Rust threads, so UI state must be marshalled. Every object implements `Disposable`/`AutoCloseable` (`destroy()` / `close()`); a `TaskHandle` returned from a subscription must be held (and `cancel()`ed) or the subscription dies with the GC.

**Platform init (top-level):** `initPlatform(config: TracingConfiguration, useLightweightTokioRuntime: Boolean)`; `TracingConfiguration(logLevel: LogLevel, traceLogPacks, extraTargets, writeToStdoutOrSystem, writeToFiles: TracingFileConfiguration?, sentryConfig: SentryConfig?)`. Keep `LogLevel.INFO`; TRACE logs HTTP bodies.

**ClientBuilder** (`ClientBuilder()` no-arg; each setter returns a new `ClientBuilder`; `suspend fun build(): Client`):
- `homeserverUrl(url: String)` (never does well-known lookup), `serverName(String)`, `serverNameOrHomeserverUrl(String)`, `serverNameFromUserId(String)`; mutually exclusive, last one wins. `disableWellKnownLookup(Boolean)`.
- `sqliteStore(config: SqliteStoreBuilder)` with `SqliteStoreBuilder(dataPath: String, cachePath: String).passphrase(String?)` (also `.key(ByteArray?)`, `.highEntropyPassphrase(ByteArray?, Base64Variant)`); legacy `sessionPaths(dataPath, cachePath)`; `inMemoryStore()`. Paths must be unique per session.
- `addRootCertificates(certificates: List<ByteArray>)` (each entry a DER or PEM certificate; the native code tries DER, then PEM; a multi-cert PEM bundle must be split first, see `CertificateBytes`); `disableBuiltInRootCertificates()`; `disableSslVerification()` (do not use).
- `userAgent(String)`, `proxy(String)`, `requestConfig(RequestConfig)`, `setSessionDelegate(ClientSessionDelegate)`, `slidingSyncVersionBuilder(...)`, `autoEnableCrossSigning(Boolean)`, `autoEnableBackups(Boolean)`, `threadsEnabled(Boolean, Boolean)`.
- **The builder must set the sliding-sync version.** Use `slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)` (variants `NONE`, `NATIVE`, `DISCOVER_NATIVE`; Synapse serves native MSC4186). Without it, starting the room list fails with "Sliding sync version is missing". It is a builder setting, so a restored session needs it too.

**Login / session:** `suspend Client.login(username, password, initialDeviceName: String?, deviceId: String?)`; `loginWithEmail(...)`. Export with `Client.session(): Session`; `Session(accessToken, refreshToken?, userId, deviceId, homeserverUrl, oauthData?, slidingSyncVersion)`. Restore by building a client with the same store paths/passphrase/homeserver and `suspend Client.restoreSession(session)` (or `restoreSessionWith(session, RoomLoadSettings)`). `setSessionDelegate` is the hook to persist refreshed tokens. `suspend logout()`.

**Sync:** `client.syncService(): SyncServiceBuilder` -> `suspend finish(): SyncService`; `SyncService.start()` / `stop()` (suspend), `state(SyncServiceStateObserver): TaskHandle` (IDLE, RUNNING, TERMINATED, ERROR, OFFLINE), `roomListService(): RoomListService`.

**Room list:** `RoomListService.allRooms(): RoomList` (suspend) -> `entriesWithDynamicAdapters(pageSize: UInt, listener: RoomListEntriesListener): RoomListEntriesWithDynamicAdaptersResult`. The result gives `controller(): RoomListDynamicEntriesController` (`addOnePage()`, `resetToOnePage()`, `setFilter(RoomListEntriesDynamicFilterKind)`) and `entriesStream(): TaskHandle` (must be kept). `RoomListEntriesListener.onUpdate(List<RoomListEntriesUpdate>)`: sealed diffs Append, Clear, PushFront, PushBack, PopFront, PopBack, Insert, Set, Remove, Truncate, Reset, each carrying `Room` objects. Filters include NonSpace, NonLeft, Joined, Invite, Favourite, Category, All/Any combinators. Per room: `suspend room.roomInfo(): RoomInfo` with `displayName`, `rawName`, `encryptionState` (`uniffi.matrix_sdk_base.EncryptionState`), `isDirect`, `isDm`, `numUnreadMessages`, `numUnreadNotifications`, `numUnreadMentions`, `notificationCount`, `highlightCount`, `isMarkedUnread`, `heroes`, `membership`; live updates via `subscribeToRoomInfoUpdates(RoomInfoListener)`. `suspend room.latestEvent(): LatestEventValue` (None, Remote, RemoteInvite, Local; with timestamp, sender, isOwn, `content: TimelineItemContent`).

**Create room:** `suspend Client.createRoom(CreateRoomParameters): String` (room id). `CreateRoomParameters(name: String?, topic = null, isEncrypted: Boolean, isDirect = false, visibility: RoomVisibility, preset: RoomPreset, invite: List<String>? = null, avatar, powerLevelContentOverride, joinRuleOverride, historyVisibilityOverride, canonicalAlias, isSpace = false)`. `RoomVisibility.Private`; `RoomPreset` = PRIVATE_CHAT, PUBLIC_CHAT, TRUSTED_PRIVATE_CHAT. `Client.getRoom(id): Room?`, `getDmRoom(userId)`, `getDmRooms(userId)`.

**Timeline:** `suspend room.timeline(): Timeline` (or `timelineWithConfiguration(TimelineConfiguration)`). `suspend addListener(TimelineListener): TaskHandle`; `TimelineListener.onUpdate(List<TimelineDiff>)` with the same diff vocabulary over `TimelineItem` (`asEvent(): EventTimelineItem?`, `asVirtual()`). `EventTimelineItem`: `eventOrTransactionId`, `sender`, `senderProfile`, `isOwn`, `isEditable`, `content`, `reactions`, `timestamp`, `localSendState`, `readReceipts`, `canBeRepliedTo`. Message text: `content as TimelineItemContent.MsgLike` -> `.content.kind as MsgLikeKind.Message` -> `.content.body`; other kinds include `UnableToDecrypt`, `Redacted`, `Poll`, `Sticker`.
- `paginateBackwards(numEvents: UShort): Boolean` (true when the start of the room is reached), `paginateForwards`, `subscribeToBackPaginationStatus`.
- Send: top-level `messageEventContentFromMarkdown(md)` / `messageEventContentFromHtml(body, html)` -> `suspend timeline.send(msg): SendHandle`; `sendReply(msg, eventId)`.
- Media: `sendImage(params: UploadParameters, thumbnailSource: UploadSource?, imageInfo: ImageInfo): SendAttachmentJoinHandle` (non-suspend; `UploadParameters.source = UploadSource.File(path)`); also `sendFile/sendVideo/sendAudio/sendVoiceMessage/sendGallery`.
- Edit: `suspend edit(EventOrTransactionId, EditedContent)`; redact: `redactEvent(...)`.
- Reactions: `suspend toggleReaction(itemId: EventOrTransactionId, key: String): Boolean`.
- Receipts: `suspend sendReadReceipt(ReceiptType.READ | READ_PRIVATE | FULLY_READ, eventId)`, `markAsRead(ReceiptType)`.
- Send-queue state per item: `EventSendState` (NotSentYet, SendingFailed, Sent, ...).

**Encryption:** `client.encryption(): Encryption`: `recoveryState()` (UNKNOWN, ENABLED, DISABLED, INCOMPLETE), `recoveryStateListener`, `suspend enableRecovery(waitForBackupsToUpload, passphrase?, EnableRecoveryProgressListener): String` (returns the recovery key), `suspend recover(recoveryKey)`, `resetRecoveryKey()`, `backupState()`, `verificationState()` (UNKNOWN, VERIFIED, UNVERIFIED), `waitForE2eeInitializationTasks()`. SAS verification: `suspend client.getSessionVerificationController()` -> `requestDeviceVerification()`, `startSasVerification()`, `approveVerification()`, `declineVerification()`, `cancelVerification()`, with `SessionVerificationControllerDelegate` callbacks (`didReceiveVerificationRequest`, `didStartSasVerification`, `didReceiveVerificationData` carrying the emoji/decimals, `didFinish`, `didFail`, `didCancel`).

**Threading model:** async work is `suspend` (UniFFI bridges Rust futures; safe from any dispatcher, including Main). Streams are callback interfaces fired on Rust worker threads, returning a `TaskHandle`. Room-list entries are live `Room` objects and `roomInfo()` is suspend, so the callback must hand work to a coroutine. Destroy `Timeline`, `RoomList` results, `SyncService` and `Client` when done.

### TLS and cleartext findings

Source: `client_builder.rs` in the SDK repo and strings in the shipped `libmatrix_sdk_ffi.so`.

- The native HTTP stack is reqwest + rustls (aws-lc-rs), not the Java stack. **Android's `network_security_config` cleartext policy is not consulted by it**, so plain `http://` to the homeserver works regardless of the app's `usesCleartextTraffic`. (The app currently sets `usesCleartextTraffic="true"` and has no network security config; this was left untouched.)
- Certificate verification normally goes through **rustls-platform-verifier**, which on Android calls back over JNI into the Android trust manager (class `org.rustls.platformverifier.CertificateVerifier`).
- **Found on device: `addRootCertificates` alone fails on Android** with a reqwest "builder error" before any request is made. Cause (reqwest 0.13.5 `async_impl/client.rs`): when extra root certs are supplied without `tls_certs_only`, the platform-verifier branch is compiled as `return Err(builder("rustls-platform-verifier could not load extra certs"))` on Android (extra roots are only supported on desktop unix/Windows). The PEM/DER handling in the SDK was fine.
- **Fix:** when a private CA is imported, call `addRootCertificates(certs)` **and** `disableBuiltInRootCertificates()`. That maps to reqwest's `tls_certs_only`, which skips the platform verifier and uses a plain webpki verifier trusting only the supplied CA(s). Consequences: the private CA need not be installed system-wide; trust is pinned to the imported CA (no public CAs); the server certificate must carry a SAN matching the host or IP used. Without a CA the platform verifier (system trust store) is used, which needs `Android.init(context)`.
- Cleartext: the policy is still bypassed (native sockets), as above.
- Without `Android.init(context)` the verifier is uninitialised ("Expect rustls-platform-verifier to be initialized"), so HTTPS would fail. The debug spike calls it before `initPlatform`.
- Live verification (device, against the real Synapse): HTTPS with the imported CA plus `disableBuiltInRootCertificates()` works, as do encrypted and unencrypted DM round trips with the bot. `slidingSyncVersionBuilder(DISCOVER_NATIVE)` is required.

### Spike code

- `app/src/main/.../data/matrix/CertificateBytes.kt`: pure PEM-bundle / DER splitting (unit-tested). This is main-source (so JVM tests can reach it) but has no SDK dependency and is unused by release code. Everything below is in the debug source set only.
- `app/src/debug/.../data/matrix/MatrixClientFactory.kt`: platform init (SDK tracing at DEBUG to logcat tag `org.matrix.rust.sdk`), store dir under `filesDir` (wiped per login), random per-process store passphrase (Phase 1 replaces with Keystore-wrapped), optional CA.
- `app/src/debug/.../data/matrix/MatrixSpike.kt` and `MatrixSpikeActivity.kt`: a separate "Matrix spike" launcher icon (debug builds only) with homeserver / username / password / bot MXID fields, a CA picker (Storage Access Framework), **Login + list rooms** (name, encrypted, unread) and **Create DM with bot** (Encrypted switch; creates the direct room, invites the bot, sends a ping and shows live timeline items). The password is cleared from the field on tap and never stored or logged. Errors show the full cause chain (redacted) on screen, in `DebugLog` and in logcat tag `MatrixSpike`.
- `DebugLog.redact` additionally masks bearer tokens and `access_token` / `refresh_token` / `password` values.

### On-device checks (user)

1. Open the "Matrix spike" app icon. Pick the CA (stored in Downloads), enter the HTTPS URL, credentials, bot MXID; tap Login + list rooms.
2. Repeat with the plain HTTP URL and no CA.
3. With Encrypted on: Create DM with bot; expect the ping plus the bot's reply (no "[unable to decrypt]").
4. With Encrypted off: repeat.

## Phase 3 notes (from the Phase 0 device run)

- The SDK can emit a timeline `Clear` diff (seen on device). On `Clear`, re-paginate backwards from the live end; never treat an empty list as final or as the start of the room.

## Required in Phase 1 (from spike review)

- Own the coroutine scope: cancel it when the client is torn down (the spike's scope is never cancelled).
- Update list state atomically: build the new room/timeline list under one lock and publish a single immutable snapshot, instead of clear-then-addAll on snapshot state.
- Shut down in order: cancel `TaskHandle`s, `stop()` the sync service, then destroy timeline, room list, sync service and client.
- Resolve `org.rustls` via `exclusiveContent` for that repository and pin artifact checksums (Gradle dependency verification) because it is served from a GitHub-hosted Maven archive.
- Replace the in-memory store passphrase with an Android-Keystore-wrapped one; stop wiping the store on each login.

## Phase 1a (implemented)

- **Dependencies:** `sdk-android` and `rustls-platform-verifier` are now `implementation`; the `org.matrix.rustsdk.Android` JNI shim lives in main. The `org.rustls` repository is declared with `exclusiveContent` (only `org.rustls`, and `org.rustls` resolves nowhere else). The debug-only `abiFilters` stay; release ABI splits are Phase 1b, so release builds are not shippable with the SDK until then (the release workflow is untouched).
- **Dependency verification: adopted.** `gradle/verification-metadata.xml` (sha256, no signature verification, metadata verification on) was generated for the full graph with `--write-verification-metadata sha256` over `testDebugUnitTest assembleDebug assembleRelease assembleBeta`, run with `--refresh-dependencies --no-daemon` (a warm Gradle cache hides buildscript-classpath artifacts, so a first attempt missed four and failed on a clean cache). It pins the GitHub-hosted `org.rustls` artifacts and everything else; a clean-cache `testDebugUnitTest assembleDebug compileReleaseKotlin assembleBeta` passes. Trade-off: every dependency bump now needs a regenerated file (`./gradlew --refresh-dependencies --write-verification-metadata sha256 <the tasks above>`). The androidTest configurations cannot be resolved on this branch regardless (`concurrent-futures-ktx` strict-version conflict, pre-existing), so they are not covered.
- **Code (`data/matrix`):** `MatrixSdk` / `MatrixClientHandle` are the seam over the FFI (`RustMatrixSdk` is the only class touching it besides `MatrixPlatform`); `MatrixAccountStore` persists the session, bot MXID, optional CA (DER) and a random 32-byte store passphrase in EncryptedSharedPreferences (passphrase generated once, kept across logouts; the password is never stored); `MatrixClientManager` is the `@Singleton` owner with one `StateFlow<MatrixState>` (LoggedOut, Restoring, LoggingIn, Ready, Error, RestoreFailed) plus `loginError` for the last rejected or failed login and `rooms: StateFlow<List<MatrixRoomSummary>>` built with `applyDiffs`. UI: Settings -> "Matrix account" (`ui/matrix`).
- **Lifecycle (the "Required in Phase 1" list):** one owned scope per session, cancelled on logout/teardown; shutdown cancels the scope, then `stop()`s the sync service, then destroys entries handle, controller, room-list result, room list, room-list service, sync service and client; `Room`/`RoomInfo` handles are destroyed after being summarised; the room list is published as immutable snapshots from a single collector; the store passphrase is persisted and kept across logins, and each login's store directory follows the slot scheme under **Store slots** below.
- **Session delegate:** `ClientBuilder.setSessionDelegate(ClientSessionDelegate)` exists with two methods, `saveSessionInKeychain(Session)` (called on token refresh; we persist it via `MatrixAccountStore.updateSession`, a no-op once logged out) and `retrieveSessionFromKeychain(userId)` (served from the store, falling back to the last session the SDK saved).
- **Debug defaults:** gitignored `matrix.defaults.properties` (`homeserver`, `username`, `botMxid`; see the `.example`) feeds debug-only `BuildConfig` fields that prefill the setup form; release and beta are always empty; no password key.
- **Restore on start:** `HermesApp` calls `restoreOnStart()`; the manager reads its encrypted store on the IO dispatcher. A failure leaves the saved session in place and moves to `RestoreFailed(message, retryable)`; `retryable` is false only when the homeserver rejected the token (`M_UNKNOWN_TOKEN`, `M_USER_DEACTIVATED`, `M_USER_LOCKED`). The setup screen shows **Retry** (when retryable) and **Discard saved session** (a full logout) in that state, above the login form. Restore is also allowed after a login error (`Error`).
- **Operations and states:** login, restore and logout run as jobs on the manager's own scope (leaving the screen never orphans a half-finished login), serialised by a mutex; every in-flight job is tracked and logout/shutdown cancel all of them, so logout preempts a running login or restore. States: LoggedOut, Restoring, LoggingIn, Ready, Error (a login failed and nothing is saved), RestoreFailed. A login is ignored while one is running or a session is live (log out first); a blank password is rejected before anything is touched. The rejection or failure message is exposed separately as `loginError`, so a failed login from `RestoreFailed` leaves the state (and Retry/Discard) intact.
- **Store slots:** each login builds its client in a fresh slot directory (`files/matrix/s0` or `s1`, recorded as `storeSlot` in the account store). A failed login deletes only its own slot and restores the previous account entry, so it can never cost the user a good saved session; a successful one deletes the old slot afterwards (that deletion, and everything after activation, is not rolled back by a cancel). If the server login succeeded but a later step fails, the new device is logged out on the server. Token refreshes from the SDK delegate are applied only to the saved user/device. Logout wipes all slots and the account store (the passphrase is kept).
- **Device name:** the Matrix device is registered as "Hermes Local".
- **Backups and ABIs:** the SDK database and the encrypted account prefs are excluded from cloud backup and device transfer. Release and beta are limited to `arm64-v8a` until Phase 1b adds ABI splits.

## Phase 2 (done)

- **Chats tab is Matrix-only.** The user decided this, replacing the Matrix | Gateway toggle in the first plan. The tab shows the room list. With no Matrix account it shows the "Set up Matrix" empty state.
- **Gateway sessions:** Settings → Sessions opens `gateway_sessions`, the unchanged gateway list (grouping, search, pins, archive, Archived, session admin). Opening a session still goes to `chat/{id}`. The widget's "sessions" item and `hermes://gateway/sessions` open it too.
- **Profiles:** `ShellViewModel` already refreshes the profile manager at startup, so `profileManager.active` is set for Mission Control, new chat and share.
- **Rows:** `MatrixRoomSummary` gained `lastMessagePreview`, `lastMessageSender`, `lastActivityTs` and `avatarLetter`, filled from `Room.latestEvent()` (`LatestEventValue.Remote`/`Local`: `timestamp` is a ULong of epoch millis, `sender`, `profile`, `content`). Previews are plain text, one line, capped at 120 characters (`data/matrix/MessagePreview`). An undecryptable event shows "Encrypted message".
- **List:** sorted by last message time, newest first. Equal times keep the SDK order (the sort is stable); rooms without activity sort last. Search matches the name and the preview. Rows show the avatar letter, name, lock when encrypted, "sender: preview", the activity time (`HH:mm`, "Yesterday" or a date) and an unread badge (`ui/matrix/RoomListPresentation`).
- **States:** signed out (Set up Matrix opens the account screen), restoring or logging in (loading), restore failed (banner with Retry, or Set up Matrix when the server rejected the token), login error, and no rooms.
- **Placeholder:** `matrix_room/{roomId}` (room ID URL-encoded) shows the room name and "Room view arrives in Phase 3". No new-room button yet (Phase 5).

## Phase 3a (done)

Read-only room timeline on `matrix_room/{roomId}` (sending arrived in Phase 3b below; at the time the screen ended in a placeholder bar).

- **Timeline model (`data/matrix/TimelineModels.kt`):** a pure `TimelineEntry` sealed type — `DayDivider(ts)`, `ReadMarker`, `Message(id, sender, senderName, isOwn, ts, body, isEdited, kind, sendState)`, `Utd(id, sender, ts)`, `StateEvent(id, ts, summary)`, `Unsupported(id)`. `kind` is `TEXT | NOTICE | EMOTE | IMAGE | FILE | OTHER`; `sendState` is `NOT_SENT_YET | SENDING_FAILED | SENT`; plus `PaginationState` (`Idle | Loading | AtStart`).
- **Seam (`MatrixSdk` / `RustMatrixSdk`):** `MatrixClientHandle.openTimeline(roomId): MatrixTimelineHandle?` (null when the room is unknown). The handle exposes `items: StateFlow<List<TimelineEntry>>`, `suspend paginateBackwards(count): Boolean` (true at the start), `paginationState: StateFlow<PaginationState>` and `close()`. The Rust handle looks the room up with `Client.getRoom`, calls `room.timeline()`, `timeline.addListener(TimelineListener)` and `timeline.subscribeToBackPaginationStatus(PaginationStatusListener)`, then `timeline.paginateBackwards(UShort)` for the first page.
- **Diff application:** the listener's `List<TimelineDiff>` goes through one `Channel` into one collector, which maps each diff to `ListDiff<TimelineEntry>` via `applyDiffs` and publishes an immutable snapshot. Every `TimelineItem` and its nested content (`TimelineItemContent`, `MsgLikeContent`, `MsgLikeKind`, `MessageContent`, `MessageType`, image/file/audio/video content) is destroyed exactly once in `finally` blocks, including on cancellation; the listener body never throws (a bad item is logged by variant and dropped to `Unsupported`).
- **Clear handling (`data/matrix/TimelineRepaginator.kt`):** the SDK can emit `Clear` (and an empty `Reset`) a few seconds after open — an empty list is not final. `TimelineRepaginator` debounces those into a single `paginateBackwards` after the first page has been requested and stops for good once `paginateBackwards` reports the start, so it never loops. JVM-tested with a fake pager.
- **Presentation (`ui/matrix/TimelinePresentation.kt`):** pure `timelineRows(...)` maps entries to `TimelineRow`s, collapsing consecutive messages from one sender within 5 minutes so the avatar and name show only on the first of a group. Day dividers read "Today"/"Yesterday"/"MMM d"; message times read "HH:mm"; state events become small centred rows; UTDs become a muted "Encrypted message" bubble; read markers and unsupported items are dropped.
- **Screen (`MatrixRoomViewModel` + `MatrixRoomScreen`):** top bar shows the room name (from `MatrixClientManager.rooms`), a back button and a lock icon when encrypted. A `reverseLayout = true` `LazyColumn` keeps the newest message at the bottom, auto-pages backwards as the user scrolls near the top, shows a loading indicator while paging and "Beginning of conversation" at the start. Own messages go right (`primaryContainer`), others left with the SDK markdown renderer; "(edited)" marks edits; images/files show a "[image] filename" placeholder. States: loading, error with Retry, and "Matrix isn't set up" with a button to Settings → Matrix account. The timeline handle is closed in `onCleared`.

## Phase 3b (done)

Sending text from the composer, with in-place retry of failed local echoes. Two commits: e05ccdc
("send text messages from the Matrix room screen") then 370913c ("retry failed Matrix sends in
place and keep the draft until a send succeeds").

- **Seam (`MatrixSdk` / `RustMatrixSdk`):** `MatrixTimelineHandle` gained three never-throwing,
  boolean-returning calls (same `ffi.use(false)` pattern as the rest of the handle):
  - `sendText(body)`: builds the content with the top-level `messageEventContentFromMarkdown(md)`
    and `timeline.send(content)`; the content and the returned `SendHandle` are destroyed in
    `finally`/`runCatching`; the body is never logged (user content).
  - `retrySend(itemId)` / `abortSend(itemId)`: `timeline.retrySend(…, SendTarget.Event)` /
    `timeline.abortSend(…, SendTarget.Event)` so a failed send is retried or discarded as the same
    event, never sent twice.
- **Event identity (`TimelineModels.kt`):** `EventOrTxnId` — a pure sealed copy of the SDK's
  `EventOrTransactionId` (`Event(eventId)` / `Transaction(transactionId)`), mapped in both
  directions by small `toItemId()` / `toSdk()` helpers. `TimelineEntry.Message` carries it as
  `itemId` (null when unknown) so the UI can retry a failed echo in place.
- **ViewModel (`MatrixRoomViewModel`):** the draft lives in a `draft: StateFlow<String>` (not in the
  composable) so a failed send keeps the text. `send()` trims `updateDraft`'s value, ignores blank
  input and overlapping sends (`_sending.compareAndSet(false, true)`), clears the draft with
  `compareAndSet(raw, "")` only when the send is accepted — anything typed while the send was in
  flight survives — and raises `sendFailed` on failure (cleared when the next send starts).
  `retrySend(itemId)` / `abortSend(itemId)` call the handle and swallow the never-thrown failure.
- **Screen (MatrixRoomScreen):** the Phase 3a disabled bar is replaced by a `MessageComposer`
  (outlined field, IME Send action, send icon disabled while a send is in flight or the draft is
  blank, "Couldn't send" banner), padded by the union of the IME and navigation-bar insets. Own
  bubbles show a `SendStatus`: a clock while in flight, and a tappable "Not sent" label offering a
  Retry / Delete menu once the echo has failed (the label is inert when `itemId` is null). Slash
  commands are sent as-is for Hermes to parse.
- **Presentation (`TimelinePresentation`):** `TimelineRow.Message` mirrors `sendState` and `itemId`
  (Utd rows always carry `SENT`), so the bubble UI needs no SDK types.
- **Tests:** `MatrixRoomViewModelTest` — trim + success, failure surfaced, blank ignored,
  overlapping send ignored, success clears / failure keeps / overlapping send keeps the draft,
  retry reuses the same item and never sends a copy, delete aborts the failed echo.
  `TimelinePresentationTest` — send state and item id reach the row. The `MatrixTimelineHandle`
  fakes in `MatrixClientManagerTest` implement the new methods.

## Phase 3c (done)

Editing own text messages in place (commit 0051055, "edit own Matrix text messages in place").

- **Model / mapper:** `TimelineEntry.Message` gained `isEditable` (default false). The mapper sets it
  from the SDK's `EventTimelineItem.isEditable` and only for `MessageType.Text` (attachment, poll
  and caption edits wait for a later phase). `TimelineRow.Message` mirrors the flag.
- **Seam:** `MatrixTimelineHandle.edit(itemId, newBody): Boolean` — the markdown new body goes
  through `messageEventContentFromMarkdown` wrapped in `EditedContent.RoomMessage`; both the content
  and the wrapper are destroyed in `finally`; the same `ffi.use(false)` never-throw pattern as the
  other calls, and the body is never logged (user content).
- **ViewModel:** `editing: StateFlow<EventOrTxnId?>` and `editFailed: StateFlow<Boolean>`.
  `beginEdit(itemId, body)` prefills the composer (ignored while a send is in flight or already
  editing), saving the draft to restore on cancel. `submitEdit()` trims, ignores blanks, reuses the
  `_sending` gate (an edit and a text/img send can never run at once) and only clears the composer
  draft when the timeline accepted the edit (`compareAndSet`, so anything typed during the submit
  survives). `cancelEdit()` restores the pre-edit draft. `closeTimeline()` clears the editing state,
  so it never spills across a reopened handle.
- **Screen:** a long-press (`combinedClickable`) on an own editable bubble opens edit mode; the
  composer then shows an "Editing message" header with Cancel, an "Edit message…" placeholder and
  the send icon replaced by a check ("Save edit"). A failed edit surfaces "Couldn't save" (the
  "Couldn't send" banner stays send-related only).
- **Tests:** `MatrixRoomViewModelTest` (prefill, in-place edit + stop editing, failure keeps edit
  and draft, blank ignored, cancel restores the draft, begin ignored while sending, editing ends
  when the timeline closes) and `TimelinePresentationTest` (flag relay).

## Phase 3d (done)

Read receipts sent from the viewport (commit eb16a56, "send read receipts from the Matrix room
viewport"). No UI of its own; receipts are public (`ReceiptType.READ`).

- **Seam:** `MatrixTimelineHandle.sendReadReceipt(eventId: String) -> Boolean` →
  `timeline.sendReadReceipt(ReceiptType.READ, eventId)`, never throws.
- **Viewport logic:** `TimelinePresentation.latestVisibleEventId(rows, visibleKeys)` (pure) picks
  the newest message row actually rendered whose identity is a remote event id; local echoes and
  UTDs return null, so the newest visible item being an unsent own message suppresses nothing
  weird — the next pass fixes the marker once it resolves.
- **Tracker (`data/matrix/ReadReceipts.kt`):** pure `ReadReceiptTracker` — a receipt is wanted only
  when the user is settled (not mid-scroll) on a different remote event than the last one sent, so
  scrolling never spams and each newly-visible newest event sends exactly one; `reset()` for a new
  timeline handle (called by `closeTimeline`).
- **ViewModel / screen:** `onVisibleKeysChanged(keys, isScrollInProgress)` fed from a second
  `snapshotFlow` over the LazyColumn's visible keys; best-effort send, failure swallowed.
- **Tests:** `ReadReceiptsTest` (scrolled / settled / dedup / reset), `TimelinePresentationTest`
  (newest-visible pick, local echo not target), `MatrixRoomViewModelTest` (one receipt for the
  newest event, no repeat, none while scrolling).

## Phase 3e (done)

Sending images from the composer (commit 1ffbf8f, "send images from the Matrix room composer").
Own bubbles already render "[image] filename" placeholders (Phase 2/3a mapping); this slice sends.

- **Staging (`data/matrix/ImageStaging.kt` + `ImageStager.kt`):** the picker hands over a content
  URI; `ImageStager.stage(uri)` (Hilt singleton, `Dispatchers.IO`, never throws) copies it into
  `cacheDir/matrix-outbox`, resolves the display name (`OpenableColumns`, best effort) and the
  mimetype. Pure helpers with JVM tests: `sanitizedImageFilename` (separators cut, punctuation
  dropped, inner spaces kept, extension-only names fall back to `image.jpg`), `imageMimetype`
  (resolver answer wins when it looks like an image, else the extension map), `imageExtensionFor`.
  The staged copy is deleted after the attempt (staged during send via
  `removeStaged`; a staging failure sends nothing).
- **Seam:** `MatrixTimelineHandle.sendImage(path, filename, mimetype): Boolean` →
  `timeline.sendImage(UploadParameters(UploadSource.File(path), …), null thumbnail,
  ImageInfo(mimetype, …))`, `SendAttachmentJoinHandle.join()` awaited, the `ImageInfo` destroyed in
  `finally`; `UploadSource`/`UploadParameters` are FFI value types (no destructor).
- **ViewModel / screen:** `sendImage(uri)` shares the text `_sending` gate (one send of any kind at
  a time) and surfaces failures through the existing `sendFailed` banner. The composer gained an
  add-image button (`Icons.Rounded.AddPhotoAlternate`, hidden while editing) launching the
  `GetContent` image picker, matching the CA-import picker pattern already in `MatrixAccountScreen`.
- **Tests:** `ImageStagingTest` (sanitation/fallbacks/mime maps/filename-extends), VM tests
  (staged-then-sent-then-removed, staging failure surfaces without sending, upload failure surfaces,
  image send ignored while a text send is in flight). The resolver/copy I/O path itself needs an
  on-device or emulator check.

## Phase 4 (in progress — 4a, 4b done; approvals deferred)

### Bot-side contract findings (live, from the emulator room)

- The bot **does parse slash commands from plain Matrix text messages**: `/new` (sent twice) each
  produced a bot confirm prompt; `/stop` produced "No active task to stop".
- The bot **does emit approval prompts over Matrix** (contradicting the earlier "not implemented
  yet" assumption): a "⚠️ Confirm /new" message offering Approve Once / Always Approve / Cancel,
  with a text fallback of replying `!approve` / `!always` / `!cancel`. Note: my own `!cancel` and
  `/help` sends did not linger in-room afterwards (bot-side cleanup suspected) — the reaction keys
  for the room-side approve buttons stay parked until confirmed with the bot owner.
- The bot's model chooser over Matrix is **reaction-based** (bot message "React to choose a
  model: 1️⃣ … 🔟" with per-reaction highlight chips), not slash text. The `modelCommand` wire
  string is preserved (same as the gateway chat) but whether the bot honors it over Matrix is
  unverified.

### 4a — command palette (done, commit 419878c)

The composer's `/` replaces the timeline with the gateway slash-command catalog
(`ChatRepository.commandsCatalog()`, shared with the gateway chat), mirroring the gateway chat's
palette rules. Pure logic in `ui/matrix/CommandPalette.kt` with JVM tests:
- `commandPaletteQuery(draft)`: the query while composing a slash word (no space), null otherwise.
- `filterCommands(commands, query)`: case/slash-insensitive prefix match, catalog order kept.
- `commandDraft(name)`: tap completes to `"/cmd "`.

VM: `commands` StateFlow loaded per room open, best-effort — and **`ChatRepository.connect()`
first** (cold start has no gateway socket; the RPC would block on its ready-gate — caught live).
Screen: `CommandPalette` composable in the content slot; palette hidden while editing; gateway
failures leave an empty palette (raw commands still send as text).

### 4b — native hooks (done, commit ea0f414)

- `sendCommand(command)` in the room VM: sends a fixed slash command as text for the bot to parse
  (identical to typing it), never touches the draft, shares the `_sending` gate, failures surface
  via `sendFailed`; ignores anything not starting with `/`.
- Top-bar overflow quick actions (⋮): "New session" → `/new`, "Stop" → `/stop`, "Choose model…" →
  the model picker sheet; send items disabled while a send is in flight.
- Model picker: `ModelPickerUi` state; `openModelPicker()` fetches `ModelRepository.providers()`
  (gateway REST) the first time (best-effort, failure + retry shown), later opens reuse it;
  `selectModel(provider, model)` closes the sheet and sends `modelCommand(provider, model)` =
  `/model <model> --provider <provider> --session` (the gateway chat's SESSION wire string).
- On-device (emulator, Home Channel): `/new` sent from the menu (bot confirm prompt observed),
  `/stop` sent (bot replied "No active task to stop."), palette/model sheet driven via adb UI
  dumps.
- Tests: 8 new VM cases (send/drop/fail/limits for commands, picker load/load-fail+retry/cache,
  pick → wire string) + the `modelCommand` pin (9 total).

## Out of scope for Phase 0

Real Sessions-tab UI, persistent storage, notifications, recovery/verification flows.
