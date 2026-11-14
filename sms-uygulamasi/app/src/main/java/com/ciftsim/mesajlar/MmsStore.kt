package com.ciftsim.mesajlar

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Telephony.Mms
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.RetrieveConf
import com.google.android.mms.pdu_alt.SendReq
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MMS alma ve gönderme. Ağ işini Android'in kendisi yapar (SmsManager); burada yalnızca
 * PDU dosyası hazırlanır / ayrıştırılır ve sonuç content://mms veritabanına yazılır.
 */
object MmsStore {
    const val EXTRA_FILE = "dosya"
    const val EXTRA_SUB_ID = "subId"
    private const val DEFAULT_MAX_SIZE = 300 * 1024

    private fun tempFile(ctx: Context, prefix: String): File {
        val dir = File(ctx.cacheDir, "mms").apply { mkdirs() }
        return File(dir, "$prefix-${System.currentTimeMillis()}-${(0..99999).random()}.pdu")
    }

    private fun shareUri(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".dosyalar", file)

    private fun threadIdOf(ctx: Context, message: Uri): Long {
        ctx.contentResolver.query(message, arrayOf(Mms.THREAD_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return -1L
    }

    // ---- Alma ----

    /** WAP push ile gelen MMS bildirimini ayrıştırır ve içeriği indirmeye başlar. */
    fun startDownload(ctx: Context, pushData: ByteArray, subId: Int) {
        val notification = PduParser(pushData).parse() as? NotificationInd ?: return
        val location = notification.contentLocation?.let { String(it) } ?: return
        // Engellenen numaradan gelen MMS hiç indirilmez.
        val from = notification.from?.string.orEmpty()
        if (Blocking.isBlocked(ctx, from)) return

        val file = tempFile(ctx, "gelen")
        file.createNewFile()
        val intent = Intent(ctx, MmsDownloadedReceiver::class.java)
            .setData(Uri.fromFile(file))
            .putExtra(EXTRA_FILE, file.absolutePath)
            .putExtra(EXTRA_SUB_ID, subId)
        val done = PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            SmsStore.smsManager(ctx, subId)
                .downloadMultimediaMessage(ctx, location, shareUri(ctx, file), null, done)
        } catch (_: Exception) {
            file.delete()
            Notifier.showMmsFailed(ctx, subId)
        }
    }

    /** İndirme bittiğinde: PDU'yu ayrıştırır, Inbox'a yazar ve bildirim gösterir. */
    fun onDownloaded(ctx: Context, file: File, subId: Int, resultCode: Int) {
        try {
            if (resultCode != Activity.RESULT_OK || file.length() == 0L) {
                Notifier.showMmsFailed(ctx, subId)
                return
            }
            val pdu = PduParser(file.readBytes()).parse() as? RetrieveConf
            if (pdu == null) {
                Notifier.showMmsFailed(ctx, subId)
                return
            }
            // Operatör aynı bildirimi tekrar yollarsa mesajı ikinci kez kaydetme.
            val messageId = pdu.messageId?.let { String(it) }
            if (messageId != null && exists(ctx, messageId)) return

            val uri = PduPersister.getPduPersister(ctx)
                .persist(pdu, Mms.Inbox.CONTENT_URI, true, true, null, subId)
            val threadId = threadIdOf(ctx, uri)
            val address = pdu.from?.string.orEmpty()
            val quiet = AppState.isOpen(threadId, subId) || Blocking.isBlocked(ctx, address)
            ctx.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(Mms.DATE, System.currentTimeMillis() / 1000)
                    put(Mms.READ, if (quiet) 1 else 0)
                    put(Mms.SEEN, if (quiet) 1 else 0)
                    put(Mms.SUBSCRIPTION_ID, subId)
                },
                null, null,
            )
            if (!quiet) {
                Notifier.show(ctx, address, textOf(pdu.body).ifEmpty { "Fotoğraf" }, threadId, subId)
            }
        } catch (_: Exception) {
            Notifier.showMmsFailed(ctx, subId)
        } finally {
            file.delete()
        }
    }

    private fun exists(ctx: Context, messageId: String): Boolean {
        ctx.contentResolver.query(
            Mms.CONTENT_URI, arrayOf(Mms._ID), "${Mms.MESSAGE_ID}=?", arrayOf(messageId), null,
        )?.use { return it.count > 0 }
        return false
    }

    private fun textOf(body: PduBody?): String {
        body ?: return ""
        val out = StringBuilder()
        for (i in 0 until body.partsNum) {
            val part = body.getPart(i)
            if (String(part.contentType ?: continue).lowercase() == "text/plain") {
                val data = part.data ?: continue
                val charset = try {
                    charset(CharacterSets.getMimeName(part.charset))
                } catch (_: Exception) {
                    Charsets.UTF_8
                }
                if (out.isNotEmpty()) out.append('\n')
                out.append(String(data, charset))
            }
        }
        return out.toString()
    }

    // ---- Gönderme ----

    /**
     * Resimli mesaj gönderir: resmi operatör sınırına sığacak şekilde küçültür, Outbox'a
     * yazar ve SmsManager'a teslim eder. Sonuç MmsSentReceiver'da işlenir.
     * Dönen değer: (threadId, kullanılan subId).
     */
    fun send(
        ctx: Context, address: String, text: String, image: Uri, requestedSubId: Int,
    ): Pair<Long, Int> {
        val subId = SmsStore.resolveSubId(requestedSubId)
        val manager = SmsStore.smsManager(ctx, subId)
        val limit = try {
            manager.carrierConfigValues
                ?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MAX_SIZE)
                ?: DEFAULT_MAX_SIZE
        } catch (_: Exception) {
            DEFAULT_MAX_SIZE
        }.coerceIn(100 * 1024, 1024 * 1024)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val jpeg = compress(ctx, image, limit - textBytes.size - 4096)

        val body = PduBody()
        val smil = StringBuilder("<smil><head><layout><root-layout/>")
            .append("<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>")
            .append("<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>")
            .append("</layout></head><body><par dur=\"5000ms\">")
            .append("<img src=\"resim.jpg\" region=\"Image\"/>")
        if (textBytes.isNotEmpty()) smil.append("<text src=\"metin.txt\" region=\"Text\"/>")
        smil.append("</par></body></smil>")

        body.addPart(part("application/smil", "smil.xml", smil.toString().toByteArray()))
        body.addPart(part("image/jpeg", "resim.jpg", jpeg))
        if (textBytes.isNotEmpty()) {
            body.addPart(part("text/plain", "metin.txt", textBytes).apply {
                charset = CharacterSets.UTF_8
            })
        }

        val request = SendReq().apply {
            setTo(arrayOf(EncodedStringValue(address)))
            date = System.currentTimeMillis() / 1000
            this.body = body
            messageSize = (jpeg.size + textBytes.size + smil.length).toLong()
            messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray()
            expiry = 7L * 24 * 60 * 60
            priority = PduHeaders.PRIORITY_NORMAL
            deliveryReport = PduHeaders.VALUE_NO
            readReport = PduHeaders.VALUE_NO
        }
        val bytes = PduComposer(ctx, request).make()
            ?: throw IllegalStateException("MMS hazırlanamadı")

        val message = PduPersister.getPduPersister(ctx)
            .persist(request, Mms.Outbox.CONTENT_URI, true, true, null, subId)
        ctx.contentResolver.update(
            message,
            ContentValues().apply {
                put(Mms.DATE, System.currentTimeMillis() / 1000)
                put(Mms.READ, 1)
                put(Mms.SEEN, 1)
                put(Mms.SUBSCRIPTION_ID, subId)
            },
            null, null,
        )
        val threadId = threadIdOf(ctx, message)

        val file = tempFile(ctx, "giden")
        try {
            file.writeBytes(bytes)
            val intent = Intent(ctx, MmsSentReceiver::class.java)
                .setData(message)
                .putExtra(EXTRA_FILE, file.absolutePath)
            val sent = PendingIntent.getBroadcast(
                ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.sendMultimediaMessage(ctx, shareUri(ctx, file), null, null, sent)
        } catch (_: Exception) {
            file.delete()
            setBox(ctx, message, Mms.MESSAGE_BOX_FAILED)
        }
        return threadId to subId
    }

    fun setBox(ctx: Context, message: Uri, box: Int) {
        ctx.contentResolver.update(
            message, ContentValues().apply { put(Mms.MESSAGE_BOX, box) }, null, null,
        )
    }

    private fun part(type: String, name: String, data: ByteArray) = PduPart().apply {
        contentType = type.toByteArray()
        contentLocation = name.toByteArray()
        contentId = name.substringBefore('.').toByteArray()
        this.name = name.toByteArray()
        this.data = data
    }

    /** Resmi JPEG'e çevirir; sınıra sığana kadar önce kaliteyi, sonra boyutu düşürür. */
    private fun compress(ctx: Context, image: Uri, maxBytes: Int): ByteArray {
        var maxSide = 1600
        var last = ByteArray(0)
        while (maxSide >= 320) {
            val side = maxSide
            val bitmap = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(ctx.contentResolver, image)
            ) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = max(info.size.width, info.size.height)
                if (largest > side) {
                    val scale = side.toFloat() / largest
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).roundToInt()),
                        max(1, (info.size.height * scale).roundToInt()),
                    )
                }
            }
            for (quality in intArrayOf(85, 70, 55)) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                last = out.toByteArray()
                if (last.size <= maxBytes) {
                    bitmap.recycle()
                    return last
                }
            }
            bitmap.recycle()
            maxSide = maxSide * 3 / 4
        }
        return last
    }
}
