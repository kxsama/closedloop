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
    """距上次键鼠输入的秒数（GetLastInputInfo）；失败时返回 0（当作有人操作）。

    **必须用 32 位的 GetTickCount()，不能用 GetTickCount64()**：
    `LASTINPUTINFO.dwTime` 是 32 位（DWORD），装的是 32 位 GetTickCount 的值。
    拿 64 位计数去减它，开机满 49.7 天（2^32 毫秒）后高位对不上，
    结果会变成几千万秒——于是每个 tick 都判定"你好像 N 分钟没操作了"，
    提醒永不停止。按代码推是必然，只是要连续开机一个多月才会撞上。
    两个 32 位值相减再按 2^32 取模，得到的就是正确的空闲时长。
    """
    try:
        lii = _LASTINPUTINFO()
        lii.cbSize = ctypes.sizeof(_LASTINPUTINFO)
        if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(lii)):
            return 0.0
        k32 = ctypes.windll.kernel32
        k32.GetTickCount.restype = wintypes.DWORD      # 默认是 c_int，会给出负数
        ticks = k32.GetTickCount()
        return max(0.0, ((ticks - lii.dwTime) & 0xFFFFFFFF) / 1000.0)
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
