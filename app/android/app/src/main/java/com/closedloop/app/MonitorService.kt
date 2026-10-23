package com.closedloop.app

import android.app.AppOpsManager
import android.app.Service
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** 执行中亮屏每 3 秒监测；待机降低频率，屏幕/时间变化立即重查。 */
class MonitorService : Service() {

    companion object {
        private val sessionLock = Any()
        val engines = ConcurrentHashMap<String, Engine>()

        /** 错过追击次数上限（v0.8）：追 5 次不来就别追了，等自动结算 */
        private const val MISS_NAG_MAX = 5

        /** 熔断待确认（由 Activity 弹窗处理） */
        @Volatile
        var pendingFusePlanId: String? = null

        @Volatile
        var running = false
            private set

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
            if (!PlanStore.monitoringEnabled(ctx)) return
            // 兜住 ForegroundServiceStartNotAllowedException（Android 12+ 后台起前台服务受限）。
            // 从开机广播里拉起时尤其可能撞上；崩在 receiver 里会变成"应用老是停止运行"，
            // 比"这次没起来"糟糕得多——下次打开应用就恢复了。
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            } catch (e: Exception) {
                android.util.Log.w("ClosedLoop", "Monitor start rejected", e)
            }
        }

        fun resume(ctx: Context) {
            PlanStore.setMonitoringEnabled(ctx, true)
            ctx.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(ctx, BootReceiver::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
            )
            start(ctx)
        }

        fun stopManually(ctx: Context) {
            // Persist the opt-out before stopping: neither sticky restart nor boot/update may undo it.
            PlanStore.setMonitoringEnabled(ctx, false)
            ctx.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(ctx, BootReceiver::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
            synchronized(sessionLock) {
                val now = System.currentTimeMillis() / 1000
                val saved = PlanStore.loadSessions(ctx).toMutableMap()
                for ((id, eng) in engines) saved[id] = eng.session
                for (session in saved.values) {
                    if (session.state == SState.SETTLED) continue
                    session.lastObserved = false
                    session.lastTick = now
                    session.curDevStart = null
                    session.devConfirmed = false
                    session.contDevSec = 0.0
                }
                PlanStore.saveSessions(ctx, saved, synchronous = true)
            }
            pendingFusePlanId = null
            Updater.DownloadState.cancelRequested = true
            ctx.stopService(Intent(ctx, MonitorService::class.java))
            Notify.cancelAll(ctx)
        }

        /** 落盘全部会话。服务、通知动作、Activity 三处都要落盘，抽一个静态入口免得各写一份。 */
        fun persist(ctx: Context) = synchronized(sessionLock) {
            if (!PlanStore.monitoringEnabled(ctx)) return // Explicit stop already saved a synchronized snapshot.
            val sessions = PlanStore.loadSessions(ctx).toMutableMap()
            engines.forEach { (id, eng) -> sessions[id] = eng.session }
            PlanStore.saveSessions(ctx, sessions)
        }

        /** Also used by the visible activity: edits take effect without dropping live progress. */
        fun refreshPlans(ctx: Context) = synchronized(sessionLock) {
            val result = SessionLifecycle.reload(
                PlanStore.loadPlans(ctx), PlanStore.loadSessions(ctx), engines.toMap(), LocalDate.now(),
            )
            result.history.forEach { (eng, act) ->
                Ledger.record(ctx, eng, act)
                Notify.cancelRemind(ctx, eng.plan.id)
                PlanStore.clearSnooze(ctx, eng.plan.id)
            }
            engines.keys.filter { it !in result.engines }.forEach { Notify.cancelRemind(ctx, it) }
            engines.clear()
            engines.putAll(result.engines)
            PlanStore.saveSessions(ctx, result.sessions)
            pendingFusePlanId?.let { id ->
                if (engines[id]?.session?.state !in listOf(SState.RUNNING, SState.COOLDOWN)) pendingFusePlanId = null
            }
        }

        /** Notification actions must work after the service process was reclaimed, too. */
        fun finishPlan(ctx: Context, id: String, reason: String, expectedDate: String? = null): Boolean =
            synchronized(sessionLock) {
                refreshPlans(ctx)
                val eng = engines[id] ?: return@synchronized false
                if (expectedDate != null && eng.session.date != expectedDate) return@synchronized false
                if (eng.session.state != SState.SETTLED) {
                    eng.giveUp(reason).filterIsInstance<Act.Settle>().forEach { Ledger.record(ctx, eng, it) }
                    PlanStore.saveSessions(ctx, PlanStore.loadSessions(ctx) + (id to eng.session))
                }
                if (pendingFusePlanId == id) pendingFusePlanId = null
                PlanStore.clearSnooze(ctx, id)
                Notify.cancelRemind(ctx, id)
                Notify.nagCancel(ctx)
                true
            }

        fun continuePlan(ctx: Context, id: String, expectedDate: String? = null) = synchronized(sessionLock) {
            if (expectedDate != null && engines[id]?.session?.date != expectedDate) return@synchronized
            engines[id]?.takeIf { it.session.state != SState.SETTLED }?.fuseContinue()
            if (pendingFusePlanId == id) pendingFusePlanId = null
            persist(ctx)
        }

        fun deletePlan(ctx: Context, id: String) = synchronized(sessionLock) {
            val eng = engines[id]
            if (eng != null && eng.session.state in listOf(SState.RUNNING, SState.COOLDOWN)) {
                finishPlan(ctx, id, "删除计划")
            }
            PlanStore.deletePlan(ctx, id)
            engines.remove(id)
            PlanStore.saveSessions(ctx, PlanStore.loadSessions(ctx) - id)
            PlanStore.clearSnooze(ctx, id)
            Notify.cancelRemind(ctx, id)
            if (pendingFusePlanId == id) pendingFusePlanId = null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var reloadRequested = false
    private val environmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_TIME_CHANGED ||
                intent.action == Intent.ACTION_TIMEZONE_CHANGED) reloadRequested = true
            wake.trySend(Unit)
        }
    }
    private val labelCache = ConcurrentHashMap<String, String>()

    /** 每计划上一次「有声提醒」时刻（秒），用于重复提醒节流 */
    private val lastNagAt = ConcurrentHashMap<String, Long>()

    /** 错过追击计数（v0.8）：每计划每天最多追 5 次，之后闭嘴等自动结算。
     *  旧实现 `late` 无上限，8 点的计划没做会被追到半夜。 */
    private val missNagCount = ConcurrentHashMap<String, Int>()

    /** 息屏开始时刻（秒）；null = 亮屏 */
    private var screenOffSince: Long? = null
    private var lastScreenNagAt = 0L

    /** 最近一次任何应用交互事件的时间（秒）——亮屏挂机启发式 */
    private var lastUserActivityAt = System.currentTimeMillis() / 1000
    private var lastIdleNagAt = 0L

    private var lastPersistAt = 0L
    private var nagPersonaKey: Pair<Int, Map<String, Any>>? = null
    private var nagPersonaText = ""

    /** 引擎对应的日期。跨天必须重建——按天排期后，周一的计划不能跑到周二去（v0.8）。 */
    private var engineDay: LocalDate = LocalDate.now()

    /** 前台状态保持：最近一次确知的前台包名（v0.4 修复核心）。
     *  旧实现只查最近 10 秒事件，看书这种"无切换"场景 10 秒后查不到事件 → 返回 null → 计时静默冻结。
     *  现在增量消费事件流：没新事件 = 没有切换 = 上次的前台应用仍在前面。 */
    private var currentFg: String? = null
    private var lastQueryAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notify.ensureChannels(this)
        ContextCompat.registerReceiver(this, environmentReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PlanStore.monitoringEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            ServiceCompat.startForeground(
                this, 1, buildSelfNotification(),
                if (Build.VERSION.SDK_INT >= 34)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else 0,
            )
        }
        running = true
        reloadRequested = true
        wake.trySend(Unit)
        // 只在没有循环时启动一个（v0.9）。以前每次 start() 都 launch 一个新 loop，
        // 而 Activity 每次 onResume 都会 start()——一次会话里切几次后台就多出几条循环在跑。
        // 计时按真实时间差算不会算错，但无谓地耗电、也让状态更新互相打架。
        if (loopJob?.isActive != true) loopJob = scope.launch { loop() }
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
        refreshPlans(this)
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (e: Exception) {
                android.util.Log.w("ClosedLoop", "Monitor tick failed", e)
            }
            val nowMs = System.currentTimeMillis()
            val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            val wait = MonitorCadence.delayMs(nowMs, canObserve(), engines.values.toList(), midnight)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private fun tick() = synchronized(sessionLock) { tickLocked() }

    private fun tickLocked() {
        if (!PlanStore.monitoringEnabled(this)) return
        val now = System.currentTimeMillis() / 1000
        // 跨天：先把昨天没结算的如实记一笔，再重建引擎（跑什么由"今天适用哪些计划"决定）
        val todayDate = LocalDate.now()
        if (todayDate != engineDay) {
            engineDay = todayDate
            missNagCount.clear()
            lastNagAt.clear()
            reloadRequested = true
        }
        // Close yesterday before reloading, including when onStartCommand wakes a sleeping service.
        if (reloadRequested) {
            reloadRequested = false
            reloadEngines()
        }
        val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val observable = interactive && !(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val needsForeground = MonitorCadence.needsForeground(now, engines.values.toList())
        // No plan is executing/due: don't ask UsageStats about unrelated phone use.
        val fg = if (observable && needsForeground) foregroundPackage() else null

        // 息屏跟踪
        if (!interactive && screenOffSince == null) screenOffSince = now
        if (interactive) screenOffSince = null

        var best: Triple<Int, Engine, Map<String, Any>>? = null

        for ((_, eng) in engines) {
            if (eng.session.state == SState.SETTLED) continue
            if (eng.session.state == SState.WAITING && now < eng.plan.startEpoch(eng.today)) continue
            val acts = eng.tick(now, fg, locked = !observable)
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
            val snoozeTill = PlanStore.snoozeUntil(this, plan.id)
            val text = when {
                // 静音期间常驻通知要说清楚"我不是不管了"，否则用户会以为坏了
                snoozeTill > now -> "已静音到 ${hhmm(snoozeTill)}，计时照走"
                eng.session.devConfirmed -> {
                    // Randomizing the same message every tick defeats notification deduplication.
                    val key = lvl to ctx
                    if (key != nagPersonaKey) {
                        nagPersonaKey = key
                        nagPersonaText = Persona.say(lvl.coerceAtLeast(2), ctx)
                    }
                    nagPersonaText
                }
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

        // Use elapsed time: adaptive polling must not stretch active-session persistence.
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastPersistAt >= 30_000) {
            persistSessions()
            lastPersistAt = elapsed
        }
    }

    /** 有声重复提醒：这是核心机制——不是通知一次就完，而是追到你去为止。
     *  v0.9：用户点了「稍后 15 分钟」的计划在这一段时间内完全不出声。 */
    private fun repeatedReminders(now: Long, interactive: Boolean, best: Triple<Int, Engine, Map<String, Any>>?) {
        // v0.8 夜间静默：22:30–07:00 一律不发声（错过/切走/息屏挂机/亮屏挂机全静音）。
        // 常驻通知照常更新，白天再看得到。设了不做的计划在半夜弹全屏，是卸载的头号原因。
        if (isQuietHours()) return

        // ① 每个进行中的计划：错过开始（每 2 分钟，最多 5 次）/ 偏离中（每 1 分钟）
        for ((_, eng) in engines) {
            val s = eng.session
            if (s.state != SState.RUNNING) continue
            // 用户按过「稍后」：这一段时间内不追。计时不受影响，真去做了照样记有效。
            if (PlanStore.snoozeUntil(this, eng.plan.id) > now) continue
            val last = lastNagAt[eng.plan.id] ?: 0L
            val late = now - eng.plan.startEpoch(LocalDate.now())
            val eff = s.effectiveSec

            when {
                // 到点后一直没开始（最多追 5 次，之后交给引擎的错过收敛自动结算）
                eff < 5 && late > 120 && now - last >= 120 -> {
                    val n = missNagCount[eng.plan.id] ?: 0
                    if (n < MISS_NAG_MAX) {
                        Notify.remind(
                            this, eng.plan.id.hashCode(), "${eng.plan.name} · 还没开始",
                            "原定 ${eng.plan.start} 开始，进入关联应用后开始记录。",
                            eng.plan.id,
                        )
                        lastNagAt[eng.plan.id] = now
                        missNagCount[eng.plan.id] = n + 1
                    }
                }
                // 偏离追击
                s.devConfirmed && now - last >= 60 -> {
                    val st = eng.status(now)
                    Notify.remind(
                        this, eng.plan.id.hashCode(), eng.plan.name,
                        Persona.say(s.level.coerceAtLeast(2), ctxOf(eng, st)),
                        eng.plan.id,
                    )
                    lastNagAt[eng.plan.id] = now
                }
            }
        }

        // ② 息屏挂机：计时已暂停的显性提醒（每 5 分钟）
        //    用户按过「稍后」就一起静音——他说的"别吵我"不该只对一半提醒生效
        val bestSnoozed = best != null && PlanStore.snoozeUntil(this, best.second.plan.id) > now
        val off = screenOffSince
        if (!interactive && off != null && best != null && !bestSnoozed && now - off >= 300 &&
            now - lastScreenNagAt >= 300
        ) {
            val planName = best.second.plan.name
            val mins = ((now - off) / 60).toInt()
            Notify.remind(
                this, 4242, "计时已暂停",
                "手机已息屏 $mins 分钟，${planName} 的计时暂停了——你是睡着了，还是走开了？",
                best.second.plan.id,
            )
            lastScreenNagAt = now
        }

        // ③ 亮屏挂机：前台是关联应用、计时在走，但 10 分钟没有任何交互事件
        // （启发式：读书/看视频不点屏幕也会误报，所以只提醒、不停表）
        if (interactive && best != null && !bestSnoozed) {
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
                    eng.plan.id,
                )
                lastIdleNagAt = now
            }
        }
    }

    private fun canObserve(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** 夜间静默：22:30 到次日 07:00 不发有声提醒。 */
    private fun isQuietHours(): Boolean {
        val t = java.time.LocalTime.now()
        return t.hour >= 23 || (t.hour == 22 && t.minute >= 30) || t.hour < 7
    }

    private fun hhmm(epochSec: Long): String {
        val t = java.time.Instant.ofEpochSecond(epochSec)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    private fun ctxOf(eng: Engine, st: Map<String, Any>): Map<String, Any> {        val eff = (st["effectiveSec"] as Double / 60).toInt()
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
        val canRemind = !isQuietHours() &&
            PlanStore.snoozeUntil(this, eng.plan.id) <= System.currentTimeMillis() / 1000
        when (a) {
            is Act.Remind -> {
                if (canRemind) Notify.remind(this, eng.plan.id.hashCode(), "到点了", Persona.say(1, ctx), eng.plan.id)
                lastNagAt[eng.plan.id] = System.currentTimeMillis() / 1000
            }
            is Act.Escalate -> Unit
            is Act.DeviationStart -> Unit
            is Act.DeviationEnd -> Unit
            is Act.CooldownEnter -> Notify.nagCancel(this)
            is Act.CooldownExit -> Unit
            is Act.FuseAsk -> {
                pendingFusePlanId = eng.plan.id
                if (canRemind) Notify.remind(this, eng.plan.id.hashCode() + 7, "还要吗？", Persona.say(4, ctx), eng.plan.id)
            }
            is Act.Gap -> Unit
            is Act.Settle -> {
                Notify.cancelRemind(this, eng.plan.id)
                Ledger.record(this, eng, a)
                // 结算了就没必要再静音了，留着会让明天的"稍后"莫名其妙的失效/生效
                PlanStore.clearSnooze(this, eng.plan.id)
                persistSessions()
                Notify.nagCancel(this)
                if (a.result == Engine.RESULT_DONE && canRemind) {
                    Notify.remind(
                        this, eng.plan.id.hashCode() + 3, "完成",
                        Persona.sayDone(mapOf("plan" to eng.plan.name, "target" to eng.plan.targetMinutes)),
                    )
                }
            }
        }
    }

    private fun persistSessions() = persist(this)

    /** 增量读取 UsageEvents 维护 currentFg，顺带刷新"最近用户活动"时间。
     *  窗口从上次查询点回退 2 秒（防边界丢事件）到当前；服务刚启动回溯 30 分钟恢复现场。
     *  窗口重叠导致的事件重复是幂等的，无副作用。
     *  lastQueryAt 只在查询成功后更新——查询抛异常时窗口保持不动，下次自动重查这段，不丢事件。
     *
     *  v0.7 小窗修复：除了 RESUMED，还要消费"退后台"类事件与 USER_INTERACTION——
     *  关小窗时，小窗应用会收到 PAUSED/STOPPED/DESTROYED，而部分 ROM 不给底下那个应用补发
     *  RESUMED。旧实现把 currentFg 永远卡在小窗应用上，用户明明切回去了却被持续判偏离。
     *  现在：currentFg 收到退后台事件 → 前台不可知 → 置空（引擎按"未观测"冻结），
     *  宁可诚实地不计时，也不瞎计时；随后靠新的 RESUMED 或用户触摸（USER_INTERACTION）恢复。 */
    private fun foregroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val begin = if (lastQueryAt == 0L) now - 1_800_000
        else maxOf(lastQueryAt - 2_000, now - 1_800_000)
        val events = usm.queryEvents(begin, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.eventType
            val pkg = e.packageName
            when {
                t == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= 23 && t == UsageEvents.Event.ACTIVITY_RESUMED) -> {
                    currentFg = pkg
                    lastUserActivityAt = maxOf(lastUserActivityAt, e.timeStamp / 1000)
                }
                isBackgroundType(t) && pkg == currentFg -> {
                    // 当前认定的前台应用退到后台了（典型：小窗被关闭/销毁）。
                    // 若同批事件里紧跟另一个 RESUMED，会被下面的分支接住；
                    // 没有后续 RESUMED（ROM 不补发）就保持"不可知"→ 冻结，不误判偏离。
                    currentFg = null
                }
                t == UsageEvents.Event.USER_INTERACTION -> {
                    lastUserActivityAt = maxOf(lastUserActivityAt, e.timeStamp / 1000)
                    // 前台未知时的恢复线索：用户在触摸哪个应用，它多半就在前台。
                    // （小窗关闭后 ROM 不补发 RESUMED 的场景，靠翻页/滑动触摸恢复计时。）
                    // 仅在 currentFg == null 时采用，避免与明确的 RESUMED 判断打架。
                    if (currentFg == null) currentFg = pkg
                }
            }
        }
        lastQueryAt = now
        return currentFg
    }

    /** "退到后台"类事件类型。
     *  ACTIVITY_STOPPED/DESTROYED 在部分 SDK 里没有公开常量，按 AOSP 实际值内联：
     *  ACTIVITY_PAUSED=2（即 MOVE_TO_BACKGROUND）、ACTIVITY_STOPPED=23、ACTIVITY_DESTROYED=24，
     *  这些事件从 API 29 起才会由系统发出；低版本设备上只是收不到，判断本身无害。 */
    private fun isBackgroundType(t: Int): Boolean =
        t == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            (Build.VERSION.SDK_INT >= 29 && (t == 23 || t == 24))

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
        running = false
        unregisterReceiver(environmentReceiver)
        persistSessions()
        scope.cancel()
        super.onDestroy()
    }
}
