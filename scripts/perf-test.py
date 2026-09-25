#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Text2SQL Demo 性能测试：数据规模 / SQL 查询延迟 / 接口端到端延迟。

用法：
    # 只测数据库（跑用例集里的参考 SQL + 若干重查询）
    eval/.venv/bin/python scripts/perf-test.py --label "10万订单" --repeat 5

    # 同时测接口（需先启动服务）
    eval/.venv/bin/python scripts/perf-test.py --label "10万订单" --api http://127.0.0.1:8080

输出：控制台表格 + eval/reports/perf-<时间戳>.md / .json
"""
import argparse
import json
import os
import statistics
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parent.parent
DB = dict(
    host=os.getenv("T2SQL_DB_HOST", "127.0.0.1"),
    port=int(os.getenv("T2SQL_DB_PORT", "3306")),
    user=os.getenv("T2SQL_DB_USER", "root"),
    password=os.getenv("T2SQL_DB_PASSWORD", "Atguigu.123"),
    database=os.getenv("T2SQL_DB_NAME", "ecommerce"),
    charset="utf8mb4", cursorclass=pymysql.cursors.Cursor,
)

# 典型"重查询"：全表聚合 / 时间窗口 / 多表关联 / TopN / 趋势
HEAVY_QUERIES = {
    "全表按渠道聚合": "SELECT channel, COUNT(*) c, ROUND(SUM(pay_amount),2) amt FROM t_order GROUP BY channel",
    "近30天下单渠道聚合": "SELECT channel, COUNT(*) c FROM t_order WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) GROUP BY channel",
    "按月趋势(13个月)": "SELECT DATE_FORMAT(create_time,'%Y-%m') m, COUNT(*) c, ROUND(SUM(pay_amount),2) amt FROM t_order GROUP BY m ORDER BY m DESC LIMIT 13",
    "省份销售额Top10(两表)": "SELECT p.province_name, ROUND(SUM(o.pay_amount),2) amt FROM t_order o JOIN t_province p ON o.province_id=p.id WHERE o.order_status IN (2,3,4) GROUP BY p.province_name ORDER BY amt DESC LIMIT 10",
    "类目销售额Top10(三表)": "SELECT p.category_name, ROUND(SUM(i.item_amount),2) amt, SUM(i.quantity) qty FROM t_order_item i JOIN t_order o ON o.id=i.order_id JOIN t_product p ON p.id=i.product_id WHERE o.order_status IN (2,3,4) GROUP BY p.category_name ORDER BY amt DESC LIMIT 10",
    "单品销量Top10(三表)": "SELECT p.product_name, SUM(i.quantity) qty FROM t_order_item i JOIN t_order o ON o.id=i.order_id JOIN t_product p ON p.id=i.product_id WHERE o.order_status IN (2,3,4) GROUP BY p.id, p.product_name ORDER BY qty DESC LIMIT 10",
    "用户消费Top10(两表)": "SELECT u.username, u.total_amount, p.province_name FROM t_user u LEFT JOIN t_province p ON p.id=u.province_id ORDER BY u.total_amount DESC LIMIT 10",
    "复购用户统计(HAVING)": "SELECT COUNT(*) FROM (SELECT user_id FROM t_order GROUP BY user_id HAVING COUNT(*) >= 2) t",
    "钻石会员客单价(两表)": "SELECT COUNT(DISTINCT u.id) n, ROUND(SUM(o.pay_amount)/COUNT(DISTINCT o.id),2) aov FROM t_user u JOIN t_order o ON o.user_id=u.id WHERE u.member_level=4 AND o.order_status IN (2,3,4)",
    "明细全表计数": "SELECT COUNT(*) FROM t_order_item",
}


def connect():
    return pymysql.connect(**DB)


def table_stats(conn):
    with conn.cursor() as cur:
        cur.execute("""
            SELECT TABLE_NAME, TABLE_ROWS,
                   CAST(IFNULL(DATA_LENGTH, 0) / 1024 / 1024 AS DECIMAL(12,1)),
                   CAST(IFNULL(INDEX_LENGTH, 0) / 1024 / 1024 AS DECIMAL(12,1))
            FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_ROWS DESC
        """)
        rows = cur.fetchall()
    stats = []
    for name, approx, data_mb, index_mb in rows:
        with conn.cursor() as cur:
            started = time.time()
            cur.execute("SELECT COUNT(*) FROM `%s`" % name)
            cnt = cur.fetchone()[0]
            cost = (time.time() - started) * 1000
        stats.append({"table": name, "rows": cnt, "count_ms": round(cost, 1),
                      "data_mb": float(data_mb or 0), "index_mb": float(index_mb or 0)})
    return stats


def explain(conn, sql):
    """返回访问类型摘要：是否出现全表扫描（ALL）。"""
    try:
        with conn.cursor() as cur:
            cur.execute("EXPLAIN " + sql)
            cols = [d[0] for d in cur.description]
            rows = cur.fetchall()
        types, keys = set(), set()
        for row in rows:
            rec = dict(zip(cols, row))
            if rec.get("type"):
                types.add(rec["type"])
            if rec.get("key"):
                keys.add(rec["key"])
        return {"access_types": sorted(types), "indexes": sorted(keys), "full_scan": "ALL" in types}
    except Exception as e:
        return {"error": str(e)}


def run_query(conn, sql, repeat, warmup=1):
    times, rows = [], 0
    for i in range(repeat + warmup):
        with conn.cursor() as cur:
            started = time.time()
            cur.execute(sql)
            result = cur.fetchall()
            cost = (time.time() - started) * 1000
        if i >= warmup:
            times.append(cost)
            rows = len(result)
    times_sorted = sorted(times)
    return {
        "rows": rows,
        "min_ms": round(min(times), 1),
        "p50_ms": round(statistics.median(times), 1),
        "p95_ms": round(times_sorted[min(len(times_sorted) - 1, int(len(times_sorted) * 0.95))], 1),
        "max_ms": round(max(times), 1),
        "runs": len(times),
    }


def api_call(url, payload, timeout=300):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            return {"ok": True, "ms": round((time.time() - started) * 1000), "body": body}
    except urllib.error.HTTPError as e:
        return {"ok": False, "ms": round((time.time() - started) * 1000),
                "error": e.read().decode("utf-8", errors="replace")[:200]}
    except Exception as e:
        return {"ok": False, "ms": round((time.time() - started) * 1000), "error": str(e)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default="", help="本次测试标签，例如「10万订单」")
    ap.add_argument("--repeat", type=int, default=5)
    ap.add_argument("--api", default="", help="被测服务地址，留空则跳过接口测试")
    ap.add_argument("--out-dir", default=str(ROOT / "eval" / "reports"))
    ap.add_argument("--end2end", type=int, default=3, help="端到端 Text2SQL 调用次数")
    args = ap.parse_args()

    conn = connect()
    report = {"label": args.label, "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
              "repeat": args.repeat, "tables": table_stats(conn), "queries": {}, "api": {}}

    print("=" * 96)
    print("数据规模")
    print("=" * 96)
    for t in report["tables"]:
        print("  %-16s %9d 行   COUNT(*) %6.1f ms   data %6.1f MB  index %5.1f MB"
              % (t["table"], t["rows"], t["count_ms"], t["data_mb"] or 0, t["index_mb"] or 0))

    # 1) 用例集里的参考 SQL
    dataset_path = ROOT / "eval" / "dataset.jsonl"
    cases = []
    if dataset_path.exists():
        cases = [json.loads(l) for l in dataset_path.read_text(encoding="utf-8").splitlines() if l.strip()]

    print()
    print("=" * 96)
    print("用例集参考 SQL（%d 条，每条跑 %d 次取中位数）" % (len(cases), args.repeat))
    print("=" * 96)
    print("  %-6s %-8s %8s %8s %8s %8s  %s" % ("用例", "难度", "行数", "p50(ms)", "p95(ms)", "max(ms)", "索引/扫描"))
    for case in cases:
        sql = case["reference_sql"]
        stat = run_query(conn, sql, args.repeat)
        plan = explain(conn, sql)
        report["queries"]["case:" + case["id"]] = {**stat, "plan": plan, "question": case["question"]}
        print("  %-6s %-8s %8d %8.1f %8.1f %8.1f  %s%s"
              % (case["id"], case["level"], stat["rows"], stat["p50_ms"], stat["p95_ms"], stat["max_ms"],
                 ",".join(plan.get("indexes") or []) or "-",
                 "  ⚠全表扫描" if plan.get("full_scan") else ""))

    # 2) 典型重查询
    print()
    print("=" * 96)
    print("典型重查询")
    print("=" * 96)
    print("  %-26s %8s %8s %8s  %s" % ("查询", "行数", "p50(ms)", "p95(ms)", "索引/扫描"))
    for name, sql in HEAVY_QUERIES.items():
        stat = run_query(conn, sql, args.repeat)
        plan = explain(conn, sql)
        report["queries"]["heavy:" + name] = {**stat, "plan": plan, "sql": sql}
        print("  %-26s %8d %8.1f %8.1f  %s%s"
              % (name, stat["rows"], stat["p50_ms"], stat["p95_ms"],
                 ",".join(plan.get("indexes") or []) or "-",
                 "  ⚠全表扫描" if plan.get("full_scan") else ""))
    conn.close()

    # 3) 接口层
    if args.api:
        print()
        print("=" * 96)
        print("接口层（%s）" % args.api)
        print("=" * 96)
        # 3.1 只读 SQL 执行接口（纯 API + MyBatis 开销）
        exec_sql = HEAVY_QUERIES["类目销售额Top10(三表)"]
        exec_times = []
        for _ in range(args.repeat):
            r = api_call(args.api + "/api/sql/execute", {"sql": exec_sql})
            if r["ok"]:
                exec_times.append(r["ms"])
        if exec_times:
            report["api"]["sql_execute"] = {
                "p50_ms": round(statistics.median(exec_times), 1), "runs": len(exec_times),
                "min_ms": min(exec_times), "max_ms": max(exec_times)}
            print("  POST /api/sql/execute        p50 %6.1f ms  (min %d / max %d)"
                  % (report["api"]["sql_execute"]["p50_ms"], min(exec_times), max(exec_times)))
        # 3.2 表结构读取（大表下 COUNT(*) 会变慢，这项能看出影响）
        schema_ms = []
        for _ in range(args.repeat):
            started = time.time()
            try:
                with urllib.request.urlopen(args.api + "/api/schema", timeout=60) as resp:
                    json.loads(resp.read().decode("utf-8"))
                schema_ms.append((time.time() - started) * 1000)
            except Exception:
                pass
        if schema_ms:
            report["api"]["schema"] = {"p50_ms": round(statistics.median(schema_ms), 1), "runs": len(schema_ms)}
            print("  GET  /api/schema             p50 %6.1f ms  （含各表 COUNT(*)）" % statistics.median(schema_ms))
        # 3.3 端到端 Text2SQL：只生成 SQL（表结构检索 + SQL 编写）
        gen_times, gen_rows = [], []
        for i in range(args.end2end):
            r = api_call(args.api + "/api/text2sql/generate",
                         {"question": "各商品类目的销售额和销量排名"})
            if r["ok"]:
                gen_times.append(r["ms"])
                gen_rows.append(r["body"].get("steps"))
        if gen_times:
            report["api"]["text2sql_generate"] = {"runs": len(gen_times), "times_ms": gen_times,
                                                  "p50_ms": round(statistics.median(gen_times), 1)}
            print("  POST /api/text2sql/generate  %d 次：%s ms（含大模型，p50 %.0f）"
                  % (len(gen_times), gen_times, statistics.median(gen_times)))
            if gen_rows and gen_rows[0]:
                for step in gen_rows[0]:
                    print("      - %s: %s ms" % (step.get("stage"), step.get("elapsedMs")))

    # 4) 落盘
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    (out_dir / ("perf-%s.json" % stamp)).write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md = ["# 性能测试报告 %s" % (args.label or ""), "",
          "- 时间：%s" % report["time"], "- 每条 SQL 执行次数：%d（取中位数）" % args.repeat, "",
          "## 数据规模", "", "| 表 | 行数 | COUNT(*) ms | 数据 MB | 索引 MB |", "| --- | --- | --- | --- | --- |"]
    for t in report["tables"]:
        md.append("| %s | %d | %.1f | %.1f | %.1f |" % (t["table"], t["rows"], t["count_ms"],
                                                        t["data_mb"] or 0, t["index_mb"] or 0))
    md += ["", "## 用例集参考 SQL", "", "| 用例 | 难度 | 行数 | p50 | p95 | max | 索引 | 全表扫描 |",
           "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for key, q in report["queries"].items():
        if not key.startswith("case:"):
            continue
        md.append("| %s | - | %d | %.1f | %.1f | %.1f | %s | %s |"
                  % (key[5:], q["rows"], q["p50_ms"], q["p95_ms"], q["max_ms"],
                     ",".join(q["plan"].get("indexes") or []) or "-",
                     "是" if q["plan"].get("full_scan") else "否"))
    md += ["", "## 典型重查询", "", "| 查询 | 行数 | p50 | p95 | max | 索引 | 全表扫描 |",
           "| --- | --- | --- | --- | --- | --- | --- |"]
    for key, q in report["queries"].items():
        if not key.startswith("heavy:"):
            continue
        md.append("| %s | %d | %.1f | %.1f | %.1f | %s | %s |"
                  % (key[6:], q["rows"], q["p50_ms"], q["p95_ms"], q["max_ms"],
                     ",".join(q["plan"].get("indexes") or []) or "-",
                     "是" if q["plan"].get("full_scan") else "否"))
    if report["api"]:
        md += ["", "## 接口层", "", "```", json.dumps(report["api"], ensure_ascii=False, indent=2), "```"]
    md_path = out_dir / ("perf-%s.md" % stamp)
    md_path.write_text("\n".join(md), encoding="utf-8")
    print()
    print("报告：%s" % md_path)


if __name__ == "__main__":
    main()
