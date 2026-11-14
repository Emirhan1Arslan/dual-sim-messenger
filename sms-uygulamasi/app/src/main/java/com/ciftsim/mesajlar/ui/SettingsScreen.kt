package com.ciftsim.mesajlar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ciftsim.mesajlar.MainViewModel
import com.ciftsim.mesajlar.SimInfo
import com.ciftsim.mesajlar.SimPrefs
import com.ciftsim.mesajlar.UiState

/** Ayarlar: SIM adları ve renkleri, engellenen numaralar. */
@Composable
fun SettingsScreen(state: UiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                title = { Text("Ayarlar") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("SIM kartlar")
            if (state.sims.isEmpty()) {
                Text(
                    "Aktif SIM bulunamadı.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (sim in state.sims) {
                SimCard(
                    sim = sim,
                    onName = { viewModel.setSimName(sim.subId, it) },
                    onColor = { viewModel.setSimColor(sim.subId, it) },
                )
            }

            Spacer(Modifier.height(4.dp))
            SectionTitle("Engellenen numaralar")
            if (!state.blockingAvailable) {
                Text(
                    "Bu cihazda numara engelleme kullanılamıyor.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Engellenen numaralardan gelen mesajlar ve aramalar telefona ulaşmaz (liste " +
                        "telefon uygulamasıyla ortaktır). Engellemeden önce gelmiş mesajlar " +
                        "ana listeden kalkar ve \"Engellenenler\" ekranında görünür.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BlockNumberField(onBlock = { viewModel.setBlocked(it, true) })
                if (state.blockedNumbers.isEmpty()) {
                    Text(
                        "Engellenen numara yok.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (number in state.blockedNumbers) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(number, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { viewModel.setBlocked(number, false) }) {
                            Text("Engeli kaldır")
                        }
                    }
                }
                TextButton(onClick = viewModel::openBlocked) {
                    Text("Engellenen konuşmaları göster")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SimCard(sim: SimInfo, onName: (String) -> Unit, onColor: (Int) -> Unit) {
    // Yazarken imleç zıplamasın diye metin yerelde tutulur, her değişiklikte kaydedilir.
    var name by rememberSaveable(sim.subId) { mutableStateOf(sim.customName.orEmpty()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SimDot(sim.uiColor)
                Spacer(Modifier.width(8.dp))
                Text(sim.systemLabel, style = MaterialTheme.typography.titleMedium)
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(20)
                    onName(name)
                },
                label = { Text("Ad") },
                placeholder = { Text("Örn. İş, Kişisel") },
                supportingText = { Text("Boş bırakılırsa \"SIM ${sim.slot + 1}\" görünür.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Renk", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (color in SimPrefs.palette) {
                    val selected = color == sim.color
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(Color(color), CircleShape)
                            .then(
                                if (selected) {
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onColor(color) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check, contentDescription = "Seçili",
                                tint = Color.White, modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockNumberField(onBlock: (String) -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            label = { Text("Engellenecek numara") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                onBlock(number)
                number = ""
            },
            enabled = number.any { it.isDigit() },
        ) { Text("Engelle") }
    }
}

/** Engellenen numaraların konuşmaları. */
@Composable
fun BlockedScreen(state: UiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    val conversations = state.blockedConversations

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                title = { Text("Engellenenler") },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (conversations.isEmpty()) {
                Text(
                    "Engellenen numaralardan mesaj yok",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ConversationList(
                    conversations = conversations,
                    state = state,
                    showSim = true,
                    stableKeys = true,
                    viewModel = viewModel,
                )
            }
        }
    }
}
