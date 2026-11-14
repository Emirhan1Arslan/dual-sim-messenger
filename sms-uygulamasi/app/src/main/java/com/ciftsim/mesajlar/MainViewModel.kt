package com.ciftsim.mesajlar

import android.app.Application
import android.app.role.RoleManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen

    data class Conversation(val threadId: Long, val subId: Int, val address: String) : Screen

    data class NewMessage(
        val address: String = "",
        val body: String = "",
        /** Ana ekranda seçili sekmenin SIM'i ("Tümü" sekmesinde null). */
        val preferredSubId: Int? = null,
    ) : Screen
}

data class UiState(
    /** Rol ve izin durumu en az bir kez okundu mu? */
    val checked: Boolean = false,
    val isDefault: Boolean = false,
    val permissionsOk: Boolean = false,
    val sims: List<SimInfo> = emptyList(),
    val defaultSubId: Int = -1,
    val conversations: List<Conversation> = emptyList(),
    val loaded: Boolean = false,
    /** Açık konuşmanın mesajları; henüz yüklenmediyse null. */
    val messages: List<Message>? = null,
) {
    val ready: Boolean get() = isDefault && permissionsOk

    fun sim(subId: Int): SimInfo? = sims.firstOrNull { it.subId == subId }

    fun unread(subId: Int?): Int =
        conversations.sumOf { if (subId == null || it.subId == subId) it.unread else 0 }

    /** Gönderim için varsayılan SIM: tercih edilen → sistem varsayılanı → ilk SIM. */
    fun pickSendSim(preferred: Int?): Int = when {
        preferred != null && sim(preferred) != null -> preferred
        sim(defaultSubId) != null -> defaultSubId
        else -> sims.firstOrNull()?.subId ?: -1
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen = _screen.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()

    /** Art arda gelen yenileme isteklerini tek yüklemede toplar. */
    private val reloads = Channel<Unit>(Channel.CONFLATED)
    private var observer: ContentObserver? = null

    init {
        viewModelScope.launch {
            for (request in reloads) load()
        }
    }

    // ---- Durum ----

    /** Rol ve izinleri yeniden okur; kurulum tamamsa content://sms'i dinler ve yükler. */
    fun refreshStatus() {
        val ctx = getApplication<Application>()
        val isDefault =
            ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        val permissionsOk = Permissions.hasRequired(ctx)
        _state.update {
            it.copy(checked = true, isDefault = isDefault, permissionsOk = permissionsOk)
        }
        if (isDefault && permissionsOk) {
            registerObserver()
            reload()
        }
    }

    fun reload() {
        reloads.trySend(Unit)
    }

    private fun registerObserver() {
        if (observer != null) return
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = reload()
        }
        try {
            getApplication<Application>().contentResolver
                .registerContentObserver(Telephony.Sms.CONTENT_URI, true, created)
            observer = created
        } catch (_: SecurityException) {
        }
    }

    private suspend fun load() {
        if (!_state.value.ready) return
        val ctx = getApplication<Application>()
        val current = _screen.value
        try {
            withContext(Dispatchers.IO) {
                val sims = Sims.active(ctx)
                val defaultSubId = SmsManager.getDefaultSmsSubscriptionId()
                val conversations = SmsStore.conversations(ctx)
                var messages: List<Message>? = null
                if (current is Screen.Conversation) {
                    messages = SmsStore.messages(ctx, current.threadId, current.subId)
                    // Açık konuşmaya gelen yeni mesajları okundu olarak işaretle.
                    val unread = conversations.any {
                        it.threadId == current.threadId && it.subId == current.subId &&
                            it.unread > 0
                    }
                    if (unread) SmsStore.markRead(ctx, current.threadId, current.subId)
                }
                _state.update {
                    it.copy(
                        sims = sims,
                        defaultSubId = defaultSubId,
                        conversations = conversations,
                        loaded = true,
                        messages = if (_screen.value == current) messages else it.messages,
                    )
                }
            }
        } catch (_: SecurityException) {
            // İzin ya da rol geri alınmış; durum bir sonraki onResume'da yenilenir.
        }
    }

    // ---- Gezinme ----

    fun openConversation(threadId: Long, subId: Int, address: String) {
        AppState.activeThread = threadId
        AppState.activeSub = subId
        _state.update { it.copy(messages = null) }
        _screen.value = Screen.Conversation(threadId, subId, address)
        reload()
    }

    fun openNewMessage(address: String = "", body: String = "", preferredSubId: Int? = null) {
        clearActive()
        _screen.value = Screen.NewMessage(address, body, preferredSubId)
    }

    fun back() {
        clearActive()
        _screen.value = Screen.Home
    }

    private fun clearActive() {
        AppState.activeThread = -1L
        AppState.activeSub = -1
        _state.update { it.copy(messages = null) }
    }

    // ---- İşlemler ----

    /** Gönderir ve mesajın yazıldığı (thread, SIM) konuşmasını açar. */
    fun send(address: String, body: String, subId: Int) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val (threadId, usedSubId) = withContext(Dispatchers.IO) {
                    SmsStore.send(ctx, address, body, subId)
                }
                val current = _screen.value
                val same = current is Screen.Conversation &&
                    current.threadId == threadId && current.subId == usedSubId
                if (same) reload() else openConversation(threadId, usedSubId, address)
            } catch (e: Exception) {
                _errors.tryEmit("Gönderilemedi: ${e.message ?: "bilinmeyen hata"}")
            }
        }
    }

    fun deleteConversation(threadId: Long, subId: Int) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { SmsStore.deleteConversation(ctx, threadId, subId) }
                back()
            } catch (e: Exception) {
                _errors.tryEmit("Silinemedi: ${e.message ?: "bilinmeyen hata"}")
            }
        }
    }

    override fun onCleared() {
        observer?.let { getApplication<Application>().contentResolver.unregisterContentObserver(it) }
        clearActive()
    }
}
