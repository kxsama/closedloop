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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.Collator
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val GREEN = 0xFF22A06B.toInt()
        private const val GREEN_DARK = 0xFF177A50.toInt()
        private const val WARN = 0xFFE8A13A.toInt()
        private const val DANGER = 0xFFD64545.toInt()
        private const val INK = 0xFF1F1E1B.toInt()
        private const val SUB = 0xFF6B6A66.toInt()
        private const val IDLE = 0xFFC9C7C0.toInt()
        private val DURATIONS = listOf(5, 10, 15, 20, 30, 45, 60, 90)
        private val REASONS = arrayOf("背完了", "今天状态不行", "计划定得不合理", "有突发")
    }

    private lateinit var plansContainer: LinearLayout
    private lateinit var reviewView: TextView
    private lateinit var dateView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var fuseDialogShowing = false

    /** 下载进度框（当前实例的）；状态本体在 Updater.DownloadState，切后台回来重挂 */
    private var dlDialog: AlertDialog? = null
    private var dlPolling = false

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 3000)   // 与服务 3 秒轮询对齐；tick 回调也会触发刷新
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        findViewById<Button>(R.id.btnNew).setOnClickListener { showPlanDialog() }
        findViewById<TextView>(R.id.btnUpdate).apply {
            setOnClickListener { checkUpdate() }
            text = updateBtnLabel()
        }

        Notify.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
        MonitorService.onTick = { runOnUiThread { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        // 下载中切后台再回来 → 重挂进度框（状态在 DownloadState，丢不了）
        if (Updater.DownloadState.running) showDownloadDialog()
        // 授权页回来了且已允许 → 自动接着装
        if (Updater.DownloadState.awaitPermission && Updater.canInstall(this)) {
            Updater.DownloadState.awaitPermission = false
            Updater.DownloadState.result?.let { installApk(it) }
        }
        // 下载已结束还没处理（比如人不在时下完的）→ 接着走安装/报错
        consumeFinishedDownload()
        if (!MonitorService.hasUsageAccess(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要一个关键权限")
                .setMessage("「使用情况访问」权限让闭环知道你此刻在哪个应用里——这是整个验收机制的地基。数据只留在本机。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("以后再说", null)
                .show()
        } else {
            MonitorService.start(this)
        }
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    // ---------- 渲染 ----------

    private fun refresh() {
        val today = LocalDate.now()
        dateView.text = today.let { "${it.monthValue}月${it.dayOfMonth}日" }
        val plans = PlanStore.loadPlans(this)
        val shown = plans.filter { it.appliesOn(today) }   // v0.8：只显示今天适用的
        plansContainer.removeAllViews()
        if (shown.isEmpty()) {
            val tv = TextView(this).apply {
                text = if (plans.isEmpty()) "还没有计划，点下方按钮给自己定一个"
                else "今天没有安排。\n" + nextOccurrenceText(plans)
                setTextColor(SUB); textSize = 12f
                setPadding(8, 24, 8, 24)
            }
            plansContainer.addView(tv)
        }
        val now = System.currentTimeMillis() / 1000
        for (p in shown) {
            plansContainer.addView(planRow(p, now))
        }
        renderReview(shown)
        maybeShowFuseDialog()
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
        // 按天排期后，卡片显示的是"今天这一档"的时间和时长（v0.8）
        val day = LocalDate.now()
        val tp = p.forDay(day)
        val eng = MonitorService.engines[p.id]
        val st = eng?.status(now)
        val state = st?.get("state") as? String ?: "WAITING"
        val level = (st?.get("level") as? Int) ?: 0
        val eff = st?.get("effectiveSec") as? Double ?: 0.0
        val target = st?.get("targetSec") as? Double ?: (tp.targetMinutes * 60.0)
        val deviating = st?.get("deviating") as? Boolean ?: false
        val result = st?.get("result") as? String ?: ""

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            setBackgroundResource(R.drawable.bg_card)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = 14
            layoutParams = lp
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val timeTv = TextView(this).apply {
            text = tp.start; textSize = 20f; setTextColor(INK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        head.addView(timeTv)
        val nameTv = TextView(this).apply {
            text = "  ${p.name}"; textSize = 13f; setTextColor(SUB)
            setPadding(12, 10, 0, 0)
        }
        head.addView(nameTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chip = TextView(this).apply {
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(18, 6, 18, 6)
        }
        val missedMin = if (state == "RUNNING" && eff < 5)
            ((now - tp.startEpoch(day)) / 60).toInt() else 0
        when {
            state == "RUNNING" && missedMin > 0 -> {
                chip.text = "已到点·错过 ${missedMin} 分"
                chip.setTextColor(0xFF8A5A00.toInt())
                chip.setBackgroundColor(0xFFFBF3E2.toInt())
            }
            state == "RUNNING" && deviating -> {
                chip.text = "已切走"
                chip.setTextColor(if (level >= 3) DANGER else 0xFF8A5A00.toInt())
                chip.setBackgroundColor(if (level >= 3) 0xFFFBEAEA.toInt() else 0xFFFBF3E2.toInt())
            }
            state == "RUNNING" -> {
                chip.text = "计时中"
                chip.setTextColor(GREEN_DARK)
                chip.setBackgroundColor(0xFFE9F5EE.toInt())
            }
            state == "COOLDOWN" -> {
                chip.text = "沉默中"; chip.setTextColor(DANGER)
                chip.setBackgroundColor(0xFFFBEAEA.toInt())
            }
            state == "SETTLED" -> {
                chip.text = result.ifBlank { "已结算" }
                chip.setTextColor(SUB); chip.setBackgroundColor(0xFFF1F0EC.toInt())
            }
            else -> {
                chip.text = "待开始"; chip.setTextColor(SUB)
                chip.setBackgroundColor(0xFFF1F0EC.toInt())
            }
        }
        head.addView(chip)
        card.addView(head)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = target.toInt()
            progress = eff.toInt().coerceIn(0, max)
            val color = when {
                state == "SETTLED" -> if (result == "完成") GREEN else IDLE
                state == "RUNNING" && deviating -> if (level >= 3) DANGER else WARN
                state == "COOLDOWN" -> DANGER
                state == "RUNNING" -> GREEN
                else -> IDLE
            }
            progressTintList = ColorStateList.valueOf(color)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 14
            )
            lp.topMargin = 16
            layoutParams = lp
        }
        card.addView(bar)

        val ratio = TextView(this).apply {
            text = if (state == "RUNNING" || state == "COOLDOWN")
                "${fmt(eff)} / ${fmt(target)}"
            else "目标 ${fmt(target)}"
            textSize = 11f; setTextColor(INK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 0)
        }
        card.addView(ratio)

        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val detail = TextView(this).apply {
            var t = "目标 ${tp.targetMinutes} 分钟"
            val devMin = ((st?.get("deviationSec") as? Double) ?: 0.0).toInt() / 60
            val devCnt = (st?.get("deviationCount") as? Int) ?: 0
            if (devMin > 0 || devCnt > 0) t += "｜切走 ${devMin} 分 × ${devCnt} 次"
            text = t; textSize = 10f; setTextColor(SUB)
        }
        foot.addView(detail, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (state == "RUNNING" || state == "COOLDOWN") {
            val btnEnd = Button(this).apply {
                text = "提前结束"; textSize = 10f
                setTextColor(SUB); setBackgroundColor(0xFFF1F0EC.toInt())
                setOnClickListener { showEarlyEndDialog(p) }
            }
            foot.addView(btnEnd)
        }
        val btnEdit = Button(this).apply {
            text = "编辑"; textSize = 10f
            setTextColor(SUB); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPlanDialog(p) }
        }
        foot.addView(btnEdit)
        val btnDel = Button(this).apply {
            text = "删除"; textSize = 10f
            setTextColor(DANGER); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                PlanStore.deletePlan(this@MainActivity, p.id)
                MonitorService.engines.remove(p.id)
                refresh()
            }
        }
        foot.addView(btnDel)
        card.addView(foot)
        return card
    }

    private fun renderReview(plans: List<Plan>) {
        val today = LocalDate.now().toString()
        val lines = mutableListOf<String>()
        for (rec in PlanStore.loadLedger(this, today)) {
            val name = rec["name"] ?: "?"
            val result = rec["result"] ?: "?"
            val eff = (rec["effectiveMin"] as? Double)?.toInt() ?: 0
            val target = (rec["target"] as? Double)?.toInt() ?: 0
            val devMin = (rec["deviationMin"] as? Double)?.toInt() ?: 0
            val devCnt = (rec["deviationCount"] as? Double)?.toInt() ?: 0
            val reason = rec["reason"]?.toString()?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
            lines.add("✓ $name：$result$reason｜有效 $eff/$target 分｜切走 $devMin 分 × $devCnt 次")
        }
        val now = System.currentTimeMillis() / 1000
        for (p in plans) {
            val eng = MonitorService.engines[p.id]
            if (eng == null || eng.session.state == SState.WAITING) {
                lines.add("· ${p.name}（${p.slotOf(LocalDate.now()).start} 开始）")
            }
        }
        if (lines.isEmpty()) lines.add("今天还没有结算记录。计划完成后会出现在这里。")
        reviewView.text = lines.joinToString("\n")
    }

    // ---------- 对话框 ----------

    private fun showEarlyEndDialog(p: Plan) {
        AlertDialog.Builder(this)
            .setTitle("提前结束「${p.name}」？")
            .setItems(REASONS) { _, which ->
                val eng = MonitorService.engines[p.id] ?: return@setItems
                handleSettleActs(eng, eng.earlyEnd(REASONS[which]))
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
        fuseDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("熔断询问")
            .setMessage("「${eng.plan.name}」累计切走时间已达上限。还要吗？")
            .setPositiveButton("继续") { _, _ ->
                eng.fuseContinue()
                MonitorService.pendingFusePlanId = null
                fuseDialogShowing = false
            }
            .setNegativeButton("今天算了") { _, _ ->
                handleSettleActs(eng, eng.abandon())
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
        val np = NumberPicker(this).apply {
            minValue = 1
            maxValue = 999
            value = initial.coerceIn(1, 999)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(16), dp(20), 0)
            addView(np)
        }
        AlertDialog.Builder(this)
            .setTitle("选择时长（分钟）")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ -> onPicked(np.value) }
            .setNegativeButton("取消", null)
            .show().also { frost(it) }
    }

    /** 小标签：选中态绿底绿字，未选中灰底 */
    private fun chipView(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(7), 0, dp(7))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isClickable = true
            if (selected) {
                setTextColor(GREEN_DARK)
                setBackgroundColor(0xFFE9F5EE.toInt())
            } else {
                setTextColor(SUB)
                setBackgroundColor(0xFFF1F0EC.toInt())
            }
            setOnClickListener { onClick() }
        }

    /** existing == null → 新建；否则编辑。id 不变，历史账本不断。 */
    private fun showPlanDialog(existing: Plan? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_plan, null)
        val editName = view.findViewById<EditText>(R.id.editName)
        val btnTime = view.findViewById<Button>(R.id.btnTime)
        val pickerDur = view.findViewById<NumberPicker>(R.id.pickerDur)
        val editCustom = view.findViewById<EditText>(R.id.editCustom)
        val btnApps = view.findViewById<Button>(R.id.btnApps)
        val dayRow = view.findViewById<LinearLayout>(R.id.dayRow)
        val weekRow = view.findViewById<LinearLayout>(R.id.weekRow)
        val weekHint = view.findViewById<TextView>(R.id.weekHint)
        val btnPerDay = view.findViewById<Button>(R.id.btnPerDay)
        val perDayHint = view.findViewById<TextView>(R.id.perDayHint)

        val today = LocalDate.now()

        // 基准时间/时长：编辑时取"今天那一档"；新建时默认 2 分钟后 + 10 分钟
        var timeStr = if (existing != null) existing.forDay(today).start else {
            val n = java.time.LocalTime.now().plusMinutes(2)
            "%02d:%02d".format(n.hour, n.minute)
        }
        val baseMinutes = existing?.forDay(today)?.targetMinutes ?: 10

        // 工作副本：7 个槽位。勾选只是批量套用，不存在第二套"重复规则"
        val slots: MutableMap<Int, DaySlot> = existing?.slots
            ?.mapValues { (_, s) -> DaySlot(s.on, s.start, s.minutes) }
            ?.toMutableMap()
            ?: (1..7).associateWith { DaySlot(true, timeStr, baseMinutes) }.toMutableMap()

        // 与基准不同的天 = 已微调；改"默认时间/时长"时不覆盖它们
        val customized = mutableSetOf<Int>()
        for (d in 1..7) {
            val s = slots[d] ?: continue
            if (s.start != timeStr || s.minutes != baseMinutes) customized.add(d)
        }
        var weekMode = existing?.weekMode ?: "all"

        if (existing != null) {
            editName.setText(existing.name)
            editName.setSelection(editName.text.length)
        }
        val installed = launchableApps()
        val chosen = linkedSetOf<String>()
        existing?.packages?.let { chosen.addAll(it) }

        fun appsLabel(): String {
            val names = chosen.mapNotNull { pkg -> installed.firstOrNull { it.pkg == pkg }?.label }
            return if (names.isEmpty()) "选择关联应用"
            else names.take(3).joinToString("、") + if (names.size > 3) " 等${names.size}个" else ""
        }

        btnApps.text = appsLabel()

        val durLabels = (DURATIONS.map { "$it 分钟" } + "自定义").toTypedArray()
        pickerDur.minValue = 0
        pickerDur.maxValue = durLabels.size - 1
        pickerDur.displayedValues = durLabels
        val baseIdx = DURATIONS.indexOf(baseMinutes)
        pickerDur.value = if (baseIdx >= 0) baseIdx else durLabels.size - 1
        if (baseIdx < 0) {
            editCustom.visibility = View.VISIBLE
            editCustom.setText(baseMinutes.toString())
        } else {
            editCustom.visibility = View.INVISIBLE
        }

        fun currentBaseMinutes(): Int = if (pickerDur.value == durLabels.size - 1) {
            editCustom.text.toString().toIntOrNull() ?: baseMinutes
        } else {
            DURATIONS[pickerDur.value]
        }

        /** 把默认时间/时长套到"没微调过"的天上 */
        fun syncBase() {
            val m = currentBaseMinutes()
            for (d in 1..7) {
                if (d in customized) continue
                slots[d]?.start = timeStr
                slots[d]?.minutes = m
            }
        }

        fun updatePerDayHint() {
            perDayHint.text = if (customized.isEmpty()) {
                "未微调：勾选的天都用默认时间/时长"
            } else {
                "已单独设置：" + customized.sorted().joinToString("、") { "周" + DAY_LABELS[it - 1] }
            }
        }

        fun paintDays() {
            dayRow.removeAllViews()
            for (d in 1..7) {
                val onNow = slots[d]?.on == true
                val tv = chipView(DAY_LABELS[d - 1], onNow) {
                    slots[d]?.on = !onNow
                    paintDays()
                    updatePerDayHint()
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp(4)
                tv.layoutParams = lp
                dayRow.addView(tv)
            }
        }

        fun paintWeek() {
            weekRow.removeAllViews()
            for ((mode, label) in listOf("all" to "每天", "odd" to "单周", "even" to "双周")) {
                val tv = chipView(label, weekMode == mode) {
                    weekMode = mode
                    paintWeek()
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp(4)
                tv.layoutParams = lp
                weekRow.addView(tv)
            }
            val thisWeek = if (weekIsOdd(LocalDate.now())) "单周" else "双周"
            weekHint.text = "本周（含今天）是$thisWeek；单/双周是在勾选的基础上再筛一层"
        }

        btnTime.text = "默认开始时间：$timeStr"
        btnTime.setOnClickListener {
            pickTime(timeStr) { t ->
                timeStr = t
                btnTime.text = "默认开始时间：$timeStr"
                syncBase()
            }
        }

        pickerDur.setOnValueChangedListener { _, _, newVal ->
            editCustom.visibility =
                if (newVal == durLabels.size - 1) View.VISIBLE else View.INVISIBLE
            syncBase()
        }

        btnApps.setOnClickListener {
            showAppPicker(installed, chosen) {
                btnApps.text = appsLabel()
                // 名称栏没输入过内容才更新占位提示
                if (editName.text.isBlank() && chosen.isNotEmpty()) {
                    installed.firstOrNull { it.pkg == chosen.first() }?.label?.let {
                        editName.hint = it
                    }
                }
            }
        }

        btnPerDay.setOnClickListener {
            syncBase()
            showPerDayDialog(slots, customized, timeStr, currentBaseMinutes()) {
                paintDays()
                updatePerDayHint()
            }
        }

        paintDays()
        paintWeek()
        updatePerDayHint()

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建计划" else "编辑计划")
            .setView(view)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .show()
            .also { dialog ->
                frost(dialog)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = editName.text.toString().trim().ifBlank {
                        editName.hint?.toString()?.trim().orEmpty()
                    }
                    val mins = currentBaseMinutes()
                    val packages = chosen.toMutableList()
                    when {
                        name.isEmpty() || name == "计划名称（留空则用应用名）" ->
                            Toast.makeText(this, "先选一个关联应用，或填个名称", Toast.LENGTH_SHORT).show()
                        mins !in 1..999 ->
                            Toast.makeText(this, "时长需为 1-999 分钟", Toast.LENGTH_SHORT).show()
                        packages.isEmpty() ->
                            Toast.makeText(this, "至少选一个关联应用", Toast.LENGTH_SHORT).show()
                        (1..7).none { slots[it]?.on == true } ->
                            Toast.makeText(this, "至少勾选一天", Toast.LENGTH_SHORT).show()
                        else -> {
                            syncBase()
                            val plan = existing?.copy(
                                name = name,
                                start = timeStr,
                                targetMinutes = mins,
                                packages = packages,
                                slots = slots,
                                weekMode = weekMode,
                            ) ?: Plan(
                                id = UUID.randomUUID().toString().take(8),
                                name = name,
                                start = timeStr,
                                targetMinutes = mins,
                                packages = packages,
                                slots = slots,
                                weekMode = weekMode,
                            )
                            if (existing == null) PlanStore.addPlan(this, plan)
                            else PlanStore.updatePlan(this, plan)
                            // 触发 reloadEngines：改了时间/时长/排期都会重建对应引擎
                            MonitorService.start(this)
                            dialog.dismiss()
                            refresh()
                        }
                    }
                }
            }
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
        val btn = findViewById<TextView>(R.id.btnUpdate)
        btn.text = "检查中…"
        Thread {
            val rel = Updater.check()
            runOnUiThread {
                btn.text = updateBtnLabel()
                when {
                    rel == null ->
                        Toast.makeText(this, "检查失败：网络不通或 GitHub 访问受限", Toast.LENGTH_LONG).show()
                    !Updater.newerThan(rel.versionName, Updater.localVersionName(this)) ->
                        Toast.makeText(this, "已是最新版｜当前 v${localVersion()}", Toast.LENGTH_SHORT).show()
                    else -> showUpdateDialog(rel)
                }
            }
        }.start()
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
        handler.post(poll)
    }

    /** 处理已结束的下载结果（成功→安装/授权引导；失败→toast；取消→沉默）。
     *  completed 标志保证"本进程从没下载过"的空白态不会被误报成失败。 */
    private fun consumeFinishedDownload() {
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

    private fun handleSettleActs(eng: Engine, acts: List<Act>) {
        for (a in acts) {
            if (a is Act.Settle) {
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
                ))
                Notify.nagCancel(this)
            }
        }
    }

    private fun fmt(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
