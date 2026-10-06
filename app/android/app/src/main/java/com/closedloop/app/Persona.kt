package com.closedloop.app

import kotlin.random.Random

/** 人格层 v1：本地模板。语气原则：情绪递进（温柔→担心→失望），绝不愤怒。 */
object Persona {

    private val templates = mapOf(
        1 to listOf(
            "到点啦，{plan}，说好 {target} 分钟的。我等你开始哦。",
            "现在是 {plan} 时间，{target} 分钟，开始吧。",
        ),
        2 to listOf(
            "说好 {plan} 的…你现在在 {cur}，对吧？回来我们继续。",
            "{plan} 才做了 {eff} 分钟，还差 {left}。我在这儿等你。",
        ),
        3 to listOf(
            "{plan} 还差 {left} 分钟。今天第 {count} 次了，上次你说好会做完的。",
            "有点失望。{plan} 的 {target} 分钟，现在只走了 {eff}。",
        ),
        4 to listOf(
            "已经偏了 {dev} 分钟。还要吗？要说实话哦。",
            "{plan} 偏离 {dev} 分钟了。继续，还是今天就这样？",
        ),
    )

    private val doneTexts = listOf(
        "完成了！{plan}，{target} 分钟一分不少。今天赢了。",
        "{plan} 搞定，{target} 分钟全部拿下。给你记上。",
    )

    fun say(level: Int, ctx: Map<String, Any>): String {
        val pool = templates[level] ?: templates.getValue(1)
        return fill(pool.random(Random), ctx)
    }

    fun sayDone(ctx: Map<String, Any>): String = fill(doneTexts.random(Random), ctx)

    private fun fill(template: String, ctx: Map<String, Any>): String {
        var out = template
        for ((k, v) in ctx) out = out.replace("{$k}", v.toString())
        return out
    }
}
