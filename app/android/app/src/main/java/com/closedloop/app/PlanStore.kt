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
    private val gson = Gson()

    private fun pref(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 会话持久化：App 重启后恢复进行中/已结算的状态，不再"重置为计时中"。 */
    fun saveSessions(ctx: Context, sessions: Map<String, Session>) {
        pref(ctx).edit().putString(KEY_SESSIONS, gson.toJson(sessions)).apply()
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
        return try {
            val type = object : TypeToken<MutableList<Plan>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
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

    fun deletePlan(ctx: Context, planId: String) {
        savePlans(ctx, loadPlans(ctx).filter { it.id != planId })
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
