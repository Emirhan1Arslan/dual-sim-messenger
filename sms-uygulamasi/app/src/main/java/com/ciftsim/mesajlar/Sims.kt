package com.ciftsim.mesajlar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

data class SimInfo(val subId: Int, val slot: Int, val carrier: String) {
    /** "SIM 1" / "SIM 2" */
    val label: String get() = "SIM ${slot + 1}"

    /** "SIM 1 · Operatör" */
    val fullLabel: String get() = if (carrier.isEmpty()) label else "$label · $carrier"

    /** ARGB; her SIM'in sabit rengi. */
    val color: Int get() = Sims.colorForSlot(slot)
}

/** Aktif SIM'ler ve SIM'e özgü sabit renkler. */
object Sims {
    const val COLOR_SIM1 = 0xFF1E88E5.toInt() // mavi
    const val COLOR_SIM2 = 0xFF43A047.toInt() // yeşil
    const val COLOR_OTHER = 0xFF757575.toInt()

    fun colorForSlot(slot: Int): Int = when (slot) {
        0 -> COLOR_SIM1
        1 -> COLOR_SIM2
        else -> COLOR_OTHER
    }

    private fun infos(ctx: Context): List<SubscriptionInfo> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val manager = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return try {
            manager.activeSubscriptionInfoList ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun active(ctx: Context): List<SimInfo> =
        infos(ctx).sortedBy { it.simSlotIndex }.map {
            val carrier = it.carrierName?.toString()?.takeIf { n -> n.isNotBlank() }
                ?: it.displayName?.toString().orEmpty()
            SimInfo(it.subscriptionId, it.simSlotIndex, carrier)
        }

    /** subId'nin yuva numarası (0 tabanlı); SIM artık takılı değilse -1. */
    fun slotOf(ctx: Context, subId: Int): Int =
        infos(ctx).firstOrNull { it.subscriptionId == subId }?.simSlotIndex ?: -1
}

object Permissions {
    /** Uygulamanın çalışması için şart olanlar. */
    private val required = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_PHONE_STATE,
    )

    /** İsteğe bağlı: yoksa rehber adı ve bildirim görünmez, uygulama yine çalışır. */
    private val optional = buildList {
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun granted(ctx: Context, permission: String) =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasRequired(ctx: Context): Boolean = required.all { granted(ctx, it) }

    fun missing(ctx: Context): Array<String> =
        (required + optional).filterNot { granted(ctx, it) }.toTypedArray()
}

/** Ekranda açık olan konuşma: bu konuşmaya gelen mesaj okundu sayılır, bildirim çıkmaz. */
object AppState {
    @Volatile var resumed = false
    @Volatile var activeThread = -1L
    @Volatile var activeSub = -1

    fun isOpen(threadId: Long, subId: Int): Boolean =
        resumed && activeThread == threadId && activeSub == subId
}
