package com.ciftsim.mesajlar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ciftsim.mesajlar.Contact
import com.ciftsim.mesajlar.MainViewModel
import com.ciftsim.mesajlar.Screen
import com.ciftsim.mesajlar.UiState
import java.util.Locale

private val turkish = Locale.forLanguageTag("tr")

/** Yazılan metne göre kişileri daraltır: ada ya da numaranın rakamlarına göre. */
private fun filterContacts(contacts: List<Contact>, query: String): List<Contact> {
    val text = query.trim().lowercase(turkish)
    if (text.isEmpty()) return contacts
    val digits = text.filter { it.isDigit() }
    return contacts.filter { contact ->
        contact.name.lowercase(turkish).contains(text) ||
            (digits.isNotEmpty() && contact.number.filter { it.isDigit() }.contains(digits))
    }
}

/** Yeni mesaj: kişi seçimi (ya da elle numara), SIM seçimi ve mesaj alanı. */
@Composable
fun NewMessageScreen(screen: Screen.NewMessage, state: UiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)

    // Alıcı alanına ad ya da numara yazılır; listeden kişi seçilince numarası yerleşir.
    var recipient by rememberSaveable { mutableStateOf(screen.address) }
    var pickedName by rememberSaveable { mutableStateOf<String?>(null) }
    var recipientFocused by remember { mutableStateOf(false) }
    var body by rememberSaveable { mutableStateOf(screen.body) }
    // Varsayılan: seçili sekmenin SIM'i, yoksa sistemin varsayılan SMS SIM'i.
    var chosenSubId by rememberSaveable { mutableStateOf<Int?>(null) }
    val subId =
        chosenSubId?.takeIf { state.sim(it) != null } ?: state.pickSendSim(screen.preferredSubId)
    var sending by rememberSaveable { mutableStateOf(false) }
    val bodyFocus = remember { FocusRequester() }
    val recipientFocus = remember { FocusRequester() }

    // Gönderilebilmesi için alıcıda en az bir rakam olmalı (yalnızca ad yazılıp kişi seçilmediyse olmaz).
    val canSend = !sending && recipient.any { it.isDigit() } && body.isNotBlank()
    // Gönderim hata verirse buton yeniden kullanılabilir olsun.
    LaunchedEffect(viewModel) { viewModel.errors.collect { sending = false } }
    LaunchedEffect(Unit) {
        if (screen.address.isEmpty()) recipientFocus.requestFocus() else bodyFocus.requestFocus()
    }

    val matches = remember(state.contacts, recipient) { filterContacts(state.contacts, recipient) }
    val showContacts = recipientFocused && pickedName == null && matches.isNotEmpty()
    var focusBodyNext by remember { mutableStateOf(false) }
    LaunchedEffect(focusBodyNext, showContacts) {
        if (focusBodyNext && !showContacts) {
            focusBodyNext = false
            bodyFocus.requestFocus()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                title = { Text("Yeni mesaj") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = recipient,
                onValueChange = {
                    recipient = it
                    pickedName = null
                },
                label = { Text("Kime") },
                placeholder = { Text("Ad ya da numara yazın") },
                supportingText = pickedName?.let { name -> { Text(name) } },
                trailingIcon = if (recipient.isEmpty()) null else {
                    {
                        IconButton(onClick = {
                            recipient = ""
                            pickedName = null
                            recipientFocus.requestFocus()
                        }) { Icon(Icons.Filled.Close, contentDescription = "Temizle") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(recipientFocus)
                    .onFocusChanged { recipientFocused = it.isFocused },
            )

            if (showContacts) {
                // Alıcı yazılırken mesaj alanının yerinde, yazdıkça daralan kişi listesi görünür.
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
                    items(matches, key = { it.name + "|" + it.number }) { contact ->
                        ContactRow(contact) {
                            recipient = contact.number
                            pickedName = contact.name
                            // Mesaj alanı liste kapanınca ekrana gelir; odak ondan sonra istenir.
                            focusBodyNext = true
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text("Gönderilecek SIM", style = MaterialTheme.typography.labelLarge)
                if (state.sims.isEmpty()) {
                    Text(
                        "Aktif SIM bulunamadı; sistemin varsayılan SIM'i kullanılacak.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    SimSelector(
                        sims = state.sims, selected = subId, onSelected = { chosenSubId = it },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Mesaj") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(bodyFocus),
                )
                Spacer(Modifier.height(12.dp))
                val sendColor = state.sim(subId)?.uiColor
                Button(
                    onClick = {
                        sending = true
                        // Gönderim sonrası ViewModel ilgili konuşmayı açar.
                        viewModel.send(recipient.trim(), body.trim(), subId)
                    },
                    enabled = canSend,
                    colors = if (sendColor == null) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = sendColor, contentColor = Color.White,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (sending) "Gönderiliyor…" else "Gönder")
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                contact.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                contact.number,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
