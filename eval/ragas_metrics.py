#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按 RAGAS v0.4.3 官方 Text2SQL 示例
（examples/ragas_examples/text2sql/evals.py）的写法，用 RAGAS 指标原语定义 SQL 评估指标：

1) execution_accuracy        —— 执行准确性（discrete，datacompy 比对结果集，官方口径）
2) execution_accuracy_setwise—— 执行准确性（行序无关：先按全部列排序再比对，更贴近 EX 指标）
3) sql_query_equivalence     —— SQL 语义等价（numeric，LLM 裁判）
"""
import asyncio
import json
import os
import re

from collections import Counter

import datacompy
import pandas as pd

from ragas.metrics.discrete import discrete_metric
from ragas.metrics.numeric import numeric_metric
from ragas.metrics.result import MetricResult

from db import execute_sql

MAX_ROWS = 10000


def _compare_with_datacompy(expected: pd.DataFrame, predicted: pd.DataFrame, sort_rows: bool):
    """用 datacompy 比较两个 DataFrame；sort_rows=True 时先按所有列排序，使比较与行序无关。"""
    if sort_rows:
        expected = expected.sort_values(by=list(expected.columns), kind="stable").reset_index(drop=True)
        predicted = predicted.sort_values(by=list(predicted.columns), kind="stable").reset_index(drop=True)
    else:
        expected = expected.reset_index(drop=True)
        predicted = predicted.reset_index(drop=True)
    # 列名不同（模型常见行为：中文别名 vs 英文列名）时，按位置对齐列名，保证只比数值
    if len(expected.columns) == len(predicted.columns) and list(expected.columns) != list(predicted.columns):
        predicted = predicted.copy()
        predicted.columns = expected.columns
    return _make_comparison(expected, predicted)


def _make_comparison(expected: pd.DataFrame, predicted: pd.DataFrame):
    """
    兼容 datacompy 的两套 API：
      - datacompy < 1.0：datacompy.Compare(...)（RAGAS 官方示例用的写法）
      - datacompy >= 1.0：datacompy.PandasCompare(...)（同名方法 matches()/report()）
    """
    factory = getattr(datacompy, "Compare", None) or getattr(datacompy, "PandasCompare", None)
    if factory is None:
        raise RuntimeError("当前 datacompy 版本既没有 Compare 也没有 PandasCompare")
    return factory(
        expected, predicted, on_index=True, abs_tol=1e-6, rel_tol=1e-6,
        df1_name="expected", df2_name="predicted")


def _accuracy(expected_sql, predicted_success, predicted_result, sort_rows):
    expected_success, expected_result = execute_sql(expected_sql)
    if not expected_success:
        return MetricResult(value="incorrect", reason="参考 SQL 本身执行失败：%s" % expected_result)
    if not predicted_success:
        return MetricResult(value="incorrect", reason="生成 SQL 执行失败：%s" % predicted_result)
    if not isinstance(expected_result, pd.DataFrame) or not isinstance(predicted_result, pd.DataFrame):
        return MetricResult(value="incorrect", reason="结果不是 DataFrame")

    if expected_result.empty and predicted_result.empty:
        return MetricResult(value="correct", reason="两者都返回空结果")
    if expected_result.empty != predicted_result.empty:
        return MetricResult(value="incorrect",
                            reason="行数不一致：参考 %d 行，生成 %d 行"
                                   % (len(expected_result), len(predicted_result)))
    if len(expected_result) > MAX_ROWS or len(predicted_result) > MAX_ROWS:
        return MetricResult(value="incorrect", reason="结果集过大，跳过比对")

    try:
        comparison = _compare_with_datacompy(expected_result, predicted_result, sort_rows)
    except Exception as e:
        return MetricResult(value="incorrect", reason="datacompy 比对失败：%s" % e)

    if comparison.matches():
        return MetricResult(
            value="correct",
            reason="结果集完全一致（%d 行 × %d 列）" % (len(expected_result), len(expected_result.columns)))
    return MetricResult(value="incorrect", reason=_compact_reason(expected_result, predicted_result, comparison))


def _compact_reason(expected: pd.DataFrame, predicted: pd.DataFrame, comparison) -> str:
    """把 datacompy 的报告压成一句话，方便在报告里直接看出差在哪。"""
    parts = [
        "行数 参考=%d/生成=%d" % (len(expected), len(predicted)),
        "列数 参考=%d/生成=%d" % (len(expected.columns), len(predicted.columns)),
    ]
    if list(expected.columns) != list(predicted.columns):
        parts.append("列名 参考=%s/生成=%s"
                     % (list(expected.columns)[:4], list(predicted.columns)[:4]))
    try:
        mismatched = [c for c in comparison.intersect_columns()
                      if c in set(comparison.columns_with_mismatches())]
        if mismatched:
            parts.append("数值不一致列: " + ",".join(mismatched[:5]))
    except Exception:
        pass
    return "；".join(parts)


@discrete_metric(name="execution_accuracy", allowed_values=["correct", "incorrect"])
def execution_accuracy(expected_sql: str, predicted_success: bool, predicted_result):
    """执行准确性（官方口径：按行号对齐比较，受行序影响）。"""
    return _accuracy(expected_sql, predicted_success, predicted_result, sort_rows=False)


@discrete_metric(name="execution_accuracy_setwise", allowed_values=["correct", "incorrect"])
def execution_accuracy_setwise(expected_sql: str, predicted_success: bool, predicted_result):
    """执行准确性（行序无关：排序后比对，Text2SQL 评测更常用的 EX 口径）。"""
    return _accuracy(expected_sql, predicted_success, predicted_result, sort_rows=True)


@discrete_metric(name="core_values_match", allowed_values=["correct", "incorrect"])
def core_values_match(expected_sql: str, predicted_success: bool, predicted_result):
    """
    核心数值匹配（业务口径，宽容判据）：

    参考结果集每一行的取值，都必须能在生成结果的某一行里找到（**允许生成多带列**）。
    用来把「多输出了一列排名/明细/附加指标」和「数字算错了 / 少算了」区分开：
    前者业务上仍然答对了问题，不该按严格 EX 判错。

    注意：问题里明确要求"排名"时，模型多给一个排名列是合理的，因此这里用子集判据而不是全等。
    """
    expected_success, expected_result = execute_sql(expected_sql)
    if not expected_success:
        return MetricResult(value="incorrect", reason="参考 SQL 本身执行失败")
    if not predicted_success or not isinstance(predicted_result, pd.DataFrame):
        return MetricResult(value="incorrect", reason="生成 SQL 执行失败")

    expected_rows = _cell_multisets(expected_result)
    predicted_rows = _cell_multisets(predicted_result)
    if not expected_rows:
        return MetricResult(value="incorrect", reason="参考结果集为空，无法比对")
    if not predicted_rows:
        return MetricResult(value="incorrect", reason="生成结果集为空，无法比对")

    # 参考的每一行，都要能在生成的某一行里被"包含"（生成可以有额外列）
    unmatched = [row for row in expected_rows
                 if not any(not (row - candidate) for candidate in predicted_rows)]
    extra_cols = len(predicted_result.columns) - len(expected_result.columns)
    if not unmatched:
        note = ("生成多带 %d 列（不扣分）" % extra_cols) if extra_cols > 0 else "列数与参考一致"
        return MetricResult(
            value="correct",
            reason="参考结果的 %d 行取值全部命中；%s" % (len(expected_rows), note))
    return MetricResult(
        value="incorrect",
        reason="有 %d/%d 行的取值对不上（参考 %d 列 / 生成 %d 列），例如参考行 %s 在生成结果里找不到"
               % (len(unmatched), len(expected_rows),
                  len(expected_result.columns), len(predicted_result.columns),
                  sorted(unmatched[0].keys())[:4]))


def _cell_multisets(df: pd.DataFrame):
    """把结果集压成「每行取值计数的多重集」：列名、列顺序都不参与比较。"""
    rows = []
    for _, row in df.iterrows():
        counter = Counter()
        for value in row:
            counter[_cell(value)] += 1
        if counter:
            rows.append(counter)
    return rows


def _cell(value):
    """单元格归一：None→NULL，数值保留 2 位小数，其余去空白。"""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    from decimal import Decimal
    if isinstance(value, (int, float, Decimal)):
        try:
            return str(Decimal(str(value)).quantize(Decimal("0.01")).normalize())
        except Exception:
            return str(value)
    return str(value).strip()


# --------------------------------------------------------------------------
# LLM 裁判：SQL 语义等价
# --------------------------------------------------------------------------
JUDGE_PROMPT = """你是 MySQL 查询评审专家。请判断「生成 SQL」与「参考 SQL」在语义上是否等价：
在同一个数据库、同一份数据上执行，两者是否返回业务含义相同的结果集。

【用户问题】
{question}

【参考 SQL】
{expected_sql}

【生成 SQL】
{predicted_sql}

评分标准（宽松优先，不要因为表述差异扣分）：
- 1.0：语义等价。以下都算等价，给 1.0：
  · 列别名不同、列顺序不同、列名中英文不同；
  · 生成了**额外的列**，但包含问题问到的全部指标且取值正确（例如问"订单量"却额外给了"成交订单量"）；
  · 枚举字段被翻译成可读中文（如 1→"待付款"）；
  · COUNT(*) vs COUNT(DISTINCT 主键/唯一编号) 这类取值相同的等价写法；
- 0.5：方向正确但有**实质口径偏差**（如自加了未被要求的过滤条件导致数字变化、客单价分母口径不同、少了被要求的指标列）；
- 0.0：明显错误、答非所问，或引用了不存在的表/字段

只输出 JSON，不要任何解释文字：
{{"score": 0.0 或 0.5 或 1.0, "reason": "一句话中文理由"}}
"""


async def _judge(client, model, question, expected_sql, predicted_sql):
    response = await client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": JUDGE_PROMPT.format(
            question=question, expected_sql=expected_sql, predicted_sql=predicted_sql)}],
        temperature=0.0,
        response_format={"type": "json_object"},
    )
    text = response.choices[0].message.content or ""
    try:
        payload = json.loads(text)
        score = float(payload.get("score", 0.0))
        reason = str(payload.get("reason", ""))
    except (ValueError, TypeError):
        match = re.search(r'"score"\s*:\s*([0-9.]+)', text)
        score = float(match.group(1)) if match else 0.0
        reason = "解析失败，原始输出：%s" % text[:120]
    score = max(0.0, min(1.0, score))
    return MetricResult(value=score, reason=reason)


@numeric_metric(name="sql_query_equivalence", allowed_values=(0.0, 1.0))
async def sql_query_equivalence(question: str, expected_sql: str, predicted_sql: str):
    """SQL 语义等价（LLM 裁判，0~1 分）。"""
    from openai import AsyncOpenAI

    api_key = os.getenv("T2SQL_LLM_API_KEY") or os.getenv("DEEPSEEK_API_KEY")
    if not api_key:
        return MetricResult(value=0.0, reason="未配置 T2SQL_LLM_API_KEY / DEEPSEEK_API_KEY，跳过裁判")
    model = os.getenv("T2SQL_JUDGE_MODEL", "deepseek-flash")
    timeout = float(os.getenv("T2SQL_JUDGE_TIMEOUT", "180"))
    client = AsyncOpenAI(base_url=os.getenv("T2SQL_LLM_BASE_URL", "https://api.deepseek.com"),
                         api_key=api_key, timeout=timeout, max_retries=0)
    last_error = None
    for attempt in range(3):  # 限流/超时重试
        try:
            return await asyncio.wait_for(
                _judge(client, model, question, expected_sql, predicted_sql), timeout=timeout)
        except Exception as e:
            last_error = "%s: %s" % (type(e).__name__, e)
            await asyncio.sleep(2 * (attempt + 1))
    return MetricResult(value=0.0, reason="裁判调用失败（已重试 3 次）：%s" % last_error)
