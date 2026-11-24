# =================================================================
#  task_manager.py
#  Version: 2.3.0
#  Author: MUXSET (Refactored by Senior Software Engineer)
#  Description: 通用后台任务调度器。
#               采用世代隔离的 threading.Event 实现瞬时安全停止，
#               并提供各任务下次预计执行时间戳查询。
# =================================================================

import threading
import time
from typing import Callable, Dict, List, Optional
from logger import logger


class TaskManager:
    """管理后台工作线程，处理定时执行和优雅瞬时停止。"""

    def __init__(self):
        self.tasks: List[Dict] = []
        self.task_lock = threading.RLock()
        self.stop_event = threading.Event()
        self.threads: List[threading.Thread] = []
        self._meta_lock = threading.Lock()
        self._next_run_times: Dict[str, Optional[float]] = {}

    def add_task(self, func: Callable, interval_hr: float, name: str, initial_delay_hr: float = 0):
        self.tasks.append({
            "func": func,
            "interval_sec": max(10.0, interval_hr * 3600),
            "name": name,
            "initial_delay_sec": max(0.0, initial_delay_hr * 3600),
        })

    def _set_next_run_time(self, name: str, ts: Optional[float]):
        with self._meta_lock:
            self._next_run_times[name] = ts

    def get_next_run_time(self, name: str) -> Optional[float]:
        """返回指定任务下次计划执行的 Unix 时间戳；未运行或正在执行返回 None。"""
        with self._meta_lock:
            return self._next_run_times.get(name)

    def _task_worker(self, task: Dict, stop_evt: threading.Event):
        name = task["name"]
        interval = task["interval_sec"]
        initial_delay = task["initial_delay_sec"]

        logger.info(f"⚙️  [调度器] '{name}' 任务线程已就绪。")
        if initial_delay > 0:
            next_ts = time.time() + initial_delay
            self._set_next_run_time(name, next_ts)
            first_run_time = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(next_ts))
            logger.info(f"⏳ [调度器] '{name}' 首次计划运行于: {first_run_time}")
            if stop_evt.wait(timeout=initial_delay):
                self._set_next_run_time(name, None)
                return

        while not stop_evt.is_set():
            with self.task_lock:
                if stop_evt.is_set():
                    break
                self._set_next_run_time(name, None)
                logger.info(f"▶️  [任务] 开始执行 '{name}'...")
                try:
                    task["func"]()
                    logger.info(f"🏁 [任务] '{name}' 执行完成。")
                except Exception as e:
                    logger.error(f"❌ [任务] '{name}' 执行时发生错误: {e}")

            if stop_evt.is_set():
                break

            next_ts = time.time() + interval
            self._set_next_run_time(name, next_ts)
            next_run_time = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(next_ts))
            logger.info(f"😴 [调度器] '{name}' 进入休眠，下次运行: {next_run_time}")

            if stop_evt.wait(timeout=interval):
                break

        self._set_next_run_time(name, None)
        logger.info(f"⏹️  [调度器] '{name}' 任务线程已安全退出。")

    def start(self):
        if not self.tasks:
            return
        self.stop_event.set()
        current_stop_evt = threading.Event()
        self.stop_event = current_stop_evt
        self.threads = []
        with self._meta_lock:
            self._next_run_times.clear()

        for task_info in list(self.tasks):
            thread = threading.Thread(
                target=self._task_worker,
                args=(task_info, current_stop_evt),
                daemon=True,
            )
            self.threads.append(thread)
            thread.start()
        logger.info("🚀 [调度器] 所有定时任务线程已启动。")

    def stop(self):
        if not self.stop_event.is_set():
            logger.info("⏹️  [调度器] 正在向所有后台任务发送停止信号...")
            self.stop_event.set()
        with self._meta_lock:
            self._next_run_times.clear()

    @property
    def is_running(self) -> bool:
        """检查当前世代是否有任务线程仍在运行。"""
        return not self.stop_event.is_set() and any(t.is_alive() for t in self.threads)
