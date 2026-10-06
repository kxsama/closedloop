package com.closedloop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat

/** 通知层：L1 提醒通道（有声）+ 常驻催促通道（无声持续更新，PC 常驻小窗的等价物）。 */
object Notify {
    private const val CH_REMIND = "remind"
    private const val CH_NAG = "nag"
    private const val NAG_ID = 1001

    private const val COLOR_OK = 0xFF22A06B.toInt()
    private const val COLOR_WARN = 0xFFE8A13A.toInt()
    private const val COLOR_DANGER = 0xFFD64545.toInt()

    private fun manager(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels(ctx: Context) {
        val remind = NotificationChannel(
            CH_REMIND, "到点提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "计划开始时的人声提醒"
            // 显式声音+震动：防国产 ROM 把通道静默归类为"不重要通知"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                android.media.RingtoneManager.getDefaultUri(
                    android.media.RingtoneManager.TYPE_NOTIFICATION
                ),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .build(),
            )
        }
        val nag = NotificationChannel(
            CH_NAG, "计划进行中", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "执行期的常驻状态与偏离催促" }
        manager(ctx).createNotificationChannels(listOf(remind, nag))
    }

    private fun contentIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 有声提醒：闹钟级别（CATEGORY_ALARM + 全屏 Intent），防国产 ROM 折叠。 */
    fun remind(ctx: Context, planId: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(COLOR_OK)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setFullScreenIntent(contentIntent(ctx), true)
            .setContentIntent(contentIntent(ctx))
            .build()
        manager(ctx).notify(planId, n)
    }

    /** 常驻催促通知：进度条 + 状态文案，随引擎每跳更新，只更新不响。 */
    fun nagUpdate(
        ctx: Context,
        title: String,
        text: String,
        level: Int,
        effectiveSec: Double,
        targetSec: Double,
    ) {
        val color = when {
            level >= 3 -> COLOR_DANGER
            level >= 2 -> COLOR_WARN
            else -> COLOR_OK
        }
        val progress = if (targetSec > 0) (effectiveSec * 100 / targetSec).toInt() else 0
        val n = NotificationCompat.Builder(ctx, CH_NAG)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(color)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setContentIntent(contentIntent(ctx))
            .build()
        manager(ctx).notify(NAG_ID, n)
    }

    fun nagCancel(ctx: Context) = manager(ctx).cancel(NAG_ID)
}
