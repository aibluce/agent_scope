#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Text2SQL 生成质量评估（RAGAS 0.4.3 + 执行准确性）

用法：
    # 1) 先启动被测服务（MVC 版）
    bash scripts/run-api.sh
    # 2) 跑评估（默认 20 条用例，评估 /api/text2sql/generate 生成的 SQL）
    eval/.venv/bin/python eval/run_eval.py
    # 常用参数
    eval/.venv/bin/python eval/run_eval.py --limit 5              # 只跑前 5 条
    eval/.venv/bin/python eval/run_eval.py --only q07,q08         # 只跑指定用例
    eval/.venv/bin/python eval/run_eval.py --no-judge             # 关掉 LLM 裁判，只跑执行准确性
    eval/.venv/bin/python eval/run_eval.py --gen-file out.jsonl   # 离线评估已有 SQL（不调接口）

输出：
    eval/reports/eval-<时间戳>.json     完整明细
    eval/reports/eval-<时间戳>.md       可直接阅读的报告
    eval/experiments/<name>/             RAGAS experiment 落盘的结果
"""
import argparse
import asyncio
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pandas as pd
from ragas import Dataset, experiment

from db import execute_sql
from ragas_metrics import (core_values_match, execution_accuracy,
                           execution_accuracy_setwise, sql_query_equivalence)
from text2sql_client import generate_sql, health

EVAL_DIR = Path(__file__).resolve().parent
LEVELS = ["easy", "medium", "hard"]

# RAGAS 的 arun 会把数据集里所有行一次性并发跑（asyncio.as_completed），
# 直接跑会把被测模型和自己的裁判都打到限流上，所以这里用信号量限流。
ROW_CONCURRENCY = int(os.getenv("EVAL_CONCURRENCY", "3"))
_row_semaphore = asyncio.Semaphore(ROW_CONCURRENCY)


# --------------------------------------------------------------------------
# RAGAS experiment：每行 = 一个问题
# --------------------------------------------------------------------------
@experiment()
async def text2sql_experiment(row, api_base: str, use_judge: bool, gen_file: str = ""):
    """对单个问题：调用接口拿生成 SQL → 执行 → RAGAS 指标打分。"""
    async with _row_semaphore:
        return await _evaluate_row(row, api_base, use_judge, gen_file)


async def _evaluate_row(row, api_base, use_judge, gen_file=""):
    question = row["question"]
    generated = None
    if gen_file:
        # 离线模式：从已生成的 SQL 文件里读
        generated = json.loads(row["generated_json"]) if row.get("generated_json") else None
    if generated is None:
        generated = generate_sql(api_base, question)

    predicted_sql = generated.get("sql") or ""
    predicted_success, predicted_result = (False, generated.get("error") or "未生成 SQL")
    if predicted_sql:
        predicted_success, predicted_result = execute_sql(predicted_sql)

    accuracy = await execution_accuracy.ascore(
        expected_sql=row["reference_sql"],
        predicted_success=predicted_success,
        predicted_result=predicted_result,
    )
    accuracy_setwise = await execution_accuracy_setwise.ascore(
        expected_sql=row["reference_sql"],
        predicted_success=predicted_success,
        predicted_result=predicted_result,
    )

    core_match = await core_values_match.ascore(
        expected_sql=row["reference_sql"],
        predicted_success=predicted_success,
        predicted_result=predicted_result,
    )

    equivalence = None
    if use_judge and predicted_sql:
        equivalence = await sql_query_equivalence.ascore(
            question=question, expected_sql=row["reference_sql"], predicted_sql=predicted_sql)

    rows = 0
    gen_cols = ref_cols = 0
    if isinstance(predicted_result, pd.DataFrame):
        rows = len(predicted_result)
        gen_cols = len(predicted_result.columns)
    ok_ref, ref_df = execute_sql(row["reference_sql"])
    if ok_ref and isinstance(ref_df, pd.DataFrame):
        ref_cols = len(ref_df.columns)

    return {
        "id": row["id"],
        "level": row["level"],
        "question": question,
        "reference_sql": row["reference_sql"],
        "predicted_sql": predicted_sql,
        "generate_ok": bool(generated.get("ok")),
        "guard_rejected": bool(generated.get("rejected")),
        "generate_error": generated.get("error"),
        "generate_ms": generated.get("elapsed_ms", 0),
        "predicted_exec_ok": bool(predicted_success),
        "predicted_rows": rows,
        "predicted_columns": gen_cols,
        "reference_columns": ref_cols,
        "execution_accuracy": accuracy.value,
        "execution_accuracy_setwise": accuracy_setwise.value,
        "core_values_match": core_match.value,
        "accuracy_reason": accuracy_setwise.reason,
        "core_match_reason": core_match.reason,
        "sql_query_equivalence": None if equivalence is None else equivalence.value,
        "judge_reason": None if equivalence is None else equivalence.reason,
    }


# --------------------------------------------------------------------------
# 数据加载
# --------------------------------------------------------------------------
def load_cases(dataset_path, limit=None, only=None):
    cases = []
    with open(dataset_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    if only:
        wanted = {x.strip() for x in only.split(",") if x.strip()}
        cases = [c for c in cases if c["id"] in wanted]
    if limit:
        cases = cases[:limit]
    return cases


def build_dataset(cases, root_dir):
    dataset = Dataset(name="text2sql_ecommerce", backend="local/csv", root_dir=str(root_dir))
    for case in cases:
        dataset.append({
            "id": case["id"],
            "level": case["level"],
            "question": case["question"],
            "reference_sql": case["reference_sql"],
        })
    return dataset


# --------------------------------------------------------------------------
# 报告
# --------------------------------------------------------------------------
def summarize(rows):
    total = len(rows)
    if total == 0:
        return {}

    def rate(values):
        return round(sum(1 for v in values if v == "correct") / total, 4)

    judges = [r["sql_query_equivalence"] for r in rows if r["sql_query_equivalence"] is not None]
    summary = {
        "cases": total,
        "generate_success_rate": round(sum(1 for r in rows if r["generate_ok"]) / total, 4),
        "execution_accuracy": rate([r["execution_accuracy"] for r in rows]),
        "execution_accuracy_setwise": rate([r["execution_accuracy_setwise"] for r in rows]),
        "core_values_match": rate([r["core_values_match"] for r in rows]),
        "sql_valid_rate": round(sum(1 for r in rows if r["predicted_exec_ok"]) / total, 4),
        "guard_rejected": sum(1 for r in rows if r["guard_rejected"]),
        "avg_generate_ms": int(sum(r["generate_ms"] for r in rows) / total),
        "avg_predicted_columns": round(sum(r["predicted_columns"] for r in rows) / total, 2),
        "avg_reference_columns": round(sum(r["reference_columns"] for r in rows) / total, 2),
        "over_answer_cases": sum(1 for r in rows if r["predicted_columns"] > r["reference_columns"]),
        "avg_sql_query_equivalence": round(sum(judges) / len(judges), 4) if judges else None,
        "by_level": {},
    }
    for level in LEVELS:
        subset = [r for r in rows if r["level"] == level]
        if not subset:
            continue
        summary["by_level"][level] = {
            "cases": len(subset),
            "execution_accuracy": round(
                sum(1 for r in subset if r["execution_accuracy"] == "correct") / len(subset), 4),
            "execution_accuracy_setwise": round(
                sum(1 for r in subset if r["execution_accuracy_setwise"] == "correct") / len(subset), 4),
            "core_values_match": round(
                sum(1 for r in subset if r["core_values_match"] == "correct") / len(subset), 4),
            "avg_sql_query_equivalence": round(
                sum(r["sql_query_equivalence"] for r in subset
                    if r["sql_query_equivalence"] is not None)
                / max(1, sum(1 for r in subset if r["sql_query_equivalence"] is not None)), 4),
        }
    return summary


def write_markdown(path, summary, rows, meta):
    lines = []
    lines.append("# Text2SQL 生成质量评估报告\n")
    lines.append("- 评估时间：%s" % meta["time"])
    lines.append("- 被测接口：`%s/api/text2sql/generate`（模型 %s）" % (meta["api"], meta["model"]))
    lines.append("- 用例集：%s（%d 条）" % (meta["dataset"], summary.get("cases", 0)))
    lines.append("- 评估框架：RAGAS %s（自定义 SQL 指标）+ datacompy 结果集比对\n" % meta["ragas"])

    lines.append("## 总体指标\n")
    lines.append("| 指标 | 值 | 说明 |")
    lines.append("| --- | --- | --- |")
    lines.append("| 执行准确性 EX（官方口径，按行号对齐） | **%.2f%%** | RAGAS `execution_accuracy` |"
                 % (100 * summary.get("execution_accuracy", 0)))
    lines.append("| 执行准确性 EX（行序无关） | **%.2f%%** | RAGAS `execution_accuracy_setwise`（列名/列数不同即判错，严格口径） |"
                 % (100 * summary.get("execution_accuracy_setwise", 0)))
    lines.append("| **核心数值匹配** | **%.2f%%** | RAGAS `core_values_match`（多输出列不扣分，只要被问的指标算对） |"
                 % (100 * summary.get("core_values_match", 0)))
    lines.append("| SQL 语义等价（LLM 裁判均分） | %s | RAGAS `sql_query_equivalence`（0~1） |"
                 % ("-" if summary.get("avg_sql_query_equivalence") is None
                    else "%.3f" % summary["avg_sql_query_equivalence"]))
    lines.append("| SQL 可执行率 | %.2f%% | 生成 SQL 能在 MySQL 跑通 |" % (100 * summary.get("sql_valid_rate", 0)))
    lines.append("| 生成成功率 | %.2f%% | 接口正常返回 SQL |" % (100 * summary.get("generate_success_rate", 0)))
    lines.append("| 被安全网关拦截 | %d 条 | 越权/写操作等 |" % summary.get("guard_rejected", 0))
    lines.append("| 平均生成耗时 | %d ms | 只生成 SQL（含表结构检索） |" % summary.get("avg_generate_ms", 0))
    lines.append("| 过度输出用例数 | %d 条 | 生成列数多于参考列数（平均 %.2f 列 vs 参考 %.2f 列） |"
                 % (summary.get("over_answer_cases", 0),
                    summary.get("avg_predicted_columns", 0), summary.get("avg_reference_columns", 0)))
    lines.append("")

    if summary.get("by_level"):
        lines.append("## 分难度\n")
        lines.append("| 难度 | 用例数 | EX（严格） | 核心数值匹配 | 语义等价均分 |")
        lines.append("| --- | --- | --- | --- | --- |")
        for level, st in summary["by_level"].items():
            lines.append("| %s | %d | %.2f%% | %.2f%% | %.3f |"
                         % (level, st["cases"], 100 * st["execution_accuracy_setwise"],
                            100 * st["core_values_match"], st["avg_sql_query_equivalence"]))
        lines.append("")

    lines.append("## 明细\n")
    lines.append("| # | 难度 | 问题 | EX | 核心数值 | 语义等价 | 生成耗时 | 失败原因 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for r in rows:
        ex = "✅" if r["execution_accuracy_setwise"] == "correct" else "❌"
        core = "✅" if r["core_values_match"] == "correct" else "❌"
        eq = "-" if r["sql_query_equivalence"] is None else "%.1f" % r["sql_query_equivalence"]
        reason = ""
        if r["core_values_match"] != "correct":
            reason = (r["generate_error"] or r["core_match_reason"] or "")[:110].replace("|", "/").replace("\n", " ")
        elif r["execution_accuracy_setwise"] != "correct":
            reason = "数值对，但列名/列数/枚举表述不同（严格口径判错）"
        lines.append("| %s | %s | %s | %s | %s | %s | %d ms | %s |"
                     % (r["id"], r["level"], r["question"][:24], ex, core, eq, r["generate_ms"], reason))
    lines.append("")

    failed = [r for r in rows if r["core_values_match"] != "correct"]
    if failed:
        lines.append("## 失败用例详情\n")
        for r in failed:
            lines.append("### %s（%s）%s\n" % (r["id"], r["level"], r["question"]))
            lines.append("- 生成 SQL：\n\n```sql\n%s\n```\n" % (r["predicted_sql"] or "(未生成)"))
            lines.append("- 参考 SQL：\n\n```sql\n%s\n```\n" % r["reference_sql"])
            lines.append("- 判定：%s\n" % (r["generate_error"] or r["accuracy_reason"] or ""))
            if r["judge_reason"]:
                lines.append("- 裁判意见：%s\n" % r["judge_reason"])
    Path(path).write_text("\n".join(lines), encoding="utf-8")


# --------------------------------------------------------------------------
async def main():
    parser = argparse.ArgumentParser(description="Text2SQL 生成质量评估（RAGAS）")
    parser.add_argument("--api", default="http://127.0.0.1:8080", help="被测服务地址")
    parser.add_argument("--dataset", default=str(EVAL_DIR / "dataset.jsonl"))
    parser.add_argument("--out-dir", default=str(EVAL_DIR / "reports"))
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--only", default=None, help="只跑指定用例，如 q07,q08")
    parser.add_argument("--no-judge", action="store_true", help="关闭 LLM 裁判（省钱，只跑执行准确性）")
    parser.add_argument("--gen-file", default="", help="离线模式：读取已生成 SQL 的 jsonl")
    parser.add_argument("--name", default=None, help="RAGAS experiment 名称")
    parser.add_argument("--concurrency", type=int, default=None,
                        help="并发用例数（默认 3，太大容易被模型限流）")
    args = parser.parse_args()

    import ragas
    if args.concurrency:
        global _row_semaphore
        _row_semaphore = asyncio.Semaphore(args.concurrency)
    cases = load_cases(args.dataset, args.limit, args.only)
    if not cases:
        print("没有可评估的用例")
        return 1

    use_judge = not args.no_judge
    if not args.gen_file:
        status = health(args.api)
        if status.get("status") != "UP":
            print("被测服务不可用：%s（请先执行 bash scripts/run-api.sh）" % status)
            return 1
        print("被测服务: %s | 模型: %s" % (args.api, status.get("model")))
    print("用例数: %d | LLM 裁判: %s | 并发: %d\n"
          % (len(cases), "开" if use_judge else "关", args.concurrency or ROW_CONCURRENCY))

    dataset = build_dataset(cases, EVAL_DIR)
    run_name = args.name or ("text2sql_eval_" + datetime.now().strftime("%Y%m%d_%H%M%S"))
    started = time.time()
    results = await text2sql_experiment.arun(
        dataset, name=run_name, api_base=args.api, use_judge=use_judge, gen_file=args.gen_file)
    rows = list(results)
    elapsed = time.time() - started

    summary = summarize(rows)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    meta = {
        "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "api": args.api,
        "model": health(args.api).get("model"),
        "dataset": args.dataset,
        "ragas": ragas.__version__,
        "elapsed_seconds": round(elapsed, 1),
        "run_name": run_name,
    }
    json_path = out_dir / ("eval-%s.json" % stamp)
    md_path = out_dir / ("eval-%s.md" % stamp)
    json_path.write_text(json.dumps(
        {"meta": meta, "summary": summary, "rows": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown(md_path, summary, rows, meta)

    print("\n====== 评估结果 ======")
    print("执行准确性 EX（严格）: %.2f%%" % (100 * summary.get("execution_accuracy_setwise", 0)))
    print("核心数值匹配（宽容）: %.2f%%" % (100 * summary.get("core_values_match", 0)))
    if summary.get("avg_sql_query_equivalence") is not None:
        print("SQL 语义等价（裁判均分）: %.3f" % summary["avg_sql_query_equivalence"])
    print("SQL 可执行率: %.2f%%" % (100 * summary.get("sql_valid_rate", 0)))
    print("耗时: %.0f 秒" % elapsed)
    print("报告: %s" % md_path)
    print("明细: %s" % json_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
