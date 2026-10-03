# Kubernetes (K8s) 整体架构与基础设施详解

> 面向 SpringCloud 应用迁移的系统性入门指南

---

## 目录

- [一、K8s 是什么](#一k8s-是什么)
- [二、整体架构概览](#二整体架构概览)
- [三、控制平面组件详解](#三控制平面组件详解)
- [四、工作节点组件详解](#四工作节点组件详解)
- [五、核心资源对象](#五核心资源对象)
- [六、网络基础设施原理（含 Pod/Node 网段、Service、ClusterIP、Ingress 详解）](#六网络基础设施原理含-podnode-网段serviceclusterip-ingress-详解)
- [七、存储基础设施原理](#七存储基础设施原理)
- [八、调度原理与资源管理](#八调度原理与资源管理)
- [九、服务发现与负载均衡](#九服务发现与负载均衡)
- [十、配置与密钥管理](#十配置与密钥管理)
- [十一、SpringCloud 迁移对应关系](#十一springcloud-迁移对应关系)
- [十二、迁移实践建议](#十二迁移实践建议)
- [十三、K8s 通信协议剖析（REST vs gRPC 澄清）](#十三k8s-通信协议剖析rest-vs-grpc-澄清)

---

## 一、K8s 是什么

Kubernetes（简称 K8s，因为 K 到 s 之间有 8 个字母）是 Google 开源的**容器编排平台**，用于自动化部署、扩展和管理容器化应用。

### 1.1 核心价值

| 能力 | 说明 |
|------|------|
| **服务发现与负载均衡** | 内置 DNS + Service 机制，无需额外组件即可实现服务注册与发现 |
| **自我修复** | 容器异常时自动重启，节点故障时自动重新调度 Pod |
| **水平扩展** | 一条命令或自动根据 CPU/内存使用率扩缩容应用实例 |
| **滚动更新与回滚** | 零停机部署新版本，出错可一键回滚 |
| **配置与密钥管理** | 统一管理环境配置和敏感信息，不硬编码在镜像中 |
| **存储编排** | 自动挂载本地/云存储/网络存储，数据持久化 |
| **资源装箱** | 智能调度，提高服务器资源利用率 |

### 1.2 K8s vs 传统部署模式对比

```
传统部署（物理机/虚拟机）：        容器化 + K8s 部署：
┌──────────────────────┐         ┌──────────────────────────────┐
│  App1  App2  App3    │         │  ┌─────┐  ┌─────┐  ┌─────┐  │
│  JVM   JVM   JVM     │         │  │Pod1 │  │Pod2 │  │Pod3 │  │
│  OS    OS    OS      │         │  └─────┘  └─────┘  └─────┘  │
│  Hypervisor          │         │    Container Runtime        │
│  Hardware            │         │        OS                    │
└──────────────────────┘         │      Hardware                │
                                 └──────────────────────────────┘
```

---

## 二、整体架构概览

K8s 采用 **主从架构（Master-Worker）**，分为控制平面（Control Plane）和工作节点（Worker Node）两大部分。

### 2.1 架构全景图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        KUBERNETES CLUSTER                           │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    CONTROL PLANE (Master)                    │   │
│  │                                                             │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │   │
│  │  │ API Server   │◄─┤   etcd       │  │ Controller       │  │   │
│  │  │ (REST API)   │──►(KV Store)    │  │ Manager          │  │   │
│  │  └──────┬───────┘  └──────────────┘  └────────┬─────────┘  │   │
│  │         │                                      │            │   │
│  │         │                              ┌───────▼─────────┐  │   │
│  │         │                              │  Scheduler      │  │   │
│  │         │                              │ (调度Pod到Node) │  │   │
│  │         │                              └─────────────────┘  │   │
│  └─────────┼───────────────────────────────────────────────────┘   │
│            │                                                         │
│  ┌─────────▼─────────────────────────────────────────────────────┐   │
│  │                    WORKER NODES (数据平面)                      │   │
│  │                                                               │   │
│  │  ┌──────────────────┐  ┌──────────────────┐                   │   │
│  │  │  Worker Node 1   │  │  Worker Node 2   │  ...              │   │
│  │  │  ┌────────────┐  │  │  ┌────────────┐  │                   │   │
│  │  │  │ kubelet    │  │  │  │ kubelet    │  │                   │   │
│  │  │  │ (节点代理) │  │  │  │ (节点代理) │  │                   │   │
│  │  │  ├────────────┤  │  │  ├────────────┤  │                   │   │
│  │  │  │ kube-proxy │  │  │  │ kube-proxy │  │                   │   │
│  │  │  │ (网络代理) │  │  │  │ (网络代理) │  │                   │   │
│  │  │  ├────────────┤  │  │  ├────────────┤  │                   │   │
│  │  │  │ Container  │  │  │  │ Container  │  │                   │   │
│  │  │  │ Runtime    │  │  │  │ Runtime    │  │                   │   │
│  │  │  │ (Docker/   │  │  │  │ (Docker/   │  │                   │   │
│  │  │  │  containerd)│  │  │  │  containerd)│  │                   │   │
│  │  │  ├────────────┤  │  │  ├────────────┤  │                   │   │
│  │  │  │ Pods       │  │  │  │ Pods       │  │                   │   │
│  │  │  │ ┌─┐┌─┐┌─┐  │  │  │  │ ┌─┐┌─┐    │  │                   │   │
│  │  │  │ └─┘└─┘└─┘  │  │  │  │ └─┘└─┘    │  │                   │   │
│  │  │  └────────────┘  │  │  └────────────┘  │                   │   │
│  │  └──────────────────┘  └──────────────────┘                   │   │
│  └───────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心设计理念

1. **声明式 API**：你只需要声明「期望状态」（比如：运行 3 个实例），K8s 负责让「实际状态」收敛到「期望状态」
2. **一切皆资源**：所有对象（Pod、Service、ConfigMap…）都是通过 API Server 管理的资源
3. **Controller 模式**：每个 Controller 持续监控资源状态，自动进行调谐（Reconcile）
4. **Pod 为最小调度单元**：不是直接调度容器，而是调度包含一个或多个容器的 Pod

---

## 三、控制平面组件详解

控制平面是集群的「大脑」，负责全局决策和管理。生产环境通常部署 3 或 5 个 Master 节点实现高可用。

### 3.1 kube-apiserver（API 服务器）

**作用**：K8s 集群的唯一入口，所有组件都通过它通信。

- **通信枢纽**：所有组件（Scheduler、Controller、kubelet、kubectl）都不直接互访，而是通过 API Server
- **认证授权**：处理身份认证、权限校验（RBAC）、准入控制
- **数据持久化**：唯一能读写 etcd 的组件
- **对外 API 协议**：提供 **HTTP/HTTPS RESTful 接口**，同时支持两种序列化格式：`application/json`（人类可读，99% 场景使用）和 `application/vnd.kubernetes.protobuf`（二进制，性能更好但仍是 HTTP 传输，不是 gRPC）
- **Watch 机制**：通过 HTTP Chunked Streaming 长连接推送资源变更事件

> 📌 **常见疑问：K8s API 改成 gRPC 了吗？**<br>
> 对外用户 API（kubectl、client-go、SDK 调用的这层）**没有改成 gRPC，仍然是 HTTP REST**。<br>
> 但 K8s 在内部插件接口（CRI/CSI/Device Plugin 等）、以及 API Server → etcd 的调用链上，**已经全面使用 gRPC**。详细分层讲解见 [第十三章](#十三k8s-通信协议剖析rest-vs-grpc-澄清)。

```
   kubectl ────┐
   Dashboard ──┼── HTTP REST ──►┌────────────┐◄── gRPC ──► etcd
   client-go  ─┤                │ API Server │
   kubelet ────┤   HTTP REST    │            │   HTTP REST
   Controller ─┘◄───────────────┴────────────┘◄────────────┐
                                                           │
                                                     Scheduler
```

### 3.2 etcd（分布式键值存储）

**作用**：K8s 的「数据库」，存储集群所有状态数据。

- **存储内容**：所有资源对象（Pod、Service、Node…）、集群配置、状态信息
- **一致性**：基于 Raft 协议实现强一致性分布式存储
- **高可用**：建议部署奇数节点（3/5/7），超过半数节点存活即可工作

> ⚠️ **重要**：生产环境一定要定期备份 etcd，它是集群数据的唯一存储。

### 3.3 kube-controller-manager（控制器管理器）

**作用**：运行一系列「控制器」，持续监控集群状态，驱动实际状态向期望状态收敛。

| 控制器 | 功能 |
|--------|------|
| **Node Controller** | 监控节点状态，节点故障时标记并触发 Pod 重建 |
| **Replication Controller** | 确保 Pod 副本数始终符合期望 |
| **Deployment Controller** | 管理 Deployment，实现滚动更新和回滚 |
| **Service Controller** | 创建/更新 LoadBalancer 类型的 Service |
| **Endpoint Controller** | 根据 Pod IP 更新 Service 的 Endpoints |
| **Namespace Controller** | 管理命名空间生命周期 |
| **Job/CronJob Controller** | 管理一次性任务和定时任务 |

**工作原理**：
```
期望状态（3个Pod） ───► Controller ───► 实际状态（当前2个Pod）
                              │
                              ▼
                       创建1个新Pod（通过API Server）
```

### 3.4 kube-scheduler（调度器）

**作用**：为新创建的 Pod 选择最合适的工作节点运行。

**调度流程（3 步）**：

1. **过滤（Filtering）**：从所有 Node 中筛选出满足 Pod 要求的节点
   - 资源是否充足（CPU/内存）
   - 是否满足节点选择器（nodeSelector）
   - 是否容忍污点（Taint/Toleration）
   - 是否满足亲和/反亲和规则
   
2. **打分（Scoring）**：对过滤后的节点打分，选出分数最高的
   - 资源剩余量（LeastRequested / MostRequested）
   - Pod 分布均衡性（BalanceResourceAllocation）
   - 镜像是否已拉取（ImageLocality）
   
3. **绑定（Binding）**：将 Pod 与目标 Node 绑定

### 3.5 cloud-controller-manager（云控制器管理器，可选）

**作用**：对接云服务商 API，实现云基础设施联动。

- LoadBalancer Service 自动创建云负载均衡器
- 自动发现/同步云主机为 Node
- 云存储卷（PV）管理

> 自建集群可不用，阿里云/腾讯云/AWS/GCP 托管 K8s 会自动集成。

---

## 四、工作节点组件详解

工作节点是真正运行业务容器的地方，也叫数据平面。

### 4.1 kubelet（节点代理）

**作用**：运行在每个 Worker 上的「特工」，是 Node 和 Control Plane 的桥梁。

**核心职责**：

1. **Pod 管理**：
   - 接收 API Server 的 Pod 分配指令
   - 调用 Container Runtime 创建/销毁容器
   - 定期上报 Pod 健康状态

2. **健康检查**：
   - 执行 Liveness Probe（存活探针）—— 不存活则重启容器
   - 执行 Readiness Probe（就绪探针）—— 未就绪则从 Service 摘除流量

3. **节点状态上报**：
   - 上报 Node 的 CPU/内存/磁盘容量
   - 心跳上报（NodeLease），告知 Master 节点存活

4. **Volume 管理**：
   - 挂载 PV（持久卷）到 Pod
   - 管理 ConfigMap/Secret 卷挂载

**kubelet 工作流程**：
```
API Server ──(Pod 清单)──► kubelet
                                │
                   ┌────────────┼────────────┐
                   ▼            ▼            ▼
             Container     Storage      Network
             Runtime       (CSI)        (CNI)
             (创建容器)    (挂载卷)     (配置网络)
```

### 4.2 kube-proxy（网络代理）

**作用**：运行在每个 Worker 上，实现 Service 网络规则和负载均衡。

**三种工作模式**：

| 模式 | 原理 | 性能 | 适用场景 |
|------|------|------|----------|
| **iptables** | 基于 netfilter 规则做 DNAT 转发 | 中等（规则多时慢）| 默认，兼容性好 |
| **ipvs** | 内核级负载均衡，支持更多调度算法 | 高（大型集群）| 1000+ Service 推荐 |
| **userspace** | 用 kube-proxy 进程做代理 | 最低（用户态转发）| 已废弃，不推荐 |

**iptables 模式原理示例**：
```
用户请求 Service ClusterIP: 10.96.0.100:80
           │
           ▼
  iptables PREROUTING 规则
           │
           ▼
  DNAT 到某个 Pod IP（随机/轮询）: 10.244.1.5:8080
           │
           ▼
       真正的 Pod
```

### 4.3 Container Runtime（容器运行时）

**作用**：真正负责运行容器的软件。

| 运行时 | 说明 |
|--------|------|
| **containerd** | CNCF 毕业项目，K8s 1.24+ 默认推荐，轻量稳定 |
| **Docker** | 经典容器运行时，K8s 1.24 起已弃用（通过 cri-dockerd 兼容）|
| **CRI-O** | RedHat 主导，轻量，只实现 CRI 接口 |

> 🔎 **CRI 接口**：Container Runtime Interface，是 K8s 定义的一套标准 gRPC 接口，让 kubelet 不用绑定具体运行时。

---

## 五、核心资源对象

### 5.1 Pod —— 最小调度单元

**定义**：一个或多个容器的组合，共享网络和存储。

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-app-pod
  labels:
    app: my-app
spec:
  containers:
  - name: app-container
    image: my-registry/my-app:v1
    ports:
    - containerPort: 8080
    resources:
      requests:
        cpu: "500m"      # 申请 0.5 核
        memory: "512Mi"  # 申请 512MB
      limits:
        cpu: "2000m"     # 上限 2 核
        memory: "2Gi"    # 上限 2GB
```

**关键特性**：
- 每个 Pod 有独立的 IP（Pod IP）
- 一个 Pod 内的容器共享 `localhost`，通过 `127.0.0.1` 互访
- 容器之间可以共享 Volume
- Pod 是**临时性**的，销毁重建后 IP 会变化（所以不能直接用 Pod IP 通信）

> 🎯 **SpringCloud 提示**：你的一个微服务实例 = 一个 Pod。Eureka 里的实例在 K8s 里由 Pod 承载。

### 5.2 Deployment —— 无状态应用部署

**作用**：管理 Pod 的副本数、滚动更新。是最常用的工作负载资源。

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
spec:
  replicas: 3                    # 期望副本数
  selector:
    matchLabels:
      app: user-service          # 选择管理哪些 Pod
  template:                      # Pod 模板
    metadata:
      labels:
        app: user-service
    spec:
      containers:
      - name: user-service
        image: my-registry/user-service:1.0
        ports:
        - containerPort: 8080
```

**核心功能**：
- 水平扩展：`kubectl scale deployment user-service --replicas=5`
- 滚动更新：更新镜像自动分批替换 Pod
- 回滚：`kubectl rollout undo deployment user-service`
- 版本历史：保留历史 ReplicaSet

### 5.3 Service —— 服务访问入口

**作用**：为一组 Pod 提供**固定的访问入口**，解决 Pod IP 易变问题。

**四种类型**：

| 类型 | 说明 | 适用场景 |
|------|------|----------|
| **ClusterIP** | 仅集群内部可访问的虚拟 IP | **默认**，微服务间调用 |
| **NodePort** | 在每个 Node 上开放固定端口（30000-32767） | 测试/临时外部访问 |
| **LoadBalancer** | 在 ClusterIP + NodePort 基础上，再挂云负载均衡器 | 生产环境对外服务 |
| **ExternalName** | 把外部服务映射进集群 DNS | 访问集群外数据库等 |

**ClusterIP Service 示例**：
```yaml
apiVersion: v1
kind: Service
metadata:
  name: user-service
spec:
  type: ClusterIP
  selector:                   # 选择器，匹配 Pod 标签
    app: user-service
  ports:
  - name: http
    port: 80                  # Service 端口
    targetPort: 8080          # Pod 容器端口
```

### 5.4 Ingress —— HTTP/HTTPS 路由入口

**作用**：在集群边缘做 HTTP 路由，将不同域名/路径转发到不同 Service。

> ⚠️ Ingress 只是规则，必须配合 **Ingress Controller**（如 Nginx Ingress、Traefik、Istio Gateway）才生效。

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: web-ingress
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  rules:
  - host: api.example.com
    http:
      paths:
      - path: /user
        pathType: Prefix
        backend:
          service:
            name: user-service
            port:
              number: 80
      - path: /order
        pathType: Prefix
        backend:
          service:
            name: order-service
            port:
              number: 80
```

**Ingress 数据流**：
```
  用户请求 api.example.com/user/1
              │
              ▼
    云负载均衡器 (可选)
              │
              ▼
    Ingress Controller Pod (Nginx)
              │
              ▼
      user-service (ClusterIP)
              │
              ▼
    user-service Pod x3
```

### 5.5 Namespace —— 命名空间

**作用**：逻辑隔离集群资源，类似「租户」。

| 常用命名空间 | 用途 |
|-------------|------|
| `default` | 默认命名空间，不指定时资源都在这 |
| `kube-system` | K8s 系统组件（coredns, calico 等）|
| `kube-public` | 所有用户可读的公共资源 |
| `kube-node-lease` | Node 心跳租约 |

**实践建议**：按环境（dev/test/prod）或按团队划分命名空间。

---

## 六、网络基础设施原理（含 Pod/Node 网段、Service、ClusterIP、Ingress 详解）

K8s 网络是新手最容易懵的部分，尤其「Pod IP、Node IP、ClusterIP、Service IP、NodePort、Ingress 这一堆 IP/端口/概念到底是什么关系？」这一节从小白视角，从「三大网段规划」到「Service 四种类型」再到「Ingress 为什么存在」，系统性地串起来。

> 📌 本节配合前文的 [iptables.md](file:///d:/BaiduSyncdisk/ai/k8s/iptables.md) 一起阅读，可以把 K8s 网络转发的前因后果彻底搞懂。

---

### 6.1 K8s 网络模型（三大硬性要求）

CNI（Container Network Interface）插件必须满足：

1. **Pod 之间直接通信**：Node1 上的 PodA 可以直接和 Node2 上的 PodB 通信，无需 NAT
2. **Node 和 Pod 直接通信**：宿主机和 Pod 之间不经过 NAT
3. **Pod 看到的 IP = 别人看到的 IP**：IP 地址一致，无地址转换

---

### 6.2 集群三大网段规划（小白必懂！Pod / Node / Service ClusterIP）

K8s 集群**必须有 3 个互不重叠的 CIDR 网段**，这是部署集群第一天就要规划好的，90% 的新手部署失败就是网段重叠或者理解错了。

| 序号 | 网段名字 | 俗称 | 谁在分配 | 作用范围 | 典型 CIDR | 大小建议 | 可以从外部 Ping 通吗？|
|------|----------|------|----------|---------|-----------|---------|-------------------|
| **①** | **Node 网段（Node CIDR / 主机网段）** | 宿主机网段 | 由你自己的物理网络/云 VPC 分配，**K8s 本身不管理** | 所有 Master + Worker 节点的物理网卡 IP（`ens33/eth0`）| 10.0.0.0/24 或 192.168.1.0/24（你的办公网/机房 VLAN/云 VPC 子网）| 按节点数：/24 能装 254 台机器够大多数用 | ✅ **能**（这就是你 ssh 登录 Node 用的那个真实 IP） |
| **②** | **Pod 网段（Pod CIDR / 集群 Pod 网络）** | Pod 容器网段 | K8s Controller Manager 的 `--cluster-cidr` 参数，再由 kube-controller-manager 按 Node 拆成每个 Node 的 `/24` 子块（NodeCIDR）交给 kubelet，最终 CNI 插件（flannel/calico）给每个 Pod 从这个子块里挑 IP | 所有 Pod（包括 CoreDNS、Ingress Controller、你的业务 Pod）拿到的 eth0 IP | kubeadm 默认 **10.244.0.0/16**（65536 个 Pod）；自建大集群可以用 10.200.0.0/14 | /16 → 65536 Pod；/14 → 26 万 Pod；**每个 Node 默认分到 /24（256 个 Pod，对应 K8s 默认每 Node 110 Pod 上限）** | ❌ **不能直接 ping**（除非你在同一个 Node 上、或者用 Calico 纯 BGP 模式 + 物理交换机加路由；普通 Flannel VXLAN 模式下 Pod 网段是 Overlay 虚拟网，集群外看不到） |
| **③** | **Service 网段（ClusterIP CIDR / Service Cluster IP Range）** | 虚拟服务网段（最容易理解错！） | API Server 的 `--service-cluster-ip-range` 参数；每创建一个 Service 就从这个网段里挑一个 IP，存在 etcd 里 | 所有 Service（ClusterIP 类型）拿到的那个「ClusterIP」，包括 kubernetes.default（10.96.0.1）、CoreDNS（通常 10.96.0.10）| kubeadm 默认 **10.96.0.0/12**（104 万个 Service，够超大规模用了）| /12 或 /16，小集群 /16 够了；**注意 kubeadm 默认 10.96.0.0/12 不可以和 Pod 网段重叠！** | ❌ **绝对 ping 不通！也不是任何真实网卡上的 IP**。下面 6.8 节专门解释它到底是什么。 |

> 🚨 **新手最常犯的 3 个部署错误**：
> 1. **网段重叠**：PodCIDR=10.244.0.0/16，但你物理网络本身就是 10.244.x.x → 冲突，网络全不通
> 2. **把 ClusterIP 当成真实 IP ping**：在 Node 上 `ping 10.96.0.1`（kube-dns/service-cluster 第一个 IP）ping 不通就以为集群坏了 → **正常，因为它根本不是 IP 层的真实地址**。
> 3. **Pod CIDR 开太大/太小**：生产集群 3 节点 300 Pod，选 /16 没问题，但如果要做 Pod 网络与企业网 BGP 打通，千万不要和现有网络重叠。

#### 三大网段在一台集群里的真实示例（kubeadm 默认）

```bash
# 一台典型 3 节点集群部署完后：
Node 网段（真实物理）   10.0.0.0/24       ─► master=10.0.0.10, worker1=10.0.0.11, worker2=10.0.0.12
Pod  网段（CNI 虚拟）   10.244.0.0/16     ─► 拆分：worker1 负责 10.244.1.0/24, worker2 负责 10.244.2.0/24
                                          └► worker1 上的 Pod：10.244.1.2/3.4.5...
Service 网段（全虚拟） 10.96.0.0/12       ─► kubernetes.default = 10.96.0.1
                                          └► CoreDNS (kube-dns) = 10.96.0.10
                                          └► user-service = 10.96.124.37
```

#### 网段规划时的一张关系图

```
  [集群外的客户端：172.16.5.88（用户电脑/手机/外部网关）]
                       │
                       │  企业物理网 / 云 VPC 路由
                       ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  Node 网段（真实可见、可互通）：10.0.0.0/24                     │
  │  ┌──────────Master:10.0.0.10───── Worker1:10.0.0.11 ── Worker2:10.0.0.12 ─┐
  │  │                                 │                          │            │
  │  │  ② Pod 网段 Overlay：10.244.0.0/16（CNI 提供，Pod 之间全局可达）          │
  │  │  Worker1 子块 10.244.1.0/24  Worker2 子块 10.244.2.0/24                   │
  │  │                                 │                          │            │
  │  │  Pod A:10.244.1.5  Pod B:10.244.1.6  Pod C:10.244.2.5 Pod D:10.244.2.6 │
  │  │                                 │                          │            │
  │  │  ③ Service ClusterIP 网段：10.96.0.0/12（不存在于任何网卡，纯 iptables 规则）│
  │  │  user-service ClusterIP = 10.96.124.37（任意 Node/Pod 都能访问到，DNAT 到 Pod）│
  └──┴─────────────────────────────────────────────────────────────────────────┘
```

---

### 6.3 集群网络基础组成（补全 6.2 架构图）

```
┌──────────────────────────────────────────────────────────────┐
│ Node 1 (10.0.0.11)         Node 2 (10.0.0.12)                  │
│ ┌─────────────────────┐      ┌─────────────────────┐          │
│ │ Pod 1   10.244.1.2  │      │ Pod 3   10.244.2.2  │          │
│ │ Pod 2   10.244.1.3  │      │ Pod 4   10.244.2.3  │          │
│ └──────────┬──────────┘      └──────────┬──────────┘          │
│            │  Pod 网段（② 10.244.1.0/24）│  Pod 网段②          │
│    ┌───────▼───────┐              ┌───────▼───────┐          │
│    │  CNI 网桥     │              │  CNI 网桥     │          │
│    │ 10.244.1.1    │◄──Underlay──►│ 10.244.2.1    │          │
│    └───────┬───────┘  (Flannel VXLAN/BGP)                   │
│            │                                             │
│     ┌──────▼──────┐  Node 网段①：真实物理网卡 eth0          │
│     │  10.0.0.11  │◄────────────────► 10.0.0.12            │
│     └─────────────┘                                     │
│                                                           │
│  ③ Service ClusterIP 网段：10.96.0.0/12                     │
│  （所有 Node 内核 iptables 里都有相同规则，不管数据包在哪台机器  │
│     访问 10.96.x.y 都被 DNAT 到对应后端 Pod IP）              │
└──────────────────────────────────────────────────────────────┘
```

---

### 6.4 常见 CNI 插件对比

| 插件 | 原理 | 优点 | 缺点 | 适用场景 |
|------|------|------|------|----------|
| **Flannel** | VXLAN 隧道封装（默认 backend=vxlan）| 简单、稳定、易部署，kubeadm Flannel 插件一键部署 | 性能中等（VXLAN 加解封有 ~10% 开销），无 NetworkPolicy | 中小集群、快速上手，SpringCloud 初次迁移先拿它练手 |
| **Calico** | BGP 路由（三层 Underlay 纯路由模式，也支持 IPIP/VXLAN 封装）| 性能最高的纯路由模式、NetworkPolicy 严谨、支持大规模 | 配置复杂，BGP 模式要求你物理网络能配合（交换机开 BGP）| 生产集群、需要网络隔离策略、对性能要求高 |
| **Cilium** | eBPF 内核级（Socket/Tc hook 直接转发，替代 iptables）| 性能极高、内置 Hubble L7 流量可视化、支持 HTTP/GRPC 细粒度策略 | 内核要求高（推荐 5.10+，5.15+ 全特性）| 大规模、高性能、需要流量可视化的场景 |

---

### 6.5 先搞懂 Service 到底是什么（和 Endpoint/Endpoints 的关系）

在讲 4 种 Service 类型之前，先解决小白最常见的疑问：

> "Service 到底是不是一个真实进程？为什么它能负载均衡？"

答案：**Service 本身只是 API Server + etcd 里存的一条 YAML 资源（metadata + spec），它本身没有运行着一个进程，也不是一台负载均衡器**。它的「负载均衡能力」完全来自于：

1. **kube-proxy**：每个 Worker 都跑的 DaemonSet，持续 watch API Server 里 Service 和 EndpointSlice 的变化，把它翻译成 Node 内核的 iptables/ipvs 规则
2. **Endpoint / EndpointSlice**：Service 的「后端 Pod 清单」，K8s 自动根据 Service selector 把符合 label 的 Pod IP:Port 填进去

三者的关系链：
```
  开发写 Service YAML（selector: app=user）
        │
        ▼  API Server 写 etcd
  Controller Manager 的 Endpoint Controller watch Service
        │ 找到所有 app=user 的 Running + Ready 的 Pod IP
        ▼
  生成 EndpointSlice（v1.21+ 替代老 Endpoints，分片支持大规模）
        │
        ▼
  每个 Node 的 kube-proxy watch Service + EndpointSlice
        │
        ▼
  在 Node 内核 netfilter 写 iptables/ipvs 规则（ClusterIP → Pod IP DNAT 映射）
        │
        ▼  真正生效
  Pod/外部流量发往 ClusterIP:Port → 内核规则匹配 → DNAT 到真实 Pod IP
```

> 🎯 一句话记忆：**Service = 规则定义，kube-proxy = 规则下发者，iptables/ipvs = 规则真正执行者**。

---

### 6.6 Service 的 4 种类型（ClusterIP / NodePort / LoadBalancer / ExternalName）

K8s Service 有且仅有 4 种 `spec.type`，下表一次讲清：

| 类型 | 对外可见性 | 端口 | 给它分配什么 IP？ | 典型用途 | SpringCloud 对应理解 |
|------|-----------|------|------------------|----------|---------------------|
| **① ClusterIP（默认）**| ❌ 仅集群内部（Pod 内部、Node 上都能访问） | `spec.ports[].port` | 从 Service CIDR（10.96.x.y）挑一个 ClusterIP + Endpoints Pod IP 列表 | **微服务之间内部调用**（order-service → user-service）| **相当于 Nacos 注册中心 + Ribbon 负载均衡组合** |
| **② NodePort** | ✅ 集群外部可见（用任意 Node 的真实 IP:NodePort 就能访问）| 额外占用每个 Node 上一个静态端口（默认范围 30000-32767）| 先给你一个 ClusterIP（底层自动建 ClusterIP），再把它映射到每个 Node 的 <NodeIP>:<NodePort> | 临时调试、小规模对外服务、后面接外部 LB 的中转层 | 相当于你把应用 `server.port=8080` 暴露在所有机器的固定端口 30xxx 上 |
| **③ LoadBalancer** | ✅ 对外可见（分配公网或私网 LB VIP）| NodePort + LB 端口 | 在 ② 的基础上**额外调用云厂商 API 申请一个外部 LB**（阿里云 SLB、AWS ELB、腾讯云 CLB 等），LB 把流量转发到各 Node 的 NodePort | **生产对外公网服务**（官网、OpenAPI 入口）| 相当于买了一台 F5/NGINX Plus / 云 SLB，K8s 自动帮你配置好后端 RS |
| **④ ExternalName** | 内部专用（CNAME 转发）| 无 | **不给任何 IP**，只给 CoreDNS 加一条 CNAME 记录指向一个外部域名 | 访问集群外的数据库/老系统用的「DNS 别名」 | 相当于内网 DNS 的 CNAME 记录，完全不涉及四层转发 |

> 📌 重要：4 种不是并列选择，而是**递进关系**：`LoadBalancer` 底层必须先建 `NodePort`，NodePort 底层必须先建 `ClusterIP`，ExternalName 是第四种完全独立的类型。

---

### 6.7 一张表 + 一张图看懂 4 种 Service 数据流

```
                    公网用户：172.16.5.88  浏览器访问 https://app.example.com
                                          │
                     ③ LoadBalancer VIP：47.x.x.x (云厂商 SLB/CLB)
                                          │
              ┌───────────────────────────┼───────────────────────────┐
              ▼                           ▼                           ▼
   Node1 真实 IP:10.0.0.11      Node2 IP:10.0.0.12        Node3 IP:10.0.0.13
       ② NodePort:30080                NodePort:30080             NodePort:30080
              │                           │                           │
              └───────────────────────────┴───────────────────────────┘
                                          │
                      ① ClusterIP Service: 10.96.124.37:80
                                          │
                             kube-proxy iptables DNAT 按概率分
                                    ┌─────┼─────┐
                                    ▼     ▼     ▼
                        user-service Pod1 Pod2 Pod3 (10.244.x.y:8080)
```

#### 实际生产怎么选？

| 你的需求 | 推荐 Service 类型 | 备注 |
|----------|------------------|------|
| 微服务 A 调微服务 B | ClusterIP | **95% 内部服务都选这个**，配合 CoreDNS 直接用域名（`http://user-service:80`）|
| 临时调试，我电脑要直接访问 Pod 里的接口 | `kubectl port-forward` 或 NodePort | NodePort 用完记得删掉，不安全 |
| 生产公网对外网站、HTTPS 接口 | ❌ 不用 Service，选 **Ingress（下一节讲）** 或者 LoadBalancer + Ingress | 直接用 LoadBalancer 一个服务 1 个 SLB 太浪费 |
| 应用要访问外部 SaaS（rds.aliyuncs.com / oldsystem.corp）| ExternalName | 内部统一叫 `old-system.default.svc.cluster.local` → CNAME 到外网地址 |

---

### 6.8 重点解析：ClusterIP 到底是什么？为什么 ping 不通？

回到你前面最疑惑的问题：

> ClusterIP 明明是一个 IP，而且我在 Pod 里 curl 它能通，但我 ssh 到 Node 上 ping 不通？为什么？？

答案一句话：**ClusterIP 不是一个分配在任何真实网卡（lo/eth0/veth）上的真实 IP，它只是 iptables/ipvs 内核规则里的一个「匹配用的虚拟目标地址」**。

#### 原理拆解（5 步完全懂）

**Step 1：K8s 分配 ClusterIP 时什么都没做**
创建 Service 时，API Server 只是在 etcd 里记了一句「user-service → ClusterIP = 10.96.124.37」，没有去任何地方配置网卡。

**Step 2：kube-proxy 把规则写到每个 Node 的 iptables**
每个 Node 上的 kube-proxy Watch 到这个 Service + EndpointSlice 之后，会在 Node 的内核 Netfilter 里加这样一组链（详见 [iptables.md](file:///d:/BaiduSyncdisk/ai/k8s/iptables.md) 第三章）：

```bash
# Node 上执行 iptables -t nat -S | grep 10.96.124.37
-A KUBE-SERVICES -d 10.96.124.37/32 -p tcp -m tcp --dport 80 \
   -j KUBE-SVC-XXXXXXXXXXXXXX   ← 只要目标 IP=ClusterIP,端口=80 就进 Service 链

-A KUBE-SVC-XXX -m statistic --mode random --probability 0.33333 -j KUBE-SEP-POD1
-A KUBE-SVC-XXX -m statistic --mode random --probability 0.5     -j KUBE-SEP-POD2
-A KUBE-SEP-XXX -j DNAT --to-destination 10.244.1.5:8080     ← 关键！做 DNAT
```

→ **重点**：内核的 `nat` 表只会匹配 **有状态的 NEW 连接的第一个包**（由 conntrack 配合）。匹配到以后，直接做**目标地址转换 DNAT**：把 dst=10.96.124.37 → 改成 dst=10.244.1.5:8080。

**Step 3：ping 用的是 ICMP 协议，不匹配 TCP/UDP 规则**
```bash
ping 10.96.124.37      # 发 ICMP echo request
```
ICMP 包 **没有 TCP 80 端口号**，所以进不了上面那条 `-m tcp --dport 80` 的 iptables 规则 → 没被 DNAT，内核把它当成一个「发往本机没路由的 IP」，走默认 FORWARD/LOCAL_IN，**最后被丢弃** → 所以 ping 不通。

**Step 4：curl http://10.96.124.37:80 为什么能通？**
因为 curl 发 TCP SYN 包，目标端口是 80 → 完美匹配 iptables 规则 → DNAT 改到真正 Pod IP → 包发到 CNI 网桥 → Pod 收到包 → 正常 TCP 握手。

**Step 5：conntrack 的功劳：回程包不用再查 Service**
第一个包匹配 DNAT 之后，conntrack 在连接跟踪表中记录：
```
tuple=(src=10.244.1.2:45678, dst=10.96.124.37:80) ↔ (src=10.244.1.2:45678, dst=10.244.1.5:8080)
```
所以 Pod 返回的 SYN+ACK 到 Node 后，conntrack 自动反向做 SNAT，把 src IP 再改回 ClusterIP。对调用方 Pod 来说，全程看到的目标 IP 就是 10.96.124.37 → 满足了「Pod 看到的 IP 和别人看到的一致」这条网络模型要求。

> 💡 **小白验证实验**（任何集群都能做）：
> ```bash
> # ssh 到任意 Node：
> ping -c 1 10.96.0.10             # 100% 丢包（CoreDNS ClusterIP）。正常！
> curl -v http://10.96.0.10:53     # 也不通（因为 DNS 走 UDP 53，curl 不是 DNS 客户端）
> dig @10.96.0.10 google.com A     # ✅ 能通！UDP 53 匹配规则，DNAT 到 CoreDNS Pod
> ```

---

### 6.9 Ingress 是什么？为什么它和 Service 容易搞混？

#### 先看结论性的对比表

| 对比维度 | Service | Ingress |
|----------|---------|---------|
| **OSI 层级** | 四层（L4：TCP/UDP + IP + Port）| 七层（L7：HTTP/HTTPS，按域名/路径路由）|
| **负载均衡维度** | 只能按源 IP/源端口 hash 或轮询，不懂 HTTP | 按 Host、Path、Header、Cookie 做路由；支持会话保持、权重、金丝雀 |
| **端口资源占用** | 一个 Service 一个端口（NodePort/LoadBalancer 模式下每个都占一个独立物理端口）| **所有 HTTP 服务共用 80/443 两个端口**（Ingress Controller 监听这俩，再靠 Host/Path 区分） |
| **HTTPS/TLS** | L4 Service 做不了 TLS 卸载，得后端 Pod 自己弄 | **集中在 Ingress Controller 层统一卸载**（配合 cert-manager 自动发证书）|
| **资源成本** | 公网 Service 每个对应 1 台云 SLB → N 个公网服务 = N 台 SLB，贵炸 | N 个公网服务 = 共享 1 台 SLB + 1 个 Ingress Controller → 省 99% 公网入口成本 |
| **典型场景** | 内部微服务调用；非 HTTP 的 TCP 服务（MySQL/Redis/Kafka）对外 | 所有 HTTP/HTTPS 网站、OpenAPI、Web 应用、GRPC-Web 的统一入口 |

> 🎯 小白一句话记忆：
> - **Service 是「微服务的内网 VIP」**：解决内部多个 Pod IP 动态变化的问题，让你用一个固定 ClusterIP:Port 访问
> - **Ingress 是「整个集群的 HTTP 统一网关 / 反向代理」**：解决集群内部 100 个 HTTP 服务共享 80/443 对外发布的问题。它本身不是 Service 的替代品，而是 **Ingress → 后端匹配到 Service ClusterIP → 再 kube-proxy DNAT 到 Pod IP** 的两层结构。

#### Ingress 三段式架构（必须理解）

Ingress 本身**不是一个可运行的组件**，它又是一条 YAML 规则。真正干活的是这 3 个部分配合：

```
  [① Ingress 对象（YAML 规则）]
       定义：api.example.com/user → user-service:80
             api.example.com/order → order-service:80
       存在哪里？→ API Server / etcd，和 Service 一样是纯 K8s 资源
            │
            ▼ Watch 规则变化
  [② Ingress Controller（真正运行的 Pod！）]
       实现者：Nginx Ingress（社区默认，用得最多）、Contour（Envoy）、Traefik、Istio Gateway（服务网格那层）
       部署形态：Deployment（2 副本高可用）+ Service(LoadBalancer/NodePort) 对外暴露 80/443
       工作原理：把 Ingress YAML 转成 Nginx.conf 或者 Envoy Route
            │
            ▼ 7 层路由匹配完成后，反代到后端
  [③ Service ClusterIP（每个后端服务 1 个）]
       user-service ClusterIP 10.96.x.y:80  → kube-proxy iptables → DNAT 到 user Pod IP:8080
```

#### 生产环境完整的外部 → Pod 流量 7 跳链路

把前面讲过的所有东西串起来，一个用户访问 `https://api.example.com/user/123`，数据包在 K8s 里完整走 7 步：

```
用户浏览器 172.16.5.88 → https://api.example.com
   │ ① DNS 解析 api.example.com → 云 LB VIP（47.x.x.x）
   ▼
云厂商 SLB（LoadBalancer Service 申请的 VIP）
   │ ② SLB 四层转发 → 选一个健康的 Node：Worker1=10.0.0.11 的 NodePort 30443
   ▼
Worker1 内核 iptables（NodePort 规则，kube-proxy 写的）
   │ ③ NodePort DNAT → 把 dst=10.0.0.11:30443 改成 Ingress Controller Pod IP:8443
   ▼
Ingress Controller Pod（Nginx/Contour + Envoy，部署在任意 Worker 上）
   │ ④ 7 层解析：Host=api.example.com, Path=/user/** → 反代到 user-service ClusterIP:80
   ▼
Worker1 内核 iptables（ClusterIP 规则）
   │ ⑤ ClusterIP DNAT → 从 user-service 3 个 Endpoint 里挑一个（比如 10.244.1.5:8080）
   ▼
user-service Pod（目标 Pod）
   │ ⑥ Pod 里的 SpringBoot/Tomcat 处理请求：GET /user/123 → 生成 JSON 响应
   ▼ 返回
原路返回：NIC 出口 → conntrack 自动反向 SNAT（把 src=10.244.1.5:8080 改回 ClusterIP:80 → 再改回 NodeIP → SLB → 浏览器）
```

> 🚨 新手容易踩的 4 个 Ingress 坑：
> 1. **只写了 Ingress YAML，没装 Ingress Controller** → Ingress 永远不生效，IP 永远是 `<pending>`。
> 2. **把 Ingress 的 backend.service.port 写错**：写的是 Service 的 port（Service spec.ports 里定义的那个 80），不是 Pod 的 8080！
> 3. **Ingress Controller 放在了公网 SLB 后面，但健康检查配错** → 404/502/504。
> 4. **HTTPS 证书没配 tls.secretName** → 浏览器报证书错误（cert-manager + Let's Encrypt 一节已经讲过，用 cert-manager 自动签发）。

---

### 6.10 CoreDNS —— 集群 DNS（串联 Service 与 Ingress 的关键）

**作用**：K8s 内置的服务发现 DNS，让你能用域名访问 Service，不用记 10.96.x.y 这种 ClusterIP。

**Service DNS 格式（3 种长度都能用）**：
```
# 同命名空间下（都在 default）：
<service-name>                         →  例：user-service:80 （最短，最常用）

# 跨命名空间（比如 monitoring 命名空间下的 prometheus）：
<service-name>.<namespace>             →  例：prometheus.monitoring:9090

# 全限定 FQDN：
<service-name>.<namespace>.svc.cluster.local
           user-service.default.svc.cluster.local  → 解析为 user-service ClusterIP
```

**Pod DNS 配置（自动注入，不需要你手动配）**：
每个 Pod 启动时，kubelet 自动写入 `/etc/resolv.conf`：
```
nameserver 10.96.0.10        ← CoreDNS 的 ClusterIP（固定！集群里所有 Pod DNS 都指向它）
search default.svc.cluster.local svc.cluster.local cluster.local
options ndots:5
```
→ 这就是为什么你在 Pod 里直接 `curl user-service` 能通的原因。

> 🎯 **SpringCloud 提示**：这相当于 K8s 内置的「服务注册与发现」基础能力。配合 Service 的负载均衡你可以不再依赖 Eureka/Nacos（当然也可以继续用 Nacos 做配置中心 + 灰度，见第 11 章两种迁移策略）。

---

### 6.11 四种通信场景及原理（补全 Service + Ingress）

| 场景 | 发起方 | 目标地址 | 通信路径 | 关键机制 |
|------|--------|----------|----------|----------|
| **同 Pod 内容器** | 容器 A（Sidecar）| 127.0.0.1:8080 | 本机 lo 回环 | 共享 Network Namespace（istio-proxy 这种 Sidecar 就是靠这个劫持业务流量）|
| **同 Node 上 Pod 间** | Pod 1（10.244.1.2）| Pod 2 IP（10.244.1.3:8080）| CNI 网桥 cni0 二层转发 | 不需要过 Node 路由，网桥直接转，最快 |
| **跨 Node Pod 间** | Pod 1（Worker1 10.244.1.2）| Pod 3 IP（Worker2 10.244.2.5:8080）| Pod 1 → cni0 → VXLAN 封包（Flannel VNI=1）→ eth0 → 物理交换 → Worker2 eth0 → VXLAN 解包 → cni0 → Pod3 | CNI Overlay（VxLAN/Geneve）或 Underlay（Calico BGP 直接路由）|
| **Pod 调微服务内部 Service**（最常见！）| order Pod → `http://user-service:80/user/1` | ① CoreDNS 解析 user-service → ClusterIP 10.96.124.37；② 发 TCP 包到 10.96.124.37:80 | ① DNS → ② 本 Node iptables nat 表 KUBE-SVC 链匹配 → DNAT 到 user Pod IP:8080 → ③ CNI 发过去 | CoreDNS + kube-proxy（iptables/ipvs）+ conntrack，**SpringCloud 用得最多的一条链路** |
| **集群外访问 NodePort** | 用户 172.16.5.88 → 10.0.0.11:30080 | Node 真实 IP + 30000-32767 的 NodePort | Worker1 内核 iptables KUBE-NODEPORTS 链匹配 → DNAT → ClusterIP → 再 DNAT → Pod IP | kube-proxy 写的 NodePort 规则；如果 ExternalTrafficPolicy=Cluster 还会再做一层 SNAT 可能导致看不到客户端真实 IP |
| **集群外访问 HTTP（Ingress 正式入口）** | 用户 → https://api.example.com/order | 云 SLB VIP → NodePort 30443 → Ingress Controller Pod | 完整 7 跳见 6.9 节 | SLB → Ingress Controller 7 层路由 → Service ClusterIP → Pod IP |

---

### 6.12 一张总复习表：所有 IP / 端口 / 名词大汇总

| 名词 | 属于哪个网段？| 是真实 IP 吗？| 谁来创建/维护？| 小白一句话 |
|------|-------------|-------------|----------------|----------|
| Node IP | Node 网段（①）| ✅ 真实（eth0）| 你公司网络/云 VPC | ssh 登录 Node 用的那个 IP |
| Pod IP | Pod 网段（②）| ✅ 真实（Pod eth0）| CNI + kube-controller-manager NodeCIDR 分配 | 每个容器的 IP，容器间互相访问用 |
| ClusterIP | Service 网段（③）| ❌ 虚拟，只在 iptables 里 | API Server + kube-proxy | **内部微服务固定 VIP**，ping 不通，tcp/udp 端口通 |
| Service Endpoints / EndpointSlice | 不属于任何网段（它是 Pod IP:Port 列表）| ✅ 真实的后端列表 | Endpoint Controller 根据 Selector 自动生成 | Service 的「后端服务器列表清单」 |
| NodePort | Node 的所有网卡（开一个端口）| ✅ 真实端口（默认 30000+）| kube-proxy 开 socket + iptables 规则 | 每个 Node 上都有一个相同的端口，可以直接用 NodeIP:30xxx 访问 |
| LoadBalancer VIP | 云厂商 SLB 的公网/私网 IP | ✅ 真实 IP（云上的）| cloud-controller-manager 调用云 API 申请 | 公网入口 IP，DNS A 记录就写它 |
| Ingress 规则 | 不是 IP（只是规则）| ❌ 虚拟 | 开发者写 YAML | HTTP/HTTPS 的 7 层路由规则 |
| Ingress Controller Pod IP | Pod 网段（②）| ✅ 真实（Pod）| Ingress Controller DaemonSet/Deployment | **真正运行的 Nginx/Envoy**，做反向代理和 TLS 卸载 |
| CoreDNS ClusterIP 10.96.0.10 | Service 网段（③）| ❌ 虚拟 | kubeadm 默认固定这个 IP | 所有 Pod 的 DNS 服务器 |

---

## 七、存储基础设施原理

### 7.1 容器存储的问题

容器文件系统是**临时**的：
- 容器销毁后，写入容器内的数据全部丢失
- 容器重建后数据无法保留
- 多个副本之间无法共享数据

### 7.2 PV / PVC 体系

K8s 通过「生产者-消费者」模型解耦存储供应与使用。

```
管理员（运维）              开发者（应用）
      │                        │
      ▼                        ▼
创建 PV (PersistentVolume)  创建 PVC (PersistentVolumeClaim)
  - 定义存储大小              - 声明需要多少存储
  - 定义存储类型              - 声明访问模式
  - 对接后端存储(NFS/云盘)    - 由 K8s 自动匹配合适的 PV
      │                        │
      └───────────┬────────────┘
                  ▼
           自动绑定（Binding）
                  │
                  ▼
          Pod 通过 volumeMounts 挂载使用
```

**PV 示例（管理员创建）**：
```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: pv-nfs-001
spec:
  capacity:
    storage: 5Gi
  accessModes:
    - ReadWriteOnce   # 单节点读写
  persistentVolumeReclaimPolicy: Retain  # 删除 PVC 后保留数据
  nfs:
    path: /data/share
    server: 10.0.0.100
```

**PVC 示例（开发者声明）**：
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: mysql-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 5Gi
```

**Pod 中挂载**：
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: mysql
spec:
  containers:
  - name: mysql
    image: mysql:5.7
    volumeMounts:
    - name: data
      mountPath: /var/lib/mysql    # 容器内挂载路径
  volumes:
  - name: data
    persistentVolumeClaim:
      claimName: mysql-pvc        # 引用 PVC
```

### 7.3 StorageClass —— 动态供应

PV 手动创建很麻烦，StorageClass 可以**按需自动创建 PV**。

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: nfs-storage
provisioner: k8s-sigs.io/nfs-subdir-external-provisioner
parameters:
  archiveOnDelete: "false"
```

**使用方式**：PVC 中指定 `storageClassName: nfs-storage` 即可自动创建 PV。

### 7.4 访问模式说明

| 模式 | 说明 |
|------|------|
| `ReadWriteOnce (RWO)` | 只能被一个节点以读写方式挂载（最常用） |
| `ReadOnlyMany (ROX)` | 可被多个节点只读挂载 |
| `ReadWriteMany (RWX)` | 可被多个节点读写挂载（需存储支持，如 NFS、CephFS）|
| `ReadWriteOncePod (RWOP)` | 只能被单个 Pod 读写挂载（K8s 1.22+）|

### 7.5 常见存储后端

| 存储类型 | 后端 | 适用场景 |
|----------|------|----------|
| 文件存储 | NFS、CephFS、GlusterFS、NAS | RWX 共享文件、日志、附件 |
| 块存储 | Cinder、Ceph RBD、云盘、本地磁盘 | 数据库（MySQL/ES）、高性能 |
| 对象存储 | S3、MinIO、OSS | 图片、视频、备份等海量文件 |

> 🎯 **SpringCloud 提示**：MySQL、Redis、RabbitMQ、Nacos 这些有状态的中间件，迁移时一定要配置 PVC 做数据持久化，否则 Pod 重启数据全丢。

---

## 八、调度原理与资源管理

### 8.1 资源模型：Request 与 Limit

```yaml
resources:
  requests:      # 「申请量」—— 调度依据
    cpu: "500m"  # 0.5 核，m = milli-core（千分之一核）
    memory: "512Mi"
  limits:        # 「上限」—— 运行时约束
    cpu: "2000m"
    memory: "2Gi"
```

| 字段 | 作用 | 超过时的行为 |
|------|------|--------------|
| **requests** | 调度时用：Node 剩余可分配资源 ≥ requests 的 Pod 才能被调度上去 | 不限制运行时 |
| **limits.cpu** | CPU 运行上限 | 限流（Throttling），Pod 变慢但不 kill |
| **limits.memory** | 内存运行上限 | OOMKilled（容器被 Kill，然后重启）|

> ⚠️ **大坑提示**：内存 Limit 超限会 OOMKill 容器！生产环境一定要做好压测设置合理值。

### 8.2 节点资源可分配公式

```
节点总资源
──────────────────────────────────────────────
  操作系统 + 系统进程占用（如 dockerd, kubelet）
  K8s 组件预留（kube-reserved）
  驱逐阈值（eviction-threshold，防止磁盘/内存耗尽）
──────────────────────────────────────────────
= 可分配资源（Allocatable）→ 供 Pod 的 requests 使用
```

### 8.3 高级调度机制

| 机制 | 作用 | 典型场景 |
|------|------|----------|
| **nodeSelector** | Pod 只能调度到指定标签的节点 | 把有状态服务调度到 SSD 节点 |
| **Node Affinity** | 软/硬亲和，更灵活的节点选择 | 尽量把 Pod 分散到不同机架 |
| **Pod Affinity/AntiAffinity** | Pod 之间的亲和/反亲和 | 把 Web 和 Redis 调度到一起（低延迟），或把副本分散（高可用）|
| **Taint / Toleration** | 节点排斥策略，只有容忍的 Pod 才能上 | GPU 节点只跑 AI 任务，Master 节点禁止业务 Pod |
| **PriorityClass** | 优先级，高优 Pod 可以抢占低优 Pod | 核心服务优先级 > 离线任务 |

---

## 九、服务发现与负载均衡

### 9.1 集群内服务发现完整流程

以 `order-service` 调用 `user-service` 为例：

```
┌──────────────────────────────────────────────────────────────┐
│  order-service Pod (10.244.1.5)                               │
│    │                                                           │
│    │  发起 HTTP 请求: http://user-service:80/getUser?id=1     │
│    ▼                                                           │
│  ① 解析域名 user-service ───► CoreDNS                          │
│    │  返回 ClusterIP: 10.96.0.100                              │
│    ▼                                                           │
│  ② 发起到 10.96.0.100:80 的请求                                │
│    │                                                           │
│    ▼                                                           │
│  ③ 本机 iptables/ipvs 规则 (由 kube-proxy 维护)                │
│    │  DNAT: 10.96.0.100:80  ───► 10.244.2.7:8080              │
│    │                        ───► 10.244.1.8:8080  (随机选一个)│
│    │                                                           │
│    ▼                                                           │
│  ④ 数据包从 Pod 网络发出，通过 CNI 到达对端 Pod                  │
└──────────────────────────────────────────────────────────────┘
```

### 9.2 负载均衡层级

| 层级 | 发生在哪里 | 负载均衡方式 |
|------|-----------|-------------|
| **Layer 4 (TCP/UDP)** | kube-proxy (iptables/ipvs) | 随机或轮询（ipvs 支持更多算法）|
| **Layer 7 (HTTP/HTTPS)** | Ingress Controller (Nginx/Traefik) | 按路径/域名路由、权重、会话保持 |
| **East-West 服务网格** | Istio/Linkerd (Sidecar) | 金丝雀、熔断、重试、可观测性 |

---

## 十、配置与密钥管理

### 10.1 ConfigMap —— 非敏感配置

**作用**：把配置从镜像中抽离，避免配置变更需要重新构建镜像。

**创建方式 1：from-file**
```bash
kubectl create configmap app-config --from-file=application.properties
```

**创建方式 2：YAML 定义**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: user-service-config
data:
  SPRING_PROFILES_ACTIVE: "prod"
  JAVA_OPTS: "-Xms512m -Xmx2048m"
  application.properties: |
    server.port=8080
    spring.datasource.url=jdbc:mysql://mysql:3306/user_db
    logging.level.root=INFO
```

**Pod 中使用：挂载为文件**
```yaml
containers:
- name: user-service
  image: user-service:1.0
  volumeMounts:
  - name: config
    mountPath: /app/config     # 挂载成文件
volumes:
- name: config
  configMap:
    name: user-service-config
```

**Pod 中使用：注入为环境变量**
```yaml
containers:
- name: user-service
  env:
  - name: SPRING_PROFILES_ACTIVE
    valueFrom:
      configMapKeyRef:
        name: user-service-config
        key: SPRING_PROFILES_ACTIVE
```

### 10.2 Secret —— 敏感信息（密码/Token/证书）

**与 ConfigMap 的区别**：
- 以 Base64 编码存储（不是加密，但至少不是明文）
- 按需分发，kubelet 不会把 Secret 写入磁盘（tmpfs）
- 可以配合 RBAC 限制谁能读

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
type: Opaque
data:
  username: cm9vdA==               # root 的 base64
  password: TXlQYXNzQDEyMw==       # MyPass@123 的 base64
```

**使用方式与 ConfigMap 完全相同**：`secretKeyRef` 注入环境变量，或 `volumes.secret` 挂载文件。

> 🎯 **SpringCloud 提示**：原来 application.yml 里的数据库密码、JWT 密钥，一律放到 Secret 里；其他配置放 ConfigMap。ConfigMap 更新后挂载模式自动生效（需应用监听），环境变量方式需重启 Pod。

---

## 十一、SpringCloud 迁移对应关系

这一章是你最关心的：SpringCloud 的组件在 K8s 里怎么对应？

### 11.1 功能对应总表

| SpringCloud 功能 | 对应 K8s 原生能力 | 是否可替代 Eureka/Nacos |
|------------------|-------------------|-----------------------|
| **服务注册** | Pod 启动后被 Service 自动关联（通过 Label） | ✅ 是 |
| **服务发现** | CoreDNS + Service 域名（如 `user-service:80`） | ✅ 是 |
| **客户端负载均衡（Ribbon/LoadBalancer）** | kube-proxy 的 Service 负载均衡 | ✅ 是（但原理不同）|
| **配置中心（Config Server / Nacos Config）** | ConfigMap + Secret | ⚠️ 部分替代（无动态推送）|
| **网关（Gateway / Zuul）** | Ingress + Ingress Controller | ✅ 基本可替代 |
| **熔断降级（Hystrix / Sentinel）** | 需要服务网格 Istio 或继续用 Sentinel | ❌ K8s 原生没有 |
| **链路追踪（Sleuth/Zipkin）** | 继续用 Zipkin/Jaeger，或接入服务网格 | ❌ 与 K8s 解耦 |
| **限流（Sentinel Gateway）** | Ingress Controller 限流注解 / Istio | ⚠️ 部分替代 |

### 11.2 两种迁移策略对比

```
┌────────────────────────────────────────────────────────────────┐
│ 策略 A：渐进式（推荐新手）                                       │
│   - 保留 Nacos/Eureka，所有服务注册到 Nacos 不变                 │
│   - Config 也继续用 Nacos Config                                 │
│   - 先把服务容器化，一个个部署到 K8s 作为 Pod 运行                │
│   - 服务之间通过 Nacos 寻址 + Pod IP 直连                        │
│   - 等完全跑稳后，再逐步把 Nacos 的能力切换到 K8s 原生            │
│                                                                  │
│ 策略 B：全面拥抱 K8s 原生（进阶）                                 │
│   - 用 Service + CoreDNS 做服务发现，移除 Nacos/Eureka           │
│   - 用 ConfigMap + Secret 做配置，移除 Nacos Config              │
│   - 用 Ingress 做统一网关，移除 SpringCloud Gateway（可选）      │
│   - 引入 Istio 做熔断/限流/灰度，替代 Sentinel                   │
│   - 适合 K8s 经验成熟、希望减少技术栈复杂度的团队                │
└────────────────────────────────────────────────────────────────┘
```

### 11.3 关键迁移细节详解

#### （1）服务发现：Nacos → K8s Service

```
SpringCloud 模式:                           K8s 原生模式:
┌──────────────┐                           ┌──────────────┐
│  User Svc    │──register──►               │  User Svc    │
│  (Pod x3)    │                            │  (Pod x3)    │
└──────┬───────┘   Eureka/Nacos             └──────┬───────┘
       │               ▲                            │
       │  find         │                            │ Label
       │               │                            ▼
       │        ┌──────┴──────┐              ┌──────────────┐
       └───────►│  Order Svc  │              │   Service    │
                └─────────────┘              │ (ClusterIP)  │
                                             └──────┬───────┘
                                                    │ CoreDNS
                                                    ▼
                                             ┌──────────────┐
                                             │  Order Svc   │
                                             │    通过域名   │
                                             └──────────────┘
```

**代码改造**：
- 原代码：`restTemplate.getForObject("http://user-service/getUser", ...)`
- K8s 原生：**完全一样**，因为 Ribbon/LoadBalancer 会把 `user-service` 通过 CoreDNS 解析

#### （2）客户端负载均衡的区别

| 维度 | Ribbon（客户端） | K8s Service（服务端） |
|------|-----------------|----------------------|
| 负载均衡发生在 | 调用方服务内部 | kube-proxy（内核层）|
| 感知新实例速度 | 心跳周期（30s内） | Pod Ready 后立即生效 |
| 算法 | 轮询/随机/权重等 | iptables=随机, ipvs=支持轮询/最少连接等 |
| 重试/熔断 | 有（Ribbon/Hystrix）| 无，需在应用层或 Istio 实现 |

> 💡 **建议**：可以保留 Ribbon（SpringCloud LoadBalancer）做重试策略，底层用 K8s Service 做实际转发——不冲突。

#### （3）配置中心：Nacos Config → ConfigMap/Secret

| 特性 | Nacos Config | ConfigMap + Secret |
|------|-------------|-------------------|
| 动态推送 | ✅ 实时推送 | ⚠️ 挂载文件方式 ~1分钟生效；环境变量需重启 Pod |
| 版本管理 | ✅ 历史版本/回滚 | ❌ 原生不支持（需配合 GitOps/ArgoCD） |
| 配置加密 | ✅ 加密配置 | ⚠️ Secret 仅 Base64，需 KMS 加密或 SealedSecret |
| 管理界面 | ✅ Web 控制台 | ❌ 需 K8s Dashboard/自建平台 |
| 灰度/环境隔离 | ✅ 多 DataId / Group | ⚠️ 按 Namespace + 不同 ConfigMap 区分 |

> 💡 **建议**：生产环境如果依赖动态刷新，建议**保留 Nacos Config**；否则 ConfigMap 足够简单。

#### （4）网关：Gateway + Nacos → Ingress

| 场景 | 推荐方案 |
|------|---------|
| **对外的统一入口** | 用 Ingress (Nginx Controller) 作为南北流量网关 |
| **微服务间调用** | 继续用 SpringCloud Gateway 做内部复杂路由/过滤（可选）|
| **两者关系** | Ingress 在更外层，流量顺序：Client → Ingress → Gateway → Service → Pod |

---

## 十二、迁移实践建议

### 12.1 迁移路线图（建议按顺序）

```
阶段 1：基础建设（1-2 周）
  ├─ 搭建 K8s 集群（3 Master + N Worker，推荐用 kubeadm 或托管 K8s）
  ├─ 部署监控：Prometheus + Grafana + AlertManager
  ├─ 部署日志：ELK 或 Loki
  ├─ 部署 Ingress Controller（Nginx）
  └─ 配置存储：StorageClass（NFS/云盘/Ceph）

阶段 2：中间件容器化（1-2 周）
  ├─ MySQL（用 StatefulSet + PVC）
  ├─ Redis（主从或 Cluster）
  ├─ Nacos/Eureka（保留注册和配置中心）
  ├─ RabbitMQ/Kafka
  └─ 验证数据持久化 ✓

阶段 3：边缘服务迁移（1-2 周）
  ├─ 把流量小的非核心服务先容器化（写 Dockerfile → 推镜像仓库）
  ├─ 编写 Deployment + Service + ConfigMap + Secret YAML
  ├─ 部署到 K8s，联调数据库、服务注册
  └─ 验证：监控指标、日志、链路追踪

阶段 4：核心服务迁移（2-4 周）
  ├─ 逐个迁移核心微服务
  ├─ 建议用蓝绿发布：新的 K8s 实例和旧的 VM 实例同时存在
  ├─ 流量逐步切到 K8s（通过 Nacos 权重或 Ingress 权重）
  └─ 监控 1-2 周无问题再下线 VM 实例

阶段 5：深度融合（持续）
  ├─ 引入 HPA（基于 CPU/内存自动扩缩容）
  ├─ 引入 Istio（可选，解决熔断、灰度、流量镜像）
  ├─ 引入 ArgoCD（GitOps，部署流程升级）
  └─ 评估是否下掉 Nacos/Eureka，全面用 K8s 原生
```

### 12.2 常见踩坑清单

| # | 坑点 | 说明 | 规避建议 |
|---|------|------|---------|
| 1 | **没有设置 requests/limits** | Pod 被驱逐、Node 资源耗尽 | 每个容器必须设置 requests+limits |
| 2 | **用了 latest 镜像标签** | 你以为是新版本，实际节点缓存了旧镜像 | 用版本号如 `user-service:v1.2.3` |
| 3 | **忘记配置 Readiness 探针** | Pod 还没启动完就被打入流量导致 502 | 配置 HTTP 探针（如 `/actuator/health`） |
| 4 | **Liveness 探针太激进** | 应用启动慢时被反复重启 | 加 `initialDelaySeconds: 60` |
| 5 | **数据库用 Deployment** | 重建后数据丢失（没挂 PVC） | 有状态服务用 StatefulSet + PVC |
| 6 | **HostPort 冲突** | 多个 Pod 想占同一个 Node 端口 | 用 Service 不要用 HostPort |
| 7 | **时间不同步** | Pod 内时间和实际不一致 | 挂载宿主机 `/etc/localtime` |
| 8 | **跨 namespace Service 访问** | 只写了 Service 名没加 namespace | 用完整域名 `svc.ns.svc.cluster.local` |
| 9 | **没有 PodDisruptionBudget** | 节点维护时所有副本被赶走，服务中断 | 设置 PDB，保证最少 N 个副本存活 |
| 10| **镜像拉不到（私有仓库）** | K8s 没配置镜像仓库凭据 | 配置 `imagePullSecrets` 到 ServiceAccount |

### 12.3 第一个 SpringBoot 服务的 K8s 部署示例

**Dockerfile 模板：**
```dockerfile
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY target/user-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**Deployment + Service + ConfigMap + HPA 全在一个 YAML：**

```yaml
# ========== ConfigMap ==========
apiVersion: v1
kind: ConfigMap
metadata:
  name: user-service
  namespace: prod
data:
  JAVA_OPTS: "-Xms512m -Xmx1024m -XX:+UseG1GC"
  SPRING_PROFILES_ACTIVE: "prod"

---
# ========== Deployment ==========
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
  namespace: prod
  labels:
    app: user-service
spec:
  replicas: 3
  selector:
    matchLabels:
      app: user-service
  template:
    metadata:
      labels:
        app: user-service
    spec:
      containers:
      - name: user-service
        image: registry.example.com/biz/user-service:1.0.0
        imagePullPolicy: IfNotPresent
        ports:
        - name: http
          containerPort: 8080
        env:
        - name: JAVA_OPTS
          valueFrom:
            configMapKeyRef:
              name: user-service
              key: JAVA_OPTS
        - name: SPRING_PROFILES_ACTIVE
          valueFrom:
            configMapKeyRef:
              name: user-service
              key: SPRING_PROFILES_ACTIVE
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: mysql-secret
              key: password
        resources:
          requests:
            cpu: "500m"
            memory: "512Mi"
          limits:
            cpu: "2000m"
            memory: "1Gi"
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          failureThreshold: 3
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 20
          failureThreshold: 3
        volumeMounts:
        - name: timezone
          mountPath: /etc/localtime
          readOnly: true
      volumes:
      - name: timezone
        hostPath:
          path: /usr/share/zoneinfo/Asia/Shanghai

---
# ========== Service ==========
apiVersion: v1
kind: Service
metadata:
  name: user-service
  namespace: prod
spec:
  selector:
    app: user-service
  ports:
  - name: http
    port: 80
    targetPort: 8080
  type: ClusterIP

---
# ========== HPA 自动扩缩容 ==========
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: user-service
  namespace: prod
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: user-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 60    # CPU 使用率超过 60% 就扩容
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 70    # 内存超过 70% 也扩容
```

### 12.4 推荐的 YAML 目录结构

```
k8s-manifests/
├── namespaces/
│   └── prod.yaml
├── configs/
│   ├── user-service-configmap.yaml
│   └── mysql-secret.yaml            # ⚠️ 不要提交到 Git！
├── middleware/
│   ├── mysql/
│   │   ├── statefulset.yaml
│   │   ├── service.yaml
│   │   └── pvc.yaml
│   └── nacos/
├── services/
│   ├── user-service/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── hpa.yaml
│   ├── order-service/
│   └── gateway/
├── ingress/
│   └── prod-ingress.yaml
└── kustomization.yaml                # 可选：kustomize 统一管理
```

---

## 十三、K8s 通信协议剖析（REST vs gRPC 澄清）

> 针对新手常见疑问：「K8s API 是不是改成 gRPC 了？」——答案是**分层而异**，不能一概而论。这一章系统拆解 K8s 全栈各层调用链上到底用了什么协议。

### 13.1 结论速览（一句话版）

| 调用层级 | 协议 | 举例 |
|----------|------|------|
| **用户/客户端 → API Server**（你写代码调 K8s）| ✅ **HTTP/1.1 + HTTP/2 RESTful**，未改 gRPC | `kubectl apply`、client-go、Dashboard、Spring Cloud Kubernetes |
| **标准插件接口**（CRI/CSI/Device Plugin 等）| ✅ **几乎全部 gRPC** | kubelet 调 containerd、kubelet 调 Ceph CSI 驱动 |
| **API Server → etcd** | ✅ **gRPC**（etcd v3 API 原生 gRPC）| 读写 K8s 资源到 etcd |
| **控制平面内部互调**（Scheduler/Controller/kubelet → API Server）| ✅ **HTTP REST + JSON/Protobuf**，均通过 API Server 中转，不直连 | Scheduler 调 Binding、Controller List+Watch |
| **CNI 网络插件接口** | ❌ **例外**：用「exec 二进制 + stdin JSON」模式，非 gRPC（新版社区仍在推进 gRPC 化）| calico/flannel CNI 二进制 |
| **API Server → Admission Webhook** | ✅ **HTTP POST JSON**（调用用户自定义服务）| OPA Gatekeeper、自定义准入控制器 |

> 💡 **记忆口诀**：**对外 HTTP，对内插件 gRPC，组件之间全走 API Server 的 HTTP**

---

### 13.2 对外 API（客户端 → API Server）：仍是 HTTP REST

你日常使用的全部接口，**没有改成 gRPC**，走的都是 RESTful HTTP：

| 项目 | 现状 |
|------|------|
| 传输协议 | HTTP/1.1（默认）+ HTTP/2（K8s 1.30+ 默认开启）|
| 序列化 1（99% 场景） | `Content-Type: application/json`，直接 `curl` 可调用 |
| 序列化 2（性能场景） | `application/vnd.kubernetes.protobuf`，**HTTP Body 用 Protobuf，不是 gRPC** |
| URL 风格 | 标准 REST 动词：`GET /api/v1/namespaces/default/pods`、`PUT /apis/apps/v1/deployments/xxx` |
| Watch 订阅 | HTTP Chunked Streaming 长连接（分块传输，不是 gRPC Stream）|
| exec/logs/port-forward | HTTP Upgrade 到 WebSocket 或 SPDY |

**代码示例：直接 curl 调 K8s API（走 HTTP JSON，和 gRPC 无关）**
```bash
# 1. 先起本地代理，绕过 TLS 认证
kubectl proxy --port=8080 &

# 2. 直接用 curl 调（就是标准 HTTP/JSON）
curl http://127.0.0.1:8080/api/v1/namespaces/default/pods
# 返回标准 JSON：
# {
#   "kind": "PodList",
#   "apiVersion": "v1",
#   "items": [ ... ]
# }
```

#### 为什么用户层不改成 gRPC？
- **生态兼容性**：HTTP/JSON 任何语言、任何工具（curl/Postman/WASM/浏览器）零门槛
- **历史包袱**：百万级应用依赖，大改等于整个生态重写
- **社区方案未统一**：2020-2022 有过 gRPC Gateway 探索（内部 gRPC + 自动 HTTP/JSON 网关），但未被主分支接受

---

### 13.3 标准插件接口：**几乎全部 gRPC**

CNCF 定义的三大插件接口（CRI / CSI / CNI），除了最古老的 CNI 之外，其余**全部设计为 gRPC**。这是你听到「K8s 改成 gRPC」最多的来源。

| 接口 | 全称 | gRPC 化时间 | 作用 | 调用链 |
|------|------|------------|------|--------|
| **CRI** | Container Runtime Interface | K8s 1.5（2016，最早 gRPC 化） | kubelet ↔ 容器运行时，创建/启动/删除容器、拉镜像 | `kubelet` —（gRPC /run/containerd/containerd.sock）→ `containerd` |
| **CSI** | Container Storage Interface | K8s 1.9 设计 / 1.13 GA（2018）| K8s ↔ 存储驱动（Ceph/云盘/NFS），CreateVolume、MountVolume、扩容、快照 | K8s external-provisioner / external-attacher 组件 —（gRPC Unix Socket）→ CSI 驱动（rbdplugin/cinder-csi 等）|
| **Device Plugin API** | 设备插件接口 | K8s 1.10（2018）| kubelet ↔ GPU/FPGA/SR-IOV 等硬件设备发现和分配 | nvidia-device-plugin —（gRPC）→ kubelet |
| **Pod Resources API** | Pod 资源查询 | K8s 1.15 | kubelet 对外暴露「某 Pod 分到哪些 CPU/Device/GPU」，给调度器扩展、监控用 | Prometheus nvidia_gpu_exporter —（gRPC）→ kubelet |
| **DRS API** | Dynamic Resource Allocation | K8s 1.26 Beta | 任意非整数资源（如 FPGA 核数、复杂设备）的动态分配 | DRA Controller ↔ CDI 驱动 |
| **CNI** | Container Network Interface | **未 gRPC 化（例外）** | kubelet 调 CNI 插件配置 Pod 网络 | kubelet —（execve 执行二进制 + stdin JSON 配置）→ calico/flannel 二进制 |

> 📌 **CRI 实战验证**（你可以在任意 Node 上执行以下命令，看到 kubelet 连接 containerd 的 gRPC Unix Socket）：
```bash
ss -lx | grep containerd
# /run/containerd/containerd.sock   ← CRI 的 gRPC Unix 套接字
ctr --address /run/containerd/containerd.sock containers ls  # containerd 自带 CLI，底层走 gRPC
```

---

### 13.4 控制平面内部调用全景图

```
┌────────────────────────────────────────────────────────────────────┐
│                     Control Plane（Master）                         │
│                                                                    │
│  ┌──────────────┐  gRPC (etcd v3 API)   ┌──────────────┐           │
│  │  API Server  │◄──────────────────────►│    etcd      │           │
│  │              │   HTTP/2 + Protobuf   │  (Raft 集群) │           │
│  └───────┬──────┘                       └──────────────┘           │
│          │                                                           │
│          │ HTTP REST (JSON / Protobuf body)                         │
│          │                                                           │
│    ┌─────┴──────┬──────────────────────┐                             │
│    ▼            ▼                      ▼                             │
│ ┌────────┐ ┌──────────────────┐  ┌────────────┐                     │
│ │ Sched  │ │ CtrlMgr          │  │ Cloud Ctrl │   （Scheduler /     │
│ │        │ │ Node/Deploy/RS…  │  │   Manager  │    CtrlMgr 都不     │
│ └────────┘ └──────────────────┘  └────────────┘    直接 gRPC 互访） │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
          ▲
          │ HTTP REST (kubelet上报 NodeLease、Pod 状态 Patch…)
          │
┌─────────┴─────────────────────────────────────────────────────────┐
│   Worker Node（工作节点）                                           │
│   ┌───────────┐    gRPC (CRI Socket)    ┌──────────────┐         │
│   │ kubelet   │◄─────────────────────────►  containerd   │        │
│   │           │    gRPC (DevicePlugin)    └──────────────┘         │
│   │           │◄───────────► nvidia / sriov  device-plugin         │
│   │           │                                                      │
│   │           │    exec + JSON (CNI, 非 gRPC)                       │
│   │           └───────────────────► calico / flannel CNI 二进制   │
│   └───────────┘                                                      │
└────────────────────────────────────────────────────────────────────┘
```

**各关键链路的具体协议**：

| # | 调用关系 | 协议 | 说明 |
|---|----------|------|------|
| 1 | Scheduler → API Server | HTTP REST + client-go Informer | Scheduler List+Watch 未调度 Pods，Binding Pod 到 Node，全通过标准 HTTP API |
| 2 | Controller Manager → API Server | HTTP REST | Node/Deployment/RS/Endpoint… 几十种 Controller 全走 List+Watch，K8s 设计要求**所有组件不直连，必经 API Server** |
| 3 | kubelet → API Server | HTTP REST | Node 心跳（NodeLease）、Pod 状态 Patch、events 上报 |
| 4 | API Server → etcd | **gRPC（etcd v3 Client）** | etcd v3 放弃了 v2 的 HTTP JSON 模式，强制 gRPC + HTTP/2；这就是为什么 K8s 1.6+ 只支持 etcd v3 |
| 5 | API Server → Admission Webhook | HTTP POST JSON | 调外部（比如 Gatekeeper）的 webhook，用户侧服务 HTTP 即可接入，不强制 gRPC |
| 6 | API Server → Aggregated APIServer（metrics-server 等） | HTTP REST Reverse Proxy | API Server 做反向代理，内部仍是 HTTP |
| 7 | kubelet → containerd | **gRPC（CRI 接口）** | RunPodSandbox、CreateContainer、StartContainer 全部 gRPC |
| 8 | kubelet → CSI Node Plugin | **gRPC（CSI NodeService）** | NodePublishVolume / NodeStageVolume |
| 9 | kubelet → CNI Plugin | **execve 进程 + JSON stdin/stdout** | CNI 最古老，不使用 gRPC；社区有 gRPC-CNI 提案但至今未主支 |

---

### 13.5 最容易误判为「已改 gRPC」的几个坑

#### 坑 1：看到「Protobuf」就以为是 gRPC
K8s 资源对象确实有 `.proto` 定义（`k8s.io/api/core/v1/generated.proto` 等），但**只用于 HTTP Body 的二进制序列化**，不是 gRPC。

| 协议对比 | HTTP + Protobuf Body | **gRPC** |
|----------|----------------------|----------|
| 传输层 | HTTP/1.1 或 HTTP/2 | 强制 HTTP/2 |
| 调用模式 | REST 动词 GET/PUT/POST/DELETE/PATCH | RPC `service` 定义：`CreatePod(Request) returns (Response)` |
| 多消息流 | HTTP Chunked（Watch）| 原生 4 种 Stream 模式（Unary/ClientStream/ServerStream/Bidi）|
| 状态码 | HTTP 200/404/500 | HTTP 200 + gRPC `Status.Code`（OK/NotFound/Internal…）|
| 是否能 curl | ✅ 能，加 header 即可 | ❌ 不能，必须用 gRPC 客户端 |

Go 代码区分示例：
```go
// HTTP + Protobuf Body（K8s 实际做法）
req.Header.Set("Accept", "application/vnd.kubernetes.protobuf") // 仍是 HTTP REST
resp, _ := httpClient.Do(req)

// 真正的 gRPC 调用
conn, _ := grpc.Dial("etcd:2379")                              // gRPC Dial
cli := pb.NewKVClient(conn)
cli.Put(ctx, &pb.PutRequest{Key: []byte("/pods/xxx"), Value: v}) // 真正的 gRPC 方法调用
```

#### 坑 2：看到 kube-proxy、kubelet 的 gRPC Server 就以为用户 API 也 gRPC
kubelet 开了很多 gRPC Server（CRI/DevicePlugin/PodResources），但这些是 Node 内部给插件用的，**用户不直接调用**，用户面对的还是 API Server 的 HTTP。

#### 坑 3：第三方 gRPC Gateway ≠ K8s 官方 API
社区有 `kubernetes-grpc-gateway`、`grpc-proxy` 等项目给 API Server 套一层 gRPC 网关，属于第三方扩展，不算 K8s 本身的变更。

---

### 13.6 对你 SpringCloud 迁移工作的实际影响

#### 日常 99% 的场景：完全不用关心 gRPC
只要你做的是：
- 写 YAML 调 kubectl apply
- 写 Java/C# 代码用 fabric8/kubernetes-client 访问 K8s
- 用 Spring Cloud Kubernetes 做服务发现/配置中心
- 写 Operator（client-go / kubebuilder / Java Operator SDK）

**你接触到的全是 HTTP/JSON，和 gRPC 不沾边。**

#### 需要了解/写 gRPC 的两种场景
| 场景 | 是否需要写 gRPC | 建议 |
|------|----------------|------|
| 自己写 CSI 存储插件 / CRI 运行时 / Device Plugin 设备插件 | ✅ 必须，官方接口定义就是 `.proto` | 直接用 protoc-gen-go-grpc 生成代码，套官方库 |
| 深度开发 kubelet / API Server / etcd Operator | ✅ 会用到 | 深入 K8s 源码时再看 |
| 调 CRI 接口排错（比如容器启动失败）| 不需要写，用现成工具 | `crictl`（CRI CLI 工具）底层就是 gRPC，直接 `crictl pods`、`crictl ps` |
| 调试 CSI 存储插件 | 不需要写，用现成工具 | `csi-sanity` 官方 E2E 测试工具 / `kubectl get csinode` |

#### 快速排错工具（底层全是 gRPC，你只管用 CLI）
```bash
# 查 CRI（gRPC 调 containerd）
crictl pods              # 列出 PodSandbox（等价 docker ps，但看 CRI 层）
crictl inspectp <podID>  # 查看 PodSandbox 启动失败原因

# 查 CSI（gRPC 调 CSI 驱动）
kubectl get csidrivers                 # 集群已注册的 CSI 驱动
kubectl describe csinode <node-name>   # 该 Node 上 CSI 插件安装情况
kubectl get volumeattachments          # 卷挂载进度（CSI Controller 写的对象）
```

---

## 结语

作为小白上手 K8s，建议遵循以下学习路径：

1. **先学会用**：学会写 Deployment/Service/ConfigMap 三种 YAML，能把服务跑起来
2. **理解原理**：Pod 网络怎么通的？Service 怎么转发的？——出了问题能排查
3. **生产化**：监控、日志、告警、灰度、自动扩缩容、备份容灾
4. **深度融合**：服务网格（Istio）、GitOps（ArgoCD）、平台化

SpringCloud 迁移的核心思路是：**不要一上来就全换**，先容器化跑稳，再根据团队 K8s 熟练度逐步替换掉 Nacos/Eureka 等组件。遇到任何问题，先看 Pod 状态（`kubectl describe pod`）和日志（`kubectl logs`），这是 K8s 排错的起点。
