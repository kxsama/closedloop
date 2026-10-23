package com.closedloop.app

import java.time.LocalDate

/** Exercises the production reconciliation helper without an Android runtime. */
fun runSessionLifecycleRegression(): Int {
    val day = LocalDate.of(2026, 10, 23)
    fun plan() = Plan("lifecycle", "阅读", "15:00", 60, mutableListOf("reader"))
    val start = plan().startEpoch(day)
    fun workingEngine(): Engine = Engine(plan(), day).also {
        it.tick(start, "reader", false)
        it.tick(start + 30, "reader", false)
    }
    var passed = 0
    fun test(name: String, body: () -> Unit) {
        body()
        passed++
        println("PASS $name")
    }

    test("live session wins over an older disk checkpoint") {
        val live = workingEngine()
        val saved = live.session.copy(effectiveSec = 3.0)
        val result = SessionLifecycle.reload(listOf(plan()), mapOf(plan().id to saved), mapOf(plan().id to live), day)
        check(result.engines.getValue(plan().id) === live)
        check(result.sessions.getValue(plan().id).effectiveSec == 30.0)
        check(live.session.lastObserved)
    }
    test("editing associated apps takes effect while preserving live work") {
        val live = workingEngine()
        val edited = plan().copy(name = "新名称", packages = mutableListOf("newReader"))
        val result = SessionLifecycle.reload(listOf(edited), emptyMap(), mapOf(edited.id to live), day)
        val updated = result.engines.getValue(edited.id)
        check(updated !== live && updated.plan.name == "新名称")
        check(updated.plan.matches("newReader") && !updated.plan.matches("reader"))
        check(updated.session.effectiveSec == 30.0 && !updated.session.lastObserved)
    }
    test("a cold restart under the gap threshold never credits process downtime") {
        val old = workingEngine()
        val result = SessionLifecycle.reload(listOf(plan()), mapOf(plan().id to old.session), emptyMap(), day)
        val restored = result.engines.getValue(plan().id)
        restored.tick(start + 70, "reader", false)
        check(restored.session.effectiveSec == 30.0)
        restored.tick(start + 73, "reader", false)
        check(restored.session.effectiveSec == 33.0)
    }
    test("cross-day recovery settles against the original plan snapshot") {
        val old = workingEngine()
        val edited = plan().copy(name = "更名", targetMinutes = 40)
        val result = SessionLifecycle.reload(listOf(edited), mapOf(edited.id to old.session), emptyMap(), day.plusDays(1))
        val (archived, act) = result.history.single()
        check(archived.plan.name == "阅读" && archived.plan.targetMinutes == 60)
        check(archived.session.date == day.toString())
        check(act.result == Engine.RESULT_UNFINISHED && act.effectiveMin == 0.5)
        check(act.unfinishedMin == 59.5 && act.reason == "跨日收尾，未监测期间不计时")
        check(result.engines.getValue(edited.id).plan.targetMinutes == 40)
    }
    test("pausing and re-enabling a plan retains today's effective work") {
        val old = workingEngine()
        val paused = SessionLifecycle.reload(listOf(plan().copy(enabled = false)), emptyMap(), mapOf(plan().id to old), day)
        check(paused.engines.isEmpty() && paused.history.isEmpty())
        check(paused.sessions.getValue(plan().id).effectiveSec == 30.0)
        check(!paused.sessions.getValue(plan().id).lastObserved)
        val resumed = SessionLifecycle.reload(listOf(plan()), paused.sessions, emptyMap(), day)
        check(resumed.engines.getValue(plan().id).session.effectiveSec == 30.0)
    }
    test("reloading after rollover cannot settle the new day's session again") {
        val old = workingEngine()
        val tomorrow = day.plusDays(1)
        val first = SessionLifecycle.reload(listOf(plan()), mapOf(plan().id to old.session), emptyMap(), tomorrow)
        check(first.history.size == 1)
        val second = SessionLifecycle.reload(listOf(plan()), first.sessions, first.engines, tomorrow)
        check(second.history.isEmpty())
        val current = second.engines.getValue(plan().id)
        check(current.session.date == tomorrow.toString() && current.session.state == SState.WAITING)
        check(current.session.effectiveSec == 0.0)
    }
    test("legacy sessions without snapshots use the plan's schedule for their own day") {
        val old = Session(plan().id, day.toString(), state = SState.RUNNING, effectiveSec = 60.0)
        check(old.planSnapshot == null)
        val current = plan().copy(targetMinutes = 50)
        val result = SessionLifecycle.reload(listOf(current), mapOf(current.id to old), emptyMap(), day.plusDays(1))
        val (archived, act) = result.history.single()
        check(archived.plan.targetMinutes == 50)
        check(act.effectiveMin == 1.0 && act.unfinishedMin == 49.0)
    }
    test("days without monitoring do not manufacture daily records") {
        val old = workingEngine()
        val result = SessionLifecycle.reload(listOf(plan()), mapOf(plan().id to old.session), emptyMap(), day.plusDays(4))
        check(result.history.size == 1 && result.history.single().first.session.date == day.toString())
        check(result.sessions.getValue(plan().id).date == day.plusDays(4).toString())
        val empty = SessionLifecycle.reload(listOf(plan()), emptyMap(), emptyMap(), day.plusDays(4))
        check(empty.history.isEmpty())
    }
    return passed
}
