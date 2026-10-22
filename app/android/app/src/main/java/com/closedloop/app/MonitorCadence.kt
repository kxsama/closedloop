package com.closedloop.app

/** Only active, visible-screen monitoring needs a three-second cadence. No wake locks. */
object MonitorCadence {
    const val ACTIVE_MS = 3_000L
    const val IDLE_MS = 60_000L
    const val SCREEN_OFF_MS = 30_000L

    fun needsForeground(nowSec: Long, engines: Collection<Engine>): Boolean = engines.any {
        when (it.session.state) {
            SState.RUNNING, SState.COOLDOWN -> true
            SState.WAITING -> nowSec >= it.plan.startEpoch(it.today)
            SState.SETTLED -> false
        }
    }

    fun delayMs(nowMs: Long, interactive: Boolean, engines: Collection<Engine>, midnightMs: Long): Long {
        val active = needsForeground(nowMs / 1000, engines)
        val interval = when {
            !active -> IDLE_MS
            interactive -> ACTIVE_MS
            else -> SCREEN_OFF_MS
        }
        // Waiting can be slow, but must stop exactly at the next scheduled start/day boundary.
        val nextStart = engines.asSequence()
            .filter { it.session.state == SState.WAITING }
            .map { it.plan.startEpoch(it.today) * 1000 }
            .filter { it > nowMs }
            .minOrNull() ?: Long.MAX_VALUE
        return minOf(interval, nextStart - nowMs, midnightMs - nowMs).coerceAtLeast(1L)
    }
}
