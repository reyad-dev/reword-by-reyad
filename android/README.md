# 📱 ReWord Android Application (Native Kotlin & Jetpack Compose)

تطبيق أندرويد متكامل لنظام **ReWord** مع دعم **الملء التلقائي على مستوى النظام بالكامل (System-wide Android Autofill)** والمصادقة البيومترية ومزامنة سحابية مشفرة.

الإصدار الحالي **1.3.1 (versionCode 8)**: [تحميل ReWord.apk](https://github.com/reyad-dev/reword-by-reyad/releases/latest/download/ReWord.apk) · [أحدث إصدار](https://github.com/reyad-dev/reword-by-reyad/releases/latest). هذا هو ملف التثبيت الرسمي الوحيد المنشور. راجع [خطوات تحديث النسخ القديمة وحماية بيانات الخزنة](artifacts/INSTALL-v1.3.1.md) قبل التثبيت؛ نسختا GitHub القديمتان 1.2.1 و1.2.2 تستخدمان توقيعين مختلفين عن النسخة الحالية.

---

## 🌟 الميزات الرئيسية

1. **الملء التلقائي الشامل (Android Autofill Framework):**
   - خدمة `ReWordAutofillService` المتوافقة مع Android 8.0 Oreo (API 26) فما فوق.
   - تكتشف حقول اسم المستخدم وكلمات المرور في **جميع التطبيقات** (تطبيقات البنوك، فيسبوك، إكس، إنستغرام، إلخ) وفي **متصفحات الهاتف** (Chrome, Firefox, Edge, Samsung Internet).
   - مطابقة ذكية للدومين أو اسم الحزمة البرمجية (Package Name).

2. **الأمان والمصادقة البيومترية (BiometricPrompt & Keystore):**
   - فتح الخزينة وتأكيد الملء عبر **بصمة الإصبع أو بصمة الوجه (Fingerprint / Face ID)** مع الرجوع لكلمة المرور الرئيسية عند الحاجة.
   - حماية مفتاح فك التشفير بواسطة عتاد الهاتف المشفر **Android Keystore (Hardware-backed KeyStore)**.

3. **تطابق تشفيري 100% مع الويب والإكستنشن (`core.js`):**
   - تشفير **AES-256-GCM** مع علامة مصادقة 128-bit.
   - اشتقاق المفاتيح بواسطة **PBKDF2 with HMAC-SHA256 (310,000 تكرار)**.
   - نفس بنية البيانات وحجم الـ Salt (32 بايت) والـ IV (12 بايت).

4. **تزامن سحابي مشفر Zero-Knowledge:**
   - تزامن مع خادم Cloudflare Worker (`/vault`).
   - لا يمكن للخادم قراءة البيانات؛ فالبيانات تخرج مشفرة محلياً قبل إرسالها.

5. **واجهة مستخدم عصرية فائقة السلاسة (Jetpack Compose & Material 3):**
   - ثيم داكن مستوحى من هوية ReWord بألوان متدرجة أنيقة (Dark Slate, Violet & Cyan).
   - بحث فوري في المواقع والحسابات مع إمكانية نسخ اسم المستخدم وكلمة المرور بلمسة واحدة.

---

## 📂 هيكل المشروع (Project Architecture)

```
android/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/
│       │   ├── xml/autofill_service_config.xml
│       │   ├── values/ (strings, colors, themes)
│       │   ├── layout/autofill_dataset_item.xml
│       │   └── drawable/ic_reword_logo.xml
│       └── java/com/reword/app/
│           ├── MainActivity.kt
│           ├── ReWordApplication.kt
│           ├── model/
│           │   └── VaultModels.kt
│           ├── crypto/
│           │   ├── CryptoEngine.kt      (AES-256-GCM + PBKDF2x310k)
│           │   ├── KeystoreHelper.kt    (Hardware Keystore & Biometrics)
│           │   └── SecureStorage.kt     (Encrypted SharedPreferences)
│           ├── network/
│           │   └── SyncRepository.kt    (Cloudflare Worker Sync)
│           ├── service/
│           │   ├── ReWordAutofillService.kt  (Autofill Engine)
│           │   └── AutofillAuthActivity.kt   (Biometric Prompt overlay)
│           └── ui/
│               ├── theme/ (Color, Theme, Type)
│               ├── components/ (TopBar, SiteCard, AccountItem)
│               └── screens/
│                   ├── ConnectScreen.kt
│                   ├── VaultScreen.kt
│                   └── SettingsScreen.kt
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 🚀 طريقة التشغيل والبناء (Build & Run)

### 1. عبر Android Studio (موصى به):
1. افتح مجلد `android` في **Android Studio** (نسخة Iguana / Jellyfish / Koala أو أحدث).
2. سيقوم Android Studio بمزامنة ملفات Gradle تلقائياً (`Sync Project with Gradle Files`).
3. اختر محاكي (Emulator) أو هاتف حقيقي بنظام أندرويد 8.0 أو أحدث.
4. اضغط على زر **Run 'app'** (أو `Shift + F10`).

### 2. تفعيل خدمة الملء التلقائي على الهاتف:
1. بعد فتح التطبيق لأول مرة، انتقل إلى شاشة **الإعدادات (Settings)**.
2. اضغط على **"فتح إعدادات الملء التلقائي بالنظام"**.
3. اختر **ReWord** كخدمة الملء التلقائي الافتراضية للجهاز.
4. الآن عند الوقوف على أي حقل تسجيل دخول في أي تطبيق أو متصفح، ستظهر اقتراحات ReWord تلقائياً.
