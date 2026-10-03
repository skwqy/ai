# iptables 原理与 K8s 应用详解

---

## 目录

- [一、iptables 是什么](#一iptables-是什么)
- [二、Netfilter 内核框架 —— iptables 的底层基石](#二netfilter-内核框架--iptables-的底层基石)
- [三、iptables 的核心结构：表 + 链 + 规则](#三iptables-的核心结构表--链--规则)
- [四、数据包流经的完整路径](#四数据包流经的完整路径)
- [五、常用匹配条件与动作详解](#五常用匹配条件与动作详解)
- [六、K8s 中 iptables 的应用全景](#六k8s-中-iptables-的应用全景)
- [七、K8s Service (ClusterIP) 实现原理](#七k8s-service-clusterip-实现原理)
- [八、K8s NodePort / LoadBalancer 实现](#八k8s-nodeport--loadbalancer-实现)
- [九、K8s iptables 自定义链组织结构](#九k8s-iptables-自定义链组织结构)
- [十、真实 K8s 集群 iptables 规则逐行解读](#十真实-k8s-集群-iptables-规则逐行解读)
- [十一、iptables 模式 vs ipvs 模式](#十一iptables-模式-vs-ipvs-模式)
- [十二、K8s 下 iptables 常用排错命令](#十二k8s-下-iptables-常用排错命令)

---

## 一、iptables 是什么

iptables 是 **Linux 操作系统上工作在用户态的防火墙配置工具**。它通过操作内核中的 **Netfilter 模块**，对进出系统的 IP 数据包进行**过滤、修改、重定向、地址伪装**等处理。

```
┌───────────────────────────────────────────────────┐
│                  用户空间 User Space                │
│                                                   │
│   你 ──命令──►  iptables 命令行工具 ──netlink──►  │
└─────────────────────────────────┬─────────────────┘
                                  │ 读写规则
                                  ▼
┌───────────────────────────────────────────────────┐
│                  内核空间 Kernel Space              │
│                                                   │
│   ┌─────────────────────────────────────────┐     │
│   │              Netfilter                   │     │
│   │   (挂载在网络栈各处的钩子 + 规则匹配引擎)  │     │
│   └─────────────────────────────────────────┘     │
│                 ▲         ▲                        │
│           数据包进入     数据包发出                  │
└───────────────────────────────────────────────────┘
```

> 🔎 **关键区分**：iptables ≠ Netfilter
> - **Netfilter**：内核中的代码框架，提供 5 个挂载点（HOOK）
> - **iptables**：用户态工具，帮你把「规则」写入 Netfilter 维护的表中

---

## 二、Netfilter 内核框架 —— iptables 的底层基石

### 2.1 Netfilter 的 5 个钩子点（HOOK）

Netfilter 在 Linux 网络协议栈中嵌入了 **5 个钩子点**，数据包走到对应位置时会依次触发钩子上挂载的规则链：

```
            ┌──────────────────────────────────────────────────────┐
            │                                                      │
   ┌───────►│  ① PREROUTING              ④ OUTPUT                 │──┐
   │        │  (刚进入网卡，路由决策前)    (本地进程发的包，路由前) │  │
   │        └───────────┬───────────────────────┬──────────────────┘  │
   │                    │                       │                     │
   │               路由决策                路由决策                  │
   │                    │                       │                     │
   │         ┌──────────▼─────────┐   ┌─────────▼────────┐          │
   │         │  是本机要接收的？    │   │ 从哪个网卡出去？  │          │
   │         └──┬─────────────┬───┘   └─────────┬────────┘          │
   │            │             │                   │                   │
   │   是本机   │             │  转发             │                   │
   │            ▼             ▼                   ▼                   │
   │  ┌─────────────┐  ┌──────────────┐   ┌──────────────┐         │
   │  │ ② INPUT     │  │ ③ FORWARD    │   │ ⑤ POSTROUTING│◄────────┘
   │  │ (送往本地)  │  │ (转发给别人) │   │ (即将出网卡) │
   │  └──────┬──────┘  └───────┬──────┘   └──────┬───────┘
   │         │                 │                  │
   │         ▼                 ▼                  ▼
   │   本地进程            出网卡  ◄──────────── 出网卡
   └──────────────────────────────────────────────────────────────►
```

| 钩子点 | 触发时机 | 典型用途 |
|--------|---------|---------|
| **PREROUTING** | 数据包刚从网卡进来，**还没做路由决策**前 | DNAT（端口映射）、修改目的地址 |
| **INPUT** | 路由决策后，发现是发给本机的数据包，交给本地进程前 | 入站防火墙（拒绝/允许访问本地端口）|
| **FORWARD** | 路由决策后，发现不是本机的，需要转发给其他机器 | 转发防火墙（K8s Pod 跨节点流量经过这里）|
| **OUTPUT** | 本机进程产生的发出数据包，**还没做路由决策**前 | 本机发出的包做 DNAT（如 Service 访问就是从 Pod 发出经过 OUTPUT）|
| **POSTROUTING** | 所有即将离开网卡的数据包（不管是本机发出的还是转发的） | SNAT、MASQUERADE（地址伪装，让 Pod 访问外网时看起来像 Node IP）|

### 2.2 为什么 K8s 选择了这 5 个钩子

K8s kube-proxy 的 iptables 模式，核心只用了 **PREROUTING + OUTPUT + POSTROUTING** 三个钩子：

| 钩子 | K8s 怎么用 |
|------|------------|
| **PREROUTING** | 拦截**从外部进入 Node** 的流量，转发给 Pod（NodePort/LoadBalancer 场景）|
| **OUTPUT** | 拦截**从本机 Pod/进程** 发出访问 Service 的流量，做 DNAT 转发 |
| **POSTROUTING** | Pod 访问外网时做 MASQUERADE（源地址伪装成 Node IP），以及集群内一些源地址修正 |
| INPUT/FORWARD | K8s 一般不直接用，但 CNI（如 Calico 的 NetworkPolicy）会用 FORWARD 链实现网络策略 |

---

## 三、iptables 的核心结构：表 + 链 + 规则

iptables 是一个**四表五链**的结构（实际还有其他表，核心常用是四表）。

### 3.1 层次关系

```
iptables
    └── Table 表（按功能分）
          └── Chain 链（按钩子分）
                └── Rule 规则（按顺序匹配，命中就执行 Target 动作）
```

### 3.2 四张核心表（优先级从上到下）

| 表名 | 功能 | 挂载的链 | K8s 使用情况 |
|------|------|---------|-------------|
| **raw** | 状态跟踪前的原始处理，关闭连接跟踪 | PREROUTING、OUTPUT | ❌ 基本不用 |
| **mangle** | 修改数据包头部（TOS、TTL、MARK 标记） | 5 个钩子全挂 | ⚠️ 少量用：K8s 用它打 MARK 用于 ipvs 模式，以及一些特殊路由标记 |
| **nat** | 网络地址转换（DNAT/SNAT）| PREROUTING、INPUT、OUTPUT、POSTROUTING | ✅ **K8s 用得最多**！ClusterIP/NodePort 的 DNAT、外网访问的 MASQUERADE |
| **filter** | 数据包过滤（允许/拒绝/丢弃）| INPUT、FORWARD、OUTPUT | ✅ CNI 网络策略用这里；Docker 默认也在这里放规则 |

### 3.3 规则的基本语法

```
iptables -t <表名> <操作> <链名> <匹配条件> -j <动作/TARGET>
```

| 字段 | 示例 | 说明 |
|------|------|------|
| `-t <表>` | `-t nat` | 指定操作哪张表，默认 filter |
| `<操作>` | `-A`（追加）/`-I`（插入）/`-D`（删除）/`-L`（查看）/`-N`（新建自定义链） | 对链做什么操作 |
| `<链名>` | `PREROUTING` / `KUBE-SERVICES` | 可以是默认 5 链，也可以是自定义链 |
| `<匹配条件>` | `-p tcp --dport 80 -d 10.96.0.100` | 对什么数据包生效 |
| `-j <动作>` | `-j DNAT --to-destination 10.244.1.5:8080` | 匹配上了怎么办 |

### 3.4 常用 Target（动作）一览

| Target | 所在表 | 作用 |
|--------|--------|------|
| **ACCEPT** | filter | 允许通过，不再匹配后续规则 |
| **DROP** | filter | 直接丢弃数据包，不返回任何响应 |
| **REJECT** | filter | 拒绝并返回 ICMP 拒绝响应（对方能感知到被拒）|
| **DNAT** | nat | **目的地址转换**：改数据包的目标 IP/端口，K8s Service 转发的核心 |
| **SNAT** | nat | **源地址转换**：固定改成某个源 IP（需要知道出口 IP 是什么）|
| **MASQUERADE** | nat | **动态源地址伪装**：改成出口网卡的 IP（适合动态公网 IP，比 SNAT 更方便但略慢）|
| **REDIRECT** | nat | 把数据包重定向到本机端口（等价于 DNAT 到本机）|
| **MARK** | mangle | 给数据包打一个内核态的 fwmark 标记（不走网络层，仅内核内部用），用于后续路由匹配 |
| **RETURN** | 任意 | 从当前自定义链返回，继续执行父链后面的规则 |
| **-g 自定义链** | 任意 | 「跳转」到自定义链处理，处理完回来继续 |

> 🎯 **K8s 高频用到的就 4 个**：`DNAT` / `MASQUERADE` / `MARK` / `-g 自定义链跳转`

---

## 四、数据包流经的完整路径

综合「表的优先级」和「链的顺序」，一个数据包实际穿过的 iptables 规则序列如下（加粗的是 K8s 关键路径）：

### 4.1 场景 A：外部流量进入 Node（如 NodePort 访问）
```
数据包从 eth0 进入
    │
    ▼
 ┌─────────────────┐
 │  raw.PREROUTING │  （一般空）
 └────────┬────────┘
          ▼
 ┌─────────────────┐
 │mangle.PREROUTING│  （K8s 可能打 MARK）
 └────────┬────────┘
          ▼
 ┌─────────────────┐
 │  nat.PREROUTING │◄── **K8s 在这里做 DNAT：把 NodePort 转到 Pod IP**
 └────────┬────────┘
          ▼
      路由决策（这个包去哪儿？）
          │
   ┌──────┴──────┐
   ▼             ▼
 本机？        转发？ → mangle.FORWARD → filter.FORWARD → ...（见跨节点）
   │
   ▼
 mangle.INPUT
   ▼
 nat.INPUT
   ▼
 filter.INPUT
   ▼
 本地进程（如 Ingress Controller Pod）
```

### 4.2 场景 B：Pod 内访问 Service（最常见的微服务调用）
```
Pod 内进程发起 http://user-service:80 请求
（user-service 的 ClusterIP = 10.96.0.100）
    │
    ▼
 数据包从 Pod 的 veth 出现在 Node 网络命名空间（本机发出的包）
    │
    ▼
 ┌────────────────┐
 │   raw.OUTPUT   │
 └───────┬────────┘
         ▼
 ┌────────────────┐
 │ mangle.OUTPUT  │
 └───────┬────────┘
         ▼
 ┌────────────────┐
 │   nat.OUTPUT   │◄── **K8s 在这里跳 KUBE-SERVICES 链做 DNAT**
 └───────┬────────┘       ：10.96.0.100:80 → 10.244.2.7:8080
         ▼
     路由决策（现在目的 IP 已变成 Pod IP）
         │
         ▼
 ┌────────────────┐
 │ mangle.POSTROUTING │
 └───────┬────────┘
         ▼
 ┌──────────────────┐
 │  nat.POSTROUTING │◄── **K8s 在这里处理 MASQUERADE（访问外网时）**
 └───────┬──────────┘
         ▼
      出网卡（到其他 Node 的 Pod / 或外网）
```

---

## 五、常用匹配条件与动作详解

### 5.1 常见匹配模块

| 匹配 | 示例 | 说明 |
|------|------|------|
| 协议 | `-p tcp` / `-p udp` / `-p icmp` | 匹配 IP 协议类型 |
| 源 IP | `-s 10.244.0.0/16` | 源地址，支持 CIDR |
| 目的 IP | `-d 10.96.0.0/12` | 目标地址，K8s Service 网段 |
| 入网卡 | `-i eth0` | 从哪块网卡进来（仅 PREROUTING/INPUT/FORWARD） |
| 出网卡 | `-o eth0` | 从哪块网卡出去（仅 FORWARD/OUTPUT/POSTROUTING） |
| 源端口 | `--sport 30000:32767` | TCP/UDP 源端口（需先 -p tcp）|
| 目的端口 | `--dport 80` | TCP/UDP 目的端口 |
| 状态匹配 | `-m state --state NEW,ESTABLISHED` | conntrack 连接跟踪状态 |
| 概率匹配 | `-m statistic --mode random --probability 0.5` | K8s 多 Endpoint 做概率负载 |
| 最近状态 | `-m recent --name xxx` | 限流场景 |
| 字符串匹配 | `-m string --algo bm --string "bad"` | 内容匹配（很少用）|

### 5.2 最常用的连接跟踪状态（conntrack）

Netfilter 的连接跟踪（nf_conntrack）能记住每条 TCP/UDP 流的状态，是 iptables 性能的关键。

| 状态 | 说明 |
|------|------|
| **NEW** | 第一个包（如 TCP SYN） |
| **ESTABLISHED** | 已建立连接的后续包（SYN-ACK 之后） |
| **RELATED** | 关联连接（如 FTP 数据通道、ICMP 错误响应） |
| **INVALID** | 无法识别的包（畸形包），一般 DROP |
| **UNTRACKED** | 在 raw 表里被 `--notrack` 标记过的，跳过连接跟踪 |

> ⚠️ **K8s 大规模坑点**：每个 Service+Endpoint 组合会占用 conntrack 条目。默认内核 nf_conntrack_max 约 262144，Endpoint 上万时需要调大！

### 5.3 DNAT 和 MASQUERADE 示例

```bash
# DNAT：把访问本机 80 端口的请求转到 10.244.1.5:8080
iptables -t nat -A PREROUTING -p tcp --dport 80 -j DNAT --to-destination 10.244.1.5:8080

# MASQUERADE：所有 Pod 网段访问外网时，伪装成出口网卡 IP
iptables -t nat -A POSTROUTING -s 10.244.0.0/16 ! -d 10.0.0.0/8 -j MASQUERADE
```

---

## 六、K8s 中 iptables 的应用全景

K8s 不直接让你写 iptables 规则，而是由每个 Node 上的 **kube-proxy 进程** 自动管理。它监听 API Server 的 Service/Endpoint 变化，实时渲染成 iptables 规则。

### 6.1 kube-proxy iptables 模式工作流程

```
┌───────────────────────────────────────────────────────────────┐
│                       Control Plane                           │
│                      API Server                                │
│                  (存储 Service / Endpoint)                     │
└───────────────┬───────────────────────────────┬───────────────┘
                │ Watch（LIST + WATCH）          │ Watch
                ▼                               ▼
┌──────────────────────────┐    ┌──────────────────────────┐
│  Worker Node 1           │    │  Worker Node 2           │
│                          │    │                          │
│  ┌────────────────────┐  │    │  ┌────────────────────┐  │
│  │    kube-proxy      │  │    │  │    kube-proxy      │  │
│  │  - 每 30s 全量同步 │  │    │  │  - 每 30s 全量同步 │  │
│  │  - 变更时增量同步  │  │    │  │  - 变更时增量同步  │  │
│  └─────────┬──────────┘  │    │  └─────────┬──────────┘  │
│            │ 写入规则     │    │            │ 写入规则     │
│            ▼             │    │            ▼             │
│  ┌────────────────────┐  │    │  ┌────────────────────┐  │
│  │   iptables 规则集   │  │    │  │   iptables 规则集   │  │
│  │ （每个 Node 独立维护）│  │    │  │（每个 Node 独立维护）│  │
│  └────────────────────┘  │    │  └────────────────────┘  │
└──────────────────────────┘    └──────────────────────────┘
```

> ⚠️ 关键点：**每个 Node 有一套完整的 iptables 规则**。1000 个 Service、每个 5 个 Endpoint，就会产生几万条 iptables 规则在每个 Node 上。

### 6.2 K8s 使用的 iptables 能力清单

| # | iptables 能力 | K8s 用来做什么 | 所在表/链 |
|---|---------------|---------------|-----------|
| 1 | **自定义链（-N）** | 把 Service 转发逻辑拆到独立链（KUBE-SERVICES、KUBE-SEP-XXX 等），方便管理 | 所有表 |
| 2 | **跳转（-g/-j）** | 从 PREROUTING/OUTPUT 跳到 KUBE-SERVICES 主链，再按 Service 分发 | nat 表 |
| 3 | **DNAT** | 把 Service ClusterIP:Port 转成某个真实 Pod IP:Port（服务转发核心）| nat.PREROUTING / nat.OUTPUT |
| 4 | **MASQUERADE** | Pod → 外网时把源 IP 伪装成 Node IP；`externalTrafficPolicy: Local` 场景下的返回流量修正 | nat.POSTROUTING |
| 5 | **SNAT** | `service.spec.loadBalancerSourceRanges` 等场景下精确的源地址修改 | nat.POSTROUTING |
| 6 | **statistic 模块** | 多 Endpoint 负载均衡：按概率随机选中一个 Pod（K8s 默认随机）| nat |
| 7 | **comment 模块** | `--comment "default/user-service:"` 给规则加可读注释，排错时你能看懂 | 所有表 |
| 8 | **set / addrtype / ipvs 模块** | KUBE-MARK-MASQ（给需要伪装的包打 0x4000/0x8000 MARK），配合 MASQUERADE 减少重复判断 | mangle / nat |
| 9 | **addrtype 匹配** | `--dst-type LOCAL` 识别目的地址是本机地址（NodePort 用）| nat.PREROUTING |
| 10| **filter.FORWARD 链** | CNI 插件（Calico）用它实现 NetworkPolicy：拒绝 Pod A 访问 Pod B | filter.FORWARD |
| 11| **conntrack（系统层）** | 所有 DNAT/SNAT 之后，回程包自动转回原地址，保证 TCP 连接不中断 | 内核自动 |

---

## 七、K8s Service (ClusterIP) 实现原理

这是 iptables 在 K8s 中**最核心、最复杂**的用法。我们拿一个实际 Service 推导。

### 7.1 准备：一个 3 副本的 Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: user-service
  namespace: default
spec:
  type: ClusterIP
  clusterIP: 10.96.0.100
  ports:
  - name: http
    port: 80
    targetPort: 8080
  selector:
    app: user-service
---
# 对应的 Endpoint（3 个 Pod IP）
# 10.244.1.5:8080、10.244.2.6:8080、10.244.3.7:8080
```

### 7.2 入水口：PREROUTING 和 OUTPUT 都跳 KUBE-SERVICES

首先，kube-proxy 会在 nat 表的两个关键钩子上挂一个入口：

```
*nat
:PREROUTING ACCEPT [0:0]
:OUTPUT ACCEPT [0:0]
:KUBE-SERVICES - [0:0]         # K8s 自定义总入口链

# ① 所有外部进入的流量（NodePort/进入 Node 又访问 ClusterIP 的）都先进 KUBE-SERVICES
-A PREROUTING -m comment --comment "kubernetes service portals" -j KUBE-SERVICES

# ② 所有本机 Pod / 进程发出的流量也先进 KUBE-SERVICES（比如 Pod 访问 ClusterIP）
-A OUTPUT -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
```

### 7.3 KUBE-SERVICES 主链：按 Service 分发

KUBE-SERVICES 里按**每个 Service 的 ClusterIP + 端口**写一条规则，命中就跳到这个 Service 专属的子链：

```
*nat
:KUBE-SVC-L727XZ3NABCDEFGH - [0:0]   # user-service 专属的 Service 链（名称由 hash 生成）

-A KUBE-SERVICES -d 10.96.0.100/32 -p tcp -m comment --comment "default/user-service:http cluster IP" \
     -m tcp --dport 80 -j KUBE-SVC-L727XZ3NABCDEFGH
```

> 这条规则的意思：**如果数据包的目标 IP 是 user-service 的 ClusterIP 10.96.0.100，目标端口是 80 且是 TCP，就跳转去处理这个 Service 的专用链。**

### 7.4 KUBE-SVC-XXX 链：按概率负载分发到 Endpoint

3 个 Pod 怎么选？K8s 默认使用 **statistic 模块 + 等概率 + 串行递减算法**，确保每个 Pod 被选到的概率是 1/N。

```
*nat
:KUBE-SEP-AAAAAAAAAAAAA1 - [0:0]   # Endpoint 1：10.244.1.5:8080 的专用链
:KUBE-SEP-BBBBBBBBBBBBB2 - [0:0]   # Endpoint 2：10.244.2.6:8080
:KUBE-SEP-CCCCCCCCCCCCC3 - [0:0]   # Endpoint 3：10.244.3.7:8080

# KUBE-SVC-L727XZ3NABCDEFGH 链的规则（按顺序匹配）：
#
# 第 1 条：33.3% 概率命中 → 跳 Endpoint 1
-A KUBE-SVC-L727XZ3NABCDEFGH -m statistic --mode random --probability 0.33333 -j KUBE-SEP-AAAAAAAAAAAAA1
#
# （如果第 1 条没中，剩余概率 66.6%，接下来的概率会被归一化）
# 第 2 条：在剩余的 66.6% 里有 50% 概率命中 → 跳 Endpoint 2（实际总概率 = 66.6% × 50% = 33.3%）
-A KUBE-SVC-L727XZ3NABCDEFGH -m statistic --mode random --probability 0.50000 -j KUBE-SEP-BBBBBBBBBBBBB2
#
# 第 3 条：前两个都没中，必然走这里 → 跳 Endpoint 3（剩下的 33.3%）
-A KUBE-SVC-L727XZ3NABCDEFGH -j KUBE-SEP-CCCCCCCCCCCCC3
```

**概率算法原理（N 个 Endpoint）**：第 i 条的 probability = 1 / (N - i + 1)

| i | 公式 | 实际概率 | 累积 |
|---|------|---------|------|
| 1 | 1/3 = 0.333 | 33.3% | 33.3% |
| 2 | 1/2 = 0.5 → 乘剩余 0.667 | 33.3% | 66.7% |
| 3 | 1/1 = 1.0 → 乘剩余 0.333 | 33.3% | 100% |

### 7.5 KUBE-SEP-XXX 链：打 MARK + 真正的 DNAT

每个 Endpoint 链里有两件事：标记需要做 MASQUERADE（如果 Service 开启了 sessionAffinity 或 externalTrafficPolicy 等特性需要）、**真正执行 DNAT 把目标改成 Pod IP:Port**。

```
*nat
# Endpoint 1（10.244.1.5:8080）
:KUBE-MARK-MASQ - [0:0]
-A KUBE-MARK-MASQ -j MARK --set-xmark 0x4000/0x4000   # 打 0x4000 标记，后续 POSTROUTING 统一识别做 MASQ

-A KUBE-SEP-AAAAAAAAAAAAA1 -s 10.244.1.5/32 -m comment --comment "default/user-service:http" -j KUBE-MARK-MASQ
-A KUBE-SEP-AAAAAAAAAAAAA1 -p tcp -m comment --comment "default/user-service:http" \
     -m tcp -j DNAT --to-destination 10.244.1.5:8080
                                                                   └─────────────────┘
                                                                        ↑ 真正的转发目标
```

> 这里的第一条（`-s 10.244.1.5` 跳 KUBE-MARK-MASQ）是处理「发出去的包源地址正好是自己」的特殊场景，避免回环。

### 7.6 收尾：KUBE-POSTROUTING 链统一 MASQUERADE

刚才在 SEP 链里打了 0x4000 标记，在 POSTROUTING 钩子上统一处理：

```
*nat
:KUBE-POSTROUTING - [0:0]
-A POSTROUTING -m comment --comment "kubernetes postrouting rules" -j KUBE-POSTROUTING

# 带 0x4000 标记的，都做 MASQUERADE（源地址伪装）
-A KUBE-POSTROUTING -m comment --comment "kubernetes service traffic requiring SNAT" \
     -m mark --mark 0x4000/0x4000 -j MASQUERADE

# 另外：Pod → 外网的默认规则（如果 CNI 没特殊处理，kube-proxy 也会加）
-A KUBE-POSTROUTING -s 10.244.0.0/16 -m comment --comment "kubernetes service pods to external" \
     -j MASQUERADE  # 或加 ! -d 10.0.0.0/8 避免集群内流量被伪装
```

### 7.7 一次 ClusterIP 调用的 iptables 规则穿越总览

把上面的串起来，从 Pod（10.244.1.20）访问 `user-service.default:80`，数据包经过的完整 iptables 链：

```
 Pod 10.244.1.20 发起 → 10.96.0.100:80
        │
        ▼
 nat.OUTPUT
        │  -j KUBE-SERVICES
        ▼
 KUBE-SERVICES 主链
        │  命中 -d 10.96.0.100 --dport 80
        │  -j KUBE-SVC-L727XZ3NABCDEFGH
        ▼
 KUBE-SVC-L727XZ3NABCDEFGH (负载均衡链)
        │  statistic 命中第 2 条
        │  -j KUBE-SEP-BBBBBBBBBBBBB2
        ▼
 KUBE-SEP-BBBBBBBBBBBBB2 (Endpoint 链)
        │  ① -s 10.244.2.6（不是我，跳过）
        │  ② -j DNAT --to 10.244.2.6:8080  ← 核心转发！
        ▼
      路由决策 → 目的已变成 10.244.2.6（Pod IP）
        ▼
 nat.POSTROUTING
        │  -j KUBE-POSTROUTING
        │  检查：是否有 MARK？是否需要 MASQ？
        │  集群内 Pod → Pod 不做 MASQ（根据 --cluster-cidr 配置）
        ▼
   数据包送出去，目标 Pod 10.244.2.6:8080 接收
```

---

## 八、K8s NodePort / LoadBalancer 实现

### 8.1 NodePort：在 KUBE-SERVICES 链的入口就匹配

NodePort 的 Service 有两个匹配入口：**ClusterIP 正常匹配** + **所有发到 <NodeIP>:<NodePort> 的流量也被同一个 Service 捕获**。

在 KUBE-SERVICES 链中，kube-proxy 会为 NodePort 额外加一条匹配：

```
*nat
# NodePort:30080 的 Service 规则
:KUBE-NODEPORTS - [0:0]

-A KUBE-SERVICES -m comment --comment "kubernetes service nodeports; NOTE: this must be the last rule in this chain" \
     -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS
     #                       ↑ 如果目的 IP 是本机任意 IP（NodeIP），去 NODEPORTS 链

# 在 KUBE-NODEPORTS 里有：
-A KUBE-NODEPORTS -p tcp -m comment --comment "default/user-service:http" \
     -m tcp --dport 30080 \
     -j KUBE-MARK-MASQ                       # ① 先打 0x4000，后续要做源地址伪装
-A KUBE-NODEPORTS -p tcp -m comment --comment "default/user-service:http" \
     -m tcp --dport 30080 \
     -j KUBE-SVC-L727XZ3NABCDEFGH            # ② 再跳这个 Service 的 SVC 链，后续流程和 ClusterIP 完全一样
```

**为什么 NodePort 要强制打 MARK-MASQ？**
答：避免「返回包路由不对称」。例如客户端 203.x.x.x → Node1:30080 → Node2 上的 Pod，Pod 的返回包直接从 Node2 出去，源 IP 变成 Node2 IP，客户端会收到一个不是它请求的 IP 的响应，直接丢弃。**MASQUERADE 把源 IP 改成 Node1 IP**，回程流量一定回到 Node1，再转回客户端。（这就是为什么 NodePort 模式下 Pod 看到的源 IP 都是 Node IP，而不是真正的客户端 IP）

### 8.2 LoadBalancer：和 NodePort 几乎一样

LoadBalancer 类型的 Service 在 iptables 上的实现**完全等价于 NodePort + 外部云负载均衡器**：

- kube-proxy 给它分配 ClusterIP + NodePort
- iptables 规则与 NodePort 一致
- 不同点：云 Controller 在云端再创建一个 LB（如 SLB/ALB/ELB），把 LB 的 80 端口转发到所有 Node 的 NodePort

### 8.3 特殊：externalTrafficPolicy: Local

这个选项会**改变 iptables 规则**：让 NodePort/LoadBalancer 只转发到「当前 Node 上的本地 Pod」，转发失败就丢包。好处是 Pod 能看到真实的客户端源 IP（因为中间不需要 MASQ 了）。

iptables 上的区别：
```
*nat
# externalTrafficPolicy=Local 时，KUBE-XLB-XXX 链的处理：
# 若当前 Node 有这个 Service 的 Pod Endpoint，就直接 DNAT（不打 MARK-MASQ！）
# 若当前 Node 没有对应 Pod，直接 DROP（不走其他 Node）
```

---

## 九、K8s iptables 自定义链组织结构

kube-proxy 创建的自定义链有一套严格的命名规范（便于排错时对应资源）。

```
nat 表中 K8s 自定义链的层级：

PREROUTING ───► KUBE-SERVICES
OUTPUT     ───► KUBE-SERVICES
                    │
                    ├─► 匹配 ClusterIP ─► KUBE-SVC-<HASH>
                    │                       │
                    │                       ├─► statistic... ─► KUBE-SEP-<HASH1> ─► DNAT Pod1:Port
                    │                       ├─► statistic... ─► KUBE-SEP-<HASH2> ─► DNAT Pod2:Port
                    │                       └─► (最后一条)   ─► KUBE-SEP-<HASH3> ─► DNAT Pod3:Port
                    │
                    ├─► 匹配 --dst-type LOCAL ─► KUBE-NODEPORTS
                    │                             │
                    │                             ├─► 匹配 --dport 30080 ─► KUBE-SVC-<HASH>（复用上面的）
                    │                             └─► ...其他 NodePort
                    │
                    └─► 匹配 ExternalIP / LB 入口

POSTROUTING ──► KUBE-POSTROUTING
                    │
                    ├─► mark==0x4000  →  MASQUERADE（KUBE-MARK-MASQ 打过的）
                    └─► 其他 SNAT 规则（如 Pod → 外网）
```

### 9.1 链名速查

| 链名前缀 | 含义 |
|----------|------|
| `KUBE-SERVICES` | 所有 Service 的总入口 |
| `KUBE-SVC-<hash>` | 某个具体 Service 的负载链（跳 Endpoint）|
| `KUBE-SEP-<hash>` | 某个具体 Endpoint（Pod）的 DNAT 链 |
| `KUBE-NODEPORTS` | 所有 NodePort 的集中入口 |
| `KUBE-POSTROUTING` | SNAT / MASQUERADE 总入口 |
| `KUBE-MARK-MASQ` | 打 0x4000 标记的宏链 |
| `KUBE-MARK-DROP` | 打 DROP 标记（用于 NetworkPolicy 联动）|
| `KUBE-FIREWALL` | K8s 入站保护的前置链（和 Kubernetes 健康检查相关）|
| `KUBE-XLB-<hash>` | LoadBalancer + externalTrafficPolicy=Local 专用链 |
| `KUBE-FORWARD` | filter.FORWARD 上的转发控制（默认放行已建立连接）|

> 💡 **排错技巧**：链名里的 hash 由 Service/Endpoint 的 namespace + name + port 信息生成，**不同集群相同 Service 生成的 hash 也一样**（是确定性 hash）。用 `iptables -t nat -L KUBE-SERVICES -n --line-numbers` 看 comment 能定位资源。

---

## 十、真实 K8s 集群 iptables 规则逐行解读

下面是一个真实集群 `iptables -t nat -S` 输出的片段，按刚才的结构标注：

```bash
# 1. 自定义链声明
-N KUBE-SERVICES
-N KUBE-SVC-3NOIDMZSB3M2444F      # 对应某个 Service
-N KUBE-SEP-LF22BGRX323M33II      # 对应某个 Endpoint
-N KUBE-SEP-27FHQI72HFFSADKQ      # 对应第二个 Endpoint
-N KUBE-NODEPORTS
-N KUBE-POSTROUTING
-N KUBE-MARK-MASQ

# 2. 入口钩子挂接
-A PREROUTING -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
-A OUTPUT -m comment --comment "kubernetes service portals" -j KUBE-SERVICES
-A POSTROUTING -m comment --comment "kubernetes postrouting rules" -j KUBE-POSTROUTING

# 3. MARK 宏
-A KUBE-MARK-MASQ -j MARK --set-xmark 0x4000/0x4000

# 4. KUBE-POSTROUTING 处理 MASQUERADE
-A KUBE-POSTROUTING -m mark ! --mark 0x4000/0x4000 -j RETURN            # 没打 MARK 的直接返回（不用伪装）
-A KUBE-POSTROUTING -j MASQUERADE                                        # 打了 MARK 的都伪装
# 外加：Pod 访问外网默认伪装
-A KUBE-POSTROUTING -s 10.244.0.0/16 ! -d 10.244.0.0/16 \
     -m comment --comment "kubernetes service traffic requiring SNAT" -j MASQUERADE

# 5. KUBE-SERVICES 主链：匹配每个 Service
-A KUBE-SERVICES -d 10.96.0.1/32 -p tcp -m tcp --dport 443 \
     -m comment --comment "default/kubernetes:https cluster IP" \
     -j KUBE-SVC-3NOIDMZSB3M2444F        # 以 kubernetes.default 这个系统 Service 为例

# 6. 匹配 NodePort 入口（必须是最后一条！）
-A KUBE-SERVICES -m addrtype --dst-type LOCAL -j KUBE-NODEPORTS

# 7. 单个 Service 的 SVC 链（假设两个 Endpoint）
-A KUBE-SVC-3NOIDMZSB3M2444F -m statistic --mode random --probability 0.50000 \
     -j KUBE-SEP-LF22BGRX323M33II        # 50% 跳第一个
-A KUBE-SVC-3NOIDMZSB3M2444F -j KUBE-SEP-27FHQI72HFFSADKQ   # 剩下的 50% 跳第二个

# 8. 两个 SEP 链做 DNAT
-A KUBE-SEP-LF22BGRX323M33II -s 10.0.0.11/32 \
     -m comment --comment "default/kubernetes:https" -j KUBE-MARK-MASQ
-A KUBE-SEP-LF22BGRX323M33II -p tcp -m tcp \
     -j DNAT --to-destination 10.0.0.11:6443

-A KUBE-SEP-27FHQI72HFFSADKQ -s 10.0.0.12/32 \
     -m comment --comment "default/kubernetes:https" -j KUBE-MARK-MASQ
-A KUBE-SEP-27FHQI72HFFSADKQ -p tcp -m tcp \
     -j DNAT --to-destination 10.0.0.12:6443
```

---

## 十一、iptables 模式 vs ipvs 模式

kube-proxy 有两种代理模式，生产环境的选择很重要。

| 维度 | iptables 模式 | ipvs 模式 |
|------|--------------|-----------|
| **底层机制** | netfilter 规则匹配，每条规则顺序遍历 | 内核 IPVS（LVS 内核模块），哈希查找 O(1) |
| **Service 扩展性能** | Service 数量>1000 时性能下降明显（上万条规则匹配 10ms 级延迟）| 上万 Service 依然稳定（微秒级） |
| **CPU 消耗** | 新增连接时 O(N) 遍历；Endpoint 多了 CPU 飙升 | O(1)，几乎不随 Service 数增长 |
| **负载均衡算法** | 只有「随机（statistic）」+ sessionAffinity=ClientIP | 轮询/最少连接/加权/目标哈希/源哈希 等 10 种 |
| **健康检查** | 依赖 liveness/readiness；Pod NotReady 就没 Endpoint，自动不在规则里 | 同样依赖 Endpoint Controller；IPVS 自己能对 RS 做主动健康检查（kube-proxy 启用） |
| **安装依赖** | 任何 Linux 默认可用 | 需要内核启用 `ip_vs`、`ip_vs_rr` 等模块（大多数发行版已编译好） |
| **并发连接** | 强依赖 conntrack，条目满了直接丢包 | 用 IPVS 自己的连接表，conntrack 占用少（仍需要 SNAT 时才用 conntrack） |
| **包路径复杂度** | 所有包都走 iptables，每个 Service 至少 2-3 条规则匹配 | 只有控制面写入 iptables（MARK/MASQ），数据面包只进入 IPVS 一次 |
| **官方推荐** | 小规模、兼容性优先 | **生产级、大规模**（推荐）|

**什么时候切换到 ipvs？**
- 单集群 Service > 200
- 或 Pod 数量 > 2000
- 或 P99 连接建立延迟敏感（微服务间短连接多）

> 🎯 **对 SpringCloud 的建议**：你的微服务迁移过去后一般会有几十~几百个 Service，起步用 iptables 没问题。一旦 Service+Endpoint 超过 1000，直接切 ipvs 模式。

---

## 十二、K8s 下 iptables 常用排错命令

### 12.1 基础命令速查

```bash
# 查看 nat 表全部规则（K8s 绝大多数都在 nat 表里）
iptables -t nat -L -n -v --line-numbers
#  -t nat      指定 nat 表
#  -L          列出
#  -n          数字显示（不反解域名/端口名，快很多）
#  -v          显示规则匹配的 包数/字节数（看有没有命中很有用！）
#  --line-     显示行号（删规则时用）

# 只看某个自定义链
iptables -t nat -L KUBE-SERVICES -n -v --line-numbers

# 查看 filter 表（NetworkPolicy / Docker 规则）
iptables -t filter -L FORWARD -n -v --line-numbers

# 用 -S 显示完整命令格式（方便复制/阅读具体参数）
iptables -t nat -S KUBE-SVC-3NOIDMZSB3M2444F

# 统计 K8s 相关规则数量（评估集群规模）
iptables -t nat -S | grep -c 'KUBE-'
iptables -t nat -S | grep -c DNAT
```

### 12.2 典型排错流程：访问 Service 不通

**现象**：`kubectl exec pod1 -- curl -m 3 user-service.default` 超时。

```bash
# Step 1：确认 Service 有 Endpoint
kubectl get endpoints user-service -o wide
# 没 Endpoint？→ 检查 Pod label 和 Service selector 是否匹配、Pod 是否 Ready

# Step 2：在运行 pod1 的 Node 上，抓包看流量
kubectl debug node/<node-name> -it --image=nicolaka/netshoot -- bash
# 或者直接上 Node：
tcpdump -i any host 10.96.0.100 -nn
# 能否看到发往 ClusterIP 的包？看不到就是 Pod 内路由问题

# Step 3：确认 iptables 规则存在且命中
iptables -t nat -L KUBE-SERVICES -n -v | grep 10.96.0.100
# 看 pkts 计数器：curl 时有没有增长？没增长就是规则没匹配到（看 dport/协议对不对）

# Step 4：顺着 SVC 链往下看 SEP 链
iptables -t nat -S KUBE-SVC-<HASH>
iptables -t nat -S KUBE-SEP-<HASH>
# 确认 --to-destination 是正确的 Pod IP:Port

# Step 5：conntrack 表是否有坏条目（老 DNAT 结果残留）
conntrack -L -d 10.96.0.100   # 需要 conntrack 工具
conntrack -D -d 10.96.0.100   # 删除旧条目（不影响已有连接，会重建）

# Step 6：目标 Pod 是否正常工作
kubectl exec -it debug-pod -- curl -m 3 http://<直接PodIP>:8080
# PodIP 通、Service 不通 → 100% 是 iptables/路由问题；PodIP 也不通 → 查 Pod/CNI 网络
```

### 12.3 典型排错流程：NodePort 外网不通

```bash
# Step 1：确认 NodePort 端口在监听（其实 netstat 看不到，因为 iptables 是内核层转发）
# 改用：查看是否有对应 KUBE-NODEPORTS 规则
iptables -t nat -L KUBE-NODEPORTS -n -v
# 找 --dport 30xxx，看 pkts 是否增长（从外部 curl 一次）

# Step 2：看防火墙 / 安全组
# 云环境：安全组是否放行 30000-32767 端口段
# 自建：Node 本身的 firewalld/ufw 是否放行
firewall-cmd --list-ports  # CentOS
ufw status verbose          # Ubuntu

# Step 3：MASQUERADE 是否生效
iptables -t nat -L KUBE-POSTROUTING -n -v
# 看 MARK 匹配和 MASQUERADE 计数

# Step 4：externalTrafficPolicy=Local？
# 如果是 Local，检查 Node 上是否存在这个 Service 的本地 Endpoint
kubectl get pods -o wide | grep <NodeName>
# 没有的话就会被 DROP，改 Cluster 或把 Pod 调度过来
```

### 12.4 性能/容量类常用查看

```bash
# 查看 conntrack 使用量（接近上限必须调大！）
sysctl net.netfilter.nf_conntrack_count net.netfilter.nf_conntrack_max
# 调大（临时）
sysctl -w net.netfilter.nf_conntrack_max=1048576

# iptables 规则计数（达到几万条建议切 ipvs）
echo "nat 表规则数：$(iptables -t nat -S | wc -l)"
echo "filter 表规则数：$(iptables -t filter -S | wc -l)"
echo "DNAT 条目数：$(iptables -t nat -S | grep -c DNAT)"
```

---

## 小结

- **iptables 是内核 Netfilter 框架的用户态接口**，核心是 5 钩子 × 4 表 × 多条规则
- **K8s kube-proxy 大量用 nat 表**：PREROUTING/OUTPUT → KUBE-SERVICES → KUBE-SVC-XXX → KUBE-SEP-XXX → DNAT 到 Pod，POSTROUTING 统一处理 MASQUERADE
- **负载均衡靠 statistic 模块**：1/N 递减概率随机选 Endpoint
- **性能瓶颈**：规则数/Endpoint 多了 iptables 线性匹配变慢、conntrack 表爆；此时切 ipvs 模式
- **排错三板斧**：`kubectl get endpoints` + `iptables -t nat -S 对应链` + `tcpdump`
