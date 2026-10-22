package com.closedloop.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 体检页（v0.9）。
 *
 * 设计取向：不做成"一连串权限弹窗"，而做成一张**能看见自己进度的清单**——
 * 每项写清"为什么要它"、右侧一个「去开启」，回到本页自动回读状态。
 * 这样用户的感受是"我把它调好了"，而不是"我被要求了一堆事"。
 *
 * 用 Activity 而不是 Dialog，是因为要跳系统设置再回来，`onResume` 必须能重查。
 */
class DiagnoseActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_diagnose)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        list = findViewById(R.id.diagList)
        summary = findViewById(R.id.diagSummary)
        findViewById<Button>(R.id.btnRecheck).setOnClickListener { render() }
        findViewById<Button>(R.id.btnDone).apply {
            backgroundTintList = null
            setOnClickListener { finish() }
        }
        render()
    }

    /** 从系统设置回来时重新回读——这是这一页的全部价值所在，别只在 onCreate 查一次。 */
    override fun onResume() {
        super.onResume()
        if (PlanStore.monitoringEnabled(this) && MonitorService.hasUsageAccess(this) && !MonitorService.running) {
            MonitorService.start(this)
            list.postDelayed({ if (!isFinishing && !isDestroyed) render() }, 500)
        }
        render()
    }

    private fun render() {
        val items = Health.items(this)
        val problems = items.count { it.core && !it.ok }

        if (!PlanStore.monitoringEnabled(this)) {
            summary.text = "监测已停止，点「恢复监测」后才会提醒。"
            summary.setTextColor(0xFF708078.toInt())
        } else if (problems == 0) {
            summary.text = "提醒所需的运行设置已就绪"
            summary.setTextColor(0xFF22A06B.toInt())
        } else {
            summary.text = "还有 $problems 项运行设置需要处理"
            summary.setTextColor(0xFF8A652C.toInt())
        }

        list.removeAllViews()
        for (it in items) list.addView(row(it))
    }

    private fun row(item: Health.Item): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(9) }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左侧状态标记：用文字而不是图标，省一个资源、也不会在小字号下糊
        val mark = TextView(this).apply {
            text = if (item.manual) "手动" else if (item.ok) "✓" else "○"
            textSize = if (item.manual) 10f else 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (item.ok) 0xFF22A06B.toInt() else 0xFFE8A13A.toInt())
            width = dp(32)
        }
        top.addView(mark)

        top.addView(TextView(this).apply {
            text = item.title
            textSize = 14f
            setTextColor(0xFF1F1E1B.toInt())
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val action = TextView(this).apply {
            text = when {
                item.manual -> "系统设置 ›"
                item.ok -> "已开启"
                item.key == Health.MONITOR -> "恢复监测 ›"
                else -> "去开启 ›"
            }
            textSize = 12f
            setTextColor(if (item.ok) 0xFFB4B2A9.toInt() else 0xFF22A06B.toInt())
            setTypeface(null, Typeface.BOLD)
            minHeight = dp(48)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, 0, 0)
            if (!item.ok || item.manual) setOnClickListener { openItem(item) }
        }
        top.addView(action)

        box.addView(top)
        box.addView(TextView(this).apply {
            text = item.why
            textSize = 11f
            setTextColor(if (item.ok) 0xFFB4B2A9.toInt() else 0xFF6B6A66.toInt())
            setPadding(dp(22), dp(4), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        // 整行可点：小屏上"去开启"这几个字未必好按，整块热区更宽容
        if (!item.ok || item.manual) box.setOnClickListener { openItem(item) }
        if (item.manual) {
            box.addView(CheckBox(this).apply {
                text = if (item.ok) "你已手动确认开启" else "我已在系统中开启"
                textSize = 12f
                minHeight = dp(48)
                setTextColor(0xFF207B5E.toInt())
                isChecked = item.ok
                setOnCheckedChangeListener { _, checked ->
                    PlanStore.confirmAutostart(this@DiagnoseActivity, checked)
                    render()
                }
            })
        }
        return box
    }

    private fun openItem(item: Health.Item) {
        Health.open(this, item.key)
        if (item.key == Health.MONITOR) list.postDelayed({ if (!isFinishing && !isDestroyed) render() }, 500)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
