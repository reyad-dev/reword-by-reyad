package com.reword.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reword.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    syncId: String?,
    workerUrl: String?,
    isBiometricEnabled: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    onLockVault: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Re-check on every resume: the user enables the service in system Settings and
    // returns here, so a value computed once at first composition would stay stale.
    var isAutofillEnabled by remember { mutableStateOf(checkAutofillEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAutofillEnabled = checkAutofillEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = TextPrimary)
                    }
                },
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Section 1: Android System Autofill & Gboard (Primary / Recommended) ──
            Text(
                text = "الملء التلقائي للنظام ولوحة المفاتيح (موصى به)",
                style = MaterialTheme.typography.titleMedium,
                color = AccentCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, if (isAutofillEnabled) PrimaryViolet.copy(alpha = 0.5f) else BorderDark, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isAutofillEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isAutofillEnabled) SuccessEmerald else WarningAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isAutofillEnabled) "الملء التلقائي للنظام مفعل ✓" else "خدمة الملء التلقائي غير مفعلة",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "يعرض اقتراحات الحسابات تلقائياً بجانب حقول اسم المستخدم وكلمة المرور في المتصفحات والتطبيقات، أو في شريط الاقتراحات أعلى لوحة مفاتيح جوجل (Gboard) دون الحاجة لأي أيقونة عائمة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { openAutofillSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet)
                    ) {
                        Icon(Icons.Default.SettingsSuggest, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isAutofillEnabled) "تغيير إعدادات الملء التلقائي" else "تفعيل ReWord كخدمة الملء التلقائي", color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 4: Biometrics ──
            Text(
                text = "الأمان البيومتري (Biometrics)",
                style = MaterialTheme.typography.titleMedium,
                color = AccentCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "فتح الخزينة بالبصمة أو الوجه",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "تأمين مفتاح فك التشفير عبر Hardware Keystore",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = isBiometricEnabled,
                        onCheckedChange = onToggleBiometric,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = PrimaryViolet,
                            uncheckedTrackColor = SurfaceDark
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 5: Sync Details ──
            Text(
                text = "معلومات المزامنة (Cloudflare Sync)",
                style = MaterialTheme.typography.titleMedium,
                color = AccentCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Sync ID:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(text = syncId.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "Worker URL:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(text = workerUrl.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Section 6: Lock & Disconnect ──
            OutlinedButton(
                onClick = onLockVault,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = BorderStroke(1.dp, BorderDark)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("قفل الخزينة الآن")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRose)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("فصل الخزينة ومسح البيانات من الهاتف")
            }
        }
    }
}

private fun checkAutofillEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val afm = context.getSystemService(AutofillManager::class.java) ?: return false
    if (afm.hasEnabledAutofillServices()) return true
    // Fallback: some OEM builds report false right after switching; read the secure setting directly.
    val selected = Settings.Secure.getString(context.contentResolver, "autofill_service")
    return selected?.substringBefore('/') == context.packageName
}

private fun openAutofillSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            context.startActivity(fallback)
        }
    }
}
