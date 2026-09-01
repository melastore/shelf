# Threat model

Shelf is designed to stop casual browsing on an unlocked Android device. It does
not claim to protect against forensic analysis, malware with root access, or an
attacker who can modify the operating system.

## Protected cases

- A gallery or ordinary file manager indexing a hidden folder.
- Casual inspection of a dot-renamed folder in a hidden-file browser. File names
  are replaced with opaque identifiers and their mapping is PIN-encrypted.
- App interruption or power loss during a journal write.
- Tampering with an exported recovery-record file. Exports are authenticated and
  encrypted with a separate recovery passphrase; they do not contain file data.
- Shelf being reinstalled or having its app data cleared after a folder was
  hidden with root or all-files access.
- Being made to open the private space under pressure. The second credential opens a
  space stocked with unremarkable entries, and logs that it was used. It behaves
  like the real one throughout, including hiding and unhiding its rows.
- Opportunistic PIN observation when strong biometric unlock is enabled. A
  biometric always opens the real space; the primary PIN remains the fallback.
- Someone adding their own fingerprint to an unlocked phone in order to open the
  private space. Biometric unlock is bound to a Keystore key that the platform
  invalidates on any enrolment change, so the prompt refuses and the PIN is
  required.
- Restoring content after biometric entry. When enabled, the primary PIN is held
  only as AES-GCM ciphertext whose non-exportable Keystore key requires a strong
  biometric for every decryption. The recovered PIN exists only for the current
  private-space session and is wiped when it closes.
- Emergency re-hiding after Shelf's process has been stopped. Only while a tracked
  folder is exposed, Shelf keeps a temporary copy of the primary PIN encrypted by
  a non-exportable Android Keystore key in app-private storage. The ciphertext is
  deleted as soon as every tracked folder is hidden. This capability exists only
  while the protected content itself is already exposed.
- Screenshots, screen recordings, and the recent apps preview. The private space
  and the credential keypad set `FLAG_SECURE`, so Android renders them as a blank
  frame to anything trying to capture the screen. **Settings → Screen capture →
  Allow screenshots** lifts this deliberately, for taking pictures of the folder
  list; it never applies to the keypad, and it is dropped when the private space
  closes rather than stored, so it cannot be left on by accident.
- Someone reading the phone's notification shade or lock screen while the private
  space is open. The optional notification carries the disguise's name and says
  nothing about a private space, and its channel is named the same way in system
  settings, where it stays listed permanently.

## Out of scope

- Full-file confidentiality. Shelf encrypts only the leading 64 KiB; content
  beyond that remains recoverable with forensic tools.
- Erasing every historical copy of a file name. Original names can remain in
  filesystem metadata, MediaStore, thumbnails, backups, logs, or other app caches.
- Concealing directory names inside the selected folder. Only file names are
  randomized; directory names remain visible to make interrupted recovery safe.
- Root or physical attacks. Data beyond each protected header remains plaintext
  on disk.
- Concealing that Shelf itself is installed from someone inspecting the device.
- Offline guessing of a short credential by an attacker who can read app-private
  files. A four-digit PIN has only 10,000 combinations and a four-dot pattern
  fewer; a password is the only one of the three that can be made long enough to
  resist this. The persisted attempt delay only limits guesses made through
  Shelf's own interface. That delay is measured against uptime rather than the
  wall clock, so changing the device's date does not clear it.
- A compromised operating system or root process observing the PIN after a
  successful biometric match. Keystore protects the encrypted PIN at rest, not
  a process that can inspect Shelf while it is legitimately using the plaintext.
- The list of folders Shelf tracks. It outlives a restore so folders can be
  hidden again in one tap, which means app-private storage names every folder the
  user has ever added, whether or not any of them are hidden right now.
- A forgotten credential. It is the key file headers and the filename manifest are
  encrypted under, and no copy is kept that does not need it. The biometric and
  emergency wrappers both require the device and an unlocked session to have
  existed first. A recovery file restores records, not contents.
- The second credential against an adversary who already knows Shelf has one. It
  buys a plausible answer, not proof that nothing else exists. No screen an
  adversary could make the owner open names the feature as a decoy, but the app
  is open source and the mechanism is public.

## Storage methods

Root `chmod` is the strongest folder-hiding method. The all-files method moves a
folder under random names inside `/storage/emulated/<user>/.shelf`; it survives
uninstall but remains discoverable to other apps with all-files access. The SAF
fallback adds a leading dot and is therefore concealment, not access control.
After a primary-PIN hide, all three methods also encrypt each non-empty file's
leading 64 KiB with authenticated recovery data appended first. They replace
file names with random identifiers and keep the original relative names in an
authenticated, PIN-encrypted manifest written before any rename. This defeats
ordinary filename browsing, previews, and opening, but is not full-file
confidentiality or forensic erasure.

The journal is committed before a folder changes, and failed or uncertain writes
retain recovery state instead of deleting it. Restores always use the method
that hid the folder, never whatever the device can do best today.
