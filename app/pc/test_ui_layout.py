"""界面布局回归测试（无截图、不干扰桌面）。

锁住三个被实机测评抓出来的问题，防止改着改着又回去：

1. **自定义时长必须真的能输入**（P0）：
   原来输入框被固定高度挤成 1 px、窗口又锁死不可缩放，选了「自定义」就是死路。
   现在断言它 ismapped=1 且高度 > 20。

2. **小窗必须装得下自己的文案**（P1）：
   原来 252×158 写死，挂机那句 3 行文案会把统计行和按钮压住。
   现在断言长文案下窗口高度会自动变大。

3. **多个提醒窗不能叠在一起**（P1）：
   同一分钟到点的两个计划，弹窗位置必须错开。

用法：python test_ui_layout.py
"""
import customtkinter as ctk

from ui import MainWindow, NewPlanDialog
from windows import InfoDialog, NagWindow

OFF = "+6000+6000"          # 一律摆到屏幕外，测试期间不占桌面

ctk.set_appearance_mode("light")
root = ctk.CTk()
root.geometry("460x680" + OFF)

ui = MainWindow(root, on_new=lambda d: None, on_delete=lambda i: None,
                on_early_end=lambda i: None)
ui.set_plans([])


def settle(n=30):
    for _ in range(n):
        root.update_idletasks()
        root.update()


# ---- 1. 自定义时长 ----
dlg = NewPlanDialog(root, lambda d: None)
dlg.win.geometry(OFF)
settle()
assert dlg.e_custom.winfo_ismapped() == 0, "默认不该显示自定义输入框"

dlg._toggle_custom("自定义")
settle()
h = dlg.e_custom.winfo_height()
assert dlg.e_custom.winfo_ismapped() == 1, "选了自定义，输入框却没有显示（P0 回归）"
assert h > 20, f"自定义输入框高度只有 {h}px，被挤扁了（P0 回归）"
assert 3 in NewPlanDialog.DURATIONS, "最小档位里没有 3 分钟"
dlg.win.destroy()
print("1 自定义时长可输入 OK")

# ---- 2. 小窗按内容自适应高度 ----
nag = NagWindow(root, on_go_back=lambda: None, on_early_end=lambda: None)
nag.win.geometry(OFF)
base = nag.MIN_H

nag.update(1, "有效 3 / 剩 7 分", "有效 3 / 剩 7 分")
settle()
h_short = nag._h

long_msg = ("「学英语」12 分钟没操作了，计时还在走。挂着的话，记得诚实结束。"
            "如果只是挂着，记得诚实结束。")
nag.update(1, long_msg, "有效 12 / 剩 18 分")
settle()
h_long = nag._h

assert h_short >= base, h_short
assert h_long > h_short, f"长文案没有长高：{h_short} -> {h_long}（P1 回归）"
nag.win.destroy()
print("2 小窗高度自适应 OK")

# ---- 3. 多个提醒窗错开 ----
# 这一项不能把窗口挪到屏幕外——测的就是它们自己算出来的位置。
d1 = InfoDialog(root, "到点了", "计划 A 开始了。")
settle()
y1 = d1.win.winfo_y()
d2 = InfoDialog(root, "到点了", "计划 B 也开始了。")
settle()
y2 = d2.win.winfo_y()
assert y2 > y1, f"两个提醒窗同位置重叠：y1={y1} y2={y2}（P1 回归）"
d1.win.destroy()
d2.win.destroy()
print("3 多提醒窗错位 OK")

root.destroy()
print("\n全部通过")
