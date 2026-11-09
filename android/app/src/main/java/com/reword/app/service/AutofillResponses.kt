package com.reword.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.reword.app.R
import com.reword.app.crypto.SecureStorage
import com.reword.app.model.Account
import com.reword.app.model.Site
import java.util.UUID

/** Builds datasets / responses for both the service and [AutofillAuthActivity]. */
internal object AutofillResponses {
    const val MAX_DATASETS = 20

    /** Everything needed to rebuild a response outside the service (e.g. after the user cancels). */
    data class Target(
        val domain: String?,
        val packageName: String?,
        val appLabel: String?,
        val userField: AutofillId?,
        val passField: AutofillId?,
        val saveInfo: SaveInfo?
    )

    fun presentation(context: Context, title: String, subtitle: String) =
        RemoteViews(context.packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle)
        }

    fun accountDataset(
        context: Context, site: Site, account: Account, target: Target, inline: InlinePresentation? = null
    ): Dataset {
        val title = site.name.ifBlank { site.url }
        val subtitle = account.user.ifBlank { "حساب ReWord" }
        val menu = presentation(context, title, subtitle)
        val builder = Dataset.Builder(menu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inline != null) builder.setInlinePresentation(inline)
        target.userField?.let { builder.setValue(it, AutofillValue.forText(account.user), menu) }
        target.passField?.let { builder.setValue(it, AutofillValue.forText(account.pass), menu) }
        return builder.build()
    }

    /** Dataset without values that opens [AutofillAuthActivity]; the activity returns the real Dataset. */
    fun authDataset(
        context: Context, target: Target, chooseAny: Boolean, title: String, subtitle: String,
        inline: InlinePresentation? = null
    ): Dataset {
        val intent = Intent(context, AutofillAuthActivity::class.java).apply {
            data = Uri.parse("reword-autofill:${UUID.randomUUID()}")
            putExtra(AutofillAuthActivity.EXTRA_DOMAIN, target.domain)
            putExtra(AutofillAuthActivity.EXTRA_PACKAGE, target.packageName)
            putExtra(AutofillAuthActivity.EXTRA_APP_LABEL, target.appLabel)
            putExtra(AutofillAuthActivity.EXTRA_USER_FIELD, target.userField)
            putExtra(AutofillAuthActivity.EXTRA_PASS_FIELD, target.passField)
            putExtra(AutofillAuthActivity.EXTRA_SAVE_INFO, target.saveInfo)
            putExtra(AutofillAuthActivity.EXTRA_CHOOSE_ANY, chooseAny)
        }
        // Separate pending intents keep concurrent sessions from replacing each other's field IDs.
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val auth = PendingIntent.getActivity(context, 0, intent, flags)
        val menu = presentation(context, title, subtitle)
        val dataset = Dataset.Builder(menu).setAuthentication(auth.intentSender)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inline != null) dataset.setInlinePresentation(inline)
        target.userField?.let { dataset.setValue(it, null, menu) }
        target.passField?.let { dataset.setValue(it, null, menu) }
        return dataset.build()
    }

    fun lockedTitle(locked: Boolean) = if (locked) "فتح ReWord 🔒" else "اختيار حساب من ReWord"
    fun lockedSubtitle(locked: Boolean) =
        if (locked) "افتح بالبصمة لملء بيانات الدخول" else "اضغط لاختيار حساب للملء التلقائي"

    /**
     * Response offered again when the user backs out of unlock / the account chooser. Without it Android ends the
     * session and the ReWord suggestion never comes back until the app is restarted.
     */
    fun keepAliveResponse(context: Context, target: Target, storage: SecureStorage): FillResponse {
        val builder = FillResponse.Builder()
        target.saveInfo?.let { builder.setSaveInfo(it) }
        val vault = SecureStorage.activeVault
        var count = 0
        if (vault != null) {
            val linked = storage.getLinkedSiteId(AutofillSiteMatcher.linkKey(target.domain, target.packageName))
            for (site in AutofillSiteMatcher.find(vault, target.domain, target.packageName, linked, target.appLabel)) {
                for (account in site.accounts) {
                    if (count >= MAX_DATASETS) break
                    builder.addDataset(accountDataset(context, site, account, target))
                    count++
                }
            }
        }
        val locked = vault == null
        builder.addDataset(
            if (count > 0) authDataset(context, target, true, "ReWord — حساب آخر", "البحث في كل حسابات الخزنة")
            else authDataset(context, target, false, lockedTitle(locked), lockedSubtitle(locked))
        )
        return builder.build()
    }

    /** Launcher name of the app being filled ("Instagram"), used to recognise apps with odd package names. */
    fun appLabel(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank() || AutofillSiteMatcher.isBrowser(packageName)) return null
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString().trim().takeIf { it.isNotBlank() && it != packageName }
        } catch (_: Exception) { null }
    }
}
