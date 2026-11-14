package com.reword.app.service

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import android.view.autofill.AutofillId
import androidx.autofill.inline.UiVersions
import com.reword.app.crypto.SecureStorage
import kotlinx.coroutines.*
import java.util.UUID
import java.util.regex.Pattern

class ReWordAutofillService : AutofillService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val job = scope.launch {
            val response = try {
                val structure = request.fillContexts.lastOrNull()?.structure
                if (structure == null) null else createResponse(parseStructure(structure, cancellationSignal), request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No credentials, field contents, or URLs in diagnostic logs.
                Log.w("ReWordAutofill", "Fill request failed: ${e.javaClass.simpleName}")
                null
            }
            if (!cancellationSignal.isCanceled && isActive) {
                try { callback.onSuccess(response) }
                catch (e: Exception) {
                    Log.w("ReWordAutofill", "Fill callback failed: ${e.javaClass.simpleName}")
                }
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        scope.launch {
            try {
                val contexts = request.fillContexts.map { parseStructure(it.structure, CancellationSignal(), true) }
                val latest = contexts.lastOrNull() ?: error("Missing structure")
                val related = contexts.filter { it.packageName == latest.packageName && it.webDomain == latest.webDomain }
                val user = related.asReversed().firstNotNullOfOrNull { p ->
                    (p.usernameFields.filter { it !in p.emailFields } + p.emailFields)
                        .firstNotNullOfOrNull { p.values[it]?.takeIf(String::isNotBlank) }
                }.orEmpty().trim()
                val email = related.asReversed().firstNotNullOfOrNull { p ->
                    p.emailFields.firstNotNullOfOrNull { p.values[it]?.takeIf(String::isNotBlank) }
                }.orEmpty().trim().takeUnless { it.equals(user, true) }.orEmpty()
                val pass = related.asReversed().firstNotNullOfOrNull { p ->
                    (p.newPasswordFields + p.passwordFields).firstNotNullOfOrNull { p.values[it]?.takeIf(String::isNotEmpty) }
                }
                // Never store one-time codes / PINs, even if a screen slipped through classification.
                if (pass == null || CredentialFieldClassifier.looksLikeOneTimeCode(pass) || pass == user) {
                    callback.onSuccess()
                    return@launch
                }
                val label = AutofillResponses.appLabel(this@ReWordAutofillService, latest.packageName)
                if (SecureStorage.activeMasterPassword != null) {
                    CredentialSaver.save(SecureStorage(this@ReWordAutofillService), latest.webDomain, latest.packageName, user, pass, label, email)
                    callback.onSuccess()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val intent = Intent(this@ReWordAutofillService, AutofillAuthActivity::class.java).apply {
                        data = Uri.parse("reword-save:${UUID.randomUUID()}")
                        putExtra(AutofillAuthActivity.EXTRA_SAVE, true)
                        putExtra(AutofillAuthActivity.EXTRA_DOMAIN, latest.webDomain)
                        putExtra(AutofillAuthActivity.EXTRA_PACKAGE, latest.packageName)
                        putExtra(AutofillAuthActivity.EXTRA_APP_LABEL, label)
                        putExtra("save_user", user)
                        putExtra("save_email", email)
                        putExtra("save_pass", pass)
                    }
                    callback.onSuccess(PendingIntent.getActivity(this@ReWordAutofillService, 0, intent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE).intentSender)
                } else callback.onFailure("افتح خزنة ReWord ثم أعد محاولة الحفظ")
            } catch (_: Exception) {
                callback.onFailure("تعذر حفظ بيانات الدخول في ReWord")
            }
        }
    }

    /**
     * Only offer "Save" for a real password. The validator hides the save prompt when the "password" is a
     * short numeric code (OTP / PIN) or empty, so typing a verification code never asks to save.
     */
    private fun saveInfo(parsed: ParsedStructure): SaveInfo? {
        val passwords = (parsed.newPasswordFields + parsed.passwordFields).distinct()
        if (passwords.isEmpty()) {
            // Username-only first step (e.g. Google: email, then password on the next screen).
            if (parsed.usernameFields.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, parsed.usernameFields.toTypedArray())
                .setFlags(SaveInfo.FLAG_DELAY_SAVE).build()
        }
        val builder = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME,
            arrayOf(passwords.first()))
        val optional = (parsed.usernameFields + passwords.drop(1)).distinct()
        if (optional.isNotEmpty()) builder.setOptionalIds(optional.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            builder.setValidator(RegexValidator(passwords.first(), NOT_ONE_TIME_CODE))
        }
        return builder.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build()
    }

    private fun createResponse(parsed: ParsedStructure, request: FillRequest): FillResponse? {
        if (parsed.packageName == packageName) return null
        // Registration forms collect a new identity and must start with a fresh save response.
        if (parsed.newPasswordFields.isNotEmpty() && parsed.passwordFields.isEmpty()) return saveInfo(parsed)?.let {
            FillResponse.Builder().setSaveInfo(it).build()
        }
        // A manual long-press Autofill request can recover an unlabelled login field.
        if (request.flags and FillRequest.FLAG_MANUAL_REQUEST != 0 && parsed.usernameFields.isEmpty() &&
            parsed.passwordFields.isEmpty() && parsed.newPasswordFields.isEmpty() &&
            parsed.focusedId != null && parsed.focusedId in parsed.editableFields) {
            parsed.usernameFields.add(parsed.focusedId!!)
        }
        val username = parsed.usernameFields.firstOrNull { it !in parsed.emailFields } ?: parsed.usernameFields.firstOrNull()
        val password = parsed.passwordFields.firstOrNull { it == parsed.focusedId } ?: parsed.passwordFields.firstOrNull()
        val save = saveInfo(parsed)
        if (username == null && password == null) return save?.let { FillResponse.Builder().setSaveInfo(it).build() }
        // Do not offer credentials while the user is typing into chat/search/OTP fields.
        if (parsed.focusedId != null && parsed.focusedId !in parsed.usernameFields && parsed.focusedId !in parsed.passwordFields) {
            return if (parsed.focusedId in parsed.newPasswordFields) save?.let {
                FillResponse.Builder().setSaveInfo(it).build()
            } else null
        }
        val label = AutofillResponses.appLabel(this, parsed.packageName) ?: parsed.windowTitle
        val target = AutofillResponses.Target(parsed.webDomain, parsed.packageName, label, username, password, save,
            parsed.emailFields.firstOrNull { it != username }, username in parsed.emailFields)
        val vault = SecureStorage.activeVault
        val linked = SecureStorage(this).getLinkedSiteId(AutofillSiteMatcher.linkKey(parsed.webDomain, parsed.packageName))
        val matches = vault?.let { AutofillSiteMatcher.find(it, parsed.webDomain, parsed.packageName, linked, label) }.orEmpty()
        if (vault == null || matches.isEmpty()) return buildAuthResponse(target, vault == null, request)

        val builder = FillResponse.Builder()
        save?.let { builder.setSaveInfo(it) }
        var count = 0
        for (site in matches) {
            for (account in site.accounts) {
                if (count >= AutofillResponses.MAX_DATASETS) break
                val inline = createInlinePresentation(request, site.name.ifBlank { site.url }, account.user.ifBlank { "حساب ReWord" }, count)
                builder.addDataset(AutofillResponses.accountDataset(this, site, account, target, inline))
                count++
            }
        }
        if (count == 0) return buildAuthResponse(target, false, request)
        // Matching accounts are shown, but the user can still pick any other account from the vault.
        builder.addDataset(AutofillResponses.authDataset(this, target, true, "ReWord — حساب آخر", "البحث في كل حسابات الخزنة",
            createInlinePresentation(request, "ReWord — حساب آخر", "البحث في كل حسابات الخزنة", count)))
        return builder.build()
    }

    private fun createInlinePresentation(request: FillRequest, title: String, subtitle: String, index: Int): InlinePresentation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val inlineRequest = request.inlineSuggestionsRequest ?: return null
        if (index >= inlineRequest.maxSuggestionCount) return null
        val specs = inlineRequest.inlinePresentationSpecs
        val spec = specs.getOrNull(index.coerceAtMost(specs.lastIndex)) ?: return null
        return try {
            if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
            val action = PendingIntent.getActivity(this, 0, Intent(this, com.reword.app.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val content: UiVersions.Content = androidx.autofill.inline.v1.InlineSuggestionUi.newContentBuilder(action)
                .setContentDescription("$title - $subtitle").setTitle(title).setSubtitle(subtitle).build()
            InlinePresentation(content.slice, spec, false)
        } catch (_: Exception) { null }
    }

    private fun buildAuthResponse(target: AutofillResponses.Target, locked: Boolean, request: FillRequest): FillResponse {
        val title = AutofillResponses.lockedTitle(locked)
        val subtitle = AutofillResponses.lockedSubtitle(locked)
        return FillResponse.Builder()
            .addDataset(AutofillResponses.authDataset(this, target, false, title, subtitle,
                createInlinePresentation(request, title, subtitle, 0)))
            .apply { target.saveInfo?.let { setSaveInfo(it) } }
            .build()
    }

    private data class ParsedStructure(
        val packageName: String?,
        var webDomain: String? = null,
        var focusedId: AutofillId? = null,
        var windowTitle: String? = null,
        val usernameFields: MutableList<AutofillId> = mutableListOf(),
        val emailFields: MutableList<AutofillId> = mutableListOf(),
        val passwordFields: MutableList<AutofillId> = mutableListOf(),
        val newPasswordFields: MutableList<AutofillId> = mutableListOf(),
        val values: MutableMap<AutofillId, String> = mutableMapOf(),
        val editableFields: MutableList<AutofillId> = mutableListOf()
    )

    private fun parseStructure(structure: AssistStructure, cancellation: CancellationSignal, forSave: Boolean = false): ParsedStructure {
        val parsed = ParsedStructure(structure.activityComponent?.packageName)
        val nodes = java.util.ArrayDeque<AssistStructure.ViewNode>()
        for (i in structure.windowNodeCount - 1 downTo 0) {
            val window = structure.getWindowNodeAt(i)
            if (parsed.windowTitle == null) parsed.windowTitle = window.title?.toString()?.trim()?.takeIf { it.isNotBlank() }
            nodes.addLast(window.rootViewNode)
        }
        var count = 0
        // Iteration avoids stack overflow on deeply nested app/browser hierarchies.
        while (nodes.isNotEmpty() && count++ < 10000) {
            cancellation.throwIfCanceled()
            val node = nodes.removeLast()
            val domain = node.webDomain?.takeIf { it.isNotBlank() }
            if (parsed.webDomain == null || node.isFocused) {
                if (domain != null) parsed.webDomain = domain
            }
            val id = node.autofillId
            val html = node.htmlInfo
            val attributes = html?.attributes.orEmpty().associate { it.first.lowercase(java.util.Locale.ROOT) to it.second }
            val hints = node.autofillHints.orEmpty().toList() + attributes["autocomplete"].orEmpty().split(' ')
            val htmlInput = html?.tag.equals("input", true)
            val editable = (forSave || node.isEnabled) && (node.inputType != 0 || hints.any {
                it.lowercase(java.util.Locale.ROOT) in setOf("username", "emailaddress", "password", "newpassword", "newusername", "new-password", "current-password", "email")
            } ||
                node.autofillType == android.view.View.AUTOFILL_TYPE_TEXT ||
                node.className.orEmpty().contains("EditText", true) || htmlInput)
            if (node.isFocused && editable) parsed.focusedId = id
            val labels = listOf(node.idEntry, node.hint, node.contentDescription,
                attributes["name"], attributes["id"], attributes["placeholder"], attributes["aria-label"])
                .joinToString(" ") { it?.toString().orEmpty() }
            val nativeMax = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.maxTextLength else -1
            val maxLength = nativeMax.takeIf { it > 0 } ?: attributes["maxlength"]?.toIntOrNull() ?: -1
            val kind = CredentialFieldClassifier.classify(node.inputType, editable, hints, labels, attributes["type"], maxLength)
            if (id != null) {
                val value = node.autofillValue
                if (value?.isText == true) parsed.values[id] = value.textValue?.toString().orEmpty()
                when (kind) {
                    CredentialFieldClassifier.Kind.USERNAME -> {
                        parsed.usernameFields.add(id)
                        if (hints.any { it.equals("email", true) || it.equals("emailAddress", true) } ||
                            attributes["type"].equals("email", true) || Regex("email|e-mail|بريد", RegexOption.IGNORE_CASE).containsMatchIn(labels) ||
                            (node.inputType and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_TEXT &&
                                node.inputType and android.text.InputType.TYPE_MASK_VARIATION in setOf(
                                    android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                    android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))) {
                            parsed.emailFields.add(id)
                        }
                        parsed.editableFields.add(id)
                    }
                    CredentialFieldClassifier.Kind.PASSWORD -> { parsed.passwordFields.add(id); parsed.editableFields.add(id) }
                    CredentialFieldClassifier.Kind.NEW_PASSWORD -> { parsed.newPasswordFields.add(id); parsed.editableFields.add(id) }
                    CredentialFieldClassifier.Kind.CANDIDATE -> parsed.editableFields.add(id)
                    CredentialFieldClassifier.Kind.NONE -> Unit
                }
            }
            for (i in node.childCount - 1 downTo 0) nodes.addLast(node.getChildAt(i))
        }
        val firstPassword = (parsed.newPasswordFields + parsed.passwordFields).firstOrNull()
        if (firstPassword != null && parsed.usernameFields.isEmpty()) {
            val beforePassword = parsed.editableFields.indexOf(firstPassword) - 1
            if (beforePassword >= 0 && parsed.editableFields[beforePassword] !in parsed.newPasswordFields &&
                parsed.editableFields[beforePassword] !in parsed.passwordFields) parsed.usernameFields.add(parsed.editableFields[beforePassword])
        }
        return parsed
    }

    private companion object {
        /** Anything except 1–8 digits (one-time codes and PINs). */
        val NOT_ONE_TIME_CODE: Pattern = Pattern.compile("(?!\\s*[0-9]{1,8}\\s*$)[\\s\\S]+")
    }
}
