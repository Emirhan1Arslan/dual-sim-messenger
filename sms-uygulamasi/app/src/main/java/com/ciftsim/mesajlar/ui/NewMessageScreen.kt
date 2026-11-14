package com.ciftsim.mesajlar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ciftsim.mesajlar.MainViewModel
import com.ciftsim.mesajlar.Screen
import com.ciftsim.mesajlar.UiState

/** Yeni mesaj: numara, SIM seçimi ve mesaj alanı. */
@Composable
fun NewMessageScreen(screen: Screen.NewMessage, state: UiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)

    var address by rememberSaveable { mutableStateOf(screen.address) }
    var body by rememberSaveable { mutableStateOf(screen.body) }
    // Varsayılan: seçili sekmenin SIM'i, yoksa sistemin varsayılan SMS SIM'i.
    var chosenSubId by rememberSaveable { mutableStateOf<Int?>(null) }
    val subId =
        chosenSubId?.takeIf { state.sim(it) != null } ?: state.pickSendSim(screen.preferredSubId)
    var sending by rememberSaveable { mutableStateOf(false) }
    val canSend = !sending && address.isNotBlank() && body.isNotBlank()
    // Gönderim hata verirse buton yeniden kullanılabilir olsun.
    LaunchedEffect(viewModel) { viewModel.errors.collect { sending = false } }

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
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Numara") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("Gönderilecek SIM", style = MaterialTheme.typography.labelLarge)
            if (state.sims.isEmpty()) {
                Text(
                    "Aktif SIM bulunamadı; sistemin varsayılan SIM'i kullanılacak.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                SimSelector(sims = state.sims, selected = subId, onSelected = { chosenSubId = it })
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Mesaj") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(12.dp))
            val sendColor = state.sim(subId)?.uiColor
            Button(
                onClick = {
                    sending = true
                    // Gönderim sonrası ViewModel ilgili konuşmayı açar.
                    viewModel.send(address.trim(), body.trim(), subId)
                },
                enabled = canSend,
                colors = if (sendColor == null) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(containerColor = sendColor, contentColor = Color.White)
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
