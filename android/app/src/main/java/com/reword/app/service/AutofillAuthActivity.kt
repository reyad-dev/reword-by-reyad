package com.reword.app.service

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.service.autofill.SaveInfo
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.*
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.*
import com.google.gson.Gson
import com.reword.app.R
import com.reword.app.crypto.CryptoEngine
import com.reword.app.crypto.KeystoreHelper
import com.reword.app.crypto.SecureStorage
import com.reword.app.model.Account
import com.reword.app.model.Site
import com.reword.app.model.Vault
import kotlinx.coroutines.*
import java.util.Locale

/** Retains ongoing decryption across rotation without blocking the UI thread. */
class AutofillUnlockModel : ViewModel() {
    data class Unlocked(val vault: Vault, val password: String)
    val result = MutableLiveData<Result<Unlocked>?>()
    var running = false
        private set
    var biometricInProgress = false
    val saved = MutableLiveData<Result<Unit>?>()
    var saving = false
        private set

    fun save(storage: SecureStorage, domain: String?, app: String?, user: String, pass: String, label: String?, email: String) {
        if (saving || saved.value != null) return
        saving = true
        viewModelScope.launch {
            saved.value = try {
                CredentialSaver.save(storage, domain, app, user, pass, label, email)
                Result.success(Unit)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            saving = false
        }
    }

    fun unlock(blob: String, password: String) {
        if (running) return
        result.value = null
        running = true
        viewModelScope.launch {
            val unlocked = try {
                Result.success(withContext(Dispatchers.Default) {
                    Unlocked(requireNotNull(Gson().fromJson(CryptoEngine.decrypt(blob, password), Vault::class.java)), password)
                })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            running = false
            result.value = unlocked
        }
    }
}

class AutofillAuthActivity : FragmentActivity() {
    companion object {
        const val EXTRA_DOMAIN = "extra_domain"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_USER_FIELD = "extra_user_field"
        const val EXTRA_PASS_FIELD = "extra_pass_field"
        const val EXTRA_EMAIL_FIELD = "extra_email_field"
        const val EXTRA_USER_IS_EMAIL = "extra_user_is_email"
        const val EXTRA_SAVE = "extra_save"
        const val EXTRA_CHOOSE_ANY = "extra_choose_any"
        const val EXTRA_APP_LABEL = "extra_app_label"
        const val EXTRA_SAVE_INFO = "extra_save_info"
    }

    private lateinit var storage: SecureStorage
    private lateinit var model: AutofillUnlockModel
    private lateinit var biometricPrompt: BiometricPrompt
    private var dialog: AlertDialog? = null
    private var phase = "initial"
    private var userField: AutofillId? = null
    private var passField: AutofillId? = null
    private var saveMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        userField = parcelExtra(EXTRA_USER_FIELD, AutofillId::class.java)
        passField = parcelExtra(EXTRA_PASS_FIELD, AutofillId::class.java)
        saveMode = intent.getBooleanExtra(EXTRA_SAVE, false)
        if (!saveMode && userField == null && passField == null) { finish(); return }
        try { storage = SecureStorage(this) } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح التخزين الآمن. افتح ReWord وحاول مرة أخرى", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        model = ViewModelProvider(this)[AutofillUnlockModel::class.java]
        // Back / cancel / leaving the screen keeps the ReWord suggestion instead of ending the autofill session.
        keepSuggestionsOnCancel()
        model.saved.observe(this) { result ->
            if (result == null) return@observe
            Toast.makeText(this, if (result.isSuccess) "تم حفظ بيانات الدخول في ReWord"
                else "تعذر حفظ بيانات الدخول. حاول مرة أخرى", Toast.LENGTH_LONG).show()
            if (result.isSuccess) setResult(Activity.RESULT_OK)
            finish()
        }
        phase = savedInstanceState?.getString("phase") ?: "initial"
        // Reconnect callbacks on recreation; AndroidX retains the active biometric prompt.
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                model.biometricInProgress = false
                if (isFinishing || isDestroyed) return
                try {
                    val encrypted = storage.getBiometricEncryptedPassword() ?: error("Missing biometric data")
                    val cipher = result.cryptoObject?.cipher ?: error("Missing cipher")
                    unlock(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
                } catch (_: Exception) { showMasterPasswordDialog() }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                model.biometricInProgress = false
                if (isFinishing || isDestroyed) return
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> finish()
                    else -> showMasterPasswordDialog()
                }
            }
        })
        model.result.observe(this) { outcome ->
            if (outcome == null) return@observe
            dialog?.dismiss()
            outcome.fold(onSuccess = {
                SecureStorage.setSession(it.vault, it.password)
                // Now unlocked: backing out should offer the matched accounts directly.
                keepSuggestionsOnCancel()
                chooseAccount(it.vault)
            }, onFailure = {
                Toast.makeText(this, "تعذر فتح الخزنة. تأكد من كلمة المرور الرئيسية", Toast.LENGTH_LONG).show()
                showMasterPasswordDialog()
            })
        }
        if (model.result.value != null) return
        if (model.running) { showProgress(); return }
        val vault = SecureStorage.activeVault
        if (vault != null) { chooseAccount(vault); return }
        if (phase == "biometric" && model.biometricInProgress) return
        if (phase == "password") showMasterPasswordDialog() else startBiometric()
    }

    private fun <T : android.os.Parcelable> parcelExtra(name: String, clazz: Class<T>): T? = try {
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(name, clazz)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(name)
    } catch (_: Exception) { null }

    private val appLabel: String? get() = intent.getStringExtra(EXTRA_APP_LABEL)

    private fun target() = AutofillResponses.Target(
        intent.getStringExtra(EXTRA_DOMAIN), intent.getStringExtra(EXTRA_PACKAGE), appLabel,
        userField, passField, parcelExtra(EXTRA_SAVE_INFO, SaveInfo::class.java),
        parcelExtra(EXTRA_EMAIL_FIELD, AutofillId::class.java), intent.getBooleanExtra(EXTRA_USER_IS_EMAIL, false)
    )

    /** Default result: a fresh response so the suggestion re-appears when the user cancels. */
    private fun keepSuggestionsOnCancel() {
        if (saveMode) return
        try {
            val response = AutofillResponses.keepAliveResponse(this, target(), storage)
            setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
        } catch (_: Exception) {
            setResult(Activity.RESULT_CANCELED)
        }
    }

    private fun startBiometric() {
        try {
            val iv = storage.getBiometricIv()
            if (!storage.isBiometricEnabled || iv == null || storage.getBiometricEncryptedPassword() == null ||
                BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
                showMasterPasswordDialog()
                return
            }
            val crypto = KeystoreHelper.createDecryptCryptoObject(iv)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_prompt_title))
                .setSubtitle(getString(R.string.biometric_prompt_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("استخدام كلمة المرور")
                .build()
            phase = "biometric"
            model.biometricInProgress = true
            biometricPrompt.authenticate(info, crypto)
        } catch (_: Exception) {
            model.biometricInProgress = false
            showMasterPasswordDialog()
        }
    }

    private fun showMasterPasswordDialog() {
        if (isFinishing || isDestroyed) return
        phase = "password"
        dialog?.dismiss()
        val input = EditText(this).apply {
            hint = getString(R.string.master_password_label)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val container = FrameLayout(this).apply {
            val padding = (18 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(input)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_name).setMessage("أدخل كلمة المرور الرئيسية لفتح الخزنة")
            .setView(container).setPositiveButton("فتح", null)
            .setNegativeButton(R.string.biometric_prompt_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.create()
        dialog?.setOnShowListener {
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val password = input.text.toString()
                if (password.isBlank()) input.error = "أدخل كلمة المرور" else unlock(password)
            }
        }
        dialog?.show()
    }

    private fun showProgress() {
        phase = "unlocking"
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setTitle(R.string.app_name)
            .setMessage("جارٍ فتح الخزنة…").setView(ProgressBar(this))
            .setNegativeButton(R.string.biometric_prompt_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun unlock(password: String) {
        val blob = storage.vaultBlob
        if (blob == null) {
            Toast.makeText(this, "افتح ReWord وزامن الخزنة أولاً", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        showProgress()
        model.unlock(blob, password)
    }

    private fun chooseAccount(vault: Vault) {
        if (isFinishing || isDestroyed) return
        if (saveMode) {
            phase = "saving"
            dialog?.dismiss()
            model.save(storage, intent.getStringExtra(EXTRA_DOMAIN), intent.getStringExtra(EXTRA_PACKAGE),
                intent.getStringExtra("save_user").orEmpty(), intent.getStringExtra("save_pass").orEmpty(), appLabel,
                intent.getStringExtra("save_email").orEmpty())
            return
        }
        phase = "chooser"
        val domain = intent.getStringExtra(EXTRA_DOMAIN)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val linkKey = AutofillSiteMatcher.linkKey(domain, pkg)
        val matches = AutofillSiteMatcher.find(vault, domain, pkg, storage.getLinkedSiteId(linkKey), appLabel)
        val chooseAny = intent.getBooleanExtra(EXTRA_CHOOSE_ANY, false)
        // Matching sites first (★), then the rest of the vault so any account can still be picked.
        val ordered = matches + vault.sites.filter { it !in matches }
        val items = ordered.flatMap { site -> site.accounts.map { site to it } }
        if (items.isEmpty()) {
            Toast.makeText(this, "لا توجد حسابات مسجلة في الخزنة", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Do not silently choose the first account when several accounts match.
        val matchedAccounts = matches.sumOf { it.accounts.size }
        if (!chooseAny && matchedAccounts == 1) {
            returnDataset(items.first().first, items.first().second)
            return
        }
        showChooser(items, matches, linkKey)
    }

    /** Searchable account picker: typing filters by site name, URL or username. */
    private fun showChooser(items: List<Pair<Site, Account>>, matches: List<Site>, linkKey: String?) {
        dialog?.dismiss()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val labels = items.map { (site, account) ->
            val star = if (site in matches) "★ " else ""
            "$star${site.name.ifBlank { site.url }}\n${account.user.ifBlank { "بدون اسم مستخدم" }}"
        }
        var visible = items.indices.toList()
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels.toMutableList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    textSize = 15f
                }
        }
        val search = EditText(this).apply {
            hint = "ابحث باسم الموقع أو المستخدم…"
            setSingleLine()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val list = ListView(this).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
            divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#33475569"))
            dividerHeight = 1
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                visible = if (q.isEmpty()) items.indices.toList() else items.indices.filter { i ->
                    val (site, account) = items[i]
                    site.name.lowercase(Locale.ROOT).contains(q) || site.url.lowercase(Locale.ROOT).contains(q) ||
                        account.user.lowercase(Locale.ROOT).contains(q)
                }
                adapter.clear()
                adapter.addAll(visible.map { labels[it] })
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val (site, account) = items[visible[position]]
            // Learn the choice: this app/site is recognized automatically next time.
            storage.linkSite(linkKey, site.id)
            returnDataset(site, account)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
            addView(search)
            addView(list)
        }
        val title = appLabel?.let { "اختر حساباً لـ $it" } ?: intent.getStringExtra(EXTRA_DOMAIN)?.let { "اختر حساباً لـ $it" }
            ?: "اختر الحساب للملء التلقائي"
        dialog = AlertDialog.Builder(this).setTitle(title).setView(container)
            .setNegativeButton(R.string.biometric_prompt_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun returnDataset(site: Site, account: Account) {
        val dataset = AutofillResponses.accountDataset(this, site, account, target())
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("phase", phase)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }
}
