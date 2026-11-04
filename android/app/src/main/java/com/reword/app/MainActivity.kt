package com.reword.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.reword.app.crypto.KeystoreHelper
import com.reword.app.crypto.SecureStorage
import com.reword.app.model.Vault
import com.reword.app.network.SyncRepository
import com.reword.app.ui.screens.ConnectScreen
import com.reword.app.ui.screens.SettingsScreen
import com.reword.app.ui.screens.VaultScreen
import com.reword.app.ui.theme.BgDark
import com.reword.app.ui.theme.ReWordTheme
import kotlinx.coroutines.launch

sealed class Screen {
    object Connect : Screen()
    object VaultView : Screen()
    object Settings : Screen()
}

class MainActivity : FragmentActivity() {

    private lateinit var storage: SecureStorage
    private lateinit var syncRepository: SyncRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        storage = SecureStorage(this)
        syncRepository = SyncRepository(storage)

        setContent {
            ReWordTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgDark
                ) {
                    AppNavigation()
                }
            }
        }
    }

    @Composable
    private fun AppNavigation() {
        var currentScreen by remember {
            mutableStateOf<Screen>(
                if (SecureStorage.activeVault != null) Screen.VaultView else Screen.Connect
            )
        }

        var vaultState by remember { mutableStateOf(SecureStorage.activeVault ?: Vault()) }
        var syncStatus by remember { mutableStateOf("ok") }
        var isConnecting by remember { mutableStateOf(false) }
        var connectError by remember { mutableStateOf<String?>(null) }
        var isBiometricEnabled by remember { mutableStateOf(storage.isBiometricEnabled) }

        // Show accounts saved/updated by the autofill service without reopening the app
        LaunchedEffect(Unit) {
            SecureStorage.vaultFlow.collect { v -> if (v != null) vaultState = v }
        }

        Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
            when (screen) {
                is Screen.Connect -> {
                    ConnectScreen(
                        initialSyncId = storage.syncId ?: "",
                        initialWorkerUrl = storage.workerUrl ?: "https://reword-vault-sync.moasaar6.workers.dev",
                        isLoading = isConnecting,
                        errorMessage = connectError,
                        onConnect = { sid, wurl, masterPass ->
                            isConnecting = true
                            connectError = null
                            storage.syncId = sid
                            storage.workerUrl = wurl

                            lifecycleScope.launch {
                                val result = syncRepository.sync(masterPass)
                                isConnecting = false
                                if (result.isSuccess) {
                                    val vault = result.getOrThrow()
                                    vaultState = vault
                                    // Prompt to enroll Biometric lock if not already enabled
                                    if (!storage.isBiometricEnabled) {
                                        enrollBiometric(masterPass) { enabled ->
                                            isBiometricEnabled = enabled
                                        }
                                    }
                                    currentScreen = Screen.VaultView
                                } else {
                                    connectError = result.exceptionOrNull()?.message ?: "فشلت المزامنة"
                                }
                            }
                        }
                    )
                }

                is Screen.VaultView -> {
                    VaultScreen(
                        vault = vaultState,
                        syncStatus = syncStatus,
                        onSyncClick = {
                            val mp = SecureStorage.activeMasterPassword
                            if (mp != null) {
                                syncStatus = "syncing"
                                lifecycleScope.launch {
                                    val res = syncRepository.sync(mp)
                                    if (res.isSuccess) {
                                        vaultState = res.getOrThrow()
                                        syncStatus = "ok"
                                        Toast.makeText(this@MainActivity, "تمت المزامنة بنجاح", Toast.LENGTH_SHORT).show()
                                    } else {
                                        syncStatus = "error"
                                        Toast.makeText(this@MainActivity, "فشلت المزامنة", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onSettingsClick = {
                            currentScreen = Screen.Settings
                        }
                    )
                }

                is Screen.Settings -> {
                    SettingsScreen(
                        syncId = storage.syncId,
                        workerUrl = storage.workerUrl,
                        isBiometricEnabled = isBiometricEnabled,
                        onToggleBiometric = { enable ->
                            val mp = SecureStorage.activeMasterPassword
                            if (enable && mp != null) {
                                enrollBiometric(mp) { success ->
                                    isBiometricEnabled = success
                                }
                            } else {
                                storage.isBiometricEnabled = false
                                isBiometricEnabled = false
                                KeystoreHelper.deleteKey()
                            }
                        },
                        onLockVault = {
                            SecureStorage.clearSession()
                            currentScreen = Screen.Connect
                        },
                        onDisconnect = {
                            storage.clearAll()
                            vaultState = Vault()
                            currentScreen = Screen.Connect
                        },
                        onBack = {
                            currentScreen = Screen.VaultView
                        }
                    )
                }
            }
        }
    }

    private fun enrollBiometric(masterPassword: String, onComplete: (Boolean) -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("تفعيل البصمة لـ ReWord")
            .setSubtitle("المس مستشعر البصمة لحفظ كلمة المرور الرئيسية بأمان في Keystore")
            .setNegativeButtonText("تخطي")
            .build()

        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                try {
                    val cipher = result.cryptoObject?.cipher
                    if (cipher != null) {
                        val encryptedBytes = cipher.doFinal(masterPassword.toByteArray(Charsets.UTF_8))
                        val iv = cipher.iv
                        storage.saveBiometricEncryptedPassword(encryptedBytes, iv)
                        onComplete(true)
                        Toast.makeText(this@MainActivity, "تم تفعيل المصادقة البيومترية بنجاح ✓", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    onComplete(false)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onComplete(false)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
            }
        })

        try {
            val cryptoObject = KeystoreHelper.createEncryptCryptoObject()
            biometricPrompt.authenticate(promptInfo, cryptoObject)
        } catch (_: Exception) {
            onComplete(false)
        }
    }
}
