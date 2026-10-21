"""常驻催促窗与各类对话框（tkinter）。

NagWindow 是 PC 版的「Live Updates」等价物：执行期常驻右下角，
合规时绿色安静，切走时按 L2/L3 换色换语气，封顶后不刷屏。

**一个必须守住的约定**：小窗的颜色和状态标签看的是 `deviating`（偏离已确认），
不是 `level`。`level` 要连续偏离 5 分钟才升到 3，如果拿它决定"你在不在状态"，
就会出现"卡片写着已切走、小窗还绿着说计时中"——而小窗才是余光能扫到的那个。
`level` 只负责决定**语气有多重**（担心 / 失望）。
"""
from __future__ import annotations

import os
import tkinter as tk
import winsound

FONT = "Microsoft YaHei UI"
ICON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon.ico")

# 提醒窗自动收起：不自动收会一直挂着挡住别的东西（实测能挂 7 分钟）。
# 90 秒够看见、够读完，又不会变成桌面上的一块牛皮癣。
AUTO_CLOSE_MS = 90_000

LEVEL_STYLE = {
    1: {"bg": "#EAF3DE", "fg": "#27500A", "bd": "#3B6D11", "tag": "计时中"},
    2: {"bg": "#FAEEDA", "fg": "#633806", "bd": "#854F0B", "tag": "已切走"},
    3: {"bg": "#F6E5E3", "fg": "#8E3A31", "bd": "#B8443C", "tag": "持续切走"},
}
STYLE_DONE = {"bg": "#EAF3DE", "fg": "#27500A", "bd": "#3B6D11", "tag": "完成"}
STYLE_INFO = {"bg": "#E6F1FB", "fg": "#0C447C", "bd": "#185FA5", "tag": "提醒"}
STYLE_WARN = {"bg": "#FAEEDA", "fg": "#633806", "bd": "#854F0B", "tag": "熔断"}

REASONS = ["背完了", "今天状态不行", "计划定得不合理", "有突发"]

# 同屏活着的弹窗（用来错位摆放，避免两个计划同一分钟到点时互相压住）
_LIVE: list = []


def set_icon(win) -> None:
    """统一窗口图标。不设的话对话框标题栏是 Tk 默认羽毛，看着像别人的程序。"""
    try:
        win.iconbitmap(ICON)
    except Exception:
        pass


def _beep() -> None:
    try:
        winsound.MessageBeep(winsound.MB_ICONEXCLAMATION)
    except Exception:
        pass


def _stack_offset() -> int:
    """已有弹窗占掉的高度（用于把新弹窗往下错开）。"""
    alive = [w for w in _LIVE if _alive(w)]
    _LIVE[:] = alive
    total = 0
    for w in alive:
        try:
            total += w.winfo_height() + 8
        except Exception:
            pass
    return total


def _alive(w) -> bool:
    try:
        return bool(w.winfo_exists())
    except Exception:
        return False


def _register(w) -> None:
    _LIVE.append(w)

    def gone(e):
        if getattr(e, "widget", None) is not w:
            return
        if w in _LIVE:
            _LIVE.remove(w)

    try:
        w.bind("<Destroy>", gone, add="+")
    except Exception:
        pass


def _place_top_right(w, base_ratio: float = 0.32) -> None:
    """贴右边、按已有弹窗数量往下错开。"""
    w.update_idletasks()
    sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
    x = sw - w.winfo_width() - 48
    y = int(sh * base_ratio) + _stack_offset()
    w.geometry(f"+{max(0, x)}+{max(0, y)}")
    _register(w)


def _make_draggable(win: tk.Toplevel, *widgets) -> None:
    def start(e):
        win._drag_x, win._drag_y = e.x, e.y

    def move(e):
        win.geometry(f"+{e.x_root - win._drag_x}+{e.y_root - win._drag_y}")

    for wdg in widgets:
        wdg.bind("<Button-1>", start)
        wdg.bind("<B1-Motion>", move)


class NagWindow:
    """执行期常驻小窗（PC 版常驻镜子）。

    高度不再写死：文案长度是变的（挂机提醒那句最长），固定 158 px 会把它压到
    统计行和按钮上。现在按内容测量，往上长——底边位置不动，视觉上不会跳。
    """
    W = 252
    MIN_H = 158
    MARGIN_R = 24
    MARGIN_B = 72

    def __init__(self, root: tk.Tk, on_go_back, on_early_end):
        self.win = tk.Toplevel(root)
        w = self.win
        w.overrideredirect(True)
        w.attributes("-topmost", True)
        self._h = self.MIN_H

        self.frame = tk.Frame(w, bd=0, highlightthickness=2)
        self.frame.pack(fill="both", expand=True)
        self.tag = tk.Label(self.frame, font=(FONT, 9, "bold"), anchor="w")
        self.tag.pack(fill="x", padx=10, pady=(8, 0))
        self.msg = tk.Label(self.frame, wraplength=self.W - 24, justify="left",
                            font=(FONT, 10), anchor="w")
        self.msg.pack(fill="x", padx=10, pady=(4, 0))
        self.stat = tk.Label(self.frame, font=(FONT, 9), anchor="w")
        self.stat.pack(fill="x", padx=10, pady=(6, 0))

        btns = tk.Frame(self.frame)
        btns.pack(side="bottom", fill="x", padx=8, pady=8)
        self.btn_back = tk.Button(btns, text="这就回去", command=on_go_back,
                                  relief="flat", font=(FONT, 9), bg="white")
        self.btn_back.pack(side="left", expand=True, fill="x", padx=(0, 4))
        self.btn_end = tk.Button(btns, text="提前结束", command=on_early_end,
                                 relief="flat", font=(FONT, 9), bg="white")
        self.btn_end.pack(side="left", expand=True, fill="x")

        _make_draggable(w, self.frame, self.tag, self.msg, self.stat)
        self._fit(reset=True)
        w.withdraw()
        self.visible = False

    def _fit(self, reset: bool = False) -> None:
        """按内容量高度。底边锚定：变高时向上长，不遮挡下方内容。"""
        try:
            self.win.update_idletasks()
            need = self.win.winfo_reqheight()
        except Exception:
            need = self.MIN_H
        h = max(self.MIN_H, need)
        if not reset and h == self._h:
            return
        old_h, self._h = self._h, h
        sw, sh = self.win.winfo_screenwidth(), self.win.winfo_screenheight()
        if reset or not self.visible:
            x = sw - self.W - self.MARGIN_R
            y = sh - h - self.MARGIN_B
        else:
            # 用户可能拖过位置：沿用当前 x，保持底边不动
            x = self.win.winfo_x()
            y = self.win.winfo_y() + old_h - h
        self.win.geometry(f"{self.W}x{h}+{max(0, x)}+{max(0, y)}")

    def update(self, level: int, msg: str, stat: str) -> None:
        st = LEVEL_STYLE.get(level, LEVEL_STYLE[1])
        self.frame.configure(bg=st["bg"], highlightbackground=st["bd"])
        for wdg in (self.tag, self.msg, self.stat):
            wdg.configure(bg=st["bg"], fg=st["fg"])
        self.tag.configure(text=st["tag"])
        self.msg.configure(text=msg)
        self.stat.configure(text=stat)
        self._fit()

    def show(self) -> None:
        if not self.visible:
            self._fit()
            self.win.deiconify()
            self.visible = True

    def hide(self) -> None:
        if self.visible:
            self.win.withdraw()
            self.visible = False


class InfoDialog:
    """带音效的提醒/庆祝弹窗（L1 提醒、完成结算）。

    两个改进（v0.3）：多个计划同一分钟到点时按已有弹窗高度往下错开；
    90 秒后自动收起（到点提醒收起后，常驻小窗仍在，信息不会丢）。
    """

    def __init__(self, root: tk.Tk, title: str, text: str,
                 style: dict | None = None, ok_text: str = "知道了"):
        st = style or STYLE_INFO
        w = tk.Toplevel(root)
        self.win = w
        w.attributes("-topmost", True)
        w.title(title)
        w.configure(bg=st["bg"])
        w.resizable(False, False)
        set_icon(w)
        tk.Label(w, text=title, font=(FONT, 11, "bold"),
                 bg=st["bg"], fg=st["fg"]).pack(anchor="w", padx=16, pady=(14, 4))
        tk.Label(w, text=text, wraplength=330, justify="left",
                 font=(FONT, 10), bg=st["bg"], fg=st["fg"]).pack(
            anchor="w", padx=16, pady=(0, 10))
        tk.Button(w, text=ok_text, command=w.destroy, font=(FONT, 10),
                  relief="flat", bg="white", width=12).pack(pady=(0, 14))
        _place_top_right(w)
        _beep()
        w.after(AUTO_CLOSE_MS, lambda: w.destroy() if _alive(w) else None)


class FuseDialog:
    """熔断询问：'还要吗？' —— 继续 / 今天算了（不自动记未完成）。"""

    def __init__(self, root: tk.Tk, text: str, on_continue, on_abandon):
        w = tk.Toplevel(root)
        self.win = w
        w.attributes("-topmost", True)
        w.title("还要吗")
        st = STYLE_WARN
        w.configure(bg=st["bg"])
        w.resizable(False, False)
        set_icon(w)
        tk.Label(w, text="熔断 · 切走有点久了", font=(FONT, 11, "bold"),
                 bg=st["bg"], fg=st["fg"]).pack(anchor="w", padx=16, pady=(14, 4))
        tk.Label(w, text=text, wraplength=330, justify="left",
                 font=(FONT, 10), bg=st["bg"], fg=st["fg"]).pack(
            anchor="w", padx=16, pady=(0, 10))
        row = tk.Frame(w, bg=st["bg"])
        row.pack(pady=(0, 14))
        tk.Button(row, text="继续，我说真的", font=(FONT, 10), relief="flat",
                  bg="white", command=lambda: (w.destroy(), on_continue())).pack(
            side="left", padx=(0, 8))
        tk.Button(row, text="今天算了", font=(FONT, 10), relief="flat",
                  bg="white", command=lambda: (w.destroy(), on_abandon())).pack(
            side="left")
        _place_top_right(w)
        _beep()


class ReasonDialog:
    """提前结束原因快选：一次有理由的退出，不审判。"""

    def __init__(self, root: tk.Tk, plan_name: str, on_pick):
        w = tk.Toplevel(root)
        self.win = w
        w.attributes("-topmost", True)
        w.title("提前结束")
        w.configure(bg="white")
        w.resizable(False, False)
        set_icon(w)
        tk.Label(w, text=f"为什么提前结束「{plan_name}」？",
                 font=(FONT, 11, "bold"), bg="white").pack(
            anchor="w", padx=16, pady=(14, 2))
        tk.Label(w, text="选一个就行，不审判。时长不够的部分会记为「未完成」。",
                 font=(FONT, 9), bg="white", fg="#5F5E5A").pack(
            anchor="w", padx=16, pady=(0, 8))
        for r in REASONS:
            tk.Button(w, text=r, font=(FONT, 10), relief="flat", bg="#F1EFE8",
                      width=22, command=lambda r=r: (w.destroy(), on_pick(r))).pack(
                padx=16, pady=3)
        tk.Button(w, text="再想想，继续", font=(FONT, 10), relief="flat",
                  bg="#E6F1FB", width=22, command=w.destroy).pack(
            padx=16, pady=(6, 14))
        w.update_idletasks()
        sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
        y = int(sh * 0.35) + _stack_offset()
        w.geometry(f"+{int(sw * 0.5) - w.winfo_width() // 2}+{max(0, y)}")
        _register(w)
