package com.closedloop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.app.NotificationCompat

/** 通知层：L1 提醒通道（有声）+ 常驻催促通道（无声持续更新，PC 常驻小窗的等价物）。 */
object Notify {
    private const val CH_REMIND = "remind"
    private const val CH_NAG = "nag"
    private const val CH_DL = "dl"
    private const val NAG_ID = 1001
    private const val DL_ID = 1002

    private const val COLOR_OK = 0xFF22A06B.toInt()
    private const val COLOR_WARN = 0xFFE8A13A.toInt()
    private const val COLOR_DANGER = 0xFFD64545.toInt()

    /** 彩色大图（通知正文右侧 + 下拉/展开里的那个）。
     *  状态栏小图标只能走 alpha 剪影（系统限制），彩色要靠 largeIcon 呈现。 */
    @Volatile
    private var largeIconCache: Bitmap? = null

    private fun largeIcon(ctx: Context): Bitmap? {
        largeIconCache?.let { return it }
        return BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher)?.also {
            largeIconCache = it
        }
    }

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
        ).apply { description = "执行期的常驻状态与切走提醒" }
        val dl = NotificationChannel(
            CH_DL, "应用更新下载", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "更新包下载进度（切到别的应用也能看到）" }
        manager(ctx).createNotificationChannels(listOf(remind, nag, dl))
    }

    private fun contentIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 有声提醒：闹钟级别（CATEGORY_ALARM + 全屏 Intent），防国产 ROM 折叠。
     *
     *  v0.9 起给计划相关的提醒挂两个出口：「稍后 15 分钟」「今天不做了」。
     *  没有出口的追击，最后会被用户用"关掉通知权限"这个更彻底的方式解决掉。 */
    fun remind(
        ctx: Context,
        nid: Int,
        title: String,
        text: String,
        planKey: String? = null,
    ) {
        val b = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(largeIcon(ctx))
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

        if (planKey != null) {
            b.addAction(0, "稍后 15 分钟", action(ctx, planKey, ActionReceiver.ACTION_SNOOZE, 0))
            b.addAction(0, "今天不做了", action(ctx, planKey, ActionReceiver.ACTION_GIVE_UP, 1))
        }
        manager(ctx).notify(nid, b.build())
    }

    private fun action(ctx: Context, planKey: String, action: String, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            planKey.hashCode() * 31 + slot,
            Intent(ctx, ActionReceiver::class.java).apply {
                this.action = action
                putExtra(ActionReceiver.EXTRA_PLAN, planKey)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 收掉某个计划的全部有声提醒（"到点了" / 已错过 / 熔断问话 / 完成）。 */
    fun cancelRemind(ctx: Context, planKey: String) {
        val m = manager(ctx)
        m.cancel(planKey.hashCode())
        m.cancel(planKey.hashCode() + 3)
        m.cancel(planKey.hashCode() + 7)
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
            .setLargeIcon(largeIcon(ctx))
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

    /** 下载进度通知（v0.7）：切后台也看得见"还在下载"——修复"进度框没了、
     *  实际还在下、用户又点一遍"的问题。total<=0 时显示不确定进度。 */
    fun dlUpdate(ctx: Context, title: String, text: String, done: Long, total: Long) {
        val n = NotificationCompat.Builder(ctx, CH_DL)
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(largeIcon(ctx))
            .setContentTitle(title)
            .setContentText(text)
            .setColor(COLOR_OK)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0, total <= 0)
            .setContentIntent(contentIntent(ctx))
            .build()
        manager(ctx).notify(DL_ID, n)
    }

    fun dlCancel(ctx: Context) = manager(ctx).cancel(DL_ID)
}
