"""核心状态机：计划会话的计时、偏离、升级、结算。

纯逻辑模块，不依赖 tkinter / pywin32，可独立测试。
规则依据 SPEC-行为闭环应用 v0.2.1：有效时长制、偏离只记录不记债、
<60s 偏离去抖、L1-L4 升级封顶、COOLDOWN、熔断、未观测不等于偏离。
"""
from __future__ import annotations

import datetime as dt
import math
from dataclasses import dataclass, field

# 会话状态
WAITING, RUNNING, COOLDOWN, SETTLED = "WAITING", "RUNNING", "COOLDOWN", "SETTLED"
# 结算结果
DONE, UNFINISHED, EARLY_END = "完成", "未完成", "提前结束"

# 可调参数（秒）
GRACE_DEVIATION_SEC = 60        # 去抖宽限：偏离不足 60 秒不计
FIRST_RECHECK_SEC = 5 * 60      # 到点后第一次回查（催促从这之后才可能启动）
L3_CONTINUOUS_SEC = 5 * 60      # 连续确认偏离超过此值升级 L3
COOLDOWN_CONTINUOUS_SEC = 10 * 60  # 连续确认偏离超过此值进入沉默冷却
GAP_SEC = 120                   # 两次检测间隔超过此值记为"未观测"空洞


@dataclass
class Plan:
    id: str
    name: str
    start: str                       # "HH:MM"
    target_minutes: int
    whitelist_procs: list = field(default_factory=list)   # 进程名（小写，含 .exe）
    whitelist_title: str = ""                              # 窗口标题关键词（可选）
    fuse_ratio: float = 1.0          # 熔断阈值 = 比例 × 目标时长
    enabled: bool = True

    def start_epoch(self, date: dt.date) -> float:
        h, m = map(int, self.start.split(":"))
        return dt.datetime.combine(date, dt.time(h, m)).timestamp()

    def matches(self, fg: dict) -> bool:
        proc = (fg.get("proc") or "").lower()
        if proc and proc in [p.lower() for p in self.whitelist_procs]:
            return True
        kw = (self.whitelist_title or "").strip().lower()
        if kw and kw in (fg.get("title") or "").lower():
            return True
        return False


@dataclass
class Session:
    plan_id: str
    date: str
    state: str = WAITING
    effective_sec: float = 0.0       # 有效时长（秒）
    deviation_sec: float = 0.0       # 正式偏离累计（秒）
    deviation_count: int = 0         # 偏离次数
    cur_dev_start: float | None = None   # 当前偏离段起点（含宽限期）
    dev_confirmed: bool = False      # 当前偏离段是否已过宽限转正
    cont_dev_sec: float = 0.0        # 当前连续确认偏离时长
    last_tick: float | None = None
    recheck_at: float | None = None
    level: int = 0                   # 0 未开始 / 1 提醒后 / 2 偏离 / 3 持续偏离
    fused: bool = False
    result: str | None = None
    reason: str | None = None
    not_observed: list = field(default_factory=list)  # [[start, end], ...]


class Engine:
    """单个计划当日的会话引擎。tick(now, fg) 返回动作列表，由外层执行 UI。"""

    def __init__(self, plan: Plan, session: Session | None = None,
                 today: dt.date | None = None):
        self.plan = plan
        self.today = today or dt.date.today()
        self.session = session or Session(plan_id=plan.id, date=self.today.isoformat())

    def _settle(self, result: str, reason: str | None = None) -> list:
        s = self.session
        s.state = SETTLED
        s.result = result
        s.reason = reason
        target = self.plan.target_minutes * 60
        return [{
            "type": "settle", "result": result, "reason": reason,
            "effective_min": round(s.effective_sec / 60, 1),
            "deviation_min": round(s.deviation_sec / 60, 1),
            "deviation_count": s.deviation_count,
            "unfinished_min": round(max(0.0, target - s.effective_sec) / 60, 1),
        }]

    def status(self, fg_proc: str = "") -> dict:
        s = self.session
        target = self.plan.target_minutes * 60
        left = max(0, math.ceil((target - s.effective_sec) / 60))
        return {
            "type": "status", "state": s.state, "level": s.level,
            "effective_min": int(s.effective_sec // 60), "left_min": left,
            "deviation_min": int(s.deviation_sec // 60),
            "deviation_count": s.deviation_count,
            "deviating": s.dev_confirmed, "cur_proc": fg_proc,
            # 秒级原值（UI 进度条用）
            "effective_sec": round(s.effective_sec, 1),
            "target_sec": target,
            "deviation_sec": round(s.deviation_sec, 1),
            "result": s.result,
        }

    def tick(self, now: float, fg: dict | None, locked: bool = False) -> list:
        s = self.session
        acts: list = []
        if s.state == SETTLED:
            return acts

        # 监测空洞：服务被杀/系统休眠。未观测 ≠ 偏离。
        if s.last_tick is not None and now - s.last_tick > GAP_SEC:
            s.not_observed.append([round(s.last_tick, 1), round(now, 1)])
            s.cur_dev_start = None
            s.dev_confirmed = False
            s.cont_dev_sec = 0.0
            acts.append({"type": "gap", "from": s.not_observed[-1][0],
                         "to": s.not_observed[-1][1]})

        if locked or fg is None:
            # 锁屏/前台未知：未观测，不计账，也不算偏离
            s.last_tick = now
            return acts

        delta = now - s.last_tick if s.last_tick is not None else 0.0
        s.last_tick = now

        if s.state == WAITING:
            if now >= self.plan.start_epoch(self.today):
                s.state = RUNNING
                s.level = 1
                s.recheck_at = now + FIRST_RECHECK_SEC
                acts.append({"type": "remind", "level": 1})
            return acts

        # ---- RUNNING / COOLDOWN 计时与偏离 ----
        compliant = self.plan.matches(fg)

        if compliant:
            if s.cur_dev_start is not None:
                if s.dev_confirmed:
                    s.cur_dev_start = None
                    s.dev_confirmed = False
                    s.cont_dev_sec = 0.0
                    if s.state == COOLDOWN:
                        s.state = RUNNING
                        acts.append({"type": "cooldown_exit"})
                    acts.append({"type": "deviation_end"})
                else:
                    # 宽限内的毛刺（<60s）：整段补记为有效，偏离从未发生
                    s.effective_sec += now - s.cur_dev_start
                    s.cur_dev_start = None
            s.effective_sec += delta
        else:
            if s.cur_dev_start is None:
                s.cur_dev_start = s.last_tick - delta
            seg = now - s.cur_dev_start
            if not s.dev_confirmed and seg >= GRACE_DEVIATION_SEC:
                s.dev_confirmed = True
                s.deviation_count += 1
                s.deviation_sec += seg          # 转正后从起点整段计入
                s.cont_dev_sec = seg
                acts.append({"type": "deviation_start"})
            elif s.dev_confirmed:
                s.deviation_sec += delta
                s.cont_dev_sec += delta

        # ---- 升级阶梯（第一次回查之后才"上膛"）----
        armed = s.recheck_at is not None and now >= s.recheck_at
        if s.state == RUNNING and armed:
            if s.dev_confirmed:
                new_level = 3 if s.cont_dev_sec >= L3_CONTINUOUS_SEC else 2
                if new_level != s.level:
                    s.level = new_level
                    acts.append({"type": "escalate", "level": new_level})
                if s.cont_dev_sec >= COOLDOWN_CONTINUOUS_SEC:
                    s.state = COOLDOWN
                    acts.append({"type": "cooldown_enter"})
            elif s.level > 1:
                s.level = 1
                acts.append({"type": "escalate", "level": 1})

        # ---- 熔断：累计偏离超阈值，问"还要吗"（不自动记未完成）----
        if (not s.fused
                and s.deviation_sec >= self.plan.fuse_ratio * self.plan.target_minutes * 60):
            s.fused = True
            acts.append({"type": "fuse_ask"})

        # ---- 完成 ----
        if s.effective_sec >= self.plan.target_minutes * 60:
            return acts + self._settle(DONE)

        return acts

    # ---- 用户主动动作 ----
    def early_end(self, reason: str) -> list:
        return self._settle(EARLY_END, reason=reason)

    def abandon(self) -> list:
        return self._settle(UNFINISHED)

    def fuse_continue(self) -> None:
        s = self.session
        s.deviation_sec = 0.0
        s.cont_dev_sec = 0.0
        s.fused = False
