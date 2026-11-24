# =================================================================
#  report_exporter.py
#  Version: 2.3.0
#  Description: 月度报告导出模块（统一客户端 + 缓存回写版）。
#               - 复用 tbea_client 拉取频道文章与核验服务端状态
#               - 透传 AuthExpiredError 支持上层自动续期重试
#               - 导出时将服务端已赞文章顺手同步回写本地账号缓存
# =================================================================

import calendar
import csv
import os
import threading
from datetime import datetime
from typing import Callable, Dict, Optional

import config_manager
import liked_cache
import tbea_client
from app_context import BASE_PATH
from logger import logger


def export_monthly_report(
    stop_event: Optional[threading.Event] = None,
    target_month: Optional[str] = None,
    username: Optional[str] = None,
    progress_cb: Optional[Callable[[Dict], None]] = None,
) -> str:
    """
    导出目标月份点赞报告为 CSV。
    若遇 Cookies 或 Token 失效将抛出 tbea_client.AuthExpiredError。
    返回: 生成的文件路径，失败或取消返回空字符串。
    """
    current_month = target_month or datetime.now().strftime("%Y-%m")
    try:
        year_int, month_int = map(int, current_month.split("-"))
        last_day = calendar.monthrange(year_int, month_int)[1]
    except Exception:
        last_day = 31

    start_date = f"{current_month}-01"
    end_date = f"{current_month}-{last_day:02d}"

    target_user = username or config_manager.get_credentials()[0]
    if not target_user:
        logger.error("❌ [报告导出] 未配置账号。")
        return ""

    logger.info(f"📊 [报告导出] ({target_user}) 开始生成 {current_month} 点赞报告...")

    tbea_token = config_manager.get_token(username=target_user)
    ejia_cookies = config_manager.get_ejia_cookies(username=target_user)

    if not tbea_token or not ejia_cookies:
        raise tbea_client.AuthExpiredError("导出报告凭据缺失或已过期")

    channels = config_manager.get_channels()

    with tbea_client.create_pubacc_session(ejia_cookies) as im_session, \
            tbea_client.create_news_session(tbea_token) as news_session:
        unique = tbea_client.fetch_all_channels_articles(
            im_session,
            channels,
            start_date,
            end_date,
            stop_event=stop_event,
            progress_cb=progress_cb,
        )

        if stop_event and stop_event.is_set():
            logger.info("⏹️ [报告导出] 已取消。")
            return ""

        total = len(unique)
        logger.info(f"📋 [报告导出] 共 {total} 篇文章，正在核验服务端点赞状态...")

        liked_so_far = 0
        try:
            for idx, art in enumerate(unique, 1):
                if stop_event and stop_event.is_set():
                    logger.info("⏹️ [报告导出] 已取消。")
                    return ""

                if progress_cb:
                    progress_cb({
                        "stage": "export",
                        "channel": art.get("channel", ""),
                        "current": idx,
                        "total": total,
                        "liked": liked_so_far,
                        "skipped": 0,
                        "message": f"核验状态 ({idx}/{total}) {art['title'][:20]}",
                    })

                try:
                    d = tbea_client.get_article_detail(news_session, art["id"])
                    if d.get("code") == 1 and d.get("data"):
                        is_digg = bool(d["data"].get("is_digg"))
                        art["is_digg"] = "✅ 已赞" if is_digg else "❌ 未赞"
                        if is_digg:
                            liked_so_far += 1
                            liked_cache.mark_liked(art["id"], username=target_user, flush=False)
                    else:
                        art["is_digg"] = "⚠️ 未知"
                except tbea_client.AuthExpiredError:
                    raise
                except Exception:
                    art["is_digg"] = "⚠️ 查询失败"

                if tbea_client.sleep_interruptible(0.35, stop_event):
                    logger.info("⏹️ [报告导出] 已取消。")
                    return ""
        finally:
            liked_cache.flush()

    desktop = os.path.expanduser("~/Desktop")
    if not os.path.isdir(desktop):
        desktop = BASE_PATH

    filename = f"TBE+月度点赞报告_{current_month}_{target_user}.csv"
    filepath = os.path.join(desktop, filename)

    with open(filepath, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        writer.writerow(["文章ID", "标题", "来源频道", "推送时间", "点赞状态"])
        for art in unique:
            writer.writerow([
                art["id"],
                art["title"],
                art["channel"],
                art["send_time"],
                art["is_digg"],
            ])

    liked = sum(1 for a in unique if "已赞" in a.get("is_digg", ""))
    logger.info(f"🎉 [报告导出] 报告已生成！共 {total} 篇（已赞 {liked} 篇）")
    logger.info(f"📁 [报告导出] 文件位置: {filepath}")
    return filepath
