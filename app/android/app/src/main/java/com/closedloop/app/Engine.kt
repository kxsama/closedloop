package com.closedloop.app

import java.time.LocalDate
import java.time.ZoneId

/** 计划与核心状态机：与 PC 版 engine.py 完全同构（有效时长制/去抖/升级/熔断/未观测）。 */

/**
 * 某一天的排期槽位（v0.8）。
 * 设计原则：**以天为唯一真源**——内部永远存周一到周日 7 个槽位，
 * 界面上"勾选星期几"只是把这 7 个槽位批量赋值，不再单独维护一份"重复规则"，
 * 否则两套数据早晚打架（勾了周一但槽位是关的，到底算不算？）。
 */
data class DaySlot(
    var on: Boolean = true,
    var start: String = "20:00",     // "HH:MM"
    var minutes: Int = 30,
)

data class Plan(
    val id: String,
    var name: String,
    var start: String,                      // 基准开始时间（新建时的默认值）
    var targetMinutes: Int,                 // 基准时长（新建时的默认值）
    var packages: MutableList<String>,      // 关联应用包名
    var titleKeyword: String = "",
    var fuseRatio: Double = 1.0,
    var enabled: Boolean = true,
    /** 1=周一 … 7=周日（ISO）。null = 老数据，按"每天生效、用基准时间"处理。 */
    var slots: MutableMap<Int, DaySlot>? = null,
    /** 单双周：all=每周 / odd=单周 / even=双周（按 ISO 周数奇偶判定） */
    var weekMode: String = "all",
) {
    fun startEpoch(day: LocalDate): Long {
        val parts = start.split(":")
        return day.atTime(parts[0].toInt(), parts[1].toInt())
            .atZone(ZoneId.systemDefault()).toEpochSecond()
    }

    fun matches(pkg: String?): Boolean =
        pkg != null && packages.any { it.equals(pkg, ignoreCase = true) }

    /** 取某天的槽位；老数据/缺项一律回落到基准时间与时长，且默认开启。 */
    fun slotOf(day: LocalDate): DaySlot {
        val wd = day.dayOfWeek.value
        slots?.get(wd)?.let { return it }
        return DaySlot(true, start, targetMinutes)
    }

    /** 单双周是否命中。用 ISO 周数奇偶——稳定、可解释；界面上会显示"本周算单/双周"供核对。 */
    fun weekMatch(day: LocalDate): Boolean {
        if (weekMode == "all") return true
        val week = day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val isOdd = week % 2 == 1
        return if (weekMode == "odd") isOdd else !isOdd
    }

    /** 今天要不要做这件事（停用 / 当天关 / 单双周不符 → 不做）。 */
    fun appliesOn(day: LocalDate): Boolean = enabled && slotOf(day).on && weekMatch(day)

    /** 把某天排期"拍平"成当天生效的计划——Engine 只认 start/targetMinutes，其余字段原样带过去。 */
    fun forDay(day: LocalDate): Plan {
        val s = slotOf(day)
        return copy(start = s.start, targetMinutes = s.minutes)
    }
}

/** ISO 周数是单还是双，用于界面提示"本周：单周/双周"。 */
fun weekIsOdd(day: LocalDate): Boolean =
    day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 1

enum class SState { WAITING, RUNNING, COOLDOWN, SETTLED }

data class Session(
    val planId: String,
    val date: String,
    var state: SState = SState.WAITING,
    var effectiveSec: Double = 0.0,
    var deviationSec: Double = 0.0,
    var deviationCount: Int = 0,
    var curDevStart: Long? = null,
    var devConfirmed: Boolean = false,
    var contDevSec: Double = 0.0,
    var lastTick: Long? = null,
    var lastObserved: Boolean = false, // Unknown/screen-off time is never credited on return.
    var planSnapshot: Plan? = null, // Preserve the actual day's name and target for later settlement.
    var recheckAt: Long = 0L,
    var level: Int = 0,
    var fused: Boolean = false,
    var reminded: Boolean = false,   // 到点提醒只发一次；重启恢复后不再重复"到点了"，改由"已错过"接管
    var result: String? = null,
    var reason: String? = null,
    val notObserved: MutableList<Pair<Long, Long>> = mutableListOf(),
)

sealed class Act {
    object Remind : Act()
    data class Escalate(val level: Int) : Act()
    object DeviationStart : Act()
    object DeviationEnd : Act()
    object CooldownEnter : Act()
    object CooldownExit : Act()
    object FuseAsk : Act()
    data class Gap(val from: Long, val to: Long) : Act()
    data class Settle(
        val result: String,
        val effectiveMin: Double,
        val deviationMin: Double,
        val deviationCount: Int,
        val unfinishedMin: Double,
        val reason: String? = null,
    ) : Act()
}

class Engine(
    val plan: Plan,
    val today: LocalDate = LocalDate.now(),
    session: Session? = null,
) {
    val session = session ?: Session(plan.id, today.toString())

    init {
        this.session.planSnapshot = plan.copy(
            packages = plan.packages.toMutableList(),
            slots = plan.slots?.mapValues { it.value.copy() }?.toMutableMap(),
        )
    }

    companion object {
        const val GRACE_SEC = 60L           // 去抖宽限
        const val FIRST_RECHECK_SEC = 300L  // 首次回查
        const val L3_AFTER_SEC = 300L       // 连续偏离升级 L3
        const val COOLDOWN_AFTER_SEC = 600L // 连续偏离进入沉默
        const val GAP_SEC = 120L            // 监测空洞
        const val MISS_SETTLE_SEC = 3 * 3600L   // 开始后 3 小时仍未开始 → 自动记未完成（v0.8 错过收敛）
        const val RESULT_DONE = "完成"
        const val RESULT_UNFINISHED = "未完成"
        const val RESULT_EARLY_END = "提前结束"
    }

    private fun settle(result: String, reason: String? = null): List<Act> {
        val s = session
        s.state = SState.SETTLED
        s.result = result
        s.reason = reason
        return listOf(
            Act.Settle(
                result = result,
                effectiveMin = s.effectiveSec / 60.0,
                deviationMin = s.deviationSec / 60.0,
                deviationCount = s.deviationCount,
                unfinishedMin = maxOf(0.0, plan.targetMinutes * 60 - s.effectiveSec) / 60.0,
                reason = reason,
            )
        )
    }

    fun status(now: Long, curPkg: String? = null): Map<String, Any> {
        val s = session
        val target = plan.targetMinutes * 60.0
        return mapOf(
            "state" to s.state.name,
            "level" to s.level,
            "effectiveSec" to s.effectiveSec,
            "targetSec" to target,
            "deviationSec" to s.deviationSec,
            "deviationCount" to s.deviationCount,
            "deviating" to s.devConfirmed,
            "curPkg" to (curPkg ?: ""),
            "result" to (s.result ?: ""),
        )
    }

    /** fg = 前台包名；locked = 锁屏/未知（未观测，不计账也不算偏离）。 */
    fun tick(now: Long, fg: String?, locked: Boolean): List<Act> {
        val s = session
        val acts = mutableListOf<Act>()
        if (s.state == SState.SETTLED) return acts

        // Expire before WAITING can issue its first reminder. Opening the app late
        // must not replay an afternoon alarm hundreds of minutes after its time.
        if (s.effectiveSec < 5 && now - plan.startEpoch(today) >= MISS_SETTLE_SEC) {
            return settle(RESULT_UNFINISHED, "未开始")
        }

        val last = s.lastTick
        if (last != null && (now < last || now - last > GAP_SEC)) {
            // A wall-clock correction must never subtract credited time or leave a
            // deviation anchored in the future. Resume observation from this tick.
            if (now > last) {
                s.notObserved.add(last to now)
                acts.add(Act.Gap(last, now))
            }
            s.curDevStart = null
            s.devConfirmed = false
            s.contDevSec = 0.0
            s.lastObserved = false
        }

        val delta = if (last != null && s.lastObserved) (now - last).toDouble() else 0.0
        s.lastTick = now

        if (s.state == SState.WAITING) {
            s.lastObserved = !locked && fg != null
            if (now >= plan.startEpoch(today)) {
                s.state = SState.RUNNING
                s.level = 1
                s.recheckAt = now + FIRST_RECHECK_SEC
                if (!s.reminded) {
                    s.reminded = true
                    acts.add(Act.Remind)
                }
            }
            return acts
        }

        // Scheduling/timeout decisions above don't depend on seeing a foreground app.
        // A screen-off interval must neither add effective time nor prolong a deviation.
        if (locked || fg == null) {
            s.lastObserved = false
            s.curDevStart = null
            s.devConfirmed = false
            s.contDevSec = 0.0
            return acts
        }
        s.lastObserved = true
        val compliant = plan.matches(fg)

        if (compliant) {
            if (s.state == SState.COOLDOWN) {
                s.state = SState.RUNNING
                acts.add(Act.CooldownExit)
            }
            val devStart = s.curDevStart
            if (devStart != null) {
                if (s.devConfirmed) {
                    s.curDevStart = null
                    s.devConfirmed = false
                    s.contDevSec = 0.0
                    acts.add(Act.DeviationEnd)
                } else {
                    // 宽限内的毛刺：整段补记为有效——但前提是"已经开始过"（本段偏离之前有有效时长）。
                    // 若 effectiveSec == 0，这是"到点没开始"而不是中途毛刺，不能补记；
                    // 否则"晚到 1 分钟"会被宽限规则错误地整段记为有效（v0.4 实测抓出）。
                    if (s.effectiveSec > 0) {
                        // delta is added below for the current interval. Only fill
                        // earlier grace intervals here, or every return counts twice.
                        s.effectiveSec += maxOf(0.0, (now - devStart).toDouble() - delta)
                    }
                    s.curDevStart = null
                }
            }
            s.effectiveSec += delta
        } else {
            if (s.curDevStart == null) {
                s.curDevStart = if (delta > 0) now - delta.toLong() else now
            }
            val seg = now - (s.curDevStart ?: now)
            if (!s.devConfirmed && seg >= GRACE_SEC) {
                s.devConfirmed = true
                s.deviationCount += 1
                s.deviationSec += seg
                s.contDevSec = seg.toDouble()
                acts.add(Act.DeviationStart)
            } else if (s.devConfirmed) {
                s.deviationSec += delta
                s.contDevSec += delta
            }
        }

        // 升级阶梯（v0.10，与 PC engine.py 同构）。
        // L2 看"偏离已确认"（连续偏离满 60 秒宽限），L3 看"连续偏离 ≥5 分钟"。
        // 以前整段挂在"首次回查（到点 +5 分钟）"后面：到点就没进来的场景里，
        // 回查那一刻连续偏离早已 ≥5 分钟，一次就判 3 —— L2「担心」永远看不到。
        if (s.state == SState.RUNNING) {
            if (s.devConfirmed) {
                val newLevel = if (s.contDevSec >= L3_AFTER_SEC) 3 else 2
                if (newLevel != s.level) {
                    s.level = newLevel
                    acts.add(Act.Escalate(newLevel))
                }
                if (s.contDevSec >= COOLDOWN_AFTER_SEC) {
                    s.state = SState.COOLDOWN
                    acts.add(Act.CooldownEnter)
                }
            } else if (s.level > 1) {
                s.level = 1
                acts.add(Act.Escalate(1))
            }
        }

        // 熔断：累计偏离超过阈值，问一次
        if (!s.fused && s.deviationSec >= plan.fuseRatio * plan.targetMinutes * 60) {
            s.fused = true
            acts.add(Act.FuseAsk)
        }

        if (s.effectiveSec >= plan.targetMinutes * 60) {
            acts.addAll(settle(RESULT_DONE))
        }

        return acts
    }

    fun earlyEnd(reason: String): List<Act> = settle(RESULT_EARLY_END, reason)

    fun abandon(reason: String? = null): List<Act> = settle(RESULT_UNFINISHED, reason)

    /**
     * 用户主动放弃这一场（v0.9：通知上的"今天不做了"）。
     *
     * 和 `abandon()` 的区别是**如实**：没开始过的记"未完成"，已经做过的记"提前结束"，
     * 并且都带上原因。账本要能解释自己——只写一个"未完成"、原因为空，
     * 回头看只会以为自己又没做，而不是"那天我自己喊停了"。
     */
    fun giveUp(reason: String): List<Act> =
        if (session.effectiveSec < 5) settle(RESULT_UNFINISHED, reason)
        else settle(RESULT_EARLY_END, reason)

    fun fuseContinue() {
        session.deviationSec = 0.0
        session.contDevSec = 0.0
        session.fused = false
    }
}
