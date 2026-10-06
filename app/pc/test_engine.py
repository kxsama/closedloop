"""引擎逻辑无头测试（不依赖 GUI）。

用法：venv\\Scripts\\python.exe test_engine.py
覆盖：完成结算 / 去抖宽限 / 升级时序 L2→L3→COOLDOWN / 冷却回归 /
提前结束 / 熔断 / 偏离整段计入。
"""
import datetime as dt

from engine import (COOLDOWN, DONE, EARLY_END, RUNNING, Engine, Plan)

T0 = dt.datetime(2026, 10, 5, 15, 0, 0).timestamp()
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

print("\n全部通过")
