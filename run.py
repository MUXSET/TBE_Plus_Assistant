# =================================================================
#  run.py
#  Version: 2.3.0
#  Author: MUXSET
#  Description: 应用程序主入口。
#               统一处理运行环境检查，并启动原生 WebView 桌面界面。
# =================================================================

import ctypes
import os
import subprocess
import sys

import app_context
import http_utils


def _show_native_error(title: str, message: str):
    """无 Tkinter 依赖的原生系统错误弹窗提示。"""
    try:
        if sys.platform == "darwin":
            escaped_msg = message.replace("\\", "\\\\").replace('"', '\\"')
            escaped_title = title.replace("\\", "\\\\").replace('"', '\\"')
            subprocess.run(
                [
                    "osascript",
                    "-e",
                    f'display alert "{escaped_title}" message "{escaped_msg}" as critical',
                ],
                check=False,
            )
            return
        if sys.platform == "win32":
            ctypes.windll.user32.MessageBoxW(0, message, title, 0x10)
            return
    except Exception:
        pass
    print(f"❌ [{title}] {message}")


def initialize_environment():
    """在程序启动时执行一次性的环境检查。"""
    print("🚀 [环境初始化] 正在检查应用环境...")
    if getattr(sys, "frozen", False) and not os.path.exists(app_context.BROWSER_DATA_PATH):
        if http_utils.has_system_chromium_browser():
            print("ℹ️ [环境初始化] 未检测到附带的 ms-playwright 目录，将自动调用本机 Chrome/Edge 无头模式运行。")
        else:
            _show_native_error(
                "环境缺失错误",
                f"浏览器数据目录不存在且未检测到 Chrome/Edge！\n\n"
                f"缺少组件: '{os.path.basename(app_context.BROWSER_DATA_PATH)}'\n\n"
                f"请确保解压了所有文件并将 'ms-playwright' 文件夹与主程序放在同一级目录，或安装 Google Chrome 浏览器。",
            )
            sys.exit(1)
    print("✅ [环境初始化] 环境检查通过。")


if __name__ == "__main__":
    initialize_environment()
    import gui_app

    gui_app.launch_app()
