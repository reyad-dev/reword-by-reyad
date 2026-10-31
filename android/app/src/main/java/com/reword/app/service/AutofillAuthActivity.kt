package com.reword.app.service

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
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

    fun save(storage: SecureStorage, domain: String?, app: String?, user: String, pass: String) {
        if (saving || saved.value != null) return
        saving = true
        viewModelScope.launch {
            saved.value = try {
                CredentialSaver.save(storage, domain, app, user, pass)
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
        const val EXTRA_SAVE = "extra_save"
    }

    private lateinit var storage: SecureStorage
    private lateinit var model: AutofillUnlockModel
    private lateinit var biometricPrompt: BiometricPrompt
    private var dialog: AlertDialog? = null
    private var phase = "initial"
    private var userField: AutofillId? = null
    private var passField: AutofillId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        userField = fieldExtra(EXTRA_USER_FIELD)
        passField = fieldExtra(EXTRA_PASS_FIELD)
        if (!intent.getBooleanExtra(EXTRA_SAVE, false) && userField == null && passField == null) { finish(); return }
        try { storage = SecureStorage(this) } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح التخزين الآمن. افتح ReWord وحاول مرة أخرى", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        model = ViewModelProvider(this)[AutofillUnlockModel::class.java]
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

    private fun fieldExtra(name: String): AutofillId? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(name, AutofillId::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(name)
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
        if (intent.getBooleanExtra(EXTRA_SAVE, false)) {
            phase = "saving"
            dialog?.dismiss()
            model.save(storage, intent.getStringExtra(EXTRA_DOMAIN), intent.getStringExtra(EXTRA_PACKAGE),
                intent.getStringExtra("save_user").orEmpty(), intent.getStringExtra("save_pass").orEmpty())
            return
        }
        phase = "chooser"
        val matches = AutofillSiteMatcher.find(vault, intent.getStringExtra(EXTRA_DOMAIN), intent.getStringExtra(EXTRA_PACKAGE))
        val items = (matches.ifEmpty { vault.sites }).flatMap { site -> site.accounts.map { site to it } }
        if (items.isEmpty()) {
            Toast.makeText(this, "لا توجد حسابات مسجلة في الخزنة", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Do not silently choose the first account when several accounts match.
        if (matches.isNotEmpty() && items.size == 1) {
            returnDataset(items.first().first, items.first().second)
            return
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setTitle("اختر الحساب للملء التلقائي")
            .setItems(items.map { (site, account) -> "${site.name.ifBlank { site.url }} (${account.user})" }.toTypedArray()) { _, which ->
                returnDataset(items[which].first, items[which].second)
            }.setNegativeButton(R.string.biometric_prompt_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun returnDataset(site: Site, account: Account) {
        val menu = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(R.id.autofill_title, site.name.ifBlank { site.url })
            setTextViewText(R.id.autofill_subtitle, account.user)
        }
        val builder = Dataset.Builder(menu)
        userField?.let { builder.setValue(it, AutofillValue.forText(account.user), menu) }
        passField?.let { builder.setValue(it, AutofillValue.forText(account.pass), menu) }
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build()))
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
