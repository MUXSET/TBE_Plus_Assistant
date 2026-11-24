# =================================================================
#  channel_sweep.py
#  Version: 2.3.0
#  Description: 频道扫描点赞模块（统一客户端 + 账号快照隔离版）。
#               - 基于 tbea_client 统一会话与防置顶截断拉取
#               - 显式透传目标 username，杜绝切号/并发串号写回
#               - 修复部分自定义日期误覆盖全月统计大盘的隐患
# =================================================================

import threading
from datetime import datetime
from typing import Callable, Dict, Optional, Tuple
import requests

import config_manager
import http_utils
import liked_cache
import tbea_client
from logger import logger

AuthExpiredError = tbea_client.AuthExpiredError
CookiesExpiredError = tbea_client.AuthExpiredError


def run_sweep(
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    stop_event: Optional[threading.Event] = None,
    dry_run: bool = False,
    username: Optional[str] = None,
    progress_cb: Optional[Callable[[Dict], None]] = None,
) -> Tuple[int, int, int]:
    """
    执行频道文章扫描点赞。

    Args:
        dry_run: 若为 True，则仅统计文章总数，不访问详情页且不点赞
        username: 显式锁定的账号用户名；缺省时回退当前激活账号
        progress_cb: 实时进度回调函数

    Returns:
        (总文章数, 新点赞数, 跳过数)
        总文章数 == -1 表示登录凭据(Cookies 或 Token)已过期，调用方应刷新后重试
    """
    now = datetime.now()
    month_start = now.strftime("%Y-%m-01")
    today_str = now.strftime("%Y-%m-%d")

    if not start_date:
        start_date = month_start
    if not end_date:
        end_date = today_str

    target_user = username or config_manager.get_credentials()[0]
    if not target_user:
        logger.error("❌ [频道扫描] 凭据不足，请先在设置中配置账号。")
        return 0, 0, 0

    if dry_run:
        logger.info(f"🔄 [频道扫描-干跑] ({target_user}) 仅统计日期范围文章数: {start_date} ~ {end_date}")
    else:
        logger.info(f"🔄 [频道扫描] ({target_user}) 扫描日期范围: {start_date} ~ {end_date}")

    tbea_token = config_manager.get_token(username=target_user)
    ejia_cookies = config_manager.get_ejia_cookies(username=target_user)

    if not ejia_cookies or (not dry_run and not tbea_token):
        _, password = config_manager.get_credentials(username=target_user)
        if password:
            logger.warning(f"⚠️ [频道扫描] ({target_user}) 尚未缓存有效凭据，准备触发自动登录。")
            return -1, 0, 0
        logger.error("❌ [频道扫描] 凭据不足，请先在设置中配置账号。")
        return 0, 0, 0

    if config_manager.get_disable_system_proxy():
        logger.info("🛡️ [频道扫描] 已启用环境代理绕过 (强制直连)")

    session_ok = http_utils.check_ejia_session_valid(ejia_cookies)
    if session_ok is False:
        logger.warning("⚠️ [频道扫描] Session Cookies 已过期，需要刷新凭据。")
        return -1, 0, 0

    if not dry_run:
        token_ok = http_utils.check_token_valid(tbea_token)
        if token_ok is False:
            logger.warning("⚠️ [频道扫描] 新闻 Token 已过期，需要刷新凭据。")
            return -1, 0, 0

    target_channels = config_manager.get_channels()

    with tbea_client.create_pubacc_session(ejia_cookies) as pub_session, \
            tbea_client.create_news_session(tbea_token) as news_session:
        try:
            unique = tbea_client.fetch_all_channels_articles(
                pub_session,
                target_channels,
                start_date,
                end_date,
                stop_event=stop_event,
                progress_cb=progress_cb,
            )
        except tbea_client.AuthExpiredError:
            logger.warning("⚠️ [频道扫描] Cookies 已过期。")
            return -1, 0, 0

        if stop_event and stop_event.is_set():
            logger.info("⏹️ [频道扫描] 收到停止信号。")
            return 0, 0, 0

        total = len(unique)

        if dry_run:
            cached_liked = sum(1 for a in unique if liked_cache.is_liked(a["id"], username=target_user))
            logger.info(f"📋 [频道扫描-干跑] ({target_user}) 本月共有 {total} 篇文章（本地已记录已赞 {cached_liked} 篇）。")
            config_manager.update_monthly_total_only(total, min_liked=cached_liked, username=target_user)
            return total, 0, 0

        logger.info(f"📋 [频道扫描] 共有 {total} 篇文章（去重后），开始检查点赞状态...")

        if total == 0:
            logger.info("ℹ️ [频道扫描] 暂无文章需要处理。")
            config_manager.save_sweep_stats(0, 0, 0, username=target_user)
            if start_date == month_start and end_date >= today_str:
                config_manager.save_monthly_stats(0, 0, username=target_user)
            return 0, 0, 0

        liked_count = 0
        skipped_count = 0
        pending_flush = 0
        auth_expired = False

        try:
            for idx, art in enumerate(unique, 1):
                if stop_event and stop_event.is_set():
                    logger.info("⏹️ [频道扫描] 收到停止信号。")
                    break

                art_id = art["id"]
                title = art["title"][:22]
                display = f"「{title}」(ID:{art_id})"

                if progress_cb:
                    progress_cb({
                        "stage": "like",
                        "channel": art.get("channel", ""),
                        "current": idx,
                        "total": total,
                        "liked": liked_count,
                        "skipped": skipped_count,
                        "message": f"({idx}/{total}) {title}",
                    })

                if liked_cache.is_liked(art_id, username=target_user):
                    logger.info(f"💨 [频道扫描] 本地已记录，跳过: {display}")
                    skipped_count += 1
                    continue

                try:
                    d_data = tbea_client.get_article_detail(news_session, art_id)

                    if d_data.get("code") == 1 and d_data.get("data"):
                        is_digg = d_data["data"].get("is_digg", True)
                        if is_digg:
                            logger.info(f"⏭️ [频道扫描] 服务端已赞，跳过: {display}")
                            liked_cache.mark_liked(art_id, username=target_user, flush=False)
                            pending_flush += 1
                            skipped_count += 1
                        else:
                            logger.info(f"🌟 [频道扫描] 发现未赞，点赞中: {display}")
                            like_data = tbea_client.like_article(news_session, art_id)

                            if like_data.get("code") == 1:
                                liked_cache.mark_liked(art_id, username=target_user, flush=False)
                                pending_flush += 1
                                logger.info(f"✅ [频道扫描] {display} 点赞成功！")
                                liked_count += 1
                            elif "重复" in str(like_data.get("msg", "")) or "已点" in str(like_data.get("msg", "")):
                                liked_cache.mark_liked(art_id, username=target_user, flush=False)
                                pending_flush += 1
                                logger.info(f"✅ [频道扫描] {display} 已点赞（服务端确认）")
                                skipped_count += 1
                            else:
                                logger.error(f"❌ [频道扫描] {display} 点赞失败: {like_data.get('msg')}")
                    else:
                        logger.warning(f"⚠️ [频道扫描] 无法获取文章详情: {display} ({d_data.get('msg', '未知响应')})")

                except tbea_client.AuthExpiredError as auth_err:
                    logger.warning(f"⚠️ [频道扫描] {auth_err}，触发凭据刷新。")
                    auth_expired = True
                    return -1, 0, 0
                except requests.exceptions.RequestException as req_err:
                    err_msg = str(req_err)
                    if "10013" in err_msg or "10054" in err_msg or "Connection aborted" in err_msg:
                        logger.error("❌ [频道扫描] 网络被强行阻断 (10013/10054)。")
                        logger.info("ℹ️ [提示] 请确认『设置』中已开启「强制直连 (绕过代理/安全软件)」。")
                    else:
                        logger.error(f"❌ [频道扫描] 网络请求异常: {type(req_err).__name__} - 请检查网络连接。")
                except Exception as e:
                    logger.error(f"❌ [频道扫描] 处理 {display} 时发生未知错误: {e}")

                if pending_flush >= 5:
                    liked_cache.flush()
                    pending_flush = 0

                if tbea_client.sleep_interruptible(0.6, stop_event):
                    logger.info("⏹️ [频道扫描] 收到停止信号。")
                    break
        finally:
            liked_cache.flush()

        if auth_expired:
            return -1, 0, 0

        logger.info(f"🎉 [频道扫描] 完成！共 {total} 篇: 新点赞 {liked_count}，跳过 {skipped_count}。")
        logger.info(f"💾 [频道扫描] 账号 ({target_user}) 本地缓存共记录 {liked_cache.get_cache_size(username=target_user)} 篇文章。")

        config_manager.save_sweep_stats(total, liked_count, skipped_count, username=target_user)
        # 仅当完整扫描了本月月初至今天（及以后）且未被中途中断时，才更新当月大盘
        if start_date == month_start and end_date >= today_str and not (stop_event and stop_event.is_set()):
            config_manager.save_monthly_stats(total, liked_count + skipped_count, username=target_user)

        return total, liked_count, skipped_count
