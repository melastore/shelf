# Threat model

Shelf is designed to stop casual browsing on an unlocked Android device. It does
not claim to protect against forensic analysis, malware with root access, or an
attacker who can modify the operating system.

## Protected cases

- A gallery or ordinary file manager indexing a hidden folder.
- Someone opening a media file without its lock passphrase.
- App interruption or power loss during a journal or header write.
- Shelf being reinstalled or having its app data cleared after a new-format file
  was locked.

## Out of scope

- Full-file confidentiality. Only the first MiB is encrypted.
- Root or physical attacks. Folder contents remain plaintext on disk.
- Hiding the existence of a locked file from forensic inspection. Its recovery
  trailer has a versioned marker.
- Recovery without the user's file passphrase.

## Storage methods

Root `chmod` is the strongest folder-hiding method. The all-files method moves a
folder into `/storage/emulated/<user>/.shelf`; it survives uninstall but remains
visible to other apps with all-files access. The SAF fallback only adds a leading
dot and is therefore concealment, not access control.

The journal is committed before a folder changes. A locked file receives its
recovery trailer before its header is overwritten. Failed or uncertain writes
retain recovery state instead of deleting it.
