#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""调用 Text2SQL Demo 的接口，拿"模型生成的 SQL"。"""
import json
import time
import urllib.error
import urllib.request


def generate_sql(api_base, question, timeout=300, session_id=None, include_answer=False):
    """
    调用 POST /api/text2sql/generate（只生成 SQL，不执行）。

    返回 dict: {ok, sql, elapsed_ms, http_status, error, rejected, category}
    """
    url = api_base.rstrip("/") + "/api/text2sql/generate"
    payload = {"question": question, "includeAnswer": include_answer}
    if session_id:
        payload["sessionId"] = session_id
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST")

    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
            return {
                "ok": True,
                "sql": body.get("sql"),
                "elapsed_ms": int((time.time() - started) * 1000),
                "http_status": response.status,
                "error": None,
                "rejected": False,
                "steps": body.get("steps") or [],
            }
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            body = json.loads(raw)
        except ValueError:
            body = {"error": raw[:200]}
        return {
            "ok": False,
            "sql": None,
            "elapsed_ms": int((time.time() - started) * 1000),
            "http_status": e.code,
            "error": body.get("error"),
            "rejected": bool(body.get("rejected")),
            "category": body.get("category"),
        }
    except Exception as e:  # 网络/超时等
        return {
            "ok": False,
            "sql": None,
            "elapsed_ms": int((time.time() - started) * 1000),
            "http_status": None,
            "error": "%s: %s" % (type(e).__name__, e),
            "rejected": False,
        }


def health(api_base, timeout=10):
    try:
        with urllib.request.urlopen(api_base.rstrip("/") + "/health", timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        return {"status": "DOWN", "error": str(e)}
