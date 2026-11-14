package com.ciftsim.mesajlar.ui

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ciftsim.mesajlar.Permissions
import com.ciftsim.mesajlar.Sims
import com.ciftsim.mesajlar.UiState

/** Kurulum: önce varsayılan SMS uygulaması rolü, sonra izinler. */
@Composable
fun SetupScreen(state: UiState, onChanged: () -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onChanged() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        denied = !Permissions.hasRequired(context)
        onChanged()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                "Çift SIM Mesajlar",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Mesajlarınızı SIM kartlara göre ayrı ayrı görmek ve göndermek için " +
                    "iki adımı tamamlayın.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            StepCard(
                number = 1,
                done = state.isDefault,
                title = "Varsayılan SMS uygulaması",
                description = "SMS almak, kaydetmek ve göndermek için bu uygulamanın " +
                    "varsayılan SMS uygulaması olması gerekir.",
                button = "Varsayılan SMS uygulaması yap",
                enabled = !state.isDefault,
                onClick = {
                    val roles = context.getSystemService(RoleManager::class.java)
                    if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
                        roleLauncher.launch(roles.createRequestRoleIntent(RoleManager.ROLE_SMS))
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            StepCard(
                number = 2,
                done = state.permissionsOk,
                title = "İzinler",
                description = "SMS, telefon durumu (SIM bilgisi), rehber ve bildirim " +
                    "izinleri istenir.",
                button = "İzinleri ver",
                enabled = state.isDefault && !state.permissionsOk,
                onClick = { permissionLauncher.launch(Permissions.missing(context)) },
            )

            if (denied && !state.permissionsOk) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Gerekli izinler verilmedi. İzin penceresi açılmıyorsa izinleri " +
                        "uygulama ayarlarından verebilirsiniz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                }) { Text("Uygulama ayarlarını aç") }
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    done: Boolean,
    title: String,
    description: String,
    button: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(
                            if (done) Color(Sims.COLOR_SIM2)
                            else MaterialTheme.colorScheme.primaryContainer,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(
                            Icons.Filled.Check, contentDescription = "Tamamlandı",
                            tint = Color.White, modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(
                            number.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (done) "Tamamlandı" else button)
            }
        }
    }
}
