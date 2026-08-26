# Shelf

[![Android checks](https://github.com/melastore/shelf/actions/workflows/android.yml/badge.svg)](https://github.com/melastore/shelf/actions/workflows/android.yml)

**Hide folders on Android behind an app that looks like something else.**

Shelf hides a folder instantly, whatever its size. It has no network permission,
so nothing can leave your phone. Root makes hiding stronger but is not required.

On your home screen it can be Momento (a habit tracker), a calendar, or a
calculator. All three are real, working apps.

| Private space | Momento | Settings | Recovery |
| --- | --- | --- | --- |
| ![Private space](fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg) | ![Momento](fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg) | ![Settings](fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg) | ![Recovery](fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg) |

## Install

Android 11 or newer. Grab the APK from
[Releases](https://github.com/melastore/shelf/releases), or install it from a
store that carries it.

Shelf has no network access, so it never checks for updates. A store that
carries it will offer them; a hand-installed copy has to be updated by hand.
Every release is signed with the same key:

```
SHA-256  c0a840767117551defe6282499cf23a10c586a42da67a8bb4f038ef297d6a405
```

## Getting in

**Tap the top-right corner five times.** That always works, from any screen.

There is also a quieter way, set in Settings: a long press on the title, or on
an ordinary-looking control in the disguise.

Then enter your credential — a **PIN**, a **password**, a **3×3 pattern**, or a
**knock code** on four unmarked quarters. You pick which at setup and can change
it later in Settings. Fingerprint unlock can be turned on once a credential is
set.

Settings can also put an **app-has-stopped dialog** in front of the unlock
screen. Closing it really closes the app; holding the message carries on.

## Hiding a folder

Add a folder and it stays on your list whether it is hidden or not, so putting it
back out of sight later is one tap. One button at the top hides or unhides
everything at once.

Shelf picks the strongest method your phone allows, or you can choose in Settings:

| Method | Needs | What it does |
| --- | --- | --- |
| **Root** | Root | Strips the folder's permissions. Nothing on the phone can open it. |
| **All-files** | All-files access | Moves the folder into a hidden `.shelf` directory. Instant, and survives uninstalling Shelf. |
| **Rename** | Nothing | Renames the folder with a leading dot. Always available. |

All three also scramble file names and encrypt the first 64 KB of every file, so
even a file manager that finds the folder shows nothing it can open or preview.

## Getting out fast

**Auto hide** closes the private space and puts folders back on its own — after
the screen turns off, immediately when you leave the app, or never.

You can also turn on a quiet notification with a one-tap hide. It carries the
disguise's name and says nothing about a private space. It stays up while any
folder is unhidden, works even after Shelf is closed, and comes back after a
restart.

## The second credential

Optional. It opens a private space that looks and behaves like the real one and
holds nothing that matters — the one to hand over if you are ever forced to.

Nothing in the app calls it a decoy on any screen you could be made to open.
Its use is recorded, and your real space tells you about it next time you get in.
So are wrong credentials that someone else tried.

Guessing gets slower each time: 30 seconds, then a minute, five, fifteen, an hour.

## Read this before you rely on it

**Forget your credential and the contents are gone.** A recovery file brings
folders back into view, but their contents and names stay scrambled — the key is
your credential, and Shelf keeps no copy of it. Nothing can undo that.

**This is not full encryption.** Only the first 64 KB of each file is protected.
The rest is still on disk in the clear and forensic tools can recover it.

Shelf stops someone picking up your phone and browsing it. It does not stop
someone determined, with time and the right tools.

There is more detail, and an honest list of what Shelf does *not* protect
against, in [THREAT_MODEL.md](THREAT_MODEL.md).

## If something goes wrong

**Settings → Recovery → Unhide everything** finds folders Shelf hid but lost
track of and restores them all in one pass. It also offers a health check, a
manual route for dot-renamed folders, and an encrypted export of your records.

## Building

```sh
./gradlew assembleDebug
```

Needs JDK 21 and Android SDK 37. No analytics, ads, or proprietary dependencies.

To build a signed release, copy `keystore.properties.example` to
`keystore.properties`, fill in your keystore details, then:

```sh
./gradlew clean assembleRelease
```

Keep that keystore safe — future updates must be signed with the same key.

## Credits

The idea of configurable camouflage came from
[Amarok-Hider](https://github.com/deltazefiro/Amarok-Hider). The UI, credential
handling, journal, and recovery format here are Shelf's own.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
