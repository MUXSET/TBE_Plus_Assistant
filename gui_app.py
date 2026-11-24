# =================================================================
#  gui_app.py
#  Version: 2.3.0
#  Description: GUI 兼容入口（已全面升级至 pywebview 原生桌面窗口 desktop_app.py）。
# =================================================================

from desktop_app import DesktopBridge, launch_app


class App:
    """兼容旧版 App().mainloop() 调用方式的轻量包装器。"""

    def mainloop(self):
        launch_app()


__all__ = ["App", "DesktopBridge", "launch_app"]

if __name__ == "__main__":
    launch_app()
