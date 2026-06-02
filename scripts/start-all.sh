#!/usr/bin/env bash
# 一键启动：检查 K8s → 部署演示环境 → 启动应用
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=========================================="
echo " Pod Master 一键启动"
echo "=========================================="

if ! kubectl cluster-info &>/dev/null; then
  echo ""
  echo "❌ K8s 集群不可达，请先配置 kubectl 并确保集群可用："
  echo "   kubectl cluster-info"
  echo "   详见 docs/KUBERNETES-CONNECTION.md"
  exit 1
fi

echo "✅ K8s 集群: $(kubectl config current-context)"

"$SCRIPT_DIR/setup-k8s-demo.sh"

echo ""
echo "🚀 启动 Pod Master 应用..."
echo "   访问: http://localhost:8083"
echo "   按 Ctrl+C 停止"
echo ""
cd "$PROJECT_DIR"
mvn spring-boot:run
