package com.ciftsim.mesajlar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ciftsim.mesajlar.MainViewModel
import com.ciftsim.mesajlar.Message
import com.ciftsim.mesajlar.Screen
import com.ciftsim.mesajlar.UiState

/** Tek bir (thread_id, sub_id) konuşması. */
@Composable
fun ConversationScreen(screen: Screen.Conversation, state: UiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)

    val conversation = state.conversations.firstOrNull {
        it.threadId == screen.threadId && it.subId == screen.subId
    }
    val sim = state.sim(screen.subId)
    val messages = state.messages

    var draft by rememberSaveable { mutableStateOf("") }
    // Cevap varsayılan olarak konuşmanın SIM'inden gider; kullanıcı chip ile değiştirebilir.
    var chosenSubId by rememberSaveable { mutableStateOf<Int?>(null) }
    val sendSubId = chosenSubId?.takeIf { state.sim(it) != null } ?: state.pickSendSim(screen.subId)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        // Klavye açılınca bütün ekran (mesaj kutusu dahil) klavyenin üstüne kayar.
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                title = {
                    Column {
                        Text(
                            conversation?.title ?: screen.address,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (sim != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SimBadge(sim)
                                if (sim.carrier.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(sim.carrier, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Konuşmayı sil")
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    val showSelector =
                        state.sims.size > 1 || (state.sims.isNotEmpty() && sim == null)
                    if (showSelector) {
                        SimSelector(
                            sims = state.sims,
                            selected = sendSubId,
                            onSelected = { chosenSubId = it },
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        TextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = { Text("Mesaj yazın") },
                            maxLines = 5,
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(4.dp))
                        val sendColor = state.sim(sendSubId)?.uiColor
                        FilledIconButton(
                            onClick = {
                                viewModel.send(screen.address, draft.trim(), sendSubId)
                                draft = ""
                            },
                            enabled = draft.isNotBlank(),
                            colors = if (sendColor == null) {
                                IconButtonDefaults.filledIconButtonColors()
                            } else {
                                IconButtonDefaults.filledIconButtonColors(
                                    containerColor = sendColor,
                                    contentColor = Color.White,
                                )
                            },
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                messages == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                messages.isEmpty() -> Text(
                    "Bu konuşmada mesaj yok",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    val newestFirst = messages.asReversed()
                    LazyColumn(
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        // Az mesaj varken de mesaj kutusunun hemen üstüne yaslansın.
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(newestFirst, key = { _, m -> m.id }) { index, message ->
                            val older = newestFirst.getOrNull(index + 1)
                            Column(Modifier.fillMaxWidth()) {
                                if (older == null || !sameDay(older.date, message.date)) {
                                    DayLabel(message.date)
                                }
                                Bubble(message)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Konuşma silinsin mi?") },
            text = { Text("Bu konuşmadaki bütün mesajlar kalıcı olarak silinecek.") },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteConversation(screen.threadId, screen.subId)
                }) { Text("Sil", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun DayLabel(date: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            formatDay(date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Gelen mesaj solda, giden mesaj sağda. */
@Composable
private fun Bubble(message: Message) {
    val scheme = MaterialTheme.colorScheme
    val incoming = message.incoming
    val background = when {
        incoming -> scheme.surfaceContainerHighest
        message.failed -> scheme.errorContainer
        else -> scheme.primaryContainer
    }
    val foreground = when {
        incoming -> scheme.onSurface
        message.failed -> scheme.onErrorContainer
        else -> scheme.onPrimaryContainer
    }
    val status = when {
        message.sending -> "Gönderiliyor…"
        message.failed -> "Gönderilemedi"
        else -> formatClock(message.date)
    }
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (incoming) Alignment.Start else Alignment.End,
    ) {
        SelectionContainer {
            Text(
                message.body,
                style = MaterialTheme.typography.bodyLarge,
                color = foreground,
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .background(
                        background,
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (incoming) 4.dp else 18.dp,
                            bottomEnd = if (incoming) 18.dp else 4.dp,
                        ),
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = if (message.failed) scheme.error else scheme.onSurfaceVariant,
            fontWeight = if (message.failed) FontWeight.Bold else null,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
