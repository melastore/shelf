# Contributing to Shelf

Thanks for helping improve Shelf. Changes that affect hiding, restoration, credentials, or recovery
should preserve the invariants documented in [THREAT_MODEL.md](THREAT_MODEL.md).

## Development setup

1. Install JDK 17 and the Android SDK required by `compileSdk` in `app/build.gradle.kts`.
2. Clone the repository and run `./gradlew testDebugUnitTest`.
3. Use `keystore.properties.example` only as a template for local release signing. Never commit keys,
   passwords, recovery files, or real folder paths.

## Before opening a pull request

Run the same checks as CI:

```bash
./gradlew --no-daemon spotlessCheck test lint assembleRelease
```

Use `./gradlew spotlessApply` to apply the Kotlin style automatically. Add or update tests for behavior
changes. In particular, hiding changes should cover both the operation and its recovery/rollback path;
authentication changes should cover successful unlock, rejection, and lockout behavior.

Keep user-facing text in Android string or plural resources. Data-layer operations should return typed
failures and warnings so the UI can localize them. Avoid logging credentials, real paths, journal
contents, or recovery material.

## Pull requests

Keep changes focused and explain any storage, permission, or recovery tradeoffs. If a change modifies a
security invariant, update `THREAT_MODEL.md` in the same pull request. Report vulnerabilities through
the private process in [SECURITY.md](SECURITY.md), not a public issue.
