package com.ciftsim.cift_sim_mesajlar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

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

    fun active(ctx: Context): List<Map<String, Any?>> =
        infos(ctx).sortedBy { it.simSlotIndex }.map {
            val carrier = it.carrierName?.toString()?.takeIf { n -> n.isNotBlank() }
                ?: it.displayName?.toString().orEmpty()
            mapOf(
                "subId" to it.subscriptionId,
                "slot" to it.simSlotIndex,
                "carrier" to carrier,
            )
        }

    /** subId'nin yuva numarası (0 tabanlı); SIM artık takılı değilse -1. */
    fun slotOf(ctx: Context, subId: Int): Int =
        infos(ctx).firstOrNull { it.subscriptionId == subId }?.simSlotIndex ?: -1
}

/** Ekranda açık olan konuşma: bu konuşmaya gelen mesaj okundu sayılır, bildirim çıkmaz. */
object AppState {
    @Volatile var resumed = false
    @Volatile var activeThread = -1L
    @Volatile var activeSub = -1

    fun isOpen(threadId: Long, subId: Int): Boolean =
        resumed && activeThread == threadId && activeSub == subId
}
