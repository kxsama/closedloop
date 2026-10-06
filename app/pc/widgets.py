"""自绘组件：滚轮选择器（WheelPicker）与圆角进度条（ProgressBar）。

百词斩式简约：白底、圆角、单一绿色点缀、层级靠字号与灰度。
"""
from __future__ import annotations

import tkinter as tk

FONT = "Microsoft YaHei UI"
INK = "#1F1E1B"
SUB = "#6B6A66"
FAINT = "#B4B2A9"
BAND = "#E9F5EE"
TRACK = "#EFEEEA"
ACCENT = "#22A06B"


class WheelPicker(tk.Frame):
    """iOS 风格滚轮：鼠标滚轮步进、拖拽滑动、松手吸附、中央选中带。"""

    ITEM_H = 36
    VISIBLE = 5

    def __init__(self, master, items, initial=0, width=84, bg="white", on_change=None):
        super().__init__(master, bg=bg)
        self.items = [str(i) for i in items]
        self.on_change = on_change
        self.index = max(0, min(len(self.items) - 1, int(initial)))
        self.offset = float(self.index)
        self.width = width
        self._drag_y = None
        self._drag_offset = 0.0
        self._anim = None

        h = self.ITEM_H * self.VISIBLE
        self.cv = tk.Canvas(self, width=width, height=h, bg=bg,
                            highlightthickness=0, bd=0)
        self.cv.pack()
        band_y = h // 2
        self.cv.create_rectangle(6, band_y - self.ITEM_H // 2, width - 6,
                                 band_y + self.ITEM_H // 2, fill=BAND, outline="")
        self.cv.bind("<MouseWheel>", self._on_wheel)
        self.cv.bind("<ButtonPress-1>", self._on_press)
        self.cv.bind("<B1-Motion>", self._on_motion)
        self.cv.bind("<ButtonRelease-1>", self._on_release)
        self._draw()

    def _on_wheel(self, e):
        self.set_index(self.index - (1 if e.delta > 0 else -1))

    def _on_press(self, e):
        self._cancel_anim()
        self._drag_y = e.y
        self._drag_offset = self.offset

    def _on_motion(self, e):
        if self._drag_y is None:
            return
        self.offset = self._drag_offset + (self._drag_y - e.y) / self.ITEM_H
        self.offset = max(0.0, min(len(self.items) - 1.0, self.offset))
        self._draw()

    def _on_release(self, _e):
        self._drag_y = None
        self.set_index(int(round(self.offset)))

    def set_index(self, i):
        self.index = max(0, min(len(self.items) - 1, int(i)))
        self._animate_to(float(self.index))
        if self.on_change:
            self.on_change(self.get())

    def get(self):
        return self.items[self.index]

    def _cancel_anim(self):
        if self._anim is not None:
            self.cv.after_cancel(self._anim)
            self._anim = None

    def _animate_to(self, target):
        self._cancel_anim()

        def step():
            diff = target - self.offset
            if abs(diff) < 0.02:
                self.offset = target
                self._draw()
                self._anim = None
                return
            self.offset += diff * 0.28
            self._draw()
            self._anim = self.cv.after(16, step)

        step()

    def _draw(self):
        self.cv.delete("num")
        cy = self.ITEM_H * self.VISIBLE / 2
        cx = self.width / 2
        for i, text in enumerate(self.items):
            dist = i - self.offset
            y = cy + dist * self.ITEM_H
            if y < -self.ITEM_H or y > self.ITEM_H * (self.VISIBLE + 1):
                continue
            ad = abs(dist)
            if ad < 0.5:
                fill, size, weight = INK, 16, "bold"
            elif ad < 1.5:
                fill, size, weight = SUB, 12, "normal"
            else:
                fill, size, weight = FAINT, 11, "normal"
            self.cv.create_text(cx, y, text=text, tags="num",
                                font=(FONT, size, weight), fill=fill)


class ProgressBar(tk.Canvas):
    """圆头进度条：轨道灰、填充随状态变色。"""

    def __init__(self, master, width=380, height=12, bg="white"):
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.W = width
        self.H = height
        self.ratio = 0.0
        self.color = ACCENT
        self._draw()

    def set(self, ratio, color=ACCENT):
        self.ratio = max(0.0, min(1.0, float(ratio)))
        self.color = color
        self._draw()

    def _pill(self, x0, x1, color):
        self.create_oval(x0, 0, x0 + self.H, self.H, fill=color, outline="")
        self.create_oval(x1 - self.H, 0, x1, self.H, fill=color, outline="")
        self.create_rectangle(x0 + self.H / 2, 0, x1 - self.H / 2, self.H,
                              fill=color, outline="")

    def _draw(self):
        self.delete("all")
        self._pill(0, self.W, TRACK)
        if self.ratio > 0:
            fill_w = max(float(self.H), self.W * self.ratio)
            self._pill(0, fill_w, self.color)
