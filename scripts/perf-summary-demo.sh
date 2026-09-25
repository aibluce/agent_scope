#!/usr/bin/env bash
# ============================================================================
# 预聚合性能演示：同一份数据，三种写法的耗时对比
#
#   bash scripts/perf-summary-demo.sh          # 建表 + 刷新 + 对比（跑完保留汇总表）
#   bash scripts/perf-summary-demo.sh --drop   # 结束后删掉汇总表，恢复默认 schema
# ============================================================================
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MYSQL="${MYSQL_BIN:-/opt/anaconda3/bin/mysql}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"; MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"; MYSQL_PASSWORD="${MYSQL_PASSWORD:-Atguigu.123}"

mysql_args=(-h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD"
            --protocol=TCP --default-character-set=utf8mb4)

run() { "$MYSQL" "${mysql_args[@]}" -e "USE ecommerce; $1" 2>&1 | grep -v "Using a password" || true; }
run_file() { "$MYSQL" "${mysql_args[@]}" < "$1" 2>&1 | grep -v "Using a password" || true; }

timeit() {  # timeit <说明> <sql> <次数>
  local label="$1" sql="$2" n="${3:-3}" total=0
  for _ in $(seq 1 "$n"); do
    local ms
    ms=$( { /usr/bin/time -p "$MYSQL" "${mysql_args[@]}" -N -B -e "USE ecommerce; $sql" >/dev/null; } 2>&1 \
          | awk '/^real/{printf "%d", $2*1000}')
    total=$((total + ms))
  done
  printf "  %-42s %6d ms（%d 次平均）\n" "$label" $((total / n)) "$n"
}

echo "== 建表 + 全量刷新（这一步是"离线"成本，生产上放定时任务）=="
start=$(date +%s)
run_file "$ROOT_DIR/src/main/resources/db/summary.sql"
echo "  刷新耗时: $(( $(date +%s) - start ))s"
run "SELECT COUNT(*) AS 汇总行数, MIN(stat_date) AS 起始, MAX(stat_date) AS 结束 FROM t_sales_summary_daily;"

echo
echo "== 同一问题「各商品类目的销售额」的三种写法 =="
timeit "① 三表关联（明细 ⋈ 订单 ⋈ 商品）" \
  "SELECT p.category_name, ROUND(SUM(i.item_amount),2) amt FROM t_order_item i JOIN t_order o ON o.id=i.order_id JOIN t_product p ON p.id=i.product_id WHERE o.order_status IN (2,3,4) GROUP BY p.category_name ORDER BY amt DESC;"
timeit "② 明细直查（用冗余的 order_status + 覆盖索引）" \
  "SELECT i.category_name, ROUND(SUM(i.item_amount),2) amt FROM t_order_item i WHERE i.order_status IN (2,3,4) GROUP BY i.category_name ORDER BY amt DESC;"
timeit "③ 查预聚合汇总表" \
  "SELECT category_name, ROUND(SUM(sales_amount),2) amt FROM t_sales_summary_daily GROUP BY category_name ORDER BY amt DESC;"

echo
echo "== 同一问题「按月销售额趋势」的两种写法 =="
timeit "① 扫明细按月聚合（约 25 万行）" \
  "SELECT DATE_FORMAT(create_time,'%Y-%m') m, ROUND(SUM(item_amount),2) amt FROM t_order_item WHERE order_status IN (2,3,4) GROUP BY m ORDER BY m;"
timeit "② 查预聚合汇总表（730 天 × 8 类目）" \
  "SELECT DATE_FORMAT(stat_date,'%Y-%m') m, ROUND(SUM(sales_amount),2) amt FROM t_sales_summary_daily GROUP BY m ORDER BY m;"

if [[ "${1:-}" == "--drop" ]]; then
  echo
  echo "== 删除汇总表（恢复默认 schema）=="
  run "DROP TABLE IF EXISTS t_sales_summary_daily;"
  echo "  已删除"
fi
