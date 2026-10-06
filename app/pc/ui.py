"""主窗口 v2：百词斩式简约。

白底圆角卡片、单一绿色点缀、大留白、秒级进度条、滚轮式时间选择。
"""
from __future__ import annotations

import datetime as dt
import tkinter as tk
from tkinter import messagebox

import customtkinter as ctk

import processes
from widgets import ACCENT, FAINT, FONT, INK, SUB, ProgressBar, WheelPicker

BG = "#F6F6F4"
CARD = "#FFFFFF"
ACCENT_DARK = "#177A50"
ACCENT_BG = "#E9F5EE"
WARN = "#E8A13A"
WARN_BG = "#FBF3E2"
WARN_FG = "#8A5A00"
DANGER = "#D64545"
DANGER_BG = "#FBEAEA"
GRAY_BG = "#F1F0EC"
IDLE_BAR = "#C9C7C0"

STATE_LABEL = {
    "WAITING": "待开始", "RUNNING": "进行中",
    "COOLDOWN": "沉默中", "SETTLED": "已结算",
}


def fmt(sec) -> str:
    sec = max(0, int(sec))
    return f"{sec // 60:02d}:{sec % 60:02d}"


class PlanCard(ctk.CTkFrame):
    """一条计划的卡片：时间 + 名称 + 状态徽章 + 秒级进度条 + 操作。"""

    def __init__(self, master, plan, on_delete, on_early_end):
        super().__init__(master, fg_color=CARD, corner_radius=16)
        self.plan = plan

        top = ctk.CTkFrame(self, fg_color="transparent")
        top.pack(fill="x", padx=16, pady=(14, 0))
        ctk.CTkLabel(top, text=plan.start, font=(FONT, 20, "bold"),
                     text_color=INK).pack(side="left")
        ctk.CTkLabel(top, text=plan.name, font=(FONT, 13),
                     text_color=SUB).pack(side="left", padx=(10, 0), pady=(5, 0))
        self.chip = ctk.CTkLabel(top, text="", font=(FONT, 10, "bold"),
                                 corner_radius=10, fg_color=GRAY_BG,
                                 text_color=SUB, width=66, height=22)
        self.chip.pack(side="right", pady=(3, 0))

        mid = ctk.CTkFrame(self, fg_color="transparent")
        mid.pack(fill="x", padx=16, pady=(10, 0))
        self.bar = ProgressBar(mid, width=272, height=12, bg=CARD)
        self.bar.pack(side="left")
        self.lbl_ratio = ctk.CTkLabel(mid, text="", font=(FONT, 11, "bold"),
                                      text_color=INK)
        self.lbl_ratio.pack(side="left", padx=(12, 0))

        bot = ctk.CTkFrame(self, fg_color="transparent")
        bot.pack(fill="x", padx=16, pady=(8, 12))
        self.lbl_detail = ctk.CTkLabel(bot, text="", font=(FONT, 10),
                                       text_color=SUB, anchor="w")
        self.lbl_detail.pack(side="left")
        ctk.CTkButton(bot, text="删除", font=(FONT, 10), width=52, height=26,
                      corner_radius=13, fg_color="transparent",
                      text_color=DANGER, hover_color=DANGER_BG,
                      command=lambda: on_delete(plan.id)).pack(side="right")
        self.btn_end = ctk.CTkButton(bot, text="提前结束", font=(FONT, 10),
                                     width=76, height=26, corner_radius=13,
                                     fg_color=GRAY_BG, text_color=SUB,
                                     hover_color="#E6E5E0",
                                     command=lambda: on_early_end(plan.id))
        self.btn_end.pack(side="right", padx=(0, 6))

    def update(self, st: dict):
        state = st.get("state", "")
        level = st.get("level") or 1
        deviating = st.get("deviating") or state == "COOLDOWN"
        target_sec = st.get("target_sec") or self.plan.target_minutes * 60
        eff_sec = st.get("effective_sec", st.get("effective_min", 0) * 60)
        ratio = eff_sec / target_sec if target_sec else 0

        color, chip_bg, chip_fg = ACCENT, GRAY_BG, SUB
        chip_txt = STATE_LABEL.get(state, "—")
        if state == "RUNNING":
            if deviating:
                strong = level >= 3
                color = DANGER if strong else WARN
                chip_bg = DANGER_BG if strong else WARN_BG
                chip_fg = DANGER if strong else WARN_FG
                chip_txt = "偏离中"
            else:
                chip_bg, chip_fg, chip_txt = ACCENT_BG, ACCENT_DARK, "计时中"
        elif state == "COOLDOWN":
            color, chip_bg, chip_fg, chip_txt = DANGER, DANGER_BG, DANGER, "沉默中"
        elif state == "SETTLED":
            done = st.get("result") == "完成"
            color = ACCENT if done else IDLE_BAR
            chip_txt = st.get("result") or "已结算"

        self.chip.configure(text=chip_txt, fg_color=chip_bg, text_color=chip_fg)
        self.bar.set(ratio, color if state in ("RUNNING", "COOLDOWN", "SETTLED") else IDLE_BAR)

        if state in ("RUNNING", "COOLDOWN"):
            self.lbl_ratio.configure(text=f"{fmt(eff_sec)} / {fmt(target_sec)}")
        elif state == "SETTLED":
            self.lbl_ratio.configure(text=fmt(eff_sec))
        else:
            self.lbl_ratio.configure(text=f"目标 {fmt(target_sec)}")

        detail = f"目标 {self.plan.target_minutes} 分钟"
        dev_min, dev_cnt = st.get("deviation_min", 0), st.get("deviation_count", 0)
        if dev_min or dev_cnt:
            detail += f"｜偏离 {dev_min} 分 × {dev_cnt} 次"
        if state == "RUNNING" and deviating and st.get("cur_proc"):
            detail += f"｜当前：{st['cur_proc']}"
        self.lbl_detail.configure(text=detail)

        if state in ("RUNNING", "COOLDOWN"):
            self.btn_end.configure(state="normal", fg_color=GRAY_BG,
                                   text_color=SUB)
        else:
            self.btn_end.configure(state="disabled", fg_color="transparent",
                                   text_color=FAINT)


class NewPlanDialog:
    """新建计划：滚轮选时间与时长，白名单手填。"""

    DURATIONS = [5, 10, 15, 20, 30, 45, 60, 90, "自定义"]

    def __init__(self, root, on_save):
        self.on_save = on_save
        # 用普通 tk.Toplevel：CTkToplevel 在本环境下不映射（ismapped=0），
        # CTk 控件放在普通 Toplevel 里渲染完全正常
        w = tk.Toplevel(root)
        w.title("新建计划")
        w.geometry("372x596")
        w.resizable(False, False)
        w.configure(bg=BG)
        w.attributes("-topmost", True)
        self.win = w

        body = ctk.CTkFrame(w, fg_color="transparent")
        body.pack(fill="both", expand=True, padx=18, pady=16)

        def label(text):
            ctk.CTkLabel(body, text=text, font=(FONT, 11), text_color=SUB,
                         anchor="w").pack(fill="x", pady=(6, 0))

        def entry(placeholder=""):
            e = ctk.CTkEntry(body, font=(FONT, 12), height=36, corner_radius=10,
                             fg_color=CARD, border_width=0, text_color=INK,
                             placeholder_text=placeholder,
                             placeholder_text_color=FAINT)
            e.pack(fill="x", pady=(4, 0))
            return e

        label("计划名称")
        self.e_name = entry()
        self.e_name.insert(0, "学英语")

        label("开始时间 与 时长（滚轮选取）")
        card = ctk.CTkFrame(body, fg_color=CARD, corner_radius=14)
        card.pack(fill="x", pady=(4, 0))
        wheels = ctk.CTkFrame(card, fg_color="transparent")
        wheels.pack(pady=8)
        now = dt.datetime.now() + dt.timedelta(minutes=2)
        self.w_hour = WheelPicker(wheels, [f"{h:02d}" for h in range(24)],
                                  initial=now.hour, width=68, bg=CARD)
        self.w_hour.pack(side="left", padx=(10, 2))
        ctk.CTkLabel(wheels, text=":", font=(FONT, 16, "bold"),
                     text_color=INK).pack(side="left")
        self.w_min = WheelPicker(wheels, [f"{m:02d}" for m in range(60)],
                                 initial=now.minute, width=68, bg=CARD)
        self.w_min.pack(side="left", padx=(2, 10))
        ctk.CTkLabel(wheels, text="做", font=(FONT, 12),
                     text_color=SUB).pack(side="left")
        self.w_dur = WheelPicker(wheels, self.DURATIONS,
                                 initial=self.DURATIONS.index(10),
                                 width=60, bg=CARD,
                                 on_change=self._toggle_custom)
        self.w_dur.pack(side="left", padx=(4, 2))
        ctk.CTkLabel(wheels, text="分钟", font=(FONT, 12),
                     text_color=SUB).pack(side="left", padx=(2, 8))

        self.e_custom = entry("自定义分钟数（1-999）")
        self.e_custom.pack_forget()

        label("关联应用（在这些应用里才计时）")
        self.e_proc = entry("从下方选择，或手动输入进程名")
        self.e_proc.insert(0, "notepad.exe")
        ctk.CTkButton(body, text="从运行中的应用选择…", font=(FONT, 10),
                      height=26, corner_radius=13, fg_color=ACCENT_BG,
                      text_color=ACCENT_DARK, hover_color="#D9EEE3",
                      command=self._open_picker).pack(anchor="w", pady=(4, 0))

        label("窗口标题关键词（可选）")
        self.e_title = entry("标题里含这个词也算合规")

        ctk.CTkLabel(body,
                     text="关联应用按进程判定；至少选一个关联应用，"
                          "或填一个窗口标题关键词。",
                     font=(FONT, 9), text_color=FAINT, wraplength=330,
                     justify="left").pack(fill="x", pady=(8, 0))

        ctk.CTkButton(body, text="保存计划", font=(FONT, 13, "bold"), height=42,
                      corner_radius=21, fg_color=ACCENT,
                      hover_color=ACCENT_DARK, text_color="white",
                      command=self._save).pack(fill="x", pady=(12, 0))

    def _toggle_custom(self, val):
        if val == "自定义":
            self.e_custom.pack(fill="x", pady=(6, 0))
        else:
            self.e_custom.pack_forget()

    def _open_picker(self):
        ProcessPickerDialog(self.win, self.e_proc.get(), self._apply_picked)

    def _apply_picked(self, text):
        self.e_proc.delete(0, "end")
        self.e_proc.insert(0, text)

    def _save(self):
        name = self.e_name.get().strip()
        start = f"{self.w_hour.get()}:{self.w_min.get()}"
        dur_sel = self.w_dur.get()
        if dur_sel == "自定义":
            try:
                mins = int(self.e_custom.get().strip())
                assert 1 <= mins <= 999
            except Exception:
                messagebox.showerror("还差一点", "自定义时长请填 1-999 的整数分钟",
                                     parent=self.win)
                return
        else:
            mins = int(dur_sel)
        procs = [p.strip().lower() for p in
                 self.e_proc.get().replace("，", ",").split(",") if p.strip()]
        title_kw = self.e_title.get().strip()
        if not name or (not procs and not title_kw):
            messagebox.showerror("还差一点",
                                 "名称不能为空，且至少选一个关联应用或填标题关键词",
                                 parent=self.win)
            return
        self.on_save({
            "name": name, "start": start, "target_minutes": mins,
            "whitelist_procs": procs, "whitelist_title": title_kw,
        })
        self.win.destroy()


class ProcessPickerDialog:
    """从当前运行中带窗口的应用里勾选关联应用。"""

    def __init__(self, parent, current: str, on_apply):
        w = tk.Toplevel(parent)
        w.title("选择关联应用")
        w.geometry("400x440")
        w.configure(bg=BG)
        w.attributes("-topmost", True)
        self.win = w
        self.on_apply = on_apply

        preselected = {p.strip().lower() for p in
                       current.replace("，", ",").split(",") if p.strip()}
        ctk.CTkLabel(w, text="勾选正在运行的应用，点确定生效。",
                     font=(FONT, 9), text_color=FAINT, anchor="w",
                     wraplength=360, justify="left").pack(fill="x", padx=16,
                                                          pady=(14, 4))
        box = ctk.CTkScrollableFrame(w, fg_color=CARD, corner_radius=14)
        box.pack(fill="both", expand=True, padx=16, pady=(0, 10))
        self.vars = []
        apps = processes.running_apps()
        if not apps:
            ctk.CTkLabel(box, text="没检测到运行中带窗口的应用",
                         font=(FONT, 10), text_color=SUB).pack(pady=16)
        for app in apps:
            var = tk.BooleanVar(value=app["proc"].lower() in preselected)
            title = app["title"]
            if len(title) > 24:
                title = title[:24] + "…"
            cb = ctk.CTkCheckBox(box, text=f'{app["proc"]}  —  {title}',
                                 font=(FONT, 10), text_color=INK, variable=var,
                                 fg_color=ACCENT, hover_color=ACCENT_DARK,
                                 corner_radius=4)
            cb.pack(anchor="w", padx=12, pady=4)
            self.vars.append((var, app["proc"]))
        ctk.CTkButton(w, text="确定", font=(FONT, 12, "bold"), height=38,
                      corner_radius=19, fg_color=ACCENT,
                      hover_color=ACCENT_DARK, text_color="white",
                      command=self._apply).pack(fill="x", padx=16, pady=(0, 14))

    def _apply(self):
        chosen = [p for var, p in self.vars if var.get()]
        self.on_apply(", ".join(chosen))
        self.win.destroy()


class MainWindow:
    def __init__(self, root, on_new, on_delete, on_early_end):
        self.root = root
        self.on_new = on_new
        self.on_delete = on_delete
        self.on_early_end = on_early_end
        self.plans = []
        self.cards = {}

        root.title("闭环")
        root.geometry("460x680")
        root.configure(fg_color=BG)

        head = ctk.CTkFrame(root, fg_color="transparent")
        head.pack(fill="x", padx=22, pady=(20, 4))
        ctk.CTkLabel(head, text="闭环", font=(FONT, 24, "bold"),
                     text_color=INK).pack(side="left")
        self.lbl_date = ctk.CTkLabel(head, text="", font=(FONT, 11),
                                     text_color=SUB)
        self.lbl_date.pack(side="left", padx=(10, 0), pady=(9, 0))
        ctk.CTkLabel(head, text="看见自己在干嘛", font=(FONT, 10),
                     text_color=FAINT).pack(side="right", pady=(11, 0))

        self.cards_frame = ctk.CTkScrollableFrame(root, fg_color="transparent",
                                                  height=318,
                                                  scrollbar_button_color=GRAY_BG)
        self.cards_frame.pack(fill="x", padx=14)

        ctk.CTkButton(root, text="＋ 新建计划", font=(FONT, 13, "bold"),
                      height=44, corner_radius=22, fg_color=ACCENT,
                      hover_color=ACCENT_DARK, text_color="white",
                      command=self._new).pack(fill="x", padx=20, pady=10)

        review_card = ctk.CTkFrame(root, fg_color=CARD, corner_radius=16)
        review_card.pack(fill="both", expand=True, padx=14, pady=(0, 14))
        ctk.CTkLabel(review_card, text="当日复盘", font=(FONT, 12, "bold"),
                     text_color=INK, anchor="w").pack(fill="x", padx=16,
                                                      pady=(12, 0))
        self.review = ctk.CTkTextbox(review_card, font=(FONT, 10),
                                     fg_color="transparent", text_color=SUB,
                                     wrap="word", activate_scrollbars=False)
        self.review.pack(fill="both", expand=True, padx=10, pady=(2, 10))
        self.review.configure(state="disabled")

        self.empty_lbl = None
        self._update_date()

    def _update_date(self):
        d = dt.date.today()
        self.lbl_date.configure(text=d.strftime("%m月%d日"))

    def _new(self):
        NewPlanDialog(self.root, self.on_new)

    def set_plans(self, plans):
        self.plans = plans
        self._update_date()
        self._rebuild()

    def _rebuild(self):
        for wdg in self.cards_frame.winfo_children():
            wdg.destroy()
        self.cards = {}
        if not self.plans:
            self.empty_lbl = ctk.CTkLabel(
                self.cards_frame, text="还没有计划\n点下面按钮，给自己定一个",
                font=(FONT, 11), text_color=FAINT, justify="center")
            self.empty_lbl.pack(pady=30)
            return
        for p in self.plans:
            card = PlanCard(self.cards_frame, p, self.on_delete,
                            self.on_early_end)
            card.pack(fill="x", pady=4, padx=2)
            self.cards[p.id] = card

    def refresh(self, statuses):
        if len(self.cards) != len(self.plans):
            self._rebuild()
        for p in self.plans:
            card = self.cards.get(p.id)
            if card:
                card.update(statuses.get(p.id, {}))

    def set_review(self, text):
        self.review.configure(state="normal")
        self.review.delete("1.0", "end")
        self.review.insert("1.0", text)
        self.review.configure(state="disabled")
