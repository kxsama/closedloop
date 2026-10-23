package com.closedloop.app

import java.time.LocalDate

/** Additional checks use the production engine and can run without Android. */
fun runEngineTimingRegression(): Int {
    val day = LocalDate.of(2026, 10, 22)
    fun engine() = Engine(
        Plan("timing", "阅读", "15:00", 60, mutableListOf("reader"), fuseRatio = 99.0), day,
    )
    val start = engine().plan.startEpoch(day)
    var passed = 0
    fun test(name: String, body: () -> Unit) {
        body()
        passed++
        println("PASS $name")
    }

    test("returning during grace counts every elapsed interval once") {
        val e = engine()
        e.tick(start, "reader", false)
        e.tick(start + 3, "reader", false)
        e.tick(start + 6, "other", false)
        e.tick(start + 9, "reader", false)
        check(e.session.effectiveSec == 9.0)
        check(e.session.deviationSec == 0.0 && e.session.deviationCount == 0)
    }
    test("repeated brief switches cannot create extra effective time") {
        val e = engine()
        e.tick(start, "reader", false)
        e.tick(start + 3, "reader", false)
        for (i in 1L..30L) {
            e.tick(start + i * 6, "other", false)
            e.tick(start + i * 6 + 3, "reader", false)
        }
        check(e.session.effectiveSec == 183.0)
        check(e.session.deviationCount == 0)
    }
    test("a backward clock adjustment never subtracts effective time") {
        val e = engine()
        e.tick(start, "reader", false)
        e.tick(start + 30, "reader", false)
        e.tick(start + 10, "reader", false)
        check(e.session.effectiveSec == 30.0)
        e.tick(start + 13, "reader", false)
        check(e.session.effectiveSec == 33.0)
    }
    test("a backward clock adjustment resets the deviation observation") {
        val e = engine()
        e.tick(start, "reader", false)
        e.tick(start + 3, "reader", false)
        e.tick(start + 6, "other", false)
        e.tick(start + 66, "other", false)
        check(e.session.devConfirmed && e.session.deviationSec == 63.0)
        val before = e.session.deviationSec
        e.tick(start + 30, "other", false)
        check(!e.session.devConfirmed && e.session.contDevSec == 0.0)
        check(e.session.deviationSec == before)
        e.tick(start + 33, "reader", false)
        check(e.session.deviationSec == before)
        check(e.session.effectiveSec >= 3.0)
    }
    test("session plan snapshot keeps the original schedule without shared mutation") {
        val e = engine()
        e.plan.slots = (1..7).associateWith { DaySlot(true, "15:00", 60) }.toMutableMap()
        val rebound = Engine(e.plan, day, e.session)
        val saved = rebound.session.planSnapshot!!
        rebound.plan.name = "更名"
        rebound.plan.packages.add("newReader")
        rebound.plan.slots!!.getValue(1).minutes = 20
        check(saved.name == "阅读" && saved.packages == listOf("reader"))
        check(saved.slots!!.getValue(1).minutes == 60)
        check(rebound.abandon("日期已过").filterIsInstance<Act.Settle>().single().reason == "日期已过")
    }
    return passed
}
