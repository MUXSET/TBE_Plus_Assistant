#!/bin/bash
# =================================================================
#  TBE+ 阅赞助手 - macOS 一键启动脚本
#  双击此文件即可自动激活虚拟环境并启动 GUI 程序。
# =================================================================

export LANG="zh_CN.UTF-8"
export LC_ALL="zh_CN.UTF-8"

# 切换到脚本所在目录（防止从其它位置双击时路径出错）
cd "$(dirname "$0")" || exit 1

# 检查虚拟环境是否存在
if [ ! -d "venv" ]; then
    echo "⚙️  首次启动或未找到 venv 虚拟环境，正在自动初始化环境..."
    PYTHON_BIN=$(command -v python3.12 || command -v python3)
    if [ -z "$PYTHON_BIN" ]; then
        echo "❌ 错误：未检测到 Python 3，请先安装 Python 3.12+。"
        read -r -p "按回车键退出..."
        exit 1
    fi
    "$PYTHON_BIN" -m venv venv && source venv/bin/activate && pip install -r requirements.txt && playwright install chromium
    if [ $? -ne 0 ]; then
        echo "❌ 环境初始化失败，请检查网络连接后重试。"
        read -r -p "按回车键退出..."
        exit 1
    fi
fi

# 激活虚拟环境并启动主程序
source venv/bin/activate
python run.py
