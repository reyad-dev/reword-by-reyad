# Chrome extension 1.3.3

- Compact popup layout: slim header, one-line current-site bar, smaller search and rows, taller vault list with a thin dark scrollbar.
- Added an open-site button (new tab) to site rows and expanded accounts; hidden for androidapp:// and empty URLs.
- Added notes (ملاحظات) to the add form, an edit-account form and the expanded account view; notes are preserved by captures, edits and sync.
- Site/account counter in the header, category badges on sites and a category filter row.
- Category picker in the add/edit form: choose existing categories or add a new one.
- Show/hide password eye now switches icon and works in the login and add forms.
- No "save?" prompt for logins already saved, even while the vault is locked. Uses a salted 24-bit fingerprint index built when the vault is unlocked, so unlock once after updating.

## تحديث الإضافة

1. نزّل `ReWord-Chrome-1.3.3.zip` من إصدار `v1.3.3` وفك الضغط.
2. استبدل الملفات داخل نفس مجلد الإضافة الذي حمّلته سابقًا. لا تحذف الإضافة من Chrome.
3. افتح `chrome://extensions` واضغط زر إعادة التحميل ↻ على بطاقة ReWord.
4. تأكد أن الإصدار أصبح `1.3.3`، ثم أعد تحميل صفحات المواقع المفتوحة.
5. امسح الأخطاء القديمة من صفحة أخطاء الإضافة، ثم افتحها وجرّب إضافة حساب والملء التلقائي.

للتثبيت لأول مرة: فعّل وضع المطوّر، ثم اختر **Load unpacked** وحدد المجلد الذي يحتوي على `manifest.json`.

## Validation

- 12 automated regression tests: `node --test extension/tests/autofill.test.cjs` (requires `jsdom@26`).
- Chrome 156 headless: popup under strict CSP, quick-add focus, favicon error fallbacks, immediate shield alignment during page/container scrolling, clipping/reappearance and RTL. Run `node extension/tests/browser-check.cjs` (requires `playwright`, and Chrome at the Windows path specified in the script).
- Browser checks mock the Chrome messaging/storage APIs and use synthetic credentials; cloud sync and a live user vault are outside these checks.
