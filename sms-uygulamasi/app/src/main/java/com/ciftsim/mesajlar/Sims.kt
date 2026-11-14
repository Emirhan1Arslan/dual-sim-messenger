package com.ciftsim.mesajlar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

data class SimInfo(
    val subId: Int,
    val slot: Int,
    val carrier: String,
    /** Kullanıcının verdiği ad; yoksa null. */
    val customName: String?,
    /** ARGB; kullanıcının seçtiği ya da yuvaya göre varsayılan renk. */
    val color: Int,
) {
    /** Rozet ve bildirimde görünen kısa ad: özel ad ya da "SIM 1". */
    val label: String get() = customName ?: "SIM ${slot + 1}"

    /** Sekme ve chip'lerde görünen ad: özel ad ya da "SIM 1 · Operatör". */
    val fullLabel: String
        get() = customName ?: if (carrier.isEmpty()) "SIM ${slot + 1}" else "SIM ${slot + 1} · $carrier"

    /** Ayarlar ekranında SIM'i tanıtmak için: "SIM 1 · Operatör". */
    val systemLabel: String
        get() = if (carrier.isEmpty()) "SIM ${slot + 1}" else "SIM ${slot + 1} · $carrier"
}

/** Kullanıcının SIM'lere verdiği ad ve renkler (subId'ye göre saklanır). */
object SimPrefs {
    /** Seçilebilir renkler; ilk ikisi SIM 1 ve SIM 2'nin varsayılanı. */
    val palette = intArrayOf(
        0xFF1E88E5.toInt(), // mavi
        0xFF43A047.toInt(), // yeşil
        0xFFE53935.toInt(), // kırmızı
        0xFFFB8C00.toInt(), // turuncu
        0xFF8E24AA.toInt(), // mor
        0xFF00897B.toInt(), // turkuaz
        0xFFD81B60.toInt(), // pembe
        0xFF6D4C41.toInt(), // kahverengi
        0xFF3949AB.toInt(), // lacivert
        0xFF546E7A.toInt(), // gri mavi
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sim_ayarlari", Context.MODE_PRIVATE)

    fun name(ctx: Context, subId: Int): String? =
        prefs(ctx).getString("ad_$subId", null)?.takeIf { it.isNotBlank() }

    fun color(ctx: Context, subId: Int, slot: Int): Int {
        val prefs = prefs(ctx)
        val key = "renk_$subId"
        return if (prefs.contains(key)) prefs.getInt(key, 0) else defaultColor(slot)
    }

    fun defaultColor(slot: Int): Int = when (slot) {
        0 -> palette[0]
        1 -> palette[1]
        else -> palette[9]
    }

    fun setName(ctx: Context, subId: Int, name: String) {
        prefs(ctx).edit().putString("ad_$subId", name.trim()).apply()
    }

    fun setColor(ctx: Context, subId: Int, color: Int) {
        prefs(ctx).edit().putInt("renk_$subId", color).apply()
    }
}

/** Aktif SIM'ler. */
object Sims {
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
            SimInfo(
                subId = it.subscriptionId,
                slot = it.simSlotIndex,
                carrier = carrier,
                customName = SimPrefs.name(ctx, it.subscriptionId),
                color = SimPrefs.color(ctx, it.subscriptionId, it.simSlotIndex),
            )
        }

    /** subId'ye karşılık gelen takılı SIM; yoksa null. */
    fun find(ctx: Context, subId: Int): SimInfo? = active(ctx).firstOrNull { it.subId == subId }
}

/**
 * Numara engelleme: sistemin ortak engelli listesi kullanılır (telefon uygulamasıyla aynı
 * liste), böylece engellenen numaradan arama da gelmez.
 */
object Blocking {
    fun available(ctx: Context): Boolean = try {
        BlockedNumberContract.canCurrentUserBlockNumbers(ctx)
    } catch (_: Exception) {
        false
    }

    fun list(ctx: Context): List<String> {
        val out = ArrayList<String>()
        try {
            ctx.contentResolver.query(
                BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { out += it }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun isBlocked(ctx: Context, number: String): Boolean = try {
        number.isNotBlank() && BlockedNumberContract.isBlocked(ctx, number)
    } catch (_: Exception) {
        false
    }

    /** Önceden okunmuş listeye göre karşılaştırma (konuşma listesi için, tek sorguyla). */
    @Suppress("DEPRECATION")
    fun matches(blocked: List<String>, number: String): Boolean =
        number.isNotBlank() && blocked.any { PhoneNumberUtils.compare(it, number) }

    fun block(ctx: Context, number: String) {
        if (number.isBlank() || isBlocked(ctx, number)) return
        val values = ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
        ctx.contentResolver.insert(BlockedNumbers.CONTENT_URI, values)
    }

    fun unblock(ctx: Context, number: String) {
        BlockedNumberContract.unblock(ctx, number)
    }
}

object Permissions {
    /** Uygulamanın çalışması için şart olanlar. */
    private val required = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_PHONE_STATE,
    )

    /** İsteğe bağlı: yoksa ilgili özellik (MMS, rehber adı, bildirim) çalışmaz. */
    private val optional = buildList {
        add(Manifest.permission.RECEIVE_MMS)
        add(Manifest.permission.RECEIVE_WAP_PUSH)
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
