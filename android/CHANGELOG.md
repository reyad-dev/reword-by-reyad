# Android releases

## 1.2.3 (versionCode 6) - 2026-10-31

- Capture login and registration credentials through Android's native save prompt.
- Update an existing username's password while retaining notes, metadata and unrelated vault fields.
- Persist the encrypted vault and its timestamp together before confirming a save; synchronize in the background.
- Reuse biometric/master-password unlock for saves from a locked vault on Android 9+.
- Recognize new-password, confirmation-password, new-username and phone metadata while excluding OTP fields.
- Retain username-first save contexts on Android 10+ and support manually requested autofill for unlabelled editable login fields.
- Match newly captured native accounts to their exact `androidapp://` package URI.
- Use JDK 17, Android SDK 35 and Build Tools 35.0.0; add regression coverage for credential updates and metadata preservation.

Validation: `testDebugUnitTest assembleDebug lintDebug` passed (19 tests, 0 failures, 0 lint errors and 61 warnings). Both standard and side-by-side APKs built successfully, and their signatures, application IDs and version 1.2.3/versionCode 6 were verified. Physical-device validation remains pending.

Files: repository-root `ReWord.apk`, `android/artifacts/ReWord-v1.2.3.apk`, and `android/artifacts/ReWord-v1.2.3-autofill-test.apk`. Checksums are recorded in `android/artifacts/SHA256SUMS-v1.2.3.txt`.

Installation: the signing key matches the repository's 1.2.2 APK, allowing an in-place update from that APK. A side-by-side **ReWord Autofill Test** package is also provided for devices with an older, differently signed installation. Keep the existing app installed; connect the test package to the same vault and choose it as the system autofill provider.

## 1.2.2 (versionCode 5) - 2026-10-21

- Fix the autofill authentication result so selecting a locked credential can fill after biometric verification.
- Decrypt the vault in the background and retain the unlock operation across activity recreation.
- Fall back to a retryable master-password dialog when biometrics are unavailable or fail.
- Recognize native, HTML and Arabic credential metadata, prioritize focused fields, and enable compatible inline keyboard suggestions.
- Offer an account chooser for unmatched apps and email-only screens. Use consistent domain matching in both the service and unlock screen.
- Bound hierarchy traversal, honor cancellation, isolate request pending intents, and guard service failures.
- Add 14 credential-detection and site-matching regression tests and a reproducible side-by-side diagnostic build.
- Require unit tests and lint in Android CI. CI artifacts no longer overwrite an existing GitHub Release.

Validation: 14 unit tests passed; standard and diagnostic APKs built and signature/version checks passed; lint completed with 0 errors and 61 warnings.

### Downloads and installation

- `ReWord.apk`: standard package `com.reword.app`, version 1.2.2.
- `ReWord-v1.2.2-autofill-test.apk`: independent package `com.reword.app.autofilltest`, labeled **ReWord Autofill Test**. Sync its vault and select it as the autofill provider before testing.
- Both APKs are debug-signed. The original 1.2.1 APK uses a different signing certificate; an in-place update from that APK requires its original signing key, which is unavailable here. The diagnostic APK installs alongside it and keeps the original app's data intact.
- Device validation remains pending on the user's Poco F5 Pro running global HyperOS. Build/unit-test/lint checks cannot verify biometric hardware or app-specific field exposure.
