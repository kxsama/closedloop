package com.closedloop.app

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
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

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
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
        findViewById<Button>(R.id.btnNew).setOnClickListener { showNewPlanDialog() }

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
        dateView.text = LocalDate.now().let { "${it.monthValue}月${it.dayOfMonth}日" }
        val plans = PlanStore.loadPlans(this)
        plansContainer.removeAllViews()
        if (plans.isEmpty()) {
            val tv = TextView(this).apply {
                text = "还没有计划，点下方按钮给自己定一个"
                setTextColor(SUB); textSize = 12f
                setPadding(8, 24, 8, 24)
            }
            plansContainer.addView(tv)
        }
        val now = System.currentTimeMillis() / 1000
        for (p in plans) {
            plansContainer.addView(planRow(p, now))
        }
        renderReview(plans)
        maybeShowFuseDialog()
    }

    private fun planRow(p: Plan, now: Long): View {
        val eng = MonitorService.engines[p.id]
        val st = eng?.status(now)
        val state = st?.get("state") as? String ?: "WAITING"
        val level = (st?.get("level") as? Int) ?: 0
        val eff = st?.get("effectiveSec") as? Double ?: 0.0
        val target = st?.get("targetSec") as? Double ?: (p.targetMinutes * 60.0)
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
            text = p.start; textSize = 20f; setTextColor(INK)
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
            ((now - p.startEpoch(LocalDate.now())) / 60).toInt() else 0
        when {
            state == "RUNNING" && missedMin > 0 -> {
                chip.text = "已到点·错过 ${missedMin} 分"
                chip.setTextColor(0xFF8A5A00.toInt())
                chip.setBackgroundColor(0xFFFBF3E2.toInt())
            }
            state == "RUNNING" && deviating -> {
                chip.text = "偏离中"
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
            var t = "目标 ${p.targetMinutes} 分钟"
            val devMin = ((st?.get("deviationSec") as? Double) ?: 0.0).toInt() / 60
            val devCnt = (st?.get("deviationCount") as? Int) ?: 0
            if (devMin > 0 || devCnt > 0) t += "｜偏离 ${devMin} 分 × ${devCnt} 次"
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
            lines.add("✓ $name：$result$reason｜有效 $eff/$target 分｜偏离 $devMin 分 × $devCnt 次")
        }
        val now = System.currentTimeMillis() / 1000
        for (p in plans) {
            val eng = MonitorService.engines[p.id]
            if (eng == null || eng.session.state == SState.WAITING) {
                lines.add("· ${p.name}（${p.start} 开始）")
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
            .setMessage("「${eng.plan.name}」累计偏离已达上限。还要吗？")
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

    private fun showNewPlanDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_plan, null)
        val editName = view.findViewById<EditText>(R.id.editName)
        val btnTime = view.findViewById<Button>(R.id.btnTime)
        val pickerDur = view.findViewById<NumberPicker>(R.id.pickerDur)
        val editCustom = view.findViewById<EditText>(R.id.editCustom)
        val btnApps = view.findViewById<Button>(R.id.btnApps)

        var timeStr = run {
            val now = java.time.LocalTime.now().plusMinutes(2)
            "%02d:%02d".format(now.hour, now.minute)
        }
        btnTime.text = "开始时间：$timeStr"
        btnTime.setOnClickListener {
            // 大号双滚轮时间选择（替代系统 Holo TimePicker，滚轮太窄不好用）
            val v = LayoutInflater.from(this).inflate(R.layout.dialog_time, null)
            val hourPicker = v.findViewById<NumberPicker>(R.id.pickerHour)
            val minPicker = v.findViewById<NumberPicker>(R.id.pickerMinute)
            hourPicker.minValue = 0
            hourPicker.maxValue = 23
            hourPicker.displayedValues = (0..23).map { "%02d".format(it) }.toTypedArray()
            minPicker.minValue = 0
            minPicker.maxValue = 59
            minPicker.displayedValues = (0..59).map { "%02d".format(it) }.toTypedArray()
            val parts = timeStr.split(":")
            hourPicker.value = parts[0].toInt()
            minPicker.value = parts[1].toInt()
            AlertDialog.Builder(this)
                .setTitle("选择开始时间")
                .setView(v)
                .setPositiveButton("确定") { _, _ ->
                    timeStr = "%02d:%02d".format(hourPicker.value, minPicker.value)
                    btnTime.text = "开始时间：$timeStr"
                }
                .show().also { frost(it) }
        }

        val durLabels = (DURATIONS.map { "$it 分钟" } + "自定义").toTypedArray()
        pickerDur.minValue = 0
        pickerDur.maxValue = durLabels.size - 1
        pickerDur.displayedValues = durLabels
        pickerDur.value = 1
        pickerDur.setOnValueChangedListener { _, _, newVal ->
            // editCustom 常驻占位（invisible），切换时布局不跳动
            editCustom.visibility =
                if (newVal == durLabels.size - 1) View.VISIBLE else View.INVISIBLE
        }

        val installed = launchableApps()
        val chosen = linkedSetOf<String>()  // 包名集合，保留选择顺序
        btnApps.setOnClickListener {
            showAppPicker(installed, chosen) {
                val names = chosen.mapNotNull { pkg -> installed.firstOrNull { it.pkg == pkg }?.label }
                btnApps.text = if (names.isEmpty()) "选择关联应用"
                else names.take(3).joinToString("、") + if (names.size > 3) " 等${names.size}个" else ""
                // 计划名默认跟随第一个关联应用；名称栏没输入过内容才更新占位提示
                if (editName.text.isBlank() && names.isNotEmpty()) {
                    editName.hint = names.first()
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("新建计划")
            .setView(view)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .show()
            .also { dialog ->
                frost(dialog)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    // 名称为空 → 用第一个关联应用名；再空才拦
                    val name = editName.text.toString().trim().ifBlank {
                        editName.hint?.toString()?.trim().orEmpty()
                    }
                    val durIdx = pickerDur.value
                    val mins = if (durIdx == durLabels.size - 1) {
                        editCustom.text.toString().toIntOrNull() ?: 0
                    } else DURATIONS[durIdx]
                    val packages = chosen.toMutableList()
                    when {
                        name.isEmpty() || name == "计划名称（留空则用应用名）" ->
                            Toast.makeText(this, "先选一个关联应用，或填个名称", Toast.LENGTH_SHORT).show()
                        mins !in 1..999 ->
                            Toast.makeText(this, "时长需为 1-999 分钟", Toast.LENGTH_SHORT).show()
                        packages.isEmpty() ->
                            Toast.makeText(this, "至少选一个关联应用", Toast.LENGTH_SHORT).show()
                        else -> {
                            val plan = Plan(
                                id = UUID.randomUUID().toString().take(8),
                                name = name, start = timeStr, targetMinutes = mins,
                                packages = packages,
                            )
                            PlanStore.addPlan(this, plan)
                            MonitorService.start(this)
                            dialog.dismiss()
                            refresh()
                        }
                    }
                }
            }
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
