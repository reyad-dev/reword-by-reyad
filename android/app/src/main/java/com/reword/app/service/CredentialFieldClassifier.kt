package com.reword.app.service

import android.text.InputType
import java.util.Locale

/** Uses field metadata only: typed text must never change a field's identity. */
internal object CredentialFieldClassifier {
    enum class Kind { USERNAME, PASSWORD, NEW_PASSWORD, CANDIDATE, NONE }

    private val OTP_HINTS = setOf("smsotpcode", "onetimecode", "one-time-code", "2faappotpcode", "otp")

    /** One-time codes, PINs and verification inputs: never filled and never saved as passwords. */
    private val OTP_LABELS = Regex(
        "(^|[^a-z])(otp|totp|mfa|2fa|tfa|pin|pincode|passcode|verification|verify|verif|one.?time|" +
            "auth.?code|sms.?code|security.?code|confirmation.?code|access.?code|token|code)([^a-z]|$)|" +
            "(otp|pin|sms|auth|verify|verification|security|confirm|access|activation)[_-]?code|" +
            "رمز التحقق|رمز التأكيد|كود التحقق|كود التفعيل|رمز التفعيل|الرمز المرسل|رمز مرة|رمز سري|الرمز السري|رمز pin"
    )

    private val PASSWORD_WORDS = listOf("password", "passwd", "pwd", "كلمة المرور", "كلمه المرور", "كلمة_المرور", "كلمة السر", "رمز المرور")

    fun classify(
        inputType: Int,
        editable: Boolean,
        hints: List<String>,
        labels: String,
        htmlType: String? = null,
        maxLength: Int = -1
    ): Kind {
        val normalizedHints = hints.map { it.lowercase(Locale.ROOT) }
        val metadata = labels.lowercase(Locale.ROOT)
        val type = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val html = htmlType?.lowercase(Locale.ROOT)
        if (!editable) return Kind.NONE
        if (normalizedHints.any { it in OTP_HINTS }) return Kind.NONE
        val hasPasswordWord = PASSWORD_WORDS.any { metadata.contains(it) }
        if (!hasPasswordWord && OTP_LABELS.containsMatchIn(metadata)) return Kind.NONE
        // Split OTP boxes ("1 digit per field") are never credentials.
        if (maxLength in 1..3) return Kind.NONE
        // Capture registration passwords for saving, without filling an existing password into them.
        if (normalizedHints.any { it in setOf("new-password", "newpassword") } ||
            Regex("(new|confirm|repeat|create)[_ -]*(password|passwd|pwd)|(password|passwd|pwd)[_ -]*(new|confirm|repeat)|تأكيد.*المرور|مرور.*جديدة").containsMatchIn(metadata)) return Kind.NEW_PASSWORD
        val textPassword = type == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        // Numeric "password" inputs are almost always OTP / PIN boxes; only trust them when labelled as a password.
        val numberPassword = type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (numberPassword) {
            return if (hasPasswordWord || normalizedHints.any { it in setOf("password", "current-password") }) Kind.PASSWORD else Kind.NONE
        }
        if (textPassword || html == "password" || normalizedHints.any { it in setOf("password", "current-password") }) return Kind.PASSWORD
        val excluded = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) ||
            (type == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
                InputType.TYPE_TEXT_VARIATION_FILTER, InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            )) || type == InputType.TYPE_CLASS_DATETIME ||
            html in setOf("search", "hidden", "submit", "button", "checkbox", "radio", "number", "tel-otp", "url", "date")
        if (excluded) return Kind.NONE
        // Explicit native / HTML semantics take priority over incidental resource names.
        if (normalizedHints.any { it in setOf("username", "newusername", "emailaddress", "email", "usernameoremailaddress") } ||
            html == "email" || (type == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            ))) return Kind.USERNAME
        if (listOf("message", "chat", "comment", "search", "query", "compose", "رسالة", "دردشة", "بحث", "تعليق").any { metadata.contains(it) }) return Kind.NONE
        if (hasPasswordWord) return Kind.PASSWORD
        val loginWords = listOf("email", "e-mail", "username", "user_name", "user-name", "userid", "user_id", "login", "phone", "mobile",
            "بريد", "إلكتروني", "اسم المستخدم", "اسم_المستخدم", "هاتف", "موبايل", "جوال")
        if (loginWords.any { metadata.contains(it) }) return Kind.USERNAME
        return Kind.CANDIDATE
    }

    private val OTP_VALUE = Regex("^\\s*[0-9]{3,8}\\s*$")

    /** A short numeric value is a one-time code or PIN, not a password worth saving. */
    fun looksLikeOneTimeCode(value: String): Boolean = OTP_VALUE.matches(value)
}
