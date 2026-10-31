package com.reword.app.service

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialFieldClassifierTest {
    private fun classify(labels: String = "", hints: List<String> = emptyList(), type: Int = InputType.TYPE_CLASS_TEXT, html: String? = null, editable: Boolean = true) =
        CredentialFieldClassifier.classify(type, editable, hints, labels, html)

    @Test fun emailInputOnSeparateScreenIsRecognized() {
        assertEquals(CredentialFieldClassifier.Kind.USERNAME, classify(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }
    @Test fun explicitEmailHintWinsOverResourceName() {
        assertEquals(CredentialFieldClassifier.Kind.USERNAME, classify("search_login_email", listOf("emailAddress")))
    }
    @Test fun browserAutocompleteAndPasswordTypeAreRecognized() {
        assertEquals(CredentialFieldClassifier.Kind.USERNAME, classify(hints = listOf("section-login", "username")))
        assertEquals(CredentialFieldClassifier.Kind.PASSWORD, classify(type = 0, html = "password"))
        assertEquals(CredentialFieldClassifier.Kind.USERNAME, classify(type = 0, html = "email"))
    }
    @Test fun arabicLabelsAreRecognized() {
        assertEquals(CredentialFieldClassifier.Kind.USERNAME, classify("البريد الإلكتروني"))
        assertEquals(CredentialFieldClassifier.Kind.PASSWORD, classify("كلمة المرور"))
    }
    @Test fun pinSubstringDoesNotMisclassifyShippingField() {
        assertEquals(CredentialFieldClassifier.Kind.CANDIDATE, classify("shipping_address"))
    }
    @Test fun numericPasswordStillRecognized() {
        assertEquals(CredentialFieldClassifier.Kind.PASSWORD, classify(type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }
    @Test fun chatAndSearchNeverBecomeLoginCandidates() {
        assertEquals(CredentialFieldClassifier.Kind.NONE, classify("message_input"))
        assertEquals(CredentialFieldClassifier.Kind.NONE, classify(html = "search"))
        assertEquals(CredentialFieldClassifier.Kind.NONE, classify("chat email"))
    }
    @Test fun registrationAndPhoneMetadataAreRecognized() {
        assertEquals(CredentialFieldClassifier.Kind.NEW_PASSWORD, classify(hints = listOf("newPassword")))
        assertEquals(CredentialFieldClassifier.Kind.NEW_PASSWORD, classify("confirm_password", html = "password"))
        assertEquals(CredentialFieldClassifier.Kind.USERNAME, classify(hints = listOf("newUsername")))
        assertEquals(CredentialFieldClassifier.Kind.USERNAME, classify("mobile_number"))
        assertEquals(CredentialFieldClassifier.Kind.NONE, classify("verification_code"))
    }
    @Test fun labelsAndOneTimeCodesAreNotCredentialFields() {
        assertEquals(CredentialFieldClassifier.Kind.NONE, classify("password_label", editable = false))
        assertEquals(CredentialFieldClassifier.Kind.NONE, classify(hints = listOf("one-time-code")))
        assertEquals(CredentialFieldClassifier.Kind.NEW_PASSWORD, classify(hints = listOf("new-password"), html = "password"))
    }
}
