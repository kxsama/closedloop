package com.closedloop.app

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 计划与账本的本地持久化（SharedPreferences + JSON）。无服务器、无账号。 */
object PlanStore {
    private const val PREF = "closedloop"
    private const val KEY_PLANS = "plans"
    private const val KEY_LEDGER_PREFIX = "ledger_"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_SNOOZE_PREFIX = "snooze_"
    private const val KEY_MONITORING = "monitoring_enabled"
    private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"
    private val gson = Gson()

    private fun pref(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 会话持久化：App 重启后恢复进行中/已结算的状态，不再"重置为计时中"。 */
    fun saveSessions(ctx: Context, sessions: Map<String, Session>, synchronous: Boolean = false) {
        val sp = pref(ctx)
        val json = gson.toJson(sessions)
        if (sp.getString(KEY_SESSIONS, null) != json) {
            val edit = sp.edit().putString(KEY_SESSIONS, json)
            if (synchronous) edit.commit() else edit.apply()
        }
    }

    fun monitoringEnabled(ctx: Context): Boolean = pref(ctx).getBoolean(KEY_MONITORING, true)

    /** Synchronous because explicit exit may immediately end the process. */
    fun setMonitoringEnabled(ctx: Context, enabled: Boolean) {
        check(pref(ctx).edit().putBoolean(KEY_MONITORING, enabled).commit())
    }

    fun autostartConfirmed(ctx: Context): Boolean = pref(ctx).getBoolean(KEY_AUTOSTART_CONFIRMED, false)

    fun confirmAutostart(ctx: Context, confirmed: Boolean) {
        pref(ctx).edit().putBoolean(KEY_AUTOSTART_CONFIRMED, confirmed).apply()
    }

    fun loadSessions(ctx: Context): Map<String, Session> {
        val json = pref(ctx).getString(KEY_SESSIONS, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Session>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun loadPlans(ctx: Context): MutableList<Plan> {
        val json = pref(ctx).getString(KEY_PLANS, null) ?: return mutableListOf()
        val list: MutableList<Plan> = try {
            val type = object : TypeToken<MutableList<Plan>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
        list.forEach { normalize(it) }
        return list
    }

    /**
     * 老数据归一化（v0.8）。
     *
     * **为什么必须做**：Gson 反序列化走 Unsafe 实例化，**不执行 Kotlin 构造器**，
     * 所以 `slots: MutableMap<Int, DaySlot>? = null` 这类"默认值"对已存在的 JSON 不生效——
     * 字段会是 null / 0 / 空。凡是新增字段，都在这里补一次兜底，别指望默认值。
     */
    private fun normalize(p: Plan) {
        if (p.start.isBlank() || !p.start.contains(":")) p.start = "20:00"
        if (p.targetMinutes !in 1..999) p.targetMinutes = 30
        if (p.weekMode !in setOf("all", "odd", "even")) p.weekMode = "all"

        val slots = p.slots
        if (slots == null) {
            // v0.7 及以前的计划：没有按天概念，语义就是"每天"→ 7 天全开，用基准时间
            p.slots = (1..7).associateWith { DaySlot(true, p.start, p.targetMinutes) }.toMutableMap()
        } else {
            // 缺天/脏值补齐：一天都不能缺，否则那天会走 slotOf 的回落分支，行为不可预期
            for (d in 1..7) {
                val s = slots[d]
                if (s == null) {
                    slots[d] = DaySlot(true, p.start, p.targetMinutes)
                } else {
                    if (s.start.isBlank() || !s.start.contains(":")) s.start = p.start
                    if (s.minutes !in 1..999) s.minutes = p.targetMinutes
                }
            }
        }
    }

    fun savePlans(ctx: Context, plans: List<Plan>) {
        pref(ctx).edit().putString(KEY_PLANS, gson.toJson(plans)).apply()
    }

    fun addPlan(ctx: Context, plan: Plan) {
        val plans = loadPlans(ctx)
        plans.add(plan)
        savePlans(ctx, plans)
    }

    /** 原地替换同 id 的计划（v0.8 编辑功能）。找不到就追加。 */
    fun updatePlan(ctx: Context, plan: Plan) {
        val plans = loadPlans(ctx)
        val i = plans.indexOfFirst { it.id == plan.id }
        if (i >= 0) plans[i] = plan else plans.add(plan)
        savePlans(ctx, plans)
    }

    fun deletePlan(ctx: Context, planId: String) {
        savePlans(ctx, loadPlans(ctx).filter { it.id != planId })
        clearSnooze(ctx, planId)
    }

    // ===== 静音（v0.9）=====
    // 单独存，不放进 Plan：这是"此刻的状态"，不是计划配置。
    // 混进配置里会在编辑计划时被顺带写回去，还会跟着计划一起导出/迁移，语义就脏了。

    /** 静音到这个时刻（秒）。0 表示没有静音。 */
    fun snoozeUntil(ctx: Context, planId: String): Long =
        pref(ctx).getLong(KEY_SNOOZE_PREFIX + planId, 0L)

    fun setSnooze(ctx: Context, planId: String, untilSec: Long) {
        pref(ctx).edit().putLong(KEY_SNOOZE_PREFIX + planId, untilSec).apply()
    }

    fun clearSnooze(ctx: Context, planId: String) {
        pref(ctx).edit().remove(KEY_SNOOZE_PREFIX + planId).apply()
    }

    /** 账本：按日期存结算记录列表。 */
    fun appendLedger(ctx: Context, date: String, record: Map<String, Any?>) {
        val key = KEY_LEDGER_PREFIX + date
        val json = pref(ctx).getString(key, null)
        val type = object : TypeToken<MutableList<Map<String, Any?>>>() {}.type
        val list: MutableList<Map<String, Any?>> = try {
            if (json == null) mutableListOf() else gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
        list.add(record)
        pref(ctx).edit().putString(key, gson.toJson(list)).apply()
    }

    fun loadLedger(ctx: Context, date: String): List<Map<String, Any?>> {
        val json = pref(ctx).getString(KEY_LEDGER_PREFIX + date, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
