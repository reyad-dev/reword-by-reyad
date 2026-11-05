# Chrome extension 1.3.2

- Fixed popup CSP violations by replacing inline favicon error handlers with an event listener.
- Fixed the quick-add account button focusing a field that no longer exists.
- Removed position/size animation from the autofill shield so it stays aligned with its input during page and container scrolling.
- Hide the shield and close its account list when clipped outside its input's viewport/container; restore the shield when visible again.
- Escape vault text in popup templates so account/site text cannot create HTML or inline event handlers.

## تحديث الإضافة

1. نزّل `ReWord-Chrome-1.3.2.zip` من إصدار `extension-v1.3.2` وفك الضغط.
2. استبدل الملفات داخل نفس مجلد الإضافة الذي حمّلته سابقًا. لا تحذف الإضافة من Chrome.
3. افتح `chrome://extensions` واضغط زر إعادة التحميل ↻ على بطاقة ReWord.
4. تأكد أن الإصدار أصبح `1.3.2`، ثم أعد تحميل صفحات المواقع المفتوحة.
5. امسح الأخطاء القديمة من صفحة أخطاء الإضافة، ثم افتحها وجرّب إضافة حساب والملء التلقائي.

للتثبيت لأول مرة: فعّل وضع المطوّر، ثم اختر **Load unpacked** وحدد المجلد الذي يحتوي على `manifest.json`.

## Validation

- 12 automated regression tests: `node --test extension/tests/autofill.test.cjs` (requires `jsdom@26`).
- Chrome 156 headless: popup under strict CSP, quick-add focus, favicon error fallbacks, immediate shield alignment during page/container scrolling, clipping/reappearance and RTL. Run `node extension/tests/browser-check.cjs` (requires `playwright`, and Chrome at the Windows path specified in the script).
- Browser checks mock the Chrome messaging/storage APIs and use synthetic credentials; cloud sync and a live user vault are outside these checks.
