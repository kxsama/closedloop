"""列出运行中带可见窗口的应用（供「关联应用」选择器使用）。"""
from __future__ import annotations

import psutil
import win32gui
import win32process

# 无意义的系统项，不列
_SKIP = {"explorer.exe", "textinputhost.exe", "searchhost.exe", "shellexperiencehost.exe"}


def running_apps() -> list[dict]:
    """返回 [{proc, title}]，按进程名去重，保留最长窗口标题。"""
    apps: dict[str, str] = {}

    def cb(hwnd, _):
        if not win32gui.IsWindowVisible(hwnd):
            return
        title = (win32gui.GetWindowText(hwnd) or "").strip()
        if not title:
            return
        try:
            _, pid = win32process.GetWindowThreadProcessId(hwnd)
            proc = psutil.Process(pid).name()
        except Exception:
            return
        if proc.lower() in _SKIP:
            return
        cur = apps.get(proc)
        if cur is None or len(title) > len(cur):
            apps[proc] = title

    win32gui.EnumWindows(cb, None)
    return [{"proc": p, "title": t} for p, t in sorted(apps.items())]
