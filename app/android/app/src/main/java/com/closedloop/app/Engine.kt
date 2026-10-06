package com.closedloop.app

import java.time.LocalDate
import java.time.ZoneId

/** 计划与核心状态机：与 PC 版 engine.py 完全同构（有效时长制/去抖/升级/熔断/未观测）。 */

data class Plan(
    val id: String,
    var name: String,
    var start: String,                      // "HH:MM"
    var targetMinutes: Int,
    var packages: MutableList<String>,      // 关联应用包名
    var titleKeyword: String = "",
    var fuseRatio: Double = 1.0,
    var enabled: Boolean = true,
) {
    fun startEpoch(day: LocalDate): Long {
        val parts = start.split(":")
        return day.atTime(parts[0].toInt(), parts[1].toInt())
            .atZone(ZoneId.systemDefault()).toEpochSecond()
    }

    fun matches(pkg: String?): Boolean =
        pkg != null && packages.any { it.equals(pkg, ignoreCase = true) }
}

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

    companion object {
        const val GRACE_SEC = 60L           // 去抖宽限
        const val FIRST_RECHECK_SEC = 300L  // 首次回查
        const val L3_AFTER_SEC = 300L       // 连续偏离升级 L3
        const val COOLDOWN_AFTER_SEC = 600L // 连续偏离进入沉默
        const val GAP_SEC = 120L            // 监测空洞
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

        val last = s.lastTick
        if (last != null && now - last > GAP_SEC) {
            s.notObserved.add(last to now)
            s.curDevStart = null
            s.devConfirmed = false
            s.contDevSec = 0.0
            acts.add(Act.Gap(last, now))
        }

        if (locked || fg == null) {
            s.lastTick = now
            return acts
        }

        val delta = if (last != null) (now - last).toDouble() else 0.0
        s.lastTick = now

        if (s.state == SState.WAITING) {
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

        val compliant = plan.matches(fg)

        if (compliant) {
            val devStart = s.curDevStart
            if (devStart != null) {
                if (s.devConfirmed) {
                    s.curDevStart = null
                    s.devConfirmed = false
                    s.contDevSec = 0.0
                    if (s.state == SState.COOLDOWN) {
                        s.state = SState.RUNNING
                        acts.add(Act.CooldownExit)
                    }
                    acts.add(Act.DeviationEnd)
                } else {
                    // 宽限内的毛刺：整段补记为有效
                    s.effectiveSec += now - devStart
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

        // 升级逻辑：只在 RUNNING 且过了首次回查后启用
        if (s.state == SState.RUNNING && now >= s.recheckAt) {
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

    fun abandon(): List<Act> = settle(RESULT_UNFINISHED)

    fun fuseContinue() {
        session.deviationSec = 0.0
        session.contDevSec = 0.0
        session.fused = false
    }
}
