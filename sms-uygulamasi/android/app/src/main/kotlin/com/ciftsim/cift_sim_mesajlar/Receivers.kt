package com.ciftsim.cift_sim_mesajlar

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager

private const val EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX"
private const val EXTRA_SUBSCRIPTION_LEGACY = "subscription"

/** Intent'ten SIM (abonelik) kimliğini okur; bulunamazsa sistemin varsayılan SMS SIM'i. */
private fun subIdFrom(intent: Intent): Int {
    var subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, -1)
    if (subId < 0) subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_LEGACY, -1)
    if (subId < 0) subId = SmsManager.getDefaultSmsSubscriptionId()
    return subId
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
        val threadId = Telephony.Threads.getOrCreateThreadId(ctx, address)
        val open = AppState.isOpen(threadId, subId)
        SmsStore.insertInbox(ctx, threadId, address, body, first.timestampMillis, subId, read = open)
        if (!open) Notifier.show(ctx, address, body, threadId, subId)
    }
}

/** WAP_PUSH_DELIVER: MMS bu sürümde kapsam dışı; yalnızca varsayılan SMS rolü için var. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Gönderim sonucu: Outbox'taki mesajı SENT ya da FAILED yapar. */
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
        const val ACTION_SENT = "com.ciftsim.cift_sim_mesajlar.SMS_SENT"
        const val EXTRA_LAST_PART = "last_part"
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
