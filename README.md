# Pod Master — K8s Pod 可视化管理平台

Pod Master 是一个 **前后端一体** 的 Kubernetes Pod 可视化运维平台。基于 Spring Boot + Fabric8 Client 连接集群 API，提供大屏监控风格的 Web UI，支持 Pod 管理、流量拓扑、AI 日志诊断、交互式终端与成本统计。

启动后访问 **http://localhost:8083**（端口见 `application.yml`）。

---

## 功能

| 模块 | 说明 |
|------|------|
| Pod 管理 | 命名空间切换、搜索分页、卡片/列表、详情、日志、重启、删除 |
| Pod 工作台 | 概览、实时日志、WebSocket 交互式终端、AI 诊断、事件时间线 |
| 流量拓扑 | 按业务域分组，Service 调用链可视化，异常链路标红 |
| AI 日志诊断 | OOM / 空指针 / DB 超时 / CrashLoop 等模式识别与修复建议 |
| 语音告警 | 异常 Pod 浏览器语音播报（可关闭） |
| 资源成本 | 按业务域估算日费用与占比 |

集群不可达时，前端自动进入 **Mock 模式**，UI 仍可预览。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 17、Spring Boot 3.2、Fabric8 Kubernetes Client 6.x |
| 前端 | Vue 3、Element Plus、xterm.js（单文件 `index.html`，CDN 零构建） |
| 集群 | 任意标准 Kubernetes（通过 kubeconfig 或 in-cluster 连接） |

---

## 架构

```
浏览器  http://localhost:8083
    │
    ├── GET /              → static/index.html
    ├── /api/**            → REST API
    ├── WS /ws/pods/terminal → 交互式终端
    └── Fabric8 Client → Kubernetes API Server → Pod / Service / Event
```

---

## 项目结构

```
pod-master/
├── pom.xml
├── README.md
├── docs/
│   └── KUBERNETES-CONNECTION.md   # 本地/远程集群连接指南
├── k8s/demo/                      # 演示环境 Manifest
├── scripts/                       # 部署与启动脚本
└── src/main/
    ├── java/com/podmaster/        # 后端代码
    └── resources/
        ├── application.yml
        └── static/index.html      # 前端页面
```

---

## 快速开始

**前置条件**：JDK 17+、Maven 3.8+、可访问的 Kubernetes 集群（`kubectl cluster-info` 正常）。

```bash
# 可选：部署演示环境（podmaster-demo 命名空间，6 个演示 Pod）
./scripts/setup-k8s-demo.sh

# 启动应用
mvn spring-boot:run

# 或一键：检查 K8s + 部署 demo + 启动
./scripts/start-all.sh
```

浏览器打开 http://localhost:8083 ，侧边栏显示 **「集群已连接」** 即表示成功。

打包运行：

```bash
mvn package -DskipTests
java -jar target/pod-master-1.0.0.jar
```

---

## 配置

`src/main/resources/application.yml`：

```yaml
server:
  port: 8083

k8s:
  connection-mode: local          # local | in-cluster
  kubeconfig-path: ${user.home}/.kube/config
  default-namespace: podmaster-demo
```

**如何连接本地 / 远程集群**（kubeconfig、SSH 隧道、in-cluster、RBAC、终端）：详见 **[docs/KUBERNETES-CONNECTION.md](docs/KUBERNETES-CONNECTION.md)**。

---

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 集群连接状态 |
| GET | `/api/namespaces` | 命名空间列表 |
| GET | `/api/pods` | Pod 分页列表 |
| GET | `/api/pods/{ns}/{name}` | Pod 详情 |
| GET | `/api/pods/{ns}/{name}/logs` | Pod 日志 |
| GET | `/api/pods/{ns}/{name}/cockpit` | Pod 工作台 |
| GET | `/api/pods/{ns}/{name}/diagnose` | AI 诊断 |
| GET | `/api/topology` | 流量拓扑 |
| GET | `/api/cost` | 资源成本 |
| GET | `/api/alerts` | 异常告警 |
| POST | `/api/pods/{ns}/{name}/restart` | 重启 Pod |
| DELETE | `/api/pods/{ns}/{name}` | 删除 Pod |
| WS | `/ws/pods/terminal?namespace=&name=` | 交互式终端 |

---

## License

MIT
