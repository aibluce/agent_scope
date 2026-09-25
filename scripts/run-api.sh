#!/usr/bin/env bash
# ============================================================================
# 启动 Text2SQL Demo（Spring Boot，默认 http://127.0.0.1:8080）
#
#   bash scripts/run-api.sh
#   端口/模型等可用环境变量或启动参数覆盖，例如：
#     T2SQL_LLM_API_KEY=sk-xxx bash scripts/run-api.sh
#     bash scripts/run-api.sh --server.port=9090
# ============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# 自动加载本地环境变量（.env 已在 .gitignore 中，用来放 API Key / 数据库密码）
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env"
  set +a
fi

if [[ -x ./gradlew ]]; then
  exec ./gradlew --console=plain bootRun "$@"
fi
exec gradle --console=plain bootRun "$@"
