# Text2SQL 生成质量评估工具（RAGAS）

评估本项目 Text2SQL 接口**生成的 SQL 质量**：拿一份带参考 SQL 的用例集，逐条调用被测接口生成 SQL，
再用 RAGAS 指标 + 执行准确性打分，输出可读报告，用于后续迭代（改提示词、换模型、加表结构注释）的效果回归。

实现方式对齐 RAGAS v0.4.3 官方 Text2SQL 示例
（[Evaluate a Text-to-SQL Agent](https://docs.ragas.io/en/v0.4.3/howtos/applications/text2sql/)，
源码 [`examples/ragas_examples/text2sql/evals.py`](https://github.com/vibrantlabsai/ragas/tree/main/examples/ragas_examples/text2sql)）：
用 `@discrete_metric` / `@numeric_metric` 定义指标，用 `Dataset` + `@experiment` 跑评估。

## 一、快速开始

```bash
# 1) 建虚拟环境（Python >= 3.9，实测 3.12；系统自带 3.8 不行）
python3.12 -m venv eval/.venv

# 2) 装依赖
#    macOS 上 python.org 版 Python 缺 CA 证书，需要指定 SSL_CERT_FILE，否则 pip 报 CERTIFICATE_VERIFY_FAILED
SSL_CERT_FILE=/etc/ssl/cert.pem eval/.venv/bin/pip install -r eval/requirements.txt

# 3) 启动被测服务（MVC 版即可）
bash scripts/run-api.sh

# 4) 跑评估（默认 20 条用例）
eval/.venv/bin/python eval/run_eval.py

# 加 LLM 裁判（语义等价打分，需要模型 Key）
T2SQL_LLM_API_KEY=sk-xxx eval/.venv/bin/python eval/run_eval.py --name baseline_20
```

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--api` | 被测服务地址，默认 `http://127.0.0.1:8080` |
| `--limit N` / `--only q07,q08` | 只跑部分用例 |
| `--no-judge` | 关闭 LLM 裁判，只跑执行准确性（不花 token） |
| `--gen-file file.jsonl` | 离线模式：评估已生成好的 SQL，不调接口 |
| `--name` | RAGAS experiment 名称（结果落盘到 `eval/experiments/<name>/`） |
| `--out-dir` | 报告输出目录，默认 `eval/reports/` |

## 二、指标

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `execution_accuracy` | RAGAS discrete | **执行准确性（官方口径）**：把生成 SQL 与参考 SQL 都真实执行，用 **datacompy** 比对结果集（按行号对齐，受行序影响） |
| `execution_accuracy_setwise` | RAGAS discrete | **执行准确性（行序无关）**：先按全部列排序再比对；列名/列数不同即判错（严格口径，用来看"和参考 SQL 是否逐列一致"） |
| `core_values_match` | RAGAS discrete | **核心数值匹配（业务口径，主指标）**：参考结果集每行的取值都要能在生成结果里找到，**允许生成多带列**（子集判据），列名/列序/枚举翻译都不扣分 |
| `sql_query_equivalence` | RAGAS numeric (0~1) | **SQL 语义等价**：LLM 裁判（DeepSeek）判断两条 SQL 在业务语义上是否等价，0.5 表示方向对但有实质偏差 |
| `sql_valid_rate` | 统计 | 生成 SQL 的**可执行率**（能否在 MySQL 跑通） |
| `generate_success_rate` / `guard_rejected` | 统计 | 接口生成成功率 / 被安全网关拦截的条数 |
| `avg_generate_ms` | 统计 | 平均生成耗时（含表结构检索阶段） |

> 列名不同不算错：比对前会把生成 SQL 的列按位置对齐到参考列（模型爱起中文别名，这不该扣分）。
> 数值容差 `1e-6`，空结果/行数不一致直接判错。

## 三、用例集（`dataset.jsonl`）

20 条电商场景问题，分三档难度，每条都带**已验证可执行**的参考 SQL：

| 难度 | 条数 | 覆盖点 |
| --- | --- | --- |
| easy | 6 | 单表计数、DISTINCT、带过滤计数 |
| medium | 8 | 两/三表关联、TopN、枚举分组、占比、时间窗口（最近 30 天） |
| hard | 6 | 指定月份区间、人均客单价（口径歧义）、毛利率派生指标、月趋势、复购用户（HAVING 子查询） |

参考 SQL 的统一口径：**成交 = `order_status IN (2,3,4)`**，金额用 `pay_amount` / `item_amount`。
关于口径歧义（例如"客单价"是"人均消费"还是"单均价"），指标里用 LLM 裁判兜底解释，理由会写进报告。

新增用例只要往 `dataset.jsonl` 追加一行：

```json
{"id":"q21","level":"medium","question":"各渠道的平均客单价是多少？","reference_sql":"SELECT ...","notes":"口径说明"}
```

## 四、输出

```
eval/reports/eval-<时间戳>.md     可直接阅读：总体指标 / 分难度 / 明细表 / 失败用例逐条对比
eval/reports/eval-<时间戳>.json   完整明细（含生成 SQL、参考 SQL、判定理由、裁判意见）
eval/experiments/<name>/          RAGAS experiment 自动落盘的结果
```

报告里的失败用例会同时列出**生成 SQL**、**参考 SQL**、**判定理由**和**裁判意见**，
方便直接定位是"表/字段找错""口径不对"还是"多输出列"这类问题。

## 五、基线结果与迭代记录

同一套 20 条用例、同一模型（deepseek-flash）的四轮对比：

| 轮次 | 本轮变更 | EX（严格） | 核心数值匹配 | 语义等价 | 过度输出 | 平均耗时 |
| --- | --- | --- | --- | --- | --- | --- |
| ① 原始基线 | — | 40% | — | 0.600 | — | 10385 ms |
| ② 修评估 | 题面口径写清楚 + 新增核心数值指标 + 放宽裁判 | 55% | 60% | 0.900 | 6 条 | 10340 ms |
| ③ 改提示词 | 业务口径字典 + 禁止过度输出 + few-shot | 90% | 90% | 0.900 | **0 条** | **7895 ms** |
| ④ 再修一轮 | 口径字典补"销量属成交口径"；指标改子集判据 | 90% | **100%** | 0.900 | 2 条* | 8149 ms |

\* ④ 的两条是问题本身要求"排名"、模型多给了排名列，属合理输出（裁判 1.0）。

完整过程（每轮的诊断、改了什么、为什么有效）见 [`baseline/EVOLUTION.md`](baseline/EVOLUTION.md)，
最新完整报告见 [`baseline/BASELINE.md`](baseline/BASELINE.md)。

## 六、已知限制与后续

* LLM 裁判与执行准确性偶有分歧：语义等价但排序不同 → EX 判错、裁判给 1.0，报告里两个指标并排看即可；
* 参考 SQL 只覆盖"一种正确写法"，遇到合法的等价写法（窗口函数 vs 子查询）依赖裁判解释；
* 想接 CI：把 `--no-judge --limit 10` 作为快速回归门禁，`--name nightly` 跑全量；
* 想换裁判模型：设 `T2SQL_JUDGE_MODEL`（默认 `deepseek-flash`）、`T2SQL_LLM_BASE_URL`，任何 OpenAI 协议服务都能用。

## 七、踩坑记录

1. **ragas 0.4.3 与 langchain-community ≥ 0.4 不兼容**：新版移除了 `langchain_community.chat_models.vertexai`，
   而 ragas 顶层会 import 它 → `import ragas` 直接 `ModuleNotFoundError`。
   解决：把 langchain 栈固定在 0.3.x（见 `requirements.txt`）。
2. **datacompy 1.x 改了 API**：`datacompy.Compare` 变成 `datacompy.PandasCompare`（方法签名一致）。
   `ragas_metrics.py` 里做了兼容，两套 API 都能跑。
3. **macOS 上 pip 证书**：python.org 版 Python 需要 `SSL_CERT_FILE=/etc/ssl/cert.pem` 才能装包。
4. **系统 python3 是 3.8**，ragas 要求 ≥ 3.9，必须单独建 3.12 的 venv。
