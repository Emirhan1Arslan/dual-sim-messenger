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
import android.provider.Telephony.Mms
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
    val blocked: Boolean = false,
) {
    val title: String get() = name?.takeIf { it.isNotEmpty() } ?: address
    val lastIsOutgoing: Boolean get() = lastType != Sms.MESSAGE_TYPE_INBOX
}

/** MMS eki (content://mms/part/N). */
data class Attachment(val uri: String, val mime: String) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/**
 * SMS ya da MMS. [type] iki tür için de aynı değerleri kullanır:
 * 1 gelen, 2 gönderildi, 4 gönderiliyor, 5 gönderilemedi.
 */
data class Message(
    val id: Long,
    val mms: Boolean,
    val body: String,
    val date: Long,
    val type: Int,
    val attachments: List<Attachment> = emptyList(),
) {
    val key: String get() = if (mms) "m$id" else "s$id"
    val incoming: Boolean get() = type == Sms.MESSAGE_TYPE_INBOX
    val sending: Boolean
        get() = type == Sms.MESSAGE_TYPE_OUTBOX || type == Sms.MESSAGE_TYPE_QUEUED
    val failed: Boolean get() = type == Sms.MESSAGE_TYPE_FAILED
}

data class Contact(val name: String, val number: String)

/** content://sms ve content://mms üzerindeki okuma/yazma işlemleri ve SMS gönderimi. */
object SmsStore {
    private const val MMS_SEND_REQ = 128
    private const val MMS_RETRIEVE_CONF = 132
    private const val MMS_ADDR_FROM = 137
    private const val MMS_ADDR_TO = 151
    private val MMS_PARTS: Uri = Uri.parse("content://mms/part")

    private val nameCache = ConcurrentHashMap<String, String>()

    fun clearNameCache() = nameCache.clear()

    private fun canReadContacts(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        nameCache[number]?.let { return it.ifEmpty { null } }
        if (!canReadContacts(ctx)) return null
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

    /** Rehberdeki telefon numarası olan kişiler (ada göre sıralı). */
    fun contacts(ctx: Context): List<Contact> {
        if (!canReadContacts(ctx)) return emptyList()
        val out = ArrayList<Contact>()
        val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0).orEmpty()
                    val number = c.getString(1).orEmpty().trim()
                    if (number.isEmpty()) continue
                    // Aynı kişinin aynı numarası farklı biçimlerde kayıtlı olabilir.
                    if (seen.add(name + "|" + number.filter { it.isDigit() }.takeLast(10))) {
                        out += Contact(name.ifEmpty { number }, number)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---- Konuşmalar ----

    private class Group(
        val threadId: Long, val subId: Int, var address: String,
        var snippet: String, var date: Long, var type: Int,
    ) {
        var unread = 0
        /** Son mesaj MMS ise onun kimliği; değilse -1. */
        var latestMms = -1L
        /** Konuşmadaki herhangi bir MMS (adres bulmak için). */
        var anyMms = -1L
        var anyMmsIncoming = false
    }

    /** Konuşmalar (thread_id, sub_id) çiftine göre gruplanır; en yeni üstte. */
    fun conversations(ctx: Context): List<Conversation> {
        val groups = HashMap<Pair<Long, Int>, Group>()

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

        try {
            ctx.contentResolver.query(
                Mms.CONTENT_URI,
                arrayOf(
                    Mms._ID, Mms.THREAD_ID, Mms.DATE, Mms.MESSAGE_BOX, Mms.READ,
                    Mms.SUBSCRIPTION_ID, Mms.MESSAGE_TYPE,
                ),
                null, null, "${Mms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val messageType = c.getInt(6)
                    if (messageType != MMS_SEND_REQ && messageType != MMS_RETRIEVE_CONF) continue
                    val box = c.getInt(3)
                    if (box == Mms.MESSAGE_BOX_DRAFTS) continue
                    val id = c.getLong(0)
                    val threadId = c.getLong(1)
                    val subId = if (c.isNull(5)) -1 else c.getInt(5)
                    val date = c.getLong(2) * 1000 // MMS tarihi saniye cinsinden
                    val existing = groups[threadId to subId]
                    val group = existing ?: Group(threadId, subId, "", "", date, box).also {
                        it.latestMms = id
                        groups[threadId to subId] = it
                    }
                    if (existing != null && date > group.date) {
                        group.date = date
                        group.type = box
                        group.latestMms = id
                    }
                    if (group.anyMms < 0) {
                        group.anyMms = id
                        group.anyMmsIncoming = box == Mms.MESSAGE_BOX_INBOX
                    }
                    if (box == Mms.MESSAGE_BOX_INBOX && c.getInt(4) == 0) group.unread++
                }
            }
        } catch (_: Exception) {
            // MMS tablosu okunamıyorsa yalnızca SMS'lerle devam et.
        }

        val blocked = Blocking.list(ctx)
        return groups.values.sortedByDescending { it.date }.map { group ->
            if (group.latestMms >= 0) {
                group.snippet = mmsSnippet(ctx, group.latestMms)
            }
            if (group.address.isEmpty() && group.anyMms >= 0) {
                group.address = mmsAddress(ctx, group.anyMms, group.anyMmsIncoming)
            }
            Conversation(
                group.threadId, group.subId, group.address, contactName(ctx, group.address),
                group.snippet, group.date, group.unread, group.type,
                blocked = Blocking.matches(blocked, group.address),
            )
        }
    }

    private fun mmsSnippet(ctx: Context, id: Long): String {
        val parts = mmsParts(ctx, listOf(id))[id]
        val text = parts?.first.orEmpty()
        return when {
            text.isNotEmpty() -> text
            parts?.second?.any { it.isImage } == true -> "Fotoğraf"
            else -> "MMS"
        }
    }

    /** MMS'in karşı taraf adresi: gelen mesajda gönderen, giden mesajda alıcı. */
    fun mmsAddress(ctx: Context, id: Long, incoming: Boolean): String {
        val wanted = if (incoming) MMS_ADDR_FROM else MMS_ADDR_TO
        try {
            ctx.contentResolver.query(
                Uri.parse("content://mms/$id/addr"),
                arrayOf(Mms.Addr.ADDRESS, Mms.Addr.TYPE), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val address = c.getString(0).orEmpty()
                    if (c.getInt(1) == wanted && address.isNotEmpty() &&
                        address != "insert-address-token"
                    ) return address
                }
            }
        } catch (_: Exception) {
        }
        return ""
    }

    // ---- Mesajlar ----

    fun messages(ctx: Context, threadId: Long, subId: Int): List<Message> {
        val out = ArrayList<Message>()
        ctx.contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID, Sms.BODY, Sms.DATE, Sms.TYPE),
            "${Sms.THREAD_ID}=? AND ${Sms.SUBSCRIPTION_ID}=? AND ${Sms.TYPE}!=?",
            arrayOf(threadId.toString(), subId.toString(), Sms.MESSAGE_TYPE_DRAFT.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += Message(
                    id = c.getLong(0), mms = false, body = c.getString(1).orEmpty(),
                    date = c.getLong(2), type = c.getInt(3),
                )
            }
        }

        try {
            val headers = ArrayList<Triple<Long, Long, Int>>() // id, tarih, kutu
            ctx.contentResolver.query(
                Mms.CONTENT_URI,
                arrayOf(Mms._ID, Mms.DATE, Mms.MESSAGE_BOX),
                "${Mms.THREAD_ID}=? AND ${Mms.SUBSCRIPTION_ID}=? AND " +
                    "${Mms.MESSAGE_TYPE} IN ($MMS_SEND_REQ,$MMS_RETRIEVE_CONF) AND " +
                    "${Mms.MESSAGE_BOX}!=${Mms.MESSAGE_BOX_DRAFTS}",
                arrayOf(threadId.toString(), subId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    headers += Triple(c.getLong(0), c.getLong(1) * 1000, c.getInt(2))
                }
            }
            if (headers.isNotEmpty()) {
                val parts = mmsParts(ctx, headers.map { it.first })
                for ((id, date, box) in headers) {
                    val (text, attachments) = parts[id] ?: ("" to emptyList())
                    // MMS kutu numaraları SMS tür numaralarıyla aynıdır (1, 2, 4, 5).
                    out += Message(id, mms = true, body = text, date = date, type = box,
                        attachments = attachments)
                }
            }
        } catch (_: Exception) {
        }

        out.sortWith(compareBy<Message> { it.date }.thenBy { it.id })
        return out
    }

    /** Verilen MMS'lerin metni ve ekleri (tek sorguyla). */
    private fun mmsParts(ctx: Context, ids: List<Long>): Map<Long, Pair<String, List<Attachment>>> {
        val texts = HashMap<Long, StringBuilder>()
        val attachments = HashMap<Long, MutableList<Attachment>>()
        ctx.contentResolver.query(
            MMS_PARTS,
            arrayOf(Mms.Part._ID, Mms.Part.MSG_ID, Mms.Part.CONTENT_TYPE, Mms.Part.TEXT),
            "${Mms.Part.MSG_ID} IN (${ids.joinToString(",")})",
            null, "${Mms.Part._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val partId = c.getLong(0)
                val messageId = c.getLong(1)
                val type = c.getString(2).orEmpty().lowercase()
                when {
                    type == "application/smil" -> Unit
                    type == "text/plain" -> {
                        val text = c.getString(3) ?: readPartText(ctx, partId)
                        if (text.isNotBlank()) {
                            val builder = texts.getOrPut(messageId) { StringBuilder() }
                            if (builder.isNotEmpty()) builder.append('\n')
                            builder.append(text)
                        }
                    }
                    else -> attachments.getOrPut(messageId) { ArrayList() } +=
                        Attachment("content://mms/part/$partId", type)
                }
            }
        }
        return ids.associateWith {
            (texts[it]?.toString().orEmpty()) to (attachments[it] ?: emptyList())
        }
    }

    private fun readPartText(ctx: Context, partId: Long): String = try {
        ctx.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** Mesaj metninde arama; her sonuç, ilgili konuşmayı açan bir satır olarak döner. */
    fun search(ctx: Context, query: String): List<Conversation> {
        val out = ArrayList<Conversation>()
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        ctx.contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.SUBSCRIPTION_ID),
            "${Sms.BODY} LIKE ? ESCAPE '\\' AND ${Sms.TYPE}!=${Sms.MESSAGE_TYPE_DRAFT}",
            arrayOf(pattern),
            "${Sms.DATE} DESC LIMIT 100",
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1).orEmpty()
                out += Conversation(
                    threadId = c.getLong(0),
                    subId = if (c.isNull(5)) -1 else c.getInt(5),
                    address = address,
                    name = contactName(ctx, address),
                    snippet = c.getString(2).orEmpty(),
                    date = c.getLong(3),
                    unread = 0,
                    lastType = c.getInt(4),
                )
            }
        }
        return out
    }

    // ---- Yazma ----

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
        val args = arrayOf(threadId.toString(), subId.toString())
        ctx.contentResolver.update(
            Sms.CONTENT_URI,
            ContentValues().apply {
                put(Sms.READ, 1)
                put(Sms.SEEN, 1)
            },
            "${Sms.THREAD_ID}=? AND ${Sms.SUBSCRIPTION_ID}=? AND ${Sms.READ}=0", args,
        )
        try {
            ctx.contentResolver.update(
                Mms.CONTENT_URI,
                ContentValues().apply {
                    put(Mms.READ, 1)
                    put(Mms.SEEN, 1)
                },
                "${Mms.THREAD_ID}=? AND ${Mms.SUBSCRIPTION_ID}=? AND ${Mms.READ}=0", args,
            )
        } catch (_: Exception) {
        }
        Notifier.cancel(ctx, threadId, subId)
    }

    fun deleteConversation(ctx: Context, threadId: Long, subId: Int) {
        val args = arrayOf(threadId.toString(), subId.toString())
        ctx.contentResolver.delete(
            Sms.CONTENT_URI, "${Sms.THREAD_ID}=? AND ${Sms.SUBSCRIPTION_ID}=?", args,
        )
        try {
            ctx.contentResolver.delete(
                Mms.CONTENT_URI, "${Mms.THREAD_ID}=? AND ${Mms.SUBSCRIPTION_ID}=?", args,
            )
        } catch (_: Exception) {
        }
        Notifier.cancel(ctx, threadId, subId)
    }

    fun deleteMessage(ctx: Context, message: Message) {
        val base = if (message.mms) Mms.CONTENT_URI else Sms.CONTENT_URI
        ctx.contentResolver.delete(Uri.withAppendedPath(base, message.id.toString()), null, null)
    }

    // ---- SMS gönderimi ----

    @Suppress("DEPRECATION")
    fun smsManager(ctx: Context, subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = ctx.getSystemService(SmsManager::class.java)
            if (subId >= 0) manager.createForSubscriptionId(subId) else manager
        } else {
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId)
            else SmsManager.getDefault()
        }

    fun resolveSubId(requested: Int): Int =
        if (requested >= 0) requested else SmsManager.getDefaultSmsSubscriptionId()

    /**
     * Mesajı önce Outbox'a yazar, sonra seçilen SIM'den gönderir.
     * Sonuç SmsSentReceiver'da SENT ya da FAILED olarak işlenir.
     * Dönen değer: (threadId, gerçekte kullanılan subId).
     */
    fun send(ctx: Context, address: String, body: String, requestedSubId: Int): Pair<Long, Int> {
        val subId = resolveSubId(requestedSubId)
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
