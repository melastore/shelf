# Releasing Shelf

CI only runs checks. Tagging, building, and publishing are all done by hand, and a
few steps are load-bearing in ways that are not obvious until they break.

## Checklist

**1. Bump the version.** Both fields, together, in `app/build.gradle.kts`:

```kotlin
versionName = "0.6.2"
versionCode = 15
```

**2. Write the changelog.** `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
The file is named after the version *code*, not the name, so 0.6.2 is `15.txt`. One
summary line, a blank line, then bullets. Keep it about what changed for the person
using the app, not what changed in the source.

**3. Run the checks.** The same command `CONTRIBUTING.md` asks contributors for:

```sh
./gradlew --no-daemon clean spotlessCheck test lint assembleRelease
```

**4. Verify the APK before anything is published.**

```sh
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

It must print:

```
c0a840767117551defe6282499cf23a10c586a42da67a8bb4f038ef297d6a405
```

Every release shares that key. A different one silently breaks updates for everyone
who already has Shelf installed, and they have to uninstall to recover.

Steps 1 to 4 come before any commit, so a failed build can never leave a tag behind.

**5. Commit and tag.** The tag must be annotated:

```sh
git commit -m "Release 0.6.2"
git tag -a v0.6.2 -m "Shelf 0.6.2"
git push origin main
git push origin v0.6.2
```

**6. Publish.** The asset filename matters:

```sh
cp app/build/outputs/apk/release/app-release.apk shelf-0.6.2.apk
gh release create v0.6.2 shelf-0.6.2.apk \
  --title "Shelf 0.6.2" --notes-file notes.md
```

It has to be exactly `shelf-<versionName>.apk`, because the F-Droid recipe fetches
`releases/download/v%v/shelf-%v.apk`. A mismatch fails on their side, not ours.

Release notes are the changelog plus two fixed lines:

```
Requires Android 11 or newer.

Signing certificate SHA-256: `c0a840767117551defe6282499cf23a10c586a42da67a8bb4f038ef297d6a405`
```

**7. Update the F-Droid recipe** last, once the tag exists. See below.

## The F-Droid recipe

The recipe lives in the [fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository
as `metadata/io.github.melastore.shelf.yml`, not in this one. Each release adds a
build entry and moves `CurrentVersion` / `CurrentVersionCode`:

```yaml
  - versionName: 0.6.2
    versionCode: 15
    commit: fde5f0f61999af9b0326ac03cbd6398cac851b39
    subdir: app
    gradle:
      - yes
    rm:
      - .github

CurrentVersion: 0.6.2
CurrentVersionCode: 15
```

Three rules that are easy to get wrong:

- **`commit:` must be the commit the tag points at, and it must exist.** For an
  annotated tag, `git rev-list -n1 v0.6.2` gives the commit; `git rev-parse v0.6.2`
  gives the *tag object*, which is a different hash and will not resolve for F-Droid.
- **Published entries are frozen.** `Binaries:` plus `AllowedAPKSigningKeys` means
  F-Droid rebuilds from `commit:` and byte-compares the result against the APK on the
  release page. Repointing an existing entry at a newer commit guarantees a mismatch.
  A fix always means a new version, never an edit to an old entry.
- **Check it parses** before opening the merge request:

  ```sh
  python3 -c "import yaml; print(yaml.safe_load(open('io.github.melastore.shelf.yml')))"
  ```

Because verification is a byte comparison, a toolchain difference between this machine
and F-Droid's builders can fail a version that cannot then be rebuilt identically. If a
reviewer reports a mismatch, the fix is to drop `Binaries:` and let F-Droid build and
sign with its own key instead.
