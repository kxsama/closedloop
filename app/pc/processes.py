"""列出运行中带可见窗口的应用（供「关联应用」选择器使用）。"""
from __future__ import annotations

import os

import psutil
import win32gui
import win32process

# 无意义的系统项，不列
_SKIP = {
    "explorer.exe", "textinputhost.exe", "searchhost.exe",
    "shellexperiencehost.exe", "lockapp.exe", "dwm.exe",
    # 覆盖层 / 悬浮层：它们有窗口但不是在"用"，选中了永远不合规
    "nvidia overlay.exe", "nvidia share.exe", "gamebar.exe",
}

# 自己进程的 pid：列表里那条「python.exe — 选择关联应用」就是它，纯噪音、还容易选错
_SELF = os.getpid()


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
            if pid == _SELF:
                return
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
