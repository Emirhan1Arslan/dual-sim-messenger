package com.ciftsim.mesajlar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ciftsim.mesajlar.Conversation
import com.ciftsim.mesajlar.MainViewModel
import com.ciftsim.mesajlar.SimInfo
import com.ciftsim.mesajlar.UiState
import java.util.Locale

private val turkish = Locale.forLanguageTag("tr")

/** Konuşma listesi: "Tümü" + her SIM için bir sekme; arama ve menü. */
@Composable
fun HomeScreen(state: UiState, viewModel: MainViewModel) {
    val sims = state.sims
    var savedTab by rememberSaveable { mutableIntStateOf(0) }
    // SIM çıkarıldıysa sekme "Tümü"ne döner.
    val tab = if (savedTab <= sims.size) savedTab else 0
    val selectedSubId = sims.getOrNull(tab - 1)?.subId

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    fun closeSearch() {
        searching = false
        query = ""
        viewModel.search("")
    }
    BackHandler(enabled = searching) { closeSearch() }
    LaunchedEffect(query) { viewModel.search(query) }

    val needle = query.trim().lowercase(turkish)
    val conversations = when {
        // Arama: önce adı/numarası eşleşen konuşmalar, sonra metni eşleşen mesajlar.
        searching && needle.isNotEmpty() -> {
            val byName = state.conversations.filter {
                it.title.lowercase(turkish).contains(needle) || it.address.contains(needle)
            }
            byName + state.searchHits
        }
        selectedSubId == null -> state.conversations
        else -> state.conversations.filter { it.subId == selectedSubId }
    }

    Scaffold(
        topBar = {
            Column {
                if (searching) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { closeSearch() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Aramayı kapat",
                                )
                            }
                        },
                        title = {
                            TextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Kişi, numara ya da mesaj ara") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                textStyle = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        },
                        actions = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Temizle")
                                }
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text("Çift SIM Mesajlar") },
                        actions = {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Ara")
                            }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Menü")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Engellenenler") },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.openBlocked()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Ayarlar") },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.openSettings()
                                    },
                                )
                            }
                        },
                    )
                    ScrollableTabRow(
                        selectedTabIndex = tab,
                        edgePadding = 8.dp,
                        modifier = Modifier.fillMaxWidth(),
                        // Varsayılan çizgi yalnızca sekmelerin genişliği kadar; tam genişlikte çiz.
                        divider = {},
                    ) {
                        SimTab(
                            selected = tab == 0,
                            label = "Tümü",
                            color = null,
                            unread = state.unread(null),
                            onClick = { savedTab = 0 },
                        )
                        sims.forEachIndexed { index, sim ->
                            SimTab(
                                selected = tab == index + 1,
                                label = sim.fullLabel,
                                color = sim.uiColor,
                                unread = state.unread(sim.subId),
                                onClick = { savedTab = index + 1 },
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        },
        floatingActionButton = {
            if (!searching) {
                ExtendedFloatingActionButton(
                    // Yeni mesajda varsayılan SIM: seçili sekmenin SIM'i.
                    onClick = { viewModel.openNewMessage(preferredSubId = selectedSubId) },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    text = { Text("Yeni mesaj") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                conversations.isEmpty() -> Text(
                    when {
                        !searching -> "Henüz mesaj yok"
                        needle.isEmpty() -> "Aramak için yazın"
                        else -> "Sonuç bulunamadı"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> ConversationList(
                    conversations = conversations,
                    state = state,
                    // SIM rozeti yalnızca SIM'lerin karışık göründüğü yerde: "Tümü" ve arama.
                    showSim = searching || tab == 0,
                    // Arama sonuçlarında aynı konuşma birden çok kez geçebilir.
                    stableKeys = !searching,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
internal fun ConversationList(
    conversations: List<Conversation>,
    state: UiState,
    showSim: Boolean,
    stableKeys: Boolean,
    viewModel: MainViewModel,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            conversations,
            key = if (stableKeys) { c -> "${c.threadId}/${c.subId}" } else null,
        ) { conversation ->
            ConversationRow(
                conversation = conversation,
                sim = state.sim(conversation.subId),
                showSim = showSim,
                onClick = {
                    viewModel.openConversation(
                        conversation.threadId, conversation.subId, conversation.address,
                    )
                },
            )
        }
    }
}

@Composable
private fun SimTab(
    selected: Boolean,
    label: String,
    color: Color?,
    unread: Int,
    onClick: () -> Unit,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (color != null) {
                    SimDot(color)
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, maxLines = 1)
                if (unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    UnreadBadge(unread, color ?: MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    sim: SimInfo?,
    showSim: Boolean,
    onClick: () -> Unit,
) {
    val unread = conversation.unread > 0
    val weight = if (unread) FontWeight.Bold else FontWeight.Normal
    val secondary =
        if (unread) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val initial = conversation.name?.firstOrNull()?.uppercase()
            if (initial == null) {
                Icon(
                    Icons.Filled.Person, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Text(
                    initial,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatListTime(conversation.date),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = weight,
                    color = secondary,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    if (conversation.lastIsOutgoing) "Siz: ${conversation.snippet}"
                    else conversation.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = weight,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showSim && sim != null) {
                    Spacer(Modifier.width(8.dp))
                    SimBadge(sim)
                }
                if (unread) {
                    Spacer(Modifier.width(6.dp))
                    UnreadBadge(conversation.unread, sim?.uiColor ?: MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
