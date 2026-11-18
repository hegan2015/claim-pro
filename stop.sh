#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
PORT="${CLAIM_PORT:-8097}"
if [ -f run/claim.pid ]; then
  PID=$(cat run/claim.pid)
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "claim-pro 已停止（PID ${PID}）"
  fi
  rm -f run/claim.pid
else
  PID=$(lsof -ti tcp:"${PORT}" 2>/dev/null || true)
  if [ -n "$PID" ]; then
    kill "$PID"
    echo "claim-pro 已停止（端口 ${PORT} PID ${PID}）"
  else
    echo "claim-pro 未在运行"
  fi
fi