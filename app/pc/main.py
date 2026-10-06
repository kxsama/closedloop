"""入口：装配存储、引擎、前台检测、人格层与 UI，主循环每 3 秒一跳。"""
from __future__ import annotations

import datetime as dt
import os
import time
import tkinter as tk
import uuid
from dataclasses import asdict

import engine as E
import monitor
import persona
from store import Store
from ui import MainWindow
from windows import (FuseDialog, InfoDialog, NagWindow, ReasonDialog,
                     STYLE_DONE, STYLE_INFO)

BASE = os.path.dirname(os.path.abspath(__file__))
TICK_MS = 1000
SNOOZE_SEC = 120

store = Store(os.path.join(BASE, "data"))
today = dt.date.today()

plans: list[E.Plan] = []
engines: dict[str, E.Engine] = {}
nag_text_key = None
nag_snooze_until = 0.0


# ---------- 计划与引擎 ----------
def load_plans() -> None:
    global plans
    plans = [E.Plan(**p) for p in store.load("plans.json", [])]


def save_plans() -> None:
    store.save("plans.json", [asdict(p) for p in plans])


def engine_for(plan: E.Plan) -> E.Engine:
    if plan.id not in engines:
        raw = store.load("sessions.json", {}).get(plan.id)
        sess = E.Session(**raw) if raw else None
        engines[plan.id] = E.Engine(plan, session=sess, today=today)
    return engines[plan.id]


def persist() -> None:
    store.save("sessions.json",
               {pid: asdict(e.session) for pid, e in engines.items()})


# ---------- 人设上下文 ----------
def ctx_of(plan: E.Plan, st: dict) -> dict:
    return {"plan": plan.name, "target": plan.target_minutes,
            "eff": st["effective_min"], "left": st["left_min"],
            "dev": st["deviation_min"], "count": st["deviation_count"],
            "cur": st.get("cur_proc") or "别的应用"}


# ---------- 账本 ----------
def write_ledger(plan: E.Plan, eng: E.Engine, act: dict) -> None:
    ledger = store.load("ledger.json", {})
    day = ledger.setdefault(eng.session.date, {})
    day[plan.id] = {
        "name": plan.name, "result": act["result"],
        "target": plan.target_minutes,
        "effective_min": act["effective_min"],
        "deviation_min": act["deviation_min"],
        "deviation_count": act["deviation_count"],
        "unfinished_min": act["unfinished_min"],
        "reason": act.get("reason"),
        "gaps": eng.session.not_observed,
    }
    store.save("ledger.json", ledger)


# ---------- 结算 ----------
def settle_acts(plan: E.Plan, eng: E.Engine, acts: list) -> None:
    for a in acts:
        if a["type"] != "settle":
            continue
        write_ledger(plan, eng, a)
        nag.hide()
        if a["result"] == E.DONE:
            InfoDialog(root, "完成",
                       persona.say_done({"plan": plan.name,
                                         "target": plan.target_minutes}),
                       STYLE_DONE)
        elif a["result"] == E.EARLY_END:
            InfoDialog(root, "已结算",
                       f"记下了：{a.get('reason')}。"
                       f"未完成 {a['unfinished_min']} 分钟——补不补，复盘里再定。",
                       STYLE_INFO)


# ---------- 动作分发 ----------
def handle(plan: E.Plan, eng: E.Engine, acts: list) -> None:
    global nag_text_key
    st = eng.status()
    ctx = ctx_of(plan, st)
    for a in acts:
        t = a["type"]
        store.append_event({"plan": plan.id, "action": t,
                            **{k: v for k, v in a.items() if k != "type"}})
        if t == "remind":
            nag_text_key = None
            InfoDialog(root, "到点了", persona.say(1, ctx), STYLE_INFO, "开始吧")
        elif t == "escalate":
            nag_text_key = None
        elif t == "cooldown_enter":
            nag.hide()
        elif t == "fuse_ask":
            FuseDialog(
                root, persona.say(4, ctx),
                on_continue=eng.fuse_continue,
                on_abandon=lambda eng=eng, plan=plan: settle_acts(
                    plan, eng, eng.abandon()))
        elif t == "settle":
            settle_acts(plan, eng, [a])


# ---------- 催促窗 ----------
def update_nag(now: float) -> None:
    global nag_text_key
    best = None
    for p in plans:
        eng = engines.get(p.id)
        if eng and eng.session.state == E.RUNNING:
            lvl = eng.session.level or 1
            if best is None or lvl > best[0]:
                best = (lvl, p, eng)
    if best is None:
        nag.hide()
        return
    lvl, p, eng = best
    st = eng.status()
    # 挂机提醒：合规计时中但 3 分钟无键鼠输入——只提醒，不停表（读长文也会误报，温和处理）
    idle = monitor.idle_seconds()
    if idle > 180 and not st["deviating"]:
        key = (p.id, "idle")
        if key != nag_text_key:
            nag_text_key = key
            nag.update(1,
                       f"你好像 {int(idle // 60)} 分钟没操作了——「{p.name}」的计时还在走。"
                       f"如果只是挂着，记得诚实结束。",
                       f"有效 {st['effective_min']} / 剩 {st['left_min']} 分")
        nag.show()
        return
    key = (p.id, lvl, st["deviation_min"], st["effective_min"])
    if key != nag_text_key:
        nag_text_key = key
        nag.update(max(lvl, 1), persona.say(max(lvl, 1), ctx_of(p, st)),
                   f"有效 {st['effective_min']} / 剩 {st['left_min']} 分"
                   f" · 偏离 {st['deviation_min']} 分")
    if now < nag_snooze_until and lvl < 3:
        nag.hide()
    else:
        nag.show()


def snooze() -> None:
    global nag_snooze_until
    nag_snooze_until = time.time() + SNOOZE_SEC


def active_plan_id():
    for p in plans:
        eng = engines.get(p.id)
        if eng and eng.session.state == E.RUNNING:
            return p.id
    return None


# ---------- 复盘 ----------
def review_text(statuses: dict) -> str:
    lines = []
    day = store.load("ledger.json", {}).get(today.isoformat(), {})
    for rec in day.values():
        reason = f"（{rec['reason']}）" if rec.get("reason") else ""
        extra = (f"，未完成 {rec['unfinished_min']} 分"
                 if rec.get("result") != "完成" else "")
        lines.append(f"■ {rec['name']}：{rec['result']}{reason}｜"
                     f"有效 {rec['effective_min']}/{rec['target']} 分｜"
                     f"偏离 {rec['deviation_min']} 分 × {rec['deviation_count']} 次{extra}")
        gaps_min = round(sum(e - s for s, e in rec.get("gaps", [])) / 60, 1)
        if gaps_min:
            lines.append(f"  未观测 {gaps_min} 分钟（服务中断，未计入偏离）")
    for p in plans:
        st = statuses.get(p.id, {})
        if st.get("state") == "WAITING":
            lines.append(f"· {p.name}（{p.start} 开始，待开始）")
    if not lines:
        lines.append("今天还没有结算记录，计划完成或结束后会出现在这里。")
    return "\n".join(lines)


# ---------- 跨天 ----------
def rollover() -> None:
    global today
    for p in plans:
        eng = engines.get(p.id)
        if eng and eng.session.state != E.SETTLED:
            settle_acts(p, eng, eng.abandon())
    engines.clear()
    store.save("sessions.json", {})
    today = dt.date.today()


# ---------- 主循环 ----------
def tick() -> None:
    global today
    now = time.time()
    if dt.date.today() != today:
        rollover()
    fg = monitor.foreground()
    statuses = {}
    for p in plans:
        if not p.enabled:
            continue
        eng = engine_for(p)
        if eng.session.state != E.SETTLED:
            acts = eng.tick(now, fg, locked=(fg is None))
            handle(p, eng, acts)
        statuses[p.id] = eng.status((fg or {}).get("proc", ""))
    update_nag(now)
    ui.refresh(statuses)
    ui.set_review(review_text(statuses))
    persist()
    root.after(TICK_MS, tick)


# ---------- UI 回调 ----------
def on_new(d: dict) -> None:
    plans.append(E.Plan(id=uuid.uuid4().hex[:8], **d))
    save_plans()
    ui.set_plans(plans)


def on_delete(plan_id: str) -> None:
    global plans
    plans = [p for p in plans if p.id != plan_id]
    engines.pop(plan_id, None)
    save_plans()
    sess = store.load("sessions.json", {})
    sess.pop(plan_id, None)
    store.save("sessions.json", sess)
    ui.set_plans(plans)


def on_early_end(plan_id) -> None:
    if plan_id is None:
        return
    plan = next((p for p in plans if p.id == plan_id), None)
    if not plan:
        return
    eng = engine_for(plan)
    ReasonDialog(root, plan.name,
                 on_pick=lambda reason: settle_acts(
                     plan, eng, eng.early_end(reason)))


# ---------- 启动 ----------
load_plans()
import customtkinter as ctk

ctk.set_appearance_mode("light")
root = ctk.CTk()
ui = MainWindow(root, on_new=on_new, on_delete=on_delete,
                on_early_end=on_early_end)
nag = NagWindow(root, on_go_back=snooze,
                on_early_end=lambda: on_early_end(active_plan_id()))
ui.set_plans(plans)
root.after(500, tick)
root.mainloop()
