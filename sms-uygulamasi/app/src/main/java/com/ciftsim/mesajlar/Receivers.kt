package com.ciftsim.mesajlar

import android.app.Activity
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import java.io.File

private const val EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX"
private const val EXTRA_SUBSCRIPTION_LEGACY = "subscription"

/** Intent'ten SIM (abonelik) kimliğini okur; bulunamazsa sistemin varsayılan SMS SIM'i. */
private fun subIdFrom(intent: Intent): Int {
    var subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, -1)
    if (subId < 0) subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_LEGACY, -1)
    if (subId < 0) subId = SmsManager.getDefaultSmsSubscriptionId()
    return subId
}

/** Veritabanı / dosya işi yapan alıcılar için: işi arka planda yürütür. */
private fun BroadcastReceiver.inBackground(block: () -> Unit) {
    val pending = goAsync()
    Thread {
        try {
            block()
        } catch (_: Exception) {
        } finally {
            pending.finish()
        }
    }.start()
}

/** SMS_DELIVER: gelen SMS'i Inbox'a yazar ve bildirim gösterir. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return

        val first = parts[0]
        val address = (first.displayOriginatingAddress ?: first.originatingAddress)
            .orEmpty().ifEmpty { "Bilinmeyen" }
        // Çok parçalı mesajın parçalarını tek gövdede birleştir.
        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        val subId = subIdFrom(intent)
        val ctx = context.applicationContext

        inBackground {
            val threadId = Telephony.Threads.getOrCreateThreadId(ctx, address)
            // Engellenen numaranın mesajı kaydedilir ama okunmuş sayılır ve bildirim çıkmaz;
            // "Engellenenler" ekranından görülebilir.
            val quiet = AppState.isOpen(threadId, subId) || Blocking.isBlocked(ctx, address)
            SmsStore.insertInbox(
                ctx, threadId, address, body, first.timestampMillis, subId, read = quiet,
            )
            if (!quiet) Notifier.show(ctx, address, body, threadId, subId)
        }
    }
}

/** WAP_PUSH_DELIVER: gelen MMS bildirimi; içerik SmsManager ile indirilir. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = subIdFrom(intent)
        val ctx = context.applicationContext
        inBackground { MmsStore.startDownload(ctx, data, subId) }
    }
}

/** MMS indirmesi bittiğinde çağrılır. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(MmsStore.EXTRA_FILE) ?: return
        val subId = intent.getIntExtra(MmsStore.EXTRA_SUB_ID, -1)
        val result = resultCode
        val ctx = context.applicationContext
        inBackground { MmsStore.onDownloaded(ctx, File(path), subId, result) }
    }
}

/** SMS gönderim sonucu: Outbox'taki mesajı SENT ya da FAILED yapar. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val ctx = context.applicationContext
        if (resultCode != Activity.RESULT_OK) {
            SmsStore.setType(ctx, uri, Telephony.Sms.MESSAGE_TYPE_FAILED, onlyIfOutbox = false)
        } else if (intent.getBooleanExtra(EXTRA_LAST_PART, true)) {
            // Önceki bir parça başarısız olduysa FAILED durumunu ezme.
            SmsStore.setType(ctx, uri, Telephony.Sms.MESSAGE_TYPE_SENT, onlyIfOutbox = true)
        }
    }

    companion object {
        const val ACTION_SENT = "com.ciftsim.mesajlar.SMS_SENT"
        const val EXTRA_LAST_PART = "last_part"
    }
}

/** MMS gönderim sonucu. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.data ?: return
        intent.getStringExtra(MmsStore.EXTRA_FILE)?.let { File(it).delete() }
        val box = if (resultCode == Activity.RESULT_OK) Telephony.Mms.MESSAGE_BOX_SENT
        else Telephony.Mms.MESSAGE_BOX_FAILED
        MmsStore.setBox(context.applicationContext, message, box)
    }
}

/** Bildirimdeki "Yanıtla" ve "Okundu" eylemleri. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(MainActivity.EXTRA_THREAD_ID, -1L)
        val subId = intent.getIntExtra(MainActivity.EXTRA_SUB_ID, -1)
        val address = intent.getStringExtra(MainActivity.EXTRA_ADDRESS).orEmpty()
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        val ctx = context.applicationContext
        inBackground {
            if (intent.action == ACTION_REPLY && reply.isNotEmpty() && address.isNotEmpty()) {
                SmsStore.send(ctx, address, reply, subId)
            }
            // Bildirimi de kapatır.
            SmsStore.markRead(ctx, threadId, subId)
        }
    }

    companion object {
        const val ACTION_REPLY = "com.ciftsim.mesajlar.REPLY"
        const val ACTION_MARK_READ = "com.ciftsim.mesajlar.MARK_READ"
        const val KEY_REPLY = "yanit"
    }
}

/** RESPOND_VIA_MESSAGE: gelen aramayı hazır mesajla reddetme. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val address = intent.data?.schemeSpecificPart?.substringBefore('?').orEmpty()
            val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (address.isNotBlank() && body.isNotBlank()) {
                try {
                    SmsStore.send(
                        applicationContext, address, body,
                        intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, -1),
                    )
                } catch (_: Exception) {
                }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
