"""UI 渲染自检 + 全屏截图：python test_ui.py

构造主窗口（合规/偏离两种状态）与新建计划滚轮弹窗，截图到 screenshot_ui_*.png。
"""
import customtkinter as ctk
from PIL import ImageGrab

from ui import MainWindow, NewPlanDialog

ctk.set_appearance_mode("light")
root = ctk.CTk()
root.geometry("460x680+80+50")
root.title("闭环")

saved = []
ui = MainWindow(root, on_new=lambda d: saved.append(d),
                on_delete=lambda i: None, on_early_end=lambda i: None)


class P:
    id = "p1"
    name = "学英语"
    start = "19:30"
    target_minutes = 10


ui.set_plans([P()])
ui.refresh({"p1": {"state": "RUNNING", "level": 1,
                   "effective_sec": 152, "target_sec": 600,
                   "effective_min": 2, "left_min": 8,
                   "deviation_min": 0, "deviation_count": 0,
                   "deviating": False, "cur_proc": "notepad.exe"}})
ui.set_review("· 学英语（19:30 开始，进行中）\n今天还没有结算记录。")

dlg = NewPlanDialog(root, lambda d: saved.append(d))
dlg.win.geometry("+560+50")

for _ in range(40):
    root.update_idletasks()
    root.update()

ImageGrab.grab().save("screenshot_ui_ok.png")
print("shot 1: 合规状态 + 滚轮弹窗")

# 状态切换：偏离 L3（红色）
dlg.win.destroy()
ui.refresh({"p1": {"state": "RUNNING", "level": 3,
                   "effective_sec": 152, "target_sec": 600,
                   "effective_min": 2, "left_min": 8,
                   "deviation_min": 5, "deviation_count": 1,
                   "deviating": True, "cur_proc": "chrome.exe"}})
for _ in range(20):
    root.update_idletasks()
    root.update()

ImageGrab.grab().save("screenshot_ui_dev.png")
print("shot 2: 偏离 L3 状态")

root.destroy()
print("ui test OK")
