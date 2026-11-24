# =================================================================
#  web_panel.py
#  Version: 2.3.0
#  Author: MUXSET
#  Description: 双引擎本地 Web 服务（桌面原生窗口回环引擎 + 可选局域网远程面板）。
#               - start_desktop_server(): 127.0.0.1 随机空闲端口 + 启动令牌免 PIN 鉴权
#               - start_web_panel(): 可选局域网访问端口（默认 5050）+ PIN 口令保护
#               - 统一提供全量 REST API（状态快照、增量日志、扫描、账号验证、设置、下载）
# =================================================================

import hashlib
import hmac
import logging
import os
import secrets
import socket
import threading
from datetime import datetime
from functools import wraps
from typing import Optional, Tuple

from flask import Flask, jsonify, make_response, render_template, request, send_file
from werkzeug.serving import make_server

import app_context
import config_manager
import http_utils
import logger as logger_mod
from logger import logger
from version import __app_name__, __version__

TEMPLATE_DIR = os.path.join(app_context.RESOURCE_PATH, "templates")
STATIC_DIR = os.path.join(app_context.RESOURCE_PATH, "static")
app = Flask(
    __name__,
    template_folder=TEMPLATE_DIR,
    static_folder=STATIC_DIR,
    static_url_path="/static",
)
app.config["SEND_FILE_MAX_AGE_DEFAULT"] = 0

_AUTH_NONCE = secrets.token_bytes(32)
_AUTH_COOKIE_NAME = "tbe_panel_auth"
_DESKTOP_COOKIE_NAME = "tbe_desktop_session"
_DESKTOP_BOOT_TOKEN = secrets.token_urlsafe(32)

_controller = None

# 局域网可选远程服务
_server_lock = threading.Lock()
_server = None
_server_thread: Optional[threading.Thread] = None

# 桌面窗口专用回环服务（永不受设置变更影响）
_desktop_lock = threading.Lock()
_desktop_server = None
_desktop_thread: Optional[threading.Thread] = None


def set_controller(controller):
    """注入 AppController 实例。"""
    global _controller
    _controller = controller


def set_gui_app(gui_or_ctrl):
    """兼容旧调用签名。"""
    if hasattr(gui_or_ctrl, "controller"):
        set_controller(gui_or_ctrl.controller)
    else:
        set_controller(gui_or_ctrl)


def _make_auth_token(pin: str) -> str:
    return hmac.new(_AUTH_NONCE, pin.encode("utf-8"), hashlib.sha256).hexdigest()


def _make_desktop_session_token() -> str:
    return hmac.new(
        _AUTH_NONCE, f"desktop:{_DESKTOP_BOOT_TOKEN}".encode("utf-8"), hashlib.sha256
    ).hexdigest()


def _is_loopback() -> bool:
    return request.remote_addr in ("127.0.0.1", "::1", None)


def _is_desktop_request() -> bool:
    if not _is_loopback():
        return False
    cookie_val = request.cookies.get(_DESKTOP_COOKIE_NAME, "")
    if cookie_val and hmac.compare_digest(cookie_val, _make_desktop_session_token()):
        return True
    dt = request.args.get("dt", "")
    return bool(dt and hmac.compare_digest(dt, _DESKTOP_BOOT_TOKEN))


def _is_authorized() -> bool:
    if _is_desktop_request():
        return True
    pin = config_manager.get_web_panel_config().get("access_pin", "").strip()
    if not pin:
        return True
    expected = _make_auth_token(pin)
    cookie_tok = request.cookies.get(_AUTH_COOKIE_NAME, "")
    if cookie_tok and hmac.compare_digest(cookie_tok, expected):
        return True
    header_pin = request.headers.get("X-Access-Pin", "").strip()
    return bool(header_pin and hmac.compare_digest(header_pin, pin))


def _require_auth(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if not _is_authorized():
            return jsonify({"ok": False, "auth_required": True, "msg": "需要输入访问口令"}), 401
        return fn(*args, **kwargs)
    return wrapper


# ============================
# 页面与鉴权路由
# ============================

@app.route("/")
def index():
    dt = request.args.get("dt", "")
    valid_boot = _is_loopback() and bool(dt) and hmac.compare_digest(dt, _DESKTOP_BOOT_TOKEN)
    is_desktop = valid_boot or _is_desktop_request()
    resp = make_response(
        render_template(
            "app.html",
            app_name=__app_name__,
            version=__version__,
            is_desktop=is_desktop,
        )
    )
    if valid_boot:
        resp.set_cookie(
            _DESKTOP_COOKIE_NAME,
            _make_desktop_session_token(),
            httponly=True,
            samesite="Lax",
        )
    return resp


@app.route("/api/auth", methods=["POST"])
def api_auth():
    expected_pin = config_manager.get_web_panel_config().get("access_pin", "").strip()
    if not expected_pin or _is_desktop_request():
        return jsonify({"ok": True})

    data = request.get_json(silent=True) or {}
    provided = str(data.get("pin", "")).strip()
    if not provided or not hmac.compare_digest(provided, expected_pin):
        return jsonify({"ok": False, "msg": "访问口令错误"}), 401

    resp = make_response(jsonify({"ok": True}))
    resp.set_cookie(
        _AUTH_COOKIE_NAME,
        _make_auth_token(expected_pin),
        httponly=True,
        samesite="Lax",
        max_age=86400 * 7,
    )
    return resp


# ============================
# 核心业务 REST API
# ============================

@app.route("/api/status")
@_require_auth
def api_status():
    if not _controller:
        return jsonify({"ok": False, "msg": "控制器未就绪"}), 503
    snap = _controller.get_status_snapshot()
    snap["is_desktop"] = _is_desktop_request()
    return jsonify(snap)


@app.route("/api/logs")
@_require_auth
def api_logs():
    log_path = logger_mod.LOG_FILE
    cursor_arg = request.args.get("cursor", None)
    cursor: Optional[int] = None
    if cursor_arg is not None and cursor_arg != "":
        try:
            cursor = max(0, int(cursor_arg))
        except ValueError:
            cursor = None

    if not os.path.exists(log_path):
        return jsonify({"lines": [], "cursor": 0, "reset": cursor is None})

    try:
        with open(log_path, "r", encoding="utf-8") as f:
            all_lines = [line.rstrip("\n") for line in f]
    except Exception:
        return jsonify({"lines": [], "cursor": cursor or 0, "reset": False})

    total = len(all_lines)
    if cursor is None:
        return jsonify({"lines": all_lines[-200:], "cursor": total, "reset": True})

    if cursor > total:
        return jsonify({"lines": all_lines[-200:], "cursor": total, "reset": True})

    if cursor == 0 and total > 400:
        return jsonify({"lines": all_lines[-400:], "cursor": total, "reset": True})

    return jsonify({"lines": all_lines[cursor:], "cursor": total, "reset": False})


@app.route("/api/action", methods=["POST"])
@_require_auth
def api_action():
    if not _controller:
        return jsonify({"ok": False, "msg": "控制器未就绪"}), 503

    data = request.get_json(silent=True) or {}
    action = str(data.get("action", "")).strip()

    try:
        if action == "scan":
            sd = data.get("start_date")
            ed = data.get("end_date")
            if sd is not None or ed is not None:
                sd_str = str(sd or "").strip()
                ed_str = str(ed or "").strip()
                if not sd_str or not ed_str:
                    return jsonify({"ok": False, "msg": "请提供完整的起始与结束日期"}), 400
                try:
                    datetime.strptime(sd_str, "%Y-%m-%d")
                    datetime.strptime(ed_str, "%Y-%m-%d")
                except ValueError:
                    return jsonify({"ok": False, "msg": "日期格式须为 YYYY-MM-DD"}), 400
                if sd_str > ed_str:
                    return jsonify({"ok": False, "msg": "起始日期不能晚于结束日期"}), 400
            else:
                today = datetime.now().strftime("%Y-%m-%d")
                sd_str, ed_str = today, today

            ok = _controller.start_scan(sd_str, ed_str)
            return jsonify({"ok": ok, "msg": "扫描任务已启动" if ok else "当前已有任务正在进行"})

        if action == "sweep":
            sd = datetime.now().strftime("%Y-%m-01")
            ed = datetime.now().strftime("%Y-%m-%d")
            ok = _controller.start_scan(sd, ed)
            return jsonify({"ok": ok, "msg": "本月补漏扫描已启动" if ok else "当前已有任务正在进行"})

        if action == "toggle_auto":
            ok, msg = _controller.toggle_auto_mode()
            return jsonify({"ok": ok, "msg": msg})

        if action == "export":
            ok = _controller.export_report_async()
            return jsonify({"ok": ok, "msg": "月报导出任务已触发" if ok else "当前已有任务正在进行"})

        if action == "token":
            ok = _controller.refresh_token_async(from_remote=not _is_desktop_request())
            return jsonify({"ok": ok, "msg": "凭据刷新已启动" if ok else "当前正忙或未配置账号，请稍候"})

        if action == "check_token":
            if _controller.is_scanning or _controller.is_refreshing_token or _controller.is_exporting:
                return jsonify({"ok": False, "msg": "当前正在执行任务，请稍后再试"})
            if not config_manager.get_all_accounts():
                return jsonify({"ok": False, "msg": "请先添加账号后再校验凭据"})
            _controller.check_token_health_async(refresh_if_expired=True)
            return jsonify({"ok": True, "msg": "正在校验凭据有效性..."})

        if action == "stop":
            _controller.stop_current_task()
            return jsonify({"ok": True, "msg": "已发送停止信号"})

        if action == "switch_account":
            uname = str(data.get("username", "")).strip()
            if not uname:
                return jsonify({"ok": False, "msg": "缺少账号名称"}), 400
            ok, msg = _controller.switch_account(uname)
            return jsonify({"ok": ok, "msg": msg})

        if action == "clear_cache":
            if _controller.is_scanning:
                return jsonify({"ok": False, "msg": "扫描运行中，请先停止后再清空缓存"})
            uname = str(data.get("username", "")).strip() or None
            count = _controller.clear_cache(username=uname)
            return jsonify({"ok": True, "count": count, "msg": f"已清空 {count} 条本地缓存"})

        return jsonify({"ok": False, "msg": f"未知操作: {action}"}), 400
    except Exception as e:
        return jsonify({"ok": False, "msg": str(e)}), 500


# ============================
# 账号管理与设置 API
# ============================

@app.route("/api/accounts/verify", methods=["POST"])
@_require_auth
def api_accounts_verify():
    if not _controller:
        return jsonify({"ok": False, "msg": "控制器未就绪"}), 503
    data = request.get_json(silent=True) or {}
    username = str(data.get("username", "")).strip()
    password = str(data.get("password", "")).strip()
    if not username or not password:
        return jsonify({"ok": False, "msg": "请输入工号和密码"}), 400
    job_id = _controller.start_account_verify_job(username, password)
    return jsonify({"ok": True, "job_id": job_id})


@app.route("/api/accounts/verify/<job_id>")
@_require_auth
def api_accounts_verify_status(job_id: str):
    if not _controller:
        return jsonify({"ok": False, "msg": "控制器未就绪"}), 503
    job = _controller.get_verify_job(job_id)
    if job is None:
        return jsonify({"ok": False, "msg": "验证任务不存在"}), 404
    return jsonify({"ok": True, **job})


@app.route("/api/accounts/<int:index>", methods=["DELETE"])
@_require_auth
def api_accounts_delete(index: int):
    if not _controller:
        return jsonify({"ok": False, "msg": "控制器未就绪"}), 503
    ok, msg = _controller.remove_account(index)
    if not ok:
        status = 409 if "任务运行中" in msg else 404
        return jsonify({"ok": False, "msg": msg}), status
    return jsonify({"ok": True, "msg": msg})


@app.route("/api/settings", methods=["GET"])
@_require_auth
def api_settings_get():
    shr, thr = config_manager.get_intervals()
    return jsonify({
        "scan_interval_hours": shr,
        "token_refresh_interval_hours": thr,
        "auto_check_monthly_total": config_manager.get_auto_check_monthly_total(),
        "disable_system_proxy": config_manager.get_disable_system_proxy(),
        "multi_account_auto": config_manager.get_multi_account_auto(),
        "appearance_mode": config_manager.get_appearance_mode(),
        "channels": config_manager.get_channels(),
        "default_channels": config_manager.get_default_channels(),
        "web_panel": config_manager.get_web_panel_config(),
    })


@app.route("/api/settings", methods=["POST"])
@_require_auth
def api_settings_save():
    if not _controller:
        return jsonify({"ok": False, "msg": "控制器未就绪"}), 503
    if _controller.auto_mode_running or _controller.is_scanning or _controller.is_exporting:
        return jsonify({"ok": False, "msg": "自动挂机或任务运行中，请先停止后再修改设置"}), 409

    data = request.get_json(silent=True) or {}

    try:
        scan_iv = float(data.get("scan_interval_hours", 1.0))
        token_iv = float(data.get("token_refresh_interval_hours", 6.0))
        if scan_iv <= 0 or token_iv <= 0:
            raise ValueError
    except (TypeError, ValueError):
        return jsonify({"ok": False, "msg": "扫描与刷新间隔必须为大于 0 的数字"}), 400

    raw_channels = data.get("channels", [])
    if not isinstance(raw_channels, list) or not raw_channels:
        return jsonify({"ok": False, "msg": "目标频道不能为空，请至少保留 1 个频道"}), 400

    clean_channels = []
    seen_ids = set()
    for ch in raw_channels:
        if not isinstance(ch, dict):
            continue
        name = str(ch.get("name", "")).strip()
        cid = str(ch.get("id", "")).strip()
        if not name or not cid:
            return jsonify({"ok": False, "msg": "频道名称和 ID 不能为空"}), 400
        if cid not in seen_ids:
            seen_ids.add(cid)
            clean_channels.append({"name": name, "id": cid})
    if not clean_channels:
        return jsonify({"ok": False, "msg": "目标频道不能为空"}), 400

    web_in = data.get("web_panel")
    if not isinstance(web_in, dict):
        web_in = config_manager.get_web_panel_config()

    try:
        web_port = int(web_in.get("port", 5050))
        if not (1 <= web_port <= 65535):
            raise ValueError
    except (TypeError, ValueError):
        return jsonify({"ok": False, "msg": "Web 面板端口号必须为 1 ~ 65535 的整数"}), 400

    web_enabled = bool(web_in.get("enabled", True))
    web_lan = bool(web_in.get("allow_lan", True))
    web_pin = str(web_in.get("access_pin", "")).strip()

    old_web = config_manager.get_web_panel_config()

    config_manager.save_intervals(scan_iv, token_iv)
    config_manager.save_channels(clean_channels)
    config_manager.save_auto_check_monthly_total(bool(data.get("auto_check_monthly_total", True)))
    config_manager.save_disable_system_proxy(bool(data.get("disable_system_proxy", False)))
    config_manager.save_multi_account_auto(bool(data.get("multi_account_auto", False)))
    config_manager.save_web_panel_config(web_enabled, web_port, web_lan, web_pin)

    if "appearance_mode" in data:
        mode = str(data.get("appearance_mode", "System"))
        if mode in ("System", "Dark", "Light"):
            config_manager.save_appearance_mode(mode)

    if (
        old_web["enabled"] != web_enabled
        or old_web["port"] != web_port
        or old_web["allow_lan"] != web_lan
    ):
        restart_web_panel(_controller)

    _controller.notify_settings_saved()
    logger.info("⚙️ 系统设置已保存。")
    snap = _controller.get_status_snapshot()
    return jsonify({"ok": True, "msg": "设置已保存", "web_info": snap["web_info"]})


@app.route("/api/theme", methods=["POST"])
@_require_auth
def api_theme_save():
    data = request.get_json(silent=True) or {}
    mode = str(data.get("mode", "System"))
    if mode not in ("System", "Dark", "Light"):
        return jsonify({"ok": False, "msg": "无效的主题模式"}), 400
    config_manager.save_appearance_mode(mode)
    if _controller:
        _controller.notify_settings_saved()
    return jsonify({"ok": True, "appearance_mode": mode})


@app.route("/api/report/download")
@_require_auth
def api_report_download():
    if not _controller or not _controller.last_export_path:
        return jsonify({"ok": False, "msg": "暂无可下载的月报，请先导出月报"}), 404

    fp = os.path.abspath(_controller.last_export_path)
    fname = os.path.basename(fp)
    parent = os.path.dirname(fp)
    allowed_dirs = {
        os.path.abspath(os.path.expanduser("~/Desktop")),
        os.path.abspath(app_context.BASE_PATH),
    }

    if (
        not os.path.isfile(fp)
        or not fname.endswith(".csv")
        or not fname.startswith("TBE+月度点赞报告_")
        or parent not in allowed_dirs
    ):
        return jsonify({"ok": False, "msg": "报告文件不存在或路径受限"}), 404

    return send_file(
        fp,
        as_attachment=True,
        download_name=fname,
        mimetype="text/csv; charset=utf-8",
    )


# ============================
# 桌面专用回环服务 & 局域网服务生命周期
# ============================

def start_desktop_server(controller=None) -> Tuple[int, str]:
    """启动桌面原生 WebView 专用回环服务（127.0.0.1 随机空闲端口）。"""
    global _desktop_server, _desktop_thread
    logging.getLogger("werkzeug").setLevel(logging.ERROR)

    if controller is not None:
        set_controller(controller)

    with _desktop_lock:
        if _desktop_server is not None:
            return _desktop_server.server_port, _DESKTOP_BOOT_TOKEN

        srv = make_server("127.0.0.1", 0, app, threaded=True)
        _desktop_server = srv
        _desktop_thread = threading.Thread(target=srv.serve_forever, daemon=True)
        _desktop_thread.start()
        return srv.server_port, _DESKTOP_BOOT_TOKEN


def stop_desktop_server():
    """停止桌面回环服务。"""
    global _desktop_server, _desktop_thread
    with _desktop_lock:
        srv = _desktop_server
        _desktop_server = None
        _desktop_thread = None
    if srv is not None:
        try:
            srv.shutdown()
            srv.server_close()
        except Exception:
            pass


def stop_web_panel():
    """停止局域网 Web 远程服务（不影响桌面窗口回环连接）。"""
    global _server, _server_thread
    with _server_lock:
        srv = _server
        _server = None
        _server_thread = None
    if srv is not None:
        try:
            srv.shutdown()
            srv.server_close()
        except Exception:
            pass
    if _controller:
        web_cfg = config_manager.get_web_panel_config()
        _controller.update_web_info({
            "enabled": web_cfg["enabled"],
            "port": web_cfg["port"],
            "allow_lan": web_cfg["allow_lan"],
            "running": False,
            "error": "",
        })


def start_web_panel(port: Optional[int] = None, allow_lan: Optional[bool] = None):
    """在后台守护线程中启动局域网 Web 远程面板，并捕获端口冲突。"""
    global _server, _server_thread
    logging.getLogger("werkzeug").setLevel(logging.ERROR)

    web_cfg = config_manager.get_web_panel_config()
    enabled = web_cfg["enabled"]
    use_port = int(port if port is not None else web_cfg["port"])
    use_lan = bool(allow_lan if allow_lan is not None else web_cfg["allow_lan"])

    if not enabled:
        logger.info("🌐 [Web面板] 已禁用（可在偏好设置中开启）。")
        if _controller:
            _controller.update_web_info({
                "enabled": False,
                "port": use_port,
                "allow_lan": use_lan,
                "running": False,
                "url": "",
                "lan_url": "",
                "error": "",
            })
        return None

    host = "0.0.0.0" if use_lan else "127.0.0.1"
    with _server_lock:
        if _server is not None:
            return _server_thread

        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind((host, use_port))
            srv = make_server(host, use_port, app, threaded=True)
        except (OSError, SystemExit) as e:
            err_msg = f"端口 {use_port} 被占用"
            logger.warning(f"⚠️ [Web面板] 启动失败 ({err_msg}: {e})，请更换端口后重试。")
            if _controller:
                _controller.update_web_info({
                    "enabled": True,
                    "port": use_port,
                    "allow_lan": use_lan,
                    "running": False,
                    "error": err_msg,
                })
            return None

        _server = srv
        thread = threading.Thread(target=srv.serve_forever, daemon=True)
        _server_thread = thread
        thread.start()

    local_url = f"http://localhost:{use_port}"
    lan_ip = http_utils.get_lan_ip() if use_lan else "127.0.0.1"
    lan_url = f"http://{lan_ip}:{use_port}"

    if _controller:
        _controller.update_web_info({
            "enabled": True,
            "port": use_port,
            "allow_lan": use_lan,
            "running": True,
            "url": local_url,
            "lan_url": lan_url,
            "error": "",
        })

    if use_lan:
        logger.info(f"🌐 [Web面板] 本机访问: {local_url} ｜ 局域网手机控制: {lan_url}")
    else:
        logger.info(f"🌐 [Web面板] 仅本机访问模式已启动: {local_url}")
    return thread


def restart_web_panel(controller=None):
    """应用新的 Web 面板配置后热重启服务。"""
    if controller is not None:
        set_controller(controller)
    stop_web_panel()
    return start_web_panel()
