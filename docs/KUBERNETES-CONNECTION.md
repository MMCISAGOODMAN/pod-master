# Pod Master — Kubernetes 连接完整指南

本文说明如何让 Pod Master **连接并管理 Pod**，包括**本地集群**与**远程服务器上的集群**两种场景。

---

## 1. 连接原理

Pod Master **不会直连 Pod 的 IP**，而是通过 [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client) 访问 **Kubernetes API Server**，由 API Server 代理所有操作（列表、日志、exec 终端、重启、删除等）。

```
浏览器 → Pod Master (Spring Boot :8083)
              │
              │ 读取 kubeconfig 或集群内 ServiceAccount
              ▼
         K8s API Server  ←── 你的集群（本地 / 远程）
              │
              ▼
           Pod / Service / Event ...
```

因此，「连接 Pod」的本质是：**让 Pod Master 进程能成功访问目标集群的 API Server**，且当前身份具备足够的 RBAC 权限。

---

## 2. 连接方式一览

| 模式 | 配置项 | 适用场景 |
|------|--------|----------|
| **local**（默认） | `k8s.connection-mode: local`<br>`k8s.kubeconfig-path: ~/.kube/config` | 开发机、运维机；本地或远程集群均可，靠 kubeconfig 指定 |
| **in-cluster** | `k8s.connection-mode: in-cluster` | Pod Master 自身部署在 K8s 集群内 |

连接是否成功，以接口为准：

```bash
curl http://localhost:8083/api/health
# {"code":200,"data":{"status":"UP","k8s":"connected, k8s version=v1.xx, ..."}}
```

- `status: UP` — 已连接，页面显示真实 Pod 数据
- `status: DOWN` — 未连接，前端自动进入 **Mock 模拟模式**（仅 UI 预览）

---

## 3. 连接本地 Pod

「本地 Pod」指：Kubernetes 集群运行在你本机（或本机虚拟机 / Docker 内），Pod Master 也在同一台机器上运行。

### 3.1 前置条件

- JDK 17+、Maven 3.8+
- `kubectl` 已安装
- 当前 kubeconfig 能正常访问集群：

```bash
kubectl cluster-info
kubectl get pods -A
```

### 3.2 场景 A：Docker Desktop Kubernetes（Mac / Windows）

1. 打开 **Docker Desktop → Settings → Kubernetes → Enable Kubernetes**，等待就绪。
2. 确认上下文：

```bash
kubectl config current-context
# 通常为 docker-desktop
```

3. 配置 `src/main/resources/application.yml`（默认即可）：

```yaml
k8s:
  connection-mode: local
  kubeconfig-path: ${user.home}/.kube/config
  default-namespace: podmaster-demo
```

4. 部署 demo 并启动：

```bash
./scripts/setup-k8s-demo.sh
mvn spring-boot:run
```

5. 浏览器访问 `http://localhost:8083`，侧边栏应显示 **「集群已连接」**。

### 3.3 场景 B：kind / 其他本地集群

```bash
kubectl config use-context <your-context>
./scripts/setup-k8s-demo.sh
mvn spring-boot:run
```

### 3.4 场景 C：minikube

```bash
minikube start
kubectl config use-context minikube
./scripts/setup-k8s-demo.sh
mvn spring-boot:run
```

### 3.5 场景 D：自建集群（kubeadm 等）

集群初始化并配置好 `~/.kube/config` 后，与 Docker Desktop 用法相同：保证 `kubectl get nodes` 正常，再启动 Pod Master。

```bash
./scripts/setup-k8s-demo.sh
mvn spring-boot:run
```

### 3.6 切换本地多个集群

kubeconfig 可包含多个 `context`，Pod Master 使用 **当前 context** 指向的集群：

```bash
# 查看所有上下文
kubectl config get-contexts

# 切换到 kind
kubectl config use-context kind-pod-master

# 切换到 Docker Desktop
kubectl config use-context docker-desktop
```

切换后**重启 Pod Master**（客户端在启动时读取 kubeconfig）。

指定独立 kubeconfig 文件（不修改默认 `~/.kube/config`）：

```yaml
k8s:
  kubeconfig-path: /path/to/my-kubeconfig
```

或启动参数：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--k8s.kubeconfig-path=/path/to/my-kubeconfig"
```

---

## 4. 连接远程服务器上的 Pod

远程场景指：Pod 运行在**另一台服务器**（或云厂商托管 K8s）上。常见有三种做法。

### 4.1 方案对比

| 方案 | Pod Master 运行位置 | 优点 | 缺点 |
|------|---------------------|------|------|
| **A. 远程部署** | 与集群同网段的服务器 / 集群内 | 网络简单、延迟低 | 需登录远程访问 UI |
| **B. 本地 + 远程 kubeconfig** | 你的笔记本 | 本地开发体验好 | API 须可达或需隧道 |
| **C. SSH 隧道** | 你的笔记本 | API 不对外暴露也安全 | 需保持 SSH 连接 |

---

### 4.2 方案 A：在远程服务器上运行 Pod Master（推荐生产/内网）

**适用**：集群在远程 Linux 服务器，你通过 SSH 登录该机器操作。

#### 步骤

1. **SSH 登录远程服务器**

```bash
ssh user@your-remote-server
```

2. **确认远程 kubectl 可用**

```bash
kubectl cluster-info
kubectl get nodes
kubectl get pods -A
```

3. **上传或克隆项目**

```bash
git clone <your-repo> pod-master
cd pod-master
```

4. **配置 application.yml**

```yaml
server:
  port: 8083

k8s:
  connection-mode: local
  kubeconfig-path: /home/user/.kube/config   # 远程机上的 kubeconfig
  default-namespace: podmaster-demo          # 或你的生产 namespace
```

5. **启动应用**

```bash
mvn spring-boot:run
# 或打包后后台运行
mvn package -DskipTests
nohup java -jar target/pod-master-1.0.0.jar &
```

6. **访问 UI**

- 若远程有公网 IP：`http://<远程IP>:8083`
- 若仅内网：`http://<内网IP>:8083`
- 若只能 SSH：见下方 [4.5 本地浏览器访问远程 Pod Master](#45-本地浏览器访问远程-pod-master)

---

### 4.3 方案 B：本地运行 Pod Master，使用远程 kubeconfig

**适用**：集群 API 地址从你的笔记本**网络可达**（云厂商公网 Endpoint、VPN 内网等）。

#### 步骤 1：获取远程 kubeconfig

在远程 master 节点或已有 kubectl 的机器上：

```bash
# 常见路径
cat ~/.kube/config
# 或 kubeadm 集群
sudo cat /etc/kubernetes/admin.conf
```

复制到本地，例如 `~/.kube/remote-cluster-config`。

#### 步骤 2：检查 API Server 地址

打开 kubeconfig，找到对应 `cluster` 的 `server` 字段：

```yaml
clusters:
- cluster:
    server: https://192.168.1.100:6443   # 或云厂商 HTTPS 地址
  name: my-remote-cluster
```

确保从你的笔记本可以访问该地址：

```bash
curl -k https://192.168.1.100:6443/version
# 或
kubectl --kubeconfig ~/.kube/remote-cluster-config cluster-info
```

#### 步骤 3：指定 context 并配置 Pod Master

```bash
# 查看远程 kubeconfig 中的 context 名称
kubectl --kubeconfig ~/.kube/remote-cluster-config config get-contexts

# 测试
kubectl --kubeconfig ~/.kube/remote-cluster-config get pods -A
```

`application.yml`：

```yaml
k8s:
  connection-mode: local
  kubeconfig-path: ${user.home}/.kube/remote-cluster-config
  default-namespace: production    # 改为远程实际 namespace
```

若远程 kubeconfig 含多个 context，可合并到 `~/.kube/config` 后 `kubectl config use-context <远程context>`，然后使用默认路径。

#### 步骤 4：启动并验证

```bash
mvn spring-boot:run
curl http://localhost:8083/api/health
curl "http://localhost:8083/api/pods?namespace=production&page=1&pageSize=10"
```

---

### 4.4 方案 C：SSH 隧道访问远程 API（API 不对外暴露）

**适用**：API Server 只监听远程 `127.0.0.1:6443`，无法从外网直连。

#### 步骤 1：建立 SSH 本地端口转发

```bash
ssh -N -L 16443:127.0.0.1:6443 user@your-remote-server
```

保持该终端不关闭。

#### 步骤 2：修改本地 kubeconfig 的 server 地址

将 `server` 改为隧道本地端口：

```yaml
clusters:
- cluster:
    server: https://127.0.0.1:16443
    certificate-authority-data: ...   # 保持原证书
  name: remote-via-tunnel
```

#### 步骤 3：验证并启动 Pod Master

```bash
kubectl --kubeconfig ~/.kube/remote-tunnel-config get nodes
mvn spring-boot:run -Dspring-boot.run.arguments="--k8s.kubeconfig-path=${HOME}/.kube/remote-tunnel-config"
```

> 隧道断开期间，Pod Master 会显示未连接；重新建立 SSH 隧道后重启应用。

---

### 4.5 本地浏览器访问远程 Pod Master

Pod Master 跑在远程、API 只绑定内网时，可用 SSH 转发 Web 端口：

```bash
ssh -L 8083:127.0.0.1:8083 user@your-remote-server
```

本地浏览器打开：`http://localhost:8083`

---

### 4.6 方案 D：Pod Master 部署在集群内部（in-cluster）

**适用**：长期在内网/K8s 内使用，通过 Ingress 或 NodePort 暴露 UI。

`application.yml`：

```yaml
k8s:
  connection-mode: in-cluster
  default-namespace: podmaster-demo
```

此时无需 kubeconfig，使用 Pod 挂载的 **ServiceAccount Token** 访问 API。

需为 Pod Master 创建 RBAC（示例）：

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: pod-master
  namespace: podmaster-demo
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: pod-master
rules:
  - apiGroups: [""]
    resources: ["pods", "pods/log", "pods/exec", "namespaces", "services", "events"]
    verbs: ["get", "list", "watch", "delete", "create"]
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["create"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: pod-master
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: pod-master
subjects:
  - kind: ServiceAccount
    name: pod-master
    namespace: podmaster-demo
```

Deployment 中指定 `serviceAccountName: pod-master`。

---

## 5. 配置参考

配置文件：`src/main/resources/application.yml`

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | `8083` | Web 端口 |
| `k8s.connection-mode` | `local` | `local` 或 `in-cluster` |
| `k8s.kubeconfig-path` | `~/.kube/config` | local 模式下的 kubeconfig 路径 |
| `k8s.default-namespace` | `podmaster-demo` | 前端默认选中的命名空间 |

### 命令行覆盖（无需改文件）

```bash
# 指定 kubeconfig
mvn spring-boot:run -Dspring-boot.run.arguments="--k8s.kubeconfig-path=/path/to/config"

# 指定默认 namespace
mvn spring-boot:run -Dspring-boot.run.arguments="--k8s.default-namespace=production"

# 集群内模式
java -jar target/pod-master-1.0.0.jar --k8s.connection-mode=in-cluster
```

### 环境变量（Spring Boot  relaxed binding）

```bash
export K8S_CONNECTION_MODE=local
export K8S_KUBECONFIG_PATH=/home/user/.kube/config
export K8S_DEFAULT_NAMESPACE=production
mvn spring-boot:run
```

---

## 6. 在 UI 中「进入 Pod」

连接成功后，除列表管理外，还可进入 **Pod 工作台**：

| 功能 | 说明 |
|------|------|
| 概览 | Pod 详情、容器、AI 快速诊断 |
| 实时日志 | 拉取 `kubectl logs` 等价内容 |
| 终端 | WebSocket 交互式 Shell（`ws://<host>:8083/ws/pods/terminal?namespace=xxx&name=yyy`） |
| AI 诊断 | 日志智能分析 |
| 事件 | K8s Event 时间线 |

**终端使用条件**：

- Pod 状态必须为 **Running**
- 当前 RBAC 需允许 `pods/exec`
- CrashLoopBackOff / Pending 的 Pod 无法进入终端

---

## 7. 权限要求（RBAC）

Pod Master 使用的 kubeconfig 用户（或 in-cluster ServiceAccount）建议至少具备：

| 资源 | 操作 | 用途 |
|------|------|------|
| `pods` | get, list, watch, delete | 列表、详情、删除 |
| `pods/log` | get | 日志、AI 诊断 |
| `pods/exec` | create | 交互式终端、命令执行 |
| `namespaces` | list | 命名空间下拉 |
| `services` | list | 流量拓扑 |
| `events` | list | 事件时间线 |

开发测试可用 cluster-admin；生产环境请按最小权限配置。

验证当前用户权限：

```bash
kubectl auth can-i list pods --all-namespaces
kubectl auth can-i create pods/exec -n podmaster-demo
```

---

## 8. 验证清单

按顺序执行，快速确认连接是否正常：

```bash
# ① kubectl 层
kubectl config current-context
kubectl get nodes
kubectl get pods -n podmaster-demo

# ② API 层
curl -s http://localhost:8083/api/health | jq .

# ③ Pod 列表
curl -s "http://localhost:8083/api/pods?namespace=podmaster-demo&page=1&pageSize=10" | jq .

# ④ 命名空间
curl -s http://localhost:8083/api/namespaces | jq .
```

浏览器：打开 `http://localhost:8083`，左侧应显示绿色 **「集群已连接」**，而非 **MOCK** 标签。

---

## 9. 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| `connection to 127.0.0.1:6443 was refused` | 本地 K8s 未启动 | 启动集群并确认 `kubectl cluster-info` 正常 |
| 页面显示 MOCK / 未连接 | kubeconfig 不可达或 context 错误 | `kubectl get pods` 成功后重启应用 |
| 远程 kubeconfig 连不上 | API 地址不可达 | 检查防火墙、安全组、VPN；或使用 SSH 隧道 |
| `401 Unauthorized` / `403 Forbidden` | RBAC 权限不足 | 参考第 7 节补充权限 |
| 终端连接失败 400 | Pod 非 Running 或 TTY 参数问题 | 选择 Running 的 Pod；查看应用日志 |
| 切换集群后仍显示旧数据 | 客户端启动时固定 kubeconfig | `kubectl config use-context` 后**重启** Pod Master |
| 启动慢（30s+） | 首次连接 API 较慢 | 属正常现象，后续会快 |

---

## 10. 快速命令速查

```bash
# 本地 demo 一键环境
./scripts/start-all.sh

# 仅部署 demo Pod
./scripts/setup-k8s-demo.sh

# 清理 demo
./scripts/teardown-k8s-demo.sh

# 查看当前连接的集群
kubectl config current-context
curl -s http://localhost:8083/api/health

# 本地浏览器访问远程 Pod Master（远程已启动在 8083）
ssh -L 8083:127.0.0.1:8083 user@remote-server

# 本地访问远程 K8s API（远程 API 仅 127.0.0.1:6443）
ssh -N -L 16443:127.0.0.1:6443 user@remote-server
```

---

## 相关文档

- [README.md](../README.md) — 项目总览与快速启动
- [application.yml](../src/main/resources/application.yml) — 默认配置
