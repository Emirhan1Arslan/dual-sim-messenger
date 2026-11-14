package com.ciftsim.mesajlar

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import java.util.concurrent.ConcurrentHashMap

/** (thread_id, sub_id) çiftiyle tanımlanan konuşma. */
data class Conversation(
    val threadId: Long,
    val subId: Int,
    val address: String,
    val name: String?,
    val snippet: String,
    val date: Long,
    val unread: Int,
    val lastType: Int,
) {
    val title: String get() = name?.takeIf { it.isNotEmpty() } ?: address
    val lastIsOutgoing: Boolean get() = lastType != Sms.MESSAGE_TYPE_INBOX
}

data class Message(val id: Long, val body: String, val date: Long, val type: Int) {
    val incoming: Boolean get() = type == Sms.MESSAGE_TYPE_INBOX
    val sending: Boolean
        get() = type == Sms.MESSAGE_TYPE_OUTBOX || type == Sms.MESSAGE_TYPE_QUEUED
    val failed: Boolean get() = type == Sms.MESSAGE_TYPE_FAILED
}

/** content://sms üzerindeki bütün okuma/yazma işlemleri ve SMS gönderimi. */
object SmsStore {
    private val nameCache = ConcurrentHashMap<String, String>()

    fun clearNameCache() = nameCache.clear()

    fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        nameCache[number]?.let { return it.ifEmpty { null } }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        var name = ""
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) name = c.getString(0).orEmpty() }
        } catch (_: Exception) {
        }
        nameCache[number] = name
        return name.ifEmpty { null }
    }

    /** Konuşmalar (thread_id, sub_id) çiftine göre gruplanır; en yeni üstte. */
    fun conversations(ctx: Context): List<Conversation> {
        class Group(
            val threadId: Long, val subId: Int, var address: String,
            val snippet: String, val date: Long, val type: Int, var unread: Int = 0,
        )

        val groups = LinkedHashMap<Pair<Long, Int>, Group>()
        ctx.contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(
                Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.READ,
                Sms.SUBSCRIPTION_ID,
            ),
            null, null, "${Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getInt(4)
                if (type == Sms.MESSAGE_TYPE_DRAFT) continue
                val threadId = c.getLong(0)
                val subId = if (c.isNull(6)) -1 else c.getInt(6)
                val address = c.getString(1).orEmpty()
                val group = groups.getOrPut(threadId to subId) {
                    Group(threadId, subId, address, c.getString(2).orEmpty(), c.getLong(3), type)
                }
                if (group.address.isEmpty() && address.isNotEmpty()) group.address = address
                if (type == Sms.MESSAGE_TYPE_INBOX && c.getInt(5) == 0) group.unread++
            }
        }
        return groups.values.map {
            Conversation(
                it.threadId, it.subId, it.address, contactName(ctx, it.address),
                it.snippet, it.date, it.unread, it.type,
            )
        }
    }

    fun messages(ctx: Context, threadId: Long, subId: Int): List<Message> {
        val out = ArrayList<Message>()
        ctx.contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID, Sms.BODY, Sms.DATE, Sms.TYPE),
            "${Sms.THREAD_ID}=? AND ${Sms.SUBSCRIPTION_ID}=? AND ${Sms.TYPE}!=?",
            arrayOf(threadId.toString(), subId.toString(), Sms.MESSAGE_TYPE_DRAFT.toString()),
            "${Sms.DATE} ASC, ${Sms._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += Message(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getInt(3))
            }
        }
        return out
    }

    fun insertInbox(
        ctx: Context, threadId: Long, address: String, body: String,
        sentAt: Long, subId: Int, read: Boolean,
    ) {
        val values = ContentValues().apply {
            put(Sms.THREAD_ID, threadId)
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.DATE_SENT, sentAt)
            put(Sms.READ, if (read) 1 else 0)
            put(Sms.SEEN, if (read) 1 else 0)
            put(Sms.SUBSCRIPTION_ID, subId)
        }
        ctx.contentResolver.insert(Sms.Inbox.CONTENT_URI, values)
    }

    fun markRead(ctx: Context, threadId: Long, subId: Int) {
        val values = ContentValues().apply {
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
        }
        ctx.contentResolver.update(
            Sms.CONTENT_URI, values,
            "${Sms.THREAD_ID}=? AND ${Sms.SUBSCRIPTION_ID}=? AND ${Sms.READ}=0",
            arrayOf(threadId.toString(), subId.toString()),
        )
        Notifier.cancel(ctx, threadId, subId)
    }

    fun deleteConversation(ctx: Context, threadId: Long, subId: Int) {
        ctx.contentResolver.delete(
            Sms.CONTENT_URI,
            "${Sms.THREAD_ID}=? AND ${Sms.SUBSCRIPTION_ID}=?",
            arrayOf(threadId.toString(), subId.toString()),
        )
        Notifier.cancel(ctx, threadId, subId)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context, subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = ctx.getSystemService(SmsManager::class.java)
            if (subId >= 0) manager.createForSubscriptionId(subId) else manager
        } else {
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId)
            else SmsManager.getDefault()
        }

    /**
     * Mesajı önce Outbox'a yazar, sonra seçilen SIM'den gönderir.
     * Sonuç SmsSentReceiver'da SENT ya da FAILED olarak işlenir.
     * Dönen değer: (threadId, gerçekte kullanılan subId).
     */
    fun send(ctx: Context, address: String, body: String, requestedSubId: Int): Pair<Long, Int> {
        val subId =
            if (requestedSubId >= 0) requestedSubId else SmsManager.getDefaultSmsSubscriptionId()
        val threadId = Telephony.Threads.getOrCreateThreadId(ctx, address)
        val values = ContentValues().apply {
            put(Sms.THREAD_ID, threadId)
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            put(Sms.SUBSCRIPTION_ID, subId)
        }
        val uri = ctx.contentResolver.insert(Sms.Outbox.CONTENT_URI, values)
            ?: throw IllegalStateException("Mesaj giden kutusuna yazılamadı")
        try {
            val manager = smsManager(ctx, subId)
            val parts = manager.divideMessage(body)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val intent = Intent(ctx, SmsSentReceiver::class.java)
                    .setAction(SmsSentReceiver.ACTION_SENT)
                    .setData(uri)
                    .putExtra(SmsSentReceiver.EXTRA_LAST_PART, i == parts.size - 1)
                sentIntents += PendingIntent.getBroadcast(
                    ctx, i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            manager.sendMultipartTextMessage(address, null, parts, sentIntents, null)
        } catch (_: Exception) {
            setType(ctx, uri, Sms.MESSAGE_TYPE_FAILED, onlyIfOutbox = false)
        }
        return threadId to subId
    }

    fun setType(ctx: Context, uri: Uri, type: Int, onlyIfOutbox: Boolean) {
        val values = ContentValues().apply { put(Sms.TYPE, type) }
        ctx.contentResolver.update(
            uri, values,
            if (onlyIfOutbox) "${Sms.TYPE}=${Sms.MESSAGE_TYPE_OUTBOX}" else null,
            null,
        )
    }
}
