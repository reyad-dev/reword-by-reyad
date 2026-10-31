# Autofill regression checks

## Credential capture changes — 2026-10-31

The service now advertises `SaveInfo` for login/registration passwords and handles
`onSaveRequest`. New-password fields are captured for saving without filling old
passwords into them. Username-only steps use delayed saving on Android 10+;
save requests combine values from related fill contexts. Android decides when
to show its save UI, normally when the form is submitted or its fields disappear,
rather than after every typed character.

Confirmed saves update/create the account, preserve the vault's original JSON
metadata, encrypt locally, and start a background sync. A locked vault on Android
9+ opens the existing biometric/master-password flow before persisting. Android
8 requires unlocking ReWord first. Native app captures retain their exact package
name for subsequent matching. A manually requested autofill can handle an
unlabelled editable field without enabling suggestions in excluded chat/search/OTP
fields.

Build verification completed using Microsoft JDK 17, Gradle 8.11.1, Android SDK 35
and Build Tools 35.0.0. The missing tools were installed under the user's local
`.cache/reword-android-build` directory, and the ignored `local.properties` SDK
path was corrected. `testDebugUnitTest assembleDebug lintDebug` passed: 19 tests,
0 failures, 0 lint errors and 61 warnings. The standard APK and the side-by-side
APK both report version **1.2.3**, versionCode **6**. Their signatures were verified
and match the repository's 1.2.2 APK certificate. The root `ReWord.apk` now contains
the new standard build; older APKs remain in `android/artifacts`.

Artifacts: `android/artifacts/ReWord-v1.2.3.apk` and
`android/artifacts/ReWord-v1.2.3-autofill-test.apk`. SHA-256 checksums are in
`android/artifacts/SHA256SUMS-v1.2.3.txt`. The standard build log is
`android/artifacts/build-v1.2.3.log`. No USB device was connected, so physical-device
autofill and biometric checks remain pending. Previous build records below apply
only to their stated versions.

For device verification:

1. Register an account with username/email, new password and confirmation. Complete
   the form and accept Android's ReWord save prompt; reopen the vault and verify it.
2. Repeat while locked, with biometric and master-password unlock; rotate during
   unlock and saving. Cancel without creating an account in the vault.
3. Change an existing account's password and verify an update rather than a duplicate.
4. Complete username-first and password-first screens, including native app fields
   with no autofill hints; retry using long-press Autofill where appropriate.
5. Save offline, restart ReWord, then synchronize after restoring connectivity.
6. Verify metadata/notes and other accounts survive a save, and test repeated saves
   and matching a newly captured native package.

Run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` with JDK 17+ and Android SDK 35.

To build a diagnostic app that installs beside the existing app, run `gradlew.bat -I tools/autofill-test.init.gradle :app:assembleDebug`. It uses application ID `com.reword.app.autofilltest` and label **ReWord Autofill Test**. Sync its vault and select this provider in Android's Autofill settings before testing. Its local settings and biometric enrollment are separate from the original app.

The repository's original `ReWord.apk` is signed by a different debug certificate from the key available on this machine. The diagnostic package avoids requiring an uninstall or overwriting the original app's data.

## Android 1.2.2 build verification

Both packages were rebuilt as version **1.2.2**, versionCode **5**. The standard package passed `testDebugUnitTest assembleDebug lintDebug` (14 tests, 0 lint errors, 61 warnings), followed by a successful side-by-side diagnostic APK build. Both APK signatures and their packaged version/application IDs were verified.

- Standard APK: repository-root `ReWord.apk`, also copied to `android/artifacts/ReWord-v1.2.2.apk` locally.
- Diagnostic APK: `android/artifacts/ReWord-v1.2.2-autofill-test.apk` locally.
- Checksums: [SHA256SUMS-v1.2.2.txt](artifacts/SHA256SUMS-v1.2.2.txt).
- GitHub downloads: [Android 1.2.2 release](https://github.com/reyad-dev/reword-app/releases/tag/v1.2.2).

The signing-key limitation and pending physical-device validation described below still apply. The earlier diagnostic artifact is retained locally as a record of the initial verification.

## Verification performed — 2026-10-21

- Diagnostic build: `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed using Gradle 8.11.1, JDK 17, SDK 35, and Build Tools 35.0.0. Build Tools were selected by a temporary local init script; the repository's default build configuration was preserved.
- 14 JUnit tests passed. Lint reported 0 errors and 61 warnings, primarily unused resources and dependency update notices.
- APK signature verified. Package: `com.reword.app.autofilltest`; label: `ReWord Autofill Test`; minimum API 26; target API 35. Packaged service metadata enables inline suggestions.
- Artifact: `android/artifacts/ReWord-autofill-test.apk` (19,883,593 bytes). SHA-256: `2d2b00c6216e4dcd5d80a308be518bdabaea23a1239d222c9d4dfedfdba7097f`.
- Physical biometric/system autofill testing remains pending on the user's Poco F5 Pro running global HyperOS.

The authentication flow now pairs Dataset authentication with a Dataset result, following the [Android autofill authentication contract](https://developer.android.com/identity/autofill/autofill-services). Inline content uses the public [UiVersions.Content](https://developer.android.com/reference/androidx/autofill/inline/UiVersions.Content) interface and checks the keyboard's supported UI version. Vault decryption runs outside the UI thread, and request parsing uses bounded iterative traversal with cancellation and guarded callbacks.

## Device checks

Unit tests cover native/HTML/Arabic credential metadata, email-only screens, chat/search/OTP exclusions, numeric passwords, and domain/package matching boundaries. They do not exercise Android's autofill session or biometric hardware.

On a device, select ReWord as the autofill provider and sync a test vault. Test each case with both an unlocked vault and a locked vault (force-stop/reopen the app to clear its in-memory session):

1. Native login with email and password, including two accounts for the same site: choosing the second account fills its credentials.
2. Email-only first step followed by a password-only screen: suggestions appear on each step.
3. Website login in Chrome/WebView using HTML `autocomplete`, `type`, and `name` attributes: keyboard suggestions appear if the keyboard supports inline autofill; otherwise the dropdown remains available.
4. Locked vault: select ReWord, authenticate, and confirm credentials are filled without another unlock loop. ReWord now uses dataset authentication and returns a Dataset, as required by Android.
5. Biometric lockout, missing enrollment, invalidated key, or pressing “use password”: the master-password dialog opens. A wrong password permits retry; cancellation returns to the original app.
6. Rotate during the biometric prompt and during decryption. The request must continue without a duplicate prompt, blocked UI, or lost callback. Repeat after Android recreates the authentication activity following process death.
7. Unmatched site/native package: ReWord offers an explicit account chooser, including on email-only screens, instead of suggesting arbitrary saved passwords.
8. Chat, search, OTP, new-password, and non-editable password labels: saved login credentials must not be offered.
9. Switch between two login apps while locked: each authentication request must retain its own target field IDs.
10. Visit a domain containing a saved site's name as a substring (for example `example.com.attacker.test`): it must not automatically match `example.com`.

For a remaining crash, capture `adb logcat -b crash -d` immediately after reproduction and record phone model, Android version, app/browser/keyboard names, and the affected field. Do not share vault contents or passwords. Apps that do not expose their fields to Android Autofill cannot be repaired solely by provider-side field detection.
