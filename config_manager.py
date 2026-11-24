# =================================================================
#  config_manager.py
#  Version: 2.3.0
#  Author: MUXSET
#  Description: 线程安全的配置管理中心 (多账号版)。
#               支持显式账号隔离读写、内存缓存、原子写入(0600权限)
#               及全局配置（频率、频道、界面主题、Web面板、多账号轮询）。
# =================================================================

import copy
import json
import os
import threading
import time
from typing import Any, Dict, List, Optional, Tuple, Union

from app_context import CONFIG_FILE_PATH
from logger import logger

DEFAULT_CHANNELS = [
    {"name": "特变电工股份有限公司", "id": "XT-2bb8a866-d2a3-47da-bbad-8c63db21e9b6"},
    {"name": "新变厂新闻资讯", "id": "XT-0ba2025b-e2cb-498b-99b1-cdc741a21f75"},
    {"name": "开讲了", "id": "XT-1b1e03a9-8222-4b7f-a7eb-a5d91e2d012d"},
]

DEFAULT_WEB_PANEL_CONFIG = {
    "enabled": True,
    "port": 5050,
    "allow_lan": True,
    "access_pin": "",
}

_lock = threading.RLock()
_config_cache: Optional[Dict[str, Any]] = None
_config_mtime: float = -1.0
_config_sig: Optional[Tuple[str, int, int]] = None


def _default_config() -> Dict[str, Any]:
    return {
        "accounts": [],
        "active_account": -1,
        "scan_interval_hours": 1.0,
        "token_refresh_interval_hours": 6.0,
        "auto_check_monthly_total": True,
        "disable_system_proxy": False,
        "appearance_mode": "System",
        "multi_account_auto": False,
        "web_panel": copy.deepcopy(DEFAULT_WEB_PANEL_CONFIG),
        "channels": copy.deepcopy(DEFAULT_CHANNELS),
    }


def _ensure_global_defaults(config: Dict[str, Any]) -> Dict[str, Any]:
    config.setdefault("accounts", [])
    config.setdefault("active_account", 0 if config["accounts"] else -1)
    config.setdefault("scan_interval_hours", 1.0)
    config.setdefault("token_refresh_interval_hours", 6.0)
    config.setdefault("auto_check_monthly_total", True)
    config.setdefault("disable_system_proxy", False)
    config.setdefault("appearance_mode", "System")
    config.setdefault("multi_account_auto", False)
    config.setdefault("channels", copy.deepcopy(DEFAULT_CHANNELS))

    web_cfg = config.get("web_panel")
    if not isinstance(web_cfg, dict):
        config["web_panel"] = copy.deepcopy(DEFAULT_WEB_PANEL_CONFIG)
    else:
        for k, v in DEFAULT_WEB_PANEL_CONFIG.items():
            web_cfg.setdefault(k, v)
    return config


def _migrate_config(config: Dict[str, Any]) -> Dict[str, Any]:
    """将旧的单账号 config 迁移为多账号格式并补齐全局默认键。"""
    if "accounts" in config:
        return _ensure_global_defaults(config)

    logger.info("🔄 [配置] 检测到旧版配置，正在迁移到多账号格式...")
    account = {
        "username": config.pop("username", ""),
        "password": config.pop("password", ""),
        "tbea_art_token": config.pop("tbea_art_token", ""),
        "ejia_cookies": config.pop("ejia_cookies", {}),
        "ejia_user_id": config.pop("ejia_user_id", ""),
        "token_refresh_time": config.pop("token_refresh_time", 0),
        "sweep_stats": config.pop("sweep_stats", {}),
        "monthly_stats": config.pop("monthly_stats", {}),
    }

    config["accounts"] = [account] if account["username"] else []
    config["active_account"] = 0 if config["accounts"] else -1
    _ensure_global_defaults(config)
    _save_config(config)
    logger.info("✅ [配置] 迁移完成！")
    return config


def _read_file_sig(path: str) -> Optional[Tuple[str, int, int]]:
    try:
        st = os.stat(path)
        return (path, st.st_mtime_ns, st.st_size)
    except OSError:
        return None


def _load_config() -> Dict[str, Any]:
    global _config_cache, _config_mtime, _config_sig
    with _lock:
        if not os.path.exists(CONFIG_FILE_PATH):
            _config_cache = None
            _config_mtime = -1.0
            _config_sig = None
            return _default_config()

        sig = _read_file_sig(CONFIG_FILE_PATH)
        if _config_cache is not None and sig is not None and sig == _config_sig:
            return copy.deepcopy(_config_cache)

        try:
            with open(CONFIG_FILE_PATH, "r", encoding="utf-8") as f:
                raw = json.load(f)
            if not isinstance(raw, dict):
                raw = {}
        except (json.JSONDecodeError, OSError):
            return _default_config()

        if "accounts" not in raw:
            config = _migrate_config(raw)
        else:
            config = _ensure_global_defaults(raw)

        _config_cache = copy.deepcopy(config)
        _config_sig = _read_file_sig(CONFIG_FILE_PATH)
        _config_mtime = os.path.getmtime(CONFIG_FILE_PATH) if os.path.exists(CONFIG_FILE_PATH) else -1.0
        return copy.deepcopy(config)


def _save_config(config_data: Dict[str, Any]):
    global _config_cache, _config_mtime, _config_sig
    with _lock:
        parent_dir = os.path.dirname(os.path.abspath(CONFIG_FILE_PATH))
        if parent_dir:
            os.makedirs(parent_dir, exist_ok=True)
        temp_file = f"{CONFIG_FILE_PATH}.{threading.get_ident()}.tmp"
        try:
            with open(temp_file, "w", encoding="utf-8") as f:
                json.dump(config_data, f, indent=4, ensure_ascii=False)
            if os.name == "posix":
                os.chmod(temp_file, 0o600)
            os.replace(temp_file, CONFIG_FILE_PATH)
        finally:
            if os.path.exists(temp_file):
                try:
                    os.remove(temp_file)
                except OSError:
                    pass

        _config_cache = copy.deepcopy(config_data)
        _config_sig = _read_file_sig(CONFIG_FILE_PATH)
        _config_mtime = os.path.getmtime(CONFIG_FILE_PATH) if os.path.exists(CONFIG_FILE_PATH) else -1.0


def ensure_config_exists() -> bool:
    with _lock:
        if not os.path.exists(CONFIG_FILE_PATH):
            logger.info("🔧 [配置] 首次运行，正在创建新的配置文件...")
            _save_config(_default_config())
            logger.info(f"✅ [配置] '{os.path.basename(CONFIG_FILE_PATH)}' 创建成功。")
            return True

        _load_config()
        return False


# ============================
# 账号管理与显式隔离解析
# ============================

def _resolve_account_index(config: Dict[str, Any], username: Optional[str] = None) -> int:
    accounts = config.get("accounts", [])
    if username is not None:
        for i, acc in enumerate(accounts):
            if acc.get("username") == username:
                return i
        return -1
    idx = config.get("active_account", -1)
    return idx if 0 <= idx < len(accounts) else -1


def _get_active_account(username: Optional[str] = None) -> Dict[str, Any]:
    """获取指定或当前激活账号的配置副本。"""
    with _lock:
        config = _load_config()
        idx = _resolve_account_index(config, username=username)
        if idx >= 0:
            return config["accounts"][idx]
        return {}


def _update_active_account(updates: Dict[str, Any], username: Optional[str] = None) -> bool:
    """原子更新指定或当前激活账号的字段。"""
    with _lock:
        config = _load_config()
        idx = _resolve_account_index(config, username=username)
        if idx >= 0:
            config["accounts"][idx].update(updates)
            _save_config(config)
            return True
        return False


def get_all_accounts() -> List[Dict[str, Any]]:
    """返回所有账号列表 (仅含索引和用户名，不泄露密码)。"""
    with _lock:
        config = _load_config()
        return [
            {"index": i, "username": a.get("username", "")}
            for i, a in enumerate(config.get("accounts", []))
        ]


def get_active_account_index() -> int:
    with _lock:
        config = _load_config()
        return config.get("active_account", -1)


def switch_account(index: int) -> bool:
    """切换到指定索引的账号。"""
    with _lock:
        config = _load_config()
        accounts = config.get("accounts", [])
        if 0 <= index < len(accounts):
            config["active_account"] = index
            _save_config(config)
            logger.info(f"🔀 [配置] 已切换到账号: {accounts[index].get('username', '?')}")
            return True
        return False


def add_account(username: str, password: str) -> int:
    """添加新账号，返回其索引；若已存在则返回现有索引。"""
    clean_user = username.strip()
    clean_pass = password.strip()
    with _lock:
        config = _load_config()
        accounts = config.get("accounts", [])

        for i, a in enumerate(accounts):
            if a.get("username") == clean_user:
                logger.warning(f"⚠️ [配置] 账号 {clean_user} 已存在。")
                return i

        new_account = {
            "username": clean_user,
            "password": clean_pass,
            "tbea_art_token": "",
            "ejia_cookies": {},
            "ejia_user_id": "",
            "token_refresh_time": 0,
            "sweep_stats": {},
            "monthly_stats": {},
        }
        accounts.append(new_account)
        config["accounts"] = accounts

        if len(accounts) == 1 or config.get("active_account", -1) < 0:
            config["active_account"] = 0

        _save_config(config)
        logger.info(f"➕ [配置] 已添加账号: {clean_user}")
        return len(accounts) - 1


def update_account_password(username: str, new_password: str) -> bool:
    """更新指定账号密码。"""
    clean_user = username.strip()
    clean_pass = new_password.strip()
    with _lock:
        config = _load_config()
        idx = _resolve_account_index(config, username=clean_user)
        if idx < 0:
            return False
        config["accounts"][idx]["password"] = clean_pass
        _save_config(config)
        logger.info(f"🔐 [配置] 已更新账号 {clean_user} 的密码。")
        return True


def remove_account(index: int) -> Optional[str]:
    """删除指定索引的账号（允许删除至 0 个账号），返回被删用户名。"""
    with _lock:
        config = _load_config()
        accounts = config.get("accounts", [])
        if not (0 <= index < len(accounts)):
            return None

        removed = accounts.pop(index)
        removed_name = removed.get("username", "")
        config["accounts"] = accounts

        active = config.get("active_account", -1)
        if not accounts:
            config["active_account"] = -1
        elif active == index:
            config["active_account"] = min(index, len(accounts) - 1)
        elif active > index:
            config["active_account"] = active - 1

        _save_config(config)
        logger.info(f"🗑️ [配置] 已删除账号: {removed_name or '?'}")
        return removed_name


# ============================
# 账号级凭据与统计 API（支持显式 username 参数）
# ============================

def get_credentials(username: Optional[str] = None) -> Tuple[Optional[str], Optional[str]]:
    acc = _get_active_account(username=username)
    return acc.get("username"), acc.get("password")


def get_token(username: Optional[str] = None) -> Optional[str]:
    return _get_active_account(username=username).get("tbea_art_token")


def update_credentials(username: str, password: str, target_username: Optional[str] = None):
    _update_active_account(
        {"username": username.strip(), "password": password.strip()},
        username=target_username,
    )


def save_token(token_data: Union[dict, str], username: Optional[str] = None):
    if isinstance(token_data, dict):
        updates: Dict[str, Any] = {
            "tbea_art_token": token_data.get("token", ""),
            "ejia_cookies": token_data.get("ejia_cookies", {}),
            "token_refresh_time": time.time(),
        }
        new_uid = token_data.get("ejia_user_id", "")
        if new_uid:
            updates["ejia_user_id"] = new_uid
        _update_active_account(updates, username=username)
    else:
        _update_active_account(
            {"tbea_art_token": token_data, "token_refresh_time": time.time()},
            username=username,
        )


def get_ejia_cookies(username: Optional[str] = None) -> dict:
    return _get_active_account(username=username).get("ejia_cookies", {})


def get_ejia_user_id(username: Optional[str] = None) -> str:
    return _get_active_account(username=username).get("ejia_user_id", "")


def get_token_refresh_time(username: Optional[str] = None) -> float:
    return float(_get_active_account(username=username).get("token_refresh_time", 0))


def save_sweep_stats(total: int, liked: int, skipped: int, username: Optional[str] = None):
    _update_active_account(
        {
            "sweep_stats": {
                "total": total,
                "liked": liked,
                "skipped": skipped,
                "last_sweep_time": time.strftime("%Y-%m-%d %H:%M:%S"),
            }
        },
        username=username,
    )


def get_sweep_stats(username: Optional[str] = None) -> dict:
    return _get_active_account(username=username).get("sweep_stats", {})


def save_monthly_stats(total: int, liked: int, username: Optional[str] = None):
    _update_active_account(
        {
            "monthly_stats": {
                "monthly_total": total,
                "monthly_liked": liked,
                "month": time.strftime("%Y-%m"),
            }
        },
        username=username,
    )


def update_monthly_total_only(total: int, min_liked: int = 0, username: Optional[str] = None):
    """原子更新本月总数，保留点赞数缓存并与本地已知点赞数对齐。"""
    with _lock:
        stats = get_monthly_stats(username=username)
        liked = max(stats.get("monthly_liked", 0), min_liked)
        if total >= 0:
            liked = min(total, liked)
        save_monthly_stats(total, liked, username=username)


def get_monthly_stats(username: Optional[str] = None) -> dict:
    stats = _get_active_account(username=username).get("monthly_stats", {})
    if stats.get("month") == time.strftime("%Y-%m"):
        return stats
    return {}


# ============================
# 全局设置 (所有账号共享)
# ============================

def get_intervals() -> Tuple[float, float]:
    config = _load_config()
    return (
        float(config.get("scan_interval_hours", 1.0)),
        float(config.get("token_refresh_interval_hours", 6.0)),
    )


def save_intervals(scan_interval: float, token_interval: float):
    with _lock:
        config = _load_config()
        config["scan_interval_hours"] = float(scan_interval)
        config["token_refresh_interval_hours"] = float(token_interval)
        _save_config(config)


def get_default_channels() -> List[Dict[str, str]]:
    return copy.deepcopy(DEFAULT_CHANNELS)


def get_channels() -> List[Dict[str, str]]:
    config = _load_config()
    return config.get("channels", copy.deepcopy(DEFAULT_CHANNELS))


def save_channels(channels: List[Dict[str, str]]):
    with _lock:
        config = _load_config()
        config["channels"] = copy.deepcopy(channels)
        _save_config(config)


def get_auto_check_monthly_total() -> bool:
    return bool(_load_config().get("auto_check_monthly_total", True))


def save_auto_check_monthly_total(enabled: bool):
    with _lock:
        config = _load_config()
        config["auto_check_monthly_total"] = bool(enabled)
        _save_config(config)


def get_disable_system_proxy() -> bool:
    return bool(_load_config().get("disable_system_proxy", False))


def save_disable_system_proxy(enabled: bool):
    with _lock:
        config = _load_config()
        config["disable_system_proxy"] = bool(enabled)
        _save_config(config)


def get_appearance_mode() -> str:
    mode = _load_config().get("appearance_mode", "System")
    return mode if mode in ("System", "Dark", "Light") else "System"


def save_appearance_mode(mode: str):
    normalized = mode if mode in ("System", "Dark", "Light") else "System"
    with _lock:
        config = _load_config()
        config["appearance_mode"] = normalized
        _save_config(config)


def get_multi_account_auto() -> bool:
    return bool(_load_config().get("multi_account_auto", False))


def save_multi_account_auto(enabled: bool):
    with _lock:
        config = _load_config()
        config["multi_account_auto"] = bool(enabled)
        _save_config(config)


def get_web_panel_config() -> Dict[str, Any]:
    web_cfg = _load_config().get("web_panel", {})
    port = int(web_cfg.get("port", 5050))
    if not (1 <= port <= 65535):
        port = 5050
    return {
        "enabled": bool(web_cfg.get("enabled", True)),
        "port": port,
        "allow_lan": bool(web_cfg.get("allow_lan", True)),
        "access_pin": str(web_cfg.get("access_pin", "")).strip(),
    }


def save_web_panel_config(enabled: bool, port: int, allow_lan: bool, access_pin: str):
    valid_port = int(port)
    if not (1 <= valid_port <= 65535):
        raise ValueError("端口号必须在 1 ~ 65535 之间")
    with _lock:
        config = _load_config()
        config["web_panel"] = {
            "enabled": bool(enabled),
            "port": valid_port,
            "allow_lan": bool(allow_lan),
            "access_pin": str(access_pin or "").strip(),
        }
        _save_config(config)
