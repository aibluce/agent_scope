#!/usr/bin/env bash
# ============================================================================
# 跑一次 Text2SQL 生成质量评估（RAGAS）
#
#   bash scripts/eval-sql.sh                    # 全量 20 条 + LLM 裁判
#   bash scripts/eval-sql.sh --limit 5          # 快速冒烟
#   bash scripts/eval-sql.sh --no-judge         # 只跑执行准确性，不花 token
#   bash scripts/eval-sql.sh --only q07,q08     # 指定用例
# ============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 自动加载本地环境变量（.env 已在 .gitignore 中，用来放数据库口令 / API Key）
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a; source "$ROOT_DIR/.env"; set +a
fi

cd "$ROOT_DIR"

VENV_PY="$ROOT_DIR/eval/.venv/bin/python"
if [[ ! -x "$VENV_PY" ]]; then
  cat <<'TIP'
还没建评估用的虚拟环境，先执行：
  python3.12 -m venv eval/.venv
  SSL_CERT_FILE=/etc/ssl/cert.pem eval/.venv/bin/pip install -r eval/requirements.txt
TIP
  exit 1
fi

# 被测服务是否在跑
API="${T2SQL_API:-http://127.0.0.1:8080}"
if ! curl -s -m 5 -o /dev/null "$API/health"; then
  echo "被测服务 $API 没起来，先执行： bash scripts/run-api.sh"
  exit 1
fi

# LLM 裁判的 Key：没显式给就从 application.yml 里取
if [[ -z "${T2SQL_LLM_API_KEY:-}" ]]; then
  KEY="$(python3 - <<'PY' 2>/dev/null || true
import re
for line in open('src/main/resources/application.yml', encoding='utf-8'):
    m = re.match(r'\s*api-key:\s*(\S+)', line)
    if m:
        print(m.group(1)); break
PY
)"
  export T2SQL_LLM_API_KEY="$KEY"
fi

exec "$VENV_PY" eval/run_eval.py --api "$API" "$@"
