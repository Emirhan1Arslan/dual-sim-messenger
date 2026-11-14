package com.ciftsim.mesajlar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri

/** Gelen mesaj bildirimleri: başlık "Kişi · SIM adı", SIM renginde rozet, yanıtla / okundu. */
object Notifier {
    private const val CHANNEL_ID = "mesajlar"
    private const val FAILED_ID = -4100

    private fun idFor(threadId: Long, subId: Int): Int = (threadId * 31 + subId).toInt()

    private fun manager(ctx: Context): NotificationManager? {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return null
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mesajlar", NotificationManager.IMPORTANCE_HIGH)
        )
        return manager
    }

    fun show(ctx: Context, address: String, body: String, threadId: Long, subId: Int) {
        val manager = manager(ctx) ?: return
        val sim = Sims.find(ctx, subId)
        val who = SmsStore.contactName(ctx, address) ?: address
        val title = if (sim != null) "$who · ${sim.label}" else who
        val id = idFor(threadId, subId)
        // Her konuşmanın PendingIntent'leri birbirinden ayrılsın.
        val data = Uri.parse("ciftsim://konusma/$threadId/$subId")

        val open = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CONVERSATION)
            .setData(data)
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_SUB_ID, subId)
            .putExtra(MainActivity.EXTRA_ADDRESS, address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun action(name: String) = Intent(ctx, NotificationActionReceiver::class.java)
            .setAction(name)
            .setData(data)
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_SUB_ID, subId)
            .putExtra(MainActivity.EXTRA_ADDRESS, address)

        val icon = Icon.createWithResource(ctx, R.drawable.ic_stat_message)
        // Yanıt metni sistem tarafından intent'e eklendiği için MUTABLE olmalı.
        val reply = Notification.Action.Builder(
            icon, "Yanıtla",
            PendingIntent.getBroadcast(
                ctx, 0, action(NotificationActionReceiver.ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        ).addRemoteInput(
            RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("Yanıt yazın").build()
        ).build()
        val markRead = Notification.Action.Builder(
            icon, "Okundu",
            PendingIntent.getBroadcast(
                ctx, 0, action(NotificationActionReceiver.ACTION_MARK_READ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(reply)
            .addAction(markRead)
        if (sim != null) {
            builder.setColor(sim.color).setLargeIcon(simBadge(sim.slot + 1, sim.color))
        }
        manager.notify(id, builder.build())
    }

    fun showMmsFailed(ctx: Context, subId: Int) {
        val manager = manager(ctx) ?: return
        val sim = Sims.find(ctx, subId)
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(if (sim != null) "MMS indirilemedi · ${sim.label}" else "MMS indirilemedi")
            .setContentText("Bu SIM'in mobil verisi açık olmalı. Gönderenden yeniden yollamasını isteyin.")
            .setAutoCancel(true)
        manager.notify(FAILED_ID, builder.build())
    }

    fun cancel(ctx: Context, threadId: Long, subId: Int) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(idFor(threadId, subId))
    }

    /** SIM renginde daire içinde yuva numarası. */
    private fun simBadge(number: Int, color: Int): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.55f
        paint.typeface = Typeface.DEFAULT_BOLD
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(number.toString(), size / 2f, baseline, paint)
        return bitmap
    }
}
