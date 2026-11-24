#!/bin/bash
# =================================================================
#  build.sh — macOS 本地一键打包脚本
#  用法: ./build.sh
#  产物: TBNEWS_Liker-v{VERSION}-macos/ 及对应 zip 压缩包
# =================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# 读取版本号
VERSION=$(venv/bin/python -c "from version import __version__; print(__version__)")
RELEASE_DIR="TBNEWS_Liker-v${VERSION}-macos"

echo "🚀 开始打包 TBE+ 阅赞助手 v${VERSION} ..."

# 1) 确保 venv/bin/pyinstaller 已安装
if [ ! -x "venv/bin/pyinstaller" ]; then
    echo "📦 安装 PyInstaller..."
    venv/bin/pip install pyinstaller
fi

# 2) 打包
echo "🔨 PyInstaller 打包中..."
venv/bin/pyinstaller TBNEWS_Liker.spec --noconfirm

# 3) 组装发布目录
echo "📁 组装发布包..."
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

# 复制 .app（与 TBNEWS_Liker.spec 中 APP_NAME 保持一致）
cp -R "dist/TBE+阅赞助手.app" "$RELEASE_DIR/"

# 复制 Playwright 浏览器
BROWSER_SRC="$HOME/Library/Caches/ms-playwright"
if [ -d "$BROWSER_SRC" ]; then
    echo "🌐 复制 Playwright Chromium 浏览器..."
    mkdir -p "$RELEASE_DIR/ms-playwright"
    cp -R "$BROWSER_SRC"/chromium-* "$RELEASE_DIR/ms-playwright/" 2>/dev/null || true
    cp -R "$BROWSER_SRC"/chromium_headless_shell-* "$RELEASE_DIR/ms-playwright/" 2>/dev/null || true
    cp -R "$BROWSER_SRC"/ffmpeg-* "$RELEASE_DIR/ms-playwright/" 2>/dev/null || true
else
    echo "⚠️  未找到 Playwright 浏览器目录: $BROWSER_SRC"
    echo "   建议先运行: venv/bin/playwright install chromium"
fi

# 4) 打 zip
echo "📦 压缩为 zip..."
rm -f "$RELEASE_DIR.zip"
zip -r -y "$RELEASE_DIR.zip" "$RELEASE_DIR"

echo ""
echo "✅ 打包完成！"
echo "   📁 目录: $RELEASE_DIR/"
echo "   📦 压缩: $RELEASE_DIR.zip"
echo "   📊 大小: $(du -sh "$RELEASE_DIR.zip" | cut -f1)"
