# Shelf

[![Android checks](https://github.com/melastore/shelf/actions/workflows/android.yml/badge.svg)](https://github.com/melastore/shelf/actions/workflows/android.yml)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-brightgreen.svg)](#install)

**Hide folders on Android behind an app that looks like something else.**

Shelf hides a folder in a moment, whatever its size. On your home screen it can be
Momento (a habit tracker), a calendar, or a calculator. All three are real,
working apps. It has no network permission, so nothing can leave your phone.
Root makes hiding stronger but is not required.

| Private space | Momento | Settings | Recovery |
| --- | --- | --- | --- |
| ![Private space](fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg) | ![Momento](fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg) | ![Settings](fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg) | ![Recovery](fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg) |

## What it does

- **Instant.** Hiding is a permission change or a rename, never a copy. Forty
  gigabytes of video takes as long as four photos.
- **Four ways to unlock.** PIN, password, 3x3 pattern, or a knock code on four
  unmarked quarters. Fingerprint too, once a credential is set.
- **Three disguises.** A habit tracker, a calendar, or a calculator, each with
  its own name, icon, and colours. Or no disguise at all.
- **A second credential.** Opens a private space that looks real and holds
  nothing that matters. The one to hand over if you are ever forced to.
- **Auto hide.** Puts folders back on its own after the screen turns off, or the
  moment you leave the app.
- **No network, no accounts, no ads.** No analytics and no proprietary
  dependencies. The only optional permission is all-files access.

## Install

Android 11 or newer.

Download the APK from [Releases](https://github.com/melastore/shelf/releases),
or install it from a store that carries it. The repository ships F-Droid
metadata under [`fastlane/`](fastlane/metadata/android/en-US), so a build from
source matches the published listing.

Shelf has no network access, so it never checks for updates itself. A store will
offer them; a hand-installed copy has to be updated by hand.

Every release is signed with the same key:

```
SHA-256  c0a840767117551defe6282499cf23a10c586a42da67a8bb4f038ef297d6a405
```

## Getting in

**Tap the top-right corner five times.** That always works, from any screen.

Settings adds a quieter second way: a long press on the title, or on an
ordinary-looking control inside the disguise.

Then enter your credential:

| Type | What it is |
| --- | --- |
| **PIN** | 4 to 12 digits on a keypad. |
| **Password** | 6 to 128 characters. |
| **Pattern** | A 3x3 grid, drawn like the Android lock screen. |
| **Knock code** | Taps on four unmarked quarters, repeats allowed. |

You pick one at setup and can change it later. The knock pad stays blank while
you tap, so anyone watching learns neither the length of your code nor how far
through it you are.

Guessing gets slower each time: 30 seconds, then a minute, five, fifteen, an
hour. Wrong attempts are recorded, and your real space tells you about them the
next time you get in.

Settings can also put an **app-has-stopped dialog** in front of the unlock
screen. It is laid out like the real one. Closing it really closes the app.
Holding the message is what carries on.

## Hiding a folder

Add a folder and it stays on your list whether it is hidden or not, so putting
it back out of sight later is one tap. One button at the top hides or unhides
everything at once.

Shelf picks the strongest method your phone allows, or you can choose in
Settings:

| Method | Needs | What it does |
| --- | --- | --- |
| **Root** | Root | Strips the folder's permissions. Nothing on the phone can open it. |
| **All-files** | All-files access | Moves the folder into a hidden `.shelf` directory. Survives uninstalling Shelf. |
| **Rename** | Usually nothing | Renames the folder with a leading dot. The fallback that works with no permission at all. |

All three also scramble file names and encrypt the first 64 KB of every file, so
a file manager that finds the folder shows nothing it can open or preview.

> **One catch on Rename.** It works by asking for access to the folder *above*
> the one you are hiding, and Android refuses to hand out access to the top of
> internal storage or to Download. A folder sitting directly in either of those
> needs all-files access instead. Anything nested deeper is fine.

## Getting out fast

**Auto hide** closes the private space and puts folders back on its own: after
the screen turns off, immediately when you leave the app, or never.

You can also turn on a quiet notification with a one-tap hide. It carries the
disguise's name and says nothing about a private space. It appears only while a
folder is actually sitting in the open, works after Shelf is closed, and comes
back after a restart.

## Blending in

Three settings decide how much of Shelf anyone else sees.

- **Disguise.** Momento, the calendar, the calculator, or none. Each keeps its
  own name, icon, and colours.
- **Theme.** Follow system, Light, Dark, or AMOLED. AMOLED is dark on true
  black, which costs an OLED screen nothing to draw. Disguises keep their own
  colours in every mode, because a calculator in the private space's teal is not
  much of a calculator.
- **Hide from recents.** Keeps Shelf out of the recent apps list and stops the
  system holding a picture of it. Screenshots of the private space are blocked
  already, unless you turn them on for a session.

## The second credential

Optional. It opens a private space that looks and behaves like the real one and
holds nothing that matters. This is the one to hand over if you are ever forced
to.

Nothing in the app calls it a decoy on any screen you could be made to open. Its
use is recorded, and your real space tells you about it the next time you get in.

## Read this before you rely on it

**Forget your credential and the contents are gone.** A recovery file brings
folders back into view, but their contents and names stay scrambled. The key is
your credential, and Shelf keeps no copy of it. Nothing can undo that.

**This is not full encryption.** Only the first 64 KB of each file is protected.
The rest is still on disk in the clear, and forensic tools can recover it.

Shelf stops someone picking up your phone and browsing it. It does not stop
someone determined, with time and the right tools. There is more detail, and an
honest list of what Shelf does *not* protect against, in
[THREAT_MODEL.md](THREAT_MODEL.md).

## If something goes wrong

**Settings → Recovery → Unhide everything** finds folders Shelf hid but lost
track of and restores them all in one pass. The same screen offers a health
check, a manual route for dot-renamed folders, and an encrypted export of your
records.

## Building

Needs JDK 21 and Android SDK 37.

```sh
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # tests
./gradlew spotlessApply        # format
```

To build a signed release, copy `keystore.properties.example` to
`keystore.properties`, fill in your keystore details, then:

```sh
./gradlew clean assembleRelease
```

Keep that keystore safe. Future updates must be signed with the same key.

## Contributing

Bug reports and patches are welcome. Please read
[CONTRIBUTING.md](CONTRIBUTING.md) first, and
[SECURITY.md](SECURITY.md) before reporting anything sensitive.

## Credits

The idea of configurable camouflage came from
[Amarok-Hider](https://github.com/deltazefiro/Amarok-Hider). The UI, credential
handling, journal, and recovery format here are Shelf's own.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
