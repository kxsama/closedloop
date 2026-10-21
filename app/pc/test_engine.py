"""引擎逻辑无头测试（不依赖 GUI）。

用法：venv\\Scripts\\python.exe test_engine.py
覆盖：完成结算 / 去抖宽限 / 升级时序 L2→L3→COOLDOWN / 冷却回归 /
提前结束 / 熔断 / 偏离整段计入。
"""
import datetime as dt

from engine import (COOLDOWN, DONE, EARLY_END, MISS_SETTLE_SEC, RUNNING, Engine, Plan)

# T0 必须锚定"今天 15:00"——引擎按真实日期计算开始时间，写死日期过几天就全挂
T0 = dt.datetime.now().replace(hour=15, minute=0, second=0, microsecond=0).timestamp()
GOOD = {"proc": "notepad.exe", "title": ""}
BAD = {"proc": "chrome.exe", "title": ""}


def make_plan(mins=10, fuse=1.0):
    return Plan(id="t1", name="测试", start="15:00", target_minutes=mins,
                whitelist_procs=["notepad.exe"], fuse_ratio=fuse)


def run(eng, seconds, fg_of, step=5):
    acts = []
    t = T0
    while t <= T0 + seconds:
        acts += eng.tick(t, fg_of(t))
        t += step
    return acts


def types(acts):
    return [a["type"] for a in acts]


# 1. 全程合规 → 到目标完成
eng = Engine(make_plan(10))
acts = run(eng, 600, lambda t: GOOD)
settles = [a for a in acts if a["type"] == "settle"]
assert "remind" in types(acts)
assert settles and settles[0]["result"] == DONE, settles
assert abs(settles[0]["effective_min"] - 10) < 0.2, settles
assert eng.session.deviation_count == 0
print("1 完成结算 OK")

# 2. 30 秒毛刺不计偏离，且这段时间补记为有效
def fg2(t):
    return BAD if T0 + 100 <= t < T0 + 130 else GOOD

eng = Engine(make_plan(10))
run(eng, 600, fg2)
s = eng.session
assert s.deviation_count == 0 and s.deviation_sec == 0, (s.deviation_count, s.deviation_sec)
assert abs(s.effective_sec - 600) <= 10, s.effective_sec
print("2 去抖宽限 OK")

# 3. 偏离转正 + 升级时序：回查后 L2 → 连续 5 分钟 L3 → 10 分钟冷却
def fg3(t):
    return BAD if t >= T0 + 300 else GOOD

eng = Engine(make_plan(60, fuse=99))
acts = run(eng, 900, fg3)
s = eng.session
assert s.deviation_count == 1, s.deviation_count
assert abs(s.deviation_sec - 600) <= 10, s.deviation_sec
lv = [a for a in acts if a["type"] == "escalate"]
assert lv and lv[0]["level"] == 2, lv
assert any(a["level"] == 3 for a in lv), lv
assert "cooldown_enter" in types(acts)
assert s.state == COOLDOWN
print("3 升级阶梯与冷却 OK")

# 4. 冷却后回归 → 恢复计时
def fg4(t):
    return BAD if T0 + 300 <= t < T0 + 960 else GOOD

eng = Engine(make_plan(60, fuse=99))
acts = run(eng, 1200, fg4)
assert "cooldown_exit" in types(acts)
assert eng.session.state == RUNNING
print("4 冷却回归 OK")

# 5. 提前结束 → 未完成分钟数正确
eng = Engine(make_plan(10))
run(eng, 300, lambda t: GOOD)
acts = eng.early_end("背完了")
st = acts[0]
assert st["result"] == EARLY_END and abs(st["unfinished_min"] - 5.0) < 0.2, st
print("5 提前结束 OK")

# 6. 熔断只触发一次；选择继续后可再次触发
eng = Engine(make_plan(10))
acts = run(eng, 660, lambda t: BAD)
assert len([a for a in acts if a["type"] == "fuse_ask"]) == 1
eng.fuse_continue()
more = []
t = T0 + 665
while t <= T0 + 1320:
    more += eng.tick(t, BAD)
    t += 5
assert any(a["type"] == "fuse_ask" for a in more)
print("6 熔断 OK")

# 7. 偏离转正后整段计入（含宽限期的 60 秒）
def fg7(t):
    return BAD if T0 + 60 <= t < T0 + 130 else GOOD

eng = Engine(make_plan(60, fuse=99))
run(eng, 200, fg7)
s = eng.session
assert s.deviation_count == 1
assert abs(s.deviation_sec - 70) <= 10, s.deviation_sec
print("7 偏离整段计入 OK")

# 8. 锁屏/前台未知：既不计有效也不算偏离
eng = Engine(make_plan(10))
run(eng, 300, lambda t: GOOD)
for i in range(12):          # 60 秒"锁屏"
    eng.tick(T0 + 300 + i * 5, None, locked=True)
run2 = []
t = T0 + 360
while t <= T0 + 600:
    run2 += eng.tick(t, GOOD)
    t += 5
s = eng.session
assert s.deviation_count == 0
assert abs(s.effective_sec - 540) <= 15, s.effective_sec   # 锁屏段未计
print("8 未观测处理 OK")

# 9. 晚到 <60 秒不补记：到点没开始 ≠ 中途毛刺（v0.4 真机实测抓出的回归）
def fg9(t):
    return BAD if t < T0 + 57 else GOOD

eng = Engine(make_plan(10))
run(eng, 600, fg9)
s = eng.session
assert s.deviation_count == 0 and s.deviation_sec == 0, (s.deviation_count, s.deviation_sec)
# 正确：只从第 57 秒进来后开始计时（≈540s）；若宽限误补记会多 60s（≈600s）
assert s.effective_sec <= 555, s.effective_sec
print("9 晚到不补记 OK")

# 10. 错过收敛（v0.8）：开始 3 小时仍没开始 → 自动记"未完成"，且不再追击
eng = Engine(make_plan(30))
acts10 = run(eng, MISS_SETTLE_SEC + 600, lambda t: BAD)   # 一直在别的应用里
s = eng.session
assert s.state == "SETTLED", s.state
assert s.result == "未完成", s.result
assert s.reason == "未开始", s.reason
assert any(a["type"] == "settle" for a in acts10)
# 收敛之后再 tick 不应再产生任何提醒
assert eng.tick(T0 + MISS_SETTLE_SEC + 1200, BAD) == []
print("10 错过收敛 OK")

# 11. L2 可达（v0.3）：到点就没进来，也应当先看到 L2「担心」，而不是直接跳 L3
#     回归对象：升级判定曾整段挂在"到点 +5 分钟"的回查后面，
#     那一刻连续偏离早已 ≥5 分钟，new_level 一次就判 3。
eng = Engine(make_plan(60, fuse=99))
acts11 = run(eng, 420, lambda t: BAD)          # 全程在别的应用里，7 分钟
lv11 = [a for a in acts11 if a["type"] == "escalate"]
assert lv11, "一直没有升档"
assert lv11[0]["level"] == 2, lv11              # 第一次升档必须是 L2
assert any(a["level"] == 3 for a in lv11), lv11  # 之后再到 L3
# L2 出现的时刻应在 1 分钟左右，而不是 5 分钟
print("11 L2 先于 L3 可达 OK")

print("\n全部通过")
