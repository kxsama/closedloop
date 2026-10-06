package com.closedloop.app

import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** 前台服务：每秒轮询前台应用，驱动所有计划引擎，输出提醒与常驻催促。 */
class MonitorService : Service() {

    companion object {
        val engines = ConcurrentHashMap<String, Engine>()

        /** 熔断待确认（由 Activity 弹窗处理） */
        @Volatile
        var pendingFusePlanId: String? = null

        /** Activity 注册的刷新回调 */
        @Volatile
        var onTick: (() -> Unit)? = null

        fun hasUsageAccess(ctx: Context): Boolean {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.packageName,
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, MonitorService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val labelCache = ConcurrentHashMap<String, String>()

    /** 每计划上一次「有声提醒」时刻（秒），用于重复提醒节流 */
    private val lastNagAt = ConcurrentHashMap<String, Long>()

    /** 息屏开始时刻（秒）；null = 亮屏 */
    private var screenOffSince: Long? = null
    private var lastScreenNagAt = 0L

    /** 最近一次任何应用交互事件的时间（秒）——亮屏挂机启发式 */
    private var lastUserActivityAt = System.currentTimeMillis() / 1000
    private var lastIdleNagAt = 0L

    private var tickCount = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notify.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notify.nagUpdate(this, "闭环监测中", "等待计划开始…", 1, 0.0, 1.0)
        ServiceCompat.startForeground(
            this, 1, buildSelfNotification(),
            if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0,
        )
        reloadEngines()
        scope.launch { loop() }
        return START_STICKY
    }

    private fun buildSelfNotification(): android.app.Notification {
        return androidx.core.app.NotificationCompat.Builder(this, "nag")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("闭环监测中")
            .setContentText("等待计划开始…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun reloadEngines() {
        val plans = PlanStore.loadPlans(this)
        val today = LocalDate.now().toString()
        val saved = PlanStore.loadSessions(this)
        val keep = mutableSetOf<String>()
        for (p in plans) {
            if (!p.enabled) continue
            keep.add(p.id)
            val existing = engines[p.id]
            if (existing == null || existing.session.date != today) {
                val sess = saved[p.id]?.takeIf { it.date == today && it.planId == p.id }
                engines[p.id] = if (sess != null) Engine(p, session = sess) else Engine(p)
            }
        }
        engines.keys.retainAll(keep)
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (_: Exception) {
            }
            delay(1000)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis() / 1000
        val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val fg = if (interactive) foregroundPackage() else null

        // 息屏跟踪
        if (!interactive && screenOffSince == null) screenOffSince = now
        if (interactive) screenOffSince = null

        var best: Triple<Int, Engine, Map<String, Any>>? = null

        for ((_, eng) in engines) {
            if (eng.session.state == SState.SETTLED) continue
            val acts = eng.tick(now, fg, locked = !interactive)
            for (a in acts) handleAct(eng, a)
            if (eng.session.state == SState.RUNNING) {
                val st = eng.status(now, fg)
                val lvl = eng.session.level.coerceAtLeast(1)
                if (best == null || lvl > best!!.first) best = Triple(lvl, eng, st)
            }
        }

        // 重复提醒：错过开始 / 偏离中 / 息屏挂机
        repeatedReminders(now, interactive, best)

        if (best != null) {
            val (lvl, eng, st) = best!!
            val ctx = ctxOf(eng, st)
            val plan = eng.plan
            val left = ((st["targetSec"] as Double - st["effectiveSec"] as Double) / 60).toInt()
            val text = when {
                eng.session.devConfirmed -> Persona.say(lvl.coerceAtLeast(2), ctx)
                else -> "${plan.name}：有效 ${(st["effectiveSec"] as Double / 60).toInt()} 分 / 剩 $left 分"
            }
            Notify.nagUpdate(
                this, plan.name, text, lvl,
                st["effectiveSec"] as Double, st["targetSec"] as Double,
            )
        } else {
            val anyWaiting = engines.values.any { it.session.state == SState.WAITING }
            if (anyWaiting) {
                Notify.nagUpdate(this, "闭环监测中", "等待计划开始…", 1, 0.0, 1.0)
            } else {
                Notify.nagUpdate(this, "闭环监测中", "今日计划均已结算", 1, 0.0, 1.0)
            }
        }

        // 会话持久化（每 10 秒或刚好有事件时）
        tickCount += 1
        if (tickCount % 10 == 0L) persistSessions()

        onTick?.invoke()
    }

    /** 有声重复提醒：这是核心机制——不是通知一次就完，而是追到你去为止。 */
    private fun repeatedReminders(now: Long, interactive: Boolean, best: Triple<Int, Engine, Map<String, Any>>?) {
        // ① 每个进行中的计划：错过开始（每 2 分钟）/ 偏离中（每 1 分钟）
        for ((_, eng) in engines) {
            val s = eng.session
            if (s.state != SState.RUNNING) continue
            val last = lastNagAt[eng.plan.id] ?: 0L
            val late = now - eng.plan.startEpoch(LocalDate.now())
            val eff = s.effectiveSec

            when {
                // 到点后一直没开始
                eff < 5 && late > 120 && now - last >= 120 -> {
                    Notify.remind(
                        this, eng.plan.id.hashCode(), "已错过 ${(late / 60).toInt()} 分钟",
                        "到点 ${(late / 60).toInt()} 分钟了，「${eng.plan.name}」还没开始。",
                    )
                    lastNagAt[eng.plan.id] = now
                }
                // 偏离追击
                s.devConfirmed && now - last >= 60 -> {
                    val st = eng.status(now)
                    Notify.remind(
                        this, eng.plan.id.hashCode(), eng.plan.name,
                        Persona.say(s.level.coerceAtLeast(2), ctxOf(eng, st)),
                    )
                    lastNagAt[eng.plan.id] = now
                }
            }
        }

        // ② 息屏挂机：计时已暂停的显性提醒（每 5 分钟）
        val off = screenOffSince
        if (!interactive && off != null && best != null && now - off >= 300 &&
            now - lastScreenNagAt >= 300
        ) {
            val planName = best.second.plan.name
            val mins = ((now - off) / 60).toInt()
            Notify.remind(
                this, 4242, "计时已暂停",
                "手机已息屏 $mins 分钟，${planName} 的计时暂停了——你是睡着了，还是走开了？",
            )
            lastScreenNagAt = now
        }

        // ③ 亮屏挂机：前台是关联应用、计时在走，但 10 分钟没有任何交互事件
        // （启发式：读书/看视频不点屏幕也会误报，所以只提醒、不停表）
        if (interactive && best != null) {
            val eng = best.second
            val s = eng.session
            if (!s.devConfirmed && now - lastUserActivityAt >= 600 &&
                now - lastIdleNagAt >= 600
            ) {
                val st = best.third
                Notify.remind(
                    this, 4343, "还在吗？",
                    "你好像很久没操作了——还在「${appLabel(st["curPkg"] as String)}」里吗？" +
                        "计时仍在走；如果只是挂着，记得诚实结束。",
                )
                lastIdleNagAt = now
            }
        }
    }

    private fun ctxOf(eng: Engine, st: Map<String, Any>): Map<String, Any> {
        val eff = (st["effectiveSec"] as Double / 60).toInt()
        val left = ((st["targetSec"] as Double - st["effectiveSec"] as Double) / 60).toInt()
        return mapOf(
            "plan" to eng.plan.name,
            "target" to eng.plan.targetMinutes,
            "eff" to eff,
            "left" to left,
            "dev" to (st["deviationSec"] as Double / 60).toInt(),
            "count" to (st["deviationCount"] as Int),
            "cur" to appLabel(st["curPkg"] as String),
        )
    }

    private fun handleAct(eng: Engine, a: Act) {
        val st = eng.status(System.currentTimeMillis() / 1000)
        val ctx = ctxOf(eng, st)
        when (a) {
            is Act.Remind -> {
                Notify.remind(this, eng.plan.id.hashCode(), "到点了", Persona.say(1, ctx))
                lastNagAt[eng.plan.id] = System.currentTimeMillis() / 1000
            }
            is Act.Escalate -> Unit
            is Act.DeviationStart -> Unit
            is Act.DeviationEnd -> Unit
            is Act.CooldownEnter -> Notify.nagCancel(this)
            is Act.CooldownExit -> Unit
            is Act.FuseAsk -> {
                pendingFusePlanId = eng.plan.id
                Notify.remind(this, eng.plan.id.hashCode() + 7, "还要吗？", Persona.say(4, ctx))
            }
            is Act.Gap -> Unit
            is Act.Settle -> {
                PlanStore.appendLedger(this, LocalDate.now().toString(), mapOf(
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
                ))
                persistSessions()
                Notify.nagCancel(this)
                if (a.result == Engine.RESULT_DONE) {
                    Notify.remind(
                        this, eng.plan.id.hashCode() + 3, "完成",
                        Persona.sayDone(mapOf("plan" to eng.plan.name, "target" to eng.plan.targetMinutes)),
                    )
                }
            }
        }
    }

    private fun persistSessions() {
        val map = engines.entries.associate { it.key to it.value.session }
        PlanStore.saveSessions(this, map)
    }

    /** 通过 UsageEvents 取最近的前台应用包名，顺带刷新"最近用户活动"时间。 */
    private fun foregroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000, now)
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= 23 && e.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            ) {
                pkg = e.packageName
                lastUserActivityAt = maxOf(lastUserActivityAt, e.timeStamp / 1000)
            }
        }
        return pkg
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return "别的应用"
        return labelCache.getOrPut(pkg) {
            try {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
        }
    }

    override fun onDestroy() {
        persistSessions()
        scope.cancel()
        super.onDestroy()
    }
}
