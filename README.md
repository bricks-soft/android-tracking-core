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
