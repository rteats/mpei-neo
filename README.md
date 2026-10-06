# MPEI Neo

Lightweight native Android timetable client for MPEI, written with Jetpack Compose and Material 3.

## Current scope

- Search lesson schedules by **group**, **teacher**, or **classroom**.
- Open any search result and browse its schedule week by week.
- Save schedules to **Quick access** and switch between them without searching again.
- Offline-first file cache for already loaded weeks.
- Setting to either refresh the selected schedule on every app launch or stay cache-first until the user taps Refresh.
- Dynamic Material You / Monet color scheme on Android 12+; system light/dark mode is respected.
- No BARS credentials, Firebase, analytics, advertising SDKs, or account requirement.

## Data source

The app talks directly to the public MPEI timetable API used by the MpeiX backend:

- `GET http://ts.mpei.ru/api/search?term=...&type=group|person|room`
- `GET http://ts.mpei.ru/api/schedule/{type}/{id}?start=yyyy.MM.dd&finish=yyyy.MM.dd&lng=1`

The upstream timetable API is currently HTTP-only. Android cleartext traffic is therefore disabled globally and explicitly allowed only for `ts.mpei.ru` in `network_security_config.xml`.

## Architecture

- Single lightweight Android app module.
- Compose + Material 3 UI.
- Manual application container instead of a DI framework.
- OkHttp + Gson for the small timetable API.
- Preferences DataStore for favorites, selected schedule, and refresh policy.
- JSON files under internal app storage for week cache; no Room database.

## Tests

- JVM tests validate typed API search, classroom schedule loading, cache-first behavior, forced refresh, cache writes, and offline fallback.
- Instrumented Compose smoke test validates navigation.
- LeakCanary instrumentation test closes `MainActivity` and fails if application leaks are retained.

## CI / releases

`.github/workflows/android.yml` runs on pushes and pull requests:

1. Unit tests + Android lint.
2. Debug APK build.
3. Emulator UI tests + LeakCanary heap assertion.
4. On a successful push to `main`, the verified APK is automatically published as a GitHub prerelease named `MPEI Neo build <run number>`.

## Local build

Use Android Studio Rabbit 1 / AGP 9.4 compatible tooling, JDK 17, Android SDK 37, and Gradle 9.6:

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Status

Initial schedule-focused release. BARS, maps, mail, QR attendance, and other large features from MpeiX / MpeiApp are intentionally outside this first lightweight scope.
