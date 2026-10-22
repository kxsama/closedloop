package com.closedloop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启 / 升级后自启（v0.9）。
 *
 * **为什么必须有**：manifest 里一直声明着 `RECEIVE_BOOT_COMPLETED`，却没有接收者——
 * 手机一重启，前台服务不会自己起来，应用界面照常能打开、计划照常显示，
 * 但后台什么都不监测、什么都不会提醒。用户以为它在跑，它其实在装死。
 * 对一个"要验收你有没有去做"的应用，这是最恶劣的一种失败：**沉默且看不出来**。
 *
 * 用 `MY_PACKAGE_REPLACED` 覆盖应用内更新后的重启：覆盖安装会杀掉进程，
 * 服务同样需要重新拉起，否则"更新完就不工作了"。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (!PlanStore.monitoringEnabled(ctx)) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT,
            -> {
                // 没有使用情况权限时起服务毫无意义，还会白占一个前台通知——不打扰。
                if (!MonitorService.hasUsageAccess(ctx)) return
                MonitorService.start(ctx)
            }
        }
    }

    private companion object {
        /** 部分国产 ROM 的开机广播（小米/OPPO 等），系统不给标准 BOOT_COMPLETED 时兜底 */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
