package com.reword.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reword.app.BuildConfig
import com.reword.app.network.AppUpdate
import com.reword.app.network.AppUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class AppUpdateViewModel : ViewModel() {
    var checking by mutableStateOf(false)
        private set
    var status by mutableStateOf("يتم البحث تلقائياً عن الإصدارات الجديدة عند فتح التطبيق")
        private set
    var available by mutableStateOf<AppUpdate?>(null)
        private set
    var showDialog by mutableStateOf(false)
    private var lastAttempt = 0L
    private val repository = AppUpdateRepository()

    fun check(context: Context, manual: Boolean = false) {
        val now = System.currentTimeMillis()
        val preferences = context.applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        val lastSuccess = preferences.getLong("last_success", 0)
        if (checking || (!manual && (now - lastSuccess in 0 until 12 * 60 * 60 * 1000L ||
                now - lastAttempt in 0 until 60_000L))) return
        lastAttempt = now
        checking = true
        viewModelScope.launch {
            try {
                available = repository.check(BuildConfig.VERSION_NAME)
                preferences.edit().putLong("last_success", now).apply()
                status = available?.let { "الإصدار ${it.version} متاح للتحديث" } ?: "أنت تستخدم أحدث إصدار"
                showDialog = available != null
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                status = "تعذر التحقق من التحديثات. تأكد من الإنترنت وحاول مرة أخرى"
            } finally {
                checking = false
            }
        }
    }
}

@Composable
internal fun rememberAppUpdates(): AppUpdateViewModel {
    val updates: AppUpdateViewModel = viewModel()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, updates) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) updates.check(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return updates
}

internal fun openAppUpdate(context: Context, update: AppUpdate) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "تعذر فتح التحميل. افتح صفحة إصدارات ReWord على GitHub", Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun AppUpdateDialog(updates: AppUpdateViewModel) {
    val context = LocalContext.current
    val update = updates.available ?: return
    if (updates.showDialog) {
        AlertDialog(
            onDismissRequest = { updates.showDialog = false },
            title = { Text("إصدار جديد من ReWord") },
            text = { Text("الإصدار ${update.version} متاح. نزّل ملف التحديث من GitHub وافتحه لتحديث التطبيق مع الاحتفاظ ببياناتك.") },
            confirmButton = {
                TextButton(onClick = {
                    updates.showDialog = false
                    openAppUpdate(context, update)
                }) { Text("تحميل التحديث") }
            },
            dismissButton = {
                TextButton(onClick = { updates.showDialog = false }) { Text("لاحقاً") }
            }
        )
    }
}
