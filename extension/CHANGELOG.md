# Chrome extension 1.3.4

- Fixed repeated save prompts for existing credentials, including locked vaults after the first unlock in a browser session.
- New login identities create separate accounts. Password updates match the saved username or email for the site.
- Captures and restores separate username and email fields alongside the password and site URL. The account editor includes an optional additional email field.
- Pending save prompts stay usable across vault lock/unlock until saved or dismissed, or the tab/browser closes.
- Verified encrypted Chrome storage by saving through the extension UI, restarting the browser, and retrieving the same account.

## Chrome extension 1.3.3

- Compact popup layout: slim header, one-line current-site bar, smaller search and rows, taller vault list with a thin dark scrollbar.
- Added an open-site button (new tab) to site rows and expanded accounts; hidden for androidapp:// and empty URLs.
- Added notes (ملاحظات) to the add form, an edit-account form and the expanded account view; notes are preserved by captures, edits and sync.
- Site/account counter in the header, category badges on sites and a category filter row.
- Category picker in the add/edit form: choose existing categories or add a new one.
- Show/hide password eye now switches icon and works in the login and add forms.
- No "save?" prompt for logins already saved, even while the vault is locked. Uses a salted 24-bit fingerprint index built when the vault is unlocked and kept in memory only (chrome.storage.session, never on disk), so after each browser start unlock once.

## تحديث الإضافة

1. نزّل [ReWord-Chrome-1.3.4.zip](https://github.com/reyad-dev/reword-by-reyad/releases/download/chrome-v1.3.4/ReWord-Chrome-1.3.4.zip) وفك الضغط.
2. استبدل الملفات داخل نفس مجلد الإضافة الذي حمّلته سابقًا. لا تحذف الإضافة من Chrome.
3. افتح `chrome://extensions` واضغط زر إعادة التحميل ↻ على بطاقة ReWord.
4. تأكد أن الإصدار أصبح `1.3.4`، ثم أعد تحميل صفحات المواقع المفتوحة وافتح الخزنة مرة في جلسة المتصفح.
5. امسح الأخطاء القديمة من صفحة أخطاء الإضافة، ثم افتحها وجرّب إضافة حساب والملء التلقائي.

للتثبيت لأول مرة: فعّل وضع المطوّر، ثم اختر **Load unpacked** وحدد المجلد الذي يحتوي على `manifest.json`.

## Validation

- 25 automated regression tests: `node --test extension/tests/autofill.test.cjs` (requires `jsdom@26`).
- Chrome 156 headless: popup under strict CSP, quick-add focus, favicon error fallbacks, immediate shield alignment during page/container scrolling, clipping/reappearance and RTL. Run `node extension/tests/browser-check.cjs` (requires `playwright`, and Chrome at the Windows path specified in the script).
- `node extension/tests/credential-storage.browser.cjs` loads the real extension in a temporary Chromium profile, clicks Save/Update, restarts the browser, and verifies encrypted storage, credential recovery, duplicate suppression and separate new accounts. Requires Playwright Chromium. Set `REWORD_EXTENSION_PATH` to validate an extracted release package.
- Tests use synthetic credentials. Cloud sync and a live user vault require separate validation.
