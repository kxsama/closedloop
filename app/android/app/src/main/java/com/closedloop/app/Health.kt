package com.closedloop.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 运行体检（v0.9）。
 *
 * **它解决的是这个应用最贵的死法**：核心机制依赖 5 项系统设置，其中 4 项不在本应用内。
 * 以前只在 `onResume` 里检查「使用情况访问」一项，还每次都弹一个对话框——漏掉别的那几项时，
 * 应用照常启动、界面照常好看、后台什么都不发生。用户不会来报 bug，他会直接判定"这玩意儿没用"然后卸载。
 *
 * 所以把判断集中在这一个对象里：首页的提示条和体检页读的是**同一份结果**，不会各说各话。
 */
object Health {

    const val USAGE = "usage"
    const val NOTIFY = "notify"
    const val BATTERY = "battery"
    const val FULLSCREEN = "fullscreen"
    const val INSTALL = "install"
    const val AUTOSTART = "autostart"
    const val MONITOR = "monitor"

    /** @param core 缺了它核心机制就不成立（标红）；false 表示"缺了会变差，但还能用"。 */
    data class Item(
        val key: String,
        val title: String,
        val why: String,
        val ok: Boolean,
        val core: Boolean,
        val manual: Boolean = false,
    )

    fun items(ctx: Context): List<Item> = listOf(
        Item(
            MONITOR, "后台监测",
            if (PlanStore.monitoringEnabled(ctx)) "监测已开启后，不必每天点开应用。等待计划时减少检查。"
            else "你已手动停止监测和自动启动。点「恢复监测」后才会重新运行。",
            MonitorService.running, core = true,
        ),
        Item(
            USAGE, "使用情况访问",
            "知道此刻在哪个应用里——整个验收机制的地基。数据只留在本机。",
            MonitorService.hasUsageAccess(ctx), core = true,
        ),
        Item(
            NOTIFY, "通知权限",
            "提醒和常驻进度都靠它。关掉之后你就只能靠自觉了。",
            notificationsAllowed(ctx), core = true,
        ),
        Item(
            BATTERY, "电池优化白名单",
            "允许监测留在后台，减少系统省电策略打断提醒的机会。它与「自启动」是两项独立设置。",
            ignoringBattery(ctx), core = true,
        ),
        Item(
            AUTOSTART, "自启动与后台运行",
            "先在系统中允许自启动和后台运行，再勾选下方确认。此开关无法自动读取，" +
                "勾选代表你已手动检查，实际生效以重启后的提醒为准。入口不可用时，可在系统设置搜索「自启动」。",
            PlanStore.autostartConfirmed(ctx), core = false, manual = true,
        ),
        Item(
            FULLSCREEN, "全屏通知",
            "到点提醒要能盖在锁屏上弹出来，否则它只是一个可以被忽略的小条。",
            fullScreenAllowed(ctx), core = false,
        ),
        Item(
            INSTALL, "安装未知应用",
            "只用于应用内更新（点「更新」时下载并覆盖安装）。不打开也不影响日常使用。",
            Updater.canInstall(ctx), core = false,
        ),
    )

    /** 需要用户处理的核心项（提示条只算核心项，避免为了更新功能去逼人开权限）。 */
    fun pending(ctx: Context): List<Item> = items(ctx).filter { it.core && !it.ok }

    fun allOk(ctx: Context): Boolean = items(ctx).none { !it.manual && !it.ok }

    private fun notificationsAllowed(ctx: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    private fun ignoringBattery(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) {
        true   // 判断不了就别催用户，免得无休止提示
    }

    private fun fullScreenAllowed(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .canUseFullScreenIntent()
        } else {
            true   // 34 之前系统不单独管控这一项
        }
    } catch (_: Exception) {
        true
    }

    /**
     * 跳到能修这一项的地方。
     * 通知权限是运行时权限，只能由 Activity 发请求；其余都是系统设置页。
     * 每一项都带 try/catch 兜底——总有 ROM 把某个设置页阉掉，不能因此崩掉。
     */
    fun open(ctx: Context, key: String) {
        val intent: Intent = when (key) {
            MONITOR -> {
                MonitorService.resume(ctx)
                return
            }
            AUTOSTART -> Intent("miui.intent.action.OP_AUTO_START")
                .addCategory(Intent.CATEGORY_DEFAULT)
            USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

            NOTIFY -> {
                if (Build.VERSION.SDK_INT >= 33 && ctx is Activity) {
                    ActivityCompat.requestPermissions(
                        ctx, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
                    )
                    return
                }
                appDetail(ctx, Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            }

            BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))

            FULLSCREEN -> appDetail(ctx, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)

            INSTALL -> Updater.installPermissionIntent(ctx)

            else -> null
        } ?: return
        try {
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (_: Exception) {
            // 某些 ROM 没有这个设置页：退回应用详情页，至少让人找得到
            try {
                val i = appDetail(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Exception) {
            }
        }
    }

    private fun appDetail(ctx: Context, action: String): Intent =
        Intent(action).setData(Uri.parse("package:${ctx.packageName}"))
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
}
