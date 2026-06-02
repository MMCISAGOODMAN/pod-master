#!/usr/bin/env bash
# 清理 Pod Master 演示环境
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "🗑️  删除 podmaster-demo 命名空间及所有资源..."
kubectl delete namespace podmaster-demo --ignore-not-found --wait=true
echo "✅ 演示环境已清理"
