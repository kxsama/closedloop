"""人格层 v1：本地模板 + 变量，离线零成本。

语气原则：情绪递进（温柔→担心→失望），绝不愤怒。
文案纪律（v0.7）：用中立的事实性词汇，不带胜负/道德评判——"切走"不是"偏离"，
完成就是完成，不说"赢了"。产品是镜子，不是裁判。
v2 预留：接 OpenAI 兼容 API（DeepSeek 等）时替换本模块的 say() 即可。
"""
from __future__ import annotations

import random

TEMPLATES = {
    1: [  # 到点：温柔期待
        "到点啦，{plan}，说好 {target} 分钟的。我等你开始哦。",
        "现在是 {plan} 时间，{target} 分钟，开始吧。",
    ],
    2: [  # 首次切走：担心
        "说好 {plan} 的…你现在在 {cur}，对吧？回来我们继续。",
        "{plan} 才做了 {eff} 分钟，还差 {left}。我在这儿等你。",
    ],
    3: [  # 持续切走：失望，不生气
        "{plan} 还差 {left} 分钟。今天第 {count} 次了，上次你说好会做完的。",
        "有点失望。{plan} 的 {target} 分钟，现在只走了 {eff}。",
    ],
    4: [  # 熔断：对话式结算
        "已经切走 {dev} 分钟了。还要吗？要说实话哦。",
        "{plan} 已经切走 {dev} 分钟了。继续，还是今天就这样？",
    ],
}

DONE_TEXTS = [
    "完成了！{plan}，{target} 分钟一分不少。",
    "{plan} 完成，{target} 分钟都记上了。",
]

COOLDOWN_TEXT = "我先不吵你了。晚上复盘的时候再一起算这笔账。"


def say(level: int, ctx: dict) -> str:
    """按层级生成一句人设话术。ctx: plan/target/eff/left/dev/count/cur。"""
    base = {
        "plan": "计划", "target": "?", "eff": 0, "left": "?",
        "dev": 0, "count": 0, "cur": "别的应用",
    }
    base.update({k: v for k, v in ctx.items() if v is not None})
    pool = TEMPLATES.get(level, TEMPLATES[1])
    return random.choice(pool).format(**base)


def say_done(ctx: dict) -> str:
    base = {"plan": "计划", "target": "?"}
    base.update(ctx)
    return random.choice(DONE_TEXTS).format(**base)
