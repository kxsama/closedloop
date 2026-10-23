package com.closedloop.app

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.Collator
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val GREEN = 0xFF207B5E.toInt()
        private const val GREEN_DARK = 0xFF177A50.toInt()
        private const val WARN = 0xFFE8A13A.toInt()
        private const val DANGER = 0xFFD64545.toInt()
        private const val INK = 0xFF172D24.toInt()
        private const val SUB = 0xFF65766C.toInt()
        private const val IDLE = 0xFFC9C7C0.toInt()
        private val DURATIONS = listOf(5, 10, 15, 20, 30, 45, 60, 90)
        private val REASONS = arrayOf("背完了", "今天状态不行", "计划定得不合理", "有突发")
    }

    private lateinit var plansContainer: LinearLayout
    private lateinit var reviewView: TextView
    private lateinit var dateView: TextView
    private lateinit var headlineView: TextView
    private lateinit var sublineView: TextView
    private lateinit var bannerView: TextView
    private lateinit var reviewSummary: TextView
    private lateinit var reviewToggle: TextView
    private lateinit var allPlansView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var fuseDialogShowing = false

    /** 复盘默认收起：一进来就摊开一堆小字，等于把最重要的信息埋掉 */
    private var reviewExpanded = false
    private var showSettled = false

    /** "全部计划"弹窗的引用：从列表点进编辑时要先关掉它，不然会叠两层对话框 */
    private var allPlanDialog: AlertDialog? = null

    /** 下载进度框（当前实例的）；状态本体在 Updater.DownloadState，切后台回来重挂 */
    private var dlDialog: AlertDialog? = null
    private var dlPolling = false
    private var downloadPoll: Runnable? = null
    private var resumed = false
    private var checkingUpdate = false
    private var pendingUpdateCheck: Pair<Boolean, Updater.Release?>? = null

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 3000)   // Only refresh the visible Activity; no service callback.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        // targetSdk 35 在 Android 15+ 强制边到边，内容会被状态栏遮住——按系统栏加内边距
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        plansContainer = findViewById(R.id.plansContainer)
        reviewView = findViewById(R.id.review)
        dateView = findViewById(R.id.dateView)
        headlineView = findViewById(R.id.headline)
        sublineView = findViewById(R.id.subline)
        bannerView = findViewById(R.id.banner)
        reviewSummary = findViewById(R.id.reviewSummary)
        reviewToggle = findViewById(R.id.reviewToggle)
        allPlansView = findViewById(R.id.allPlans)
        findViewById<Button>(R.id.btnNew).apply {
            backgroundTintList = null
            setOnClickListener { showPlanDialog() }
        }
        findViewById<TextView>(R.id.btnHealth).setOnClickListener {
            startActivity(Intent(this, DiagnoseActivity::class.java))
        }
        findViewById<TextView>(R.id.btnStop).setOnClickListener { stopAndExit() }
        findViewById<TextView>(R.id.btnUpdate).apply {
            setOnClickListener { checkUpdate() }
            text = updateBtnLabel()
        }
        // 提示条只在有问题时出现，点它去体检页
        bannerView.setOnClickListener { startActivity(Intent(this, DiagnoseActivity::class.java)) }
        allPlansView.setOnClickListener { showAllPlans() }
        findViewById<LinearLayout>(R.id.reviewHead).setOnClickListener {
            reviewExpanded = !reviewExpanded
            applyReviewVisibility()
        }
        applyReviewVisibility()

        Notify.ensureChannels(this)

        // 首次启动直接进体检页（只打扰这一次）。
        // 以前这里是一次光秃秃的通知权限请求——5 项设置里只管了 1 项，
        // 剩下 4 项没开时应用照常好看、后台什么都不做，用户不会报 bug，直接卸载。
        val sp = getSharedPreferences("closedloop", MODE_PRIVATE)
        if (!sp.getBoolean("onboarded", false)) {
            sp.edit().putBoolean("onboarded", true).apply()
            startActivity(Intent(this, DiagnoseActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        MonitorService.refreshPlans(this)
        consumeUpdateCheck()
        // 下载中切后台再回来 → 重挂进度框（状态在 DownloadState，丢不了）
        if (Updater.DownloadState.running) showDownloadDialog()
        // 授权页回来了且已允许 → 自动接着装
        if (Updater.DownloadState.awaitPermission && Updater.canInstall(this)) {
            Updater.DownloadState.awaitPermission = false
            Updater.DownloadState.result?.let { installApk(it) }
        }
        // 下载已结束还没处理（比如人不在时下完的）→ 接着走安装/报错
        consumeFinishedDownload()
        // 以前这里每次 onResume 都弹一次"需要一个关键权限"，只查 1 项、且拦路。
        // 现在改成首页顶部的信息条（见 refreshBanner），全部正常就完全不出现。
        if (PlanStore.monitoringEnabled(this) && MonitorService.hasUsageAccess(this)) MonitorService.start(this)
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(refresher)
        downloadPoll?.let { handler.removeCallbacks(it) }
        downloadPoll = null
        dlPolling = false
        // Background downloads keep running; only the visible progress UI stops refreshing.
        dlDialog?.setOnDismissListener(null)
        dlDialog?.dismiss()
        dlDialog = null
    }

    // ---------- 渲染 ----------

    private fun refresh() {
        val today = LocalDate.now()
        dateView.text = "${today.monthValue}月${today.dayOfMonth}日 ${weekdayName(today)}"
        val plans = PlanStore.loadPlans(this)
        val shown = plans.filter { it.appliesOn(today) }   // v0.8：只显示今天适用的
        val now = System.currentTimeMillis() / 1000

        plansContainer.removeAllViews()
        if (shown.isEmpty()) plansContainer.addView(emptyState(plans))
        val ended = shown.filter { MonitorService.engines[it.id]?.session?.state == SState.SETTLED }
        val open = shown.filterNot { it in ended }.sortedWith(compareBy<Plan> {
            if (MonitorService.engines[it.id]?.session?.state in listOf(SState.RUNNING, SState.COOLDOWN)) 0 else 1
        }.thenBy { it.forDay(today).start })
        for (p in open) plansContainer.addView(planRow(p, now))
        if (ended.isNotEmpty()) {
            plansContainer.addView(TextView(this).apply {
                text = "已结束 ${ended.size} 项　${if (showSettled) "收起 ↑" else "查看 ↓"}"
                textSize = 13f
                setTextColor(SUB)
                minHeight = dp(48)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setOnClickListener { showSettled = !showSettled; refresh() }
            })
            if (showSettled) for (p in ended) plansContainer.addView(planRow(p, now))
        }

        renderHeadline(shown, plans, today, now)
        refreshBanner()
        renderAllPlansEntry(plans, shown.size)
        renderReview(shown)
        maybeShowFuseDialog()
    }

    private fun weekdayName(d: LocalDate): String =
        arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]

    private fun emptyState(plans: List<Plan>): View = TextView(this).apply {
        text = if (plans.isEmpty())
            "还没有计划。\n\n先定一件真想做的小事——20 分钟也算。\n到点我提醒你，然后回来看你到底有没有做。"
        else "今天没有安排。\n\n" + nextOccurrenceText(plans)
        setTextColor(SUB)
        textSize = 12f
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(4), dp(20), dp(4), dp(22))
    }

    /**
     * 一句话现状（v0.9）。
     *
     * 以前打开应用看到的是一堆卡片 + 一段等宽小字，用户得自己在脑子里做汇总。
     * 现在第一眼就给出结论，顺序按"此刻最要紧"排：被切走 > 到点没开始 > 正在做 > 还没到点 > 都结算了。
     * 两个原则：① 只陈述事实，不评判；② 正在做的时候必须给"还差多少"，那是唯一有用的数字。
     */
    private fun renderHeadline(shown: List<Plan>, all: List<Plan>, day: LocalDate, now: Long) {
        if (!PlanStore.monitoringEnabled(this)) {
            headlineView.text = "监测已停止"
            sublineView.text = "不会检测、提醒或自动启动。计划与记录都保留，点下方提示可恢复。"
            return
        }
        if (shown.isEmpty()) {
            headlineView.text = if (all.isEmpty()) "还没有计划" else "今天没有安排"
            sublineView.text = if (all.isEmpty())
                "先定一件小事，剩下的交给提醒和回查。"
            else
                nextOccurrenceText(all)
            return
        }

        val target = shown.associateWith { it.forDay(day) }
        val sess = shown.associateWith {
            MonitorService.engines[it.id]?.session ?: Session(it.id, day.toString())
        }
        fun st(p: Plan) = sess.getValue(p).state
        fun effMin(p: Plan) = (sess.getValue(p).effectiveSec / 60).toInt()
        fun tgt(p: Plan) = target.getValue(p).targetMinutes

        val running = shown.filter { st(it) == SState.RUNNING || st(it) == SState.COOLDOWN }
        val waiting = shown.filter { st(it) == SState.WAITING }
        val settled = shown.filter { st(it) == SState.SETTLED }
        val deviating = running.firstOrNull { sess.getValue(it).devConfirmed }
        val notStarted = running.firstOrNull {
            sess.getValue(it).effectiveSec < 5 && now >= target.getValue(it).startEpoch(day)
        }

        // 进行中时，后面还剩几件——一句话交代清楚，不用往下翻
        val more = if (waiting.isEmpty()) "" else "　之后还有 ${waiting.size} 件。"
        // 已结算的不再提，避免"3 件事"和"还差 2 件"对不上
        val nextWait = waiting.minByOrNull { target.getValue(it).start }

        when {
            deviating != null -> {
                headlineView.text = "「${deviating.name}」切走了"
                sublineView.text =
                    "有效 ${effMin(deviating)} 分 / 目标 ${tgt(deviating)} 分。回到关联应用就接着算。"
            }

            notStarted != null -> {
                headlineView.text = "到了「${notStarted.name}」的时间"
                sublineView.text = "原定 ${target.getValue(notStarted).start} 开始。进入关联应用后，才开始记录时间。"
            }

            running.isNotEmpty() -> {
                val p = running.first()
                val left = (tgt(p) - effMin(p)).coerceAtLeast(0)
                headlineView.text = "正在「${p.name}」"
                sublineView.text = if (left > 0)
                    "有效 ${effMin(p)} 分，还差 $left 分。$more".trim()
                else
                    "目标已经够了，正在结算。"
            }

            nextWait != null -> {
                headlineView.text = "下一件，${nextWait.name}"
                sublineView.text =
                    "${target.getValue(nextWait).start} 开始 · ${tgt(nextWait)} 分钟。" +
                        if (waiting.size > 1) "之后还有 ${waiting.size - 1} 项。" else "到点会提醒你。"
            }

            else -> {
                val totalMin = shown.sumOf { effMin(it) }
                headlineView.text = "今天的安排已结束"
                sublineView.text = "记录了 $totalMin 分钟。已结束的安排和记录都可以在下方查看。"
            }
        }
    }

    /** 待处理提示条：只在真的有东西要处理时出现，且只说数量，不报警。 */
    private fun refreshBanner() {
        if (!PlanStore.monitoringEnabled(this)) {
            bannerView.visibility = View.VISIBLE
            bannerView.text = "监测已停止　·　点此恢复 ›"
            bannerView.setOnClickListener { MonitorService.resume(this); refresh() }
            return
        }
        bannerView.setOnClickListener { startActivity(Intent(this, DiagnoseActivity::class.java)) }
        val pending = Health.pending(this)
        if (pending.isEmpty()) {
            bannerView.visibility = View.GONE
            return
        }
        bannerView.visibility = View.VISIBLE
        bannerView.text = "有 ${pending.size} 项运行检查需要处理　›"
    }

    /** 全部计划的入口：今天没有的计划（含已暂停的）都得有个地方找回来，否则"暂停"等于"消失"。 */
    private fun renderAllPlansEntry(plans: List<Plan>, todayCount: Int) {
        if (plans.isEmpty()) {
            allPlansView.visibility = View.GONE
            return
        }
        allPlansView.visibility = View.VISIBLE
        val paused = plans.count { !it.enabled }
        val others = plans.size - todayCount
        allPlansView.text = buildString {
            append("全部计划 ${plans.size} 个")
            if (others > 0) append(" · 今天以外 $others 个")
            if (paused > 0) append(" · 已暂停 $paused 个")
            append("　›")
        }
    }


    /** 今天没安排时，告诉用户下一次是什么时候——按天排期后这是最容易迷路的地方。 */
    private fun nextOccurrenceText(plans: List<Plan>): String {
        val today = LocalDate.now()
        var best: Pair<LocalDate, Plan>? = null
        for (i in 1..14) {
            val d = today.plusDays(i.toLong())
            val hit = plans.firstOrNull { it.appliesOn(d) } ?: continue
            best = d to hit
            break
        }
        val b = best ?: return "（之后 14 天都没有安排）"
        val (d, p) = b
        val wd = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]
        val slot = p.slotOf(d)
        return "下次：${d.monthValue}月${d.dayOfMonth}日 $wd ${slot.start}「${p.name}」"
    }

    private fun planRow(p: Plan, now: Long): View {
        val day = LocalDate.now()
        val todayPlan = p.forDay(day)
        val session = MonitorService.engines[p.id]?.session
        val state = session?.state ?: SState.WAITING
        val active = state == SState.RUNNING || state == SState.COOLDOWN
        val stopped = !PlanStore.monitoringEnabled(this)
        val effective = session?.effectiveSec ?: 0.0
        val target = todayPlan.targetMinutes * 60.0
        val snoozed = PlanStore.snoozeUntil(this, p.id) > now
        val expired = effective < 5 && now - todayPlan.startEpoch(day) >= Engine.MISS_SETTLE_SEC
        val label = when {
            stopped -> "监测已停"
            state == SState.SETTLED && session?.reason == "未开始" -> "时段已过"
            state == SState.SETTLED -> session?.result ?: "已结束"
            expired -> "时段已过"
            snoozed -> "已静音"
            state == SState.COOLDOWN -> "暂不催促"
            active && effective < 5 -> "待开始"
            active && session?.devConfirmed == true -> "已切走"
            active -> "进行中"
            else -> "待开始"
        }
        val emphasis = active && !expired && !stopped
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        val head = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = p.name; textSize = 16f; setTextColor(INK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        head.addView(TextView(this).apply {
            text = label; textSize = 11f
            setTextColor(if (emphasis) GREEN_DARK else SUB)
            background = rounded(if (emphasis) 0xFFEAF4EE.toInt() else 0xFFF2F5F3.toInt(), 10)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        })
        card.addView(head)
        val time = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        time.addView(TextView(this).apply {
            text = todayPlan.start; textSize = 24f; setTextColor(INK)
        })
        time.addView(TextView(this).apply {
            text = "${todayPlan.targetMinutes} 分钟"; textSize = 13f; setTextColor(SUB)
            setPadding(dp(12), 0, 0, 0)
        })
        card.addView(time, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (active || effective > 0) {
            card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = target.toInt(); progress = effective.toInt().coerceIn(0, max)
                progressTintList = ColorStateList.valueOf(if (session?.devConfirmed == true) WARN else GREEN)
                progressBackgroundTintList = ColorStateList.valueOf(0xFFEDF2EE.toInt())
            }, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(16) })
        }
        val foot = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        foot.addView(TextView(this).apply {
            text = if (active || effective > 0) "已记录 ${fmt(effective)} / ${fmt(target)}" else scheduleText(p)
            textSize = 12f; setTextColor(SUB)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (active && !expired && !stopped) foot.addView(TextView(this).apply {
            text = "结束"; textSize = 12f; setTextColor(SUB)
            gravity = android.view.Gravity.CENTER; minWidth = dp(48); minHeight = dp(48)
            setOnClickListener { showEarlyEndDialog(p) }
        })
        foot.addView(TextView(this).apply {
            text = "编辑"; textSize = 12f; setTextColor(GREEN_DARK)
            gravity = android.view.Gravity.CENTER; minWidth = dp(48); minHeight = dp(48)
            setOnClickListener { showPlanDialog(p) }
        })
        card.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return card
    }

    private fun rounded(color: Int, radius: Int = 14) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun stopAndExit() {
        AlertDialog.Builder(this)
            .setTitle("停止监测并退出？")
            .setMessage("停止后不会检测、提醒，也不会随开机或更新自动启动。计划和记录会保留，下次打开可手动恢复监测。")
            .setNegativeButton("取消", null)
            .setPositiveButton("停止并退出") { _, _ ->
                MonitorService.stopManually(this)
                finishAndRemoveTask()
                handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 200)
            }.show()
    }

    /**
     * 当日复盘（v0.9 改成"折叠"）。
     *
     * 折叠不是藏起来，是**先给结论再给明细**：一行摘要（有效多少分钟、切走多少分钟）放在标题旁边，
     * 想看逐条再展开。以前一进首页就是一段 11sp 的小字，既不好读，也把上面的重点挤掉了。
     *
     * 内容只放**已结算的记录**。待开始的计划在卡片和顶部那句话里已经有了，
     * 在"复盘"里再列一遍会让人误以为那些也算今天的成绩。
     */
    private fun renderReview(plans: List<Plan>) {
        val recs = PlanStore.loadLedger(this, LocalDate.now().toString())
        val lines = mutableListOf<String>()
        for (rec in recs) {
            val name = rec["name"] ?: "?"
            val result = rec["result"] ?: "?"
            val eff = (rec["effectiveMin"] as? Double)?.toInt() ?: 0
            val target = (rec["target"] as? Double)?.toInt() ?: 0
            val devMin = (rec["deviationMin"] as? Double)?.toInt() ?: 0
            val devCnt = (rec["deviationCount"] as? Double)?.toInt() ?: 0
            val gaps = (rec["gaps"] as? Double)?.toInt() ?: 0
            val reason = rec["reason"]?.toString()?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
            val gapText = if (gaps > 0) "｜未观测 $gaps 段" else ""
            lines.add("$result　$name$reason\n有效 $eff/$target 分　切走 $devMin 分 × $devCnt 次$gapText")
        }

        val effSum = recs.sumOf { (it["effectiveMin"] as? Double) ?: 0.0 }.toInt()
        val devSum = recs.sumOf { (it["deviationMin"] as? Double) ?: 0.0 }.toInt()
        reviewSummary.text = when {
            recs.isEmpty() -> "今天还没有结算记录"
            devSum > 0 -> "有效 $effSum 分钟 · 切走 $devSum 分钟"
            else -> "有效 $effSum 分钟 · 没有切走"
        }
        reviewView.text = if (lines.isEmpty()) "今天还没有结算记录。\n计划结算后会出现在这里。" else lines.joinToString("\n\n")
        applyReviewVisibility()
    }

    private fun applyReviewVisibility() {
        reviewView.visibility = if (reviewExpanded) View.VISIBLE else View.GONE
        reviewToggle.text = if (reviewExpanded) "收起" else "展开"
    }

    // ---------- 全部计划（含今天不做 / 已暂停的）----------

    private fun dayOn(p: Plan, d: Int) = p.slots?.get(d)?.on ?: true
    private fun dayStart(p: Plan, d: Int) = p.slots?.get(d)?.start ?: p.start
    private fun dayMin(p: Plan, d: Int) = p.slots?.get(d)?.minutes ?: p.targetMinutes

    /** 一句话排期摘要："周一三五 20:00 · 30 分钟 · 单周"，用于列表与对话。 */
    private fun scheduleText(p: Plan): String {
        val days = (1..7).filter { dayOn(p, it) }
        val dayText = when {
            days.isEmpty() -> "没有选任何一天"
            days.size == 7 -> "每天"
            days == listOf(1, 2, 3, 4, 5) -> "工作日"
            days == listOf(6, 7) -> "周末"
            else -> "周" + days.joinToString("") { DAY_LABELS[it - 1] }
        }
        val starts = days.map { dayStart(p, it) }.distinct()
        val mins = days.map { dayMin(p, it) }.distinct()
        val timeText = if (starts.size == 1) starts[0] else "时间按天不同"
        val minText = if (mins.size == 1) "${mins[0]} 分钟" else "时长按天不同"
        val weekText = when (p.weekMode) {
            "odd" -> " · 单周"
            "even" -> " · 双周"
            else -> ""
        }
        return "$dayText $timeText · $minText$weekText"
    }

    /**
     * 全部计划（v0.9）。
     *
     * 首页只显示今天生效的计划，所以必须有这个入口：否则**暂停一个计划 = 它从世界上消失**，
     * 用户再也找不到它，也删不掉。这里同时补上了 `Plan.enabled` 的开关——
     * 这个字段一直存在、服务也认，但以前没有任何界面能改它，只能"删了重建"。
     */
    private fun showAllPlans() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("全部计划")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("关闭", null)
            .create()

        fun rebuild() {
            box.removeAllViews()
            val plans = PlanStore.loadPlans(this)
            if (plans.isEmpty()) {
                box.addView(TextView(this).apply {
                    text = "还没有计划。"
                    setTextColor(SUB); textSize = 12f
                    setPadding(dp(4), dp(18), dp(4), dp(18))
                })
                return
            }
            for (p in plans) {
                box.addView(allPlanRow(p) {
                    rebuild()
                    refresh()
                })
            }
        }
        rebuild()
        dialog.show()
        frost(dialog)
        allPlanDialog = dialog
        dialog.setOnDismissListener { allPlanDialog = null }
    }

    private fun allPlanRow(p: Plan, onChange: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(11), dp(9), dp(11))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = p.name
            textSize = 13f
            setTextColor(if (p.enabled) INK else SUB)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = scheduleText(p)
            textSize = 10f
            setTextColor(if (p.enabled) SUB else IDLE)
            setPadding(0, dp(3), 0, 0)
        })
        // 点名字进编辑：改名、改时间、改排期都在这儿
        col.setOnClickListener {
            dismissTopDialog()
            showPlanDialog(p)
        }
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(this).apply {
            isChecked = p.enabled
            setOnCheckedChangeListener { _, checked ->
                if (checked != p.enabled) {
                    PlanStore.updatePlan(this@MainActivity, p.copy(enabled = checked))
                    MonitorService.refreshPlans(this@MainActivity)
                    MonitorService.start(this@MainActivity)
                    onChange()
                }
            }
        }
        row.addView(sw)
        return row
    }

    /** 关掉当前最上层的对话框（从列表进编辑时用，避免对话框叠两层） */
    private fun dismissTopDialog() {
        // 没有可靠的"取当前对话框"，用一个显式引用更稳
        allPlanDialog?.dismiss()
        allPlanDialog = null
    }


    // ---------- 对话框 ----------

    /** 删除确认：说明代价（历史账本保留），避免"点错了全没了"的恐慌。 */
    private fun confirmDelete(p: Plan, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("删除「${p.name}」？")
            .setMessage("计划会从列表里消失。已经记下的历史账本不动——那些是发生过的事，不该被删掉。")
            .setPositiveButton("删除") { _, _ ->
                MonitorService.deletePlan(this, p.id)
                onDone()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEarlyEndDialog(p: Plan) {
        val date = LocalDate.now().toString()
        AlertDialog.Builder(this)
            .setTitle("提前结束「${p.name}」？")
            .setItems(REASONS) { _, which ->
                MonitorService.finishPlan(this, p.id, REASONS[which], date)
                refresh()
            }
            .setNegativeButton("再想想", null)
            .show()
    }

    private fun maybeShowFuseDialog() {
        val pid = MonitorService.pendingFusePlanId ?: return
        if (fuseDialogShowing) return
        val eng = MonitorService.engines[pid] ?: run {
            MonitorService.pendingFusePlanId = null; return
        }
        val date = eng.session.date
        fuseDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("熔断询问")
            .setMessage("「${eng.plan.name}」累计切走时间已达上限。还要吗？")
            .setPositiveButton("继续") { _, _ ->
                MonitorService.continuePlan(this, pid, date)
                MonitorService.pendingFusePlanId = null
                fuseDialogShowing = false
            }
            .setNegativeButton("今天算了") { _, _ ->
                MonitorService.finishPlan(this, pid, "熔断后主动结束", date)
                MonitorService.pendingFusePlanId = null
                fuseDialogShowing = false
                refresh()
            }
            .setOnCancelListener { fuseDialogShowing = false }
            .show()
    }

    // ---------- 新建 / 编辑计划（v0.8：按天排期 + 单双周 + 按天微调） ----------

    private val DAY_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 可复用的时间选择（闹钟双滚轮，反 Holo） */
    private fun pickTime(initial: String, onPicked: (String) -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_time, null)
        val hourPicker = v.findViewById<NumberPicker>(R.id.pickerHour)
        val minPicker = v.findViewById<NumberPicker>(R.id.pickerMinute)
        hourPicker.minValue = 0
        hourPicker.maxValue = 23
        hourPicker.displayedValues = (0..23).map { "%02d".format(it) }.toTypedArray()
        minPicker.minValue = 0
        minPicker.maxValue = 59
        minPicker.displayedValues = (0..59).map { "%02d".format(it) }.toTypedArray()
        val parts = initial.split(":")
        hourPicker.value = (parts.getOrNull(0)?.toIntOrNull() ?: 20).coerceIn(0, 23)
        minPicker.value = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
        AlertDialog.Builder(this)
            .setTitle("选择开始时间")
            .setView(v)
            .setPositiveButton("确定") { _, _ ->
                onPicked("%02d:%02d".format(hourPicker.value, minPicker.value))
            }
            .setNegativeButton("取消", null)
            .show().also { frost(it) }
    }

    /** 可复用的时长选择 */
    private fun pickMinutes(initial: Int, onPicked: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(initial.toString()); selectAll()
            setTextColor(INK); textSize = 22f
            hint = "1–999 分钟"
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(24), dp(14), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(56)))
        }
        val dialog = AlertDialog.Builder(this).setTitle("做多久")
            .setView(wrap).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = input.text.toString().toIntOrNull()
                if (n == null || n !in 1..999) input.error = "请输入 1–999 分钟"
                else { onPicked(n); dialog.dismiss() }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    /** Reusable selection chip; one source of truth remains ScheduleDraft.slots. */
    private fun chipView(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label; textSize = 12f
            gravity = android.view.Gravity.CENTER
            minHeight = dp(48)
            isSelected = selected
            setTextColor(if (selected) GREEN_DARK else SUB)
            background = rounded(if (selected) 0xFFE5F2E9.toInt() else 0xFFF3F6F4.toInt(), 12)
            setOnClickListener { onClick() }
        }

    private fun showPlanDialog(existing: Plan? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_plan, null)
        view.isFocusableInTouchMode = true
        view.requestFocus()
        val editor = Dialog(this, R.style.Theme_ClosedLoop_Editor)
        editor.setContentView(view)
        val defaultStart = java.time.LocalTime.now().plusMinutes(2).let { "%02d:%02d".format(it.hour, it.minute) }
        val draft = ScheduleDraft(existing, defaultStart)
        val editName = view.findViewById<EditText>(R.id.editName)
        val btnTime = view.findViewById<TextView>(R.id.btnTime)
        val btnDuration = view.findViewById<TextView>(R.id.btnDuration)
        val btnApps = view.findViewById<TextView>(R.id.btnApps)
        val dayRow = view.findViewById<LinearLayout>(R.id.dayRow)
        val weekRow = view.findViewById<LinearLayout>(R.id.weekRow)
        val presets = view.findViewById<LinearLayout>(R.id.durationPresets)
        val repeatSummary = view.findViewById<TextView>(R.id.repeatSummary)
        val options = view.findViewById<View>(R.id.repeatOptions)
        val installed = launchableApps()
        val chosen = linkedSetOf<String>().apply { existing?.packages?.let { addAll(it) } }
        view.findViewById<TextView>(R.id.editorTitle).text = if (existing == null) "新建计划" else "编辑计划"
        editName.setText(existing?.name.orEmpty())
        view.findViewById<TextView>(R.id.btnCancelPlan).setOnClickListener { editor.dismiss() }

        fun appNames() = chosen.map { pkg -> installed.firstOrNull { it.pkg == pkg }?.label ?: pkg }
        fun updateSummary() {
            repeatSummary.text = draft.summary()
            view.findViewById<TextView>(R.id.perDayHint).text = if (draft.customized.isEmpty())
                "未单独设置的日期使用上面的时间和时长"
            else "已单独安排：" + draft.customized.sorted().joinToString("、") { "周${DAY_LABELS[it - 1]}" }
        }
        fun paintTiming() {
            btnTime.text = draft.start
            btnDuration.text = "${draft.minutes} 分钟"
            presets.removeAllViews()
            for (minutes in listOf(10, 20, 30, 45, 60)) {
                val chip = chipView("$minutes 分", draft.minutes == minutes) {
                    draft.setBase(newMinutes = minutes); paintTiming(); updateSummary()
                }
                presets.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
            }
        }
        fun paintDays() {
            dayRow.removeAllViews()
            for (d in 1..7) {
                val on = draft.slots.getValue(d).on
                val chip = chipView(DAY_LABELS[d - 1], on) {
                    draft.slots.getValue(d).on = !on; paintDays(); updateSummary()
                }
                chip.contentDescription = "周${DAY_LABELS[d - 1]}，${if (on) "已选择" else "未选择"}"
                dayRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            }
        }
        fun paintWeeks() {
            weekRow.removeAllViews()
            for ((mode, label) in listOf("all" to "每周", "odd" to "单周", "even" to "双周")) {
                weekRow.addView(chipView(label, draft.weekMode == mode) {
                    draft.weekMode = mode; paintWeeks(); updateSummary()
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
            }
            view.findViewById<TextView>(R.id.weekHint).text =
                "本周是${if (weekIsOdd(LocalDate.now())) "单周" else "双周"}"
        }
        btnTime.setOnClickListener {
            pickTime(draft.start) { draft.setBase(newStart = it); paintTiming(); updateSummary() }
        }
        btnDuration.setOnClickListener {
            pickMinutes(draft.minutes) { draft.setBase(newMinutes = it); paintTiming(); updateSummary() }
        }
        fun paintApps() {
            val names = appNames()
            btnApps.text = if (names.isEmpty()) "选择应用　›"
            else names.take(3).joinToString("、") + if (names.size > 3) " 等 ${names.size} 个　›" else "　›"
        }
        btnApps.setOnClickListener { showAppPicker(installed, chosen) { paintApps() } }
        view.findViewById<View>(R.id.repeatToggle).setOnClickListener {
            val open = options.visibility != View.VISIBLE
            options.visibility = if (open) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.repeatArrow).text = if (open) "收起" else "展开"
        }
        view.findViewById<TextView>(R.id.btnPerDay).setOnClickListener {
            val working = draft.copySlots()
            showPerDayDialog(working, draft.customized.toMutableSet(), draft.start, draft.minutes) {
                draft.applyDays(working); paintDays(); updateSummary()
            }
        }
        view.findViewById<TextView>(R.id.btnDeletePlan).apply {
            visibility = if (existing == null) View.GONE else View.VISIBLE
            setOnClickListener { existing?.let { plan -> confirmDelete(plan) { editor.dismiss() } } }
        }
        view.findViewById<Button>(R.id.btnSavePlan).apply {
            backgroundTintList = null
            text = if (existing == null) "创建计划" else "保存修改"
            setOnClickListener {
                val name = editName.text.toString().trim().ifBlank { appNames().firstOrNull().orEmpty() }
                when {
                    chosen.isEmpty() -> Toast.makeText(this@MainActivity, "请选择至少一个关联应用", Toast.LENGTH_SHORT).show()
                    draft.slots.values.none { it.on } -> {
                        options.visibility = View.VISIBLE
                        view.findViewById<TextView>(R.id.repeatArrow).text = "收起"
                        Toast.makeText(this@MainActivity, "请至少选择一天", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val plan = existing?.copy(name = name, start = draft.start, targetMinutes = draft.minutes,
                            packages = chosen.toMutableList(), slots = draft.copySlots(), weekMode = draft.weekMode)
                            ?: Plan(UUID.randomUUID().toString().take(8), name, draft.start, draft.minutes,
                                chosen.toMutableList(), slots = draft.copySlots(), weekMode = draft.weekMode)
                        if (existing == null) PlanStore.addPlan(this@MainActivity, plan)
                        else PlanStore.updatePlan(this@MainActivity, plan)
                        MonitorService.refreshPlans(this@MainActivity)
                        MonitorService.start(this@MainActivity)
                        editor.dismiss(); refresh()
                    }
                }
            }
        }
        paintTiming(); paintDays(); paintWeeks(); paintApps(); updateSummary()
        editor.window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        editor.show()
        editor.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** 按天微调：7 行 = 开关 + 时间 + 时长 */
    private fun showPerDayDialog(
        slots: MutableMap<Int, DaySlot>,
        customized: MutableSet<Int>,
        baseTime: String,
        baseMinutes: Int,
        onDone: () -> Unit,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_perday, null)
        val list = view.findViewById<LinearLayout>(R.id.perDayList)
        view.findViewById<TextView>(R.id.perDayHint).text =
            "没动过的天跟随「$baseTime / $baseMinutes 分」"

        for (d in 1..7) {
            val s = slots[d] ?: continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val cb = CheckBox(this).apply {
                text = "周" + DAY_LABELS[d - 1]
                textSize = 13f
                isChecked = s.on
                setOnCheckedChangeListener { _, v -> s.on = v }
            }
            row.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val btnT = Button(this).apply {
                text = s.start
                textSize = 12f
                setTextColor(INK)
                setBackgroundColor(0xFFF1F0EC.toInt())
                setOnClickListener {
                    pickTime(s.start) { t ->
                        s.start = t
                        customized.add(d)
                        text = t
                    }
                }
            }
            row.addView(btnT)

            val btnM = Button(this).apply {
                text = "${s.minutes} 分"
                textSize = 12f
                setTextColor(INK)
                setBackgroundColor(0xFFF1F0EC.toInt())
                setOnClickListener {
                    pickMinutes(s.minutes) { m ->
                        s.minutes = m
                        customized.add(d)
                        text = "$m 分"
                    }
                }
            }
            row.addView(btnM)
            list.addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle("按天微调")
            .setView(view)
            .setPositiveButton("好了") { _, _ -> onDone() }
            .setNegativeButton("取消", null)
            .show().also { frost(it) }
    }

    // ---------- 应用选择器（搜索 + 图标 + 拼音混排 + 数字/符号垫底） ----------

    data class AppEntry(val label: String, val pkg: String, val icon: Drawable)

    private val collator: Collator = Collator.getInstance(Locale.CHINA)

    /** 排序组：字母或汉字开头 → 0（按拼音/字母混排）；数字与其他 → 1（垫底） */
    private fun sortGroup(label: String): Int {
        val c = label.firstOrNull() ?: return 1
        return if (Character.isLetter(c)) 0 else 1
    }

    private fun launchableApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map {
                AppEntry(
                    it.loadLabel(pm).toString().ifBlank { it.activityInfo.packageName },
                    it.activityInfo.packageName,
                    it.loadIcon(pm),
                )
            }
            .distinctBy { it.pkg }
            .sortedWith { a, b ->
                val g = sortGroup(a.label) - sortGroup(b.label)
                if (g != 0) g else collator.compare(a.label, b.label)
            }
    }

    private inner class AppAdapter(
        private val all: List<AppEntry>,
        private val chosen: MutableSet<String>,
    ) : BaseAdapter() {
        var shown: List<AppEntry> = all
            private set

        fun filter(q: String) {
            shown = if (q.isBlank()) all else all.filter {
                it.label.contains(q, ignoreCase = true) ||
                    it.pkg.contains(q, ignoreCase = true)
            }
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            val app = shown[position]
            row.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.appLabel).text = app.label
            val check = row.findViewById<CheckBox>(R.id.appCheck)
            check.setOnCheckedChangeListener(null)
            check.isChecked = app.pkg in chosen
            val toggle = View.OnClickListener {
                if (app.pkg in chosen) chosen.remove(app.pkg) else chosen.add(app.pkg)
                check.isChecked = app.pkg in chosen
            }
            check.setOnClickListener { toggle.onClick(row) }
            row.setOnClickListener(toggle)
            return row
        }
    }

    private fun showAppPicker(
        installed: List<AppEntry>,
        chosen: MutableSet<String>,
        onDone: () -> Unit,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_apps, null)
        val search = view.findViewById<EditText>(R.id.searchApps)
        val list = view.findViewById<ListView>(R.id.listApps)
        val adapter = AppAdapter(installed, chosen)
        list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        AlertDialog.Builder(this)
            .setTitle("选择关联应用（可多选）")
            .setView(view)
            .setPositiveButton("确定") { _, _ -> onDone() }
            .show().also { frost(it) }
    }

    /** 毛玻璃：Android 12+ 对话框背景模糊；低版本静默跳过。 */
    private fun frost(dialog: Dialog) {
        if (Build.VERSION.SDK_INT >= 31) {
            dialog.window?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = 24 }
            }
        }
    }

    // ---------- 应用内更新（M6；v0.7 重构下载状态为进程级单例） ----------

    /** 按钮上常驻显示当前版本——免得出现"我到底装的是哪版"的扯皮；
     *  下载中改为"下载中"，点击即回到进度框。 */
    private fun updateBtnLabel(): String =
        if (Updater.DownloadState.running) "下载中… · 点击查看"
        else if (checkingUpdate) "检查中…"
        else "v${localVersion()} · 更新"

    private fun localVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun checkUpdate() {
        if (Updater.DownloadState.running) {   // 别重复起下载——重挂进度框就行
            showDownloadDialog(); return
        }
        if (checkingUpdate) return
        checkingUpdate = true
        val btn = findViewById<TextView>(R.id.btnUpdate)
        btn.text = "检查中…"
        Thread {
            val rel = Updater.check()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pendingUpdateCheck = true to rel
                consumeUpdateCheck()
            }
        }.start()
    }

    private fun consumeUpdateCheck() {
        if (!resumed) return
        val result = pendingUpdateCheck ?: return
        pendingUpdateCheck = null
        checkingUpdate = false
        findViewById<TextView>(R.id.btnUpdate).text = updateBtnLabel()
        val rel = result.second
        when {
            rel == null -> Toast.makeText(this, "检查失败：网络不通或 GitHub 访问受限", Toast.LENGTH_LONG).show()
            !Updater.newerThan(rel.versionName, Updater.localVersionName(this)) ->
                Toast.makeText(this, "已是最新版｜当前 v${localVersion()}", Toast.LENGTH_SHORT).show()
            else -> showUpdateDialog(rel)
        }
    }

    /** 发现新版本弹窗：只放一句话摘要（notes 第一段）——不再糊整篇 Markdown 文本墙；
     *  想看全文点「完整说明」跳浏览器。 */
    private fun showUpdateDialog(rel: Updater.Release) {
        val summary = Updater.summaryOf(rel.notes)
            .ifBlank { "下载后可直接覆盖安装，数据不会丢。" }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${rel.tag}（当前 v${localVersion()}）")
            .setMessage(summary)
            .setPositiveButton("下载并安装") { _, _ -> downloadUpdate(rel) }
            .setNeutralButton("完整说明") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.htmlUrl)))
                } catch (_: Exception) {
                    Toast.makeText(this, "没有可打开的浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("以后再说", null)
            .show()
    }

    /** 防重复入口：已在下载就只重挂进度框。 */
    private fun downloadUpdate(rel: Updater.Release) {
        if (Updater.DownloadState.running) {
            showDownloadDialog(); return
        }
        Updater.DownloadState.begin(rel.tag)
        Notify.dlUpdate(applicationContext, "下载 ${rel.tag}", "正在连接…", 0, 0)
        // 下载线程只持 applicationContext，不持 Activity——Activity 重建/回收都不影响它
        Thread {
            val appCtx = applicationContext
            var lastNotifyAt = 0L
            val f = Updater.download(appCtx, rel, { done, total ->
                val s = Updater.DownloadState
                s.done = done; s.total = total
                // 通知别刷太勤：每 512KB 或刚换源后更新一次
                if (done - lastNotifyAt > 512 * 1024 || lastNotifyAt == 0L) {
                    lastNotifyAt = done
                    val txt = if (total > 0) "%.1f / %.1f MB".format(done / 1048576.0, total / 1048576.0)
                    else "${done / 1024} KB"
                    Notify.dlUpdate(appCtx, "下载 ${rel.tag}", txt, done, total)
                }
                !s.cancelRequested
            }, { src ->
                Updater.DownloadState.source = src
                Notify.dlUpdate(appCtx, "下载 ${rel.tag}", "正在连接 $src…", 0, 0)
            })
            Updater.DownloadState.finish(f)
            Notify.dlCancel(appCtx)
            // 人可能已经切走了：结果处理交给 Activity 回来时的 consumeFinishedDownload()
        }.start()
        showDownloadDialog()
    }

    /** 进度框：不直接接收线程回调，而是 400ms 轮询 DownloadState——
     *  这样无论哪个 Activity 实例弹出它，都能显示同一份真实进度；
     *  下载结束（含取消/失败）自动收尾。 */
    private fun showDownloadDialog() {
        if (!resumed) return
        if (dlDialog?.isShowing == true) return
        val s = Updater.DownloadState
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(GREEN)
        }
        val status = TextView(this).apply {
            text = "正在连接…"; textSize = 11f; setTextColor(SUB)
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(bar)
        box.addView(status)
        val dlg = AlertDialog.Builder(this)
            .setTitle("下载 ${s.tag}")
            .setView(box)
            .setNegativeButton("取消", null)
            .show()
        // 关掉进度框 = 取消下载（通知栏也看得到取消效果）
        dlg.setOnDismissListener {
            if (Updater.DownloadState.running) Updater.DownloadState.cancelRequested = true
        }
        dlDialog = dlg
        if (dlPolling) return
        dlPolling = true
        val poll = object : Runnable {
            override fun run() {
                if (!resumed) return
                val st = Updater.DownloadState
                if (dlDialog?.isShowing != true) {   // 用户已取消或框被收掉
                    dlDialog = null
                    dlPolling = false
                    if (st.running) findViewById<TextView>(R.id.btnUpdate).text = "下载中… · 点击查看"
                    return
                }
                bar.isIndeterminate = st.total <= 0
                if (st.total > 0) bar.progress = (st.done * 100 / st.total).toInt()
                status.text = when {
                    st.source.isNotBlank() && st.done == 0L -> "正在连接 ${st.source}…"
                    st.total > 0 -> "%.1f / %.1f MB".format(st.done / 1048576.0, st.total / 1048576.0)
                    st.done > 0 -> "${st.done / 1024} KB"
                    else -> "正在连接 ${st.source.ifBlank { "…" }}…"
                }
                if (!st.running) {   // 下载结束：收框、处理结果
                    dlDialog?.setOnDismissListener(null)
                    dlDialog?.dismiss()
                    dlDialog = null
                    dlPolling = false
                    findViewById<TextView>(R.id.btnUpdate).text = updateBtnLabel()
                    consumeFinishedDownload()
                    return
                }
                handler.postDelayed(this, 400)
            }
        }
        downloadPoll = poll
        handler.post(poll)
    }

    /** 处理已结束的下载结果（成功→安装/授权引导；失败→toast；取消→沉默）。
     *  completed 标志保证"本进程从没下载过"的空白态不会被误报成失败。 */
    private fun consumeFinishedDownload() {
        if (!resumed) return
        val s = Updater.DownloadState
        if (s.running || !s.completed || s.handled) return
        val apk = s.result
        when {
            s.cancelRequested -> s.handled = true
            apk == null || !apk.exists() -> {
                s.handled = true
                Toast.makeText(this, "下载失败：镜像和直连都不通，换个网络再试", Toast.LENGTH_LONG).show()
            }
            Updater.canInstall(this) -> {
                s.handled = true
                installApk(apk)
            }
            else -> {
                // Android 8+ 首次：先去系统设置授予"安装未知应用"，回来 onResume 自动装。
                // 只引导一次（handled=true + awaitPermission），回来没授权就保持沉默，
                // 用户下次点"更新"会重新走流程。
                s.handled = true
                s.awaitPermission = true
                Toast.makeText(
                    this, "请允许「闭环」安装应用；设置完回到这里会自动继续",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Updater.installPermissionIntent(this))
            }
        }
    }

    private fun installApk(apk: File) {
        if (!Updater.install(this, apk)) {
            Toast.makeText(this, "安装失败：文件可能损坏，请重试", Toast.LENGTH_LONG).show()
        }
    }

    private fun fmt(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
