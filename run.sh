#!/usr/bin/env bash
# claim-pro 启动脚本。默认 mysql profile（claim-shared-mysql 数据库 + 本地编排，库表见 deploy/mysql/）；
# 零依赖开发：CLAIM_PROFILE=local（H2 文件库）；
# 全栈生产：CLAIM_PROFILE=default 并确保 MySQL/Temporal/RocketMQ/MinIO 已由 ops-pro 启动。
set -euo pipefail
cd "$(dirname "$0")"

PROFILE="${CLAIM_PROFILE:-mysql}"
PORT="${CLAIM_PORT:-8097}"
JAR="claim-gateway/target/claim-pro.jar"
mkdir -p logs run data

if curl -sf "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1; then
  echo "claim-pro 已在端口 ${PORT} 运行"
  exit 0
fi

if [ ! -f "$JAR" ]; then
  echo "首次启动：编译打包（./mvnw package）..."
  ./mvnw -q package -DskipTests
fi

echo "启动 claim-pro（profile=${PROFILE}, port=${PORT}）..."
nohup java -jar "$JAR" --spring.profiles.active="${PROFILE}" --server.port="${PORT}" \
  > logs/claim-pro.out 2>&1 &
echo $! > run/claim.pid

for i in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1; then
    echo "claim-pro 就绪：http://127.0.0.1:${PORT}（PID $(cat run/claim.pid)）"
    exit 0
  fi
  sleep 1
done
echo "启动超时，请查看 logs/claim-pro.out" >&2
exit 1