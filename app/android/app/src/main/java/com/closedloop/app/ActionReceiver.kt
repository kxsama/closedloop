package com.closedloop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 提醒上的两个出口（v0.9）。
 *
 * **为什么这是必须的**：以前提醒只有一个动作——点开应用。追得烦的时候，唯一能做的就是滑掉，
 * 两分钟后又来；坚持不下去的人最后只能去关通知权限，于是核心机制彻底死掉。
 * 一个"追到你去做"的机制，如果没给"我现在不做，别烦我"的合法出口，它一定会被粗暴地关掉。
 *
 * 两个动作都给，而且都不说谎：
 * - **稍后 15 分钟**：只是让你安静，计时照走（你要真去做了照样记有效），到点再来。
 * - **今天不做了**：如实落账（没开始过记"未完成"，开始过记"提前结束"），今天不再打扰你。
 *   这不是失败按钮，是"我今天认了这一笔"——账本照记，人不必被追着跑。
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val pid = intent.getStringExtra(EXTRA_PLAN) ?: return
        val date = intent.getStringExtra(EXTRA_DATE)
        if (date != null && date != java.time.LocalDate.now().toString()) return
        val now = System.currentTimeMillis() / 1000

        when (intent.action) {
            ACTION_SNOOZE -> {
                // 静音不依赖引擎：服务被杀掉时也应该能安静下来
                PlanStore.setSnooze(ctx, pid, now + SNOOZE_SEC)
                Notify.cancelRemind(ctx, pid)
            }

            ACTION_GIVE_UP -> {
                MonitorService.finishPlan(ctx, pid, REASON_GIVE_UP, date)
            }
        }
        // An action may arrive during a slow idle tick; apply its visible state promptly.
        MonitorService.start(ctx)
    }

    companion object {
        const val ACTION_SNOOZE = "com.closedloop.app.SNOOZE"
        const val ACTION_GIVE_UP = "com.closedloop.app.GIVE_UP"
        const val EXTRA_PLAN = "planId"
        const val EXTRA_DATE = "sessionDate"

        /** 静音 15 分钟：短到不至于把今天的计划整条弄没，长到够处理完手里的事 */
        const val SNOOZE_SEC = 15 * 60L

        const val REASON_GIVE_UP = "今天不做了"
    }
}
