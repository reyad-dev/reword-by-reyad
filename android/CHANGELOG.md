# Android releases

## 1.3.3 (versionCode 9) - 2026-11-09

Android:
- Autofill no longer offers to save one-time codes / PINs: OTP fields (hints, labels, numeric-password and 1–3 character boxes) are excluded, and the save prompt is suppressed for 1–8 digit values.
- Apps are recognised by their launcher name and brand-prefixed package names; the account chooser has a search box.
- Cancelling unlock or the chooser keeps the ReWord suggestion available instead of ending autofill for that screen.
- Vault list renders progressively (15 sites at a time) with a fade/slide-in; lighter favicon loading.
- Add / edit / delete accounts in the app, with notes, categories (pick existing or create new) and a password generator; open-site button on each site.
- Fixed a crash on a wrong master password when the local vault was newer than the cloud copy.

Chrome extension 1.3.3:
- Compact popup, site/account counter, category chips and filter, open-site button, notes, edit form, category picker, working show/hide password eye.
- No "save?" prompt for logins already in the vault, even while the vault is locked (after it has been unlocked once with 1.3.3).

Web app: category picker (existing categories as chips) in the add/edit site form.

Validation: `testDebugUnitTest assembleDebug lintDebug` passed (29 tests); extension suite 12 of 12. Android autofill was not tested on a physical phone.

Distribution: `ReWord-v1.3.3.apk` (versionCode 9), same certificate as 1.3.0/1.3.1, so those installs update in place. Older releases were removed. See `artifacts/INSTALL-v1.3.3.md`.

## 1.3.1 (versionCode 8) - 2026-11-05

Android:
- Fixed: Settings showed "خدمة الملء التلقائي غير مفعلة" after ReWord had been selected as the autofill service. The status was read once when the screen opened, so returning from system settings showed the old value. It is now re-checked on every resume, with a fallback that reads the selected service from `Settings.Secure` for OEM builds that report the change late.

Chrome extension 1.3.1:
- After an update, a green **NEW** badge appears on the toolbar icon and the popup shows "تم تحديث الإضافة إلى الإصدار …" with a reminder to refresh open pages. The installed version is shown in the popup footer.

Validation: `testDebugUnitTest assembleDebug lintDebug` passed. The extension suite passed 9 of 9 tests. The autofill status fix was not tested on a physical phone.

Distribution: only the standard `ReWord.apk` (versionCode 8) is published. It is signed with the same certificate as 1.3.0, so 1.3.0 installs update in place. See `artifacts/INSTALL-v1.3.1.md`.

## 1.3.0 (versionCode 7) - 2026-11-04

Android:
- Better app and site recognition. A site's URL field can hold several URLs, and a site without a URL whose name is a domain now matches. Parent domains match their subdomain logins. Well-known apps whose package name differs from their brand (for example Twitter/X, Gmail, TikTok) are recognized. Browsers are never matched by their own package name.
- Matching stays strict on purpose. Sibling subdomains, substrings (`x.com` against `netflix.com`), shared hosting (`alice.github.io` against `bob.github.io`) and brand-only names on the web (`Facebook` against `facebook.xyz`) do not match.
- The app remembers which account you pick for an app or website, so the next request fills it automatically.
- When matching accounts appear, a "ReWord — حساب آخر" entry still lets you choose any account in the vault. The chooser lists matching accounts first, marked ★.
- A save first pulls the newest cloud vault, so edits from other devices are not overwritten. The same username with different case or spacing updates the existing account. Password-only screens update the site's single account instead of adding a nameless one. Saving an unchanged password no longer rewrites the vault.
- Browser compatibility mode is declared for Chrome, Edge, Brave, Opera, Samsung Internet, Vivaldi, DuckDuckGo and other browsers.
- The vault screen refreshes when the autofill service saves or updates an account.

Chrome extension 1.3.0:
- The in-field ReWord button no longer disappears on floating-label, overlay or `pointer-events` layouts. It uses fixed positioning attached to `<html>` and follows scrolling inside inner containers.
- Clicking into a login field opens that site's accounts, but only on real user focus, only in the top frame, and only when the vault is unlocked and has matches. A locked dropdown offers a "فتح الخزينة" button.
- Detection of saved and updated credentials uses the same strict domain matching as autofill. A password change on `accounts.example.com` now updates the account saved for `example.com` instead of creating a duplicate site. Username comparison ignores case and surrounding spaces.
- Security: every fill, save and search handler requires a trusted user event. Vault text in the dropdown is HTML-escaped. Only the current site's accounts are written into the page; other sites appear only after the user types a search.

Validation: `testDebugUnitTest assembleDebug lintDebug` passed (25 tests, 0 failures, 0 lint errors). The extension suite `node --test extension/tests/autofill.test.cjs` passed 9 of 9 tests. The standard APK was installed and launched on an Android emulator (Pixel_10 AVD), and the ReWord autofill service registered. Biometric fill and save on a physical phone were not tested.

Distribution: `ReWord.apk` (versionCode 7) was published; it has been replaced by 1.3.1. Its certificate differs from the published 1.2.1 and 1.2.2 APKs and the documented 1.2.3 certificate. Those installs require a verified vault backup/sync before replacement; same-certificate installs can update in place. Older entries below are historical records; their APK downloads have been retired.

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
