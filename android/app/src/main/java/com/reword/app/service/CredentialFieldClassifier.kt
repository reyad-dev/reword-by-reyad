package com.reword.app.service

import android.text.InputType
import java.util.Locale

/** Uses field metadata only: typed text must never change a field's identity. */
internal object CredentialFieldClassifier {
    enum class Kind { USERNAME, PASSWORD, NEW_PASSWORD, CANDIDATE, NONE }

    fun classify(
        inputType: Int,
        editable: Boolean,
        hints: List<String>,
        labels: String,
        htmlType: String? = null
    ): Kind {
        val normalizedHints = hints.map { it.lowercase(Locale.ROOT) }
        val metadata = labels.lowercase(Locale.ROOT)
        val type = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val html = htmlType?.lowercase(Locale.ROOT)
        if (!editable) return Kind.NONE
        if (normalizedHints.any { it in setOf("smsotpcode", "onetimecode", "one-time-code") } ||
            Regex("(^|[^a-z])(otp|verification|one.?time)([^a-z]|$)").containsMatchIn(metadata)) return Kind.NONE
        // Capture registration passwords for saving, without filling an existing password into them.
        if (normalizedHints.any { it in setOf("new-password", "newpassword") } ||
            Regex("(new|confirm|repeat|create)[_ -]*(password|passwd|pwd)|(password|passwd|pwd)[_ -]*(new|confirm|repeat)|تأكيد.*المرور|مرور.*جديدة").containsMatchIn(metadata)) return Kind.NEW_PASSWORD
        val password = (type == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        if (password || html == "password" || normalizedHints.any { it in setOf("password", "current-password") }) return Kind.PASSWORD
        val excluded = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) ||
            (type == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
                InputType.TYPE_TEXT_VARIATION_FILTER
            )) || html in setOf("search", "hidden", "submit", "button", "checkbox", "radio")
        if (excluded) return Kind.NONE
        // Explicit native / HTML semantics take priority over incidental resource names.
        if (normalizedHints.any { it in setOf("username", "newusername", "emailaddress", "email", "usernameoremailaddress") } ||
            html == "email" || (type == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            ))) return Kind.USERNAME
        if (listOf("message", "chat", "comment", "search", "query", "compose", "رسالة", "دردشة", "بحث", "تعليق").any { metadata.contains(it) }) return Kind.NONE
        if (listOf("password", "passwd", "pwd", "كلمة المرور", "كلمه المرور", "كلمة_المرور", "رمز المرور").any { metadata.contains(it) }) return Kind.PASSWORD
        if (listOf("email", "e-mail", "username", "user_name", "user-name", "userid", "user_id", "login", "phone", "mobile", "بريد", "إلكتروني", "اسم المستخدم", "اسم_المستخدم", "هاتف", "موبايل").any { metadata.contains(it) }) return Kind.USERNAME
        return Kind.CANDIDATE
    }
}
