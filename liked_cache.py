# =================================================================
#  liked_cache.py
#  Version: 2.3.0
#  Description: 线程安全的本地点赞记录缓存（按账号隔离）。
#               支持批量缓冲落盘、显式账号透传与旧版数组格式自动迁移。
#               数据存储在 liked_articles.json。
# =================================================================

import json
import os
import threading
from typing import Dict, Iterable, Optional, Set, Union

import config_manager
from app_context import BASE_PATH
from logger import logger

CACHE_FILE = os.path.join(BASE_PATH, "liked_articles.json")

_lock = threading.RLock()
_full_cache: Optional[Dict[str, Set[Union[int, str]]]] = None
_cached_path: Optional[str] = None
_dirty: bool = False


def _normalize_id(article_id: Union[int, str]) -> Union[int, str]:
    if isinstance(article_id, int) and not isinstance(article_id, bool):
        return article_id
    if isinstance(article_id, str) and article_id.isdigit():
        return int(article_id)
    return article_id


def _get_current_username(username: Optional[str] = None) -> str:
    if username:
        return username
    acc = config_manager._get_active_account()
    return (acc.get("username") if acc else None) or "default"


def _load_full_cache() -> Dict[str, Set[Union[int, str]]]:
    """从文件加载完整的账号分离缓存到内存，若遇到旧版数组格式则自动迁移。"""
    global _full_cache, _cached_path, _dirty
    with _lock:
        if _full_cache is not None and _cached_path == CACHE_FILE:
            return _full_cache

        _full_cache = {}
        _cached_path = CACHE_FILE
        _dirty = False

        if not os.path.exists(CACHE_FILE):
            return _full_cache

        try:
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)

            if isinstance(data, list):
                curr_user = _get_current_username()
                _full_cache[curr_user] = {_normalize_id(x) for x in data}
                _save_full_cache()
            elif isinstance(data, dict):
                for k, v in data.items():
                    if isinstance(v, list):
                        _full_cache[k] = {_normalize_id(x) for x in v}
        except (json.JSONDecodeError, OSError):
            _full_cache = {}

        return _full_cache


def _save_full_cache():
    """将内存全量缓存字典原子写回文件。"""
    global _dirty
    with _lock:
        if _full_cache is None:
            return
        parent_dir = os.path.dirname(os.path.abspath(CACHE_FILE))
        if parent_dir:
            os.makedirs(parent_dir, exist_ok=True)
        tmp = f"{CACHE_FILE}.{threading.get_ident()}.tmp"
        out_data = {k: sorted(list(v), key=str) for k, v in _full_cache.items()}
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(out_data, f, ensure_ascii=False)
            os.replace(tmp, CACHE_FILE)
            _dirty = False
        finally:
            if os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except OSError:
                    pass


def is_liked(article_id: Union[int, str], username: Optional[str] = None) -> bool:
    """检查文章是否已在指定（或当前）账号的本地缓存中标记为已赞。"""
    aid = _normalize_id(article_id)
    with _lock:
        cache = _load_full_cache()
        target_user = _get_current_username(username)
        return aid in cache.get(target_user, set())


def mark_liked(article_id: Union[int, str], username: Optional[str] = None, flush: bool = True):
    """
    将文章标记为指定（或当前）账号已点赞。
    ⚠️ 调用方必须确保只在 API 确认点赞/已赞后才调用此函数。
    """
    global _dirty
    aid = _normalize_id(article_id)
    with _lock:
        cache = _load_full_cache()
        target_user = _get_current_username(username)
        if target_user not in cache:
            cache[target_user] = set()

        if aid not in cache[target_user]:
            cache[target_user].add(aid)
            _dirty = True
            if flush:
                _save_full_cache()


def mark_liked_batch(article_ids: Iterable[Union[int, str]], username: Optional[str] = None):
    """批量标记文章已赞，仅在末尾落盘一次。"""
    global _dirty
    with _lock:
        cache = _load_full_cache()
        target_user = _get_current_username(username)
        user_set = cache.setdefault(target_user, set())
        changed = False
        for raw_id in article_ids:
            aid = _normalize_id(raw_id)
            if aid not in user_set:
                user_set.add(aid)
                changed = True
        if changed:
            _dirty = True
            _save_full_cache()


def flush():
    """若内存中存在未落盘的缓存变更，立即写入磁盘。"""
    with _lock:
        if _dirty and _full_cache is not None:
            _save_full_cache()


def get_cache_size(username: Optional[str] = None) -> int:
    """返回指定（或当前）账号缓存中的文章数量。"""
    with _lock:
        cache = _load_full_cache()
        target_user = _get_current_username(username)
        return len(cache.get(target_user, set()))


def clear_cache(username: Optional[str] = None) -> int:
    """清空指定（或当前）账号的点赞缓存，返回清空前的记录数。"""
    global _dirty
    with _lock:
        cache = _load_full_cache()
        target_user = _get_current_username(username)
        count = len(cache.get(target_user, set()))
        cache[target_user] = set()
        _dirty = True
        _save_full_cache()
        logger.info(f"🗑️ [缓存] 账号 ({target_user}) 的点赞记录缓存已清空（共 {count} 条）。")
        return count


def remove_user_cache(username: str):
    """删除账号时同步清理其对应的缓存键。"""
    global _dirty
    if not username:
        return
    with _lock:
        cache = _load_full_cache()
        if username in cache:
            cache.pop(username, None)
            _dirty = True
            _save_full_cache()
            logger.info(f"🗑️ [缓存] 已清理已删除账号 ({username}) 的本地缓存。")
