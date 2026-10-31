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
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.autofill.inline.UiVersions
import com.reword.app.R
import com.reword.app.crypto.SecureStorage
import com.reword.app.model.Account
import com.reword.app.model.Site
import kotlinx.coroutines.*
import java.util.UUID

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
                    p.usernameFields.firstNotNullOfOrNull { p.values[it]?.takeIf(String::isNotBlank) }
                }.orEmpty().trim()
                val pass = related.asReversed().firstNotNullOfOrNull { p ->
                    (p.newPasswordFields + p.passwordFields).firstNotNullOfOrNull { p.values[it]?.takeIf(String::isNotEmpty) }
                } ?: error("Missing password")
                if (SecureStorage.activeMasterPassword != null) {
                    CredentialSaver.save(SecureStorage(this@ReWordAutofillService), latest.webDomain, latest.packageName, user, pass)
                    callback.onSuccess()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val intent = Intent(this@ReWordAutofillService, AutofillAuthActivity::class.java).apply {
                        data = Uri.parse("reword-save:${UUID.randomUUID()}")
                        putExtra(AutofillAuthActivity.EXTRA_SAVE, true)
                        putExtra(AutofillAuthActivity.EXTRA_DOMAIN, latest.webDomain)
                        putExtra(AutofillAuthActivity.EXTRA_PACKAGE, latest.packageName)
                        putExtra("save_user", user)
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

    private fun saveInfo(parsed: ParsedStructure): SaveInfo? {
        val passwords = (parsed.newPasswordFields + parsed.passwordFields).distinct()
        if (passwords.isEmpty()) {
            if (parsed.usernameFields.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, parsed.usernameFields.toTypedArray())
                .setFlags(SaveInfo.FLAG_DELAY_SAVE).build()
        }
        val builder = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME,
            arrayOf(passwords.first()))
        val optional = (parsed.usernameFields + passwords.drop(1)).distinct()
        if (optional.isNotEmpty()) builder.setOptionalIds(optional.toTypedArray())
        return builder.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build()
    }

    private fun createResponse(parsed: ParsedStructure, request: FillRequest): FillResponse? {
        if (parsed.packageName == packageName) return null
        // A manual long-press Autofill request can recover an unlabelled login field.
        if (request.flags and FillRequest.FLAG_MANUAL_REQUEST != 0 && parsed.usernameFields.isEmpty() &&
            parsed.passwordFields.isEmpty() && parsed.newPasswordFields.isEmpty() &&
            parsed.focusedId != null && parsed.focusedId in parsed.editableFields) {
            parsed.usernameFields.add(parsed.focusedId!!)
        }
        val username = parsed.usernameFields.firstOrNull { it == parsed.focusedId } ?: parsed.usernameFields.firstOrNull()
        val password = parsed.passwordFields.firstOrNull { it == parsed.focusedId } ?: parsed.passwordFields.firstOrNull()
        if (username == null && password == null) return saveInfo(parsed)?.let { FillResponse.Builder().setSaveInfo(it).build() }
        // Do not offer credentials while the user is typing into chat/search/OTP fields.
        if (parsed.focusedId != null && parsed.focusedId !in parsed.usernameFields && parsed.focusedId !in parsed.passwordFields) {
            return if (parsed.focusedId in parsed.newPasswordFields) saveInfo(parsed)?.let {
                FillResponse.Builder().setSaveInfo(it).build()
            } else null
        }
        val vault = SecureStorage.activeVault
        val matches = vault?.let { AutofillSiteMatcher.find(it, parsed.webDomain, parsed.packageName) }.orEmpty()
        if (vault == null || matches.isEmpty()) return buildAuthResponse(parsed, username, password, request)

        val builder = FillResponse.Builder()
        saveInfo(parsed)?.let { builder.setSaveInfo(it) }
        var count = 0
        for (site in matches) {
            for (account in site.accounts) {
                if (count >= 20) break
                builder.addDataset(buildDataset(site, account, username, password, request, count++))
            }
        }
        return if (count > 0) builder.build() else buildAuthResponse(parsed, username, password, request)
    }

    private fun presentation(title: String, subtitle: String) = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
        setTextViewText(R.id.autofill_title, title)
        setTextViewText(R.id.autofill_subtitle, subtitle)
    }

    private fun buildDataset(site: Site, account: Account, username: AutofillId?, password: AutofillId?, request: FillRequest, index: Int): Dataset {
        val title = site.name.ifBlank { site.url }
        val subtitle = account.user.ifBlank { "حساب ReWord" }
        val menu = presentation(title, subtitle)
        val builder = Dataset.Builder(menu)
        val inline = createInlinePresentation(request, title, subtitle, index)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inline != null) builder.setInlinePresentation(inline)
        username?.let { builder.setValue(it, AutofillValue.forText(account.user), menu) }
        password?.let { builder.setValue(it, AutofillValue.forText(account.pass), menu) }
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

    private fun buildAuthResponse(parsed: ParsedStructure, username: AutofillId?, password: AutofillId?, request: FillRequest): FillResponse {
        val intent = Intent(this, AutofillAuthActivity::class.java).apply {
            data = Uri.parse("reword-autofill:${UUID.randomUUID()}")
            putExtra(AutofillAuthActivity.EXTRA_DOMAIN, parsed.webDomain)
            putExtra(AutofillAuthActivity.EXTRA_PACKAGE, parsed.packageName)
            putExtra(AutofillAuthActivity.EXTRA_USER_FIELD, username)
            putExtra(AutofillAuthActivity.EXTRA_PASS_FIELD, password)
        }
        // Separate pending intents keep concurrent sessions from replacing each other's field IDs.
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val auth = PendingIntent.getActivity(this, 0, intent, flags)
        val title = if (SecureStorage.activeVault == null) "فتح ReWord" else "اختيار حساب من ReWord"
        val subtitle = "اضغط للتحقق واختيار حساب للملء التلقائي"
        val menu = presentation(title, subtitle)
        // Dataset authentication must be paired with a Dataset result from the activity.
        val dataset = Dataset.Builder(menu).setAuthentication(auth.intentSender)
        val inline = createInlinePresentation(request, title, subtitle, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inline != null) dataset.setInlinePresentation(inline)
        username?.let { dataset.setValue(it, null, menu) }
        password?.let { dataset.setValue(it, null, menu) }
        return FillResponse.Builder().addDataset(dataset.build()).apply {
            saveInfo(parsed)?.let { setSaveInfo(it) }
        }.build()
    }

    private data class ParsedStructure(
        val packageName: String?,
        var webDomain: String? = null,
        var focusedId: AutofillId? = null,
        val usernameFields: MutableList<AutofillId> = mutableListOf(),
        val passwordFields: MutableList<AutofillId> = mutableListOf(),
        val newPasswordFields: MutableList<AutofillId> = mutableListOf(),
        val values: MutableMap<AutofillId, String> = mutableMapOf(),
        val editableFields: MutableList<AutofillId> = mutableListOf()
    )

    private fun parseStructure(structure: AssistStructure, cancellation: CancellationSignal, forSave: Boolean = false): ParsedStructure {
        val parsed = ParsedStructure(structure.activityComponent?.packageName)
        val nodes = java.util.ArrayDeque<AssistStructure.ViewNode>()
        for (i in structure.windowNodeCount - 1 downTo 0) nodes.addLast(structure.getWindowNodeAt(i).rootViewNode)
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
                attributes["name"], attributes["id"], attributes["placeholder"], attributes["aria-label"]).joinToString(" ") { it?.toString().orEmpty() }
            val kind = CredentialFieldClassifier.classify(node.inputType, editable, hints, labels, attributes["type"])
            if (id != null) {
                val value = node.autofillValue
                if (value?.isText == true) parsed.values[id] = value.textValue?.toString().orEmpty()
                when (kind) {
                    CredentialFieldClassifier.Kind.USERNAME -> { parsed.usernameFields.add(id); parsed.editableFields.add(id) }
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
}
