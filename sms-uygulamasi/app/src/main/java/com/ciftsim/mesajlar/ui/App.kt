package com.ciftsim.mesajlar.ui

import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ciftsim.mesajlar.MainViewModel
import com.ciftsim.mesajlar.Permissions
import com.ciftsim.mesajlar.Screen
import com.ciftsim.mesajlar.SimInfo
import com.ciftsim.mesajlar.SimPrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val SimInfo.uiColor: Color get() = Color(color)

/** Android 12+ cihazlarda dinamik renk; eskilerde SIM 1 mavisinden türetilen sabit tema. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFFA5D6A7))
        else -> lightColorScheme(
            primary = Color(SimPrefs.palette[0]), secondary = Color(SimPrefs.palette[1]),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    AppTheme {
        when {
            !state.checked ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            !state.ready -> SetupScreen(state, onChanged = viewModel::refreshStatus)
            else -> {
                OptionalPermissions(onResult = viewModel::reload)
                // Ekran değişse de sekme, kaydırma konumu ve yazılan taslak korunur.
                val holder = rememberSaveableStateHolder()
                when (val current = screen) {
                    Screen.Home -> holder.SaveableStateProvider("home") {
                        HomeScreen(state, viewModel)
                    }
                    is Screen.Conversation -> holder.SaveableStateProvider(current.toString()) {
                        ConversationScreen(current, state, viewModel)
                    }
                    is Screen.NewMessage -> holder.SaveableStateProvider(current.toString()) {
                        NewMessageScreen(current, state, viewModel)
                    }
                    Screen.Settings -> holder.SaveableStateProvider("settings") {
                        SettingsScreen(state, viewModel)
                    }
                    Screen.Blocked -> holder.SaveableStateProvider("blocked") {
                        BlockedScreen(state, viewModel)
                    }
                }
            }
        }
    }
}

/**
 * Rol, SMS izinlerini kendiliğinden verebilir ve kurulumdaki izin adımı atlanır.
 * Kalan isteğe bağlı izinler (bildirim, rehber) ana ekrana ilk girişte bir kez istenir.
 */
@Composable
private fun OptionalPermissions(onResult: () -> Unit) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onResult() }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            val missing = Permissions.missing(context)
            if (missing.isNotEmpty()) launcher.launch(missing)
        }
    }
}

/** SIM'in sabit renginde küçük rozet ("SIM 1"). */
@Composable
fun SimBadge(sim: SimInfo, modifier: Modifier = Modifier) {
    Text(
        text = sim.label,
        color = Color.White,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 96.dp)
            .background(sim.uiColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SimDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

/** Gönderilecek SIM'i seçmek için chip'ler. */
@Composable
fun SimSelector(
    sims: List<SimInfo>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState())) {
        for (sim in sims) {
            val isSelected = sim.subId == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelected(sim.subId) },
                label = { Text(sim.fullLabel) },
                leadingIcon = { SimDot(sim.uiColor) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = sim.uiColor.copy(alpha = 0.25f),
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    selectedBorderColor = sim.uiColor,
                    selectedBorderWidth = 1.dp,
                ),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
fun UnreadBadge(count: Int, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color, CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---- Tarih biçimleri ----

private val turkish = Locale.forLanguageTag("tr")

fun formatClock(time: Long): String = SimpleDateFormat("HH:mm", turkish).format(Date(time))

private fun sameYear(time: Long): Boolean {
    val then = Calendar.getInstance().apply { timeInMillis = time }
    return then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
}

/** Bugün: saat; bu yıl: "5 Kas"; daha eski: "05.11.2024". */
fun formatListTime(time: Long): String = when {
    DateUtils.isToday(time) -> formatClock(time)
    sameYear(time) -> SimpleDateFormat("d MMM", turkish).format(Date(time))
    else -> SimpleDateFormat("dd.MM.yyyy", turkish).format(Date(time))
}

fun formatDay(time: Long): String = when {
    DateUtils.isToday(time) -> "Bugün"
    DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> "Dün"
    sameYear(time) -> SimpleDateFormat("d MMMM", turkish).format(Date(time))
    else -> SimpleDateFormat("d MMMM yyyy", turkish).format(Date(time))
}

fun sameDay(a: Long, b: Long): Boolean {
    val first = Calendar.getInstance().apply { timeInMillis = a }
    val second = Calendar.getInstance().apply { timeInMillis = b }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}
