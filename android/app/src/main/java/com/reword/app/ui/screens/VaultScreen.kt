package com.reword.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reword.app.model.Account
import com.reword.app.model.Site
import com.reword.app.model.Vault
import com.reword.app.ui.components.AccountEditorDialog
import com.reword.app.ui.components.ReWordTopBar
import com.reword.app.ui.components.SiteCard
import com.reword.app.ui.theme.*
import kotlinx.coroutines.delay

/** Sites rendered per "page"; more are revealed as the user scrolls near the end. */
private const val PAGE_SIZE = 15

/** Values from the account editor, handed to the caller to persist. */
data class AccountForm(
    val siteId: String?,
    val accountId: String?,
    val siteName: String,
    val siteUrl: String,
    val user: String,
    val pass: String,
    val notes: String,
    val tags: String
)

private data class EditorState(val site: Site?, val account: Account?)

@Composable
fun VaultScreen(
    vault: Vault,
    syncStatus: String = "ok",
    onSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSaveAccount: (AccountForm, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onDeleteAccount: (Site, Account) -> Unit = { _, _ -> }
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var saving by remember { mutableStateOf(false) }

    val allTags = remember(vault.sites) {
        vault.sites
            .flatMap { site -> site.tags?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList() }
            .distinct()
            .sorted()
    }

    val filteredSites = remember(vault, searchQuery, selectedTag) {
        var list = vault.sites
        if (!selectedTag.isNullOrBlank()) {
            val tagLower = selectedTag!!.lowercase()
            list = list.filter { site -> site.tags?.split(",")?.any { it.trim().equals(tagLower, ignoreCase = true) } == true }
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter { site ->
                site.name.lowercase().contains(q) ||
                    site.url.lowercase().contains(q) ||
                    site.accounts.any { it.user.lowercase().contains(q) || it.notes.lowercase().contains(q) } ||
                    (site.tags?.lowercase()?.contains(q) == true)
            }
        }
        list
    }

    val totalAccounts = remember(vault) { vault.sites.sumOf { it.accounts.size } }

    // Progressive rendering: start with one page and grow while scrolling.
    var visibleCount by remember(filteredSites) { mutableIntStateOf(PAGE_SIZE) }
    val listState = rememberLazyListState()
    LaunchedEffect(filteredSites) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }
    val nearEnd by remember(filteredSites) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= minOf(visibleCount, filteredSites.size) - 4
        }
    }
    LaunchedEffect(nearEnd, visibleCount) {
        if (nearEnd && visibleCount < filteredSites.size) {
            delay(120) // lets the "loading" row and the fade read as a deliberate reveal
            visibleCount = minOf(visibleCount + PAGE_SIZE, filteredSites.size)
        }
    }
    // Items that already played their entrance animation do not replay it when scrolled back into view.
    val revealed = remember(filteredSites) { mutableSetOf<String>() }

    Scaffold(
        topBar = {
            ReWordTopBar(
                title = "${vault.sites.size} موقع • $totalAccounts حساب",
                syncStatus = syncStatus,
                onSettingsClick = onSettingsClick,
                onSyncClick = onSyncClick
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editor = EditorState(null, null) },
                containerColor = PrimaryViolet,
                contentColor = Color.White,
                modifier = Modifier.navigationBarsPadding(),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("حساب جديد", fontWeight = FontWeight.Bold) }
            )
        },
        containerColor = BgDark
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("بحث في المواقع والحسابات...", color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentCyan) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "مسح", tint = TextMuted)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceCard,
                    unfocusedContainerColor = SurfaceCard
                )
            )

            if (allTags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { TagChip("الكل", selectedTag == null) { selectedTag = null } }
                    items(allTags) { tag ->
                        val isSelected = selectedTag.equals(tag, ignoreCase = true)
                        TagChip(tag, isSelected) { selectedTag = if (isSelected) null else tag }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (filteredSites.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (searchQuery.isBlank()) "الخزينة فارغة حالياً." else "لا توجد نتائج مطابقة لـ \"$searchQuery\"",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val shown = filteredSites.take(visibleCount)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    itemsIndexed(
                        shown,
                        key = { index, site -> site.id.ifBlank { "${site.url}#$index" } },
                        contentType = { _, _ -> "site" }
                    ) { index, site ->
                        val key = site.id.ifBlank { "${site.url}#$index" }
                        RevealOnFirstShow(key = key, revealed = revealed, staggerIndex = index % PAGE_SIZE,
                            modifier = Modifier.animateItem()) {
                            SiteCard(
                                site = site,
                                onAddAccount = { editor = EditorState(it, null) },
                                onEditAccount = { s, a -> editor = EditorState(s, a) },
                                onDeleteAccount = onDeleteAccount
                            )
                        }
                    }
                    if (visibleCount < filteredSites.size) {
                        item(key = "loading", contentType = "loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = PrimaryViolet, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    editor?.let { state ->
        AccountEditorDialog(
            site = state.site,
            account = state.account,
            existingTags = allTags,
            saving = saving,
            onDismiss = { editor = null },
            onSave = { name, url, user, pass, notes, tags ->
                saving = true
                onSaveAccount(AccountForm(state.site?.id, state.account?.id, name, url, user, pass, notes, tags)) { ok ->
                    saving = false
                    if (ok) editor = null
                }
            }
        )
    }
}

/** Fade + gentle slide-up the first time an item appears, with a short stagger within each page. */
@Composable
private fun RevealOnFirstShow(
    key: String,
    revealed: MutableSet<String>,
    staggerIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val alreadyShown = remember(key) { key in revealed }
    val progress = remember(key) { Animatable(if (alreadyShown) 1f else 0f) }
    val offsetPx = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(key) {
        if (!alreadyShown) {
            revealed.add(key)
            delay((staggerIndex.coerceAtMost(8) * 35).toLong())
            progress.animateTo(1f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * offsetPx
            val s = 0.97f + 0.03f * progress.value
            scaleX = s
            scaleY = s
        }
    ) { content() }
}

@Composable
private fun TagChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) PrimaryViolet else SurfaceCard)
            .border(1.dp, if (selected) PrimaryViolet else BorderDark, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else TextSecondary
        )
    }
}
