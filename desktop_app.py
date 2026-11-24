# =================================================================
#  desktop_app.py
#  Version: 2.3.0
#  Description: 原生 WebView 桌面宿主 (pywebview)。
#               通过 127.0.0.1 回环服务与 DesktopBridge JS API 桥接，
#               实现高颜值现代化桌面窗口与系统原生集成。
# =================================================================

import os
import subprocess
import sys
import threading
import webbrowser

import webview

import config_manager
import logger as logger_mod
import web_panel
from app_controller import AppController
from logger import logger
from version import __app_name__, __version__


class DesktopBridge:
    """暴露给 pywebview 前端页面的系统级原生桥接 API。"""

    def __init__(self, controller: AppController):
        # 使用下划线前缀避免 pywebview 递归反射整个控制器对象
        self._controller = controller

    def open_external_url(self, url: str) -> dict:
        target = str(url or "").strip()
        if not (target.startswith("http://") or target.startswith("https://")):
            return {"ok": False, "msg": "仅支持打开 http/https 地址"}
        try:
            webbrowser.open(target)
            return {"ok": True}
        except Exception as e:
            return {"ok": False, "msg": str(e)}

    def open_log_file(self) -> dict:
        log_path = logger_mod.LOG_FILE
        if not os.path.exists(log_path):
            return {"ok": False, "msg": "日志文件尚未生成"}
        try:
            if sys.platform == "darwin":
                subprocess.Popen(["open", log_path])
            elif sys.platform == "win32":
                os.startfile(log_path)
            else:
                subprocess.Popen(["xdg-open", log_path])
            return {"ok": True}
        except Exception as e:
            return {"ok": False, "msg": str(e)}

    def reveal_report(self) -> dict:
        fp = self._controller.last_export_path
        if not fp or not os.path.isfile(fp):
            return {"ok": False, "msg": "暂无可查看的月报文件"}
        try:
            if sys.platform == "darwin":
                subprocess.Popen(["open", "-R", fp])
            elif sys.platform == "win32":
                subprocess.Popen(["explorer", "/select,", os.path.normpath(fp)])
            else:
                subprocess.Popen(["xdg-open", os.path.dirname(fp)])
            return {"ok": True}
        except Exception as e:
            return {"ok": False, "msg": str(e)}

    def copy_text(self, text: str) -> dict:
        content = str(text or "")
        try:
            if sys.platform == "darwin":
                subprocess.run(["pbcopy"], input=content.encode("utf-8"), check=True)
                return {"ok": True}
            if sys.platform == "win32":
                subprocess.run(["clip"], input=content, text=True, check=True)
                return {"ok": True}
        except Exception as e:
            return {"ok": False, "msg": str(e)}
        return {"ok": False, "msg": "请使用浏览器剪贴板"}


def _detect_initial_bg_color() -> str:
    mode = config_manager.get_appearance_mode()
    if mode == "Dark":
        return "#0C0E12"
    if mode == "Light":
        return "#F5F7FA"
    try:
        if sys.platform == "darwin":
            res = subprocess.run(
                ["defaults", "read", "-g", "AppleInterfaceStyle"],
                capture_output=True,
                text=True,
                timeout=0.5,
            )
            return "#0C0E12" if "Dark" in (res.stdout or "") else "#F5F7FA"
    except Exception:
        pass
    return "#0C0E12"


def launch_app():
    """在主线程启动原生 WebView 桌面应用。"""
    controller = AppController()
    web_panel.set_controller(controller)

    local_port, boot_token = web_panel.start_desktop_server(controller)
    web_panel.start_web_panel()

    logger.info("=========================================")
    logger.info(f"  ⚡ {__app_name__} v{__version__} 已启动")
    logger.info("=========================================")

    controller.start_background_services()
    if config_manager.get_all_accounts():
        controller.startup_check_async()

    bg_color = _detect_initial_bg_color()
    desktop_url = f"http://127.0.0.1:{local_port}/?dt={boot_token}"

    window = webview.create_window(
        title=f"{__app_name__} v{__version__}",
        url=desktop_url,
        js_api=DesktopBridge(controller),
        width=1140,
        height=740,
        min_size=(900, 600),
        background_color=bg_color,
        text_select=True,
    )

    def _on_closing():
        def _async_cleanup():
            try:
                controller.shutdown()
            except Exception:
                pass
            try:
                web_panel.stop_web_panel()
                web_panel.stop_desktop_server()
            except Exception:
                pass

        threading.Thread(target=_async_cleanup, daemon=True).start()

    if window is not None:
        window.events.closing += _on_closing

    try:
        webview.start(debug=False)
    finally:
        controller.shutdown()
        web_panel.stop_web_panel()
        web_panel.stop_desktop_server()
