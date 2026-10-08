# Development signing key

`mpei-neo-dev.p12.b64` is intentionally a **public, disposable development key**.

It signs only the `com.rteats.mpeineo.dev` application ID used by automated dev builds.
It must never be used for the stable `com.rteats.mpeineo` package.

The key is committed so every CI dev build has the same signature and can update the
previous dev build in-place. Anyone can sign an APK with this development identity, so
dev builds must not be treated as trusted production artifacts.

Keystore settings used by CI:

- type: PKCS12
- alias: `mpei-neo-dev`
- store/key password: `mpei-neo-dev`
