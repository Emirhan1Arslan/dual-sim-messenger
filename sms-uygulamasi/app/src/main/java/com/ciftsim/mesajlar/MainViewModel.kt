package com.ciftsim.mesajlar

import android.app.Application
import android.app.role.RoleManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

    data object Settings : Screen

    /** Engellenen numaraların konuşmaları. */
    data object Blocked : Screen
}

data class UiState(
    /** Rol ve izin durumu en az bir kez okundu mu? */
    val checked: Boolean = false,
    val isDefault: Boolean = false,
    val permissionsOk: Boolean = false,
    val sims: List<SimInfo> = emptyList(),
    val defaultSubId: Int = -1,
    /** Engellenenler dahil bütün konuşmalar. */
    val allConversations: List<Conversation> = emptyList(),
    val loaded: Boolean = false,
    /** Açık konuşmanın mesajları; henüz yüklenmediyse null. */
    val messages: List<Message>? = null,
    val contacts: List<Contact> = emptyList(),
    val blockedNumbers: List<String> = emptyList(),
    val blockingAvailable: Boolean = true,
    /** Mesaj metni araması sonuçları. */
    val searchHits: List<Conversation> = emptyList(),
) {
    val ready: Boolean get() = isDefault && permissionsOk

    /** Ana listede görünen konuşmalar (engellenenler hariç). */
    val conversations: List<Conversation> get() = allConversations.filterNot { it.blocked }
    val blockedConversations: List<Conversation> get() = allConversations.filter { it.blocked }

    fun sim(subId: Int): SimInfo? = sims.firstOrNull { it.subId == subId }

    fun conversation(threadId: Long, subId: Int): Conversation? =
        allConversations.firstOrNull { it.threadId == threadId && it.subId == subId }

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
    private var searchJob: Job? = null

    private val ctx get() = getApplication<Application>()

    init {
        viewModelScope.launch {
            for (request in reloads) load()
        }
    }

    // ---- Durum ----

    /** Rol ve izinleri yeniden okur; kurulum tamamsa mesaj veritabanını dinler ve yükler. */
    fun refreshStatus() {
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
            val resolver = ctx.contentResolver
            // SMS, MMS ve ikisinin ortak konuşma tablosu.
            resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, created)
            resolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, created)
            resolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, created)
            observer = created
        } catch (_: SecurityException) {
        }
    }

    private suspend fun load() {
        if (!_state.value.ready) return
        val current = _screen.value
        try {
            withContext(Dispatchers.IO) {
                val sims = Sims.active(ctx)
                val defaultSubId = SmsManager.getDefaultSmsSubscriptionId()
                val conversations = SmsStore.conversations(ctx)
                val blockedNumbers = Blocking.list(ctx)
                val blockingAvailable = Blocking.available(ctx)
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
                        allConversations = conversations,
                        blockedNumbers = blockedNumbers,
                        blockingAvailable = blockingAvailable,
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
        viewModelScope.launch {
            val contacts = withContext(Dispatchers.IO) { SmsStore.contacts(ctx) }
            _state.update { it.copy(contacts = contacts) }
        }
    }

    fun openSettings() {
        clearActive()
        _screen.value = Screen.Settings
    }

    fun openBlocked() {
        clearActive()
        _screen.value = Screen.Blocked
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

    // ---- Mesaj işlemleri ----

    private fun launchIo(errorPrefix: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _errors.tryEmit("$errorPrefix: ${e.message ?: "bilinmeyen hata"}")
            }
        }
    }

    /**
     * Gönderir ve mesajın yazıldığı (thread, SIM) konuşmasını açar.
     * [image] verilirse mesaj MMS olarak gider.
     */
    fun send(address: String, body: String, subId: Int, image: Uri? = null) {
        launchIo("Gönderilemedi") {
            val (threadId, usedSubId) = withContext(Dispatchers.IO) {
                if (image != null) MmsStore.send(ctx, address, body, image, subId)
                else SmsStore.send(ctx, address, body, subId)
            }
            val current = _screen.value
            val same = current is Screen.Conversation &&
                current.threadId == threadId && current.subId == usedSubId
            if (same) reload() else openConversation(threadId, usedSubId, address)
        }
    }

    /** Gönderilemeyen SMS'i silip aynı SIM'den yeniden gönderir. */
    fun retry(screen: Screen.Conversation, message: Message) {
        if (message.mms) return
        launchIo("Gönderilemedi") {
            withContext(Dispatchers.IO) {
                SmsStore.deleteMessage(ctx, message)
                SmsStore.send(ctx, screen.address, message.body, screen.subId)
            }
            reload()
        }
    }

    fun deleteMessage(message: Message) {
        launchIo("Silinemedi") {
            withContext(Dispatchers.IO) { SmsStore.deleteMessage(ctx, message) }
            reload()
        }
    }

    fun deleteConversation(threadId: Long, subId: Int) {
        launchIo("Silinemedi") {
            withContext(Dispatchers.IO) { SmsStore.deleteConversation(ctx, threadId, subId) }
            back()
        }
    }

    /** Mesaj metinlerinde arar; yazarken her tuşta sorgu atmamak için kısa bir bekleme var. */
    fun search(query: String) {
        searchJob?.cancel()
        val text = query.trim()
        if (text.length < 2) {
            _state.update { it.copy(searchHits = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            val hits = try {
                withContext(Dispatchers.IO) { SmsStore.search(ctx, text) }
            } catch (_: Exception) {
                emptyList()
            }
            _state.update { it.copy(searchHits = hits) }
        }
    }

    // ---- Engelleme ----

    fun setBlocked(number: String, blocked: Boolean) {
        launchIo(if (blocked) "Engellenemedi" else "Engel kaldırılamadı") {
            withContext(Dispatchers.IO) {
                if (blocked) {
                    Blocking.block(ctx, number.trim())
                    // Engellenen numaranın okunmamış mesajları ve bildirimi kalmasın.
                    SmsStore.conversations(ctx)
                        .filter { it.blocked && it.unread > 0 }
                        .forEach { SmsStore.markRead(ctx, it.threadId, it.subId) }
                } else {
                    Blocking.unblock(ctx, number)
                }
            }
            reload()
        }
    }

    // ---- SIM ayarları ----

    fun setSimName(subId: Int, name: String) {
        SimPrefs.setName(ctx, subId, name)
        reload()
    }

    fun setSimColor(subId: Int, color: Int) {
        SimPrefs.setColor(ctx, subId, color)
        reload()
    }

    override fun onCleared() {
        observer?.let { ctx.contentResolver.unregisterContentObserver(it) }
        clearActive()
    }
}
