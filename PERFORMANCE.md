# 性能测试报告（10 万订单）

数据规模从 300 笔订单放大到 **10 万笔订单 / 25 万条明细 / 5000 用户**，
测三层：SQL 查询、接口层、Text2SQL 端到端，并定位瓶颈给出优化路径。

## 测试方法

```bash
# 1) 生成大规模数据（默认 10 万订单，口径与 data.sql 一致，8 秒左右跑完）
eval/.venv/bin/python scripts/gen-large-data.py --orders 100000 --users 5000 --days 730

# 2) 跑性能测试（SQL 层 + 接口层，输出报告到 eval/reports/）
eval/.venv/bin/python scripts/perf-test.py --label "10万订单" --repeat 3 --api http://127.0.0.1:8080

# 3) 预聚合优化演示（同一份数据三种写法的耗时对比）
bash scripts/perf-summary-demo.sh
```

环境：MySQL 8.0（Docker）+ Spring Boot 3.5 + MyBatis-Plus，本机实测。
每条 SQL 跑 3~6 次取中位数；`perf-test.py` 计时不包含进程启动开销。

## 一、数据规模

| 表 | 300 订单 | 10 万订单 |
| --- | --- | --- |
| `t_order` | 300 行 / 0.1 MB | **100,000 行** / 21 MB |
| `t_order_item` | 751 行 / 0.1 MB | **250,014 行** / 40 MB |
| `t_user` | 40 行 | **5,000 行** |
| `t_product` / `t_province` | 40 / 14 | 40 / 14 |

生成器保证口径自洽：订单金额 = 明细金额之和、件数 = 明细数量之和、
`t_user.total_amount` 与 `t_product.sales_count` 按成交口径（`order_status IN (2,3,4)`）回填。

## 二、SQL 层结果（优化后）

20 条用例集 SQL **全部走索引、没有一条超过 300ms**：

| 查询类型 | 代表用例 | 行数 | p50 | 走的索引 |
| --- | --- | --- | --- | --- |
| 单表计数/枚举分组 | q01~q06,q10,q11,q13 | 1~39 | **0.2 ~ 1.3 ms** | `uk_*` / `idx_member_level` / `idx_brand` |
| 时间窗口聚合 | q09(近30天渠道)、q15(指定月份) | 4 | **5 ~ 15 ms** | `idx_create_time` |
| 订单表聚合 | 全表按渠道聚合、按月趋势 | 4~25 | **27 ms** | 全表扫描（无过滤条件，无法避免） |
| 两表关联聚合 | q07(省份Top5)、q16(会员客单价) | 5~10 | **10 ~ 59 ms** | `idx_status_province_amount` |
| 明细聚合 | 类目销售额/销量 | 8 | **63 ms** | `idx_status_category`（覆盖索引） |
| 明细 TopN | q12(销量最高商品) | 5 | **115 ms** | `idx_status_product` |
| 排序取 Top | q18(消费最高用户) | 10 | **1.5 ms** | `PRIMARY`（5000 行排序） |

## 三、瓶颈定位：为什么明细聚合是 100ms 级

1. **`order_status IN (2,3,4)` 选择性极差**：成交订单占 85%，用它过滤 10 万订单等于没过滤；
2. **每条明细都要回表查订单状态**：`t_order_item ⋈ t_order` 要处理约 21 万行明细，
   再回表 8.5 万次订单；
3. **加普通索引没用甚至更慢**：先加 `(order_status, province_id, pay_amount)` 与
   `(order_id, product_id, quantity, item_amount)` 两个覆盖索引后，优化器反而改成从
   `t_product` 出发走 `idx_product_id`，类目毛利率查询从 310ms 涨到 **414ms**；
4. **物理下限**：对 21 万行做分组聚合，无论怎么建索引都要把行读一遍——
   这就是"扫明细"方案 60~160ms 的由来。

## 四、三步优化与实测（同一个问题：各商品类目销售额）

| 方案 | 做法 | p50 | 相对原始 |
| --- | --- | --- | --- |
| ① 原始写法 | 明细 ⋈ 订单 ⋈ 商品，用 `o.order_status` 过滤 | **161.7 ms** | — |
| ② 明细冗余状态列 | `t_order_item` 增加 `order_status` 快照列 + 覆盖索引 `(order_status, category_name, quantity, item_amount)`，**不再关联订单表** | **63.0 ms** | **↓ 2.6×** |
| ③ 预聚合汇总表 | 新建 `t_sales_summary_daily`（日 × 类目，5840 行），夜间刷新 | **2.3 ms** | **↓ 70×** |

按月销售额趋势同样：扫明细 67.8 ms → 查汇总表 **2.0 ms**。

具体做法：

* **② 明细冗余状态列**：和已有的 `product_name`/`category_name` 快照列一个思路
  （电商明细表本来就冗余订单属性），加了 3 个覆盖索引：
  `idx_status_category` / `idx_status_product` / `idx_order_cover`，
  执行计划从 `Using where; Using filesort`（回表）变成 **`Using index`（索引覆盖，不回表、不关联）**；
* **③ 预聚合**：`src/main/resources/db/summary.sql` 建表 + `REPLACE INTO ... SELECT` 全量刷新
  （10 万订单刷新耗时 <1 秒），查询退化成扫 5840 行。
  注意它是衍生数据，默认**不在** Text2SQL 白名单里；要用它问答得先加进
  `t2sql.sql.allowed-tables` 并写好注释。

## 五、接口层

| 指标 | 300 订单 | 10 万订单（优化后） | 说明 |
| --- | --- | --- | --- |
| `POST /api/sql/execute`（类目聚合） | 3 ms | **186 ms** | 其中 SQL 执行 161ms + MyBatis/JDBC 往返约 25ms |
| `GET /api/schema` | 5.4 ms | **28.6 ms** | 表结构接口对每张表做 `COUNT(*)`，25 万行扫描约 13ms |
| `POST /api/text2sql/generate` | 10.5 s | **8.0 ~ 11.6 s** | **几乎全部是大模型耗时**（表结构检索 4~7s + SQL 编写 3~4s），数据库部分可忽略 |

结论：**Text2SQL 的端到端延迟由大模型主导，数据量从 300 涨到 10 万对端到端几乎没有影响**；
受影响的是纯 SQL 接口（毫秒 → 百毫秒）和表结构接口（因为要统计行数）。

## 六、结论与建议

1. 10 万订单 / 25 万明细下，**绝大多数分析查询在 100ms 以内**，最慢的明细聚合 115~160ms，
   对交互式问答完全可以接受；
2. 想再快只有两条路：
   - **冗余**（把订单属性冗余进明细表）→ 2.6× 提升，代价是写入时多维护一列；
   - **预聚合**（日/类目粒度汇总表）→ 70× 提升，代价是数据有延迟、需要定时刷新与口径管理；
3. **别盲目加索引**：本例加了普通覆盖索引后优化器换了个更差的计划（310→414ms），
   一定要用 `EXPLAIN` 验证执行计划，而不是想当然；
4. **`t2sql.sql.max-rows`（默认 200）是最后一道闸**：模型生成的 SQL 都会自动补 `LIMIT`，
   即使写出全表扫描也不会把结果集拉爆；
5. 表结构接口的 `COUNT(*)` 会随数据量线性变慢，数据再大（千万级）建议改成
   `information_schema.TABLES.TABLE_ROWS` 估算值。

## 七、复现与回滚

```bash
# 回到小数据集（300 订单，评估基线用的那份）
mysql -h127.0.0.1 -P3306 -uroot -p < src/main/resources/db/schema.sql
mysql -h127.0.0.1 -P3306 -uroot -p < src/main/resources/db/data.sql

# 再来一次 10 万
eval/.venv/bin/python scripts/gen-large-data.py --orders 100000 --users 5000
```

历史报告（每次 perf-test 都会落盘）：

* 小数据集（300 订单）：[`eval/baseline/perf-1-小数据集300单.md`](eval/baseline/perf-1-小数据集300单.md)
* 10 万订单优化前：[`eval/baseline/perf-2-10万单-优化前.md`](eval/baseline/perf-2-10万单-优化前.md)
* 10 万订单优化后：[`eval/baseline/perf-3-10万单-优化后.md`](eval/baseline/perf-3-10万单-优化后.md)
