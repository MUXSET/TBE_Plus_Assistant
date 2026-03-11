# =================================================================
#  liked_cache.py
#  Version: 1.0.0
#  Description: 本地点赞记录缓存。
#               只有在 API 确认点赞成功后才记录，避免冗余请求。
#               数据存储在 liked_articles.json。
# =================================================================

import json
import os
from app_context import BASE_PATH
from logger import logger
from config_manager import _get_active_account

CACHE_FILE = os.path.join(BASE_PATH, "liked_articles.json")

_full_cache = None  # Dict[str, set] 内存缓存

def _get_current_username() -> str:
    acc = _get_active_account()
    return acc.get("username", "default") if acc else "default"

def _load_full_cache() -> dict:
    """从文件加载完整的账号分离缓存到内存，如果遇到旧版数组格式则自动迁移。"""
    global _full_cache
    if _full_cache is not None:
        return _full_cache
    
    _full_cache = {}
    if not os.path.exists(CACHE_FILE):
        return _full_cache
    
    try:
        with open(CACHE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            
            if isinstance(data, list):
                # 兼容升级：旧版全局数组缓存，将其归入当前账号
                curr_user = _get_current_username()
                _full_cache[curr_user] = set(data)
                _save_full_cache()  # 立即落盘转换为新字典格式
            elif isinstance(data, dict):
                # 新版字典格式，每个用户的点赞记录分离
                for k, v in data.items():
                    if isinstance(v, list):
                        _full_cache[k] = set(v)
    except (json.JSONDecodeError, OSError):
        _full_cache = {}
    
    return _full_cache

def _save_full_cache():
    """将内存全量缓存字典写回文件（原子写入）。"""
    if _full_cache is None:
        return
    tmp = CACHE_FILE + ".tmp"
    
    # 转换为 JSON 可序列化的 list
    out_data = {k: sorted(list(v), key=str) for k, v in _full_cache.items()}
    
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(out_data, f)
    os.replace(tmp, CACHE_FILE)

def is_liked(article_id: int) -> bool:
    """检查文章是否已在当前账号的本地缓存中标记为已赞。"""
    cache = _load_full_cache()
    curr_user = _get_current_username()
    return article_id in cache.get(curr_user, set())

def mark_liked(article_id: int):
    """
    将文章标记为当前账号已点赞。
    ⚠️ 调用方必须确保只在 API 确认点赞成功后才调用此函数。
    """
    cache = _load_full_cache()
    curr_user = _get_current_username()
    if curr_user not in cache:
        cache[curr_user] = set()
        
    if article_id not in cache[curr_user]:
        cache[curr_user].add(article_id)
        _save_full_cache()

def get_cache_size() -> int:
    """返回当前账号缓存中的文章数量。"""
    cache = _load_full_cache()
    curr_user = _get_current_username()
    return len(cache.get(curr_user, set()))

def clear_cache():
    """清空当前账号的点赞缓存。"""
    global _full_cache
    cache = _load_full_cache()
    curr_user = _get_current_username()
    if curr_user in cache:
        cache[curr_user] = set()
    _save_full_cache()
    logger.info(f"🗑️ [缓存] 当前账号 ({curr_user}) 的点赞记录缓存已清空。")
