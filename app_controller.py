# =================================================================
#  app_controller.py
#  Version: 2.3.0
#  Description: 纯 Python 核心业务控制器（状态机 + 调度协调 + 观察者事件）。
#               完全解耦桌面 GUI 与 Web 面板，所有后台任务均以显式 username
#               快照隔离运行，消除跨账号数据污染与 UI 线程竞态。
# =================================================================

import os
import threading
import time
import uuid
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Tuple

import channel_sweep
import config_manager
import get_token
import http_utils
import liked_cache
import report_exporter
import task_manager
import tbea_client
from logger import logger


class AppController:
    """应用核心控制器：管理扫描、自动挂机、凭据刷新、导出与多账号状态。"""

    def __init__(self):
        config_manager.ensure_config_exists()

        self.scheduler = task_manager.TaskManager()
        self.scan_stop_event = threading.Event()
        self.scan_thread: Optional[threading.Thread] = None

        self._state_lock = threading.RLock()
        self._scan_lock = threading.Lock()
        self._token_lock = threading.Lock()
        self._listeners_lock = threading.Lock()
        self._listeners: List[Callable[[str], None]] = []
        self._shutdown_event = threading.Event()
        self._bg_started: bool = False
        self._bg_thread: Optional[threading.Thread] = None
        self._verify_jobs: Dict[str, Dict[str, Any]] = {}

        self.auto_mode_running: bool = False
        self.is_scanning: bool = False
        self.is_refreshing_token: bool = False
        self.is_exporting: bool = False
        self.token_valid: Optional[bool] = None
        self.token_checking: bool = False
        self.scan_progress: Optional[Dict[str, Any]] = None
        self.last_export_path: Optional[str] = None

        web_cfg = config_manager.get_web_panel_config()
        self.web_info: Dict[str, Any] = {
            "enabled": web_cfg["enabled"],
            "port": web_cfg["port"],
            "allow_lan": web_cfg["allow_lan"],
            "running": False,
            "url": f"http://localhost:{web_cfg['port']}",
            "lan_url": f"http://localhost:{web_cfg['port']}",
            "error": "",
        }

    # ============================
    # 观察者事件订阅
    # ============================

    def subscribe(self, listener: Callable[[str], None]):
        with self._listeners_lock:
            if listener not in self._listeners:
                self._listeners.append(listener)

    def unsubscribe(self, listener: Callable[[str], None]):
        with self._listeners_lock:
            if listener in self._listeners:
                self._listeners.remove(listener)

    def _emit(self, event_type: str = "state"):
        with self._listeners_lock:
            callbacks = list(self._listeners)
        for cb in callbacks:
            try:
                cb(event_type)
            except Exception as e:
                logger.debug(f"Observer callback error ({event_type}): {e}")

    def update_web_info(self, info: Dict[str, Any]):
        with self._state_lock:
            self.web_info.update(info)
        self._emit("state")

    @staticmethod
    def _is_current_account(username: Optional[str]) -> bool:
        if not username:
            return False
        curr_user, _ = config_manager.get_credentials()
        return curr_user == username

    def _on_progress(self, payload: Dict[str, Any]):
        total = payload.get("total") or 0
        current = payload.get("current") or 0
        ratio = min(1.0, max(0.0, current / total)) if total > 0 else 0.0
        enriched = dict(payload)
        enriched["ratio"] = ratio
        with self._state_lock:
            self.scan_progress = enriched
        self._emit("progress")

    # ============================
    # 启动自检 & 定时健康巡检
    # ============================

    def start_background_services(self, health_interval_sec: float = 300.0):
        """启动独立的后台 Token 健康巡检线程（完全脱离 GUI 框架定时器）。"""
        with self._state_lock:
            if self._bg_started:
                return
            self._bg_started = True
            self._shutdown_event.clear()

        def _health_loop():
            while not self._shutdown_event.wait(timeout=health_interval_sec):
                try:
                    self.check_token_health_async(refresh_if_expired=False)
                except Exception as e:
                    logger.debug(f"Background health tick error: {e}")

        self._bg_thread = threading.Thread(target=_health_loop, daemon=True)
        self._bg_thread.start()

    def startup_check_async(self, username: Optional[str] = None):
        target_user = username or config_manager.get_credentials()[0]
        if not target_user:
            with self._state_lock:
                self.token_valid = False
                self.token_checking = False
            self._emit("state")
            return

        threading.Thread(
            target=self._startup_check_worker,
            args=(target_user,),
            daemon=True,
        ).start()

    def _startup_check_worker(self, target_user: str):
        _, password = config_manager.get_credentials(username=target_user)
        if not password:
            if self._is_current_account(target_user):
                with self._state_lock:
                    self.token_valid = False
                    self.token_checking = False
                self._emit("state")
            return

        if self._is_current_account(target_user):
            with self._state_lock:
                self.token_checking = True
            self._emit("state")

        token = config_manager.get_token(username=target_user)
        cookies = config_manager.get_ejia_cookies(username=target_user)
        token_ok = http_utils.check_token_valid(token)
        cookie_ok = http_utils.check_ejia_session_valid(cookies)

        if not self._is_current_account(target_user):
            return

        if token_ok is None and cookie_ok is None:
            logger.warning("⚠️ [自检] 网络连接异常，暂时无法验证凭据状态。")
            with self._state_lock:
                self.token_valid = None
                self.token_checking = False
            self._emit("state")
            return

        if token_ok is True and cookie_ok is not False:
            logger.info(f"✅ ({target_user}) Token 与会话状态有效。")
            with self._state_lock:
                self.token_valid = True
                self.token_checking = False
            self._emit("state")
        else:
            with self._state_lock:
                self.token_valid = False
                self.token_checking = False
            self._emit("state")
            logger.info(f"🔄 [自检] ({target_user}) 检测到登录凭据已过期，正在后台自动刷新...")
            if not self.refresh_token_sync(username=target_user):
                return

        if not self._is_current_account(target_user):
            return

        if config_manager.get_auto_check_monthly_total() and not self.is_scanning:
            sd = datetime.now().strftime("%Y-%m-01")
            ed = datetime.now().strftime("%Y-%m-%d")
            res = channel_sweep.run_sweep(
                start_date=sd,
                end_date=ed,
                dry_run=True,
                username=target_user,
            )
            if res[0] == -1 and not self.is_scanning and self._is_current_account(target_user):
                if self.refresh_token_sync(username=target_user):
                    channel_sweep.run_sweep(
                        start_date=sd,
                        end_date=ed,
                        dry_run=True,
                        username=target_user,
                    )
            self._emit("stats")

    def check_token_health_async(self, refresh_if_expired: bool = False):
        target_user = config_manager.get_credentials()[0]
        if not target_user:
            with self._state_lock:
                self.token_valid = False
                self.token_checking = False
            self._emit("state")
            return

        def worker():
            if self.is_refreshing_token:
                return
            with self._state_lock:
                self.token_checking = True
            self._emit("state")

            token = config_manager.get_token(username=target_user)
            cookies = config_manager.get_ejia_cookies(username=target_user)
            if not token or not cookies:
                if self._is_current_account(target_user):
                    with self._state_lock:
                        self.token_valid = False
                        self.token_checking = False
                    self._emit("state")
                if refresh_if_expired and not self.is_scanning:
                    self.refresh_token_sync(username=target_user)
                return

            tok_ok = http_utils.check_token_valid(token)
            ses_ok = http_utils.check_ejia_session_valid(cookies)

            if not self._is_current_account(target_user):
                return

            if tok_ok is True and ses_ok is not False:
                with self._state_lock:
                    self.token_valid = True
                    self.token_checking = False
                self._emit("state")
            elif tok_ok is False or ses_ok is False:
                with self._state_lock:
                    self.token_valid = False
                    self.token_checking = False
                self._emit("state")
                if (refresh_if_expired or self.auto_mode_running) and not self.is_scanning:
                    logger.info(f"🔄 [巡检] ({target_user}) 检测到凭据已过期，自动续期中...")
                    self.refresh_token_sync(username=target_user)
            else:
                with self._state_lock:
                    self.token_valid = None
                    self.token_checking = False
                self._emit("state")

        threading.Thread(target=worker, daemon=True).start()

    # ============================
    # 凭据刷新
    # ============================

    def refresh_token_sync(self, username: Optional[str] = None) -> bool:
        target_user = username or config_manager.get_credentials()[0]
        if not target_user:
            logger.error("❌ 请先在『设置』中配置工号与密码。")
            with self._state_lock:
                self.token_valid = False
            self._emit("state")
            return False

        _, password = config_manager.get_credentials(username=target_user)
        if not password:
            logger.error(f"❌ 账号 {target_user} 缺少密码配置。")
            if self._is_current_account(target_user):
                with self._state_lock:
                    self.token_valid = False
                self._emit("state")
            return False

        if not self._token_lock.acquire(blocking=False):
            logger.info("⏳ 正在等待另一个凭据刷新任务完成...")
            with self._token_lock:
                return bool(config_manager.get_token(username=target_user))

        with self._state_lock:
            self.is_refreshing_token = True
            self.token_checking = False
        self._emit("state")

        try:
            data = get_token.fetch_token_sync(target_user, password)
            if data:
                # 确认账号在漫长的浏览器抓取期间未被删除
                if config_manager.get_credentials(username=target_user)[0] == target_user:
                    config_manager.save_token(data, username=target_user)
                logger.info(f"✅ ({target_user}) 凭据已更新。")
                if self._is_current_account(target_user):
                    with self._state_lock:
                        self.token_valid = True
                return True

            logger.error(f"❌ ({target_user}) 凭据获取失败。")
            if self._is_current_account(target_user):
                with self._state_lock:
                    self.token_valid = False
            return False
        except Exception as e:
            logger.error(f"❌ 刷新凭据异常: {e}")
            if self._is_current_account(target_user):
                with self._state_lock:
                    self.token_valid = False
            return False
        finally:
            with self._state_lock:
                self.is_refreshing_token = False
            self._token_lock.release()
            self._emit("state")

    def refresh_token_async(self, username: Optional[str] = None, from_remote: bool = False) -> bool:
        with self._state_lock:
            if self.is_scanning or self.is_refreshing_token or self.is_exporting:
                msg = "当前正在执行扫描、导出或刷新凭据，请稍候再试。"
                logger.warning(f"⚠️ {'[远程面板] ' if from_remote else ''}{msg}")
                return False

        target_user = username or config_manager.get_credentials()[0]
        if not target_user:
            logger.error("❌ 请先配置账号后再刷新凭据。")
            return False

        logger.info(f"\n🔄 收到{'远程' if from_remote else '手动'}刷新凭据请求 ({target_user})...")
        threading.Thread(target=self.refresh_token_sync, args=(target_user,), daemon=True).start()
        return True

    # ============================
    # 扫描执行
    # ============================

    def start_scan(
        self,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        username: Optional[str] = None,
        multi_account: bool = False,
    ) -> bool:
        with self._state_lock:
            if self.is_scanning or self.is_exporting or self._scan_lock.locked():
                logger.warning("⚠️ 已有扫描或导出任务正在运行中，已忽略重复触发。")
                return False
            self.is_scanning = True
            self.scan_stop_event.clear()
            self.scan_progress = {
                "stage": "prepare",
                "channel": "",
                "current": 0,
                "total": 0,
                "liked": 0,
                "skipped": 0,
                "message": "正在准备扫描环境...",
                "ratio": 0.0,
            }

        target_user = username or config_manager.get_credentials()[0]
        self._emit("state")

        self.scan_thread = threading.Thread(
            target=self._sweep_worker,
            args=(start_date, end_date, target_user, multi_account),
            daemon=True,
        )
        self.scan_thread.start()
        return True

    def _run_single_account_sweep(self, start_date: str, end_date: str, target_user: str):
        logger.info(f"\n{'=' * 40}")
        logger.info(f"  📡 频道扫描 ({target_user}): {start_date} ~ {end_date}")
        logger.info(f"{'=' * 40}")

        token = config_manager.get_token(username=target_user)
        cookies = config_manager.get_ejia_cookies(username=target_user)
        if not token or not cookies:
            logger.warning(f"🔄 ({target_user}) 本地凭据为空，启动无头登录获取凭据...")
            if not self.refresh_token_sync(username=target_user):
                logger.error(f"❌ ({target_user}) 登录获取凭据失败，本次扫描中止。")
                return

        def progress_wrapper(payload: Dict[str, Any]):
            enriched = dict(payload)
            enriched["username"] = target_user
            self._on_progress(enriched)

        total, _, _ = channel_sweep.run_sweep(
            start_date,
            end_date,
            self.scan_stop_event,
            username=target_user,
            progress_cb=progress_wrapper,
        )

        if total == -1 and not self.scan_stop_event.is_set():
            logger.info(f"🔄 ({target_user}) 登录凭据已过期，正在自动刷新并重试扫描...")
            if self.refresh_token_sync(username=target_user):
                channel_sweep.run_sweep(
                    start_date,
                    end_date,
                    self.scan_stop_event,
                    username=target_user,
                    progress_cb=progress_wrapper,
                )
            else:
                logger.error(f"❌ ({target_user}) 凭据自动刷新失败。")

    def _sweep_worker(
        self,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        username: Optional[str] = None,
        multi_account: bool = False,
    ):
        if not self._scan_lock.acquire(blocking=False):
            logger.warning("⚠️ 扫描锁占用中，跳过重叠扫描。")
            return

        with self._state_lock:
            self.is_scanning = True
            self.scan_stop_event.clear()
        self._emit("state")

        try:
            sd = start_date or datetime.now().strftime("%Y-%m-01")
            ed = end_date or datetime.now().strftime("%Y-%m-%d")

            if multi_account:
                accounts = [a["username"] for a in config_manager.get_all_accounts() if a.get("username")]
                if not accounts:
                    logger.error("❌ 未配置任何账号，无法扫描。")
                    return
                for idx, acc_user in enumerate(accounts, 1):
                    if self.scan_stop_event.is_set():
                        break
                    if len(accounts) > 1:
                        logger.info(f"👥 [多账号轮询] 正在处理第 {idx}/{len(accounts)} 个账号: {acc_user}")
                    self._run_single_account_sweep(sd, ed, acc_user)
            else:
                target_user = username or config_manager.get_credentials()[0]
                if not target_user:
                    logger.error("❌ 请先在『设置』中添加账号后再开始扫描。")
                    return
                self._run_single_account_sweep(sd, ed, target_user)
        except Exception as e:
            logger.error(f"❌ 扫描线程发生未预期异常: {e}")
        finally:
            with self._state_lock:
                self.is_scanning = False
                self.scan_progress = None
            self._scan_lock.release()
            self._emit("stats")
            self._emit("state")

    def stop_current_task(self):
        logger.info("⏹️ 停止请求已发送，正在安全中断当前任务...")
        self.scan_stop_event.set()

    # ============================
    # 月报导出
    # ============================

    def export_report_async(
        self,
        target_month: Optional[str] = None,
        username: Optional[str] = None,
    ) -> bool:
        with self._state_lock:
            if self.is_exporting or self.is_scanning:
                logger.warning("⚠️ 正在执行扫描或导出任务，请稍候再试。")
                return False
            self.is_exporting = True
            self.scan_stop_event.clear()
            self.scan_progress = {
                "stage": "export",
                "channel": "",
                "current": 0,
                "total": 0,
                "liked": 0,
                "skipped": 0,
                "message": "正在准备导出月度报告...",
                "ratio": 0.0,
            }

        target_user = username or config_manager.get_credentials()[0]
        self._emit("state")

        def worker():
            try:
                if not target_user:
                    logger.error("❌ 请先配置账号后再导出月报。")
                    return

                logger.info(f"\n📊 准备导出月度点赞报告 ({target_user})...")
                tok_ok = http_utils.check_token_valid(config_manager.get_token(username=target_user))
                ses_ok = http_utils.check_ejia_session_valid(config_manager.get_ejia_cookies(username=target_user))
                if tok_ok is False or ses_ok is False:
                    logger.info("🔄 导出前检测到凭据已过期，正在自动刷新...")
                    if not self.refresh_token_sync(username=target_user):
                        logger.error("❌ 凭据刷新失败，无法导出报告。")
                        return

                fp = ""
                try:
                    fp = report_exporter.export_monthly_report(
                        self.scan_stop_event,
                        target_month=target_month,
                        username=target_user,
                        progress_cb=self._on_progress,
                    )
                except tbea_client.AuthExpiredError:
                    if not self.scan_stop_event.is_set():
                        logger.info("🔄 导出过程中凭据失效，自动续期后重试一次...")
                        if self.refresh_token_sync(username=target_user):
                            fp = report_exporter.export_monthly_report(
                                self.scan_stop_event,
                                target_month=target_month,
                                username=target_user,
                                progress_cb=self._on_progress,
                            )

                if fp:
                    with self._state_lock:
                        self.last_export_path = fp
                    self._emit("export")
                else:
                    logger.warning("⚠️ 月报未生成或已取消。")
            except Exception as e:
                logger.error(f"❌ 导出月报发生异常: {e}")
            finally:
                with self._state_lock:
                    self.is_exporting = False
                    self.scan_progress = None
                self._emit("state")

        threading.Thread(target=worker, daemon=True).start()
        return True

    # ============================
    # 自动挂机调度
    # ============================

    def _scheduled_sweep_entry(self):
        sd = datetime.now().strftime("%Y-%m-01")
        ed = datetime.now().strftime("%Y-%m-%d")
        multi = config_manager.get_multi_account_auto()
        target_user = config_manager.get_credentials()[0]
        self._sweep_worker(sd, ed, username=target_user, multi_account=multi)

    def _scheduled_token_entry(self):
        if config_manager.get_multi_account_auto():
            for a in config_manager.get_all_accounts():
                if not self.auto_mode_running:
                    break
                if a.get("username"):
                    self.refresh_token_sync(username=a["username"])
        else:
            self.refresh_token_sync()

    def _keep_session_alive(self):
        if config_manager.get_multi_account_auto():
            usernames = [a["username"] for a in config_manager.get_all_accounts() if a.get("username")]
        else:
            curr = config_manager.get_credentials()[0]
            usernames = [curr] if curr else []

        for uname in usernames:
            if not self.auto_mode_running:
                break
            cookies = config_manager.get_ejia_cookies(username=uname)
            if not cookies:
                continue
            ok = http_utils.check_ejia_session_valid(cookies)
            if ok is False:
                logger.warning(f"⚠️ [心跳保活] ({uname}) 检测到门户会话已过期，正在自动续期...")
                if not self.is_scanning:
                    self.refresh_token_sync(username=uname)
            elif ok is None:
                logger.warning(f"⚠️ [心跳保活] ({uname}) 网络连接波动，等待下次重试。")

    def toggle_auto_mode(self) -> Tuple[bool, str]:
        with self._state_lock:
            if self.auto_mode_running:
                self.auto_mode_running = False
                self.scan_stop_event.set()
                self.scheduler.stop()
                self.scheduler.tasks.clear()
                logger.info("⏹️ 自动挂机模式已关闭。")
                self._emit("state")
                return True, "自动挂机已停止"

            multi = config_manager.get_multi_account_auto()
            accounts = config_manager.get_all_accounts()
            username, password = config_manager.get_credentials()
            if multi:
                if not accounts:
                    logger.error("❌ 请先在『设置』中添加账号后再启动自动挂机。")
                    return False, "请先添加账号后再启动自动挂机"
            elif not username or not password:
                logger.error("❌ 请先在『设置』中添加账号后再启动自动挂机。")
                return False, "请先添加账号后再启动自动挂机"

            self.auto_mode_running = True
            sh, th = config_manager.get_intervals()
            self.scheduler.tasks.clear()
            self.scheduler.add_task(
                self._scheduled_sweep_entry,
                sh,
                "频道扫描",
                initial_delay_hr=sh,
            )
            self.scheduler.add_task(
                self._scheduled_token_entry,
                th,
                "Token刷新",
                initial_delay_hr=th,
            )
            self.scheduler.add_task(
                self._keep_session_alive,
                5.0 / 60.0,
                "心跳保活",
                initial_delay_hr=5.0 / 60.0,
            )
            self.scheduler.start()
            mode_label = "多账号依次轮询" if multi else f"单账号 ({username})"
            logger.info(f"🚀 自动挂机模式已启动 [{mode_label}]。")
            self._emit("state")

        self.start_scan(
            datetime.now().strftime("%Y-%m-01"),
            datetime.now().strftime("%Y-%m-%d"),
            username=username,
            multi_account=multi,
        )
        return True, "自动挂机已启动"

    # ============================
    # 账号增删切 & 缓存管理
    # ============================

    def switch_account(self, username: str) -> Tuple[bool, str]:
        with self._state_lock:
            if self.is_scanning or self.auto_mode_running or self.is_exporting or self.is_refreshing_token:
                return False, "任务运行中，请先停止后再切换账号"

        for a in config_manager.get_all_accounts():
            if a["username"] == username:
                if a["index"] == config_manager.get_active_account_index():
                    return True, f"当前已是账号 {username}"
                config_manager.switch_account(a["index"])
                with self._state_lock:
                    self.token_valid = None
                    self.token_checking = True
                logger.info(
                    f"🔀 已切换至账号: {username}（本地缓存 {liked_cache.get_cache_size(username=username)} 篇）"
                )
                self._emit("stats")
                self._emit("state")
                self.startup_check_async(username=username)
                return True, f"已切换至账号 {username}"

        return False, f"未找到账号 {username}"

    def add_or_update_account_async(
        self,
        username: str,
        password: str,
        on_done: Callable[[bool, str], None],
    ):
        clean_user = username.strip()
        clean_pass = password.strip()
        if not clean_user or not clean_pass:
            on_done(False, "请输入工号和密码")
            return

        def worker():
            with self._token_lock:
                with self._state_lock:
                    self.is_refreshing_token = True
                    self.token_checking = False
                self._emit("state")
                try:
                    data = get_token.fetch_token_sync(clean_user, clean_pass)
                except Exception as e:
                    logger.error(f"❌ 验证账号 {clean_user} 异常: {e}")
                    data = None
                finally:
                    with self._state_lock:
                        self.is_refreshing_token = False

            if data:
                existing = next(
                    (a for a in config_manager.get_all_accounts() if a["username"] == clean_user),
                    None,
                )
                if existing is not None:
                    config_manager.update_account_password(clean_user, clean_pass)
                    idx = existing["index"]
                else:
                    idx = config_manager.add_account(clean_user, clean_pass)

                config_manager.switch_account(idx)
                config_manager.save_token(data, username=clean_user)
                with self._state_lock:
                    self.token_valid = True
                    self.token_checking = False

                logger.info(f"✅ 账号 {clean_user} 已验证并设为当前账号！")
                self._emit("stats")
                self._emit("state")
                self.startup_check_async(username=clean_user)
                on_done(True, f"账号 {clean_user} 验证成功并已设为当前账号")
            else:
                logger.error(f"❌ 账号 {clean_user} 添加/更新失败：登录验证未通过")
                self._emit("state")
                on_done(False, "验证失败：请检查工号、密码或网络连接")

        threading.Thread(target=worker, daemon=True).start()

    def start_account_verify_job(self, username: str, password: str) -> str:
        """启动异步账号验证任务并追踪状态，供桌面端与 Web 端轮询结果。"""
        clean_user = username.strip()
        job_id = uuid.uuid4().hex[:12]
        with self._state_lock:
            if len(self._verify_jobs) > 20:
                oldest_done = [
                    jid for jid, info in self._verify_jobs.items()
                    if info.get("status") != "running"
                ]
                for jid in oldest_done[:10]:
                    self._verify_jobs.pop(jid, None)
            self._verify_jobs[job_id] = {
                "job_id": job_id,
                "username": clean_user,
                "status": "running",
                "msg": "正在启动无头浏览器验证账号凭据...",
                "created_at": time.time(),
            }

        def on_done(ok: bool, msg: str):
            with self._state_lock:
                if job_id in self._verify_jobs:
                    self._verify_jobs[job_id]["status"] = "success" if ok else "error"
                    self._verify_jobs[job_id]["msg"] = msg

        self.add_or_update_account_async(clean_user, password, on_done)
        return job_id

    def get_verify_job(self, job_id: str) -> Optional[Dict[str, Any]]:
        with self._state_lock:
            job = self._verify_jobs.get(job_id)
            return dict(job) if job else None

    def remove_account(self, index: int) -> Tuple[bool, str]:
        with self._state_lock:
            if self.is_scanning or self.auto_mode_running or self.is_exporting or self.is_refreshing_token:
                return False, "任务运行中，请先停止后再删除账号"

        removed_name = config_manager.remove_account(index)
        if removed_name is None:
            return False, "未找到该账号"

        liked_cache.remove_user_cache(removed_name)
        with self._state_lock:
            self.token_valid = None
            self.token_checking = False
        self._emit("stats")
        self._emit("state")

        curr_user, _ = config_manager.get_credentials()
        if curr_user:
            self.startup_check_async(username=curr_user)
        return True, f"已删除账号 {removed_name}"

    def clear_cache(self, username: Optional[str] = None) -> int:
        target_user = username or config_manager.get_credentials()[0]
        if not target_user:
            return 0
        count = liked_cache.clear_cache(username=target_user)
        self._emit("stats")
        return count

    def clear_current_cache(self) -> int:
        return self.clear_cache(username=None)

    def notify_settings_saved(self):
        """设置保存后通知所有视图刷新状态与频道配置。"""
        self._emit("stats")
        self._emit("state")

    # ============================
    # 统一状态快照 & 优雅关闭
    # ============================

    def get_status_snapshot(self) -> Dict[str, Any]:
        username, _ = config_manager.get_credentials()
        accounts = config_manager.get_all_accounts()
        active_idx = config_manager.get_active_account_index()
        stats = config_manager.get_sweep_stats(username=username)
        m_stats = config_manager.get_monthly_stats(username=username)
        channels = config_manager.get_channels()
        refresh_ts = config_manager.get_token_refresh_time(username=username)
        _, token_interval_hr = config_manager.get_intervals()
        multi_auto = config_manager.get_multi_account_auto()

        month_total = m_stats.get("monthly_total")
        month_liked = m_stats.get("monthly_liked")

        completion_rate: Optional[float] = None
        pending_count: Optional[int] = None
        if isinstance(month_total, int) and isinstance(month_liked, int):
            if month_total > 0:
                completion_rate = min(100.0, round(month_liked * 100.0 / month_total, 1))
                pending_count = max(0, month_total - month_liked)
            else:
                completion_rate = 100.0
                pending_count = 0

        last_time = stats.get("last_sweep_time", "")
        last_scan = http_utils.format_relative_time(last_time)

        token_refresh_str = (
            time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(refresh_ts))
            if refresh_ts > 0
            else "从未"
        )
        token_next_str = "未知"
        if refresh_ts > 0:
            next_ts = refresh_ts + token_interval_hr * 3600
            token_next_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(next_ts))

        with self._state_lock:
            auto_mode = self.auto_mode_running
            is_scanning = self.is_scanning
            is_exporting = self.is_exporting
            is_refreshing = self.is_refreshing_token
            token_valid = self.token_valid
            token_checking = self.token_checking
            progress = dict(self.scan_progress) if self.scan_progress else None
            export_path = self.last_export_path
            web_info = dict(self.web_info)

        next_auto_scan_text = ""
        if auto_mode:
            next_ts = self.scheduler.get_next_run_time("频道扫描")
            if next_ts:
                rem_sec = max(0, int(next_ts - time.time()))
                if rem_sec < 60:
                    next_auto_scan_text = "距下次自动扫描不足 1 分钟"
                elif rem_sec < 3600:
                    next_auto_scan_text = f"距下次自动扫描约 {rem_sec // 60} 分钟"
                else:
                    h, m = rem_sec // 3600, (rem_sec % 3600) // 60
                    next_auto_scan_text = f"距下次自动扫描约 {h}h{m:02d}m"

        if not username:
            token_status, token_color = "未设置", "gray"
        elif is_refreshing:
            token_status, token_color = "刷新中", "orange"
        elif token_checking and token_valid is None:
            token_status, token_color = "检测中", "orange"
        elif token_valid is True:
            token_status, token_color = "有效", "green"
        elif token_valid is False:
            token_status, token_color = "过期", "red"
        else:
            token_status, token_color = "未知", "orange"

        if is_scanning:
            status_text = "扫描执行中"
        elif is_exporting:
            status_text = "月报导出中"
        elif is_refreshing:
            status_text = "凭据刷新中"
        elif auto_mode:
            status_text = "自动挂机中"
        else:
            status_text = "空闲待机"

        enriched_accounts = []
        for a in accounts:
            uname = a.get("username", "")
            idx = a.get("index", -1)
            acc_sweep = config_manager.get_sweep_stats(username=uname) if uname else {}
            acc_monthly = config_manager.get_monthly_stats(username=uname) if uname else {}
            acc_last_ts = acc_sweep.get("last_sweep_time", "")
            enriched_accounts.append({
                "index": idx,
                "username": uname,
                "is_active": idx == active_idx,
                "cache_size": liked_cache.get_cache_size(username=uname) if uname else 0,
                "has_token": bool(config_manager.get_token(username=uname)) if uname else False,
                "monthly_total": acc_monthly.get("monthly_total"),
                "monthly_liked": acc_monthly.get("monthly_liked"),
                "last_scan": http_utils.format_relative_time(acc_last_ts),
                "last_sweep_time": acc_last_ts,
            })

        has_report = bool(export_path and os.path.isfile(export_path))

        return {
            "username": username or "",
            "accounts": enriched_accounts,
            "active_account_index": active_idx,
            "cache_size": liked_cache.get_cache_size(username=username) if username else 0,
            "appearance_mode": config_manager.get_appearance_mode(),
            "auto_mode": auto_mode,
            "multi_account_auto": multi_auto,
            "is_scanning": is_scanning,
            "is_exporting": is_exporting,
            "is_refreshing_token": is_refreshing,
            "is_busy": is_scanning or is_exporting or is_refreshing,
            "status_text": status_text,
            "total": month_total,
            "liked": month_liked,
            "completion_rate": completion_rate,
            "pending_count": pending_count,
            "last_sweep": {
                "total": stats.get("total", 0),
                "liked": stats.get("liked", 0),
                "skipped": stats.get("skipped", 0),
                "last_sweep_time": last_time,
            },
            "last_scan": last_scan,
            "channel_count": len(channels),
            "next_auto_scan_text": next_auto_scan_text,
            "token_status": token_status,
            "token_color": token_color,
            "token_refresh_time_str": token_refresh_str,
            "token_next_refresh_str": token_next_str,
            "scan_progress": progress,
            "has_report": has_report,
            "last_export_filename": os.path.basename(export_path) if has_report else "",
            "web_info": web_info,
        }

    def shutdown(self):
        self._shutdown_event.set()
        with self._state_lock:
            self.auto_mode_running = False
            self._bg_started = False
        self.scan_stop_event.set()
        self.scheduler.stop()
        self.scheduler.tasks.clear()
        liked_cache.flush()
