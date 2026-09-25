#!/usr/bin/env bash
# ============================================================================
# 初始化 Text2SQL Demo 所需的 MySQL 库表与示例数据
#
#   MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=root MYSQL_PASSWORD=xxx \
#       bash scripts/init-db.sh
# ============================================================================
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 自动加载本地环境变量（.env 已在 .gitignore 中，用来放数据库口令 / API Key）
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a; source "$ROOT_DIR/.env"; set +a
fi

SCHEMA_SQL="$ROOT_DIR/src/main/resources/db/schema.sql"
DATA_SQL="$ROOT_DIR/src/main/resources/db/data.sql"

run_sql_file() {
  "$MYSQL_BIN" -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
      --default-character-set=utf8mb4 --protocol=TCP < "$1" 2>&1 | grep -v "Using a password" || true
}

echo "==> 目标 MySQL: $MYSQL_USER@$MYSQL_HOST:$MYSQL_PORT"
echo "==> 1/3 创建库表 (schema.sql)"
run_sql_file "$SCHEMA_SQL"

echo "==> 2/3 导入示例数据 (data.sql)"
run_sql_file "$DATA_SQL"

echo "==> 3/3 校验"
"$MYSQL_BIN" -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
    --default-character-set=utf8mb4 --protocol=TCP -e "
USE ecommerce;
SELECT 't_province' AS 表名, COUNT(*) AS 行数 FROM t_province
UNION ALL SELECT 't_user', COUNT(*) FROM t_user
UNION ALL SELECT 't_order', COUNT(*) FROM t_order;" 2>&1 | grep -v "Using a password"

echo "==> 完成。"
