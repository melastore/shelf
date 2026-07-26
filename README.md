# Shelf

Shelf is a private-space utility that can present itself as **Momento**, a
calendar, or a calculator. All three decoys work as ordinary local apps. Shelf
can hide folders and make individual files unreadable without sending data off
the device. Root improves folder hiding, but is optional.

## Using the private space

The default entry gesture is a long press on the decoy title. Inside the private
space, Settings can change that to five taps in the top-right corner or a long
press on a natural decoy control. Access uses a 4–12 digit vault PIN. An optional
different decoy PIN quietly returns to the public screen when entered under
pressure. Older installs can use their existing passphrase once, then migrate to
a PIN from Settings.

The selected decoy changes its launcher label and icon. Calendar events and
habits stay on-device, and the calculator performs ordinary chained arithmetic.

The quiet default is SAF. In Settings, the user can explicitly select root,
all-files, or automatic strongest-method selection:

- **Root:** clears permission bits on `/data/media/<user>` after recording the
  original mode and owner.
- **All-files access:** moves the folder into a persistent `.shelf` directory on
  the same volume. The move is instant and survives uninstalling the app.
- **Storage Access Framework:** renames the folder with a leading dot. This keeps
  it out of galleries, but file managers configured to show hidden files can see it.

Every operation is journalled before the folder is changed. Records are written
through a synced temporary file, and the last valid generation is retained. Root
and all-files hides also leave a small recovery marker with the folder so Shelf
can rebuild an empty journal after reinstalling or clearing app data.

File locking encrypts the first MiB with AES-256-GCM and a PBKDF2-derived key. A
sealed recovery copy and the parameters needed to reverse the operation are
appended to the same file. This makes recovery possible after a torn write,
reinstall, or cleared app data: open **Settings → Recovery**, select the file,
and enter its original file passphrase.

## What it does not do

Shelf is not full-file or full-disk encryption. Hidden folders remain plaintext,
and a locked file's body after its first MiB is unchanged. The recovery trailer
also makes a Shelf-locked file identifiable under forensic inspection. This app
is intended to stop casual browsing, not a determined attacker with physical or
root access.

A four-digit PIN is convenient but has only 10,000 combinations. Shelf slows
repeated in-app attempts, but a longer PIN is recommended and app-private
credential data is not designed to resist an attacker with root access.

Do not edit, truncate, or move a locked file through software that rewrites its
contents. Keep the file passphrase: Shelf cannot reset it.

## Building

    ./gradlew assembleDebug

Requires JDK 17 and Android SDK platform 36. The release build has no network,
analytics, advertising, or proprietary runtime dependency.

### Signing a direct release

Create a private keystore, copy `keystore.properties.example` to
`keystore.properties`, and fill in its four values. Both the properties file
and common keystore formats are ignored by Git. Then run:

    ./gradlew clean assembleRelease

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`.
Keep the keystore and its password backed up: future direct-install updates
must be signed with the same key.

## Acknowledgements

Shelf's configurable camouflage was inspired by the public ideas in
[Amarok-Hider](https://github.com/deltazefiro/Amarok-Hider). Shelf's Compose UI,
credential handling, storage journal, and recovery format are independent.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
