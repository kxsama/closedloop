"""常驻催促窗与各类对话框（tkinter）。

NagWindow 是 PC 版的「Live Updates」等价物：执行期常驻右上角，
合规时绿色安静，偏离时按 L2/L3 换色换语气，封顶后不刷屏。
"""
from __future__ import annotations

import tkinter as tk
import winsound

FONT = "Microsoft YaHei UI"

LEVEL_STYLE = {
    1: {"bg": "#EAF3DE", "fg": "#27500A", "bd": "#3B6D11", "tag": "计时中"},
    2: {"bg": "#FAEEDA", "fg": "#633806", "bd": "#854F0B", "tag": "偏离中"},
    3: {"bg": "#F6E5E3", "fg": "#8E3A31", "bd": "#B8443C", "tag": "持续偏离"},
}
STYLE_DONE = {"bg": "#EAF3DE", "fg": "#27500A", "bd": "#3B6D11", "tag": "完成"}
STYLE_INFO = {"bg": "#E6F1FB", "fg": "#0C447C", "bd": "#185FA5", "tag": "提醒"}
STYLE_WARN = {"bg": "#FAEEDA", "fg": "#633806", "bd": "#854F0B", "tag": "熔断"}

REASONS = ["背完了", "今天状态不行", "计划定得不合理", "有突发"]


def _beep() -> None:
    try:
        winsound.MessageBeep(winsound.MB_ICONEXCLAMATION)
    except Exception:
        pass


def _make_draggable(win: tk.Toplevel, *widgets) -> None:
    def start(e):
        win._drag_x, win._drag_y = e.x, e.y

    def move(e):
        win.geometry(f"+{e.x_root - win._drag_x}+{e.y_root - win._drag_y}")

    for wdg in widgets:
        wdg.bind("<Button-1>", start)
        wdg.bind("<B1-Motion>", move)


class NagWindow:
    """执行期常驻小窗（PC 版常驻镜子）。"""
    W, H = 252, 158

    def __init__(self, root: tk.Tk, on_go_back, on_early_end):
        self.win = tk.Toplevel(root)
        w = self.win
        w.overrideredirect(True)
        w.attributes("-topmost", True)
        sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
        w.geometry(f"{self.W}x{self.H}+{sw - self.W - 24}+{sh - self.H - 72}")

        self.frame = tk.Frame(w, bd=0, highlightthickness=2)
        self.frame.pack(fill="both", expand=True)
        self.tag = tk.Label(self.frame, font=(FONT, 9, "bold"), anchor="w")
        self.tag.pack(fill="x", padx=10, pady=(8, 0))
        self.msg = tk.Label(self.frame, wraplength=self.W - 22, justify="left",
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
        w.withdraw()
        self.visible = False

    def update(self, level: int, msg: str, stat: str) -> None:
        st = LEVEL_STYLE.get(level, LEVEL_STYLE[1])
        self.frame.configure(bg=st["bg"], highlightbackground=st["bd"])
        for wdg in (self.tag, self.msg, self.stat):
            wdg.configure(bg=st["bg"], fg=st["fg"])
        self.tag.configure(text=st["tag"])
        self.msg.configure(text=msg)
        self.stat.configure(text=stat)

    def show(self) -> None:
        if not self.visible:
            self.win.deiconify()
            self.visible = True

    def hide(self) -> None:
        if self.visible:
            self.win.withdraw()
            self.visible = False


class InfoDialog:
    """带音效的提醒/庆祝弹窗（L1 提醒、完成庆祝）。"""

    def __init__(self, root: tk.Tk, title: str, text: str,
                 style: dict | None = None, ok_text: str = "知道了"):
        st = style or STYLE_INFO
        w = tk.Toplevel(root)
        w.attributes("-topmost", True)
        w.title(title)
        w.configure(bg=st["bg"])
        w.resizable(False, False)
        tk.Label(w, text=title, font=(FONT, 11, "bold"),
                 bg=st["bg"], fg=st["fg"]).pack(anchor="w", padx=16, pady=(14, 4))
        tk.Label(w, text=text, wraplength=330, justify="left",
                 font=(FONT, 10), bg=st["bg"], fg=st["fg"]).pack(
            anchor="w", padx=16, pady=(0, 10))
        tk.Button(w, text=ok_text, command=w.destroy, font=(FONT, 10),
                  relief="flat", bg="white", width=12).pack(pady=(0, 14))
        w.update_idletasks()
        sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
        w.geometry(f"+{sw - w.winfo_width() - 48}+{int(sh * 0.32)}")
        _beep()


class FuseDialog:
    """熔断询问：'还要吗？' —— 继续 / 今天算了（不自动记未完成）。"""

    def __init__(self, root: tk.Tk, text: str, on_continue, on_abandon):
        w = tk.Toplevel(root)
        w.attributes("-topmost", True)
        w.title("还要吗")
        st = STYLE_WARN
        w.configure(bg=st["bg"])
        w.resizable(False, False)
        tk.Label(w, text="熔断 · 偏离有点久了", font=(FONT, 11, "bold"),
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
        w.update_idletasks()
        sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
        w.geometry(f"+{sw - w.winfo_width() - 48}+{int(sh * 0.32)}")
        _beep()


class ReasonDialog:
    """提前结束原因快选：一次有理由的退出，不审判。"""

    def __init__(self, root: tk.Tk, plan_name: str, on_pick):
        w = tk.Toplevel(root)
        w.attributes("-topmost", True)
        w.title("提前结束")
        w.configure(bg="white")
        w.resizable(False, False)
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
        w.geometry(f"+{int(sw * 0.5) - w.winfo_width() // 2}+{int(sh * 0.35)}")
