package com.reword.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.reword.app.model.Account
import com.reword.app.model.Site
import com.reword.app.ui.theme.*
import java.net.URI
import java.security.SecureRandom

/** Extracts a clean domain from site URL or name for the favicon service. */
fun extractFaviconDomain(url: String, name: String): String {
    val raw = if (url.isNotBlank()) url.split(",", ";", " ").firstOrNull()?.trim().orEmpty() else name.trim()
    if (raw.startsWith("androidapp://")) return ""
    return try {
        val formatted = if (!raw.contains("://")) "https://$raw" else raw
        (URI(formatted).host ?: raw).removePrefix("www.")
    } catch (_: Exception) {
        raw.removePrefix("www.")
    }
}

/** First openable web link of a site ("example.com" -> "https://example.com"), or null for apps / empty URLs. */
fun siteLink(site: Site): String? {
    val first = site.url.split(Regex("[,;\\s]+")).map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?: site.name.trim().takeIf { it.contains('.') && !it.contains(' ') } ?: return null
    if (first.startsWith("androidapp://")) return null
    val link = if (first.contains("://")) first else "https://$first"
    return link.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
}

/** Android package of an app-only entry (androidapp://com.example), used to launch the app instead. */
private fun sitePackage(site: Site): String? =
    site.url.split(Regex("[,;\\s]+")).firstOrNull { it.startsWith("androidapp://") }?.removePrefix("androidapp://")

@Composable
fun SiteFavicon(url: String, name: String, modifier: Modifier = Modifier, sizeDp: Int = 40) {
    val domain = remember(url, name) { extractFaviconDomain(url, name) }
    val fallbackInitial = remember(name, domain) { (name.firstOrNull() ?: domain.firstOrNull() ?: 'W').uppercase() }
    val context = LocalContext.current
    val request = remember(domain) {
        if (domain.isBlank()) null else ImageRequest.Builder(context)
            .data("https://www.google.com/s2/favicons?domain=$domain&sz=64")
            .crossfade(150)
            .build()
    }
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(SurfaceDark),
        contentAlignment = Alignment.Center
    ) {
        // Letter stays underneath; the icon simply fades in on top. AsyncImage (unlike SubcomposeAsyncImage)
        // does not subcompose, which keeps fast scrolling smooth.
        Text(fallbackInitial, fontWeight = FontWeight.Bold, color = AccentCyan.copy(alpha = 0.7f), fontSize = (sizeDp * 0.42).sp)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.size((sizeDp - 14).dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
fun SiteCard(
    site: Site,
    onAddAccount: (Site) -> Unit,
    onEditAccount: (Site, Account) -> Unit,
    onDeleteAccount: (Site, Account) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(site.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ChevronRotation"
    )
    val context = LocalContext.current
    val link = remember(site.url, site.name) { siteLink(site) }
    val appPackage = remember(site.url) { sitePackage(site) }
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(SurfaceCard)
            .border(1.dp, if (expanded) PrimaryViolet.copy(alpha = 0.45f) else BorderDark, shape)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SiteFavicon(url = site.url, name = site.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = site.name.ifBlank { site.url },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (site.url.isNotBlank()) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = site.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${site.accounts.size} حساب",
                style = MaterialTheme.typography.labelSmall,
                color = PrimaryViolet,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(PrimaryViolet.copy(alpha = 0.14f))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            )
            if (link != null || appPackage != null) {
                IconButton(
                    onClick = {
                        try {
                            val intent = if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link))
                            else context.packageManager.getLaunchIntentForPackage(appPackage!!)
                                ?: error("App not installed")
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            Toast.makeText(context, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "فتح الموقع", tint = AccentCyan, modifier = Modifier.size(19.dp))
                }
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (expanded) "طي" else "توسيع",
                tint = TextSecondary,
                modifier = Modifier.rotate(rotation)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                HorizontalDivider(color = BorderDark, thickness = 1.dp)
                Spacer(Modifier.height(4.dp))
                site.accounts.forEach { acc ->
                    AccountItem(acc, onEdit = { onEditAccount(site, acc) }, onDelete = { onDeleteAccount(site, acc) })
                }
                TextButton(onClick = { onAddAccount(site) }, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("إضافة حساب لهذا الموقع", color = AccentCyan)
                }
            }
        }
    }
}

@Composable
fun AccountItem(account: Account, onEdit: () -> Unit, onDelete: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showPass by remember { mutableStateOf(false) }
    var copiedUser by remember { mutableStateOf(false) }
    var copiedPass by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(SurfaceDark, RoundedCornerShape(10.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("اسم المستخدم", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(account.user.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, fontWeight = FontWeight.Medium)
                }
            }
            IconButton(onClick = {
                clipboardManager.setText(AnnotatedString(account.user))
                copiedUser = true
                Toast.makeText(context, "تم نسخ اسم المستخدم ✓", Toast.LENGTH_SHORT).show()
            }) {
                Icon(if (copiedUser) Icons.Default.Check else Icons.Default.ContentCopy, "نسخ اسم المستخدم",
                    tint = if (copiedUser) SuccessEmerald else AccentCyan, modifier = Modifier.size(19.dp))
            }
        }
        HorizontalDivider(color = BorderDark.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("كلمة المرور", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(if (showPass) account.pass else "••••••••••••", style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary, fontWeight = FontWeight.Medium)
                }
            }
            IconButton(onClick = { showPass = !showPass }) {
                Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, "عرض كلمة المرور",
                    tint = TextSecondary, modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = {
                clipboardManager.setText(AnnotatedString(account.pass))
                copiedPass = true
                Toast.makeText(context, "تم نسخ كلمة المرور ✓", Toast.LENGTH_SHORT).show()
            }) {
                Icon(if (copiedPass) Icons.Default.Check else Icons.Default.ContentCopy, "نسخ كلمة المرور",
                    tint = if (copiedPass) SuccessEmerald else PrimaryViolet, modifier = Modifier.size(19.dp))
            }
        }
        if (!account.email.isNullOrBlank()) {
            HorizontalDivider(color = BorderDark.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("البريد الإلكتروني", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(account.email, color = TextPrimary)
                    }
                }
                IconButton(onClick = { clipboardManager.setText(AnnotatedString(account.email)) }) {
                    Icon(Icons.Default.ContentCopy, "نسخ البريد الإلكتروني", tint = AccentCyan, modifier = Modifier.size(19.dp))
                }
            }
        }
        if (account.notes.isNotBlank()) {
            HorizontalDivider(color = BorderDark.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
            Text("ملاحظات", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Text(account.notes, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("تعديل", color = AccentCyan, style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, null, tint = ErrorRose, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("حذف", color = ErrorRose, style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = SurfaceCard,
            title = { Text("حذف الحساب؟", color = TextPrimary) },
            text = { Text("سيتم حذف \"${account.user.ifBlank { "بدون اسم" }}\" من الخزنة ومزامنته.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("حذف", color = ErrorRose) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء", color = TextSecondary) } }
        )
    }
}

private fun generatePassword(length: Int = 18): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%&*?-_"
    val rnd = SecureRandom()
    return (1..length).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}

/**
 * Add / edit an account. [site] null = brand-new site; [account] null = new account on that site.
 * Returns values through [onSave]; the caller persists them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountEditorDialog(
    site: Site?,
    account: Account?,
    existingTags: List<String>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (siteName: String, siteUrl: String, user: String, pass: String, notes: String, tags: String, email: String) -> Unit
) {
    // Categories of this site (pre-selected) + the vault's existing categories to pick from.
    var selectedTags by rememberSaveable {
        mutableStateOf(site?.tags.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }
    var newTag by rememberSaveable { mutableStateOf("") }
    val tagChoices = (existingTags + selectedTags).distinctBy { it.lowercase() }
    fun addNewTag() {
        val t = newTag.replace(',', ' ').trim()
        if (t.isEmpty()) return
        val canonical = tagChoices.firstOrNull { it.equals(t, ignoreCase = true) } ?: t
        if (selectedTags.none { it.equals(canonical, ignoreCase = true) }) selectedTags = selectedTags + canonical
        newTag = ""
    }
    var siteName by rememberSaveable { mutableStateOf(site?.name.orEmpty()) }
    var siteUrl by rememberSaveable { mutableStateOf(site?.url.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(account?.user.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(account?.email.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf(account?.pass.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(account?.notes.orEmpty()) }
    var showPass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AccentCyan, unfocusedBorderColor = BorderDark,
        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        focusedLabelColor = AccentCyan, unfocusedLabelColor = TextMuted,
        focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark
    )
    val title = when {
        site == null -> "إضافة حساب جديد"
        account == null -> "إضافة حساب لـ ${site.name.ifBlank { site.url }}"
        else -> "تعديل الحساب"
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = SurfaceCard,
        title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(siteName, { siteName = it }, label = { Text("اسم الموقع / التطبيق") },
                    singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(siteUrl, { siteUrl = it }, label = { Text("الرابط (example.com)") },
                    singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(user, { user = it }, label = { Text("اسم المستخدم / البريد") },
                    singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true, colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { pass = generatePassword(); showPass = true }) {
                                Icon(Icons.Default.Casino, "توليد كلمة مرور", tint = PrimaryViolet)
                            }
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, "عرض", tint = TextSecondary)
                            }
                        }
                    }
                )
                OutlinedTextField(email, { email = it }, label = { Text("بريد إلكتروني إضافي (اختياري)") },
                    singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Text("التصنيف (اختياري)", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                if (tagChoices.isEmpty()) {
                    Text("لا توجد تصنيفات بعد — أضف أول تصنيف", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tagChoices.forEach { tag ->
                            val on = selectedTags.any { it.equals(tag, ignoreCase = true) }
                            FilterChip(
                                selected = on,
                                onClick = {
                                    selectedTags = if (on) selectedTags.filterNot { it.equals(tag, ignoreCase = true) } else selectedTags + tag
                                },
                                label = { Text(tag) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = SurfaceDark, labelColor = TextSecondary,
                                    selectedContainerColor = PrimaryViolet, selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
                OutlinedTextField(
                    newTag, { newTag = it }, label = { Text("تصنيف جديد") }, singleLine = true, colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addNewTag() }),
                    trailingIcon = {
                        IconButton(onClick = { addNewTag() }, enabled = newTag.isNotBlank()) {
                            Icon(Icons.Default.Add, "إضافة تصنيف", tint = AccentCyan)
                        }
                    }
                )
                OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظات (اختياري)") },
                    minLines = 3, maxLines = 6, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = ErrorRose, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    error = when {
                        siteName.isBlank() && siteUrl.isBlank() -> "أدخل اسم الموقع أو الرابط"
                        pass.isEmpty() -> "أدخل كلمة المرور"
                        else -> null
                    }
                    if (error == null) {
                        addNewTag() // typed a category but did not press "+"
                        onSave(siteName, siteUrl, user, pass, notes, selectedTags.joinToString(", "), email)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet, contentColor = Color.White)
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("حفظ")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("إلغاء", color = TextSecondary) } }
    )
}
