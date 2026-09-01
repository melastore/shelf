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

**4. Commit and tag before building the APK you will publish.** The tag must be
annotated, and nothing is pushed yet:

```sh
git commit -m "Release 0.6.2"
git tag -a v0.6.2 -m "Shelf 0.6.2"
```

If anything is wrong at this point, `git tag -d v0.6.2` and start over. Nothing has
left the machine.

**5. Build the APK from the tagged tree.** `git status` must be clean and HEAD must be
the tag, so that what you publish is built from exactly the source F-Droid will fetch:

```sh
./gradlew --no-daemon clean assembleRelease
```

**6. Verify the APK before anything is published.**

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

**7. Push, once the build and the signature both check out:**

```sh
git push origin main
git push origin v0.6.2
```

**8. Publish.** The asset filename matters:

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

**9. Update the F-Droid recipe** last, once the tag exists. See below.

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

### Reproducible builds

Because the recipe uses `Binaries:` with `AllowedAPKSigningKeys`, F-Droid rebuilds from
`commit:` and byte-compares the result against the APK on the release page. Anything in
the build that varies between machines or checkouts fails the whole version.

The one that has already caught us: AGP embeds the git HEAD at build time into
`META-INF/version-control-info.textproto`. An APK built before the `Release X.Y.Z`
commit records the *previous* commit, so F-Droid rebuilding from the tagged commit gets
a different revision string and verification fails on that single line. It is disabled
in `app/build.gradle.kts`:

```kotlin
release {
    vcsInfo { include = false }
}
```

Keep it disabled. To check the build is still deterministic before publishing:

```sh
./gradlew --no-daemon clean assembleRelease
sha256sum app/build/outputs/apk/release/app-release.apk
./gradlew --no-daemon clean assembleRelease
sha256sum app/build/outputs/apk/release/app-release.apk   # must match
```

Three more rules that are easy to get wrong:

- **`commit:` must be the commit the tag points at, and it must exist.** For an
  annotated tag, `git rev-list -n1 v0.6.2` gives the commit; `git rev-parse v0.6.2`
  gives the *tag object*, which is a different hash and will not resolve for F-Droid.
- **Published entries are frozen.** Repointing an existing entry at a newer commit
  cannot fix a failure: the reference APK is whatever was uploaded, so a different
  commit just moves the mismatch. A fix always means a new version, never an edit to an
  old entry. A version whose APK cannot be reproduced from any commit in the repo is
  unverifiable forever, and its entry has to be dropped from the recipe.
- **Check it parses** before opening the merge request:

  ```sh
  python3 -c "import yaml; print(yaml.safe_load(open('io.github.melastore.shelf.yml')))"
  ```

A toolchain difference between this machine and F-Droid's builders can still fail a
version. If a reviewer reports a mismatch that is not explained by the above, the
fallback is to drop `Binaries:` and `AllowedAPKSigningKeys` and let F-Droid build and
sign with its own key. That removes the byte comparison entirely, at the cost of a
different signature from the GitHub APK.
