package com.closedloop.app

import android.content.Context
import java.time.LocalDate

/**
 * 结算落账（v0.9 抽出来）。
 *
 * 三处都要写账本：前台服务 tick、通知上的"今天不做了"、Activity 里的提前结束/熔断。
 * 同一段字段映射写三遍，迟早有一处少写一个字段——那个统计就会永远显示 0，而且没人查得出来。
 *
 * **日期取会话自己的日期，不是"今天"**：跨天收尾时（`settleYesterday()`）要结算的是昨天那场，
 * 用 `LocalDate.now()` 会把昨天的账记到今天头上。
 */
object Ledger {

    fun record(ctx: Context, eng: Engine, a: Act.Settle, date: String? = null) {
        val d = date ?: eng.session.date.ifBlank { LocalDate.now().toString() }
        PlanStore.appendLedger(
            ctx, d, mapOf(
                "planId" to eng.plan.id,
                "name" to eng.plan.name,
                "result" to a.result,
                "effectiveMin" to a.effectiveMin,
                "deviationMin" to a.deviationMin,
                "deviationCount" to a.deviationCount,
                "unfinishedMin" to a.unfinishedMin,
                "reason" to a.reason,
                "target" to eng.plan.targetMinutes,
                "gaps" to eng.session.notObserved.size,
            )
        )
    }
}
