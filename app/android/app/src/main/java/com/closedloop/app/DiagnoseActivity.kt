package com.closedloop.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

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
        setContentView(R.layout.activity_diagnose)
        list = findViewById(R.id.diagList)
        summary = findViewById(R.id.diagSummary)
        findViewById<Button>(R.id.btnRecheck).setOnClickListener { render() }
        render()
    }

    /** 从系统设置回来时重新回读——这是这一页的全部价值所在，别只在 onCreate 查一次。 */
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val items = Health.items(this)
        val problems = items.count { !it.ok }

        if (problems == 0) {
            summary.text = "都打开了。它可以正常工作。"
            summary.setTextColor(0xFF22A06B.toInt())
        } else {
            val core = items.count { it.core && !it.ok }
            summary.text = if (core > 0) "还有 $core 项必须打开" else "还有 $problems 项可以打开"
            summary.setTextColor(if (core > 0) 0xFFD64545.toInt() else 0xFFE8A13A.toInt())
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
            text = if (item.ok) "✓" else "○"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (item.ok) 0xFF22A06B.toInt() else 0xFFE8A13A.toInt())
            width = dp(22)
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
            text = if (item.ok) "已开启" else "去开启 ›"
            textSize = 12f
            setTextColor(if (item.ok) 0xFFB4B2A9.toInt() else 0xFF22A06B.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), 0, dp(4))
            if (!item.ok) setOnClickListener { Health.open(this@DiagnoseActivity, item.key) }
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
        if (!item.ok) box.setOnClickListener { Health.open(this, item.key) }
        return box
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
