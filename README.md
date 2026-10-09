# Cam_Kaze (Kotlin)

Dual-mode camera for Android 13+ (arm64-v8a only). Kotlin + Jetpack Compose (Material 3) + CameraX.
No third-party crypto: AES-256-GCM / PBKDF2 come from `javax.crypto`.

## Build on GitHub
1. Create a repo, push this folder to `main`.
2. **Actions -> Build APK** runs automatically (tests, then `assembleRelease`).
3. Download `CamKaze-arm64-v8a` from the run's *Artifacts*.
4. To publish a release APK: `git tag v1.0.0 && git push --tags`.

Signing: by default the APK is signed with the debug key (installable, fine for testing).
For your own key, add repo secrets `KEYSTORE_BASE64` (base64 of your .jks), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`.

## Small APK
`minSdk 33`, `abiFilters arm64-v8a`, English resources only, R8 minify + resource shrinking,
no image-loading library, no navigation library.

## Modes
* **Normal**: CameraX -> MediaStore `Pictures/cam_kaze`. Gallery grouped by capture day;
  long-press: Share, Move to Secure, Delete, Details (Material bottom sheet).
* **Secure**: password -> PBKDF2-HMAC-SHA256 (600k) -> AES-256-GCM. CameraX returns the JPEG
  in RAM (`ImageProxy`), it is encrypted immediately and only `.kaze` ciphertext is written to
  app-private storage (full image + separate encrypted 256px thumbnail). `FLAG_SECURE` is set
  before any secure screen shows. On pause the key is zeroed and the app returns to the lock screen.

## .kaze format
`"KAZE" | 0x01 | nonce(12) | AES-GCM(ciphertext) | tag(16)`; header is AAD.
Plaintext = int32 rotation (big-endian) + JPEG.

## Known limits
* Not yet built on a device/CI by the author: first CI run may need small fixes.
* JVM cannot guarantee every copy of a password/key is erased from RAM (String/GC).
* "Move to Secure" deletes the original with a normal MediaStore delete (not a secure wipe).
* Forgotten password = lost photos.
* arm64-v8a only: x86 emulators won't run it.
