# =================================================================
#  get_token.py
#  Version: 2.3.0
#  Author: MUXSET
#  Description: Token获取模块 (高可用自愈异步版)。
#               - 三级浏览器容错链：内置/打包 Chromium -> 本机 Chrome/Edge -> 自动重装修复
#               - 多频道轮询保底触发 SSO，防止单频道无文章导致卡死
#               - 自动遵循『强制直连』配置绕过系统代理
# =================================================================

import asyncio
import os
import sys
import glob
import platform
import subprocess
import urllib.parse
from typing import List, Optional
from playwright.async_api import async_playwright, Browser, Playwright, TimeoutError as PlaywrightTimeoutError

from logger import logger
import app_context
import config_manager

LOGIN_URL = "https://ejia.tbea.com/"
DEFAULT_CHANNEL_ID = "XT-2bb8a866-d2a3-47da-bbad-8c63db21e9b6"


def _find_bundled_browser() -> Optional[str]:
    """在打包或本地 ms-playwright/ 目录中查找 Chromium / Headless Shell 可执行文件。"""
    browser_root = app_context.BROWSER_DATA_PATH
    if not os.path.isdir(browser_root):
        return None

    if sys.platform == 'darwin':
        patterns = [
            # Playwright >= 1.49 新版轻量无头内核
            os.path.join(browser_root, 'chromium_headless_shell-*', 'chrome-headless-shell-mac*', 'chrome-headless-shell'),
            # Google Chrome for Testing / Chromium 完整包
            os.path.join(browser_root, 'chromium-*', 'chrome-mac*',
                         'Google Chrome for Testing.app', 'Contents', 'MacOS', 'Google Chrome for Testing'),
            os.path.join(browser_root, 'chromium-*', 'chrome-mac*',
                         'Chromium.app', 'Contents', 'MacOS', 'Chromium'),
        ]
    elif sys.platform == 'win32':
        patterns = [
            os.path.join(browser_root, 'chromium_headless_shell-*', 'chrome-headless-shell-win*', 'chrome-headless-shell.exe'),
            os.path.join(browser_root, 'chromium-*', 'chrome-win*', 'chrome.exe'),
        ]
    else:
        patterns = [
            os.path.join(browser_root, 'chromium_headless_shell-*', 'chrome-headless-shell-linux*', 'chrome-headless-shell'),
            os.path.join(browser_root, 'chromium-*', 'chrome-linux*', 'chrome'),
        ]

    for pattern in patterns:
        matches = sorted(glob.glob(pattern), reverse=True)
        for path in matches:
            if os.path.isfile(path):
                logger.info(f"📦 [Token] 发现本地打包浏览器内核: {os.path.basename(path)}")
                return path

    return None


def _auto_install_playwright_chromium() -> bool:
    """当开发环境无头浏览器缓存被清理且无本机 Chrome/Edge 时，自动修复下载。"""
    if getattr(sys, 'frozen', False):
        return False

    logger.warning("🔧 [Token] 正在后台自动下载修复 Playwright Chromium 组件（首次约需 15~30 秒）...")
    env = os.environ.copy()
    if config_manager.get_disable_system_proxy():
        for k in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
            env.pop(k, None)

    try:
        proc = subprocess.run(
            [sys.executable, "-m", "playwright", "install", "chromium"],
            capture_output=True,
            text=True,
            timeout=180,
            env=env,
        )
        if proc.returncode == 0:
            logger.info("✅ [Token] Playwright Chromium 自动修复安装完成！")
            return True
        logger.error(f"❌ [Token] 自动安装返回非零状态: {proc.stderr[-200:] if proc.stderr else '未知错误'}")
    except Exception as e:
        logger.error(f"❌ [Token] 自动安装浏览器异常: {e}")
    return False


async def _launch_resilient_browser(p: Playwright, launch_args: List[str]) -> Optional[Browser]:
    """
    三级高可用无头浏览器启动器：
    1. 优先使用项目附带 ms-playwright 或默认 Playwright Chromium
    2. 若系统清理工具误删了缓存目录，0 秒无缝回退至本机 Chrome / Edge 无头模式
    3. 若本机未安装 Chrome / Edge，在非打包环境下自动执行 playwright install 自愈
    """
    bundled_exe = _find_bundled_browser()
    if bundled_exe:
        try:
            return await p.chromium.launch(headless=True, executable_path=bundled_exe, args=launch_args)
        except Exception as e:
            logger.warning(f"⚠️ [Token] 启动附带浏览器失败，尝试回退方案: {e}")

    if not getattr(sys, 'frozen', False):
        try:
            return await p.chromium.launch(headless=True, args=launch_args)
        except Exception as first_err:
            err_str = str(first_err)
            if "Executable doesn't exist" in err_str or "not found" in err_str.lower():
                logger.warning("⚠️ [Token] Playwright 缓存浏览器缺失（可能被系统清理软件清除），正在切换本机浏览器兜底...")
            else:
                logger.warning(f"⚠️ [Token] 默认无头浏览器启动受阻，尝试本机浏览器兜底: {err_str.splitlines()[0]}")

    # 第二级：尝试本机安装的 Google Chrome 或 Microsoft Edge（不受缓存清理影响）
    for channel_name, label in (("chrome", "Google Chrome"), ("msedge", "Microsoft Edge")):
        try:
            browser = await p.chromium.launch(headless=True, channel=channel_name, args=launch_args)
            logger.info(f"✅ [Token] 已无缝启用本机 {label} 无头内核。")
            return browser
        except Exception:
            continue

    # 第三级：源码/虚拟环境下自动重装 Playwright Chromium 并重试一次
    if _auto_install_playwright_chromium():
        try:
            return await p.chromium.launch(headless=True, args=launch_args)
        except Exception as retry_err:
            logger.error(f"❌ [Token] 修复后启动浏览器仍失败: {retry_err}")

    logger.error("❌ [Token] 无法启动任何可用的无头浏览器，请安装 Google Chrome 或运行 playwright install chromium。")
    return None


def _get_platform_ua() -> str:
    system_os = platform.system()
    if system_os == "Darwin":
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    if system_os == "Windows":
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


async def get_new_token(username: str, password: str) -> Optional[dict]:
    """
    使用 Playwright 执行完整的无头浏览器 Token 捕获流程 (异步版本)。
    提取 tbea_art_token 以及所有的 ejia session cookies 供公众号与新闻接口使用。
    """
    logger.info("🛠️ [Token] 正在启动无头浏览器获取凭据...")
    if not username or not password:
        logger.error("❌ [Token] 错误: 未提供有效的工号或密码。")
        return None

    launch_args: List[str] = []
    if config_manager.get_disable_system_proxy():
        launch_args.append("--proxy-bypass-list=*.tbea.com,tbea.com,localhost,127.0.0.1")

    try:
        async with async_playwright() as p:
            browser = await _launch_resilient_browser(p, launch_args)
            if not browser:
                return None

            try:
                context = await browser.new_context(
                    user_agent=_get_platform_ua(),
                    ignore_https_errors=True,
                )
                page = await context.new_page()

                logger.info("🌐 [Token] 正在打开门户登录页...")
                await page.goto(LOGIN_URL, timeout=45000)
                await page.locator(".user-name").click()
                await page.locator("#email").fill(username)
                await page.locator("#password").fill(password)
                await page.locator("#log-btn").click()
                logger.info("✅ [Token] 登录信息已提交，等待会话建立...")

                try:
                    await page.wait_for_load_state("load", timeout=25000)
                except PlaywrightTimeoutError:
                    logger.warning("⚠️ [Token] 登录后页面 load 事件超时，继续尝试...")

                # 显式等待后台 SSO / 会话 Cookie 握手完成
                await page.wait_for_timeout(2500)

                # 遍历配置的频道列表构造新闻入口（防止单个频道恰好无历史推送）
                channels = config_manager.get_channels()
                candidate_ids = [ch["id"] for ch in channels if ch.get("id")]
                if DEFAULT_CHANNEL_ID not in candidate_ids:
                    candidate_ids.append(DEFAULT_CHANNEL_ID)

                found_article = False
                for ch_id in candidate_ids:
                    content_src = f'[{{"title":"","ids":["{ch_id}"]}}]'
                    encoded_src = urllib.parse.quote(content_src)
                    list_url = f"https://ejia.tbea.com/pubacc-front/article/list?contentSource={encoded_src}"

                    try:
                        await page.goto(list_url, timeout=25000)
                    except Exception as e:
                        logger.warning(f"⚠️ [Token] 加载频道列表遇到网络波动: {e}")

                    try:
                        await page.wait_for_selector("(//li[@class='article-item'])[1]", state="visible", timeout=15000)
                        found_article = True
                        logger.info("✅ [Token] 已定位可用文章入口。")
                        break
                    except PlaywrightTimeoutError:
                        continue

                if not found_article:
                    logger.error("❌ [Token] 未能在频道列表中找到文章（请检查工号密码是否正确，或网络是否畅通）。")
                    return None

                try:
                    logger.info("🔍 [Token] 触发 SSO 认证跳转...")
                    locator = page.locator("(//li[@class='article-item'])[1]")
                    await locator.wait_for(state="visible", timeout=8000)
                    await page.wait_for_timeout(1000)

                    await locator.evaluate("""
                        node => {
                            let a = node.querySelector('a');
                            if (a) a.removeAttribute('target');
                            node.click();
                        }
                    """)

                    await page.wait_for_url("**/tbeanews.tbea.com/**", wait_until="commit", timeout=15000)
                    logger.info("✅ [Token] SSO 跳转成功！")
                except Exception as e:
                    logger.warning(f"⚠️ [Token] 跳转过程出现波动 (继续检查 Cookie): {e}")

                logger.info("🔍 [Token] 正在提取认证凭据...")
                token_value = None
                all_cookies = []
                for _ in range(50):
                    all_cookies = await context.cookies()
                    token_cookie = next((c for c in all_cookies if c["name"] == "tbea_art_token"), None)
                    if token_cookie and token_cookie.get("value"):
                        token_value = token_cookie["value"]
                        await page.wait_for_timeout(500)
                        all_cookies = await context.cookies()
                        break
                    await page.wait_for_timeout(400)

                ejia_cookie_names = {
                    "JSESSIONID", "toweibologin", "gl", "ERPPRD", "cd", "cn", "cu",
                    "at", "__loginType", "uuid", "webLappToken", "sync_networkid",
                    "sync_userid", "redirectIndexUrl",
                }
                ejia_cookies = {
                    c["name"]: c["value"]
                    for c in all_cookies
                    if c["name"] in ejia_cookie_names or "ejia.tbea.com" in c.get("domain", "")
                }
                im_user_id = ejia_cookies.get("cu", "")

                if token_value and ejia_cookies:
                    logger.info("✅ [Token] 凭据提取成功！Token 与会话 Cookies 已就绪。")
                    return {
                        "token": token_value,
                        "ejia_cookies": ejia_cookies,
                        "ejia_user_id": im_user_id,
                    }

                logger.error("❌ [Token] 未能在 Cookie 中捕获完整的 'tbea_art_token'，请检查账号密码是否正确。")
                return None
            finally:
                try:
                    await browser.close()
                except Exception:
                    pass

    except PlaywrightTimeoutError:
        logger.error("❌ [Token] 登录页面加载超时，请检查企业内网/互联网连接（若开启了代理建议在设置中打开『强制直连』）。")
        return None
    except Exception as e:
        logger.error(f"❌ [Token] 获取凭据时发生异常: {e}")
        return None


def fetch_token_sync(username: str, password: str) -> Optional[dict]:
    """在独立事件循环中同步执行异步 Token 获取流程，供后台工作线程安全调用。"""
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        return loop.run_until_complete(get_new_token(username, password))
    finally:
        loop.close()
