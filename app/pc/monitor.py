"""前台窗口检测（Windows）：进程名 + 窗口标题 + 锁屏判定 + 空闲检测。"""
from __future__ import annotations

import ctypes
from ctypes import wintypes

import psutil
import win32gui
import win32process

# 锁屏时的前台进程
LOCKED_PROCS = {"lockapp.exe"}


class _LASTINPUTINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.UINT), ("dwTime", wintypes.DWORD)]


def idle_seconds() -> float:
    """距上次键鼠输入的秒数（GetLastInputInfo）；失败时返回 0（当作有人操作）。"""
    try:
        lii = _LASTINPUTINFO()
        lii.cbSize = ctypes.sizeof(_LASTINPUTINFO)
        if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(lii)):
            return 0.0
        ticks = ctypes.windll.kernel32.GetTickCount64()
        return max(0.0, (ticks - lii.dwTime) / 1000.0)
    except Exception:
        return 0.0


def foreground() -> dict | None:
    """返回 {"proc": 进程名, "title": 窗口标题}；锁屏/无法确定时返回 None。"""
    try:
        hwnd = win32gui.GetForegroundWindow()
    except Exception:
        return None
    if not hwnd:
        return None
    proc = ""
    try:
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        proc = psutil.Process(pid).name()
    except Exception:
        pass
    title = ""
    try:
        title = win32gui.GetWindowText(hwnd) or ""
    except Exception:
        pass
    if proc.lower() in LOCKED_PROCS:
        return None
    return {"proc": proc, "title": title}
