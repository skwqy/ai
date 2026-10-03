# Cilium 深度指南（小白友好）

> 从 Linux 网络基础 → eBPF 是什么 → Cilium 如何用 eBPF 重写 K8s 的网络、安全与观测 → 动手实验的完整手册。
> （基于 Cilium v1.20 整理，2026-10）

---

## 目录

- [一、Cilium 是什么](#一cilium-是什么)
  - [1.1 一句话定义](#11-一句话定义)
  - [1.2 为什么需要 Cilium：传统 K8s 网络的三座大山](#12-为什么需要-cilium传统-k8s-网络的三座大山)
  - [1.3 发展时间线](#13-发展时间线)
  - [1.4 Cilium 能做什么（功能全景）](#14-cilium-能做什么功能全景)
- [二、前置知识 A：Linux 网络基础](#二前置知识-alinux-网络基础)
  - [2.1 Network Namespace：一容器一世界](#21-network-namespace一容器一世界)
  - [2.2 veth pair：两个世界之间的网线](#22-veth-pair两个世界之间的网线)
  - [2.3 一个 Pod 的网络到底长什么样](#23-一个-pod-的网络到底长什么样)
  - [2.4 iptables：老一代"包过滤器"的工作方式](#24-iptables老一代包过滤器的工作方式)
  - [2.5 iptables 在大规模 K8s 集群中的致命痛点](#25-iptables-在大规模-k8s-集群中的致命痛点)
- [三、前置知识 B：eBPF 快速入门](#三前置知识-bbepf-快速入门)
  - [3.1 eBPF 是什么：内核的"App Store"](#31-bepf-是什么内核的app-store)
  - [3.2 eBPF 程序的一生：编写 → 编译 → 验证 → JIT → 挂载](#32-bepf-程序的一生编写--编译--验证--jit--挂载)
  - [3.3 验证器：为什么 eBPF 是安全的](#33-验证器为什么-bepf-是安全的)
  - [3.4 BPF Map：内核态与用户态之间的"共享内存"](#34-bpf-map内核态与用户态之间的共享内存)
  - [3.5 常用 Hook 点：把程序挂在哪里](#35-常用-hook-点把程序挂在哪里)
  - [3.6 和 iptables 对比：从"固定关卡"到"自定义关卡"](#36-和-iptables-对比从固定关卡到自定义关卡)
- [四、Cilium 整体架构](#四cilium-整体架构)
  - [4.1 组件全景图](#41-组件全景图)
  - [4.2 cilium-agent：每节点一个的"大脑"](#42-cilium-agent每节点一个的大脑)
  - [4.3 cilium-operator：集群级的"后勤部长"](#43-cilium-operator集群级的后勤部长)
  - [4.4 CNI 插件二进制：kubelet 的"接生婆"](#44-cni-插件二进制kubelet-的接生婆)
  - [4.5 Hubble：装在数据包上的行车记录仪](#45-hubble装在数据包上的行车记录仪)
  - [4.6 状态存储：CRD 模式 vs etcd 模式](#46-状态存储crd-模式-vs-etcd-模式)
  - [4.7 控制面 → 数据面：一个 YAML 是如何变成内核代码的](#47-控制面--数据面一个-yaml-是如何变成内核代码的)
- [五、核心原理一：Pod 网络与数据包流转](#五核心原理一pod-网络与数据包流转)
  - [5.1 Pod 创建瞬间发生了什么（CNI 流程）](#51-pod-创建瞬间发生了什么cni-流程)
  - [5.2 节点上的网络设备全景](#52-节点上的网络设备全景)
  - [5.3 同节点 Pod → Pod：两跳直达](#53-同节点-pod--pod两跳直达)
  - [5.4 跨节点：隧道模式 vs 本地路由模式](#54-跨节点隧道模式-vs-本地路由模式)
  - [5.5 eBPF host routing：为什么比内核路由更快](#55-bepf-host-routing为什么比内核路由更快)
- [六、核心原理二：Service 负载均衡（替代 kube-proxy）](#六核心原理二service-负载均衡替代-kube-proxy)
  - [6.1 kube-proxy 到底干了什么](#61-kube-proxy-到底干了什么)
  - [6.2 Cilium 的答案：把负载均衡写进 eBPF](#62-cilium-的答案把负载均衡写进-bepf)
  - [6.3 核心数据结构：三张 BPF Map](#63-核心数据结构三张-bpf-map)
  - [6.4 Socket-Level LB：在 connect() 时就"改签"地址](#64-socket-level-lb在-connect-时就改签地址)
  - [6.5 NodePort 与 XDP 加速](#65-nodeport-与-xdp-加速)
  - [6.6 会话亲和与 Maglev 一致性哈希](#66-会话亲和与-maglev-一致性哈希)
  - [6.7 kube-proxy 完全替代（kubeproxy-free）](#67-kube-proxy-完全替代kubeproxy-free)
- [七、核心原理三：NetworkPolicy 与 Identity](#七核心原理三networkpolicy-与-identity)
  - [7.1 核心思想：认"工牌"，不认"门牌号"](#71-核心思想认工牌不认门牌号)
  - [7.2 Identity 的分配流程](#72-identity-的分配流程)
  - [7.3 ipcache：IP ↔ Identity 的翻译官](#73-ipcacheip--identity-的翻译官)
  - [7.4 conntrack：门禁登记本](#74-conntrack门禁登记本)
  - [7.5 一条策略在内核里如何被执行](#75-一条策略在内核里如何被执行)
  - [7.6 L7 策略：无 sidecar 的 Envoy](#76-l7-策略无-sidecar-的-envoy)
  - [7.7 默认策略语义：养成好习惯](#77-默认策略语义养成好习惯)
- [八、核心原理四：可观测性 Hubble](#八核心原理四可观测性-hubble)
  - [8.1 flow 事件从哪里来](#81-flow-事件从哪里来)
  - [8.2 Hubble 架构](#82-hubble-架构)
  - [8.3 一条 flow 长什么样](#83-一条-flow-长什么样)
- [九、动手实战：从零跑通一个完整例子](#九动手实战从零跑通一个完整例子)
  - [9.1 用 kind 起一个实验集群并安装 Cilium](#91-用-kind-起一个实验集群并安装-cilium)
  - [9.2 解读 cilium status](#92-解读-cilium-status)
  - [9.3 官方 Star Wars Demo：L3/L4/L7 三层策略](#93-官方-star-wars-demol3l4l7-三层策略)
  - [9.4 用 Hubble 观察被拦截的请求](#94-用-hubble-观察被拦截的请求)
  - [9.5 直接查看 eBPF 内部：把原理"看"出来](#95-直接查看-bepf-内部把原理看出来)
  - [9.6 串讲：一个 HTTP 请求的完整旅程](#96-串讲一个-http-请求的完整旅程)
- [十、进阶特性一览](#十进阶特性一览)
- [十一、选型对比与生产建议](#十一选型对比与生产建议)
- [十二、总结与参考资料](#十二总结与参考资料)

---

## 一、Cilium 是什么

### 1.1 一句话定义

**Cilium 是一个基于 eBPF 的云原生网络、安全与可观测性平台**，它是 Kubernetes 中最主流的 CNI 插件之一（CNCF 毕业项目）。

拆开说，它在一个软件里同时干了三件事，而这三件事传统上需要三套东西：

| 职责 | 传统方案 | Cilium 的替代 |
|------|---------|--------------|
| 给 Pod 分 IP、打通网络 | Flannel / Calico 等 CNI | CNI + eBPF 数据面 |
| Service 虚拟 IP 负载均衡 | kube-proxy（iptables/IPVS） | eBPF Service Map（可完全替代 kube-proxy） |
| 网络安全策略 | NetworkPolicy（iptables 实现）+ sidecar 代理 | 基于 Identity 的 eBPF 策略 + 无 sidecar 的 L7 代理 |
| 网络可观测性 | cAdvisor/日志/抓包拼凑 | Hubble（每一条流都可查） |

**它最大的独特之处：所有数据面的活儿，都不是靠往内核里堆规则，而是靠 eBPF 在内核的关键路径上挂载自己编译的小程序，直接以编程方式转发、改写、过滤数据包。**

### 1.2 为什么需要 Cilium：传统 K8s 网络的三座大山

理解 Cilium 之前，先看它要解决什么问题。

**第一座山：iptables 的性能悬崖。** K8s 里每定义一个 Service，kube-proxy 就要往 iptables 里写入一堆规则。集群规模上去之后（几千个 Service），每一个进出的数据包都要在几百上千条规则里线性匹配，延迟和 CPU 开销随规模线性增长；每次增删 Service 还要全量重放规则。

**第二座山：NetworkPolicy 只认 IP，太粗。** 原生 NetworkPolicy 基于 IP/端口，而 Pod 的 IP 随时在变；想做到"只允许 checkout 服务调支付服务的 /pay 接口，其他路径都不行"这种 L7 精细控制，原生能力做不到，只能给每个 Pod 挂一个 Envoy sidecar——每个容器多一个进程，资源开销和运维复杂度都上去了。

**第三座山：黑盒。** Pod A 访问 Pod B 被拒了，到底是谁拒的、按哪条规则拒的？传统手段只能去节点上 tcpdump 抓包 + 人肉对规则，排查网络问题像考古。

Cilium 用 eBPF 把三座山一起搬了：负载均衡在内核里以 O(1) 查表完成；策略按"身份"匹配且能透传到 HTTP 层而无需 sidecar；每一次转发/拦截决策都自动生成可查询的流日志。

### 1.3 发展时间线

| 时间 | 事件 |
|------|------|
| 2016 | Isovalent 团队（Thomas Graf 等）发布 Cilium，首创"用 BPF 做 K8s 网络" |
| 2018 | eBPF 技术本身进入 CNCF 沙箱（后独立发展为 ebpf.io 生态） |
| 2021 | Hubble、kube-proxy replacement 等逐渐成熟，Cilium 开始大规模生产落地 |
| 2023-10 | **Cilium 成为 CNCF 毕业项目**；同年 Isovalent 被 Cisco 收购 |
| 2025 | 内核级加速（netkit 设备、XDP fast path）持续演进 |
| 2026-02 | Cilium 1.19 发布，项目十周年 |
| 2026-09 | 当前最新版本线 **v1.20**（v1.20.2），支持 K8s 1.33–1.36 |

### 1.4 Cilium 能做什么（功能全景）

- **CNI**：Pod 网络（VXLAN/Geneve 隧道、native routing、BGP）
- **kube-proxy 替代**：ClusterIP / NodePort / LoadBalancer / ExternalIPs 全部 eBPF 实现，节点上可以不跑 kube-proxy
- **网络策略**：L3（IP/标签）/ L4（端口）/ L7（HTTP、gRPC、Kafka、DNS），支持原生 NetworkPolicy 和增强版 CiliumNetworkPolicy（CNP）
- **可观测**：Hubble（flow 日志、指标、UI）
- **加密**：节点间 WireGuard / IPsec 透明加密
- **带宽管理**：Pod 级限速（EDT + fq，eBPF 实现）
- **多集群**：ClusterMesh 跨集群服务发现与全局负载均衡
- **进出网关**：Egress Gateway（固定出口 IP）、Ingress/L7 API Gateway
- **运行时安全**（生态）：与同门项目 Tetragon 配合做进程级/系统调用级安全观测

---

## 二、前置知识 A：Linux 网络基础

Cilium 的一切魔法都发生在 Linux 内核里，所以先花十分钟把三个基础概念搞明白。

### 2.1 Network Namespace：一容器一世界

Linux 的 network namespace（网络命名空间）给进程一套**独立的**网络视图：独立的网卡、IP、路由表、iptables 规则、端口空间。容器隔离的本质就是：每个容器跑在自己的 netns 里，所以容器 A 里看不到容器 B 的网卡。

宿主机有一个默认 netns（叫 host netns），Pod 里的进程则在一个独立的 netns 中。

### 2.2 veth pair：两个世界之间的网线

namespace 之间是隔绝的，那 Pod 里的进程怎么和外界通信？答案是 **veth pair（virtual ethernet pair）**：一对"虚拟网线"，从一端塞进去的包会从另一端出来。

```
┌───────────────┐                        ┌───────────────┐
│  Pod netns    │                        │   Host netns  │
│               │      虚拟网线            │               │
│   eth0 ●──────┼────────────────────────┼───● veth0     │──→ 外界
│  (容器一端)    │      包从这头进,那头出    │  (宿主机一端)  │
└───────────────┘                        └───────────────┘
```

**veth 一端必须在某个 netns 里才有意义**——把其中一端"转移"（move）到 Pod 的 netns 里并改名为 `eth0`，容器就觉得"我有一块自己的网卡"。

### 2.3 一个 Pod 的网络到底长什么样

在节点上执行 `ip link`，看到的典型设备（Flannel 场景）：

```
host netns:
├── eth0            # 物理网卡，对外
├── cni0            # Linux bridge，把本节点所有 Pod 的 veth 汇聚在一起（bridge 类 CNI）
├── vethxxxxx  ──→  (对端在 Pod netns 里，叫 eth0)
├── vethyyyyy  ──→  (另一个 Pod 的 eth0)
└── flannel.1       # VXLAN 隧道设备（跨节点用）
```

Pod netns 里只有一块 `eth0`（veth 的容器端）+ `lo`。

**记住这个结构，Cilium 的做法和它非常像，但把 bridge 换成了 eBPF。**

### 2.4 iptables：老一代"包过滤器"的工作方式

iptables 是 Linux 老牌的包过滤/NAT 框架，核心是**一张张规则表**。每个数据包经过内核协议栈时，会按固定的顺序穿过若干"链"（PREROUTING、INPUT、FORWARD、OUTPUT、POSTROUTING），每条链里从上到下逐条匹配规则，命中就执行动作（接受/丢弃/改地址/跳转到下一条链）。

kube-proxy 的 iptables 模式就是拿 iptables 当"负载均衡器"：为每个 Service 写 `KUBE-SVC-XXX` 链，链里用随机概率规则把流量分给各个后端（`KUBE-SEP-XXX`），再用 DNAT 改目标地址。

### 2.5 iptables 在大规模 K8s 集群中的致命痛点

```
一个数据包访问 Service 时要经历的规则链路（示意）:

包 → PREROUTING → KUBE-SERVICES 链
       → 逐条匹配 N 个 Service 的规则...(线性!)
           → 命中 Service A → KUBE-SVC-XXXX 链
               → 1/N 概率 → KUBE-SEP-1 (DNAT 到 backend1)
               → 1/N 概率 → KUBE-SEP-2 (DNAT 到 backend2) ...
```

三个问题：

1. **匹配是 O(n) 的**：规则数 ≈ Service 数 × 2 + Pod 数相关。社区实测几千个 Service 后，每个包要 traversing 上千条规则，延迟可达毫秒级。
2. **更新是全量替换**：增删一个 Service，kube-proxy 要重算并重放整表，CPU 尖刺、短暂锁表，Service 频繁变动时雪崩。
3. **无状态匹配**：iptables 不理解"这是一条已建立的连接"，每个包都要重新匹配一遍（conntrack 只帮 NAT 部分省事）。

> 一句话：**iptables 是"查字典式"的，eBPF 是"编程式"的**。这就是 Cilium 存在的技术起点。

---

## 三、前置知识 B：eBPF 快速入门

### 3.1 eBPF 是什么：内核的"App Store"

eBPF（extended Berkeley Packet Filter）可以让**经过验证的字节码程序安全地运行在 Linux 内核里**，不用改内核源码、不用重启、不用加载有风险的内核模块。

一个经典类比：

- **内核是操作系统里的"保镖"**，所有网络包、系统调用都要经过它。以前保霸只按死规则办事（iptables 规则表），想改行为只能"换保镖"（升级内核/打补丁）。
- **eBPF 相当于给保镖配了一个 App Store**：任何人都能开发一个小程序（eBPF 程序），提交给安检员（verifier），安检通过后装到保镖身上（挂载到 hook 点），保镖办事流程立刻改变。

```
       用户态（你的 K8s 组件）                    内核态（Linux）
┌──────────────────────────┐          ┌─────────────────────────────┐
│  cilium-agent (Go 进程)   │  编译/加载 │   网卡收包 → XDP 程序        │
│                          │ ────────→ │        ↓                   │
│  - watch K8s API         │  bpf()    │   协议栈 → TC 程序(收/发)     │
│  - 生成 eBPF 程序和 Map    │  系统调用  │        ↓                   │
│  - 往 Map 里写数据         │ ────────→ │   socket → sock_ops 程序    │
│                          │           │        ↓                   │
│  读 Map 获取统计/事件 ←───┐│ ←──────── │   BPF Maps（共享数据）        │
└──────────────────────────┘  读取/轮询  └─────────────────────────────┘
```

### 3.2 eBPF 程序的一生：编写 → 编译 → 验证 → JIT → 挂载

1. **编写**：C 语言写（内嵌在 Go 代码里，Cilium 就是这么干的，见其源码的 `bpf/` 目录），用 LLVM/Clang 编译成字节码。
2. **加载**：用户态程序通过 `bpf()` 系统调用把字节码提交给内核（Cilium 用 cilium/ebpf 或内部 loader 库）。
3. **验证**：内核的 **verifier（验证器）** 静态分析这份字节码。
4. **JIT**：验证通过后即时编译成本机机器码，性能接近原生代码。
5. **挂载**：绑定到某个 hook 点（网卡、协议栈、cgroup……），事件到来时执行。
6. **pin 与生命周期**：程序/map 可以"钉"（pin）在 `/sys/fs/bpf/` 下，即使加载进程退出也持续生效——Cilium 就是这样，agent 重启也不中断数据面。

### 3.3 验证器：为什么 eBPF 是安全的

verifier 像机场安检，逐条指令检查：

- 不能解引用非法指针、不能越界访问内存；
- **必须证明所有循环有界**（不能死循环卡住内核；新版内核支持有界 loop）；
- 指令数有上限（复杂程序会被拒）；
- 只能调用内核提供的白名单辅助函数（helper，如 `bpf_redirect()`、`bpf_map_lookup_elem()`）。

所以 eBPF 程序**崩溃不了内核、挂不了机器**——最坏情况是加载失败。这也是内核社区敢让 Cilium 这种程序跑在生产环境关键路径上的原因。

### 3.4 BPF Map：内核态与用户态之间的"共享内存"

eBPF 程序自己不能存太多东西，它的"数据库"是 **BPF Map**——内核里高效的 KV 存储，用户态（cilium-agent）和内核态（eBPF 程序）都能读写。

Cilium 的整个数据面就是围绕一组 map 运转的：

| Map（节点上可见的名字） | 存什么 | 类比 |
|---|---|---|
| `cilium_ipcache` | IP → Security Identity | 门牌号 → 工牌 |
| `cilium_lb4_services_v2` | Service 前端（VIP:Port）→ 后端选择器 | 服务总台 |
| `cilium_lb4_backends_v3` | 后端编号 → 后端真实 IP:Port | 后端通讯录 |
| `cilium_lb4_reverse_nat` | DNAT 后的响应如何反向还原 | 回程改签表 |
| `cilium_ct4_global` / `cilium_ct6_global` | 连接追踪表 | 访客登记本 |
| `cilium_ep_to_policy`（及 per-endpoint policy map） | Identity → 允许的 L3/L4 组合 | 门禁权限表 |
| `cilium_events`（perf buffer） | 转发/丢弃事件流 | 行车记录仪存储 |

> 后面第六、七章会反复用到这些表，实战章节（9.5）会教你用 `cilium bpf` 命令逐个查看它们。

### 3.5 常用 Hook 点：把程序挂在哪里

一个包从网卡进来到发出去，会路过内核的一串"关卡"，eBPF 可以挂在其中任意一环：

```
          ┌─────────────────────────── 一个包的生命周期 ───────────────────────────┐
外部进包 →  网卡驱动 → [XDP] → 内核协议栈(TC ingress) → 路由 → TC egress → 网卡驱动 → 出包
                          ↑最早,只有裸帧    ↑完整skb          ↑(L3/L4处理)   (本机进程)
本机进程发包:  socket层 [sock_ops/cgroup/connect4] → 协议栈 → TC egress → 出
```

Cilium 用到的主力 hook：

| Hook | Cilium 里的程序名 | 干什么 |
|------|------------------|--------|
| XDP（网卡驱动层，最早） | `cil_xdp_pre_filter` 类 | NodePort 流量在驱动层直接转发，抗 DDoS、极致性能 |
| TC ingress/egress（每个网卡） | `bpf_lxc`（Pod 的 veth）、`bpf_host`（host 设备和物理网卡）、`bpf_overlay`（隧道设备） | **主力**：转发、负载均衡、策略执行、隧道封装 |
| cgroup/connect4、sendmsg4 等 | `bpf_sock` 系列 | Socket 级负载均衡（connect() 时改写目标地址） |
| tracepoint/kprobe（drop 等） | drop/tc 监控程序 | 生成 Hubble 观测事件 |

### 3.6 和 iptables 对比：从"固定关卡"到"自定义关卡"

| 维度 | iptables | eBPF (Cilium) |
|------|----------|---------------|
| 编程模型 | 固定的规则表，逐条线性匹配 | 自己写程序，任意逻辑，一次编译 |
| 查找复杂度 | O(规则数) | O(1) 哈希查表（BPF map） |
| 更新方式 | 全表重放 | 只改涉及的 map 条目，原子更新 |
| 状态感知 | 弱 | conntrack 直接在 BPF 里维护 |
| 可编程深度 | L3/L4 | L3/L4 之外可把流量送给用户态做 L7 |
| 数据来源 | 无法记录"哪条规则命中" | 每个决策点都能发事件（→ Hubble） |

---

## 四、Cilium 整体架构

### 4.1 组件全景图

```
                        Kubernetes 控制面
        ┌────────────────────────────────────────────────┐
        │   API Server（CiliumIdentity/CiliumEndpoint/   │
        │   CiliumNetworkPolicy 等 CRD 存在这里）          │
        └───────┬───────────────────────────────▲────────┘
                │ watch 资源变化                  │ 写状态
   ┌────────────┴────────────┐      ┌──────────┴───────────────┐
   │  cilium-agent (每节点一个,│      │  cilium-operator          │
   │  DaemonSet)             │      │  (Deployment, 集群级单/双副本)│
   │                         │      │  - IPAM 地址池管理          │
   │  ● watch Pod/Service/NP │      │  - Identity 全局分配       │
   │  ● 编译 eBPF 程序并加载   │      │  - 清理孤儿资源            │
   │  ● 维护 BPF Maps        │      └──────────────────────────┘
   │  ● 控制 CNI/Envoy/Hubble│
   └──────┬──────────┬───────┘
          │          │ unix socket
          ▼          ▼
  ┌────────────┐  ┌──────────────┐   每个节点上：
  │ cilium-cni │  │ cilium-envoy │   - eBPF 程序挂满关键路径
  │ (kubelet 调 │  │ (L7 代理,     │   - 一堆 BPF Maps
  │ 用,建 Pod 网 │  │  无 sidecar) │   - Hubble Observer 收事件
  │ 络)         │  └──────────────┘
  └────────────┘
          │
          ▼
   Hubble Relay（聚合所有节点的 flow）→ Hubble UI / hubble CLI / Prometheus+Grafana
```

### 4.2 cilium-agent：每节点一个的"大脑"

DaemonSet 方式跑在每个节点上，是绝对主角：

- **Watch K8s API**：Pod 创建/删除、Service 变化、NetworkPolicy 下发、节点增减……
- **管理本节点 endpoint**：每个 Pod 在 agent 里是一个 endpoint 对象（可通过 `kubectl get ciliumendpoints` 看到），有自己的 IP、Identity、policy 配置。
- **生成并加载 eBPF**：Cilium 源码里预置了一批 C 语言 eBPF 程序模板（`bpf/lib/*.c`），agent 根据"这台节点开了哪些功能 + 这个 endpoint 有哪些策略"做编译期裁剪和常量注入，然后编译成最终字节码加载到内核。**策略变了不是往内核塞规则，而是局部重编译/更新 map**。
- **维护所有 BPF Map**：ipcache、LB 表、CT 表、policy 表……
- **托管 Envoy**：L7 策略需要时配置本节点的 Envoy。

### 4.3 cilium-operator：集群级的"后勤部长"

Deployment 部署（一般 1–2 副本，整个集群一份）：

- **IPAM**：按节点分配 Pod CIDR（`spec.ipam.mode: kubernetes` 时从 K8s node 的 podCIDR 分，也支持 multi-pool 等），节点 IP 不够时自动申请新池。
- **Identity 管理**：全局保证"相同 label 组合 = 相同 identity ID"，并负责把 Identity 做成 CRD 或写进 kvstore，让所有节点一致。
- **垃圾回收**：清理孤儿 endpoint、过期的 CNP 状态等。

### 4.4 CNI 插件二进制：kubelet 的"接生婆"

每个节点 `/opt/cni/bin/cilium-cni` 是一个符合 CNI 规范的小二进制。kubelet 创建 Pod 网络时会调用它，它本身**不做重活**，而是通过 unix socket（`/var/run/cilium/cilium.sock`）找到本节点 agent 说："帮我给容器 X 配网"。真正的 veth 创建、eBPF 准备都是 agent 早就做好的（见 5.1）。

### 4.5 Hubble：装在数据包上的行车记录仪

Hubble 不是独立的探针，它**复用数据面**：eBPF 程序在转发/丢弃/NAT 的同时把决策事件写进 perf buffer，agent 里的 Hubble Observer 读出来，按 identity/verdict 组织成 flow 日志。详见第八章。

### 4.6 状态存储：CRD 模式 vs etcd 模式

Cilium 需要一个地方存放"全局一致"的状态（Identity、ipcache、节点信息）：

- **CRD 模式（当前默认）**：直接用 K8s 的 etcd，通过 `CiliumIdentity`、`CiliumEndpoint`、`CiliumNode` 等 CRD 同步。运维最简单，规模特别大时对 API Server 有压力。
- **专用 etcd 模式**：跑一个独立 etcd 存这些状态，超大规模集群用。

### 4.7 控制面 → 数据面：一个 YAML 是如何变成内核代码的

以一条 CiliumNetworkPolicy 为例，完整链路：

```
① 用户提交 CNP YAML
      ↓
② API Server 存储 → 各节点 cilium-agent watch 到
      ↓
③ agent 计算受影响的 endpoint（按 endpointSelector 匹配本节点 Pod）
      ↓
④ 对每个 endpoint：
   - 计算新的"允许列表"（哪些 src identity + L4 组合可以进/出）
   - 更新该 endpoint 的 policy BPF Map（原子替换 map 中的条目）
   - 若涉及 L7：为 Envoy 生成/更新 listener 和 filter 配置
      ↓
⑤ 数据面立刻生效：
   - L3/L4：下一个包到达时查新 map → 放行/丢弃
   - L7：流量被重定向到 Envoy → 按 HTTP 规则过滤
      ↓
⑥ 状态写回：agent 把策略实施状态写进 CNP 的 status（kubectl 能看到）
```

关键理解：**agent 是"编译器"，把声明式 YAML 编译成 eBPF 程序 + Map 内容**。数据面上没有任何"策略引擎"在跑，只有查表和分支判断，所以快。

---

## 五、核心原理一：Pod 网络与数据包流转

### 5.1 Pod 创建瞬间发生了什么（CNI 流程）

kubelet 发现新 Pod → 调 CNI → 请求本节点 agent → agent 提前干完了绝大部分活：

```
kubelet                cilium-cni              cilium-agent               内核
   │ 创建Pod容器(先只有lo)     │                        │                      │
   ├──调用 CNI ADD ────────→ │                        │                      │
   │                        ├──unix socket 请求─────→ │                      │
   │                        │                        │ ① 从IPAM池取IP         │
   │                        │                        │ ② 创建 veth pair ────→ │ (host侧: lxcXXXX
   │                        │                        │ ③ veth容器端移入Pod ns  │  Pod侧: eth0)
   │                        │                        │ ④ 配IP/路由,设置sysctl │
   │                        │                        │ ⑤ 加载/复用该endpoint  │
   │                        │                        │    的 bpf_lxc 程序到   │
   │                        │                        │    lxcXXXX 两个方向     │
   │                        │ ←─ 返回结果 ──────────── │ ⑥ 在endpoint map注册IP │
   │ ←── CNI 返回 IP/网关 ── │                        │ ⑦ 通知operator分配      │
   │ 启动Pod业务进程          │                        │    Identity           │
```

两点值得注意：

1. **agent 是事件驱动的**：Pod 还没调度好，agent watch 到了就提前把网络环境准备好；CNI 二进制只是"按门铃领钥匙"。
2. **每个 Pod 的 veth 两端都挂了 eBPF 程序**（合称 `bpf_lxc`，分 from-container / to-container 两个方向），这是和 bridge 类 CNI 的本质区别——**转发逻辑在程序里，不在 bridge 设备里**。

### 5.2 节点上的网络设备全景

安装 Cilium 后在节点上 `ip link`，和 Flannel 的对比：

```
Cilium 节点（tunnel 模式）:
├── eth0            # 物理/主网卡
├── cilium_vxlan    # VXLAN 隧道设备（Geneve 模式下叫 cilium_geneve）
├── cilium_host     # 节点"路由器"角色（veth 一端）          ←─┐
├── cilium_net      # cilium_host 的对端                     │ 这对设备承载 host↔Pod 流量,
├── lxc8a349e8 ───→ (Pod A 的 eth0)  # 每个 Pod 一根 veth      │ 各挂着 bpf_host 程序
├── lxc91f0c22 ───→ (Pod B 的 eth0)                       ←─┘
└── cilium_net@...  (health 等辅助设备略)

没有 cni0 bridge!   ← 转发由 eBPF 程序完成，不需要交换机
```

host 侧 veth 的名字 `lxc` + 数字，数字来自内核接口的随机标识。

### 5.3 同节点 Pod → Pod：两跳直达

假设同节点的 Pod A（10.0.1.10）访问 Pod B（10.0.1.11），eBPF host routing（1.14+ 默认）的路径：

```
┌─────────────┐        ┌────────────────── host netns ──────────────────┐        ┌─────────────┐
│   Pod A     │        │                                                │        │   Pod B     │
│ 10.0.1.10   │        │   lxcA(egress侧=bpf_lxc)    lxcB(ingress侧)     │        │ 10.0.1.11   │
│             │        │  ┌───────────────────┐   ┌──────────────────┐  │        │             │
│   eth0 ─────┼──包────→│  │ ①查conntrack(新连接)│   │ ③to-container    │  │        │   eth0      │
│             │        │  │ ②查endpoints map:  │───│   bpf_redirect   │──┼─包────→│             │
│             │        │  │  10.0.1.11 → lxcB  │   │  ④策略检查(如有)    │  │        │             │
└─────────────┘        │  └───────────────────┘   └──────────────────┘  │        └─────────────┘
                       └────────────────────────────────────────────────┘
```

流程解释：

1. 包从 Pod A 的 eth0 出来，到达 host 侧 `lxcA` 的 **TC ingress**，这里是 Pod A 的 `bpf_lxc` 程序（from-container 方向）。
2. 程序先查连接追踪（CT）确认是新连接，然后查 **endpoints map**（IP → 本节点 endpoint），发现 10.0.1.11 就在本节点、出口是 `lxcB`。
3. 直接 `bpf_redirect()` 把包重定向到 `lxcB` 设备，**不经过 host 的 L3 路由栈、不经过任何 bridge**。
4. `lxcB` 的 to-container 方向程序（也是 bpf_lxc）做入方向策略检查，放行后包进入 Pod B 的 eth0。

对比老式 bridge 方案（Pod A → vethA → cni0 bridge 学习/泛洪 → vethB → Pod B，中间还要过 host 路由表），**eBPF 把转发压到了两个 hook 点之间的直接重定向**，跳数最少、可编程点最多。

> 老版本 Cilium（或关闭 eBPF host routing 时）：包要先经过 `cilium_net`/`cilium_host` 这对设备走一次 host 栈，多几跳。新版本同节点流量已不走这对设备，它们主要服务 host↔Pod 方向。

### 5.4 跨节点：隧道模式 vs 本地路由模式

目标 Pod 不在本节点时，Cilium 有两种把包送到对端的办法：

**方式一：Encapsulation 隧道模式（默认，最通用）**

```
节点1                                                        节点2
┌──────────────────────────────┐          ┌──────────────────────────────────┐
│ Pod A                        │          │                        Pod B     │
│  eth0 → lxcA (bpf_lxc)       │          │  lxcB (bpf_lxc,策略检查) ← eth0   │
│   │ 查endpoints: 不在本节点     │          │   ↑                              │
│   ↓ 封装: 外层IP=本机eth0地址   │          │   │ 解封装 + bpf_overlay          │
│   │  外层目标IP=节点2 eth0地址  │          │  cilium_vxlan ←──────────┐       │
│   ↓ (VXLAN/Geneve头部携带      │          │       eth0 ←──────┐      │       │
│   │  源Identity,对端免查表)     │          │                   │      │       │
│  cilium_vxlan ─→ eth0 ════════╪══════════╪═══ 物理网络 ═══════╪──────┘       │
└──────────────────────────────┘          └──────────────────────────────────┘
```

- VXLAN（UDP 8472）或 Geneve（UDP 6081）把 Pod 的原始包整个裹一层，外层 IP 是节点地址——**Pod 网段不需要和物理网络互通**，任何环境都能跑。
- 精妙之处：**源 Pod 的 Security Identity 直接放在封装头的 tunnel key / option 里**。对节点解包后第一件事就拿到了"这个包来自谁"，不用再查 ipcache。
- `bpf_overlay` 程序挂在隧道设备上，负责封装路径上的身份与策略衔接。

**方式二：Native Routing 本地路由模式**

不封装，直接让物理网络路由 Pod IP：

- 要求底层网络（交换机/云 VPC 路由）能到达 Pod 网段；
- 云环境常配 `nativeRoutingCIDR` + 云路由表，IDC 可用 **BGP**（Cilium 内置 BGP 支持，向 TOR 交换机宣告 Pod 网段）；
- 性能更好（少一层封包解包），适合网络可管控的环境。

| | Tunnel（VXLAN/Geneve） | Native Routing（含 BGP） |
|---|---|---|
| 环境要求 | 几乎无要求 | 底层可路由 Pod 网段 |
| 性能 | 多一层封装开销 | 更好 |
| 排查难度 | 抓包看到双层头 | 抓包所见即所得 |
| 典型场景 | 混合/默认选择 | 云厂商对接/IDC |

### 5.5 eBPF host routing：为什么比内核路由更快

`bpf_redirect_peer()` 等 helper（内核 5.10+）能直接把包**从 host netns 的 veth 端送进目标 Pod netns 的对端**，跳过整个 netns 切换和协议栈重复处理。Cilium 1.14 起默认启用 eBPF host routing，配合这个 helper，同节点转发路径上的 CPU 开销显著低于传统的 host 路由路径。这也是为什么 Cilium 对内核版本敏感——**内核越新，可用的"高速公路"越多**（后面生产建议章节细说）。

---

## 六、核心原理二：Service 负载均衡（替代 kube-proxy）

### 6.1 kube-proxy 到底干了什么

K8s 的 Service 提供一个稳定的虚拟 IP（ClusterIP）+ 端口，背后挂若干 Pod（Endpoint）。kube-proxy 的工作就是：**把发往 VIP 的包，DNAT 到某个真实后端 Pod**。iptables 模式下这靠一大堆规则链（见 2.5），IPVS 模式用内核的 IPVS 表稍好，但仍是独立组件 + 通用框架，没有针对 eBPF 优化。

### 6.2 Cilium 的答案：把负载均衡写进 eBPF

Cilium 把"VIP → 后端"的翻译直接做进数据包经过的 eBPF 程序里：

```
请求包: Pod A → 10.96.0.10:80 (deathstar 的 ClusterIP)
                     │
   bpf_lxc / bpf_host 中:
   ① 查 cilium_lb4_services_v2   →  命中 VIP:80, 得到后端选择逻辑
   ② 选一个后端 (随机/亲和/Maglev) →  backend_id=7
   ③ 查 cilium_lb4_backends_v3   →  backend_id=7 → 10.0.1.11:8080
   ④ DNAT: 包头目标地址改写为 10.0.1.11:8080
   ⑤ 在 conntrack 里记下这条映射 (含 rev_nat_index)
                     │
   后续同连接的包直接查 conntrack 短路转发, 不再走①②③④
                     │
响应包: 10.0.1.11:8080 → Pod A
   ⑤' 查 conntrack → 反向还原: 源地址改回 10.96.0.10:80 (SNAT)
      Pod A 全程以为自己在和 ClusterIP 通信
```

全程 **2 次 O(1) 哈希查表 + 1 次 conntrack 命中**，没有规则链遍历。Service 增删 = 改几条 map 条目，原子生效，无全量重放。

### 6.3 核心数据结构：三张 BPF Map

以 IPv4 为例（IPv6 是对应的 lb6 系列）：

```
┌─────────────────────────────────────────────────────────────────┐
│ cilium_lb4_services_v2  (前端表: "哪个 VIP")                       │
│   key:  { VIP 10.96.0.10, port 80, TCP }                        │
│   value: { 后端数量 n, 选择算法, 首选槽位, session affinity 配置... } │
├─────────────────────────────────────────────────────────────────┤
│ cilium_lb4_backends_v3   (后端表: "每个槽位是谁")                   │
│   key:  backend_id (如 7)                                       │
│   value: { 10.0.1.11:8080, state=active }                       │
├─────────────────────────────────────────────────────────────────┤
│ cilium_lb4_reverse_nat    (反向 NAT 表: "回程怎么改回去")            │
│   key: rev_nat_index (如 42)                                    │
│   value: { 10.96.0.10:80 }   ← 响应包把源地址还原成 VIP          │
└─────────────────────────────────────────────────────────────────┘
```

此外还有源地址白名单表、Maglev 表（见 6.6）、会话亲和超时等都各自有 map。真实 map 名和字段随版本演进（比如 backends 出过 v2/v3），但**前端表/后端表/反向表的三段式设计十年未变**。

### 6.4 Socket-Level LB：在 connect() 时就"改签"地址

TC 层的 LB 要处理每一个包。Cilium 还有一招更狠的：**在 socket 层就把地址改了**。

```bash
# 开启后，Pod 内进程 connect(10.96.0.10:80) 的瞬间:
#   cgroup/connect4 hook 的 eBPF 程序查 service map
#   把 socket 的目标地址直接改写成 10.0.1.11:8080
# 之后这个 TCP 连接的所有数据包: 目的地址本来就是后端, 内核协议栈零额外处理
```

对比：

| 方式 | 处理时机 | 每包开销 |
|------|---------|---------|
| kube-proxy iptables | 协议栈里每个包查规则链 | 高，随规则数增长 |
| Cilium TC 层 LB | 每个包查 map（首个包后走 conntrack） | 低 |
| Cilium Socket-LB | **只在 connect() 时一次** | 几乎为零 |

Socket-LB 天然支持"后端换人后新连接走新后端"，配合 conntrack 的恢复逻辑可以在后端失败时把该 socket 的后续连接切到新后端。

### 6.5 NodePort 与 XDP 加速

NodePort（外部流量打节点 IP:Port）由 `bpf_host`（挂在本节点对外网卡 TC 层）处理；进一步开启 XDP 加速后，**包还在网卡驱动里**（协议栈处理之前）就完成了 DNAT 并转发给后端节点——外部 SYN 洪水、海量短连接场景下这是数量级的性能差异。

```
外部包 → 网卡驱动 [XDP 程序: 直接查 LB map → 改写目标 → bpf_redirect 到隧道/网卡]
                ↓ 没被 XDP 处理的才继续走
              协议栈 → TC(bpf_host) 兜底
```

### 6.6 会话亲和与 Maglev 一致性哈希

- **SessionAffinity**：同一客户端在同一超时窗口内始终打到同一后端，通过 conntrack + services map 里的亲和配置实现。
- **Maglev**（Google 论文的一致性哈希算法）：为每个 Service 生成一张 65k 大小的查找表，客户端哈希后查表定后端。特性是**增删后端时只有少量映射变化**、且所有节点查同一张表结果一致——对"多节点转发的同一条连接要落到同一后端"以及"后端扩缩容时少断连"非常关键。

### 6.7 kube-proxy 完全替代（kubeproxy-free）

开启 `kubeProxyReplacement=true`（1.14+ 逐步默认）后，ClusterIP/NodePort/LoadBalancer/ExternalIP/HostPort 全部由上述 eBPF 实现，**节点上 kube-proxy 可以不装**。这也是很多用户安装 Cilium 的第一动机。

---

## 七、核心原理三：NetworkPolicy 与 Identity

### 7.1 核心思想：认"工牌"，不认"门牌号"

传统 NetworkPolicy 用 IP 判断"谁是谁"，但 Pod IP 是易变的"门牌号"。Cilium 引入 **Security Identity（安全身份）**：

> **一组 Kubernetes label 的集合 → 计算哈希 → 分配一个集群全局唯一的数字 ID**。

比如打有 `org=empire, class=deathstar` 的所有 Pod 共享同一个 identity（比如 16777）——**扩容、缩容、漂移都不变**，策略跟着工牌走，不跟 IP 走。

```
label 集合 {k8s:org=empire, k8s:class=deathstar, k8s:io.kubernetes.pod.namespace=default}
        │ 哈希
        ▼
Identity ID = 16777   ← 集群内全局唯一, 相同 label 组合的 Pod 永远拿到同一个 ID
```

除了 Pod，还有一些**保留身份（reserved identities）**：`host`（宿主机自身流量）、`world`（集群外互联网）、`unmanaged`、`health`、`remote-node`、`ingress` 等，kubectl 都能看到（见 9.5）。

### 7.2 Identity 的分配流程

```
Pod 创建
  → cilium-agent 收集 Pod 的所有 label
  → 过滤掉系统保留前缀(io.kubernetes.* 等按规则裁剪), 剩下"业务身份"
  → 把 label 集合发给 cilium-operator (经 CRD/kvstore)
  → operator 查全局表:
       已有相同组合? → 返回既有 ID          (同 label 的 Pod 复用同一个 ID)
       没有?         → 分配新 ID, 全局登记  (生成 CiliumIdentity 对象)
  → agent 把 ID 绑到本节点该 endpoint 上, 并通过 CiliumEndpoint 同步给需要的节点
```

### 7.3 ipcache：IP ↔ Identity 的翻译官

数据包里只有 IP，没有 label。所以每个节点的 agent 维护一张 **ipcache BPF Map**（`cilium_ipcache`）：整个集群所有已知 IP（本集群 Pod、节点、kube-apiserver、外部网段等）到 Identity 的映射。

```
cilium_ipcache (示意):
  10.0.1.10/32  → identity 5213  (k8s:org=rebels)
  10.0.1.11/32  → identity 16777 (k8s:org=empire,class=deathstar)
  10.0.2.15/32  → identity 41522 (k8s:app=coredns, ns=kube-system)
  192.0.2.0/24  → identity 2     (reserved:world)
  172.18.0.2/32 → identity 1     (reserved:host)
```

包到达时查一次 ipcache，源 Identity 就到手了；隧道模式下则直接从封装头里拿（5.4），更快。

### 7.4 conntrack：门禁登记本

Cilium 在 eBPF 里维护自己的连接追踪表（`cilium_ct4_global`），每条记录大致是：

```
五元组 (src ip, src port, dst ip, dst port, proto)
  + 源/目的 identity
  + 方向 (ingress/egress)
  + rev_nat_index (Service 反向还原用, 见 6.3)
  + 状态/超时 (SYN_SENT / ESTABLISHED / FIN...)
```

作用：**策略检查的结论按"连接"缓存**。一条已放行的 TCP 连接，后续包直接查 CT 放行（不再重复跑策略决策树）；Service DNAT 的回程还原也靠它。策略变更时，agent 会清理受影响的 CT 条目，让存量连接立即按新策略重判。

### 7.5 一条策略在内核里如何被执行

先看策略长什么样。下面这条 CiliumNetworkPolicy 说："**允许带 `org=empire` 标签的 Pod 通过 TCP 访问我的 80 端口**"：

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: empire-can-access
spec:
  endpointSelector:            # ① 谁被这条策略管 (目标端): 本 namespace 中 app=deathstar 的 Pod
    matchLabels:
      app: deathstar
  ingress:                     # ② 入方向规则
  - fromEndpoints:             # ③ 谁可以进: org=empire 的 Pod
    - matchLabels:
        org: empire
    toPorts:                   # ④ 进来后可以碰什么
    - ports:
      - port: "80"
        protocol: TCP
```

它变成内核里的动作：

```
包到达 Pod(deathstar) 的 lxc 设备, to-container 方向 (bpf_lxc):

 ① 查 conntrack:
    已有连接且已放行?  → 直接 FORWARD (策略结论已缓存, 最快路径)
 ② 查 ipcache/封装头 → 得到 src identity (如 5213=rebels)
 ③ 查该 endpoint 的 policy map (cilium_pol_xxx):
      key: (src_identity=5213, direction=ingress)
      value: 允许的 L4 列表 bitmap (TCP/80 ?)
    → identity 5213 不在表里 → 不匹配任何 ingress 规则 → DROP, drop reason = 130 (Policy denied)
    → identity 16777(empire) 在表里且 TCP/80 开 → FORWARD, 并写 CT 缓存
 ④ 转发事件写入 perf buffer → Hubble 记录一条 flow
```

> 注意方向语义：Cilium 的策略是"**目标端 ingress + 源端 egress 双向都要放行**"的（对 fromEndpoints 的流量，源 Pod 侧的 egress 也需允许，除非源端没有策略约束——详见 7.7 的默认语义）。这是新手最常见的困惑点之一。

**eBPF 策略的粒度**由 `toPorts` 决定：不写 = L3（整个 identity 可达）；写 port = L4；写 HTTP/Kafka/DNS 规则 = L7（见下一节）。

### 7.6 L7 策略：无 sidecar 的 Envoy

L3/L4 能在内核里做完，但"只许 POST /login、拒绝 DELETE"需要看懂 HTTP——这超出 eBPF 合理职责（不能在内核里解析协议语义）。Cilium 的方案：**每节点一个 Envoy，Pod 的 L7 流量被 eBPF 重定向给它，Pod 自己毫无感知（没有 sidecar）**。

```yaml
# 在上面 toPorts 的基础上加 rules.http:
    toPorts:
    - ports:
      - port: "80"
        protocol: TCP
      rules:
        http:
        - method: "POST"
          path: "/login"       # 只允许这一个请求
```

执行流程：

```
Pod A (empire) ──HTTP──→ deathstar Pod
        ① bpf_lxc: L4 允许, 但策略标记"此端口需 L7 检查"
        ② eBPF 把该连接重定向(REDIRECT)到本节点 Envoy 的 listener
        ③ Envoy 按 CNP 翻译出来的 HTTP 过滤规则逐请求判断:
             POST /login   → 允许 → 转发给 deathstar (流量标记回数据面)
             GET /v1/exhaust-port → 拒绝 (403 由 Envoy 直接回)
        ④ 每个请求生成 access log → Hubble 显示 method/path/verdict
```

关键点：

- **Envoy 是节点级的**（cilium-envoy 容器），一个节点一个，不是每 Pod 一个 sidecar；由 agent 自动配置 listener/filter，用户不用写任何 Envoy 配置。
- 策略语义仍是 CNP，用户感觉不到代理存在；只有用到 L7 时流量才绕行 Envoy，纯 L3/L4 流量永远不经过用户态，零代理开销。
- DNS 也是 L7 的一种：`toFQDNs` 规则会让 agent 内置的 DNS 代理接管 Pod 的 53 端口查询，学习"域名 → IP"后再放行对应 IP，解决了"策略想按域名写，但网络包里只有 IP"的错配。

### 7.7 默认策略语义：养成好习惯

- **默认全通**：集群里没有任何策略选中某 Pod 时，它随便收发（和 K8s NetworkPolicy 一致的"加法"模型）。
- **一旦有一条 ingress 规则选中该 Pod**：入方向立刻变成"白名单，默认拒绝"，没匹配到的全丢。
- **egress 同理**独立生效。
- 因此最佳实践是先写"兜底 deny"（`endpointSelector: {}` 的空策略），再按需放开，避免"以为有策略其实没生效"。

---

## 八、核心原理四：可观测性 Hubble

### 8.1 flow 事件从哪里来

还记得 3.5 的 hook 表吗？eBPF 程序在每一个决策点都会顺手做一件事——**把事件写进 perf buffer**（内核→用户态的高效事件通道）：

- `bpf_lxc`/`bpf_host`/`bpf_overlay`：每条流的建立、转发、策略丢弃；
- drop tracepoint：内核协议栈丢包也抓得到；
- Envoy access log：L7 请求的 method/path/状态码；
- 加密、CT 回收等内部事件。

agent 侧的 **Hubble Observer** 从这些渠道实时聚合，结合 identity label 把裸的 IP 五元组翻译成"业务语言"（谁、找谁、什么协议、结果如何、按哪条策略）。

### 8.2 Hubble 架构

```
节点1 agent ──Hubble API(gRPC)──┐
节点2 agent ──Hubble API(gRPC)──┤──→ Hubble Relay (聚合所有节点)
节点3 agent ────────────────────┘          │
                              ├─→ hubble observe (CLI 即时查询/跟随)
                              ├─→ Hubble UI (网页拓扑图)
                              └─→ Prometheus metrics → Grafana 面板
```

观察是完全旁路的：**看流量的开销只是事件序列化，不碰数据包本身**；不开 Hubble 时数据面照常工作。

### 8.3 一条 flow 长什么样

```
Mar 10 15:04:01.234: default/xwing-7d8f (identity 5213) ⇄ default/deathstar-0 (identity 16777)
  policy-verdict: L3-L4 DENIED  reason: Policy denied (drop 130)
  TCP 10.0.1.10:43210 → 10.0.1.11:80  flags: SYN
```

翻译：rebels 身份的 Pod 想连 deathstar 的 80 端口，在 L3/L4 阶段被策略拒绝（丢弃码 130），TCP 握手第一包就被丢了。**排查网络问题从"抓包考古"变成"读日志"**，这是 Hubble 的核心价值。

---

## 九、动手实战：从零跑通一个完整例子

> 本节所有命令都可以在一台装了 Docker 的电脑上复现（kind 单节点集群，5 分钟）。

### 9.1 用 kind 起一个实验集群并安装 Cilium

```bash
# 0. 准备工具: docker + kubectl + kind + cilium CLI + helm
#    cilium CLI:  https://github.com/cilium/cilium-cli/releases 下载, 或:
#    macOS: brew install cilium-cli   /  Linux: 官方 release 二进制
#    hubble CLI 同理下载: https://github.com/cilium/hubble/releases

# 1. 创建集群
kind create cluster --name cilium-lab

# 2. 安装 Cilium (v1.20.x)
cilium install --version 1.20.2

# 3. 等待就绪并自检
cilium status --wait

# 4. 官方连接性自检 (自动部署几十个测试 Pod 跑全套检查)
cilium connectivity test
```

`cilium install` 会用 Helm 给 kube-system 装 Cilium；如果集群自带 kube-proxy，它会以"兼容模式"共存；生产上建议显式 `--set kubeProxyReplacement=true` 彻底替代。

### 9.2 解读 cilium status

```
    /¯¯\
 /¯¯\__/¯¯\    Cilium:             OK      ← agent 全部健康
 \__/¯¯\__/    Operator:          OK      ← operator 正常
 /¯¯\__/¯¯\    Envoy DaemonSet:   OK      ← L7 代理容器正常
 \__/¯¯\__/    Hubble Relay:      OK      ← 可观测链路正常
    \__/       Deployment         hubble-ui: OK

DaemonSet         cilium            Desired: 1, Ready: 1/1     ← 每节点 agent
Containers:       cilium-operator   Running: 1                  ← 集群级 operator
                  cilium            Running: 1 on 1/1 nodes
Cluster Pods:     12/12 managed by Cilium                    ← Cilium 接管的 Pod 数
Helm chart version: 1.20.2
Image versions    cilium quay.io/cilium/cilium:v1.20.2: 1
...
```

### 9.3 官方 Star Wars Demo：L3/L4/L7 三层策略

这是 Cilium 文档的经典教学场景：

```
                 ┌───────────────────────────┐
   xwing ────────│  deathstar Service        │──────── tie-fighter
   (org=rebels)  │  app=deathstar            │        (org=empire)
                 │  80/TCP: /login, /v1/exhaust-port ...          │
                 └───────────────────────────┘
```

```bash
# 1. 部署应用 (deathstar + service + xwing + tie-fighter)
kubectl create -f https://raw.githubusercontent.com/cilium/cilium/main/examples/kubernetes/l7_sw/deployment.yaml

kubectl get pods -o wide
# NAME                         READY   LABEL
# deathstar-xxxx               1/1     org=empire,class=deathstar
# tie-fighter                  1/1     org=empire,class=tiefighter
# xwing                        1/1     org=rebels,class=xwing

# 2. 先验证: 没有策略时, 谁都能访问 deathstar
kubectl exec xwing -- curl -s -XPOST deathstar.default.svc.cluster.local/v1/request-landing   # ok
kubectl exec tie-fighter -- curl -s -XPOST deathstar.default.svc.cluster.local/v1/request-landing # ok
```

**第 1 层：L3/L4 策略——只许 empire 进 80 端口**

```yaml
# policy-l3l4.yaml
apiVersion: "cilium.io/v2"
kind: CiliumNetworkPolicy
metadata:
  name: "policy-l3l4"
spec:
  endpointSelector:
    matchLabels:
      org: empire
      class: deathstar
  ingress:
  - fromEndpoints:
    - matchLabels:
        org: empire          # L3: 只许 empire 身份
    toPorts:
    - ports:
      - port: "80"           # L4: 只许 TCP/80
        protocol: TCP
```

```bash
kubectl apply -f policy-l3l4.yaml

kubectl exec tie-fighter -- curl -s -XPOST deathstar.default.svc.cluster.local/v1/request-landing  # ✓ 还能进
kubectl exec xwing      -- curl -s -XPOST deathstar.default.svc.cluster.local/v1/request-landing  # ✗ 卡死 (L3 拒绝)
```

**第 2 层：L7 策略——empire 也只能调 /login**

```yaml
# policy-l7.yaml  (在 L3/L4 基础上收紧)
apiVersion: "cilium.io/v2"
kind: CiliumNetworkPolicy
metadata:
  name: "policy-l7"
spec:
  endpointSelector:
    matchLabels:
      org: empire
      class: deathstar
  ingress:
  - fromEndpoints:
    - matchLabels:
        org: empire
    toPorts:
    - ports:
      - port: "80"
        protocol: TCP
      rules:
        http:
        - method: "POST"
          path: "/login"       # L7: 只放行这一个请求
```

```bash
kubectl apply -f policy-l7.yaml

# empire 自己现在也被管住了:
kubectl exec tie-fighter -- curl -s -XPOST deathstar.default.svc.cluster.local/v1/login   # ✓ 200
kubectl exec tie-fighter -- curl -s -XPOST deathstar.default.svc.cluster.local/v1/exhaust-port  # ✗ 403 Access denied
# ↑ L4 是通的, 是 Envoy 在 HTTP 层拒的 → 这就是无 sidecar 的 L7 能力
```

### 9.4 用 Hubble 观察被拦截的请求

```bash
# 开启 Hubble (若安装时未开)
cilium hubble enable
cilium status --wait

# 端口转发连上 relay
cilium hubble port-forward &

# 跟踪: 从 rebels 发出的、到 deathstar 的、被丢弃的流量
hubble observe --from-label org=rebels --to-label app=deathstar --verdict DROPPED -f

# 再执行一次 xwing 的 curl, 会看到:
# Mar 10 15:04:01 default/xwing (5213) → default/deathstar (16777)
#   policy-verdict: DENIED  TCP 10.0.1.10:43210 → 10.0.1.11:80 SYN
#   DROPPED (Policy denied, drop 130)

# L7 的拒绝能看到 HTTP 细节:
hubble observe --verdict DROPPED --protocol http -f
#   ... tie-fighter → deathstar HTTP POST http://deathstar/v1/exhaust-port => 403
```

```bash
# 也可以看网页版 (拓扑图实时动起来):
cilium hubble ui
```

### 9.5 直接查看 eBPF 内部：把原理"看"出来

cilium CLI 提供了把第四~八章的 Map 内容直接打印出来的命令——**强烈建议对着前面章节逐一运行**：

```bash
# ① Service 前端/后端表 (对应 6.3 三张 map)
cilium bpf lb list
# 10.96.0.10:80/TCP (2)   [loadbalancing]
#   1. 10.0.1.11:8080 (11)      ← deathstar 的后端 Pod
#   2. 10.0.1.12:8080 (12)

# ② IP → Identity (对应 7.3 ipcache)
cilium bpf ipcache list
# 10.0.1.10/32   identity=5213  encryptkey=0
# 10.0.1.11/32   identity=16777 encryptkey=0
# 172.18.0.2/32  identity=1 (reserved:host)
# 0.0.0.0/0      identity=2 (reserved:world)

# ③ 连接追踪表 (对应 7.4 conntrack)
cilium bpf ct list global
# TCP IN 10.0.1.10:43210 -> 10.0.1.11:80
#   src-identity=5213 dst-identity=16777 expires=... state=established

# ④ 本节点 endpoint 及其 Identity (对应 7.2)
cilium endpoint list
# ENDPOINT   POLICY (ingress/egress)   IDENTITY   LABELS
# 1234       Disabled/Disabled         5213       k8s:io.kubernetes.pod.namespace=default
#                                                 k8s:org=rebels
# 5678       3/2                       16777      k8s:org=empire k8s:class=deathstar

# ⑤ Identity 对象本身 (K8s CRD 视角, 对应 7.2)
kubectl get ciliumidentities
kubectl get ciliumendpoints -A          # 每个被 Cilium 管的 Pod 一条
kubectl get ciliumnetworkpolicies       # 你下发的策略

# ⑥ 节点上的 eBPF 痕迹 (内核视角)
ls /sys/fs/bpf/tc/globals/     # 所有 pin 住的 map: cilium_ct4_global, cilium_lb4_services_v2 ...
tc filter show dev lxc<ifname> ingress   # 挂在这个 Pod veth 上的 bpf_lxc 程序
bpftool prog show | head       # 系统里所有 BPF 程序
```

> 看 `endpoint list` 里 `POLICY (ingress/egress)  3/2` 这样的数字：表示该 endpoint 的策略计算后命中了 3 条 ingress 规则、2 条 egress 规则——策略真实生效的直接证据。

### 9.6 串讲：一个 HTTP 请求的完整旅程

把所有原理串成一条线。场景：empire 的 tie-fighter 执行 `curl -XPOST deathstar.default.svc.cluster.local/v1/login`（已配置 9.3 的 L7 策略）：

```
 ① Pod 内: curl 向 DNS (CoreDNS ClusterIP) 查询 deathstar.default.svc.cluster.local
    → DNS 查询被 agent 的 DNS 代理截获并记录 (如策略含 toFQDNs, 在此学习 IP; 本例按 label 匹配, 无感放行)

 ② Pod 内: connect(10.96.0.10:80)   ← ClusterIP
    → [Socket-LB] cgroup/connect4 eBPF: 查 services map → 改写为 10.0.1.11:8080
      (若 socket-lb 未启用, 则在 ③ 的 TC 层做同样的 DNAT)

 ③ SYN 包从 Pod eth0 出来 → host 侧 lxcA TC ingress (bpf_lxc):
    → 查 CT: 新连接
    → 查 endpoints map: 10.0.1.11 在节点2 → 走隧道
    → egress 策略检查: tie-fighter(16776) 有无 egress 规则限制 → 放行
    → VXLAN/Geneve 封装, 外层 {节点1 eth0 → 节点2 eth0}, 头里带 src identity=16776

 ④ 节点1 eth0 发出 → 物理网络 → 节点2 eth0 → cilium_vxlan 解封装
    → [bpf_overlay] 拿到 src identity=16776 (不用查 ipcache)

 ⑤ 节点2 bpf_lxc (to-container, deathstar 的 lxc 设备):
    → ingress 策略: identity 16776 是 empire → 允许 TCP/80
    → 但 80 端口被标记为"需 L7 检查" → REDIRECT 到本节点 Envoy listener

 ⑥ Envoy: HTTP POST /v1/login 匹配 policy-l7 的 method+path → 放行
    → 转发给 deathstar Pod; 同时生成 access log → Hubble

 ⑦ deathstar 响应 → 沿 CT 的反向路径回程 (隧道 → 节点1 → tie-fighter)
    → 响应源地址在 CT 里被还原成 10.96.0.10:80 (curl 全程以为在和 ClusterIP 通信)

 ⑧ 与此同时, 每一步的转发/丢弃决策都在节点1/节点2 生成 flow 事件 → Hubble Relay → 你可以立刻查到
```

如果这时把 curl 换成 `POST /v1/exhaust-port`，第 ⑥ 步 Envoy 直接回 403，第 ⑦ 步变成 Envoy 生成响应，deathstar 根本收不到这个请求——这就是 Hubble 里看到的那条 `HTTP 403 DROPPED`。

---

## 十、进阶特性一览

| 特性 | 一句话原理 | 什么时候用 |
|------|-----------|-----------|
| **加密（WireGuard/IPsec）** | 在节点间隧道/路由层再套一层加密，对 Pod 完全透明 | 合规要求、跨可用区/跨云互信不足 |
| **Bandwidth Manager** | eBPF 在包上打"最早出发时间"（EDT）+ fq 队列，给 Pod 精确限速 | 多租户防吵闹邻居 |
| **Egress Gateway** | Pod 访问外部时 SNAT 成固定出口 IP，让外部防火墙有据可依 | 访问外部系统白名单 |
| **Ingress / API Gateway** | 基于 Envoy 的 L7 入口，K8s Ingress/Gateway API 实现 | 入口流量管理 |
| **ClusterMesh** | 多集群间同步 Identity/ipcache/Service，跨集群全局 LB 与策略 | 容灾、多区域 |
| **BGP** | Cilium 直接向交换机宣告 Pod 网段，native routing on bare metal | IDC 裸金属 |
| **Multi-pool IPAM** | 按 namespace/pod 划分多个网段池 | 对接多 VPC/子网 |
| **Tetragon（姊妹项目）** | kprobe 层 eBPF，观测/拦截进程、文件、系统调用，运行时安全 | 安全审计与入侵检测 |
| **netkit（内核 6.7+）** | 新一代虚拟网络设备，替代 veth，转发更接近原生性能 | 新内核环境可开启 |

与 **Istio** 的关系：Cilium 管四层以下 + 无 sidecar 的 L7 扩展，Istio 专注服务网格（mTLS、流量治理）；Cilium 可作为 Istio 的底层 CNI，也可独立用 Cilium Service Mesh（共享同一套 Envoy）。很多团队的选择是：**Cilium 打底 + 按需叠加 Istio ambient**。

---

## 十一、选型对比与生产建议

### 11.1 Cilium vs Flannel vs Calico

| 维度 | Flannel | Calico | Cilium |
|------|---------|--------|--------|
| 定位 | 极简 overlay 网络 | 网络策略见长 | 网络+安全+观测一体（eBPF） |
| 数据面 | VXLAN + bridge | iptables（可选 BPF dataplane） | eBPF 原生 |
| Service LB | 依赖 kube-proxy | 依赖 kube-proxy（可 eBPF 替代） | 原生 eBPF 替代 |
| NetworkPolicy | 不支持 | 支持（L3/L4） | 支持 + L7 + DNS/FQDN |
| 可观测性 | 无 | 中 | Hubble（每条流） |
| 性能（大规模） | 一般 | 好 | 好（且随内核演进持续受益） |
| 上手难度 | 最低 | 中 | 中（排障要懂 eBPF 工具链） |
| 典型场景 | 小集群/学习 | 策略需求明确的通用选择 | 大规模、安全/合规、可观测要求高 |

### 11.2 内核版本要求（重要）

eBPF 能力是"逐内核版本解锁"的，Cilium 会自动降级使用可用特性，但想要文档宣传的完整体验：

- **最低**：4.19.57+（RHEL 8 的 4.18 backport 也可）
- **推荐**：**5.10 LTS 及以上**——eBPF host routing、高速 socket-LB、更好的 CT 性能都在这条线上
- 内核 6.x + netkit：面向未来的最优数据面

### 11.3 生产实践建议与常见坑

1. **先在测试集群跑 `cilium connectivity test` + `hubble observe`**，熟悉"策略没生效时怎么查"：`cilium endpoint list` 看 policy 命中数 → `hubble observe --verdict DROPPED` 看谁丢的 → `cilium bpf policy get <endpoint>` 看具体规则。
2. **策略语义记牢**：一条 ingress 选中 Pod 即默认拒绝其余入流量；双向（源 egress + 目标 ingress）都要放行。新手 90% 的"策略不生效"是这两条没吃透。
3. **kube-proxy replacement** 在托管 K8s（EKS/GKE/AKS）上有各自的接入限制（如 GKE 的数据面 VPC），按云厂商文档选择模式，不要硬替。
4. **升级只能逐个 minor 版本走**（1.18→1.19→1.20），官方不测试跨版本升级路径；升级前 `cilium status` 全绿再动。
5. **资源预留**：agent 每节点 1C1G 起步（大节点更多），Envoy 另计；节点上 BPF map 都吃内存，超大规模注意 `cilium-operator` 的 map 动态扩容配置。
6. **抓包技巧**：tunnel 模式下 tcpdump 要抓两层（物理口看外层、`-i any` 或明确 lxc 设备看内层）；有 Hubble 时多数问题根本不需要抓包。
7. **不要随意卸载**：卸载 Cilium 前必须让 Pod 网络平滑迁移到新 CNI（eBPF 程序 pin 在内核里，直接卸载会留下"幽灵规则"），遵循官方 uninstall 文档。

---

## 十二、总结与参考资料

### 一张图收尾

```
                 ┌──────────────────────────────────────────────────┐
   K8s 声明式 API │  Pod / Service / NetworkPolicy / Cilium CRD      │
                 └───────────────┬──────────────────────────────────┘
                                 │ watch
                 ┌───────────────▼──────────────────────────────────┐
                 │  cilium-agent（每节点） = "编译器 + 控制器"          │
                 │  把 YAML 编译成: eBPF 程序 + BPF Map 内容           │
                 └───────┬──────────────┬──────────────┬────────────┘
                         │              │              │
              ┌──────────▼───┐  ┌───────▼──────┐  ┌────▼─────────┐
              │ bpf_lxc      │  │ bpf_host     │  │ bpf_overlay  │
              │ Pod进出:转发  │  │ host/NodePort│  │ 隧道封装      │
              │ 策略/CT/服务  │  │ XDP加速       │  │ 身份携带      │
              └──────────────┘  └──────────────┘  └──────────────┘
                    ↓ 每一个决策 → perf buffer → Hubble flow 日志
```

**核心记忆点，就四句话：**

1. **eBPF 把"网络规则"变成"网络程序"**——查表 O(1)、更新原子化、逻辑完全可编程。
2. **Identity 让策略跟着标签走**——IP 易变，工牌不变；L4 之内全在内核，L7 交给节点级 Envoy，无需 sidecar。
3. **Service 就是三张 BPF Map**——前端表、后端表、反向表，外加 socket 层"改签"极致加速。
4. **Hubble 让每个包的生死都有据可查**——数据面顺手写的日志，观测零侵入。

### 参考资料

- Cilium 官方文档（概念/安装/策略/运维）：https://docs.cilium.io/
- Cilium 官网与博客：https://cilium.io/
- eBPF 生态总览：https://ebpf.io/
- Cilium GitHub（源码 `bpf/` 目录即数据面 C 代码）：https://github.com/cilium/cilium
- 官方网络拓扑文档：《Architecture → eBPF Datapath》：https://docs.cilium.io/en/latest/network/ebpf/
- Star Wars L7 示例：https://docs.cilium.io/en/latest/security/policy/language/#http
- cilium-cli：https://github.com/cilium/cilium-cli
- Hubble：https://docs.cilium.io/en/latest/observability/
- Cilium 1.20 发布信息（pkg.go.dev / 官方 release）：https://pkg.go.dev/github.com/cilium/cilium
