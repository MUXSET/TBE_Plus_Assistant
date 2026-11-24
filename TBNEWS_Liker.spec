# -*- mode: python ; coding: utf-8 -*-
# =================================================================
#  TBNEWS_Liker.spec
#  PyInstaller 打包配置文件
#  用法: pyinstaller TBNEWS_Liker.spec
# =================================================================

import sys

block_cipher = None

# WebView 原生后端隐藏导入与前端静态资源
if sys.platform == 'darwin':
    platform_hiddenimports = ['webview.platforms.cocoa']
elif sys.platform == 'win32':
    platform_hiddenimports = ['webview.platforms.edgechromium', 'webview.platforms.winforms']
else:
    platform_hiddenimports = []

all_datas = [
    ('templates', 'templates'),
    ('static', 'static'),
]

a = Analysis(
    ['run.py'],
    pathex=[],
    binaries=[],
    datas=all_datas,
    hiddenimports=platform_hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        'matplotlib', 'numpy', 'scipy', 'pandas', 'PIL',
        'tkinter', 'tkinter.test', 'unittest', 'xmlrpc', 'pydoc',
    ],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

# ---- 平台特定配置 ----
APP_NAME = 'TBE+阅赞助手'

if sys.platform == 'darwin':
    # macOS: 生成 .app bundle
    exe = EXE(
        pyz,
        a.scripts,
        [],
        exclude_binaries=True,
        name=APP_NAME,
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=True,
        console=False,
        target_arch=None,
    )
    coll = COLLECT(
        exe,
        a.binaries,
        a.datas,
        strip=False,
        upx=True,
        upx_exclude=[],
        name=APP_NAME,
    )
    app = BUNDLE(
        coll,
        name=f'{APP_NAME}.app',
        bundle_identifier='com.muxset.tbnews-liker',
    )
else:
    # Windows / Linux: 生成目录模式可执行文件
    exe = EXE(
        pyz,
        a.scripts,
        [],
        exclude_binaries=True,
        name=APP_NAME,
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=True,
        console=False,
    )
    coll = COLLECT(
        exe,
        a.binaries,
        a.datas,
        strip=False,
        upx=True,
        upx_exclude=[],
        name=APP_NAME,
    )
