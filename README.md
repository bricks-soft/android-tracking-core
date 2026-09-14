# @bricks-soft/android-tracking-core

## What this library is

`@bricks-soft/android-tracking-core` is a Kotlin Android library shared by host apps and Capacitor plugins. It contains:

- `com.brickssoft.tracking.httpqueue`, a durable SQLite-backed HTTP upload queue.
- `com.brickssoft.tracking.location`, a vendor-neutral location API with Google fused, Huawei fused, and Android platform providers.

It does not provide a Capacitor bridge, request permissions, own a foreground service, choose application endpoints, or define an identity model.

## Public API

### HTTP queue

- `QueueDatabase` persists immutable `QueueConfig` revisions and exposes queue state.
- `QueueClient.register`, `enqueue`, `count`, `purge`, `pause`, and `resume` manage queue data and upload gates.
- `AuthorizationStore.setAuthorization`, `snapshot`, and `clear` manage scoped credential snapshots with strictly increasing revisions.
- `QueueDispatcher.drain` performs an automatic drain. `sync` bypasses automatic thresholds and throws `QueueSyncException` unless the requested scope drains completely.
- `QueueScheduler.initialize`, `requestDrain`, and `onConnectivityChanged` register periodic and connected-network recovery work.
- `Diagnostics` receives bounded operational metadata without payloads, URLs, header values, or server response text.

The built-in encoders are `JSON_RECORD_BATCH` and `SINGLE_REQUEST`. A host using `JSON_RECORD_BATCH` supplies the record JSON, root property, and root-level params. A host using `SINGLE_REQUEST` supplies a rendered request whose URL has the configured origin.

### Location providers

- `CapabilityProbe.snapshot` reads current permission, settings, power, alarm, GMS, HMS, and platform capabilities.
- `ProviderSelector.select` chooses a provider from a capability snapshot.
- `GmsFusedProvider`, `HmsFusedProvider`, and `PlatformProvider` implement `LocationProvider`.
- `LocationProvider.start` returns a `Subscription`; `awaitReady` waits for registration and `remove` fences callbacks before bounded SDK cleanup.
- `LocationProvider.currentPosition` waits for one sufficiently fresh fix and removes its private subscription on success, failure, timeout, or cancellation.

`LocationRequest` carries the host generation, interval, minimum interval, and accuracy. `LocationFix` includes provider, wall and monotonic acquisition times, coordinates, nullable measurements, and mock status.

## Guarantees and policies

- Enqueue commits to SQLite before returning. Records retain their UUID, capture time, scope, and configuration revision through retries and process loss.
- Delivery is at least once within retention. An ambiguous response or process death after a server commit can replay a request, so the host must embed the stable UUID in a server-supported idempotency field.
- Selection is oldest-first by capture time and row ID. A batch never mixes scopes or configuration revisions. A successful 2xx response acknowledges exactly the selected rows.
- Automatic drains honor `autoSync`, `autoSyncThreshold`, `maxBatchSize`, `maxBatchAgeSeconds`, upload gates, retry due times, and a bounded drain budget. Manual sync bypasses automatic thresholds but does not delete unsent data.
- A 401 retains rows and pauses the current authorization revision. A stale 401 cannot pause newer credentials. A 403 blocks the queue. Retryable responses and I/O failures retain rows with bounded exponential backoff. A 413 reduces the persisted batch limit; a singleton 413 blocks.
- `RetentionPolicy` defaults to one day and unlimited count. TTL is fixed from capture time. Expiry and count eviction remove the oldest eligible rows and continue while authorization is paused.
- Endpoints, params, payloads, and immutable headers must not contain credentials. Credential headers are stored separately with Android Keystore AES/GCM. Queue databases and encrypted credential data live under `Context.noBackupFilesDir`; queued payloads are not database-encrypted.
- Upload destinations require HTTPS. `allowCleartext=true` explicitly permits HTTP, and the consuming app must also allow cleartext traffic in its network security configuration.
- The standard worker reconstructs routing, encoders, credentials, transport, and retry policy from disk. Custom transport, cipher, or retry behavior requires a host-owned worker.
- Queue access is supported in one default app process. One owner per canonical database path serializes selection, HTTP, and acknowledgement without holding a SQLite transaction during network I/O.
- Provider selection is capability-based, not manufacturer-based. Automatic selection prefers usable GMS, then initialized HMS fused, then platform only when fallback is enabled. Explicit selection never silently switches provider.
- HMS client initialization is provisional readiness. Registration failure is reported by `awaitReady`, and a first fix can complete readiness because Huawei does not guarantee registration-success ordering before the first result.
- Subscription removal fences future callbacks immediately and then performs bounded SDK cleanup. Concurrent subscriptions remain independent. Hosts own foreground-service lifecycle, permission UX, durable generation checks, and device-specific retry decisions.
- Platform fallback requires an enabled network provider or fine permission with enabled GPS. Power saving, background permission, and exact-alarm state are diagnostics rather than unconditional foreground-acquisition gates.

## Consuming from an app

Install the package, then include its Android module from the host's maintained Gradle settings:

```sh
npm install @bricks-soft/android-tracking-core
```

```groovy
// android/settings.gradle
include ':bricks-soft-android-tracking-core'
project(':bricks-soft-android-tracking-core').projectDir =
    new File(settingsDir, '../node_modules/@bricks-soft/android-tracking-core/android')
```

```groovy
// android/app/build.gradle
dependencies {
    implementation project(':bricks-soft-android-tracking-core')
}
```

The host repositories must include Google Maven, Maven Central, and `https://developer.huawei.com/repo/`. Declare `android.permission.INTERNET` in the consuming app and verify the merged manifest. Use a distinct database name for independent queue consumers.

## Testing

Run the JVM unit tests from the Android module:

```sh
cd android
JAVA_HOME=/home/salem/.jdks/jdk-21.0.12.1+1 \
ANDROID_HOME=/home/salem/Android/Sdk \
GRADLE_USER_HOME=/tmp/workerH-gradle \
./gradlew --project-cache-dir /tmp/workerH-cache -q testDebugUnitTest
```

`ProviderDeviceQualificationTest` contains opt-in bounded acquisition checks for a permission-granted device host. Integrated queue and location smoke/soak testing is provided by the sibling `@bricks-soft/capacitor-location-tracking` plugin:

1. Start `scripts/fake-upload-server.py --mode ok` in the plugin repository.
2. Boot the target emulator, install the consuming debug app and any provider services required by the scenario, and grant its location and notification permissions.
3. From the plugin's `android` directory, run `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.smoke=true -Pandroid.testInstrumentationRunnerArguments.provider=hms` (or `gms`, `platform`, or `auto`).
4. Inject fixes with `adb emu geo fix <longitude> <latitude>` and inspect `adb logcat -s SMOKE`.
5. For a soak run, add `-Pandroid.testInstrumentationRunnerArguments.intervalMs=60000 -Pandroid.testInstrumentationRunnerArguments.runMinutes=120` and inject a fix every 60 seconds.

The default emulator endpoint is `http://10.0.2.2:8787/locations`. If that route is unavailable, run `adb reverse tcp:8787 tcp:8787` and pass the endpoint with `-Pandroid.testInstrumentationRunnerArguments.url=http://127.0.0.1:8787/locations`.

## References

- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://developer.android.com/privacy-and-security/keystore
- https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient
- https://developer.huawei.com/consumer/en/doc/HMSCore-References/fusedlocationproviderclient-0000001050746169
- https://developer.android.com/reference/android/location/LocationManager
