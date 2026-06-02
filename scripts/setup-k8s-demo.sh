#!/usr/bin/env bash
# Pod Master 演示集群一键部署（Docker Desktop Kubernetes）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
K8S_DIR="$PROJECT_DIR/k8s/demo"

echo "=========================================="
echo " Pod Master 演示环境部署"
echo "=========================================="

# 检查 kubectl
if ! command -v kubectl &>/dev/null; then
  echo "❌ 未找到 kubectl，请先安装 kubectl"
  exit 1
fi

# 检查集群连通性
if ! kubectl cluster-info &>/dev/null; then
  echo "❌ K8s 集群不可达。请启动 Docker Desktop 并开启 Kubernetes："
  echo "   Docker Desktop → Settings → Kubernetes → Enable Kubernetes"
  exit 1
fi

echo "✅ 集群已连接: $(kubectl config current-context)"
echo ""

# 部署演示资源
echo "📦 部署演示 Workload..."
kubectl apply -f "$K8S_DIR/"

echo ""
echo "⏳ 等待 Pod 就绪（最多 120 秒）..."
kubectl wait --for=condition=Ready pod \
  -l app=api-gateway \
  -n podmaster-demo \
  --timeout=120s 2>/dev/null || true

kubectl wait --for=condition=Ready pod \
  -l app=order-service \
  -n podmaster-demo \
  --timeout=120s 2>/dev/null || true

echo ""
echo "📋 当前 Pod 状态："
kubectl get pods -n podmaster-demo -o wide

echo ""
echo "=========================================="
echo " ✅ 演示环境部署完成！"
echo "=========================================="
echo ""
echo " 启动应用："
echo "   cd $PROJECT_DIR"
echo "   mvn spring-boot:run"
echo ""
echo " 浏览器访问："
echo "   http://localhost:8083"
echo ""
echo " 演示内容："
echo "   • 命名空间 podmaster-demo（6 个业务 Pod）"
echo "   • payment-service — CrashLoop + 错误日志（AI 诊断/语音告警）"
echo "   • redis-cache — Pending 状态（资源不足）"
echo "   • 业务流量拓扑 — gateway → order/payment → mysql"
echo ""
echo " 清理演示环境："
echo "   $SCRIPT_DIR/teardown-k8s-demo.sh"
echo ""
