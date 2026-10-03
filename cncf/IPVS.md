# IPVS 深度指南（小白友好）

> 从四层负载均衡的本质 → LVS/IPVS 的前世今生 → 内核里的两张核心表 → kube-proxy ipvs 模式 → 动手实验的完整手册。
> （基于 Linux 内核 5.x/6.x IPVS 与 Kubernetes v1.33–1.36 现状整理，2026-10）

---

## 目录

- [一、IPVS 是什么](#一ipvs-是什么)
  - [1.1 一句话定义](#11-一句话定义)
  - [1.2 从 LVS 说起：中国开发者写进 Linux 内核的传奇](#12-从-lvs-说起中国开发者写进-linux-内核的传奇)
  - [1.3 IPVS 在 Kubernetes 里的位置](#13-ipvs-在-kubernetes-里的位置)
  - [1.4 重要现状：IPVS 模式已被标记弃用（2025 起）](#14-重要现状ipvs-模式已被标记弃用2025-起)
- [二、前置知识：四层负载均衡与 Netfilter](#二前置知识四层负载均衡与-netfilter)
  - [2.1 四层负载均衡在干什么：DNAT 的艺术](#21-四层负载均衡在干什么dnat-的艺术)
  - [2.2 关键术语：CIP/VIP/DIP/RIP、Director/RS](#22-关键术语cipvipdipripredirectorrs)
  - [2.3 Netfilter 五个钩子点：IPVS 挂在哪里](#23-netfilter-五个钩子点ipvs-挂在哪里)
  - [2.4 为什么 IPVS 必须挂在 INPUT 链上](#24-为什么-ipvs-必须挂在-input-链上)
- [三、IPVS 核心实现原理](#三ipvs-核心实现原理)
  - [3.1 内核里的两张核心表：虚拟服务表 + 连接表](#31-内核里的两张核心表虚拟服务表--连接表)
  - [3.2 一个数据包的完整处理流程（NAT 转发）](#32-一个数据包的完整处理流程nat-转发)
  - [3.3 连接表的快路径：为什么后续包不再查调度算法](#33-连接表的快路径为什么后续包不再查调度算法)
  - [3.4 十种调度算法详解](#34-十种调度算法详解)
  - [3.5 三种转发模式：NAT / DR / TUN](#35-三种转发模式nat--dr--tun)
  - [3.6 IPVS 自己的连接状态机与超时](#36-ipvs-自己的连接状态机与超时)
  - [3.7 IPVS 不做的事：健康检查](#37-ipvs-不做的事健康检查)
- [四、Kubernetes 中的 IPVS：kube-proxy ipvs 模式](#四kubernetes-中的-ipvskube-proxy-ipvs-模式)
  - [4.1 核心技巧：kube-ipvs0 假网卡](#41-核心技巧kube-ipvs0-假网卡)
  - [4.2 副手戏：ipset 与少量 iptables 规则](#42-副手戏ipset-与少量-iptables-规则)
  - [4.3 Service/Endpoints → IPVS 规则的映射关系](#43-serviceendpoints--ipvs-规则的映射关系)
  - [4.4 一个 ClusterIP 请求在 ipvs 模式下的完整旅程](#44-一个-clusterip-请求在-ipvs-模式下的完整旅程)
  - [4.5 kube-proxy 会自动管理的内核参数](#45-kube-proxy-会自动管理的内核参数)
  - [4.6 iptables 模式 vs ipvs 模式 vs nftables 模式 vs eBPF](#46-iptables-模式-vs-ipvs-模式-vs-nftables-模式-vs-bepf)
- [五、动手实战：从零跑通](#五动手实战从零跑通)
  - [5.1 环境准备：加载内核模块、安装 ipvsadm](#51-环境准备加载内核模块安装-ipvsadm)
  - [5.2 手工搭建一个经典 LVS NAT 负载均衡器](#52-手工搭建一个经典-lvs-nat-负载均衡器)
  - [5.3 观察连接表与统计：ipvsadm 输出逐列解读](#53-观察连接表与统计ipvsadm-输出逐列解读)
  - [5.4 在 Kubernetes 中启用并验证 ipvs 模式](#54-在-kubernetes-中启用并验证-ipvs-模式)
  - [5.5 验证会话保持（sessionAffinity）](#55-验证会话保持sessionaffinity)
  - [5.6 串讲：一个请求在内核中的完整旅程](#56-串讲一个请求在内核中的完整旅程)
- [六、生产实践建议与常见坑](#六生产实践建议与常见坑)
- [七、总结与参考资料](#七总结与参考资料)

---

## 一、IPVS 是什么

### 1.1 一句话定义

**IPVS（IP Virtual Server）是 Linux 内核自带的四层（传输层）负载均衡器**，它能以内核态的性能把发往一个"虚拟 IP:端口"的流量，按调度算法分发给一组真实服务器。

它是 LVS（Linux Virtual Server）项目的内核实现部分。在 Kubernetes 世界里，你几乎一定通过 `kube-proxy` 的 **ipvs 模式**接触过它——kube-proxy 不再往 iptables 里塞规则，而是调用 IPVS 来完成 ClusterIP/NodePort 的负载均衡。

一句话记住三者关系：

```
LVS（项目/架构思想） ⊃ IPVS（内核模块，干活的） → 被 kube-proxy 复用为 Service 的数据面
```

### 1.2 从 LVS 说起：中国开发者写进 Linux 内核的传奇

IPVS 的故事要从一个中国人讲起。

- **1998 年 5 月**，浙江大学博士生**章文嵩**发起 LVS 项目——目标是让廉价的服务器集群对外表现为一台"虚拟服务器"，用负载均衡扛住网站流量。
- **2000 年前后**，IPVS 进入 Linux 内核主线（2.4 系列），成为内核自带的负载均衡能力——这是**极少数由华人主导并合入内核主线的重量级网络子系统**。
- 2000 年代，LVS 是中国互联网大厂（淘宝、阿里、新浪等）入口层负载均衡的事实标准，单机转发百万级并发连接；淘宝还衍生出FullNAT 等增强版本。
- 2010 年代，K8s 诞生后，kube-proxy 在 1.8 引入 ipvs 模式（beta）、**1.11 GA（2018 年）**，二十多岁的 LVS 思想无缝进入云原生时代。

理解这段历史的意义：**IPVS 是一个打磨了二十多年的成熟内核组件**，它解决的问题（入口流量分发）比 K8s 早了整整十五年。

### 1.3 IPVS 在 Kubernetes 里的位置

kube-proxy（每个节点一个，负责实现 Service 的虚拟 IP）有三种数据面模式：

```
                    Service (ClusterIP 10.96.0.10:80)
                                 │
                                 ▼
                    kube-proxy 把它"实现"出来，模式三选一：
┌──────────────────────┬──────────────────────┬────────────────────────┐
│  iptables 模式        │  ipvs 模式            │  nftables 模式          │
│  (历史默认)           │  (1.11 GA, 本文主角)   │  (1.29 alpha→1.33 GA)  │
│  规则表逐条匹配        │  内核哈希表+连接表      │  nftables 规则集         │
└──────────────────────┴──────────────────────┴────────────────────────┘
                                 │
                                 ▼
                    DNAT 到某个后端 Pod (10.244.x.x:8080)
```

- **iptables 模式**：为每个 Service/后端生成 iptables 规则链，逐条线性匹配，大规模时性能差（详见 [Cilium.md](Cilium.md) 2.5 节）。
- **ipvs 模式**：改用内核 IPVS 模块，O(1) 哈希查找 + 独立连接表 + 丰富的调度算法。
- **nftables 模式**：iptables 的现代继任者（nftables 是内核新的包过滤框架），1.33 GA，社区推荐的新方向。

### 1.4 重要现状：IPVS 模式已被标记弃用（2025 起）

写这份笔记时必须交代清楚的现实（2026-10）：

| 时间 | 事件 |
|------|------|
| 1.29（2023） | nftables 模式 alpha |
| 1.31（2024） | nftables 模式 beta |
| **1.33（2025-04）** | **nftables 模式 GA**，性能优于 iptables 和 IPVS 两种模式 |
| **1.35（2025 下半年）** | **kube-proxy 的 ipvs 模式被标记 deprecated**，启动时会输出警告："The ipvs proxier is now deprecated and may be removed in a future release. Please use 'nftables' instead."（KEP-5495） |

**这对你意味着什么：**

1. **存量集群不用恐慌**：弃用（deprecated）≠ 移除，IPVS 模式仍受支持，全球海量集群在用，维护期还会很长；
2. **新集群做技术选型时**：优先评估 nftables 模式；对可观测/安全有更高要求则看 eBPF 方案（Cilium，见 [Cilium.md](Cilium.md)）；
3. **为什么还要学 IPVS**：一是全球存量巨大、排障绕不开；二是 LVS/IPVS 的"虚拟服务 + 连接表 + 调度算法"三件套是**所有负载均衡器的通用心智模型**（Nginx、HAProxy、云 SLB 底层思想一致）；三是经典 IDC 场景 LVS 依然是入口层利器。

---

## 二、前置知识：四层负载均衡与 Netfilter

### 2.1 四层负载均衡在干什么：DNAT 的艺术

所谓四层负载均衡，工作在 TCP/UDP 层——它**不理解 HTTP**，只看"目标 IP + 端口 + 协议"。它的核心动作其实就两个词：

- **DNAT**（目的地址转换）：把包的目标地址从"虚拟地址"改写成某个真实服务器的地址；
- **反向还原**：真实服务器的响应包回来时，把源地址改回"虚拟地址"，让客户端全程无感。

```
客户端视角:                     实际发生:
┌────────┐   请求 → 10.0.0.100:80   ┌─────────┐  DNAT  → 192.168.1.11:80
│ client │                          │ 负载均衡器│
│        │ ← 响应 ← 10.0.0.100:80 ──│ (改写地址)│ ← 响应 ← 192.168.1.11:80
└────────┘                          └─────────┘
   客户端全程只认识 10.0.0.100（VIP），根本不知道后面有几台服务器
```

加上"**选哪台**"（调度算法）和"**记住这条连接选了谁**"（连接表），就是负载均衡器的全部本质。

### 2.2 关键术语：CIP/VIP/DIP/RIP、Director/RS

LVS 世界的标准黑话，所有文档通用，必须记住：

```
                 CIP (Client IP)
                 203.0.113.5
                     │
                     │ 请求
                     ▼
        ┌─────────────────────────┐
        │  Director（调度器/负载均衡器）│  ← LVS 里也叫 "VS" (Virtual Server)
        │  VIP = 10.0.0.100        │  ← Virtual IP，对外提供服务的虚拟地址
        │  DIP = 192.168.1.1       │  ← Director IP，内网侧地址
        └───────┬──────────┬───────┘
                │          │
        ┌───────▼───┐  ┌───▼───────┐
        │ RS1        │  │ RS2       │   ← Real Server，真实服务器
        │ RIP 1.11   │  │ RIP 1.12  │   ← Real IP
        └───────────┘  └───────────┘
```

| 术语 | 全称 | 一句话解释 |
|------|------|-----------|
| Director | 调度器 | 就是负载均衡器本身（K8s 里角色由 kube-proxy+IPVS 扮演） |
| RS | Real Server | 真正干活的后端（K8s 里就是 Pod） |
| CIP | Client IP | 客户端地址 |
| VIP | Virtual IP | 对外的虚拟服务地址（K8s 的 ClusterIP/NodePort 就扮演 VIP） |
| DIP | Director IP | 调度器内网侧地址（NAT 模式用） |
| RIP | Real Server IP | 后端地址 |

### 2.3 Netfilter 五个钩子点：IPVS 挂在哪里

Linux 内核处理每个网络包，都会按固定顺序经过 Netfilter 的五个"关卡"：

```
外部来的包:
  网卡 → ① PREROUTING → 【路由决策】→ 是发给本机的?
                                        ├─ 是 → ② INPUT → 本机进程
                                        └─ 否(转发) → ③ FORWARD → ⑤ POSTROUTING → 网卡
本机进程发的包:
  进程 → ④ OUTPUT → 【路由决策】→ ⑤ POSTROUTING → 网卡
```

iptables 挂载所有五个点；**IPVS 只注册了其中关键的几个**：

- **INPUT（LOCAL_IN）**：IPVS 的主战场。发往 VIP 的包会走到这里，IPVS 在此拦截、调度、DNAT，然后把包"重新注入"转发路径送往 RS；
- **POST_ROUTING / LOCAL_OUT 附近**：负责回程包的地址还原（SNAT 回 VIP）。

### 2.4 为什么 IPVS 必须挂在 INPUT 链上

这是个精妙的设计点，值得单独讲，因为它直接解释了第四章 kube-proxy 的"魔术"。

IPVS 要拦截"发往 VIP 的包"，前提是**内核路由决策认为这个包是"发给本机的"**（这样才会走 INPUT 而不是 FORWARD）。经典 LVS 里，VIP 就直接配在调度器网卡上；K8s 的 ClusterIP 是个"虚拟"地址，谁都没配——所以 kube-proxy 把所有 ClusterIP 绑到一个假网卡上（见 4.1），**人为制造"这是发给本机的包"的路由决策**，把包送进 INPUT 链的 IPVS 怀里。

> 记住这条因果链：**VIP 绑在本机 → 路由决策走 INPUT → IPVS 在 INPUT 拦截 → 调度转发**。后面所有实验都能用这条链解释。

---

## 三、IPVS 核心实现原理

### 3.1 内核里的两张核心表：虚拟服务表 + 连接表

IPVS 的全部状态就在两张哈希表里（都在内核内存中，用 `ipvsadm` 工具可以查看，见第五章）：

**第一张：虚拟服务表（service table）——"我对外提供什么服务"**

```
struct ip_vs_service（示意）:
  协议 + 地址 + 端口        →  TCP 10.0.0.100:80
  调度算法                  →  wlc
  后端列表 (dest list)      →  [RS1 1.11:80 w=1, RS2 1.12:80 w=3, ...]
  会话保持配置               →  persistence 300s (可选)
```

- 每条"虚拟服务"对应一个 VIP:Port（K8s 里就是每个 Service 的每个端口）；
- 服务表按 `(协议, 地址, 端口)` 哈希组织，另有一张按 fwmark 组织的表（防火墙标记法，多端口服务共用一条虚拟服务时用）；
- 每个后端（`struct ip_vs_dest`）记录权重、活跃/非活跃连接数、上下限阈值（超过可标记过载）。

**第二张：连接表（connection table）——"每条连接我调度给了谁"**

```
struct ip_vs_conn（示意）:
  key:    {协议, CIP:CPort, VIP:VPort}        ← 客户端看到的五元组
  value:  {RIP:RPort, 状态, 超时, 指向 dest 的引用} ← 实际的后端
```

- **第一个包到达时**：查服务表 → 跑调度算法选中一个 RS → 创建连接表条目；
- **后续所有包**：直接按五元组哈希查连接表 → O(1) 拿到"这条连接去哪个 RS"，**不再跑调度算法**；
- 响应包的反向地址还原也靠它。

这两张表就是 IPVS 的灵魂：**服务表回答"有哪些服务"，连接表回答"每条连接去了哪"**。对比 iptables 模式——iptables 没有后者的概念（它只能借用 conntrack），每个包都要重新走规则匹配。

### 3.2 一个数据包的完整处理流程（NAT 转发）

以 NAT 模式（K8s 用的就是这种）为例，跟踪一个请求的完整内核旅程：

```
                        Director / kube-proxy 节点
┌────────────────────────────────────────────────────────────────────┐
│                                                                    │
│  入包: CIP:53210 → VIP:80                                          │
│    ↓                                                               │
│  ① PREROUTING: IPVS 不动它 (iptables 可能打标记)                     │
│    ↓                                                               │
│  ② 路由决策: VIP 绑在本机 → 走 INPUT                                 │
│    ↓                                                               │
│  ③ INPUT 链 [IPVS hook]:                                           │
│     - 查连接表: 新连接, 没有 →                                       │
│     - 查服务表: TCP VIP:80 → wlc 算法 → 选中 RS2 (1.12)             │
│     - 在连接表创建条目: {CIP:53210, VIP:80} → {1.12:80}             │
│     - DNAT: 包头目标改为 1.12:80                                    │
│     - 重新注入转发路径 ↓                                            │
│    ↓                                                               │
│  ④ FORWARD → ⑤ POSTROUTING:                                        │
│     - 如需做源地址转换(见 3.5 NAT 模式说明)则 SNAT 成 DIP             │
│     - 发往 RS2                                                     │
└────────────────────────────────────────────────────────────────────┘

回程: RS2 响应 1.12:80 → CIP:53210（网关指向 Director）
    → 回到 Director → FORWARD → POSTROUTING 附近 [IPVS 回程 hook]:
      按五元组反查连接表 → SNAT: 源地址 1.12:80 改回 VIP:80 → 发给客户端
      客户端看到的一切源地址都是 VIP，全程无感
```

### 3.3 连接表的快路径：为什么后续包不再查调度算法

这是 IPVS 高性能的关键，值得反复强调：

```
第 1 个包 (SYN):  查服务表(哈希) → 调度算法 → 建连接表条目 → DNAT     [慢路径, 但也只是 O(1)]
第 2~N 个包:      查连接表(哈希) → 直接 DNAT/转发                    [快路径, O(1)]
响应包:           按五元组反查连接表 → SNAT 还原                     [O(1)]
```

对比 iptables 模式"每个包都逐条遍历规则链"，IPVS 把"选择后端"这个有状态的动作**收敛到了每条连接的第一个包**，后续全部走哈希直查。这带来三个直接好处：

1. **延迟恒定**：不随 Service 数量增长（iptables 是 O(规则数)）；
2. **连接一致性天然保证**：同一条连接永远去同一个后端（连接表保证），不需要额外亲和机制；
3. **规则更新不影响存量连接**：Service 变了，连接表里已建立的连接按自己的 dest 继续走完（配合超时策略），没有"规则重放期间的雪崩"。

### 3.4 十种调度算法详解

IPVS 内置十种调度算法（这是它比 iptables 模式"随机选择"丰富的最大卖点）。kube-proxy 默认用 `rr`：

| 算法 | 全称 | 一句话原理 | 适用场景 |
|------|------|-----------|---------|
| `rr` | 轮询 Round Robin | 一个一个轮着来 | **K8s 默认**，后端能力相近 |
| `wrr` | 加权轮询 | 按权重轮流，权重大的多拿 | 后端性能不均 |
| `lc` | 最少连接 | 谁手上的连接少给谁 | 连接时长差异大 |
| `wlc` | 加权最少连接 | (活跃连接数/权重) 最小者优先 | 经典通用，LVS 默认推荐 |
| `lblc` | 基于局部性的最少连接 | 同一目标尽量去同一缓存节点 | 缓存集群（透明代理） |
| `lblcr` | 带复制的局部性最少连接 | lblc 的副本容错版 | 大型缓存集群 |
| `dh` | 目标地址哈希 | 对目标 IP 哈希定后端 | 防火墙/多调度器一致性 |
| `sh` | 源地址哈希 | 对源 IP 哈希定后端 | 无持久化配置时近似会话保持 |
| `sed` | 最短期望延迟 | (连接数+1)/权重 最小者优先 | wlc 的改进，大权重差场景 |
| `nq` | 永不排队 | 先保证每人至少一个连接再按速率轮 | 权重差异极大时比 sed 更平滑 |

在 K8s 里修改调度算法（kube-proxy 配置）：

```yaml
# kube-system/kube-proxy configmap
apiVersion: kubeproxy.config.k8s.io/v1alpha1
kind: KubeProxyConfiguration
mode: ipvs
ipvs:
  scheduler: "wlc"     # 默认空 = rr；可选 rr|lc|wlc|lblc|lblcr|dh|sh|sed|nq
```

> 实用直觉：K8s 场景下 `rr` 就够用（kube-proxy 只做 IP 层均衡，连接数天然分散）；`sh`/`sh` 类算法常用于想"白嫖"会话保持的场景；经典 LVS 入口层首选 `wlc`。

### 3.5 三种转发模式：NAT / DR / TUN

LVS 最著名的设计就是三种"把包送到 RS"的方式。理解它们需要一点耐心，但这是理解所有负载均衡架构的基石。

**模式一：NAT（网络地址转换）——K8s 用的就是它**

- 原理：请求和响应**都经过调度器**，双向改地址（请求 DNAT 成 RIP，响应 SNAT 回 VIP），即 3.2 的完整流程；
- 约束：RS 的**默认网关必须指向调度器**（保证回程路过它）；RS 可以在任意网段（甚至公网）；
- 代价：调度器要处理**双向全部流量**，容易成为带宽瓶颈；
- 典型场景：K8s Service、通用内网负载均衡。

```
CIP ──请求──→ [Director: DNAT] ──→ RIP (网关=DIP)
CIP ←──响应── [Director: SNAT] ←── RIP
                ↑ 双向都过调度器
```

**模式二：DR（Direct Routing，直接路由）——性能之王**

- 原理：调度器**只改链路层的 MAC 地址**（目标 MAC 改成选中 RS 的 MAC），不改 IP；RS 收到后发现自己的 IP 就是 VIP（配在 loopback 上），**直接把响应发给客户端，不回调度器**；
- 妙处：回程流量完全绕过调度器——响应（通常远大于请求）零调度器开销，调度器只处理入方向；
- 约束（三条都要满足，也是它麻烦的地方）：
  1. 调度器和 RS 必须在**同一二层网络**（MAC 改写要求广播域可达）；
  2. RS 的 loopback 上要配 VIP，且设置 `arp_ignore=1, arp_announce=2`，否则 RS 会抢着应答 VIP 的 ARP，调度器被绕过；
  3. 不能改端口（纯 MAC 改写，端口必须是 80→80）；
- 典型场景：高吞吐网站入口层（CDN 回源、大促入口）。

```
CIP ──请求(VIP)──→ [Director: 只改目标MAC→RS1] ──(二层)──→ RS1
CIP ←─────────────── 响应直接由 RS1 发出 (src=VIP) ───────────┘
                      ↑ 调度器完全不参与回程
```

**模式三：TUN（IP 隧道）——跨机房版 DR**

- 原理：调度器把原始 IP 包**整体封装进一个新的 IP 包**（IPIP 隧道）发往 RS；RS 解封装后发现内层目标是自己 loopback 上的 VIP，直接响应客户端；
- 与 DR 的区别：RS 可以在**任意三层可达的网段**（跨机房、异地容灾），不受二层限制；代价是多一层封装开销、RS 要支持 IPIP 解封装（内核默认支持）；
- 典型场景：异地多活的分发节点。

| | NAT | DR | TUN |
|---|-----|----|----|
| 改写内容 | IP 双向改写 | 只改 MAC | 外层再套 IP |
| 回程路径 | 经过调度器 | **直接回客户端** | 直接回客户端 |
| RS 网段要求 | 任意（网关指向 DIP） | 同一二层 | 三层可达即可 |
| 支持改端口 | ✓ | ✗ | ✗ |
| 调度器带宽压力 | 双向全量 | 只有入向 | 只有入向 |
| K8s kube-proxy | ✓（DNAT 部分） | ✗ | ✗ |

> 为什么 kube-proxy 只用 NAT？因为 K8s 的后端是**动态变化的 Pod**，天然不在调度器同二层，且需要端口转换（ClusterIP:80 → Pod:8080）——NAT 是唯一自然的选择。DR/TUN 属于经典 IDC 知识，但面试和架构设计高频出现。

### 3.6 IPVS 自己的连接状态机与超时

IPVS 为每条连接维护自己的状态（TCP: SYN_RECV → ESTABLISHED → FIN_WAIT → CLOSE → TIME_WAIT...；UDP 只有简单状态），每个状态有独立超时，超时后从连接表删除。可通过内核参数调整：

```bash
/proc/sys/net/ipv4/vs/timeout_tcp        # TCP 连接空闲超时
/proc/sys/net/ipv4/vs/timeout_tcp_finwnd # FIN_WAIT 状态超时
/proc/sys/net/ipv4/vs/timeout_close      # CLOSE 状态超时
/proc/sys/net/ipv4/vs/timeout_idle       # 空闲(如 UDP)超时
```

> 注意：这是 **IPVS 自己的表**，和内核 Netfilter 的 conntrack（`nf_conntrack` 模块）是**两套并行的连接追踪**。K8s 里两者都在工作（conntrack 管伪装/回程，IPVS 管调度），所以第六章会有"两套表相互打架"的经典坑。

另外，IPVS 还有**状态同步守护进程**（sync daemon，master/backup 模式，通过组播 224.0.0.81:8848 同步连接表），用于"调度器主备切换后长连接不断"的经典 IDC 高可用方案。K8s 场景不涉及，但说明它为电信级可靠性设计过。

### 3.7 IPVS 不做的事：健康检查

**IPVS 内核本身完全不做应用层健康检查**——RS 挂了它照样往那转发。经典 LVS 靠外围工具补课：

- `keepalived`：监听 RS 健康状态，挂了就从服务表摘除（同时负责调度器自身 VRRP 主备）；
- `ldirectord`：类似的健康检查守护进程。

而 **K8s 里这个角色由 kube-proxy 承担**：它 watch Endpoints/EndpointSlice 变化（readiness 探针失败 → endpoint 摘除 → kube-proxy 调用 IPVS 把对应 dest 删除），分工非常清晰：

```
readinessProbe (kubelet) → Endpoints 对象 → kube-proxy watch → IPVS dest 增删
```

这也解释了一个经典故障：**Pod 探针没配好（或没配）时，IPVS 会把流量发给一个已经僵死的后端**——内核层面它无辜，是控制面没把 RS 摘掉。

---

## 四、Kubernetes 中的 IPVS：kube-proxy ipvs 模式

### 4.1 核心技巧：kube-ipvs0 假网卡

回忆 2.4 的因果链：IPVS 挂在 INPUT，需要"包被路由决策认为发给本机"。但 K8s 的 ClusterIP（10.96.0.10）是个纯虚拟地址，不属于任何真实网卡——包根本进不了 INPUT。

kube-proxy 的解法非常聪明：**创建一块 dummy（哑）网卡 `kube-ipvs0`，把每个 ClusterIP 以 /32 地址的形式绑上去**：

```bash
$ ip addr show kube-ipvs0 | head
5: kube-ipvs0: <BROADCAST,NOARP> mtu 1500 qdisc noop state DOWN
    link/ether aa:bb:cc:dd:ee:ff brd ff:ff:ff:ff:ff:ff
    inet 10.96.0.1/32 scope global kube-ipvs0        ← kubernetes service
    inet 10.96.0.10/32 scope global kube-ipvs0       ← coredns service
    inet 10.96.123.45/32 scope global kube-ipvs0     ← 你的每个 Service 一个 IP
    ...
```

细节很有意思：

- **NOARP + DOWN 状态**：这块网卡不收发真实数据包，纯粹是让内核协议栈"相信这些 IP 是本机的"；
- 于是发往 ClusterIP 的包，路由决策判定"目标为本机" → 进入 **INPUT 链** → 撞上 IPVS 的钩子 → 调度转发；
- 这等于把经典 LVS "VIP 配在调度器网卡上"的手工操作，变成了全自动的动态绑定（Service 增删，kube-ipvs0 上的地址同步增删）。

### 4.2 副手戏：ipset 与少量 iptables 规则

纯靠 IPVS 还不够，还需要一点 iptables/nftables 打配合，但**用的是 ipset（地址集合）而不是逐条规则**：

```bash
$ ipset list KUBE-CLUSTER-IP | head
Name: KUBE-CLUSTER-IP
Type: hash:ip,port
Members:
10.96.0.10,tcp:53
10.96.0.1,tcp:443
10.96.123.45,tcp:80
...
```

固定不变的少量 iptables 规则引用这些集合：

```
PREROUTING:  目标在 KUBE-CLUSTER-IP / KUBE-NODE-PORT-* 集合?
             → 打上 masquerade 标记 (0x4000)
INPUT:       IPVS 在这里接管，做 DNAT
POSTROUTING: 带 0x4000 标记的包 → MASQUERADE (源地址伪装)
```

对比 iptables 模式"每个 Service 贡献一组链"，ipvs 模式的 iptables 规则是**固定的几条**，Service 增删只改 ipset 成员和 IPVS 表——所以大规模下同步快、开销低。常用 ipset 一览：

| ipset | 存什么 |
|-------|--------|
| `KUBE-CLUSTER-IP` | 所有 ClusterIP:端口（配合打标记） |
| `KUBE-NODE-PORT-TCP/UDP` | NodePort 端口 |
| `KUBE-EXTERNAL-IP` | ExternalIP |
| `KUBE-LOAD-BALANCER(_LOCAL)` | LoadBalancer 服务的入口地址 |
| `KUBE-LOOP-BACK` | hairpin（Pod 访问自己所在 Service）特殊场景 |

### 4.3 Service/Endpoints → IPVS 规则的映射关系

kube-proxy 把 K8s 对象翻译成 IPVS 的三样东西，一一对应：

```
Kubernetes 对象                     IPVS 内核对象
─────────────────                  ──────────────────────────────
Service (ClusterIP+port)     →     ip_vs_service (虚拟服务)
  port.protocol/port.number        调度算法: rr (可配)
  sessionAffinity: ClientIP  →     persistence (会话保持, 10800s 默认?)
Endpoints / EndpointSlice     →     ip_vs_dest (真实服务器, 每个就绪的
  每个就绪 Pod 的 IP:port            后端一个, weight=1)
节点路由技巧                    →     kube-ipvs0 绑定 + ipset 成员
```

补充两个细节：

- **NodePort**：每台节点上，NodePort 流量经 ipset 打标记后也进 INPUT 由 IPVS 处理（虚拟服务的地址是本机 IP，端口是 nodePort）；
- **endpointSlice 变更**：kube-proxy 做的是**增量更新**——只删掉旧 dest、加上新 dest，不动其他 Service；iptables 模式历史上是全量重放规则，这是大规模下两者同步延迟差异的根源。

### 4.4 一个 ClusterIP 请求在 ipvs 模式下的完整旅程

```
Pod A (10.244.0.10) curl http://deathstar.default.svc.cluster.local

 ① DNS: 解析出 ClusterIP 10.96.0.10
 ② SYN 包: 10.244.0.10:40001 → 10.96.0.10:80  (Pod A 视角)
 ③ 包到达节点协议栈:
     PREROUTING: 目标 ∈ KUBE-CLUSTER-IP → 打标记 0x4000
 ④ 路由决策: 10.96.0.10 绑在 kube-ipvs0 → 本机 → INPUT
 ⑤ INPUT [IPVS]:
     查服务表: 10.96.0.10:80 (rr) → 选中 dest: 10.244.0.21:8080
     查连接表: 新连接 → 创建条目
     DNAT: 目标改写为 10.244.0.21:8080
 ⑥ FORWARD → POSTROUTING:
     带 0x4000 标记 → MASQUERADE: 源地址改为节点 CNI 网桥地址
     (目的: 保证响应包回到本节点, 这是 NAT 模式的完整形态)
 ⑦ 包到达 deathstar Pod
 ⑧ 响应回程: conntrack 反查还原源地址 → 回到节点 → IPVS 连接表反查
     → 源地址还原成 10.96.0.10:80 → 送回 Pod A
 ⑨ Pod A 后续的包: ③⑤⑧全部短路——conntrack/连接表直接命中
```

### 4.5 kube-proxy 会自动管理的内核参数

ipvs 模式依赖若干内核参数，较新版本的 kube-proxy 会**自动检测并修正**（老版本 kubeadm 要求手工设置）：

| 参数 | 作用 |
|------|------|
| `net.ipv4.ip_forward=1` | 转发必须开 |
| `net.ipv4.vs.conntrack=1` | 让 IPVS 的 DNAT 也进 conntrack（需要伪装时必须） |
| `net.ipv4.vs.conn_reuse_mode` | 连接复用策略（0/1，见第六章大坑） |
| `net.ipv4.vs.expire_nodest_conn=1` | 目标后端被删时立刻断开关联连接（滚动更新体验的关键） |
| `net.ipv4.vs.expire_quiescent_template=1` | 后端摘除时同步清理会话保持模板 |

### 4.6 iptables 模式 vs ipvs 模式 vs nftables 模式 vs eBPF

| 维度 | iptables | ipvs | nftables | eBPF（Cilium） |
|------|----------|------|----------|----------------|
| 核心机制 | 规则链线性匹配 | 哈希表 + 连接表 | nftables 字节码规则集 | 内核程序 + map |
| 查找复杂度 | O(n) | O(1) | 接近 O(1)（set 查找） | O(1) |
| 调度算法 | 随机 | **10 种** | 随机 | 随机/Maglev 等 |
| 独立连接状态 | 无（借 conntrack） | **有** | 有 | 有（conntrack in map） |
| 规则更新 | 全量重放（新版本有优化） | 增量 | 增量（原子事务） | 增量 |
| 内核依赖 | 无额外 | ip_vs 模块 | nftables 内核（普遍具备） | 较新内核（4.19+） |
| K8s 状态 | 历史默认 | **1.35 起弃用**（仍在维护） | 1.33 GA，官方推荐方向 | CNCF 生态方案 |
| 适合 | 小集群/兼容 | 大规模存量集群 | **新集群首选** | 网络+安全+观测一体化需求 |

> 学习路径建议：**iptables 模式看懂"规则爆炸"，IPVS 看懂"有状态调度"，nftables 看懂"声明式规则集"，eBPF 看懂"可编程数据面"**——四者串起来就是 K8s 负载均衡的完整进化史。eBPF 路线详见 [Cilium.md](Cilium.md) 第六章。

---

## 五、动手实战：从零跑通

> 需要一台 Linux 环境（虚拟机/WSL2/云主机均可）。K8s 实验部分可以复用 [Cilium.md](Cilium.md) 9.1 的 kind 集群思路。

### 5.1 环境准备：加载内核模块、安装 ipvsadm

```bash
# 1. 加载 IPVS 相关内核模块
sudo modprobe ip_vs          # 核心
sudo modprobe ip_vs_rr       # 各调度算法是独立模块
sudo modprobe ip_vs_wrr
sudo modprobe ip_vs_sh
sudo modprobe nf_conntrack   # 连接追踪（K8s 依赖）

# 验证
lsmod | grep -e ip_vs -e nf_conntrack
# ip_vs_sh              16384  0
# ip_vs_wrr             16384  0
# ip_vs_rr              16384  0
# ip_vs                172032  6 ip_vs_wrr,ip_vs_rr,ip_vs_sh
# nf_conntrack         172032  1 ip_vs

# 2. 开机自动加载（生产必做）
cat <<EOF | sudo tee /etc/modules-load.d/ipvs.conf
ip_vs
ip_vs_rr
ip_vs_wrr
ip_vs_sh
nf_conntrack
EOF

# 3. 安装用户态管理工具 ipvsadm (它通过 setsockopt 与内核 IPVS 通信)
sudo apt install -y ipvsadm     # Debian/Ubuntu
sudo yum install -y ipvsadm     # CentOS/RHEL
```

> 为什么调度算法是独立模块？因为内核按需加载——`ipvsadm -A -t ... -s wlc` 时如果 `ip_vs_wlc` 没加载，命令会失败。kubeadm 装 ipvs 模式集群前会做 preflight 检查这批模块，缺了会直接报错，这是最常见的安装失败原因。

### 5.2 手工搭建一个经典 LVS NAT 负载均衡器

用 3 台虚拟机（或云主机）复刻 1998 年 LVS 的经典场景，做完你对"负载均衡器"的理解会具体一百倍。

**规划：**

```
                    client (10.0.0.9)
                        │
                        │ 请求 VIP: 10.0.0.100:80
                        ▼
              director (调度器)
              ├─ ens33: 10.0.0.100 (VIP, 对外)
              └─ ens37: 192.168.60.1 (DIP, 对内)
                        │
        ┌───────────────┴───────────────┐
        ▼                               ▼
  rs1 (192.168.60.11)           rs2 (192.168.60.12)
  nginx, 网关指向 DIP            nginx, 网关指向 DIP
```

**第一步：配置两台 RS（nginx 后端）**

```bash
# 在 rs1 / rs2 上分别执行 (IP 换成各自的):
sudo ip addr add 192.168.60.11/24 dev ens33
sudo ip route replace default via 192.168.60.1   # 关键! 网关必须指向 DIP (NAT 模式命门)

# 装个 nginx 并让页面显示自己是谁 (方便观察调度效果)
sudo apt install -y nginx
echo "Hello from RS1" | sudo tee /usr/share/nginx/html/index.html   # rs2 写 RS2
```

**第二步：配置 director**

```bash
# 开启转发 (NAT 模式必须)
sudo sysctl -w net.ipv4.ip_forward=1

# 定义虚拟服务: VIP:80, 调度算法 wlc
sudo ipvsadm -A -t 10.0.0.100:80 -s wlc

# 添加两个真实服务器, -m = NAT(masquerading) 模式, -w 权重
sudo ipvsadm -a -t 10.0.0.100:80 -r 192.168.60.11:80 -m -w 1
sudo ipvsadm -a -t 10.0.0.100:80 -r 192.168.60.12:80 -m -w 1

# 查看配置
sudo ipvsadm -Ln
```

**第三步：从 client 压测并观察**

```bash
# client 上:
for i in $(seq 1 10); do curl -s 10.0.0.100; done
# Hello from RS1
# Hello from RS2
# Hello from RS1
# Hello from RS2
# ... ← wlc 在两条健康后端间轮流分发
```

**第四步（重要体验）：故意弄挂一个 RS**

```bash
# rs1 上停掉 nginx:
sudo systemctl stop nginx

# client 继续请求 → 你会发现部分请求**卡住/失败**!
# 再看 director: 10.168.60.11 还在虚拟服务表里
# → 这就是 3.7 说的: IPVS 不做健康检查, 它不知道 RS 死了
# → 经典解法是 keepalived; K8s 里这个角色是 kube-proxy + readinessProbe

# 把 rs1 从虚拟服务摘除, 流量立即恢复:
sudo ipvsadm -d -t 10.0.0.100:80 -r 192.168.60.11:80
```

### 5.3 观察连接表与统计：ipvsadm 输出逐列解读

**虚拟服务表（`ipvsadm -Ln`）——对应 3.1 第一张表：**

```
$ sudo ipvsadm -Ln
IP Virtual Server version 1.2.1 (size=4096)        ← 连接表哈希桶数 2^12, 即 conn_tab_bits=12
Prot LocalAddress:Port Scheduler Flags
  -> RemoteAddress:Port           Forward Weight ActiveConn InActConn
TCP  10.0.0.100:80 wlc
  -> 192.168.60.11:80             Masq    1      3          12
  -> 192.168.60.12:80             Masq    1      4          11
```

逐列解释：

| 字段 | 含义 |
|------|------|
| `(size=4096)` | 连接表哈希桶数量（由 `conn_tab_bits` 决定，见第六章调优） |
| `Masq` | 转发模式：Masq=NAT，Route=DR，Tunnel=TUN |
| `Weight` | 权重（`-w` 设置的） |
| `ActiveConn` | 活跃连接数（处于 ESTABLISHED 类状态） |
| `InActConn` | 非活跃连接数（TIME_WAIT 等将死未死状态） |

**连接表（`ipvsadm -Lnc`）——对应 3.1 第二张表：**

```
$ sudo ipvsadm -Lnc
IPVS connection entries
pro state expire    source           virtual            destination
TCP ESTABLISHED 04:12  10.0.0.9:53210   10.0.0.100:80      192.168.60.12:80
TCP TIME_WAIT   00:45  10.0.0.9:53212   10.0.0.100:80      192.168.60.11:80
```

一行 = 一条真实连接：客户端五元组 → VIP → 实际后端，`expire` 是剩余超时。**这就是"快路径"的真身：后续包按前两列哈希，O(1) 命中第三、四列。**

**流量统计（`--stats` / `--rate`）：**

```bash
$ sudo ipvsadm -Ln --stats     # 累计: Conns InPkts OutPkts InBytes OutBytes
$ sudo ipvsadm -Ln --rate      # 速率: CPS  InPPS OutPPS  InBPS  OutBPS
```

### 5.4 在 Kubernetes 中启用并验证 ipvs 模式

**方式一：已有的 kubeadm 集群（热切换）**

```bash
# 1. 修改 kube-proxy 配置: mode: "" → mode: "ipvs"
kubectl edit cm kube-proxy -n kube-system
#    mode: "ipvs"

# 2. 重启 kube-proxy 使配置生效
kubectl -n kube-system rollout restart ds/kube-proxy
```

**方式二：kind 集群一步到位**

```yaml
# kind-ipvs.yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
kubeadmConfigPatches:
- |
  apiVersion: kubeproxy.config.k8s.io/v1alpha1
  kind: KubeProxyConfiguration
  mode: "ipvs"
```

```bash
kind create cluster --name ipvs-lab --config kind-ipvs.yaml
```

> 注意：kind 节点是容器，与宿主机共享内核。ip_vs 模块必须在 Docker 所在的 Linux 内核上可用（Linux 宿主机直接 `modprobe`；Mac/Windows 的 Docker Desktop 虚拟机若缺模块，kube-proxy 起不来时优先怀疑这里）。

**验证（把第四章讲的全部"看"一遍）：**

```bash
# ① 确认模式生效
kubectl logs -n kube-system ds/kube-proxy | grep -i "ipvs\|mode"

# ② 内核模块已加载
lsmod | grep ip_vs

# ③ 看 kube-ipvs0: 每个 Service 的 ClusterIP 都绑在上面!
ip addr show kube-ipvs0
#     inet 10.96.0.1/32 ...
#     inet 10.96.0.10/32 ...

# ④ 看 IPVS 虚拟服务表 —— 集群里每个 Service 一个条目:
ipvsadm -Ln
# TCP  10.96.0.10:53 rr
#   -> 10.244.0.5:53              Masq    1      0          0
#   -> 10.244.0.6:53              Masq    1      0          0

# ⑤ 看 ipset 集合
ipset list KUBE-CLUSTER-IP | head

# ⑥ 部署个 demo 并观察连接表
kubectl create deployment web --image=nginx --replicas=2
kubectl expose deployment web --port=80
kubectl run curl --image=curlimages/curl -- sleep 3600
kubectl exec curl -- curl -s web   # 多次执行

# ⑦ 到节点里看连接表随请求变化 (Service IP → Pod IP 一目了然):
docker exec ipvs-lab-control-plane ipvsadm -Lnc | head
# TCP ESTABLISHED 04:00 10.244.0.10:41000 10.96.25.7:80 10.244.0.21:80
```

### 5.5 验证会话保持（sessionAffinity）

```bash
# 给 Service 开启 ClientIP 会话保持
kubectl patch svc web -p '{"spec":{"sessionAffinity":"ClientIP"}}'

# 看 IPVS 侧的变化 —— Flags 列出现 persistence:
ipvsadm -Ln
# TCP  10.96.25.7:80 rr persistent 10800    ← 同一客户端 3 小时内固定打到同一后端

# 多次 curl (同一 Pod 源 IP) → 永远命中同一个后端
kubectl exec curl -- curl -s web; kubectl exec curl -- curl -s web   # 返回一致的日志

# 还原
kubectl patch svc web -p '{"spec":{"sessionAffinity":"None"}}'
```

原理呼应 3.4：K8s 的 `sessionAffinity: ClientIP` 就是把 LVS 的 persistence（会话保持模板）机制打开了——第一个包调度后，模板记住"这个源 IP → 这个后端"，模板本身也有超时（默认 10800 秒，K8s 里可用 `sessionAffinityConfig.sessionAffinitySeconds` 调整）。

### 5.6 串讲：一个请求在内核中的完整旅程

把所有概念串成一条线（NAT + kube-proxy ipvs 模式）：

```
客户端 Pod curl → Service ClusterIP

 ① DNS 解析 → ClusterIP 10.96.0.10
 ② SYN: 10.244.0.10:40001 → 10.96.0.10:80
 ③ PREROUTING: ipset 命中 (KUBE-CLUSTER-IP) → 打 masquerade 标记
 ④ 路由决策: 10.96.0.10 在 kube-ipvs0 上 → 本机投递 → INPUT
 ⑤ INPUT [IPVS]:
     服务表命中 (10.96.0.10:80, rr) → 调度选 dest 10.244.0.21:8080
     连接表无记录 → 创建 {CIP:40001,VIP:80} → {RIP:8080}
     DNAT 改写目标地址
 ⑥ FORWARD → POSTROUTING: 有标记 → MASQUERADE (源改写为节点地址)
 ⑦ deathstar Pod 收到请求并响应
 ⑧ 回程: conntrack 还原源 IP → 回到本节点 → IPVS 连接表反查
     → 源地址改回 10.96.0.10:80 → 回到客户端 Pod
 ⑨ 同连接后续包: ③⑤⑧全部短路, 直接查 conntrack + IPVS 连接表
 ⑩ 全程每一步都是 O(1) 哈希查找; Service 数量增长只影响"服务表条目数",
    不影响任何单包的处理成本
```

对照 [Cilium.md](Cilium.md) 6.2 节的 eBPF 版旅程你会发现：**逻辑完全同构**——"服务表→调度→连接表→DNAT→反向还原"，IPVS 在 Netfilter 钩子里做，Cilium 在 TC/socket 钩子里做。差别在于表的组织、内核版本的利用率和可观测性。

---

## 六、生产实践建议与常见坑

**① 连接表大小调优（conn_tab_bits）**

```bash
# 默认 12 位 = 4096 个哈希桶 (ipvsadm -Ln 第一行的 size=4096)
# 大流量节点连接数远超 4096 时, 链表变长 → 查找变慢
# 调整: 以模块参数方式加载, 取值 8~20:
echo "options ip_vs conn_tab_bits=20" | sudo tee /etc/modprobe.d/ipvs.conf
# (20 位 = 1048576 桶, 桶表本身约 8MB 内存, 可忽略)
# 重载模块后生效; 验证: ipvsadm -Ln 第一行应显示 (size=1048576)
```

**② 滚动更新期间连接被拒（最著名的 IPVS 坑）**

- 现象：Pod 优雅终止/扩缩容期间，客户端偶发 `connection refused` 或连接串到旧后端；
- 根因：IPVS 连接复用策略与 conntrack 的竞争，后端已删但连接/模板还残留；
- 缓解：保证 `conn_reuse_mode=1` + `expire_nodest_conn=1` + `expire_quiescent_template=1`（kube-proxy 通常会自动设置），必要时升级内核（内核 4.19.73+/5.x 修复了相关竞态，参考 kubernetes/kubernetes#90817）。

**③ 内核模块没加载，安装直接失败**

kubeadm/kubespray 装 ipvs 模式前会 preflight 检查 `ip_vs`、`ip_vs_rr`、`ip_vs_wrr`、`ip_vs_sh`、`nf_conntrack`。报错先 `lsmod`，缺啥 modprobe 啥，并写进 `/etc/modules-load.d/`（见 5.1）。

**④ 用 MetalLB/KEP 类方案时需要 strict ARP**

ipvs 模式把大量 ClusterIP 绑在 kube-ipvs0 上，节点容易产生 ARP 应答混乱。MetalLB 二层模式要求开启 strict ARP（`arp_ignore=1, arp_announce=2`），否则 VIP 抢答导致流量绕过 LB——装 MetalLB 前先检查：

```bash
kubectl get configmap kube-proxy -n kube-system -o yaml | grep arp
# ipvs: strictARP: true   ← kube-proxy 配置里改, 而不是改宿主机 sysctl
```

**⑤ UDP 服务与超时**

IPVS 和 conntrack 对 UDP 的超时偏保守，DNS 类高频短查询可能命中过期表项导致丢包。表现特殊（时好时坏），排查方向：`net.ipv4.vs.timeout_idle`、conntrack 的 UDP 超时，以及 kube-proxy 的 UDP 相关调优配置。

**⑥ externalTrafficPolicy: Local 的语义**

NodePort 设为 Local 时，本节点没有就绪后端就直接丢包（不会跨节点转发）——这是策略本意，不是 IPVS 故障。监控流量丢失先看 endpoint 分布。

**⑦ 监控与排障三板斧**

```bash
ipvsadm -Ln            # 配置对不对 (Service/后端/权重/算法)
ipvsadm -Lnc           # 连接在不在 (有没有被调度、状态是否正常)
ipvsadm -Ln --rate     # 流量动没动 (CPS/PPS 是否增长)
# 配合: kubectl get endpoints <svc>   ← 后端列表是否与预期一致 (探针问题常在这暴露)
```

**⑧ 选型收尾**

- 新集群：优先 nftables 模式（1.33 GA）或按需求评估 Cilium；
- 存量 ipvs 集群：正常使用，升级 K8s 时关注 release note 中的弃用警告；迁移到 nftables 有官方迁移指南，行为差异主要在源地址保持和流量回切细节上，建议灰度验证；
- 经典 IDC 入口层：LVS（DR/TUN + keepalived）依然是高性能、低成本的成熟方案。

---

## 七、总结与参考资料

### 一张图收尾

```
                  ┌─────────────────────────────────────────────┐
  K8s 声明式 API   │  Service / Endpoints / EndpointSlice        │
                  └──────────────────┬──────────────────────────┘
                                     │ watch
                  ┌──────────────────▼──────────────────────────┐
                  │  kube-proxy (ipvs 模式)                      │
                  │  ● ClusterIP 绑到 kube-ipvs0 (造"本机包")      │
                  │  ● Service → ip_vs_service (虚拟服务)         │
                  │  ● 就绪 Pod → ip_vs_dest (真实服务器)          │
                  │  ● ipset + 固定 iptables 规则打配合             │
                  └──────────────────┬──────────────────────────┘
                                     │
        ┌────────────────────────────▼───────────────────────────────┐
        │  Linux 内核 IPVS:                                           │
        │   服务表 (vip:port → 算法+后端列表)                            │
        │   连接表 (五元组 → 选中后端, 状态, 超时)                        │
        │   INPUT 钩子: 调度 → DNAT → 转发;  回程: 反查 → SNAT          │
        └─────────────────────────────────────────────────────────────┘
```

**核心记忆点，四句话：**

1. **IPVS = 内核里的"虚拟服务表 + 连接表"**：第一张表回答"有哪些服务、怎么分"，第二张表回答"每条连接分给了谁"。
2. **调度只发生在每条连接的第一个包**：后续包 O(1) 查连接表直通——这是它对 iptables 模式的根本优势。
3. **kube-ipvs0 是连接"虚拟 IP"与"内核拦截"的桥**：把 ClusterIP 绑上假网卡，路由决策就会把包送进 IPVS 埋伏的 INPUT 链。
4. **IPVS 只管转发，不管健康**：后端摘除靠 kube-proxy/keepalived 等控制面，理解这个分工才能排障。

### 参考资料

- LVS 官方站点（章文嵩的项目主页，LVS-HOWTO 必读）：http://www.linuxvirtualserver.org/
- 内核 IPVS sysctl 文档：https://www.kernel.org/doc/Documentation/networking/ipvs-sysctl.txt
- Kubernetes 官方：Virtual IPs and Service Proxies：https://kubernetes.io/docs/reference/networking/virtual-ips/
- kube-proxy 配置参考（KubeProxyConfiguration）：https://kubernetes.io/docs/reference/config-api/kube-proxy-config.v1alpha1/
- kube-proxy nftables 模式（1.33 GA）：https://kubernetes.io/blog/2025/01/24/kube-proxy-nftables-beta/ 及 EKS 文档 https://docs.aws.amazon.com/eks/latest/userguide/kube-proxy-nftables.html
- IPVS 弃用说明（KEP-5495）：https://github.com/kubernetes/enhancements/issues/5495
- IPVS→nftables 迁移实践（Tigera/Calico）：https://www.tigera.io/blog/from-ipvs-to-nftables-a-migration-guide-for-kubernetes-v1-35/
- ipvsadm 手册页：`man ipvsadm`
- 姊妹篇：同目录 [Cilium.md](Cilium.md)（eBPF 路线的 Service/NetworkPolicy 实现与 IPVS 逐节对照）
