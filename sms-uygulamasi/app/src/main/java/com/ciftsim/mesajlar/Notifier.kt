package com.ciftsim.mesajlar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri

/** Gelen mesaj bildirimleri: başlık "Kişi · SIM 2", SIM renginde rozet. */
object Notifier {
    private const val CHANNEL_ID = "mesajlar"

    private fun idFor(threadId: Long, subId: Int): Int = (threadId * 31 + subId).toInt()

    fun show(ctx: Context, address: String, body: String, threadId: Long, subId: Int) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mesajlar", NotificationManager.IMPORTANCE_HIGH)
        )

        val slot = Sims.slotOf(ctx, subId)
        val who = SmsStore.contactName(ctx, address) ?: address
        val title = if (slot >= 0) "$who · SIM ${slot + 1}" else who

        val open = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CONVERSATION)
            .setData(Uri.parse("ciftsim://konusma/$threadId/$subId"))
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .putExtra(MainActivity.EXTRA_SUB_ID, subId)
            .putExtra(MainActivity.EXTRA_ADDRESS, address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
        if (slot >= 0) {
            val color = Sims.colorForSlot(slot)
            builder.setColor(color).setLargeIcon(simBadge(slot + 1, color))
        }
        manager.notify(idFor(threadId, subId), builder.build())
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
