# MPEI Neo

Lightweight native Android timetable client for MPEI, written with Jetpack Compose and Material 3.

## Current scope

- Search lesson schedules by **group**, **teacher**, or **classroom**.
- Open any search result and browse its schedule week by week.
- Swipe between weekdays; swipe the week-range header to move between weeks.
- Save schedules to **Quick access** and switch between them without searching again.
- Offline-first file cache for already loaded weeks.
- Setting to either refresh the selected schedule on every app launch or stay cache-first.
- Pull down on a loaded schedule to refresh it manually.
- Dynamic Material You / Monet color scheme on Android 12+; system light/dark mode is respected.
- No BARS credentials, Firebase, analytics, advertising SDKs, or account requirement.

## Data source

The app talks directly to the public MPEI timetable API used by the MpeiX backend:

- `GET http://ts.mpei.ru/api/search?term=...&type=group|person|room`
- `GET http://ts.mpei.ru/api/schedule/{type}/{id}?start=yyyy.MM.dd&finish=yyyy.MM.dd&lng=1`

The upstream timetable API is currently HTTP-only. Android cleartext traffic is therefore disabled globally and explicitly allowed only for `ts.mpei.ru` in `network_security_config.xml`.

Transient timetable I/O failures are retried once automatically. Superseded schedule/search calls are cancelled.

## Architecture

- Single lightweight Android app module.
- Compose + Material 3 UI.
- Manual application container instead of a DI framework.
- OkHttp + Gson for the small timetable API.
- Preferences DataStore for favorites, selected schedule, and refresh policy.
- JSON files under internal app storage for week cache; no Room database.

## Tests

- JVM tests validate typed API search, classroom schedule loading, transient network retry, cache-first behavior, forced refresh, cache writes, cancellation, and offline fallback.
- Instrumented Compose smoke test validates navigation.
- LeakCanary instrumentation test closes `MainActivity` and fails if application leaks are retained.

## CI / releases

`.github/workflows/android.yml` runs on pushes and pull requests:

1. Unit tests + Android lint.
2. Feature branches/PRs build a **release APK** signed with an ephemeral test key, so UI smoothness can be tested without debug-build overhead.
3. Emulator UI tests + LeakCanary heap assertion, followed by installing and launching the release APK as a runtime smoke test.
4. A successful push to `main` builds a **release APK**, assigns a monotonically increasing CI `versionCode`, signs it using GitHub Actions secrets, and publishes it as a GitHub prerelease.

Main builds use versions such as `0.2.<GitHub run number>`. With one persistent release key in GitHub Actions secrets, future GitHub Release APKs update in place.

Required repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The signing key must never be committed to the repository. Keep a secure backup: losing it means future APKs cannot update installations signed by that key.

Because older CI debug APKs were signed by runner-specific debug keys, moving from an already-installed old debug APK to the first stable-signed main release requires one uninstall/reinstall. After that transition, release-to-release updates can install over the existing app.

## Local build

Use Android Studio Rabbit 1 / AGP 9.4 compatible tooling, JDK 17, Android SDK 36, and Gradle 9.6:

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

For an optimized local test build signed with the local debug key:

```bash
gradle assembleRelease -PTEST_RELEASE_SIGNING=true -PVERSION_CODE=2 -PVERSION_NAME=0.2.2-test
```

## Status

Schedule-focused prerelease. BARS, maps, mail, QR attendance, and other large features from MpeiX / MpeiApp are intentionally outside the current lightweight scope.
