package com.ciftsim.mesajlar.ui

import android.net.Uri
import android.telephony.SmsMessage
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ciftsim.mesajlar.Attachment
import com.ciftsim.mesajlar.MainViewModel
import com.ciftsim.mesajlar.Message
import com.ciftsim.mesajlar.Screen
import com.ciftsim.mesajlar.UiState

/** Tek bir (thread_id, sub_id) konuşması. */
@Composable
fun ConversationScreen(screen: Screen.Conversation, state: UiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    val context = LocalContext.current

    val conversation = state.conversation(screen.threadId, screen.subId)
    val sim = state.sim(screen.subId)
    val messages = state.messages
    val blocked = conversation?.blocked == true

    var draft by rememberSaveable { mutableStateOf("") }
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    // Cevap varsayılan olarak konuşmanın SIM'inden gider; kullanıcı chip ile değiştirebilir.
    var chosenSubId by rememberSaveable { mutableStateOf<Int?>(null) }
    val sendSubId = chosenSubId?.takeIf { state.sim(it) != null } ?: state.pickSendSim(screen.subId)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) imageUri = uri.toString() }

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (sim != null) {
                                SimBadge(sim)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                when {
                                    blocked -> "Engellendi"
                                    // Kişi adı gösteriliyorsa altında numarası görünsün.
                                    conversation?.name != null -> screen.address
                                    else -> sim?.carrier.orEmpty()
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (blocked) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { dial(context, screen.address) }) {
                        Icon(Icons.Filled.Call, contentDescription = "Ara")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menü")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (state.blockingAvailable) {
                            DropdownMenuItem(
                                text = { Text(if (blocked) "Engeli kaldır" else "Numarayı engelle") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.setBlocked(screen.address, !blocked)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Konuşmayı sil") },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                state = state,
                draft = draft,
                onDraftChange = { draft = it },
                imageUri = imageUri,
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemoveImage = { imageUri = null },
                showSelector = state.sims.size > 1 || (state.sims.isNotEmpty() && sim == null),
                sendSubId = sendSubId,
                onSimSelected = { chosenSubId = it },
                onSend = {
                    viewModel.send(
                        screen.address, draft.trim(), sendSubId, imageUri?.let(Uri::parse),
                    )
                    draft = ""
                    imageUri = null
                },
            )
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
                        itemsIndexed(newestFirst, key = { _, m -> m.key }) { index, message ->
                            val older = newestFirst.getOrNull(index + 1)
                            Column(Modifier.fillMaxWidth()) {
                                if (older == null || !sameDay(older.date, message.date)) {
                                    DayLabel(message.date)
                                }
                                Bubble(
                                    message = message,
                                    onViewImage = { viewing = it },
                                    onRetry = { viewModel.retry(screen, message) },
                                    onDelete = { viewModel.deleteMessage(message) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    viewing?.let { FullScreenImage(it, onDismiss = { viewing = null }) }

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

/** Mesaj kutusu: SIM seçici, eklenen fotoğraf, metin alanı, karakter sayacı, gönder. */
@Composable
private fun Composer(
    state: UiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    imageUri: String?,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    showSelector: Boolean,
    sendSubId: Int,
    onSimSelected: (Int) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            if (showSelector) {
                SimSelector(
                    sims = state.sims,
                    selected = sendSubId,
                    onSelected = onSimSelected,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (imageUri != null) {
                Box(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                    UriImage(
                        uri = imageUri,
                        maxSide = 360,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .clickable(onClick = onRemoveImage),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close, contentDescription = "Fotoğrafı kaldır",
                            tint = Color.White, modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onPickImage, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "Fotoğraf ekle")
                }
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(if (imageUri != null) "Açıklama ekleyin" else "Mesaj yazın") },
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val counter = counterText(draft, mms = imageUri != null)
                    if (counter != null) {
                        Text(
                            counter,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val sendColor = state.sim(sendSubId)?.uiColor
                    FilledIconButton(
                        onClick = onSend,
                        enabled = draft.isNotBlank() || imageUri != null,
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
    }
}

/**
 * Gönder butonunun üstündeki sayaç: "MMS" ya da, mesaj birden çok SMS'e bölünecekse /
 * sınıra yaklaşıldıysa "kalan karakter / SMS sayısı".
 */
private fun counterText(text: String, mms: Boolean): String? {
    if (mms) return "MMS"
    if (text.isEmpty()) return null
    val (count, _, remaining) = SmsMessage.calculateLength(text, false)
    return if (count > 1 || remaining <= 30) "$remaining / $count" else null
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

/** Gelen mesaj solda, giden mesaj sağda. Uzun basınca: kopyala / yeniden gönder / sil. */
@Composable
private fun Bubble(
    message: Message,
    onViewImage: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (incoming) 4.dp else 18.dp,
        bottomEnd = if (incoming) 18.dp else 4.dp,
    )
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (incoming) Alignment.Start else Alignment.End,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .clip(shape)
                    .background(background)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
            ) {
                for (attachment in message.attachments) {
                    AttachmentView(
                        attachment = attachment,
                        foreground = foreground,
                        onViewImage = onViewImage,
                        onLongClick = { menuOpen = true },
                        onOpen = { openAttachment(context, attachment.uri, attachment.mime) },
                    )
                }
                if (message.body.isNotEmpty()) {
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = foreground,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (message.body.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Kopyala") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(message.body))
                        },
                    )
                }
                if (message.failed && !message.mms) {
                    DropdownMenuItem(
                        text = { Text("Yeniden gönder") },
                        onClick = {
                            menuOpen = false
                            onRetry()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Sil") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
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

@Composable
private fun AttachmentView(
    attachment: Attachment,
    foreground: Color,
    onViewImage: (String) -> Unit,
    onLongClick: () -> Unit,
    onOpen: () -> Unit,
) {
    if (attachment.isImage) {
        UriImage(
            uri = attachment.uri,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .combinedClickable(
                    onClick = { onViewImage(attachment.uri) },
                    onLongClick = onLongClick,
                ),
        )
    } else {
        val label = when {
            attachment.mime.startsWith("video/") -> "Video"
            attachment.mime.startsWith("audio/") -> "Ses kaydı"
            attachment.mime.contains("vcard") -> "Kişi kartı"
            else -> "Ek"
        }
        Text(
            "$label · açmak için dokunun",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = foreground,
            modifier = Modifier
                .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}
