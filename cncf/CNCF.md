# CNCF 关键项目全景详解

> 云原生计算基金会（Cloud Native Computing Foundation）核心项目深度指南，重点覆盖 K8s 相关、各大厂商都在用的基础设施组件。

---

## 目录

- [一、CNCF 是什么](#一cncf-是什么)
- [二、CNCF 全景图与成熟度分级](#二cncf-全景图与成熟度分级)
- [三、编排与调度层：Kubernetes & 周边](#三编排与调度层kubernetes--周边)
  - [3.1 Kubernetes（K8s）](#31-kubernetesk8s)
  - [3.2 etcd](#32-etcd)
  - [3.3 containerd](#33-containerd)
- [四、运行时与容器层](#四运行时与容器层)
  - [4.1 containerd / CRI-O](#41-containerd--cri-o)
  - [4.2 BuildKit](#42-buildkit)
  - [4.3 Harbor](#43-harbor)
  - [4.4 Distribution (Docker Registry)](#44-distribution-docker-registry)
- [五、CNI 网络层](#五cni-网络层)
  - [5.1 Cilium](#51-cilium)
  - [5.2 Calico](#52-calico)
  - [5.3 Flannel](#53-flannel)
- [六、可观测性层（Observability）](#六可观测性层observability)
  - [6.1 Prometheus](#61-prometheus)
  - [6.2 Grafana（非 CNCF，但是事实标准）](#62-grafana非-cncf但是事实标准)
  - [6.3 OpenTelemetry（OTel）](#63-opentelemetryotel)
  - [6.4 Jaeger](#64-jaeger)
  - [6.5 Fluentd & Fluent Bit](#65-fluentd--fluent-bit)
  - [6.6 Thanos / Cortex / Mimir](#66-thanos--cortex--mimir)
- [七、应用生命周期与 CI/CD](#七应用生命周期与-cicd)
  - [7.1 Helm](#71-helm)
  - [7.2 Argo CD（GitOps 标杆）](#72-argo-cdgitops-标杆)
  - [7.3 Argo Workflows](#73-argo-workflows)
  - [7.4 Tekton](#74-tekton)
  - [7.5 Kustomize](#75-kustomize)
- [八、服务网格与 API 网关](#八服务网格与-api-网关)
  - [8.1 Istio](#81-istio)
  - [8.2 Linkerd](#82-linkerd)
  - [8.3 Envoy](#83-envoy)
  - [8.4 Contour / Gateway API](#84-contour--gateway-api)
- [九、数据库与中间件（云原生化）](#九数据库与中间件云原生化)
  - [9.1 Vitess](#91-vitess)
  - [9.2 TiDB 生态](#92-tidb-生态)
  - [9.3 Operator 框架 & OperatorHub](#93-operator-框架--operatorhub)
- [十、安全与策略（Security & Policy）](#十安全与策略security--policy)
  - [10.1 OPA / Gatekeeper](#101-opa--gatekeeper)
  - [10.2 cert-manager](#102-cert-manager)
  - [10.3 Falco](#103-falco)
  - [10.4 SPIFFE / SPIRE](#104-spiffe--spire)
  - [10.5 Trivy](#105-trivy)
  - [10.6 Kyverno](#106-kyverno)
- [十一、存储层（Cloud Native Storage）](#十一存储层cloud-native-storage)
  - [11.1 Rook](#111-rook)
  - [11.2 Ceph + Rook 组合](#112-ceph--rook-组合)
  - [11.3 Longhorn](#113-longhorn)
  - [11.4 Container Storage Interface (CSI)](#114-container-storage-interface-csi)
- [十二、SpringCloud 迁移云原生的 CNCF 技术栈推荐](#十二springcloud-迁移云原生的-cncf-技术栈推荐)

---

## 一、CNCF 是什么

CNCF（Cloud Native Computing Foundation，**云原生计算基金会**）是 Linux 基金会旗下组织，2015 年由 Google 联合多家厂商发起，使命是「推动云原生技术的普及与可持续发展」。

### 1.1 CNCF 的核心作用

1. **中立托管**：把关键云原生项目放到中立基金会，避免被单一厂商控制（如 Google 把 K8s 捐给 CNCF）
2. **技术治理**：定义项目成熟度模型（毕业/孵化/沙盒），保证项目的社区健康度和可持续性
3. **生态规范**：制定接口标准（如 CRI、CNI、CSI、Service Mesh Interface、Gateway API）
4. **兼容性认证**：K8s 一致性认证（CKA/CKAD/CKS 考试、集群一致性认证）
5. **社区活动**：KubeCon 全球大会、Local Meetup

### 1.2 CNCF 项目成熟度模型

```
 Sandbox（沙盒）     早期创新项目，社区起步
       │   经过成熟度评审
       ▼
 Incubating（孵化中）  已具备生产可用性，有知名用户案例
       │   毕业评审：adoption数量、commit健康、治理规范
       ▼
 Graduated（毕业）     稳定可靠，广泛采用，生产级
```

**毕业项目数量（截至 2025 年）**：约 20+ 个，是真正「大厂都在用」的核心。

---

## 二、CNCF 全景图与成熟度分级

官方全景图（Landscape）在 landscape.cncf.io。按功能域划分：

| 功能域 | 毕业项目（广泛采用） | 孵化中项目（生产可用） | 核心选择建议 |
|--------|---------------------|----------------------|-------------|
| 编排调度 | **Kubernetes**、etcd | ✗ | 无可替代 |
| 容器运行时 | **containerd** | CRI-O | containerd 通用首选 |
| 镜像构建/分发 | Harbor、BuildKit、Distribution | ✗ | Harbor 必装私有仓库 |
| 网络 CNI | Cilium（2024 毕业）、Calico、Flannel | ✗ | Cilium 新秀 / Calico 稳健 |
| 可观测 | **Prometheus**、Fluentd、OpenTelemetry、Jaeger、Thanos、Grafana Loki | Grafana Tempo | Prometheus + OTel + Loki 标配 |
| 应用交付 | **Helm**、Argo CD | Kustomize（内置）、Tekton | Helm 打包 + Argo CD 部署 |
| 服务网格 | Istio（2023 毕业）、Linkerd | ✗ | 新手上 Linkerd / 深度用 Istio |
| 数据中间件 | Vitess | ✗ | Operator 化 MySQL/Redis 更常见 |
| 安全策略 | cert-manager、OPA/Gatekeeper、Falco、Trivy | Kyverno、SPIRE | cert-manager + Gatekeeper 必装 |
| 云原生存储 | Rook、CSI spec | Longhorn | Rook+Ceph 自建 / 云盘直接用 |
| 服务代理 | Envoy、CoreDNS | Contour | Envoy 事实标准（K8s CoreDNS 内置） |

> 🎯 **对你 SpringCloud 迁移 K8s 最关键的优先学习顺序**：Kubernetes → containerd → CNI（Calico/Cilium）→ CoreDNS → Helm → Prometheus → Grafana → Harbor → cert-manager → Gatekeeper → ArgoCD → Istio（可选）

---

## 三、编排与调度层：Kubernetes & 周边

### 3.1 Kubernetes（K8s）

| 属性 | 内容 |
|------|------|
| **项目背景** | 2014 年 Google 开源，基于内部 Borg/Omega 10+ 年集群管理经验；2015 年捐给 CNCF，**CNCF 第一个种子项目、第一个毕业项目** |
| **解决问题域** | 容器编排：部署、扩容、滚动更新、服务发现、自我修复、存储编排、配置管理、资源装箱 |
| **解决方案** | Master-Worker 架构（API Server + etcd + Controller + Scheduler），声明式 API，Controller 模式，Pod 为最小调度单元（详见 [k8s介绍.md](file:///d:/BaiduSyncdisk/ai/k8s/k8s介绍.md)） |
| **接口标准化** | CRI（容器运行时）、CNI（网络）、CSI（存储）、CDI（设备）、Gateway API、Device Plugin |
| **约束** | 1. 学习曲线陡峭；2. etcd 性能限制单集群约 5k Node、15w Pod；3. 有状态服务/存储仍需 Operator；4. 多集群需要联邦方案（Karmada/KubeFed） |
| **优点** | 1. 事实标准，生态最强；2. 声明式 API 自动化能力强大；3. 云中立（公有云/私有云/混合云一致体验）；4. 社区最大，人才市场充足 |
| **缺点** | 1. 组件多、复杂度高；2. 自建集群运维成本高（建议中小团队用托管 ACK/TKE/EKS/GKE）；3. 默认功能多但深入需二次开发 |

**各大厂商支持**：阿里云 ACK、腾讯云 TKE、华为云 CCE、AWS EKS、Google GKE、Azure AKS 全部是 K8s 发行版。

---

### 3.2 etcd

| 属性 | 内容 |
|------|------|
| **项目背景** | CoreOS 2013 年创建，2018 年被 RedHat 收购；2017 年 CNCF 孵化，2018 年**第一个 K8s 之外的毕业项目** |
| **解决问题域** | 分布式一致性 KV 存储——为分布式系统提供「强一致、高可用、事务性」的配置/元数据存储 |
| **解决方案** | 基于 **Raft 协议**（Paxos 的工程化简化版）实现奇数节点共识；提供线性一致读、事务（Txn）、TTL Lease、Watch 订阅、MVCC 多版本并发控制；HTTP/2 gRPC API |
| **典型场景** | K8s 唯一数据存储（所有资源对象）；服务发现；分布式锁；配置中心 |
| **约束** | 1. 写性能受 Raft 限制，单节点 ~1w/s 写，3 节点集群 ~2w/s；2. 数据存在内存，超大数据集（>8GB）要小心；3. 不适合存大文件/业务数据 |
| **优点** | 1. Raft 简单易懂，工程实现成熟；2. API 友好（gRPC + HTTP JSON 网关）；3. MVCC + Watch 做事件通知极佳；4. 内置 `etcdctl`、快照备份 |
| **缺点** | 1. 运维坑多（脑裂恢复、compaction 碎片整理、磁盘 IO 极度敏感）；2. 必须用 SSD（机械盘不可用于生产）；3. API v2 vs v3 历史包袱 |

---

## 四、运行时与容器层

### 4.1 containerd / CRI-O

| 项目 | containerd | CRI-O |
|------|-----------|-------|
| **背景** | Docker 2015 年从 Docker Engine 抽出的核心运行时；2017 捐给 CNCF，2019 毕业 | RedHat 2016 发起，专门为 K8s 实现最小 CRI 运行时 |
| **解决问题域** | 工业级容器运行时，解耦 K8s 与 Docker；镜像拉取、快照管理、容器生命周期 | 只实现 CRI 接口，去掉所有非 K8s 功能 |
| **解决方案** | 插件化架构（snapshotter / content store / shim v2），支持 runc/Kata/gVisor 多种 OCI runtime；实现了 CRI、同时也可独立使用 | 代码量仅 containerd 的 1/3，直接用 CRI-O + runc |
| **约束** | 1. 不能单独跑 Pod，必须配合 kubelet；2. containerd 1.6 后与 K8s 版本对齐 | 1. RedHat 生态绑定深；2. 某些高级特性（如 NRI）比 containerd 晚支持 |
| **优点** | 1. 通用度最高（Docker Desktop、阿里云 ACK、Google GKE 全部用）；2. 与 Docker 镜像完全兼容；3. 性能稳定 | 1. 小而美、攻击面最小；2. 与 RHEL/OpenShift 完美结合 |
| **缺点** | 组件依然偏多、插件化配置复杂 | 社区用户量小于 containerd，遇到坑资料少 |

> **选型建议**：除非你使用 OpenShift，否则默认 containerd（K8s 1.24+ 官方默认）。

---

### 4.2 BuildKit

| 属性 | 内容 |
|------|------|
| **背景** | Docker Inc. 2017 年推出的下一代镜像构建引擎，替代 `docker build` 老旧代码；2019 捐 CNCF，2023 毕业 |
| **解决问题域** | 传统 Dockerfile 构建慢（无并行、缓存低效）、跨架构构建（ARM/x86）难、CI/CD 构建环境复杂 |
| **解决方案** | 1. LLB 低层构建语言（DSL 描述依赖图）→ 支持多阶段并行构建；2. 分布式缓存层（含元数据缓存）；3. 跨平台多架构构建（`buildx --platform linux/amd64,arm64`）；4. 可导出 OCI 镜像/Docker 镜像；5. 可独立于 Docker 守护进程运行（rootless 模式） |
| **约束** | 1. 需要 Docker 23+ 或独立 buildkitd 部署；2. 旧 Dockerfile 某些 hack 可能不兼容 |
| **优点** | 1. 构建速度提升 2-10x（缓存命中、并行）；2. ARM 构建神器；3. 与 Dockerfile 兼容（兼容现有 99% Dockerfile）；4. rootless 更安全 |
| **缺点** | 1. 配置分布式缓存（S3/Registry cache）需要额外工作；2. 高级功能学习曲线高 |

> 🎯 **SpringCloud 提示**：你的 Java 服务 Dockerfile 用 BuildKit 多阶段构建（maven 构建层 + jre 运行层）可以极大提高流水线速度。

---

### 4.3 Harbor

| 属性 | 内容 |
|------|------|
| **背景** | VMware 中国团队 2014 年启动，中文名叫「港口」；2018 捐 CNCF，2020 毕业；是**第一个由中国团队主导毕业的 CNCF 项目** |
| **解决问题域** | 企业级私有容器镜像仓库——解决 Docker Hub 慢/收费、镜像安全扫描、权限隔离、镜像复制、合规审计 |
| **解决方案** | 1. 基于 Distribution（Docker Registry 开源）+ Nginx + PostgreSQL + Redis + Notary（镜像签名）+ Trivy（漏洞扫描）；2. 多租户 + RBAC 权限；3. 项目级独立命名空间；4. 跨 Region 复制策略（推拉双向）；5. 漏洞扫描阻止高危镜像被拉取；6. OIDC/LDAP 对接企业 SSO；7. Webhook 通知 |
| **约束** | 1. 本身无高可用部署（官方 Helm Chart 用双副本 + 外部数据库/存储，需你自己搭）；2. 大规模（百万镜像）需调优 PostgreSQL/S3 存储 |
| **优点** | 1. 中文文档好、国内社区活跃；2. 功能最完整的开源仓库方案；3. Helm Chart 仓库也支持（ChartMuseum 集成）；4. 各大云厂商的镜像仓库 ACR/TCR/CCR 都有 Harbor 影子 |
| **缺点** | 1. UI 风格偏老、交互一般；2. 升级版本偶有迁移坑 |

---

### 4.4 Distribution (Docker Registry)

| 属性 | 内容 |
|------|------|
| **背景** | Docker 官方开源镜像仓库的核心，2015 捐 OCI，2018 转 CNCF |
| **解决问题域** | 最小化的镜像存储服务——只是实现了 Docker Registry API v2，不含用户/权限/UI |
| **解决方案** | 支持 S3/OSS/Azure Blob/本地文件等存储后端；PULL/PUSH 镜像核心逻辑 |
| **约束** | 没有多租户、没有 UI、没有安全扫描，功能极简 |
| **优点** | 极小极快，用于 CI 中间仓库或者内网测试非常合适 |
| **缺点** | 企业基本不裸用，都是包一层 Harbor 或者用云厂商 ACR |

---

## 五、CNI 网络层

### 5.1 Cilium

| 属性 | 内容 |
|------|------|
| **背景** | Isovalent 公司 2016 年基于 Linux 内核 eBPF 技术创建；2021 进入孵化，**2024 毕业**，K8s 网络新贵 |
| **解决问题域** | 传统 iptables 模式大规模瓶颈、性能差；网络策略实现弱；缺少 4~7 层可观测性；Pod 流量加密难 |
| **解决方案** | **基于 eBPF**（内核字节码注入，不需要内核模块/重新编译）：1. 用 eBPF hook 替代 iptables，直接在 socket/tc 层做转发，性能提升 30-50%；2. 支持 L3/L4/L7 NetworkPolicy（能按 HTTP path/GRPC 方法限制）；3. Hubble 可观测组件（L7 流量拓扑、DNS 可见性、无侵入遥测）；4. CiliumClusterMesh 多集群网络互联；5. WireGuard / IPsec 透明加密；6. 内置 kube-proxy replacement（完全替代 iptables/ipvs） |
| **约束** | 1. **内核要求高**：推荐 Linux 5.10+，完全特性需要 5.15+；2. 超新特性可能有内核版本兼容坑；3. 社区中文资料少于 Calico |
| **优点** | 1. 性能最强的开源 CNI（生产实测 1k Service 下 P99 延迟比 Calico 低 40%）；2. 7 层网络策略独一无二；3. Hubble 可视化无敌；4. 替换 kube-proxy 减少每节点几万条 iptables；5. 大厂采用率上升极快（Google GKE Dataplane V2、EKS Anywhere、阿里云 Cilium 增强版都用它） |
| **缺点** | 1. eBPF 知识门槛高；2. 相比 Calico 年长 8 岁，生产案例数量略少（但差距快速缩小）；3. 某些旧内核 CentOS 7 4.19 体验差 |

---

### 5.2 Calico

| 属性 | 内容 |
|------|------|
| **背景** | Tigera（原 Metaswitch）2015 年基于 Project Calico 商用；2018 捐 CNCF，2020 毕业；是用户量最大的企业级 CNI |
| **解决问题域** | 企业数据中心、大规模集群的高性能 3 层网络，严格的网络隔离策略 |
| **解决方案** | 1. **纯 3 层 IP 路由（BGP 协议）**：Pod IP 全网可路由，无 VXLAN 封包开销（也支持 VXLAN/IPIP 封装模式）；2. NetworkPolicy + GlobalNetworkPolicy（跨节点全局策略）；3. HostEndpoint 保护 Node 主机端口；4. Calico Cloud SaaS 可观测版本；5. eBPF dataplane 模式（近年新增，与 Cilium 对标）|
| **约束** | 1. 纯 BGP 模式要求你的物理网络能支持 BGP peer（交换机配合或用 RR route reflector）；2. IPIP/VXLAN 模式性能略降 |
| **优点** | 1. 最成熟的企业级方案，金融/政府/运营商大量生产案例；2. 网络策略实现最严谨（iptables+nftables 双栈）；3. 支持 3 层路由，集群外机器也能直连 Pod IP；4. 文档完善、中文资料多 |
| **缺点** | 1. 组件多（Felix/Bird/confd/Dikastes/typha），运维复杂；2. 默认 iptables 数据面大规模性能不如 eBPF；3. 复杂 BGP 排错需要深厚网络功底 |

---

### 5.3 Flannel

| 属性 | 内容 |
|------|------|
| **背景** | CoreOS 2015 年发布，是最古老的 CNI 之一；2017 进 CNCF Sandbox（至今未毕业）|
| **解决问题域** | 提供最简单、最稳定的跨节点 Pod 网络，无需复杂配置 |
| **解决方案** | 多种 Backend：① VXLAN（默认，通用）、② host-gw（同网段主机路由模式，性能好）、③ UDP（调试用，慢）；配合 Kubernetes node-cidr-controller 分配每个 Node 的 PodCIDR |
| **约束** | 1. **不支持 NetworkPolicy**（必须搭配 Calico/canal 才行）；2. VXLAN 模式有封包开销 |
| **优点** | 1. 极简，1 条命令 `kubectl apply` 就能跑；2. 没有任何依赖，成功率最高；3. 中小规模（<200 Node）够用 |
| **缺点** | 1. 功能最简陋；2. 无网络策略、无流量观察；3. 社区活跃度逐年下降 |

---

## 六、可观测性层（Observability）

### 6.1 Prometheus

| 属性 | 内容 |
|------|------|
| **背景** | SoundCloud 2012 年内部启动，受 Google Borgmon 启发；2016 进 CNCF，**2018 毕业（第二个毕业项目）**；目前是可观测性领域无可争议的事实标准 |
| **解决问题域** | 基于**指标（Metrics）** 的监控告警系统——解决传统 Zabbix/Ganglia 对动态 K8s 环境不友好的问题（Pod IP 随时变、实例弹性） |
| **解决方案** | 1. **Pull 模型**：主动去目标（Pod/Node/中间件）的 `/metrics` 端点拉取指标，天然适合动态环境（服务发现：K8s SD / Consul / DNS）；2. **PromQL 查询语言**：支持向量/矩阵聚合、rate/irate 瞬时增长率、topk/histogram_quantile P99 分析；3. 多维数据模型（Label=Tag）+ 本地时间序列数据库 TSDB；4. **Alertmanager**：分组、静默、抑制、路由到钉钉/邮件/飞书/PagerDuty；5. Pushgateway（短生命周期 Job 推指标）、Exporter 生态（Node Exporter、MySQL Exporter、Redis Exporter、JMX Exporter 上百种）|
| **约束** | 1. 单实例内存吃紧（千万级时间序列需 64GB 内存）；2. 默认无长期存储（本地盘 15 天顶天）；3. 分布式 HA 方案要配合 Thanos/Mimir |
| **优点** | 1. 生态无敌：所有云原生项目默认都有 `/metrics`；2. PromQL 表达力强大；3. 服务发现与 K8s 无缝集成（自动发现新 Pod/Node/Endpoint）；4. Grafana 有海量现成 Dashboard |
| **缺点** | 1. 单机扩展性瓶颈；2. PromQL 陡峭学习曲线；3. 对 Tracing/Logging 原生不支持（但和 OTel/Loki 组合完美）|

---

### 6.2 Grafana（非 CNCF，但是事实标准）

> Grafana 公司自己的项目，未捐 CNCF，但所有 Prometheus 用户 100% 都用，强烈配套。

- **数据可视化**：统一 Prometheus、Loki、Tempo/Jaeger、MySQL、InfluxDB 几十种数据源到一个 UI
- **Grafana Dashboard 市场**：官方 10000+ 现成面板（Node/K8s/Nginx/MySQL 一键导入）
- **Grafana Alerting**：统一面板 + 告警配置入口
- **Unified Observability**：Metrics（Mimir）+ Logs（Loki）+ Traces（Tempo）三件套（Grafana 自家三件套，对标 OTel）

---

### 6.3 OpenTelemetry（OTel）

| 属性 | 内容 |
|------|------|
| **背景** | 2019 年 CNCF 合并 OpenTracing（CNCF）+ OpenCensus（Google）两大阵营；2021 孵化，**2023 毕业**；是 CNCF 历史上增速最快的项目（GitHub Star 数仅次于 K8s） |
| **解决问题域** | 可观测性三大支柱（Metrics/Logs/Traces）各自为战、埋点 SDK 碎片化（Jaeger SDK vs Zipkin SDK vs Prometheus SDK 冲突）、厂商锁定 |
| **解决方案** | **1 套标准 + 多语言 SDK + 采集器（Collector）**：<br>① **API + SDK**：Java/Go/Node/Python/C++ 10+ 语言；② **Instrumentation**：对 SpringBoot/JDBC/HttpClient 等常用库**零代码自动埋点**（Java Agent、Go eBPF 自动增强）；③ **Collector**：统一采集、处理（采样/过滤/聚合）、导出到任意后端（Prometheus / Jaeger / Tempo / Loki / Kafka）；④ **标准数据模型**：Metrics（OTLP）、Traces（Span/Link）、Logs（Structured） |
| **约束** | 1. Java 自动埋点 JavaAgent 额外开销 5-15% CPU；2. 自定义埋点需要学习 OTel API；3. 生态还在快速迭代，某些老 SDK 兼容性问题 |
| **优点** | 1. **标准中立，不被锁**：一次埋点可换任意后端（Jaeger↔Tempo、Prometheus↔Mimir 随便换）；2. Java SpringBoot 自动埋点基本不用改代码；3. Collector 集中管理策略，免去每台机器配置 |
| **缺点** | 1. 文档质量仍需提升（概念太多：Context / Propagation / Resource / Scope）；2. 某些高级特性（tail-based 采样）要额外组件（Collector 配合 OpenTelemetry Collector Contrib）|

> 🎯 **SpringCloud 提示**：以前用 Sleuth + Zipkin 的项目，**无缝替换成 OTel Java Agent + OTel Collector + Jaeger/Tempo**，零代码改动！`-javaagent:opentelemetry-javaagent.jar` + 环境变量就能自动采集 Feign、RestTemplate、JDBC、Kafka、Redis 全链路。

---

### 6.4 Jaeger

| 属性 | 内容 |
|------|------|
| **背景** | Uber 2015 年内部开发，受 Google Dapper 论文启发；2017 捐 CNCF，2019 毕业 |
| **解决问题域** | **分布式链路追踪（Tracing）**：微服务间一次请求经过多少节点、哪一步慢、哪一步报错——传统日志查不到跨服务因果链 |
| **解决方案** | 1. 经典架构：Agent（本机 UDP 采集）→ Collector → Cassandra/Elasticsearch/Badger（存储）→ Query + UI；2. 兼容 OpenTracing API（已完全兼容 OTel）；3. 服务依赖拓扑图；4. 支持系统间上下文传播（B3 / W3C TraceContext）；5. 近期推出架构简化版：Jaeger All-in-One（测试）和不含独立 Agent 的 OTel Collector 模式 |
| **约束** | 1. 100% 采样下存储成本极高（需做头部/尾部采样）；2. 依赖 ES/Cassandra 运维重 |
| **优点** | 1. UI 体验最好的开源 Tracing；2. 兼容性最强；3. 稳定生产验证 |
| **缺点** | 1. 架构复杂（5 个组件）；2. 社区重心逐步转向 OTel Collector 前端 + Jaeger 后端 |

---

### 6.5 Fluentd & Fluent Bit

| 项目 | Fluentd | Fluent Bit |
|------|---------|-----------|
| **背景** | Treasure Data 2011，2016 CNCF，2019 毕业 | Treasure Data 2015 为了解决 Fluentd 的资源开销问题重写的轻量版；2018 进 CNCF，2022 毕业 |
| **解决问题域** | **日志统一采集（Logging）**：K8s 每个 Node 上 Pod 的日志/主机日志/中间件日志 → 统一发往 Elasticsearch/Kafka/Loki/S3/OSS | 同上，但用于边缘/ARM/资源受限环境 |
| **解决方案** | 1. 插件化架构（1000+ 插件，Tail/Forward/Elasticsearch/S3/Kafka）；2. 统一 Buffer（内存/磁盘）保证不丢；3. 标签路由；4. Kubernetes metadata 自动注入（把 Pod/Namespace 标签打到每条日志上）| 1. C 语言写，内存 500KB vs Fluentd Ruby 40MB；2. 插件齐全（但数量比 Fluentd 少）；3. 官方 K8s 推荐：**Fluent Bit 采集 → Fluentd 聚合/转发 → 存储/分析** |
| **约束** | Ruby 运行时内存占用高 | 自定义插件开发门槛略高（C 语言，但已有 Lua 脚本扩展）|
| **选型建议** | K8s 每个 Node 上跑 DaemonSet **Fluent Bit** 采集 → 中央 Fluentd StatefulSet 做路由/清洗/发 Kafka+ES |

---

### 6.6 Thanos / Cortex / Mimir

解决 Prometheus 单节点瓶颈的三种高可用/长期存储方案（选一个即可）：

| 项目 | Thanos | Cortex | Grafana Mimir |
|------|--------|--------|---------------|
| **作者** | Improbable 2017，2020 孵化（未毕业） | Weaveworks 2016，2020 孵化（未毕业） | Grafana Labs 2022，基于 Cortex 分支重写 |
| **思路** | Sidecar 模式：每个 Prometheus 旁挂 Thanos Sidecar 上传 TSDB 块到 S3；再加 Querier 跨 Prometheus 聚合查询 | 中心化无 Prometheus：Distributor → Ingester（内存）→ Store（存 S3）→ Querier | 架构同 Cortex，但代码重写了存储路径，性能和稳定性显著优于 Cortex |
| **最亮点** | 改造成本最低（已有 Prometheus 集群零迁移） | 无状态水平扩展，支持百万级时间序列 | Grafana 官方亲儿子，前景最好，文档最佳 |
| **选型** | 已有 Prometheus 想快速长期存储 → Thanos；新建平台 → 直接 Mimir |

---

## 七、应用生命周期与 CI/CD

### 7.1 Helm

| 属性 | 内容 |
|------|------|
| **背景** | Deis（后被微软收购）2016 年推出，「K8s 的 apt-get/yum」；2018 捐 CNCF，2020 毕业 |
| **解决问题域** | 1. 一个应用需要 K8s Deployment/Service/ConfigMap/HPA/Ingress 10+ YAML 一个个 apply 效率低；2. 多环境（dev/test/prod）配置参数化、版本管理、回滚 |
| **解决方案** | 1. **Chart 包格式**：把所有 YAML 模板打包成一个 tgz；2. **Go Template 模板引擎**：`{{ .Values.replicaCount }}` 参数化；3. Helm CLI：`helm install / upgrade / rollback / uninstall` 原子操作；4. Release = Chart + Values 的一次安装实例（有历史版本，一键回滚）；5. 仓库系统（可以用 Harbor/ChartMuseum）；6. Helm 3（2019）去掉了 Tiller 服务端组件，纯客户端+K8s API 交互，更安全 |
| **约束** | 1. Go Template 语法晦涩（`with/range/indent` 易踩坑）；2. 大 Chart（比如 Kafka Cluster Helm 3000 行）调试痛苦；3. 3 向合并 patch 有时冲突 |
| **优点** | 1. 事实标准，**所有 CNCF 项目官方都有 Helm Chart**（Kafka、Harbor、Prometheus、Argo CD 一键装）；2. 版本管理和回滚最好用；3. 应用市场模式成熟（Artifact Hub 10000+ Chart）|
| **缺点** | 1. 模板机制不优雅（社区有 Kustomize/Cdk8s/Cue 替代派）；2. 不支持多环境差异化太灵活（用 subchart + values.prod.yaml 凑合）|

> 🎯 **SpringCloud 提示**：建议给每个微服务写一个 Helm Chart（deployment + service + configmap + secret + hpa + ingress 共 6 个模板文件），不同环境通过 values-dev.yaml / values-prod.yaml 区分。

---

### 7.2 Argo CD（GitOps 标杆）

| 属性 | 内容 |
|------|------|
| **背景** | Intuit 2018 年开源，与 Codefresh、RedHat 联合推动 GitOps 理念；2020 进 CNCF，**2022 毕业** |
| **解决问题域** | 传统 CI/CD（Jenkins 跑 kubectl apply）问题：① 权限大（Jenkins 要有所有集群的管理员权限）、② 无审计（谁改了集群什么不知道）、③ 集群状态和 Git 仓库不同步（有人手动改 YAML 出问题） |
| **解决方案** | **GitOps = Git 是唯一真相来源**：<br>1. 所有部署的 YAML/Helm Chart/Kustomize 全部在 Git 仓库；<br>2. Argo CD 运行在 K8s 集群里，**反向 Pull** Git 仓库，对比与集群实际状态；<br>3. 发现偏差（Diff）自动或手动 Sync；<br>4. 每次变更就是 Git Commit → 天然审计、回滚 = `git revert`；<br>5. 支持多集群、多租户（AppProject + RBAC + SSO OIDC）；<br>6. 漂亮的 UI 可视化应用拓扑；<br>7. 支持同步波次（Sync Waves/Hooks）——先 DB 后 App |
| **约束** | 1. 思维模式从「Push 触发部署」变「Git Commit 触发部署」；2. 敏感配置 Secret 不能明文放 Git（需 SealedSecret / External Secrets Operator / SOPS）；3. Git 仓库大了全量 Reconcile 有压力 |
| **优点** | 1. 安全性极高（集群不对外暴露凭据，Argo CD 只有拉 Git 的 token）；2. 审计 100%；3. 回滚最稳（Git revert 一键）；4. 多集群多租户支持最好 |
| **缺点** | 1. 部署初体验复杂（CLI 或 UI 创建 Application，有学习曲线）；2. 大量 Helm 依赖时 Repo Server 容易 OOM |

---

### 7.3 Argo Workflows

与 Argo CD 同属 Argo 家族（同一个公司 Intuit），但定位**工作流引擎**（DAG），而不是 GitOps 部署。

- 背景：Intuit 2018，2020 孵化，**2024 毕业**
- 用途：K8s 上跑 Job Pipeline（数据 ETL、机器学习训练 Pipeline、复杂 CI 流水线）
- 方案：YAML 定义 DAG 图（Task A → Task B&C → Task D），每个 Task 是一个 K8s Pod
- 选型建议：不替代 Jenkins/GitLab CI 做代码构建；但适合构建后「长时复杂流程」

---

### 7.4 Tekton

| 属性 | 内容 |
|------|------|
| **背景** | Google 2019 基于 Knative Build 重写；2020 进 CNCF 孵化（未毕业）|
| **解决问题域** | **K8s 原生 CI/CD**——解决 Jenkins 这种非 K8s 原生调度器和 K8s 割裂（K8s Pod 里跑 Jenkins Agent 效率低、资源隔离差） |
| **解决方案** | 1. **自定义 CRD 定义流水线**：Task（步骤 Step 序列）→ TaskRun（实例）；Pipeline → PipelineRun；2. 每个 Step 是独立容器（完全隔离）；3. Workspace 挂 PVC 传文件；4. PipelineResource 拉 Git/镜像；5. Chains 做供应链安全（SLSA 签名）；6. Triggers 对接 GitHub/GitLab Webhook 触发 |
| **约束** | 1. 没有内置 UI（需 Tekton Dashboard / OpenShift 提供）；2. 概念抽象度极高（Task/TaskRun/Pipeline/PipelineRun/Run/Workspace/PipelineResource/Resolver…）|
| **优点** | 1. 100% K8s 原生，弹性扩缩容（1000 条并发构建 = 1000 Pod）；2. 可组合性强；3. 配合 Chains 支持软件供应链 SLSA 合规 |
| **缺点** | 1. 学习曲线陡峭，初学者劝退；2. 生态插件少，很多轮子要自己造；3. 对非 K8s 构建任务支持弱 |
| **选型建议** | 企业内部要做云原生 CI 平台且团队 K8s 功底强 → Tekton；否则保持 GitLab CI / Jenkins 更省心。 |

---

### 7.5 Kustomize

| 属性 | 内容 |
|------|------|
| **背景** | Google 2018 推出，是 **kubectl 内置子命令**（`kubectl apply -k`），不用单独安装。虽然是 Sandbox 但普及率高。 |
| **解决问题域** | 多环境（dev/test/prod）YAML 差异化管理——不使用模板、使用 Patch 覆盖 |
| **解决方案** | Overlay 叠加：① base 目录（公共 Deployment/Service）；② overlays/dev、overlays/prod 目录用 `patchesStrategicMerge` 或 JSONPatch 修改 `replicas: 1` vs `replicas: 10`、ConfigMap nameSuffix 自动变化 |
| **约束** | 1. 无循环/变量；2. 复杂场景（同一份模板实例化 10 次）吃力 |
| **优点** | 1. 无模板学习成本，YAML 语法就是原生语法；2. kubectl 内置，零依赖；3. Argo CD 原生支持 |
| **缺点** | 1. 功能弱于 Helm；2. 很多场景需要自己写 Generator 插件 |
| **选型建议** | 简单项目用 Kustomize；复杂多租户打包分发 → Helm。两者可以混合使用（Argo CD 支持 Helm + Kustomize 叠加）。 |

---

## 八、服务网格与 API 网关

### 8.1 Istio

| 属性 | 内容 |
|------|------|
| **背景** | Google + IBM + Lyft 2017 年联合发布（基于 Lyft 的 Envoy）；2022 申请进 CNCF，**2023 毕业**。服务网格领域的标杆。 |
| **解决问题域** | 微服务治理的痛点：① SpringCloud/Sentinel 侵入代码，每个语言要写一遍；② 灰度发布（金丝雀）、蓝绿、流量镜像难做；③ mTLS 服务间加密困难；④ 统一可观测（每个服务都要改代码加 Metrics/Tracing/Logs） |
| **解决方案** | **Sidecar 模式（Envoy 数据面 + Istiod 控制面）**：<br>1. 每个业务 Pod 自动注入 Envoy Sidecar 代理，所有进出网络流量经 Envoy（业务代码 0 改动）；<br>2. **Istiod** 集中下发：路由规则（VirtualService/DestinationRule）、安全策略（PeerAuthentication/RequestAuthentication）、遥测配置；<br>3. 核心能力：<br>　① mTLS 自动双向 TLS（服务间通信加密、认证）<br>　② L7 流量管理：按 header/cookie/权重路由（金丝雀发布 5% 灰度、AB 测试）<br>　③ 故障注入（延迟/丢包测韧性）、熔断、重试、超时<br>　④ 全链路 Metrics/Tracing/Logs（0 代码）<br>　⑤ 零信任安全（AuthorizationPolicy：谁能调用谁） |
| **约束** | 1. 资源开销显著：每个 Pod 多 1 个 Envoy 容器（约 60MB 内存、0.1 核），1000 Pod 集群要预留 60GB 内存；2. 学习曲线陡峭（20+ CRD 种类）；3. 升级版本易出兼容性问题；4. 复杂入口 Ingress Gateway TLS 配置坑多 |
| **优点** | 1. 功能最全面的服务网格（没有之一）；2. Envoy 生态统一；3. 0 代码改动对 SpringCloud 存量应用太香了（可以移除 SpringCloud Gateway、Ribbon、Sentinel 的代码）；4. 大厂背书：Google、IBM、Salesforce、蚂蚁集团、Cisco 都有生产案例 |
| **缺点** | 1. 运维成本最高；2. 性能开销对 QPS 高的场景（>5w QPS 单 Pod）会放大延迟 2-5ms；3. Ambient Mesh 新模式（2024 beta）还在成熟 |

---

### 8.2 Linkerd

| 属性 | 内容 |
|------|------|
| **背景** | Buoyant 2016 推出——**服务网格概念发明者**（Service Mesh 这个词就是 Buoyant CEO 创造的）；2017 第一个进 CNCF，2021 毕业 |
| **解决问题域** | Istio 太复杂太重，Linkerd 主打「极简、零配置」 |
| **解决方案** | 1. **自研 micro-proxy（Rust 写的 Linkerd2-proxy）**，不是 Envoy；2. 控制面极轻（单 Pod）；3. 自动 mTLS、自动 metrics；4. 流量拆分（TrafficSplit CRD，符合 SMI 规范）；5. 2023 推出 ambient 模式（可选，无 Sidecar，性能更好） |
| **约束** | 1. 高级功能（复杂 L7 路由、故障注入、自定义 Wasm 插件）比 Istio 弱；2. 国内社区中文资料少 |
| **优点** | 1. 最轻量（Sidecar 仅 10MB、0.05 核，Istio 1/5 开销）；2. 真正的「30 秒安装、零配置生效」；3. 最稳定，生产事故少 |
| **缺点** | 1. 扩展性不足；2. 厂商生态绑定 Buoyant（虽毕业但厂商生态不如 Istio 广） |
| **选型建议** | 团队服务网格入门、只想用 mTLS + 基础灰度 → Linkerd；深度定制流量治理 → Istio |

---

### 8.3 Envoy

| 属性 | 内容 |
|------|------|
| **背景** | Lyft 2016 用 C++11 写的第 7 层高性能代理；2017 捐 CNCF，2018 毕业 |
| **解决问题域** | 通用 L4/L7 代理：高并发、低延迟、可扩展——Nginx 配置静态、扩展难；HAProxy 功能少 |
| **解决方案** | 1. 架构：Listener（监听端口）→ Filter Chain（L7 过滤器链：HTTP/gRPC/Thrift/RateLimit/JWTVerify/Wasm）→ Route → Cluster → Endpoint；2. **xDS API（CDS/EDS/RDS/LDS/SDS）**：动态从控制面（Istiod/Contour）拉配置，无需 reload；3. Wasm 插件化扩展（任何语言编译 Wasm 注入过滤器链）；4. 内置 HTTP/2、gRPC、TCP Proxy、MySQL/Mongo/Redis 协议级支持；5. 完善的 stats、tracing 接入 |
| **约束** | 1. C++ 源码学习门槛极高；2. 原生 YAML/JSON 配置复杂（新手看不懂，通常不直接裸用） |
| **优点** | 1. **事实上所有服务网格/现代网关的底座**：Istio/Linkerd（部分）、Contour、Gloo、Kong 2.x、AWS AppMesh、阿里云 ASM 全基于 Envoy；2. 性能极强（单实例 10w QPS、微秒级延迟）；3. xDS 标准已被行业认可 |
| **缺点** | 1. 直接用成本太高，必须配合控制面；2. 内存占用高（长连接多场景）|

---

### 8.4 Contour / Gateway API

| 项目 | Contour | Gateway API |
|------|---------|-------------|
| **背景** | VMware 2017 基于 Envoy 写的 Ingress Controller；2019 进孵化 | K8s SIG-NETWORK 官方制定的下一代网关标准（CNCF 非项目，但生态核心）；2023 v1 GA |
| **解决问题域** | 传统 Nginx Ingress 注解（annotations）混乱、不可扩展、角色不分（集群网管 vs 应用开发同一份 Ingress 改来改去冲突） | 同上，是 Contour/Istio/Contour 的共识 CRD 替代方案 |
| **解决方案** | 1. Envoy 做数据面；2. HTTPProxy CRD（比 Ingress 表达力强：路由拆分、流量镜像、全局跨域）；3. 支持 Gateway API | 1. 三层 CRD：`GatewayClass`（厂商/集群级）→ `Gateway`（租户/命名空间，定义监听器/域名）→ `HTTPRoute`/`GRPCRoute`/`TCPRoute`（应用级，定义路由规则）；2. 角色清晰、多实现互通 |
| **选型建议** | 目前 Nginx Ingress 仍是主流，但**新项目直接使用 Gateway API**（已 GA），它会在未来 2-3 年完全取代 Ingress。 |

---

## 九、数据库与中间件（云原生化）

### 9.1 Vitess

| 属性 | 内容 |
|------|------|
| **背景** | YouTube 2010 年为了解决 MySQL 单机容量极限开发的中间件；2018 捐 CNCF，2019 毕业；目前除了 YouTube 还有 Slack、Pinterest、GitHub 在用 |
| **解决问题域** | **关系型数据库水平分片**——MySQL 单表几亿行、单机 IO 瓶颈后，如何做分库分表（对业务透明/半透明）|
| **解决方案** | 1. 三层架构：① **VTGate**（SQL 路由器，应用像连普通 MySQL 一样连 VTGate）→ ② **VTTablet**（每个 MySQL 实例旁一个代理，管理每个分片）→ ③ **Topo（etcd/zk）** 存分片元数据；2. 分片算法（hash/range/list）；3. VSchema 逻辑表 → 物理表映射；4. 支持跨分片分布式事务（2PC）；5. 在线 Reshard（不停机重新分片，数据热迁移）；6. 读写分离、垂直切分；7. K8s Operator：Vitess Operator 一键部署集群 |
| **约束** | 1. 不能 100% 兼容 MySQL：复杂子查询、跨分片 JOIN、存储过程有坑；2. 运维复杂度高（相当于自己维护分布式数据库）；3. 分片策略一旦选了后续改影响大 |
| **优点** | 1. YouTube 规模验证（万亿级行、百万 QPS）；2. 业务改动最小（大部分 MySQL 代码不用改）；3. 官方 Operator K8s 部署简单 |
| **缺点** | 1. 中文资料极少，学习曲线极陡；2. 团队需要同时懂 K8s + MySQL 内核 + 分布式原理；3. 一般中小公司业务体量没到 Vitess 这一层 |

---

### 9.2 TiDB 生态

TiDB（PingCAP，开源但非 CNCF 项目）是 NewSQL 分布式数据库，与 Vitess 不同路径：**兼容 MySQL 协议、原生分布式，不依赖底层 MySQL**。K8s 上通过 TiDB Operator（PingCAP + CNCF 合推的 Operator 标杆）管理。

建议：国内企业需要分布式数据库且团队没 Google 级别功底，TiDB Operator 比 Vitess 更省心。

---

### 9.3 Operator 框架 & OperatorHub

| 属性 | 内容 |
|------|------|
| **背景** | CoreOS 2016 年提出 Operator 模式；Operator Framework 2018 捐 CNCF；**K8s 生态最重要的「模式」** |
| **解决问题域** | 把有状态中间件（MySQL/Redis/ES/Kafka/Nacos）部署在 K8s 上，需要人手工：备份、扩容、升级、故障切换、证书轮换——不可自动化 |
| **解决方案** | Operator = CRD（自定义资源定义，比如 `MysqlCluster`）+ Controller（用 Go/Java/Python 写的控制器，K8s API 监听这个资源，自动调谐执行运维动作）<br>常见场景：<br>• MySQL Operator（Oracle 官方 / Percona XtraDB Cluster / MGR）<br>• Redis Operator（RedisLabs / Spotahome）<br>• Strimzi Kafka Operator（RedHat 维护，CNCF 成员，Kafka 首选）<br>• Elastic Cloud on K8s（ECK，Elastic 官方）<br>• MinIO Operator（对象存储）<br>**OperatorHub.io**：RedHat 维护的 Operator 市场，一键安装 |
| **约束** | 1. Operator 质量参差不齐：有些大厂官方的好，有些社区写的坑多；2. 写一个好 Operator 需要深入理解中间件运维细节；3. OLM（Operator Lifecycle Manager）学习曲线 |
| **选型建议** | **有状态中间件一律找对应官方 Operator 跑**，不要手写 StatefulSet！ |

---

## 十、安全与策略（Security & Policy）

### 10.1 OPA / Gatekeeper

| 属性 | 内容 |
|------|------|
| **背景** | Styra 2016 推出通用策略引擎 OPA（Open Policy Agent）；2018 进 CNCF，2021 毕业。Gatekeeper 是 OPA 在 K8s 上的官方集成（把 OPA 当 K8s 准入控制器 webhook）。 |
| **解决问题域** | 1. 运维/开发有人把 `:latest` 镜像、没有 requests/limits、hostNetwork 特权的 Pod 提交到集群——集群会出大事；2. 传统 RBAC 只能管「谁能创建 Deployment」，管不了「Deployment 里有没有安全字段」 |
| **解决方案** | 1. **OPA Rego 声明式策略语言**：`any container in input.spec.template.spec.containers: container.image == ":latest" → deny`；2. **Gatekeeper**：作为 K8s Validating Admission Webhook（API Server 在接受资源前 POST 给 Gatekeeper 校验），3 种匹配方式（ConstraintTemplate + Constraint）；3. `--audit` 模式：定期扫描集群里已存在的违规对象报告 |
| **约束** | 1. Rego 语法学习曲线陡（Datalog 风格，过程式程序员需要适应）；2. 自定义策略写复杂了性能有影响；3. webhook 超时配置要合理 |
| **优点** | 1. 策略与代码解耦；2. 唯一毕业的策略引擎；3. 大厂必装（阿里/腾讯/字节生产集群全部有 Gatekeeper 级别策略拦截） |
| **缺点** | 1. 官方示例库不足；2. 中文资料少 |

---

### 10.2 cert-manager

| 属性 | 内容 |
|------|------|
| **背景** | Jetstack 2017 推出；2020 进 CNCF 孵化，**2023 毕业** |
| **解决问题域** | 手动申请 TLS 证书、部署到 Ingress、90 天到期手动续期——容易忘、容易错 |
| **解决方案** | 1. CRD 定义：① `Issuer/ClusterIssuer`（证书签发者：Let's Encrypt、Vault、SelfSigned、CA 私有证书）；② `Certificate`（期望的证书对象：域名、Secret 名）；2. 控制器自动：签发（ACME HTTP-01/DNS-01 验证域名所有权）→ 存 Secret → 挂载到 Ingress → **到期前自动续期**；3. 国内 DNS 支持：阿里云 DNS / DNSPod / Cloudflare |
| **约束** | 1. DNS-01 挑战需要配置云厂商 AK/SK（权限要合理）；2. 对国内 CA（如沃通）支持少，基本用 Let's Encrypt（DV 免费） |
| **优点** | 1. 证书全自动生命周期管理；2. 所有主流 Ingress Controller（Nginx/Contour/Istio Gateway）全部集成；3. 企业内部 CA 一键签发（对接 Vault PKI）|
| **缺点** | 1. 签发失败（DNS 解析不生效）排查略复杂；2. 某些老版本升级 CRD 有坑 |

---

### 10.3 Falco

| 属性 | 内容 |
|------|------|
| **背景** | Sysdig 2016 推出运行时安全；2018 进 CNCF，**2021 毕业（第一个安全毕业项目）** |
| **解决问题域** | 静态扫描（Trivy 扫描镜像）是事前；容器运行时「进去后做坏事」怎么检测？——运行时异常行为感知 |
| **解决方案** | 1. 内核驱动（传统 Kernel Module + 新版 eBPF Probe）捕获系统调用（syscall）；2. 规则引擎：`evt.type = execve and proc.name = "bash" and container.id != host → alert 容器里有人开 bash`；3. 常见规则：敏感文件访问（/etc/shadow）、特权升级、挂载宿主机 /etc、反向 shell；4. 告警输出到 Slack/钉钉/Webhook/SIEM |
| **约束** | 1. eBPF 驱动对内核版本有要求（Kernel Module 略低但可能安全不兼容）；2. 默认规则误报/漏报多，需要企业规则调优；3. Falco Sidekick / Falcosidekick UI 另装 |
| **优点** | 1. 唯一运行时安全毕业项目；2. 规则表达力最强；3. eBPF 无侵入 |
| **缺点** | 1. 误报需要耐心 tune；2. Kernel 升级时探针容易不兼容 |

---

### 10.4 SPIFFE / SPIRE

| 项目 | SPIFFE | SPIRE |
|------|--------|-------|
| **背景** | 规范标准（非代码项目）；Scytale + Google 2017；2020 孵化 | SPIFFE 规范的参考实现；2020 孵化、2024 毕业 |
| **解决问题域** | 零信任安全——服务身份认证怎么做？机器级（IP/主机名）不可靠、API Key 管理难、mTLS 证书怎么发？ | 实现 SPIFFE 标准的身份签发工具 |
| **解决方案** | ① **SPIFFE ID**：URI 格式身份 `spiffe://cluster.local/ns/default/sa/user-service`（绑定 K8s ServiceAccount）；② **SVID**：短生命周期（分钟级）X.509/JWT 证书，代表该身份。 | 1. Server（CA）+ Node Agent（每 Node 一个）+ Workload API；2. Workload 通过 Unix Domain Socket 拿 SVID（不用 Secret、不用重启）；3. 可与 Istio/Envoy 结合实现全链路 mTLS |
| **适用场景** | 大型金融/政府企业构建零信任网格；一般公司直接用 Istio 自带的 CA + SPIFFE ID（Istio 默认就用 SPIFFE 格式）即可，不必独立部署 SPIRE。 |

---

### 10.5 Trivy

| 属性 | 内容 |
|------|------|
| **背景** | Aqua Security 2019 推出，**最简单的镜像漏洞扫描工具**；2021 Sandbox、2023 孵化、2024 毕业 |
| **解决问题域** | CI/CD 流水线里 Docker 镜像上线前，扫出 CVE 漏洞（Log4j、OpenSSL、Python 依赖包…）和 IaC 配置错误（Terraform/Dockerfile/K8s YAML 不安全写法） |
| **解决方案** | 1. 单二进制文件，**零依赖**（比 Clair/Anchore 安装简单 10 倍）；2. 扫描范围：① OS 包（Alpine/Debian/Ubuntu/CentOS）、② 语言包（Java JAR、Go、npm、Python、Ruby、PHP、Rust）、③ IaC（K8s YAML/Helm/Terraform/Dockerfile 配置错误）、④ SBOM、⑤ Secret（AK/SK 泄露扫描）；3. Harbor 集成（内置 Trivy 扫描器）；4. CI Plugin（GitHub Actions / GitLab CI 现成 Action）|
| **约束** | 1. 第一次拉漏洞库大（几百 MB），后续增量；2. 某些语言版本误报略多 |
| **优点** | 1. 安装/使用最简单，没有之一；2. Harbor 官方默认扫描器；3. 功能越来越全（最近加入 SBOM、Licenses、Attestation） |
| **缺点** | 1. 无 SaaS 平台版（Aqua 的商业版才有）；2. 企业级漏洞管理（SLA、工单、例外审批）需自研或付费 |

---

### 10.6 Kyverno

| 属性 | 内容 |
|------|------|
| **背景** | Nirmata 2020 推出，K8s 原生策略引擎；2023 进孵化 |
| **解决问题域** | 同样是准入控制，但 Kyverno 认为 OPA+Rego 太复杂，主张 YAML 写策略，K8s 管理员不用学新语言 |
| **解决方案** | 策略就是 K8s CRD（YAML），支持三种行为：① **Validate**（验证不通过拒绝）、② **Mutate**（自动改资源，如自动注入 sidecar、自动加 `securityContext.runAsNonRoot: true`）、③ **Generate**（创建 Namespace 时自动生成默认 LimitRange/NetworkPolicy/RBAC RoleBinding） |
| **约束** | 1. 表达力不如 Rego；2. 毕业时间未知（还在孵化）、社区小于 Gatekeeper |
| **优点** | 1. 学习成本最低；2. Mutate/Generate 场景比 Gatekeeper 好用；3. CNCF 正式项目 |
| **选型建议** | 团队写不了 Rego → Kyverno；企业深度定制 → Gatekeeper+OPA。两者社区都很活跃。 |

---

## 十一、存储层（Cloud Native Storage）

### 11.1 Rook

| 属性 | 内容 |
|------|------|
| **背景** | Quantum（原 Upbound）2016 做的「存储编排 Operator」；2018 进 CNCF，2020 毕业（**第一个存储毕业项目**） |
| **解决问题域** | Ceph、NFS、MinIO、Cassandra 这些存储系统部署在裸机难、K8s 上跑更加难（监控、升级、扩缩容、故障处理都要会） |
| **解决方案** | Rook = Storage Operator 框架，官方成熟的有 **Rook-Ceph Operator**；CRD：`CephCluster / CephBlockPool / CephFilesystem / CephObjectStore / CephCSI` → Operator 自动部署 Ceph MON/OSD/MDS/RGW，暴露 CSI 接口给 K8s |
| **约束** | 1. Rook 不做存储本身，底层 Ceph 的坑还是要你懂；2. 生产对磁盘/网络要求高（OSD 必须 SSD、独立万兆网）；3. 版本升级严格按文档，别跳版本 |
| **优点** | 1. 让自建分布式存储在 K8s 上成为可能；2. 开源免费，Ceph 社区资料极多；3. 支持 RWO / RWX / ROP 三种访问模式 |
| **缺点** | 1. Ceph 知识门槛极高（CRUSH map、PG 数计算、BlueStore、数据平衡…）；2. 3 节点起步，机器成本高；3. 出问题时排障非常痛苦 |

---

### 11.2 Ceph + Rook 组合

Ceph（非 CNCF 项目，红帽主导）是开源分布式存储三剑客（SDS）鼻祖，RADOS 对象存储底层同时提供三种接口：

- **RBD（块存储）**：对应 K8s RWO PV，替代云盘
- **CephFS（文件存储）**：对应 K8s RWX PV，替代 NAS/NFS
- **RGW（对象存储）**：兼容 S3 API，替代 OSS/MinIO

→ 国内企业自建 K8s 存储：80% 是 Rook + Ceph。

---

### 11.3 Longhorn

| 属性 | 内容 |
|------|------|
| **背景** | Rancher（SUSE）2019 推出，2021 进 CNCF 孵化 |
| **解决问题域** | Rook+Ceph 太复杂，中小团队用不起 Ceph 这套——有没有「轻量级、简单 1 小时能装好」的分布式块存储？ |
| **解决方案** | 1. 架构极简：每个 Node 上挂一块本地磁盘 → Longhorn Manager 用 **nfs-ganesha 用户态 NFS** + 数据副本（2~3 份跨 Node 复制）；2. 漂亮的 UI + 增量快照 + 备份到 S3/NFS；3. 一个 `kubectl apply -f` 就能装完 |
| **约束** | 1. 性能不如 RBD（用户态 NFS + TCP 复制延迟）；2. 大规模（>50 Node）稳定性存疑；3. 不支持对象存储 / 文件存储（只有块） |
| **优点** | 1. 部署运维成本最低；2. 快照/克隆/备份 UI 交互最好；3. 测试环境 / 中小规模生产够用 |
| **缺点** | 1. 性能瓶颈；2. 还未毕业 |

---

### 11.4 Container Storage Interface (CSI)

CSI 是 CNCF 定义的**标准接口规范**（非具体项目），类比 CNI/CRI：

- 问题：以前每个存储厂商（Ceph/云盘/EMC ScaleIO）都要把代码写到 K8s 仓库里（树内 in-tree），K8s 发布周期绑定存储驱动发布
- 方案：CSI gRPC 标准 3 大组件：`Identity / Controller / Node`，驱动跑在独立 DaemonSet + StatefulSet，K8s 不用改核心代码
- 毕业项目；**K8s 1.27 起 in-tree 存储驱动全部移除，强制 CSI**

---

## 十二、SpringCloud 迁移云原生的 CNCF 技术栈推荐

### 12.1 最小可用栈（MVP，适合中小团队，先跑起来）

```
┌────────────────────────────────────────────────────────────┐
│                    应用层（SpringCloud）                      │
│  用户服务 / 订单服务 / 网关 / 认证中心（JVM 微服务）           │
└────────┬───────────────────────────────────────────────────┘
         │
┌────────▼───────────────────────────────────────────────────┐
│                   K8s 底座（必装）                           │
│  Kubernetes（托管 ACK/TKE 或 kubeadm 自建）                  │
│   ├─ containerd（容器运行时）                                 │
│   ├─ Calico 或 Flannel（CNI 网络）                           │
│   └─ CoreDNS（K8s 自带）                                     │
│  Helm 3（打包应用）                                          │
│  Harbor（私有镜像仓库）                                      │
│  Prometheus + Alertmanager + Grafana（监控告警）             │
│  Fluent Bit + Elasticsearch + Kibana（日志，或 Loki 轻量）   │
│  cert-manager（TLS 证书自动）                                │
│  Nginx Ingress Controller（对外流量入口）                    │
└────────────────────────────────────────────────────────────┘
```

**只需 5-10 个核心组件，90% 的 SpringCloud 应用已能跑起来。**

### 12.2 企业增强栈（大厂标配，生产就绪）

MVP + 这些：

| 组件 | 作用 | 理由 |
|------|------|------|
| **OTel Java Agent + OTel Collector** | 零代码埋点 Metrics/Trace/Log | 替换 Sleuth+Zipkin，标准中立 |
| **Jaeger / Grafana Tempo** | 链路追踪存储后端 | 排错微服务调用链必备 |
| **OPA Gatekeeper / Kyverno** | 策略拦截 | 防止 latest 镜像/无 limits/特权 Pod |
| **Trivy + Harbor 扫描** | 镜像漏洞扫描 | CI/CD 前置拦截 CVE |
| **Argo CD** | GitOps 部署 | 审计/回滚/多集群部署 |
| **Tekton / GitLab CI** | 流水线 | 构建+推送镜像+触发 Argo CD |
| **BuildKit（Docker buildx）** | 多架构/缓存构建 | 加速 CI |
| **对应中间件 Operator** | Strimzi Kafka、Percona MySQL、Redis Operator、ECK ES | 中间件云原生化，别裸部署 StatefulSet |
| **Cilium（替换 Flannel）** | 网络+策略+Hubble 可观测 | 生产高性能首选 |
| **Thanos / Mimir** | Prometheus 长期存储 HA | 30 天+ 历史指标 |
| **Rook+Ceph 或 Longhorn** | 自建 CSI 存储（用了云盘跳过）| 自建集群必备 |

### 12.3 高级栈（大规模、零信任、深度治理）

企业增强 +：

| 组件 | 作用 |
|------|------|
| **Istio / Linkerd** | 服务网格，替代 Sentinel/Ribbon/Gateway 代码 |
| **Falco** | 运行时安全 |
| **SPIRE** | 零信任身份（配合 Istio 深度安全） |
| **Argo Rollouts** | 渐进式交付（金丝雀/蓝绿，替代原生 Deployment rolling update）|
| **Karmada / Clusternet** | K8s 多集群联邦（多 Region/多活集群）|
| **Vitess / TiDB Operator** | 数据库分布式化 |
| **Vault + External Secrets Operator** | 统一密钥管理（不要把数据库密码写 YAML 里）|

### 12.4 一张图总结 SpringCloud → CNCF 组件映射

```
 SpringCloud 组件             替换/可选 CNCF 项目                是否必须替换？
─────────────────┬──────────────────────────────────────────┬─────────────────
 Eureka / Nacos  │ K8s Service + CoreDNS （服务发现）         │ 渐进式：可并存
                 │                                         │
 Config Server   │ ConfigMap + Secret / Vault + ESO        │ 动态刷新保留 Nacos
                 │                                         │
 Ribbon LB       │ K8s kube-proxy (iptables/ipvs)          │ 可保留 Ribbon 叠加
                 │                                         │
 Zuul / Gateway  │ Ingress Controller (Nginx/Contour)      │ Ingress 做边缘
                 │ + Istio Gateway 内部                    │ Gateway 按需保留
                 │                                         │
 Hystrix/Sentinel│ Istio（Envoy 超时/重试/熔断）            │ 深度才要 Istio
                 │                                         │
 Sleuth/Zipkin   │ OpenTelemetry Java Agent + Jaeger/Tempo│ ✅ 推荐替换
                 │                                         │
 Micrometer      │ Prometheus JMX Exporter + OTel          │ ✅ 推荐替换
                 │                                         │
 ELK 自建        │ Fluent Bit + ES/Loki                    │ ✅ 推荐 K8s DaemonSet 采集
                 │                                         │
 Jenkins / GitLab│ Argo CD + Tekton / GitLab CI             │ 部署模式切换 GitOps
                 │                                         │
 Artifactory     │ Harbor（私有镜像仓库）                   │ ✅ 必装
```

### 12.5 学习顺序建议（8-12 周全栈落地节奏）

| 周次 | 主题 | 学完你能做什么 |
|------|------|---------------|
| 1-2 | K8s 基础 + CKA 级知识 + CNI/CSI/CRI 原理 + kubectl 常用操作 | 能把单个 SpringBoot JAR 容器化部署成 Deployment + Service |
| 3 | Helm + Harbor + Dockerfile/BuildKit 多阶段构建 | 能给所有微服务写 Chart、推私有仓库、多环境 values 区分 |
| 4 | Ingress + cert-manager + CoreDNS 调试 | 能把服务通过 HTTPS 域名对外暴露，跑通外部调用 |
| 5 | Prometheus + Grafana + Alertmanager + OTel/JMX Exporter | 有全链路监控面板、告警到钉钉 |
| 6 | Fluent Bit + Loki/ES + Jaeger + OTel Agent | 日志集中、链路追踪可视化 |
| 7 | Argo CD + GitOps + Trivy 镜像扫描 | CI 构建 → 自动 Argo 同步部署；漏洞扫描拦截 |
| 8 | Gatekeeper/Kyverno + 中间件 Operator | 策略规范化；MySQL/Redis/Kafka 全 Operator 管理 |
| 9-10 | Cilium 网络策略 + Hubble 可视化 + Nginx Ingress 性能调优 | 网络安全加固、流量观察 |
| 11-12 | Istio 入门 / Argo Rollouts 金丝雀发布 | 跑通无侵入灰度发布，决定是否深度服务网格 |

> 最后提醒：不要一次引入太多组件。**每引入一个 CNCF 项目，都要评估团队的运维能力是否匹配**。先跑稳 MVP，再逐步加高级能力——这条原则是 99% 云原生落地成功团队的共同经验。
