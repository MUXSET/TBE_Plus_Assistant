# =================================================================
#  tbea_client.py
#  Version: 2.3.0
#  Description: TBEA 公众号文章与新闻点赞统一客户端。
#               收敛频道拉取、翻页防置顶截断、文章详情查询、点赞请求
#               及双端凭据失效检测，消除各业务模块间的重复网络代码。
# =================================================================

import re
import threading
import time
from datetime import datetime
from typing import Callable, Dict, List, Optional
import requests

import http_utils
from logger import logger

PUBACC_ARTICLE_API_URL = "https://ejia.tbea.com/pubacc_v2/api/card/getArticleList"
ARTICLE_DETAIL_API_URL = "https://tbeanews.tbea.com/api/article/detail"
LIKE_API_URL = "https://tbeanews.tbea.com/api/article/addDigg"


class AuthExpiredError(Exception):
    """当 ejia Cookies 或 tbeanews Token 失效时抛出，通知上层刷新凭据。"""


# 兼容旧引用别名
CookiesExpiredError = AuthExpiredError


def sleep_interruptible(seconds: float, stop_event: Optional[threading.Event]) -> bool:
    """可被停止信号瞬时打断的休眠；若收到停止信号则返回 True。"""
    if stop_event is not None:
        return stop_event.wait(seconds)
    time.sleep(seconds)
    return False


def create_pubacc_session(cookies: Optional[Dict[str, str]]) -> requests.Session:
    """创建用于 ejia pubacc_v2 接口的统一 Session。"""
    return http_utils.create_http_session(
        cookies=cookies,
        headers={
            "X-Requested-With": "XMLHttpRequest",
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin": "https://ejia.tbea.com",
            "Referer": "https://ejia.tbea.com/im/xiaoxi/",
        },
    )


def create_news_session(token: Optional[str]) -> requests.Session:
    """创建用于 tbeanews 详情与点赞接口的统一 Session。"""
    return http_utils.create_http_session(headers={"token": token or ""})


def _parse_article_item(item: Dict, channel_name: str) -> Optional[Dict]:
    """标准化单篇公众号文章记录；提取 URL 中的纯数字文章 ID。"""
    art_id = None
    url = item.get("url", "")
    if url:
        m = re.search(r"[?&]id=(\d+)", url)
        if m:
            art_id = int(m.group(1))

    if not art_id:
        art_id = item.get("id") or item.get("yzj_id")

    title = item.get("title", "")
    pub_ts = item.get("publishTimeStamp") or item.get("sendTime")
    if pub_ts:
        try:
            send_time = datetime.fromtimestamp(float(pub_ts) / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
        except Exception:
            send_time = item.get("publishTime", "2000-01-01 00:00:00")
    else:
        send_time = item.get("publishTime", "2000-01-01 00:00:00")

    if not art_id or not title:
        return None

    return {
        "id": art_id,
        "title": title,
        "channel": channel_name,
        "send_time": str(send_time),
    }


def fetch_channel_articles(
    session: requests.Session,
    channel_id: str,
    channel_name: str,
    start_date: str,
    end_date: str,
    stop_event: Optional[threading.Event] = None,
    page_size: int = 25,
    max_pages: int = 20,
) -> List[Dict]:
    """
    获取指定频道在 [start_date, end_date]（YYYY-MM-DD）范围内的推送文章。
    采用整页最大日期判定停止翻页，避免置顶旧文章截断翻页。
    """
    all_articles: List[Dict] = []
    prev_page_sig = None

    for page in range(1, max_pages + 1):
        if stop_event and stop_event.is_set():
            break

        data = {
            "ids": f'["{channel_id}"]',
            "source": "1",
            "pageIndex": str(page),
            "pageSize": str(page_size),
        }

        try:
            r = session.post(PUBACC_ARTICLE_API_URL, data=data, timeout=15)
            if r.status_code in (401, 403):
                raise AuthExpiredError("Session Cookies 已过期 (401)")

            r.raise_for_status()
            ctype = r.headers.get("Content-Type", "").lower()
            if "html" in ctype or r.text.lstrip().startswith("<!"):
                raise AuthExpiredError("Session Cookies 已过期 (返回登录页)")

            try:
                resp = r.json()
            except ValueError:
                raise AuthExpiredError("Session Cookies 已过期 (响应非 JSON)")

            if not resp.get("success", False) and str(resp.get("errorCode", "")) != "200":
                err_code = str(resp.get("errorCode", ""))
                err_msg = str(resp.get("error", ""))
                if err_code == "401" or "登录" in err_msg or "有效期" in err_msg:
                    raise AuthExpiredError(f"Session Cookies 已过期 ({err_msg})")
                logger.error(f"❌ [频道接口] [{channel_name}] API 返回异常: {err_msg} | Raw: {str(resp)[:100]}")
                break

            articles_list = resp.get("data", [])
            if not articles_list:
                break

            page_sig = tuple(item.get("id") or item.get("url") for item in articles_list)
            if page_sig == prev_page_sig:
                break
            prev_page_sig = page_sig

            page_articles: List[Dict] = []
            for item in articles_list:
                parsed = _parse_article_item(item, channel_name)
                if parsed:
                    page_articles.append(parsed)

            for art in page_articles:
                send_date = art["send_time"][:10]
                if start_date <= send_date <= end_date:
                    all_articles.append(art)

            # 仅当整页文章日期均早于起始日期时才停止翻页，避免置顶旧文章导致提前退出
            latest_date = max((a["send_time"][:10] for a in page_articles), default="0000-00-00")
            if latest_date < start_date:
                break

            if len(articles_list) < page_size:
                break

            if sleep_interruptible(0.25, stop_event):
                break

        except AuthExpiredError:
            raise
        except Exception as e:
            logger.error(f"❌ [频道接口] 拉取频道 [{channel_name}] 失败: {e}")
            break

    return all_articles


def fetch_all_channels_articles(
    session: requests.Session,
    channels: List[Dict],
    start_date: str,
    end_date: str,
    stop_event: Optional[threading.Event] = None,
    progress_cb: Optional[Callable[[Dict], None]] = None,
) -> List[Dict]:
    """跨多个频道拉取文章，按文章 ID 去重并按推送时间升序排列。"""
    all_articles: List[Dict] = []
    total_ch = len(channels)

    for idx, ch in enumerate(channels):
        if stop_event and stop_event.is_set():
            break
        ch_name = ch.get("name", ch.get("id", "未知频道"))
        ch_id = ch.get("id", "")
        if not ch_id:
            continue

        if progress_cb:
            progress_cb({
                "stage": "fetch",
                "channel": ch_name,
                "current": idx + 1,
                "total": total_ch,
                "liked": 0,
                "skipped": 0,
                "message": f"正在拉取频道 [{ch_name}] ({idx + 1}/{total_ch})",
            })

        logger.info(f"📡 [频道拉取] 拉取频道 [{ch_name}]...")
        arts = fetch_channel_articles(session, ch_id, ch_name, start_date, end_date, stop_event)
        all_articles.extend(arts)
        logger.info(f"    ↳ 找到 {len(arts)} 篇对应日期范围文章")

    unique_map: Dict = {}
    for art in all_articles:
        if art["id"] not in unique_map:
            unique_map[art["id"]] = art
    unique = list(unique_map.values())
    unique.sort(key=lambda x: x["send_time"])
    return unique


def get_article_detail(news_session: requests.Session, article_id) -> dict:
    """获取文章详情；若 Token 失效则抛出 AuthExpiredError。"""
    r = news_session.get(ARTICLE_DETAIL_API_URL, params={"id": article_id}, timeout=10)
    if r.status_code in (401, 403):
        raise AuthExpiredError("新闻 Token 已过期 (401)")
    r.raise_for_status()
    data = r.json()
    if http_utils.is_token_expired_payload(data):
        raise AuthExpiredError(f"新闻 Token 已失效 ({data.get('msg', '登录超时')})")
    return data


def like_article(news_session: requests.Session, article_id) -> dict:
    """执行文章点赞请求；若 Token 失效则抛出 AuthExpiredError。"""
    r = news_session.post(LIKE_API_URL, json={"id": str(article_id)}, timeout=10)
    if r.status_code in (401, 403):
        raise AuthExpiredError("新闻 Token 已过期 (401)")
    r.raise_for_status()
    data = r.json()
    if http_utils.is_token_expired_payload(data):
        raise AuthExpiredError(f"新闻 Token 已失效 ({data.get('msg', '登录超时')})")
    return data
