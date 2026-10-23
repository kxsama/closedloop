package com.closedloop.app

import java.time.LocalDate

/** Reconcile saved/live sessions without inventing observations during a stopped process. */
object SessionLifecycle {
    data class Reload(
        val engines: Map<String, Engine>,
        val sessions: Map<String, Session>,
        val history: List<Pair<Engine, Act.Settle>>,
    )

    fun freeze(s: Session) {
        s.lastObserved = false
        s.curDevStart = null
        s.devConfirmed = false
        s.contDevSec = 0.0
    }

    fun reload(
        plans: List<Plan>, saved: Map<String, Session>, live: Map<String, Engine>, today: LocalDate,
    ): Reload {
        // The service may have counted more work since its last disk checkpoint.
        val known = saved.toMutableMap()
        live.forEach { (id, eng) -> known[id] = eng.session }
        val byId = plans.associateBy { it.id }
        val history = mutableListOf<Pair<Engine, Act.Settle>>()
        val retained = mutableMapOf<String, Session>()
        for ((id, session) in known) {
            if (session.planId != id) continue
            val date = runCatching { LocalDate.parse(session.date) }.getOrNull() ?: continue
            if (date == today) {
                retained[id] = session
                continue
            }
            if (date > today) continue
            // New snapshots keep the actual target/name even if the recurring plan was edited.
            // Older versions have no snapshot: their current plan is the only available fallback.
            val plan = session.planSnapshot ?: byId[id]?.forDay(date) ?: continue
            val eng = Engine(plan, date, session)
            freeze(session)
            val act = if (session.state != SState.SETTLED) {
                eng.abandon("跨日收尾，未监测期间不计时").filterIsInstance<Act.Settle>().single()
            } else {
                Act.Settle(
                    session.result ?: Engine.RESULT_UNFINISHED, session.effectiveSec / 60.0,
                    session.deviationSec / 60.0, session.deviationCount,
                    maxOf(0.0, plan.targetMinutes * 60 - session.effectiveSec) / 60.0, session.reason,
                )
            }
            history += eng to act
        }

        val active = mutableMapOf<String, Engine>()
        for (p in plans) {
            if (!p.appliesOn(today)) continue
            val eff = p.forDay(today)
            val previous = live[p.id]?.takeIf { it.session.date == today.toString() }
            val session = retained[p.id]
            val eng = if (previous != null && previous.plan == eff) previous else {
                // A cold restart or changed association cannot prove continuity of foreground use.
                session?.let(::freeze)
                Engine(eff, today, session)
            }
            active[p.id] = eng
            retained[p.id] = eng.session
        }
        // Pausing a plan freezes it, while retaining today's work for an explicit re-enable.
        retained.filterKeys { it !in active }.values.forEach(::freeze)
        return Reload(active, retained, history)
    }
}
