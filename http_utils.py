# =================================================================
#  http_utils.py
#  Version: 2.3.0
#  Description: 统一网络会话与健康探测工具。
#               集中管理重试策略、强制直连(代理绕过)、Token/Session 探活
#               及局域网地址探测，确保全链路网络行为一致。
# =================================================================

import socket
from datetime import datetime
from typing import Dict, Optional
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import config_manager

CHECK_LOGIN_URL = "https://ejia.tbea.com/space/c/rest/user/checkLogin"
ARTICLE_DETAIL_URL = "https://tbeanews.tbea.com/api/article/detail"
PROBE_ARTICLE_ID = 8141


def create_http_session(
    headers: Optional[Dict[str, str]] = None,
    cookies: Optional[Dict[str, str]] = None,
    retries: int = 3,
    backoff_factor: float = 0.5,
) -> requests.Session:
    """
    创建统一配置的 requests.Session：
    1. 内置 5xx 与连接级自动重试
    2. 自动遵循『强制直连 (disable_system_proxy)』，彻底屏蔽环境变量与系统级代理
    """
    session = requests.Session()
    retry = Retry(
        connect=retries,
        read=2,
        backoff_factor=backoff_factor,
        status_forcelist=[500, 502, 503, 504],
        allowed_methods=["HEAD", "GET", "POST", "OPTIONS"],
    )
    adapter = HTTPAdapter(max_retries=retry)
    session.mount("https://", adapter)
    session.mount("http://", adapter)

    session.headers.update({"User-Agent": "Mozilla/5.0"})
    if headers:
        session.headers.update(headers)
    if cookies:
        session.cookies.update(cookies)

    if config_manager.get_disable_system_proxy():
        session.trust_env = False
        session.proxies.update({"http": "", "https": "", "all": ""})

    return session


def is_token_expired_payload(payload: dict) -> bool:
    """判断 tbeanews 接口返回的 JSON 是否代表 Token 登录态失效。"""
    if not isinstance(payload, dict):
        return False
    code = payload.get("code")
    msg = str(payload.get("msg", ""))
    if code in (-1, 401):
        return True
    if any(kw in msg for kw in ("登录超时", "重新登录", "未登录", "token", "Token")):
        return True
    return False


def check_token_valid(token: Optional[str], timeout: int = 10) -> Optional[bool]:
    """
    真实请求接口校验 tbea_art_token 有效性：
    - True:  有效
    - False: 已过期 / 未设置
    - None:  网络异常，暂时无法判定
    """
    if not token:
        return False
    try:
        with create_http_session(headers={"token": token}, retries=1) as s:
            r = s.get(ARTICLE_DETAIL_URL, params={"id": PROBE_ARTICLE_ID}, timeout=timeout)
            if r.status_code in (401, 403):
                return False
            data = r.json()
            if data.get("code") == 1:
                return True
            if is_token_expired_payload(data):
                return False
            # 若探针文章本身被删但未报登录超时，视为 Token 仍有效
            return True
    except ValueError:
        return False
    except requests.RequestException:
        return None


def check_ejia_session_valid(cookies: Optional[Dict[str, str]], timeout: int = 10) -> Optional[bool]:
    """
    真实校验 ejia 会话 Cookies 有效性（同时充当心跳保活请求）：
    注意：ejia 过期时可能返回 HTTP 200 的登录页 HTML，不能仅凭 status_code==200 判断！
    - True:  有效
    - False: 已过期 / 未设置
    - None:  网络异常，暂时无法判定
    """
    if not cookies:
        return False
    try:
        with create_http_session(cookies=cookies, headers={"Content-Length": "0"}, retries=1) as s:
            r = s.post(CHECK_LOGIN_URL, timeout=timeout)
            if r.status_code in (401, 403):
                return False
            if r.status_code != 200:
                return None
            ctype = r.headers.get("Content-Type", "").lower()
            if "html" in ctype or r.text.lstrip().startswith("<!"):
                return False
            data = r.json()
            return bool(data.get("success", False))
    except ValueError:
        # 非 JSON 响应（例如被重定向到 HTML 登录页）说明会话已失效
        return False
    except requests.RequestException:
        return None


def has_system_chromium_browser() -> bool:
    """检测本机是否安装了可直接用于无头兜底的 Chrome 或 Edge 浏览器。"""
    import os
    import sys

    if sys.platform == "darwin":
        candidates = [
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            os.path.expanduser("~/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
        ]
    elif sys.platform == "win32":
        prog_files = [
            os.environ.get("PROGRAMFILES", r"C:\Program Files"),
            os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)"),
            os.environ.get("LOCALAPPDATA", ""),
        ]
        candidates = []
        for base in prog_files:
            if base:
                candidates.append(os.path.join(base, "Google", "Chrome", "Application", "chrome.exe"))
                candidates.append(os.path.join(base, "Microsoft", "Edge", "Application", "msedge.exe"))
    else:
        import shutil
        return bool(shutil.which("google-chrome") or shutil.which("microsoft-edge") or shutil.which("chromium"))

    return any(os.path.isfile(p) for p in candidates)


def get_lan_ip() -> str:
    """获取本机局域网 IPv4 地址（零流量探测），失败时回退 localhost。"""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.settimeout(0.5)
            s.connect(("10.255.255.255", 1))
            ip = s.getsockname()[0]
            if ip and not ip.startswith("127."):
                return ip
    except Exception:
        pass
    return "localhost"


def format_relative_time(time_str: Optional[str]) -> str:
    """将 'YYYY-MM-DD HH:MM:SS' 格式化为相对时间描述。"""
    if not time_str:
        return "从未"
    try:
        dt = datetime.strptime(time_str, "%Y-%m-%d %H:%M:%S")
        mins = max(0, int((datetime.now() - dt).total_seconds() / 60))
        if mins < 1:
            return "刚刚"
        if mins < 60:
            return f"{mins}分钟前"
        if mins < 1440:
            return f"{mins // 60}小时前"
        return f"{mins // 1440}天前"
    except Exception:
        return str(time_str)[:10]
