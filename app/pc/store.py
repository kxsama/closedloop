"""JSON 持久化：计划、会话、账本、事件日志。本地优先，无服务器。"""
from __future__ import annotations

import datetime as dt
import json
import os
import tempfile
import threading

_LOCK = threading.Lock()


class Store:
    def __init__(self, data_dir: str):
        self.data_dir = data_dir
        os.makedirs(data_dir, exist_ok=True)

    def path(self, name: str) -> str:
        return os.path.join(self.data_dir, name)

    def load(self, name: str, default):
        p = self.path(name)
        if not os.path.exists(p):
            return default
        try:
            with open(p, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return default

    def save(self, name: str, data) -> None:
        with _LOCK:
            fd, tmp = tempfile.mkstemp(dir=self.data_dir, suffix=".tmp")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
                os.replace(tmp, self.path(name))
            except Exception:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                raise

    def append_event(self, record: dict) -> None:
        record = dict(record)
        record["ts"] = dt.datetime.now().isoformat(timespec="seconds")
        with _LOCK:
            with open(self.path("events.jsonl"), "a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
