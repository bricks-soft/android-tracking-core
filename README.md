# @bricks-soft/android-tracking-core

Shared Android library (Kotlin) consumed by Capacitor plugins and host apps:

- `com.brickssoft.tracking.httpqueue` — durable SQLite-backed HTTP upload queue (at-least-once, batching, retention, auth pause).
- `com.brickssoft.tracking.location` — `LocationProvider` abstraction with Google fused, Huawei fused (HMS Location Kit) and platform `LocationManager` implementations plus capability detection.

Not a Capacitor plugin itself. Design: see `docs/huawei/plugin-architecture.md` in the Bricks Rep repo (with the single-library override noted in `docs/huawei/plan.md`).

## Consuming from a Capacitor app

```groovy
// android/settings.gradle
include ':bricks-soft-android-tracking-core'
project(':bricks-soft-android-tracking-core').projectDir = new File('../node_modules/@bricks-soft/android-tracking-core/android')
// android/app/build.gradle
implementation project(':bricks-soft-android-tracking-core')
```

## httpqueue

`com.brickssoft.tracking.httpqueue` provides a durable, scoped upload queue with plain Kotlin models and no bridge dependency. The host supplies destinations, payloads, noncredential headers, identity scope and credentials. Use a different database name for independent consumers. All access must run in the default app process. The host must declare `android.permission.INTERNET`; the verified WorkManager AAR contributes `ACCESS_NETWORK_STATE`. Library manifests remain host-merge inputs; verify the final manifest.

```kotlin
// Run initialization and credential updates on an IO dispatcher.
val database = QueueDatabase(context, "telemetry")
val scheduler = QueueScheduler(context, "telemetry")
val queue = QueueClient(database, scheduler) // persists recovery scheduling before networking
val dispatcher = QueueDispatcher(database)
val authorization = AuthorizationStore(database)
val config = QueueConfig(
    queueId = "samples",
    scopeKey = sessionScope, // stable opaque identity; never reuse for another identity
    revision = 1,
    endpoint = uploadUrl,
    rootProperty = "samples",
    paramsJson = "{\"context\":{\"format\":1}}",
    credentialHeaderNames = setOf("Authorization"),
)
queue.register(config)
authorization.setAuthorization(Authorization(sessionScope, 1, credentialHeaders))
val capturedUuid = java.util.UUID.randomUUID().toString()
val result = queue.enqueue(QueueRecord(
    queueId = config.queueId,
    scopeKey = config.scopeKey,
    configRevision = config.revision,
    capturedAt = capturedAtEpochMs,
    uuid = capturedUuid,
    payloadJson = encodedRecordJson, // embed capturedUuid in your server's idempotency field
))
// result.retained reports immediate expiry/count eviction; inserted acknowledges a committed insert.
val automatic = dispatcher.drain(config.queueId, config.scopeKey)
val pending = queue.count(config.queueId, config.scopeKey)
val manual = dispatcher.sync(config.queueId, config.scopeKey) // also sends a singleton
```

Public API:

- `QueueDatabase.register/definitions/state` persist immutable configurations and expose upload state. `QueueClient.register/enqueue/count/purge/pause/resume` perform disk operations on IO. UUID insertion is idempotent for identical data already present; a conflicting UUID fails. Enqueue errors propagate, including SQLite storage failure. Acknowledged/purged UUIDs are not kept in a permanent deduplication ledger.
- `QueueDispatcher.drain` returns `DrainResult`; `sync` bypasses automatic thresholds and throws `QueueSyncException(result)` unless drained. Both respect persisted auth, explicit pause, blocked gates and retry cooldowns. `DrainBudget` bounds requests and elapsed time. Collection and native deadline enforcement belong to the caller.
- `AuthorizationStore.setAuthorization/snapshot/clear` handle atomic scoped credential snapshots. Revisions must strictly increase. Clear advances the revision, persists an upload fence, cancels any matching in-flight operation and waits for drain ownership to finish. A refresh opens only `AUTH_PAUSED`; explicit `PAUSED` and `BLOCKED` need `QueueClient.resume`. Call `scheduler.requestDrain()` after accepting new authorization. Tokens are never refreshed by this library.
- `QueueScheduler.initialize/requestDrain/onConnectivityChanged` register recovery. `QueueWorker` needs only its persisted database name and the two built-in encoders. `Diagnostics` receives typed operational events with no payload, URL, header values or server response text. Sink exceptions cannot affect commits or acknowledgements. `Clock`, `RandomSource`, `QueueTransport` and `CredentialCipher` are injectable for deterministic tests and host-managed foreground use.

Policies and guarantees:

- SQLite writes commit before enqueue resolves. One mutex per canonical database path spans selection, HTTP and acknowledgement across all dispatcher instances. No SQLite transaction is held during HTTP. Queued records survive process loss and retain their UUID and capture time. Delivery is at least once within retention: an ambiguous response or death after server commit can replay a request. The host must embed the stable UUID in a server-supported idempotency field; the library does not invent a wire field or header.
- Records are selected by `captured_at,id`. JSON arrays contain at most `maxBatchSize` records and never mix scopes or config revisions. Interleaved revisions remain in chronological order. `rootProperty` receives the array, including singleton batches; `paramsJson` merges at the root and cannot replace that property. The other encoding, `SINGLE_REQUEST`, sends exactly one pre-rendered `RenderedRequest`; its URL must share the configured scheme/host/port. Rendering and payload mapping remain host responsibilities.
- Ordinary automatic drain starts at `autoSyncThreshold` (default 2), then continues through a final singleton. An interrupted bounded drain persists this continuation across reopen. `autoSync=false` disables automatic sending. Optional `maxBatchAgeSeconds` makes a small tail eligible on a later trigger; it is not an exact timer. Manual sync bypasses threshold and `autoSync`, and never implicitly deletes unsent data.
- Every 2xx acknowledges exactly the selected IDs. 401 retains rows and persists `AUTH_PAUSED`; a response using an older auth revision cannot pause newer credentials. 403 persists `BLOCKED` with `FORBIDDEN`. 408, 429, 5xx and IO failures retain rows, persist attempt/due time, and stop the drain. Default retry delay is 5 seconds, doubles to a 15-minute cap, and adds 0–20% positive jitter within the cap. Delta-seconds or HTTP-date `Retry-After` can increase the delay up to that cap. Other responses block with a status diagnostic. Redirects are not followed. 413 halves the persisted batch limit; a singleton 413 blocks. Explicitly purging or expiring the blocking row releases a blocked gate. Auth pause never suspends retention.
- `RetentionPolicy(maxDaysToPersist=1, maxRecordsToPersist=-1)` defaults to one day and no count cap. Expiry is `capturedAt + days`, with rows pruned when `expiresAt <= now` before enqueue, count and each drain selection. The active scope config sets its count cap; oldest capture time/ID is evicted first. Zero count capacity retains nothing. Existing rows keep their original absolute TTL through retries/config updates. `PruneCounts` and diagnostics distinguish expiry and overflow from HTTP acknowledgements. Wall clock changes affect absolute TTL; no exact expiry alarm is promised.
- Endpoint, request, params and noncredential headers are immutable per queued revision. Standard credential header names are rejected from row/config headers; declare custom credential names before registration. Later credential snapshots are also checked against existing persisted headers. Hosts must keep credentials out of URLs, params and payloads. Default `KeystoreCredentialCipher` encrypts credentials with Android Keystore AES/GCM and authenticates the scope; SQLite itself is not encrypted. The database and encrypted credential table are under `Context.noBackupFilesDir`. Injected ciphers/transports/retry policies are not serialized: the standard worker reconstructs the default Keystore cipher, transport and retry policy. Custom recovery behavior requires a host-owned worker using the same database/dispatcher.
- `QueueScheduler` persists unique periodic work (15 minutes, KEEP) and connected-network one-shot work. One-shot work is expedited on API 31+ with normal-work fallback when quota is exhausted; older versions use ordinary work because legacy expedited work would require an FGS/notification. The queue owns no foreground service. Reconnect callbacks should call `onConnectivityChanged(true)`; WorkManager also waits for its connected constraint. Worker runs are bounded to two minutes, reopen definitions without host singletons, respect backoff and return retry when more eligible work remains. Scheduling timing, device reboot behavior and OEM restrictions still need device qualification.
- Schema version 2 migrates version 1 additively, preserving rows and revisions. A newer database schema is rejected without downgrade or upload. Configuration revisions are retained for audit/reconstruction; no destructive migration is performed. Close a database only after its callers and uploads have stopped. Logout should pause/cancel, clear credentials and retain or explicitly purge the original scope according to host policy; a later identity must use a different scope.

Dependency and API evidence, retrieved **2026-09-14** (pins are verified artifacts, not claims of latest versions):

| Choice / API | Verified source |
| --- | --- |
| OkHttp **5.2.1**, matching the consuming app's declaration inspected at `android/app/build.gradle:73` | [Maven POM](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/5.2.1/okhttp-5.2.1.pom), [5.2.1 source artifact](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-jvm/5.2.1/okhttp-jvm-5.2.1-sources.jar), inspected `OkHttpClient`, `Response`, request and callback APIs |
| HMS requests OkHttp **4.12.0**; use the host's single **5.2.1** version instead of adding duplicate implementations | [HMS core POM](https://developer.huawei.com/repo/com/huawei/hms/LocationLiteSdk/core/2.16.0.302/core-2.16.0.302.pom). Resolution/build checks do not prove HMS device compatibility; qualify the final minified host. |
| WorkManager **2.10.1**, compatible with the scaffold's compile SDK; its AAR metadata requires minCompileSdk 35 | [POM](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.10.1/work-runtime-2.10.1.pom), [AAR](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.10.1/work-runtime-2.10.1.aar), [source APIs](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.10.1/work-runtime-2.10.1-sources.jar), [testing POM](https://dl.google.com/dl/android/maven2/androidx/work/work-testing/2.10.1/work-testing-2.10.1.pom) |
| Robolectric **4.16**, MockWebServer **5.2.1**, coroutines **1.10.2** | [Robolectric POM](https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.16/robolectric-4.16.pom), [MockWebServer POM](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/mockwebserver3/5.2.1/mockwebserver3-5.2.1.pom), [MockWebServer API source](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/mockwebserver3/5.2.1/mockwebserver3-5.2.1-sources.jar), [coroutines Android POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2.pom), [coroutines test POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-test/1.10.2/kotlinx-coroutines-test-1.10.2.pom) |
| Network permissions | [Android networking guide](https://developer.android.com/develop/connectivity/network-ops/connecting) and WorkManager AAR manifest above |
| Work constraints, periodic minimum, expedited quota and legacy FGS behavior | [Android work request guide](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) |
| SQLite transactional upgrades, protected credentials and backup exclusion | [SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec), [Context.noBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()) |

JVM verification uses Robolectric SQLite, MockWebServer and coroutine tests. Hardware Keystore persistence, real process death, reboot/connectivity scheduling, R8 reconstruction and the final combined HMS host need device/release qualification. No emulator is installed on this machine.


Verified locally on 2026-09-14: **35 httpqueue tests per variant, 64 total tests per variant, zero failures** in debug and release. Worker bootstrap ran on Robolectric API 24 and 28. Both AARs assembled, and `httpqueue` has zero lint findings. The aggregate lint report still has an unrelated location-package API-level error; the scaffold sets `abortOnError=false`.

Reproduce the full verification from the repository root (temporary caches avoid read-only home directories in the worker sandbox):

```bash
GRADLE_USER_HOME=/tmp/httpqueue-gradle \
JAVA_TOOL_OPTIONS=-Duser.home=/tmp/httpqueue-jvm-home \
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=/home/salem/Android/Sdk \
bash ./android/gradlew -p android test assembleDebug assembleRelease lintDebug \
  -Pkotlin.compiler.execution.strategy=in-process --console=plain
```

Result: `BUILD SUCCESSFUL` (1m 3s, 99 tasks). `dependencyInsight --configuration debugRuntimeClasspath --dependency com.squareup.okhttp3` also succeeded and resolved HMS's requested 4.12.0 to the single OkHttp Android 5.2.1 implementation.

## location

`com.brickssoft.tracking.location` provides `GmsFusedProvider`, `HmsFusedProvider`,
`PlatformProvider`, `CapabilityProbe` and `ProviderSelector`. It has no Activity,
Capacitor, persistence, service lifecycle, application endpoint or identity dependency.
Pass a Service/application `Context` and a live `Looper` (main by default). The host
owns the foreground service, permission prompts and durable session generation.

Public contracts:

- `LocationProvider.capabilities(): ProviderCapabilities` is a fresh preflight snapshot.
- `start(LocationRequest, LocationSink): Subscription` installs callback fencing before
  registering with the SDK. `Subscription.awaitReady()` suspends until registration
  succeeds; a first fix may arrive before readiness. Bound this wait in the host.
- `stop(subscription)` / `subscription.remove()` are suspend functions. They fence
  further callbacks before requesting removal, wait up to five seconds, and propagate
  removal failure. Calling remove again is safe. Pending registration is removed
  immediately and again after its eventual completion, including after a cleanup timeout.
- `currentPosition(PositionRequest): LocationFix` suspends for one sufficiently fresh
  fix, then removes its private subscription on success, failure, timeout or cancellation.
  Defaults: 15-second acquisition timeout, zero maximum age, five-second cleanup budget.
  Total completion can take acquisition timeout plus cleanup budget. A failed removal
  rejects success; on an existing acquisition failure it is attached as a suppressed
  exception. No vendor API can guarantee successful removal if its task never responds.
- `LocationRequest` carries generation, interval, minimum interval and accuracy
  (`HIGH`, `BALANCED`, `LOW`). Positive intervals are required; effective minimum is
  `min(intervalMs, minUpdateIntervalMs)`. Google batching is disabled; platform requests
  GPS plus available network with zero distance filter and effective minimum time.
- `LocationFix` contains provider, acquisition epoch milliseconds, monotonic acquisition
  nanoseconds, latitude/longitude, nullable accuracy/altitude/altitudeAccuracy/speed/heading,
  and mock status. Zero-valued measurements remain present. Maximum age uses monotonic
  acquisition time; zero maximum age rejects fixes acquired before the request starts.
  It does not call vendor last-location caches or create a second continuous tracker.
- Sink callbacks carry the host-supplied generation. Removal fences that subscription;
  concurrent subscriptions remain independent. The host must also check its current
  durable generation before asynchronous persistence. Keep sink callbacks short and
  nonblocking; do not block a callback waiting for subscription removal. Callback batches
  are processed in acquisition order, including repeated coordinates.

Example inside a host coroutine with foreground location access:

```kotlin
val probe = CapabilityProbe(serviceContext)
val state = ProviderSelector(probe::snapshot).select(allowPlatformFallback = true)
val provider: LocationProvider = when (state.selected) {
    ProviderKind.GMS -> GmsFusedProvider(serviceContext, probe = probe::snapshot)
    ProviderKind.HMS -> HmsFusedProvider(serviceContext, probe = probe::snapshot)
    ProviderKind.PLATFORM -> PlatformProvider(serviceContext, probe = probe::snapshot)
    null -> throw ProviderUnavailableException(state)
}
val fix = provider.currentPosition(PositionRequest(LocationRequest(generation = 12)))
// For a continuous subscription:
val subscription = provider.start(LocationRequest(generation = 12), hostSink)
try {
    withTimeout(15_000) { subscription.awaitReady() }
    awaitCancellation() // host coroutine owns its lifetime
} finally {
    withContext(NonCancellable) { provider.stop(subscription) }
}
```

Selection uses capabilities, never manufacturer: auto prefers available GMS, then
successfully initialized HMS fused, then platform only with `allowPlatformFallback`.
An explicit kind must pass its checks and never switches silently; explicit PLATFORM
is itself opt-in. Permission denial and disabled location block all selections.
GPS-only platform acquisition requires fine permission; missing network is reported.
Reprobe after startup, service-package changes, permission/settings changes and explicit
retry. Temporary poor reception does not automatically switch providers.

`ProviderState` has the architecture's fields: `selected`, `gmsStatus`, `hmsStatus`,
`locationEnabled`, `locationAvailable`, `permission`, `backgroundPermission`, `powerSave`,
`exactAlarmAllowed`, `degradedReasons`. Bridge serializers should use the enums'
`wireValue` (`gms`/`hms`/`platform`, `denied`/`coarse`/`fine`). Snapshot
`locationAvailable` is null until the host incorporates a current subscription's
`onAvailability` event. SDK availability is advisory, not a promise of the next fix.
`CapabilitySnapshot` additionally exposes minimum-HMS-version status and fused
initialization separately. Probe errors have named reasons and null status codes.

HMS Core APK availability is **not** a prerequisite for this pinned fused SDK.
`hmsFusedUsable` currently means client initialization succeeded, not that a fix was
received or that background operation is qualified. Registration failure is surfaced
through `awaitReady` and the sink; reselect only on an explicit host retry. APK status
and the minimum-version check (6.3.0.301) remain diagnostics. The host may inject a
stricter fused-initialization function after device qualification. Background permission
and exact-alarm access are diagnostics, not unconditional foreground-acquisition gates.

### Verified location dependencies and sources

All references/artifacts in this subsection were retrieved or inspected on **2026-09-14**.
Huawei reference content was retrieved using `https://r.jina.ai/` followed by the source
URL. Versions are tested pins, not claims of latest releases.

| Evidence | Verified use |
| --- | --- |
| [Google builder](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder), [fused client](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient), [21.3.0 AAR](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-location/21.3.0/play-services-location-21.3.0.aar) | Builder, priorities, callback/Looper registration/removal, availability; javap and local compilation against the actual artifact. `setMaxUpdateDelayMillis(0)` becomes the interval in the built request; `isBatched` remains false. |
| [HMS fused reference](https://developer.huawei.com/consumer/en/doc/HMSCore-References/fusedlocationproviderclient-0000001050746169), [request reference](https://developer.huawei.com/consumer/en/doc/HMSCore-References/locationrequest-0000001050986189), [location AAR](https://developer.huawei.com/repo/com/huawei/hms/location/6.16.0.302/location-6.16.0.302.aar), [core AAR](https://developer.huawei.com/repo/com/huawei/hms/LocationLiteSdk/core/2.16.0.302/core-2.16.0.302.aar) | Setter signatures, Context client, callback/Looper, success/failure/cancellation listeners via resolved Tasks artifact, removal and unordered readiness/fix callbacks. Android mock metadata is ORed with HMS `KEY_MOCK_LOCATION`. All signatures compiled; request classes also inspected with javap. |
| [Google availability](https://developers.google.com/android/reference/com/google/android/gms/common/GoogleApiAvailability), [Huawei availability](https://developer.huawei.com/consumer/en/doc/hmscore-common-References/huaweiapiavailability-0000001050121134), [HMS history](https://developer.huawei.com/consumer/en/doc/HMSCore-Guides/version-change-history-0000001050986155) | Both Huawei availability overloads, Google availability, APK-independent fused support and APK-dependent minimum. Numeric version encoding cross-checked against resolved Huawei base AAR constants (SDK 6.13.0.303 = 61300303). |
| [Android Location](https://developer.android.com/reference/android/location/Location), [LocationManager](https://developer.android.com/reference/android/location/LocationManager), [LocationListener](https://developer.android.com/reference/android/location/LocationListener) | Nullable measurements via `has*`, acquisition times, mock API guards, GPS/network requests/removal; local SDK 36 android.jar cross-check. |
| [Background permission](https://developer.android.com/develop/sensors-and-location/location/permissions/background), [PowerManager](https://developer.android.com/reference/android/os/PowerManager), [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager) | Fine/coarse/background grants, power saving and exact-alarm diagnostics with API-level guards. Hosts own background-start eligibility and permission UX. |
| [Location POM](https://developer.huawei.com/repo/com/huawei/hms/location/6.16.0.302/location-6.16.0.302.pom), [core POM](https://developer.huawei.com/repo/com/huawei/hms/LocationLiteSdk/core/2.16.0.302/core-2.16.0.302.pom), [Location codelab](https://developer.huawei.com/consumer/en/codelab/HMSLocationKit/) | Location 6.16.0.302 resolves base 6.13.0.303/core 2.16.0.302; runtime `agconnect-core` 1.9.1.304 is transitive. The codelab makes AGCP conditional on adding AGC JSON. This library applies no AGCP and ships no AGC JSON. Compilation does not prove all cloud-configured runtime paths work without them. |
| [Coroutines 1.10.2 artifact](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2.pom), [Robolectric 4.16 artifact](https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.16/robolectric-4.16.pom) | Resolved artifacts for suspend timeout/cancellation cleanup and JVM Android tests; SDK request builders run under Robolectric. |

No new consumer keep rules are needed for these directly referenced Kotlin classes.
The inspected vendor core AAR already supplies consumer rules, including vendor warning
suppression. A minified consuming app must still be tested; no R8 or device qualification
is implied by this standalone library build. Huawei's optional background-location
notification methods for non-preloaded HMS environments remain a host/device
qualification question; this provider does not silently create another service.

### Testing on an AVD with sideloaded HMS Core

Sources checked **2026-09-14**: [Backbase's 2023 instructions](https://engineering.backbase.com/2023/09/30/installing-hms-core-in-android-studio-emulator/)
use Google APIs API 31 without Play Store, then AppGallery and HMS Core. Current
[Huawei device support](https://consumer.huawei.com/en/support/content/en-us15841699/)
and [update instructions](https://consumer.huawei.com/en/support/content/en-us15841698/)
still direct users to install AppGallery and obtain HMS Core there. That distribution
route remains documented in 2026; a current HMS Core APK binary, signature, version
and x86_64 installability have **not** been downloaded or verified in this task.

This host now has the emulator and `system-images;android-34;default;x86_64`
(verified from installed files). This is an AOSP image, different from Backbase's
Google APIs image, so it is useful for separating platform/HMS from GMS but is not
proof that the 2023 recipe works unchanged. No AVD was booted or APK installed here.

1. Create an API 34 AOSP AVD with the installed SDK tools and start it. Record image,
   ABI, API, emulator version and whether acceleration works. Current Android docs
   prefer the new Android CLI; this host's installed `avdmanager` remains available.
2. Obtain AppGallery from Huawei's official consumer site inside the AVD. Allow the
   required installer permission, then search AppGallery for HMS Core and install it.
   Record actual APK version/signature/ABI and any unsupported-device error. Do not
   substitute an unverified mirror APK or claim installation succeeded from this guide.
3. Install a consuming debug host with a visible location foreground service and
   granted location permissions. Confirm probe results before requesting an explicit
   HMS subscription. Also test AOSP platform with no HMS APK, then fused with no APK
   separately. A Google APIs image needs explicit HMS selection because auto prefers GMS.
4. Inject emulator GPS fixes with `adb -s <serial> emu geo fix <longitude> <latitude>`.
   Check provider tag, acquisition time, optional fields, mock metadata, timeout/removal,
   permission revocation, and late-callback fencing. Do not equate injected fixes with
   real GNSS, indoor network accuracy or EMUI background reliability.
5. Enable the ignored `ProviderDeviceQualificationTest` methods individually only in
   the prepared manual host. They exercise bounded actual acquisition. Qualify minified
   no-AGCP consumption and non-preloaded-HMS background notification behavior separately.
   Two physical HMS-only devices and a GMS control still need screen-off, kill/reboot,
   update, precision and sustained gap/battery tests in the host's service harness.

Emulator command references, retrieved **2026-09-14**:
[AVD manager](https://developer.android.com/tools/avdmanager),
[emulator startup](https://developer.android.com/studio/run/emulator-commandline),
[GPS injection](https://developer.android.com/studio/run/emulator-console).

Local JVM verification command (temporary cache avoids the sandbox's read-only default):

```sh
cd android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=/home/salem/Android/Sdk \
GRADLE_USER_HOME=/tmp/android-tracking-core-gradle \
bash ./gradlew test --max-workers=2
```

Location verification on 2026-09-14: **29 JVM tests passed in debug and 29 in release**;
`compileDebugAndroidTestKotlin` passed. The three manual acquisition tests are ignored
and were not executed. Concurrent workers required a temporary init script setting
`layout.buildDirectory` to `/tmp/android-tracking-core-location-build`, a separate
`--project-cache-dir /tmp/location-project-cache`, and `-Pkotlin.incremental=false`.
These are verification-only isolation settings, not changes to the library toolchain.

The final isolated **full-library** `test` run also passed: **59 debug + 59 release**
tests, zero failures/errors/skips (including 29 location tests per variant).
