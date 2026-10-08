# Release channels

MPEI Neo uses two Android application channels.

## Dev

- App name: **MPEI Neo Dev**
- Application ID: `com.rteats.mpeineo.dev`
- Update channel: `dev`
- Built automatically on every push to `main`
- Signed with the public disposable key in `ci/mpei-neo-dev.p12.b64`
- Published as the rolling GitHub prerelease tagged `dev`
- Version format: `0.4.0-dev.<workflow run number>`

The rolling dev release is intended for rapid device testing. The `dev` tag is force-moved
to the newest main commit and the fixed `MPEI-Neo-Dev.apk` asset is replaced.

## Stable

- App name: **MPEI Neo**
- Application ID: `com.rteats.mpeineo`
- Update channel: `stable`
- Signed only with the protected production signing secrets
- Built by `.github/workflows/stable-release.yml`
- Runs unit tests, lint and an emulator smoke test before publishing
- Version format: semantic `MAJOR.MINOR.PATCH`, for example `0.4.0`

A stable release can be started either by pushing a `vMAJOR.MINOR.PATCH` tag or through
the manual workflow dispatch input. Stable Android `versionCode` is derived as:

`major * 1,000,000 + minor * 1,000 + patch`.

Dev and stable are separate Android apps and can be installed side by side.
Their app data, WebView cookies and BARS sessions are intentionally independent.
