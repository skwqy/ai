# Cilium 深度解析：Kubernetes 数据面从 iptables 到 eBPF

> **本文对照的版本**：Cilium **v1.20.3**（2026-10-15，镜像 `quay.io/cilium/cilium:v1.20.3`）。Kubernetes 数据面史写到 **1.33**（2025-04-23）nftables 后端 GA 之后的格局：写作时（2026 年 10 月）Linux 上 kube-proxy 的默认模式仍是 iptables，nftables 已稳定但需显式打开。
>
> **和本目录其他文档的关系**：[`k8s介绍.md`](./k8s介绍.md) 第六章讲 Pod 网段、Service、ClusterIP、Ingress 的角色分工；[`iptables.md`](./iptables.md) 讲 Netfilter 五钩子、四表五链，以及 kube-proxy iptables 模式的链是怎么串起来的。本文默认你读过这两处，或至少知道「Service 的 ClusterIP 不是一块网卡上的地址」。这里不再复述四表五链，而是回答下一层问题：**这套 iptables 数据面为什么会走到头，Cilium 用什么换掉它，换掉之后一次调用还经过哪些内核钩子。**
>
> **源码锚点约定**：机制以 Cilium 仓库 tag `v1.20.3`（`github.com/cilium/cilium`）为准。【源码锚点】给出仓库相对路径和符号名。预留身份编号来自该 tag 的 `bpf/lib/identity.h` 与 `pkg/identity/numericidentity.go`，两处已逐项核对。BPF 程序文件名（`bpf_lxc.c`、`bpf_sock.c` 等）在近年版本中保持稳定；若小版本有移动，按符号名在该 tag 的 `bpf/` 下搜索即可。
>
> **阅读约定**：每章先用白话讲清「这件事在解决 iptables 时代的哪一个痛」，再给数据路径和源码锚点。行文里的版本结论能回溯到 Kubernetes 发布说明、KEP-3866，或 Cilium 1.14 / 1.20 的发布说明。

## 如何读这份文档

如果你已经会在集群里 `kubectl get svc`，但说不清包为什么能到 Pod，推荐两遍读法：

- **第一遍（建立地图，1～2 小时）**：只读第一章每一节的开头、第二章的演进对照表（2.9）、第八章的三次走包，以及各章「本章小结」。目标是能回答三句话：kube-proxy 和 CNI 各管什么？iptables、ipvs、nftables、eBPF 各自把「查服务」放在哪一层？Cilium 的身份编号为什么可以让策略不跟着 Pod IP 重写？
- **第二遍（顺着一次调用走）**：第六章（Service 负载均衡）→ 第五章（身份与策略）→ 第三章（钩子）→ 第四章（控制面组件）→ 第七章（可观测、加密、多集群，可跳读）。排错时直接翻第九章的命令表。

---

# 一、总览：Cilium 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Cilium 是跑在每个节点上的 eBPF 数据面，外加一个集群级控制面。** 它同时承担 Kubernetes 网络模型里原本拆开的三件事：给 Pod 分配可路由的地址（CNI）、把 Service 的虚拟地址转到后端（传统上是 kube-proxy）、按身份而不是按 IP 执行网络策略（传统上是另一套 iptables 或独立策略引擎）。官方仓库的自我介绍是：基于 eBPF 的 networking、observability、security；东西向负载均衡在套接字的 `connect()` 上完成，从而换掉 kube-proxy；策略可以做到 L3–L7，并且和 IP 地址解耦。

它解决的不是「再写一套更快的 iptables」。iptables / ipvs / nftables 都可以把 ClusterIP 转成 Pod IP。Cilium 要换掉的是整条链路的组织方式：转发决策放进内核里可热更新的程序和哈希表，安全决策放在一个与地址无关的身份号上，观测从同一条数据路径上直接吐出来。

Kubernetes 自己只规定了网络模型，没有规定数据面实现。官方文档把职责拆得很清楚：Pod 网络由 CNI 插件提供；Service 转发有一个默认实现叫 kube-proxy，CNI 也可以自带更紧耦合的 service proxy；NetworkPolicy 一般也由 Pod 网络实现来落地，简单插件可以完全不实现它。Cilium 就是把这三块收进同一个数据面的那个实现。云上的落地也沿着这条线：Google 的 GKE Dataplane V2、Azure 的「CNI powered by Cilium」、AWS 对 EKS Anywhere 的选择，都是把 Cilium 当作节点数据面，而不是当作 kube-proxy 旁边的一个旁路插件。

## 1.2 设计哲学：读数据路径前先记住四句话

1. **身份先于地址。** 一组标签对应一个数字身份（identity）。策略表里存的是「身份 26831 可以访问身份 1944 的 TCP 80」，不是「10.244.1.7 可以访问 10.244.2.5」。Pod 重建、IP 变化，身份号可以不变，接收端不必重写防火墙。
2. **热路径留在内核，控制面留在用户态。** 选后端、改地址、判策略、封装隧道，由挂在 XDP、TC、cgroup 套接字上的 eBPF 程序完成。cilium-agent 负责把 Service、EndpointSlice、策略翻译进 BPF map。agent 短暂抖动不应让已建立的转发停摆；agent 长时间缺席才会让新 Pod、新策略停止收敛。
3. **能在套接字上做完的，不留到每一个包。** 集群内部访问 ClusterIP 时，在 `connect()` / `sendmsg()` 里就把目的地址改成后端 Pod。此后报文按普通 Pod 到 Pod 的路径走，不再为这一跳做逐包 DNAT，也不再为这一跳占用一条 NAT conntrack。NodePort、集群外进来的流量没有「发起 connect 的那个套接字」握在本节点手里，仍走逐包负载均衡。
4. **每节点做数据面，全集群只做一次的事交给 operator。** IP 池划拨、身份回收、一部分 CRD 协调在 cilium-operator 里做一份。operator 不在转发关键路径上：它短时不可用，已有 Pod 的流量还在；它长时间不可用，新 Pod 可能拿不到 IP。

这四条会反复出现。第二章解释第 3 条在回应 iptables 的哪一种代价；第五章展开第 1 条；第四章展开第 4 条。

## 1.3 模块分层全景

Cilium 不是 Spring 那种多层 Java 库，而是「用户态进程 + 内核程序 + Kubernetes 对象」三层。按一次报文能碰到的层次从上到下看：

```
┌──────────────────────── 集群级入口与多集群 ─────────────────────────┐
│  Gateway API / Ingress    Cluster Mesh（跨集群 Service 与身份）      │
│  cilium-envoy（每节点一个，做 L7）   Hubble UI / Relay（全集群观测）  │
├──────────────────────── 策略与身份 ────────────────────────────────┤
│  Kubernetes NetworkPolicy                                            │
│  CiliumNetworkPolicy / CiliumClusterwideNetworkPolicy                │
│  Cluster Network Policy（1.20 起支持的上游管理员级策略）              │
│  标签 → 数字身份（CiliumIdentity）→ ipcache（IP → 身份）             │
├──────────────────────── Service 负载均衡 ──────────────────────────┤
│  套接字 LB：cgroup 上的 connect / sendmsg / recvmsg（东西向 ClusterIP）│
│  逐包 LB：TC / XDP 上查 Service map（NodePort、集群外、HostPort）     │
│  可选：DSR、Maglev、PreferSameZone / PreferSameNode                  │
├──────────────────────── Pod 连通 ──────────────────────────────────┤
│  CNI：cilium-cni  ← kubelet / 容器运行时调用                         │
│  地址：cluster-pool / Kubernetes PodCIDR / 云厂商 ENI、Azure、多池    │
│  通路：VXLAN 或 Geneve 隧道，或原生路由（可配合 BGP、L2 宣告）         │
│  设备：veth；内核支持时可用 netkit（1.20 起可 auto 探测）             │
├──────────────────────── eBPF 数据面 ───────────────────────────────┤
│  bpf_lxc.c（容器侧）  bpf_host.c（主机网卡）  bpf_overlay.c（隧道口） │
│  bpf_xdp.c（驱动最早处）  bpf_sock.c（套接字 LB）                     │
│  map：Service / 后端、conntrack、ipcache、policy、NAT                 │
├──────────────────────── 内核钩子 ──────────────────────────────────┤
│  XDP → TC ingress/egress → 路由 → cgroup 套接字钩子                  │
│  仍可能保留的 Netfilter：集群外出方向的 MASQUERADE（见 3.4）          │
└────────────────────────────────────────────────────────────────────┘
```

旁边还有一个经常被放进「Cilium」这个词、但不是本数据面的项目：**Tetragon**。它用 eBPF 做运行时安全观测与阻断（进程、文件、系统调用），仓库独立，不负责给 Pod 配 IP，也不负责 ClusterIP。下文除了点明边界，不再展开它。

## 1.4 组件依赖图

一次标准安装里，进程之间的依赖是这样的（箭头表示「调用或监视」）：

```
                        ┌─────────────────────┐
                        │   kube-apiserver    │
                        └──────────▲──────────┘
           watch/list              │ watch/list
     ┌─────────────┬───────────────┼────────────────┬──────────────┐
     │             │               │                │              │
 cilium-operator   │          cilium-agent × N     │        hubble-relay
 （Deployment）    │          （DaemonSet，每节点） │              │
     │             │               │                │              │
     │ 写 IP 池、   │               │ 编程 BPF map   │              │
     │ 身份、LB IP  │               │ 暴露本地 gRPC  │              │
     └─────────────┘               │                │              │
                                   │                │              │
                    kubelet ──CNI──► cilium-cni      │              │
                    （本机二进制，把 ADD/DEL 交给 agent API）       │
                                   │                                  │
                                   ├─► 本节点 cilium-envoy（L7 时）   │
                                   └─► 本节点 Hubble server（嵌在 agent 内）
                                                                      │
                              hubble CLI / hubble-ui ─────────────────┘
```

数据面上，agent 编程之后报文不再回到这些进程。用户态重新介入只有两条窄路：策略要求做 L7 时，报文被重定向到本节点 Envoy；Hubble 从 BPF 的事件环里读已经发生的流，不挡转发。

状态存在哪里，由安装方式决定：

| 存储 | 谁在用 | 什么时候选 |
|---|---|---|
| Kubernetes CRD（默认） | 身份、Endpoint、节点信息都写成 CRD，agent 通过 apiserver watch | 绝大多数集群。少一个外部依赖 |
| 独立 etcd | agent 之间直接传播 ipcache 等状态 | 规模大到 apiserver 上的 watch 成为瓶颈时的优化。官方说明：CRD 已经能满足正确性，kvstore 是规模优化 |

operator 不在转发路径上，但 IPAM 依赖它。cluster-pool 模式下，operator 按节点划 CIDR，agent 从本节点的池子里取 Pod IP。operator 挂了，已分配的地址继续能通，新 Pod 会卡在拿 IP。

## 1.5 关键问题 → Cilium 方案映射（全文导览）

| 数据面碰到的问题 | iptables / kube-proxy 时代的做法 | Cilium 的方案 | 详见 |
|---|---|---|---|
| Pod 跨节点要能直接通信，且自己看到的 IP 就是别人看到的 IP | CNI 做路由或隧道（Flannel VXLAN、Calico BGP） | 同一件事：隧道或原生路由，由 `bpf_lxc` / `bpf_overlay` 完成封装与转发 | 第四章 |
| ClusterIP 不是真实网卡地址，却要能被访问 | kube-proxy 写 DNAT 规则 | 套接字改写，或 TC/XDP 上查哈希表后改目的地址 | 第六章 |
| Service 一多，每包都要线性扫规则 | iptables 链是链表；ipvs / nftables 改成哈希 | BPF map 查找。东西向连这一跳的 NAT 也省掉 | 第二、六章 |
| Service 变更时整表刷新，节点卡一下 | `iptables-restore` 替换大块规则 | 用户态增量更新 map 元素 | 第二、六章 |
| 每条 NAT 连接占一条 conntrack，表满就丢包 | nf_conntrack | 套接字 LB 的东西向流量不再为 Service 这一跳建 NAT 记录；逐包路径使用 Cilium 自己的 CT map | 第六章 |
| NetworkPolicy 跟着 Pod IP 变，全节点重写防火墙 | Calico 等在 filter 表里按 IP 插规则 | 标签聚成身份号，策略按身份匹配；新 Pod 若标签没变，只更新 ipcache | 第五章 |
| 只想放行 `GET /public`，L4 防火墙做不到 | 另外部署 sidecar 或 Ingress | 本节点 Envoy，按身份把选定流量引过去 | 第五、七章 |
| 出了问题只看得到 NAT 之后的地址，对不上标签 | tcpdump + conntrack，人工还原 | Hubble 记录身份、标签、判决和丢包原因 | 第七章 |
| 集群外访问要伪装源地址 | POSTROUTING MASQUERADE | 默认可继续用 iptables；`bpf.masquerade=true` 时改由 BPF 做 | 第三章 |
| 多集群要共用策略语义 | 各自为政，或靠东西向网关 | Cluster Mesh：身份模型跨集群，Service 可以全局发现 | 第七章 |

## 1.6 版本演进：kube-proxy 的四代数据面，以及 Cilium 长在哪一段

写作时的维护窗口：Cilium 同时维护最近三个 minor。2026-10-15 这一天的补丁版本是 **1.20.3、1.19.9、1.18.15**。1.17 及更早已经结束维护。升级跨 minor 之前看官方 Upgrade Guide，尤其是 `kubeProxyReplacement`、Socket LB 和 NetworkPolicy 语义有关的条目。

### 1.6.1 Kubernetes 数据面时间线

Service 的「虚拟 IP → 后端」在 Kubernetes 里换过四代实现。CNI 负责的「Pod IP 怎么通」是平行的另一条线，不要和这张表混成一件事。

| 阶段 | 版本 | 时间 | 数据面在做什么 | 留下的限制 |
|---|---|---|---|---|
| userspace | 1.0 默认；1.1 引入 iptables；**1.2 起 iptables 成为默认**；**1.26 删除 userspace** | 2015 → 2022-12 | 用 iptables `REDIRECT` 把流量拐进 kube-proxy 进程，进程里再拨一条到后端的连接，字节在用户态拷贝 | 两次连接、CPU 高。1.26 起 `--mode=userspace` 直接失败 |
| iptables | 1.1 加入，**1.2（2016-03）起成为默认，写作时仍是默认** | 2016 → 今 | kube-proxy watch Service / EndpointSlice，写成 nat 表规则，内核 DNAT | 规则按链表匹配；更新要大块替换；每流一条 conntrack。链结构见 [`iptables.md`](./iptables.md) |
| ipvs | **1.8 alpha，1.9 beta，1.11 GA** | 2017-09 → 2018 | 内核 IPVS 用哈希表选后端。伪装和部分过滤仍交给 iptables，并用 ipset 把规则条数压成常数 | 要加载 `ip_vs` 等模块；VIP 挂在 `kube-ipvs0` 哑设备上。扩展性好于 iptables，仍然是「先 NAT、再 conntrack」 |
| nftables | **1.29 alpha，1.31 beta，1.33 GA**（KEP-3866） | 2023-12 → 2025-04-23 | 原生 nftables 的 set 与 verdict map，查找近似 O(1)，规则可增量更新。内核 ≥ 5.13 | 显式 `--proxy-mode=nftables` 才启用。默认仍是 iptables。它仍做包级 DNAT，不管 NetworkPolicy，也没有身份和 L7 |
| eBPF service proxy | Cilium 等 CNI 自带，不是 kube-proxy 的一个 mode | 2018 年 Cilium 1.0 之后逐渐成熟，1.8～1.11 一代把 kube-proxy 替换做成可上生产的路径 | 哈希表选后端；东西向可在 `connect()` 改写，省掉这一跳 NAT | 依赖 cgroup v2 上的 BPF。Helm 默认 `kubeProxyReplacement=false`，要替换 kube-proxy 必须显式打开 |

同一时期，控制面也在给数据面减负，但减的是「对象有多大」，不是「一个包要匹配多少条规则」：

| 变化 | 版本 | 对数据面的意义 |
|---|---|---|
| EndpointSlice 替代巨大的 Endpoints 对象 | 1.21 GA（更早的 beta 在 1.17） | kube-proxy / Cilium 收到的更新更小。iptables 规则条数仍然随 Service × 端口 × 后端增长 |
| 多个 Service CIDR | 1.33 GA（KEP-1880） | ClusterIP 网段可以扩。分配方式变了，转发机制不变 |
| `trafficDistribution: PreferClose` | 1.33 GA | 优先把流量送给拓扑上更近的后端。Cilium 1.20 同时补上了 `PreferSameZone` / `PreferSameNode` |
| NetworkPolicy | 1.7 起进入 API，长期由 CNI 实现 | kube-proxy 的四种 mode 都不负责它 |

### 1.6.2 四代 kube-proxy 各自怎么「找到后端」

把一个发往 `10.96.124.37:80` 的 TCP SYN 交给四代实现，差异集中在三处：查找结构、改地址的时机、连接状态记在哪。

| | userspace | iptables | ipvs | nftables |
|---|---|---|---|---|
| 谁选后端 | kube-proxy 进程 | 规则链上的概率跳转（`KUBE-SVC-*` → `KUBE-SEP-*`） | IPVS 调度算法（rr、lc、sh 等） | verdict map 跳到对应后端链 |
| 查找代价 | 用户态 | 链表，服务数量上来后每包要走过一长串规则 | 哈希 | 哈希（set / verdict map） |
| 地址在哪改 | 用户态代为 connect | `nat` 表 DNAT | IPVS 转发 | nftables NAT |
| 连接状态 | 进程里的两条 TCP | `nf_conntrack` | `nf_conntrack` + IPVS 连接表 | `nf_conntrack` |
| 规则怎么更新 | 改监听端口和后端列表 | 重刷 iptables 规则，xtables 锁竞争 | 改 IPVS 虚拟服务与 real server；iptables 侧因为 ipset 可以保持短规则 | 增删 set 元素 |
| ClusterIP 能不能 ping | 不能当普通地址用 | 不能。规则带 `--dport`，ICMP 没有端口，见 [`k8s介绍.md`](./k8s介绍.md) 对 ClusterIP 的说明 | 通常可以。VIP 配在 `kube-ipvs0` 上，内核认为这是本机地址 | 取决于地址是否被当作本地服务地址处理；不要用 ping 判断 Service 是否健康 |
| 还要不要 iptables | 要，用来 REDIRECT | 本身就是 | 要，做伪装和过滤 | 用的是 nftables。节点上的 `iptables` 命令若显示 `nf_tables` 后端，那只是兼容层，和这个 mode 不是同一套规则 |

最后一行值得单独记住。Ubuntu 20.04 之后，很多机器上的 `iptables` 命令其实是 iptables-nft：语法仍是链和规则，内核里落到 nf_tables，语义还是「按顺序匹配的规则链」。kube-proxy 的 **iptables mode** 走的就是这套语义。**nftables mode** 是另一条代码路径，直接写 set 和 verdict map，不再假装自己是 iptables。`iptables -V` 里出现 `nf_tables`，不能说明 kube-proxy 已经切到 nftables mode。

ipvs 模式里 iptables 没有消失。官方 ipvs 实现说明写得很明确：过滤、SNAT、伪装仍用 iptables，服务地址放进 ipset（`KUBE-CLUSTER-IP`、`KUBE-NODE-PORT-TCP` 等），这样 iptables 规则条数不随 Service 个数增长。ipvs 解决的是「选后端」的线性扫描，没有把伪装和防火墙从 Netfilter 里搬出来。

nftables mode 的边界同样明确，这是 KEP-3866 和 1.33 发布说明里的原意：它要成为 Linux 上更好的 kube-proxy 后端，修复 iptables 的性能与更新问题；**替换默认值、淘汰 iptables / ipvs 是后续 KEP 的事**。1.33 GA 之后，新集群若内核 ≥ 5.13，可以用 `--proxy-mode=nftables`（或配置文件里 `mode: nftables`）切换。从 iptables 或 ipvs 切过去时，kube-proxy 会清掉旧后端留下的规则，切回来也会清掉 nftables 规则，不需要先重启节点。节点上的 `nft` 命令行过旧（早于 1.0.0）会和其他 nftables 用户互相干扰，官方建议避免这种组合。

这三代「内核里做 NAT」的实现，都还留下同一类问题，Cilium 后面的章节就是冲着这些去的：

- 策略如果按 IP 写在 filter 表里，会和 kube-proxy 的 nat 表抢钩子顺序，Pod 一变全节点跟着改规则。
- NAT 让「套接字上看到的地址」和「线上的地址」不一致，排错要先还原 conntrack。
- L7（HTTP 路径、DNS 名字）在包过滤层没有协议语义。
- 每个节点一份规则，表达的却是全集群的服务与身份，规模上去之后控制面和数据面一起抖。

### 1.6.3 Cilium 自己的时间线

| 时间 | 事件 | 和数据面的关系 |
|---|---|---|
| 2015-12 | 首次提交 | 项目起点 |
| 2016-08 | LinuxCon 上公布：用 eBPF / XDP 做容器网络 | 数据面从一开始就不是 iptables 规则生成器 |
| 2017-05 | 0.9，开始把 Kubernetes 当作主要场景 | — |
| 2018-04-24 | **1.0** | 第一版稳定发布 |
| 2019-11 | Hubble | 观测从数据路径里直接取流，而不是在外面抓包猜 |
| 2020-08 | GKE Dataplane V2 选用 Cilium | eBPF 数据面进入托管 Kubernetes |
| 2021-05 | 1.10 | kube-proxy 替换、带宽管理等能力进入可规模化使用的阶段 |
| 2021-10-13 | 进入 CNCF，孵化级 | — |
| 2021-12 | 1.11，Cilium Service Mesh 推出 | L7 走每节点代理，而不是每个 Pod 一个 sidecar |
| 2022-05 | Tetragon 单独开源 | 运行时安全与 CNI 数据面分开 |
| 2022-10 | Azure CNI powered by Cilium | — |
| 2023-10-11 | **CNCF 毕业** | — |
| 2023-07-25 | **1.14** | `kubeProxyReplacement` 从 `partial` / `strict` / `probe` / `disabled` 改为布尔值 `true` / `false`（发布说明 #26036） |
| 2024-02 → 2025-07 | 1.15、1.16、1.17、1.18 | 约每半年一个 minor；Gateway API、加密、BGP、云 IPAM 持续补齐 |
| 2026-02-03 | 1.19 | 写作时仍在维护窗口内 |
| 2026-07-29 | **1.20.0** | 见下表 |
| 2026-10-15 | **1.20.3** | 本文对照的补丁版本 |

1.20 里和架构有关、而不是单纯修 bug 的变化（取自该版本 changelog）：

| 变化 | 对读者的含义 |
|---|---|
| 支持上游 Cluster Network Policy（KCNP） | 管理员级、集群范围的策略开始走 Kubernetes 自己的 API。Cilium 的 `CiliumClusterwideNetworkPolicy` 仍是项目自己的 CRD |
| Gateway API 1.5 / 1.6：TCPRoute、UDPRoute、ListenerSet、ExternalAuth、BackendTLSPolicy | 北向入口继续长在 Gateway API 上，而不是只长在 Ingress 上 |
| Cluster Mesh 的 Multi-Cluster Services API 标为稳定 | 跨集群 Service 有了更明确的 API 承诺 |
| `datapath-mode=auto`：内核有 netkit 就用 netkit，否则用 veth | 容器接到主机的那根「虚拟线」可以换设备，钩子模型不变 |
| 策略 map 增加聚合身份（`bpf/lib/identity.h` 里 11–14） | `world`、`remote-node`、`cluster` 可以收成通配项，策略 map 不必为每个对端铺一条 |
| 后端表示支持「成千上万个 Service 共享同一后端」 | Service 数量大时，LB map 的内存与更新方式跟着变 |
| Beta 的双向认证标为废弃；Docker libnetwork 插件移除 | 新集群不要把这两项当现役能力。加密走 WireGuard 或 IPsec |
| 声明支持 `PreferSameZone` / `PreferSameNode` | 和 Kubernetes 的流量分布字段对齐 |

### 1.6.4 三代数据面主题对照

| 维度 | iptables kube-proxy（2016 起，至今默认） | nftables kube-proxy（1.33 GA） | Cilium eBPF（KPR 打开） |
|---|---|---|---|
| 选后端 | 规则链表 + 统计概率跳转 | set / verdict map | BPF 哈希表；可选 Maglev |
| 东西向 ClusterIP | 每个包 DNAT | 每个包 DNAT | `connect()` 改套接字，线上已是 Pod IP |
| 更新方式 | 大块 restore | 增量改 set | 增量改 map |
| 连接跟踪 | nf_conntrack | nf_conntrack | 套接字路径不再为这一跳做 NAT；逐包路径用自己的 CT |
| NetworkPolicy | 不负责，交给 CNI | 不负责，交给 CNI | 同一数据面按身份执行 |
| L7 | 无 | 无 | 本节点 Envoy |
| 观测 | conntrack、计数器、抓包 | nftables 计数器；旧的 iptables 采集器可能看不到 | Hubble |
| 内核要求 | 很低 | ≥ 5.13，且 nft 工具不要过旧 | 启动时探测；KPR 所需能力缺失则 agent 退出 |
| 默认是否启用 | 是（Linux kube-proxy） | 否 | Helm 默认 KPR 为 false |

### 1.6.5 哪些说法已经过时

- **userspace kube-proxy** 在 1.26 删除。教程里若还在讲「kube-proxy 在用户态转发」，那是 2016 年以前的默认路径。
- **`kubeProxyReplacement=partial|strict|probe|disabled`** 在 1.14 废弃，现役取值是 `true` 或 `false`。看到旧 Helm values 要先翻译再升级。
- **「装了 Cilium 就一定没有 kube-proxy」** 不成立。默认 `kubeProxyReplacement=false` 时，NodePort、LoadBalancer、externalIP、hostPort 仍期待 kube-proxy 在场；Cilium 只对集群内 ClusterIP 做逐包负载均衡。
- **「Cilium 把节点上的 iptables 清空了」** 不成立。出集群的 MASQUERADE 默认仍可能走 iptables（`enableIPv4Masquerade=true` 且 BPF masquerade 默认关闭）。判断方法见 3.4 和第九章，不要只看 `iptables-save | grep KUBE-`。
- **用 ping 判断 ClusterIP** 只在 ipvs（地址挂在哑设备上）这类实现里碰巧成立。iptables 模式下 ping 不通是预期行为。Service 是否工作，用它声明的协议和端口。
- **每个 Pod 一个 Envoy sidecar 才叫服务网格。** Cilium 的 L7 是每节点一个 Envoy。1.20 把仍处在 Beta 的双向认证标为废弃，不要把它写成现役的零信任方案；节点间加密看 WireGuard / IPsec。
- **Docker libnetwork 插件** 在 1.20 移除。Cilium 的 Kubernetes 安装路径是 CNI，不是 dockerd 的网络插件。

永久有效、换版本也不会翻面的部分：Kubernetes 网络模型的三条不变量（第二章）、身份与地址分离（第五章）、套接字 LB 和逐包 LB 的分工（第六章）。本文后面的章节按这三条主线写。

## 1.7 全文章节地图

- **第二章**：Kubernetes 要数据面保证什么；userspace、iptables、ipvs、nftables 各解决了哪一层，又把哪一层留给了 CNI。
- **第三章**：eBPF 钩子图。XDP、TC、cgroup 套接字各自看见报文的哪一段；Cilium 和 Netfilter 怎样共存。
- **第四章**：agent、operator、cilium-cni、Envoy 的分工；一个 Pod 从调度到拿到 IP 的控制面路径；隧道与原生路由。
- **第五章**：预留身份、ipcache、策略为何写在接收端；L7 怎样绕到本节点 Envoy。
- **第六章**：套接字 LB 与逐包 LB；`kubeProxyReplacement` 的两种安装；DSR 与 Maglev；和 kube-proxy 共存时谁改地址。
- **第七章**：Hubble、加密、Gateway API、Cluster Mesh，以及 Tetragon 的边界。
- **第八章**：用同一组地址走通「Pod 创建、一次 ClusterIP 调用、一条策略生效」，并排对照 iptables。
- **第九章**：命令、选型、术语，以及和 `iptables.md` / `k8s介绍.md` 的阅读顺序。

---

# 二、Kubernetes 网络模型，以及 iptables 数据面为什么会演进

> 本章是历史与机制综述。iptables 链的逐跳细节以 [`iptables.md`](./iptables.md) 为准；本章只用那些链来说明「代价出现在哪里」。

## 2.1 数据面必须守住的三条不变量

Kubernetes 网络模型可以收成三条，外加两个可选 API：

1. **每个 Pod 有集群内唯一的 IP。** 同一 Pod 里的容器共享这个网络命名空间，它们之间走 `localhost`。
2. **Pod 与 Pod 可以直接通信，中间不做 NAT。** 不管是不是同一节点。Pod 里进程看到的自己的源地址，就是别的 Pod 看到的源地址。Windows 上 hostNetwork Pod 例外。
3. **节点上的代理（kubelet、系统守护进程）能访问本节点的所有 Pod。**

在这三条之上，**Service** 提供一个寿命长于单个 Pod 的虚拟地址；**EndpointSlice** 记录当前后端；**某种 service proxy** 把发往虚拟地址的流量送到后端。**NetworkPolicy** 是可选的访问控制，简单 CNI 可以不实现，API 对象还在，只是不生效。**Gateway API / Ingress / `type: LoadBalancer`** 负责把集群外的客户端引进来。

这三条把「地址」和「虚拟服务」拆开了，也因此拆出了两个组件：

| 组件 | 守哪一条 | 典型实现 |
|---|---|---|
| CNI 插件 | 1 和 2：Pod IP 分配、跨节点可达、不 NAT | Flannel、Calico、Cilium |
| kube-proxy（或 CNI 自带的 service proxy） | Service 的虚拟 IP | iptables、ipvs、nftables、Cilium 的 eBPF LB |
| 通常仍是 CNI | NetworkPolicy | Calico 的 iptables/eBPF 策略、Cilium 的身份策略 |

[`k8s介绍.md`](./k8s介绍.md) 里的三张网段，就是这张表的地址视图：节点网段是物理机，Pod 网段（常见 `10.244.0.0/16`）由 CNI 落地，Service 网段（常见 `10.96.0.0/12`）不配在业务网卡上，只存在于 service proxy 的匹配表里。

后面所有演进都没有改这三条。变的是 service proxy 用什么结构做匹配，以及策略引擎是否还要再插一套 Netfilter 规则。

## 2.2 userspace：先把包拐进进程

1.0 的 kube-proxy 在用户态当代理。每个 Service 在节点上有一个监听；iptables 用 `REDIRECT` 把目的地为 ClusterIP:端口 的流量转给这个监听；kube-proxy `accept` 之后，再向某个后端 Pod `connect`，双向拷贝字节。

这条路径和「包在内核里改个目的地址再转走」完全不同。客户端到 kube-proxy 是一条 TCP，kube-proxy 到 Pod 是另一条 TCP。延迟、文件描述符、上下文切换都按连接数涨。它的历史价值是：在没有成熟的内核负载均衡可用时，先让 Service API 能用。iptables 模式在 1.1 出现、1.2 成为默认之后，userspace 留了很久当兼容项，直到 1.26 删除。今天看到 `--mode=userspace` 会启动失败。

userspace 已经证明了一件后来被 Socket LB 重新捡起来的事：**在连接建立的那一刻选定后端，比每个包都选一次更接近「一次调用」的语义。** 差别是选定之后，userspace 自己搬运字节，Socket LB 把选定结果写回套接字，字节仍走内核转发。

## 2.3 iptables：DNAT 进内核，规则变成链表

从 1.2 起，kube-proxy 的默认工作是翻译，不是转发。它 watch Service 和后端列表，在每个节点的 `nat` 表里写下结构相同的一组链。一个 ClusterIP 调用在节点上的逻辑形状是：

```
报文目的 = ClusterIP:port
        │
        ▼
nat.PREROUTING 或 nat.OUTPUT
        │
        ▼
KUBE-SERVICES          按目的地址匹配到某个 Service
        │
        ▼
KUBE-SVC-<hash>        按概率跳到某个后端（1/n、1/(n-1)…）
        │
        ▼
KUBE-SEP-<hash>        DNAT 成 PodIP:targetPort
        │
        ▼
路由 + CNI             把包送到那个 Pod 所在的节点
        │
回程靠 conntrack 把目的地址改回客户端眼里的 ClusterIP
```

`OUTPUT` 与 `PREROUTING` 的分工、`KUBE-MARK-MASQ` 与 `KUBE-POSTROUTING` 为什么存在、hairpin 和 `externalTrafficPolicy` 怎样改源地址，[`iptables.md`](./iptables.md) 第六到第十章已经按真实规则写过。这里只保留和演进直接相关的四个性质。

**第一，匹配是链表。** `KUBE-SERVICES` 里大体是「一条规则对应一个 Service 端口」。Netfilter 从第一条看到命中为止。Service 从几十涨到几千时，平均每个包要做次数随规模线性增长的比较。CPU 花在「判断这包是不是这个 Service」上，而不是花在转发上。

**第二，更新是大块替换。** 后端变化时，kube-proxy 重新生成规则并用 iptables 的 restore 路径装进去。规则集很大时，装载期间要拿 xtables 锁，节点上其他改防火墙的人或 CNI 一起排队。表现是 Service 变更的瞬间，新连接延迟抖一下，甚至探测失败。这和「每秒有多少包」无关，和「规则文本有多大」有关。

**第三，每条被 NAT 的连接占一条 conntrack。** DNAT 之后回程必须改回去，否则客户端看到的对端不是它 `connect` 的那个 ClusterIP。`nf_conntrack` 表满时，新连接直接失败，日志里却不一定有一条应用层错误。表的大小要按节点内存单独调，这是 iptables 与 ipvs 集群的经典容量参数。

**第四，协议语义停在 L4。** 规则能匹配协议、地址、端口，匹配不了 HTTP 方法和 DNS 名字。NetworkPolicy 的官方能力也停在 IP 和端口。集群若要「只允许访问 `*.example.com`」或「只允许 `GET /healthz`」，必须再引入代理。代理若以 sidecar 形式存在，每个 Pod 多一跳，策略和 iptables 的顺序更难讲清。

这些性质在 Service 只有几十个的集群里几乎感觉不到。它们是规模、变更频率和策略粒度上去之后才变成架构问题的。ipvs、nftables、eBPF 分别拿其中一条或几条开刀。

## 2.4 ipvs：选后端改成哈希，伪装仍留在 iptables

IPVS 是内核里的 L4 负载均衡（IP Virtual Server）。kube-proxy 的 ipvs 模式在 1.8 引入，1.9 beta，1.11 GA。虚拟服务按协议、VIP、端口哈希，后面挂一组 real server，调度算法包括轮询、最少连接、源地址哈希等。查找代价不随 Service 数量线性增长，这是它相对 iptables 的主要收益。

它没有离开 Netfilter，而是把 Netfilter 的职责收窄了：

- **选后端、转发**：IPVS。
- **伪装、丢弃、按来源网段过滤**：仍是 iptables。地址集合放在 ipset 里，所以这部分 iptables 规则可以保持常数条，不随 Service 个数膨胀。官方实现列出的集合包括 `KUBE-CLUSTER-IP`、`KUBE-LOAD-BALANCER`、`KUBE-NODE-PORT-TCP` 等。

还有一个对外可观察的副作用：ipvs 模式会把 Service IP 配到哑设备 `kube-ipvs0` 上，内核因此认为这些地址是本机的。ICMP echo 没有端口，也能命中「这是我的地址」。于是 **ping ClusterIP 在 ipvs 模式下常常通，在 iptables 模式下不通**。通与不通都不代表 HTTP 是否正常，只说明地址有没有被放进本地地址列表。[`k8s介绍.md`](./k8s介绍.md) 里「ClusterIP ping 不通」的讲解，针对的是 iptables 模式。

ipvs 的运维成本在别处：节点要能加载 `ip_vs`、`ip_vs_rr`、`nf_conntrack` 等模块；和某些 ARP、严格反向路径过滤组合时要额外核对。连接状态仍然在，回程仍然依赖 NAT 记录。策略引擎若继续按 IP 写 filter 规则，规模问题还在。

## 2.5 EndpointSlice：减小的是对象，不是规则链表

早期一个 Service 的全部后端塞在同一个 Endpoints 对象里。后端一变，整个对象被重写，watch 的流量和 kube-proxy 的计算量都跟着涨。EndpointSlice 把后端切成多片，1.21 标记为 GA。kube-proxy 和 Cilium 今天 watch 的都是 EndpointSlice。

这对控制面是实打实的改进：一次 Pod 就绪，传播的是一片里的一条记录，不是整份后端清单。它不改变 iptables 模式「每个后端对应一条 DNAT 规则」的数据面结构。规则条数的大头仍是 Service 个数乘以后端个数。把 EndpointSlice 理解成「ipvs / nftables / eBPF 的替代品」会找错层。

## 2.6 nftables：同一种 NAT，换成会哈希的表

iptables（以及它的兼容层）长期是「链表 + 整表替换」。内核侧的后继是 nftables，2014 年前后进入主线，之后新能力和性能工作主要落在它上面。Kubernetes 的 nftables kube-proxy 由 KEP-3866 定义：1.29 alpha，1.31 beta，1.33（2025-04-23）GA。

它针对 2.3 的前两个性质：

- 用 set 和 verdict map 按（地址，端口，协议）跳到处理链，查找不再是扫一条链表。
- 更新可以增删单个元素，不必每次把整份规则文本灌进内核。
- 要求内核 ≥ 5.13。过旧的 `nft` 用户态工具（1.0.0 之前）有 bug，可能干扰节点上其他 nftables 使用。
- 从 iptables 或 ipvs 切到 nftables，或切回去，由 kube-proxy 自己清理前一种后端的规则。

它故意不碰的事情，发布说明也写了：默认模式仍是 iptables，以免现有 CNI、防火墙和观测工具突然失效；NetworkPolicy 继续由 Pod 网络实现负责。报文仍然被 DNAT，回程仍然靠 conntrack。客户端套接字看见的仍是 ClusterIP，抓包看见的可能已经是 Pod IP，要还原仍得看 conntrack。

所以 nftables 是 **kube-proxy 这一支的当代形态**：把「Service → 后端」做成配得上大规模的内核 NAT。它不是身份、L7 和观测的答案。集群如果只痛在「Service 太多，iptables 规则刷新太慢」，并且 NetworkPolicy 已经有别的方案，nftables mode 是 kube-proxy 家族内部的升级。集群如果痛在策略跟着 IP 走、要 L7、要每流的身份信息，就要离开「纯 NAT 代理」这一支，那就是 Cilium 所在的位置。

## 2.7 三件事叠在同一条链路上之后

把 2.1 的三块拼回一个节点，iptables 时代的报文要穿过多套互不知情的规则：

```
Pod 发出，目的 = ClusterIP
    │
    ▼
kube-proxy 的 nat 表          选后端，DNAT 成 Pod IP
    │
    ▼
CNI 的转发（路由 / VXLAN / IPIP）
    │
    ▼
CNI 或网络策略引擎的 filter 表  按「此刻的 Pod IP」允许或丢弃
    │
    ▼
若目的在集群外：POSTROUTING MASQUERADE
```

每一套规则都正确，叠在一起就出现三类日常故障：

- **顺序。** DNAT 之前和之后，策略看到的目的地址不一样。策略作者以为自己在限制「访问某个 Service」，数据面限制的是「访问 DNAT 之后的 Pod IP」，或者相反。
- **放大。** 一个标签没变的 Deployment 滚动更新，IP 全换。若策略以 IP 为键，所有节点的 filter 规则都要更新，尽管「谁可以访问谁」的答案完全没变。
- **看不见。** 丢包发生在某一张表的某一条，应用只看到超时。要把它还原成「哪条 NetworkPolicy」或「哪次选后端」，得同时看 iptables 计数器、conntrack、CNI 路由和抓包。

Cilium 的架构选择是：这三件事共用一套程序和一套 map，在报文还带着身份的时候做完判断。第三章讲这些程序挂在哪，第五章讲身份怎样代替 IP 当键，第六章讲 Service 这一跳具体怎样改地址。

## 2.8 本章小结

Kubernetes 要求 Pod 之间不 NAT、Service 用虚拟 IP 屏蔽后端变化。kube-proxy 用了四代办法实现虚拟 IP：用户态拷贝（已删除）、iptables DNAT（至今默认）、IPVS 哈希（伪装仍用 iptables）、nftables 哈希（1.33 GA，非默认）。后三代都更快地「找到后端」，但都保留包级 NAT 和 conntrack，并且都不实现 NetworkPolicy。策略按 IP 写进另一套 Netfilter 规则之后，地址变化会被放大成全集群的规则重写。Cilium 从这里接上：用 eBPF map 做查找，用身份号做策略的键，并在套接字上把东西向 Service 调用提前改写成 Pod 到 Pod。下一章先看这些程序能挂在内核的哪些位置。

---

# 三、eBPF 地基：程序挂在哪，map 里有什么

## 3.1 从「规则链表」换成「一段被验证过的程序」

[`iptables.md`](./iptables.md) 里的 Netfilter 是固定钩子加上规则链表：包走到 `PREROUTING`，按表的优先级进入某条链，逐条匹配，命中就执行 target。管理员能做的是增删规则，不能换匹配算法。

eBPF 在同一类钩子（以及 Netfilter 够不着的钩子）上放一段程序。程序由用户态装入内核，装入前经过验证器：不能任意循环、不能随意访问内存。通过之后 JIT 成机器码。程序要跨包记住的状态放在 **map** 里，用户态和内核态都能读写。Cilium 的 agent 写 map，BPF 程序在包到达时读 map。

对读过 iptables 的人，可以把它收成一句：以前 kube-proxy 把「若目的是 10.96.124.37:80，则 DNAT 到 10.244.2.5:8080」写成一条规则；现在 agent 把这条对应关系写进哈希表，程序对每个包做一次查找。表的键变了就改元素，不必重写程序，更不必重放一整份规则文本。

官方对数据面的介绍还强调：Cilium 会探测当前内核有哪些能力，探测到较新的特性就用，没有就退到仍能工作的路径。KPR 显式打开时态度相反：缺了套接字 LB 所需的内核能力，agent 启动失败，而不是静默退回一个半套的 kube-proxy。这是 1.14 之后 `kubeProxyReplacement=true` 的约定。

## 3.2 钩子地图

Cilium 文档把实际用到的钩子收成下面几类。和 Netfilter 五钩子并排放，才看得出来它多出了「套接字」这一层。

| 钩子 | 包走到哪了 | Cilium 用来做什么 | 对应程序 |
|---|---|---|---|
| XDP | 驱动收包的最早处，往往还没有完整的套接字缓冲区 | 北向高包率路径上的提前丢弃、提前转发；DDoS 类预过滤 | `bpf/bpf_xdp.c` |
| TC ingress / egress | 已形成 skb，还没进入三层路由决策的深处；挂在具体网卡上 | 本机转发、策略、封装、把包改送到另一块网卡或 Pod 的 veth | `bpf/bpf_lxc.c`、`bpf/bpf_host.c`、`bpf/bpf_overlay.c` |
| cgroup 套接字地址（connect / sendmsg / recvmsg / getpeername） | 系统调用里，包还没进协议栈 | Service 负载均衡：改套接字的目的地址，并让 `getpeername` 仍返回 ClusterIP | `bpf/bpf_sock.c` |
| sockops 与 socket send/recv | TCP 状态变化，以及已建立连接上的发送 | 本机两端都在本节点时，可以把报文在套接字层直接对送到对端，用于加速和配合 L7 代理 | 数据面文档中的 socket layer |

容器侧最常见的附着点是 **veth 对的主机端**。veth 像一根虚拟网线：一头在 Pod 网络命名空间，一头在主机。Cilium 在主机这头的 TC ingress 上看「从 Pod 出来的包」，在通往 Pod 的方向上看「进 Pod 的包」。1.20 起若内核提供 netkit（该设备随 Linux 6.7 引入），`datapath-mode=auto` 会改用 netkit；程序模型仍是「容器与主机之间的那台设备上跑 BPF」。

主机对外的物理网卡（或 bond、VLAN）上挂 `bpf_host.c`。隧道模式再多一块 `cilium_vxlan` 或 Geneve 设备，由 `bpf_overlay.c` 处理。另外还有一对给主机自己用的设备 `cilium_host` 与 `cilium_net`：节点在 Pod 网里的身份、主机进程访问 Pod，走这里。

一张节点内的位置图：

```
        物理网卡 eth0                 隧道设备 cilium_vxlan
        bpf_host / 可选 XDP            bpf_overlay
               │                            │
               └──────────► 路由 / 转发 ◄────┘
                                  │
                             cilium_host
                                  │
                    ┌─────────────┴─────────────┐
                    │  veth 主机端（或 netkit）   │
                    │  bpf_lxc：策略 + 转发       │
                    └─────────────┬─────────────┘
                                  │
                             Pod 网络命名空间
                             应用的套接字
                             cgroup BPF：connect() 时改目的地址
```

XDP 不是默认必开的加速开关。kube-proxy 替换状态里能看到 `XDP Acceleration: Disabled` 这种输出：北向逐包 LB 可以只在 TC 上做，XDP 是再往前推的一层。

## 3.3 map：数据面的「表」

iptables 的状态是规则文本加 conntrack。Cilium 的状态主要是这些 map（名字带版本后缀，如 `v2`，以节点上 `cilium-dbg bpf` 的实际输出为准）：

| map 家族 | 键 → 值 | 谁写 | 谁读 |
|---|---|---|---|
| Service / Backend（`lb4` / `lb6`） | ClusterIP:端口，或 NodePort → 后端 Pod 列表 | agent，依据 Service 与 EndpointSlice | 套接字程序、TC 程序 |
| 反向套接字映射 | 已改写的套接字 → 原来的 Service 地址 | 套接字 LB | `getpeername` / `getsockname`，让应用仍以为自己连着 ClusterIP |
| Conntrack | 五元组 → 已选定的后端、NAT 状态 | 逐包路径上的 BPF | 回程改回地址 |
| ipcache | IP → 身份、隧道对端、加密相关信息 | agent | 转发和策略 |
| Policy | （本端点，对端身份，端口，协议）→ 允许 / 丢弃 | agent，依据策略计算 | `bpf_lxc` 在进出端点时 |
| NAT | 需要伪装的流 | BPF masquerade 打开时 | egress / 回程 |

查找是哈希。Service 从 100 个变成 5000 个，多出来的成本主要是内存和更新这些元素的控制面开销，而不是每个包多扫 4900 条规则。1.20 又把「很多 Service 指向同一批后端」的内部表示压紧了，针对的就是这张表在大规模下的内存。

## 3.4 和 iptables 的真实关系

eBPF 数据面不是「把 Netfilter 从内核编译掉」。一个装了 Cilium 的节点上，iptables 或 nftables 仍可能出现，而且常常是对的。分清三条路径：

| 路径 | KPR = false（Helm 默认） | KPR = true |
|---|---|---|
| Pod 网段互通、NetworkPolicy | Cilium | Cilium |
| 集群内访问 ClusterIP | Cilium 在 Pod 出去的路径上做逐包 LB | 套接字 LB；必要时回落到逐包 |
| NodePort、externalIP、LoadBalancer、hostPort | kube-proxy（iptables / ipvs / nftables） | Cilium |
| Pod 访问集群外时的源地址伪装 | 默认 iptables MASQUERADE（`enableIPv4Masquerade=true`，BPF masquerade 默认关） | 同样，除非打开 `bpf.masquerade` |

因此 `iptables-save` 里还能看到 `KUBE-SERVICES`，说明 kube-proxy 还在，或残留规则没清掉；还能看到伪装规则，则可能只是 Cilium 的 egress masquerade。判断 KPR 是否生效，看 agent 的状态字段，而不是看 iptables 是否为空：

```bash
kubectl -n kube-system exec ds/cilium -- cilium-dbg status --verbose
```

`KubeProxyReplacement: True` 且 Socket LB 为 Enabled，才是「Service 这一支已经交给 eBPF」。`False` 时节点上应该还有 kube-proxy。两套同时对 **同一个** NodePort 做 DNAT，会出现非常难读的回程。安装时在「保留 kube-proxy」和「KPR=true 且不部署 kube-proxy」里选一个，不要各开一半。

还有一条窄的共存是故意的：Istio 这类仍要在 Pod 网络命名空间里看见原始 ClusterIP 的网格，或 KubeVirt、Kata、gVisor 这类套接字钩子够不着的运行时，用 `socketLB.hostNamespaceOnly=true` 让 Pod 内跳过套接字改写，改由 TC 上的逐包 LB 处理。1.20 的升级说明补了一条行为：这种情况下，Pod 访问 NodePort 会在 **客户端 Pod 出去时** 就完成选后端，于是 NetworkPolicy 必须允许客户端访问后端 Pod，而不是只允许访问 Service 的虚拟地址。第六章把这个语义展开。

## 3.5 本章小结

Cilium 把「匹配并改写」从 Netfilter 规则链表换成了挂在 XDP、TC 和 cgroup 套接字上的 eBPF 程序，状态放在 Service、ipcache、policy、conntrack 这些 map 里。容器流量的主附着点是 veth 主机端或 netkit。套接字钩子是相对 iptables 多出来的一层，东西向 Service 在这里就把目的地址换成后端。Netfilter 仍可能负责出集群伪装；KPR 关闭时，NodePort 一类入口仍是 kube-proxy。下一章看谁在用户态把这些 map 填上。

---

# 四、控制面：谁分配地址，谁把 map 填上

## 4.1 四个必知组件

官方组件说明把集群里的 Cilium 收成这些进程。转发不经过它们，但没有它们，map 不会出现。

| 组件 | 形态 | 职责 | 挂了以后 |
|---|---|---|---|
| cilium-agent | DaemonSet，每节点 | watch 本节点关心的 Pod、Service、EndpointSlice、策略与身份；创建端点；加载 BPF；写 map；内嵌 Hubble server | 本节点不再接受新的数据面变更。已加载的程序和已有 map 仍会转发，直到节点或程序被清掉 |
| cilium-operator | Deployment，通常两副本即可 | 全集群做一次的事：按节点划 IP 池、回收身份、协调部分 CRD、LoadBalancer IP 分配 | 不立刻中断转发。cluster-pool 下新 Pod 会因拿不到 IP 而排不上；若使用 kvstore，心跳中断会让 agent 认为存储不健康 |
| cilium-cni | 节点上的 CNI 二进制 | kubelet 创建/销毁 Pod 网络命名空间时被调用，把请求转给本机 agent 的 API | 新 Pod 的沙箱建不起来。已运行的 Pod 不受这次调用失败的影响 |
| cilium-dbg | 随 agent 提供的命令行 | 查本机 agent 的 REST API 和 BPF map | 只影响排错 |

容易和 `cilium` CLI 搞混：集群外那条 `cilium` / Helm 命令负责安装和升级，走 kubeconfig；`cilium-dbg` 在 agent 容器里，看的是这一台节点。

Hubble 的 server 嵌在 agent 里，从 BPF 事件取流。`hubble-relay` 把所有节点的 gRPC 收成集群视图，`hubble-ui` 和 `hubble` CLI 连 relay。Relay 挂了，转发不受影响，只是没有全集群的流视图。

L7 与 Gateway 需要 **cilium-envoy**：每节点一个，而不是每个 Pod 一个。没有 L7 策略、没有 Ingress/Gateway 时，它可以不作为数据路径的一环。

## 4.2 一个 Pod 从调度到可通信

把 [`k8s介绍.md`](./k8s介绍.md) 里的调度流程接到网络上，Cilium 这一段是：

```
调度器把 Pod 绑到节点
        │
        ▼
kubelet 通过 CRI 创建 Pod 沙箱（独立网络命名空间）
        │
        ▼
容器运行时按 CNI 配置调用 cilium-cni 的 ADD
        │
        ▼
cilium-cni 请求本机 cilium-agent
        │
        ├─ 从本节点 IP 池取出一个 Pod IP（池子由 operator 或云 API 事先准备）
        ├─ 创建 veth 或 netkit，一头放进 Pod，一头留在主机
        ├─ 分配 endpoint id，装上 bpf_lxc
        ├─ 根据标签解析身份；标签未齐时先给 init 身份（编号 5）
        ├─ 把 IP → 身份 写入 ipcache，并发布给其他节点
        └─ 按当前策略生成这个端点的 policy map
        │
        ▼
CNI 结果把 IP、路由、DNS 交回 kubelet，容器启动
```

标签稍后才齐全时，身份会从 init 换成正式身份，policy map 跟着更新。init 身份的意义是：在「这是谁」还没算出来之前，不要用一个空身份去匹配「允许所有已打标应用」的策略。正式身份一旦与某个已有标签组相同，就复用那个数字，其他节点上针对该数字的策略不用改。

已经在跑、且当时由别的 CNI 配过网的 Pod，不会自动被 Cilium 接管。kube-dns / CoreDNS 是常见例子。删掉 Pod 让控制器重建，新沙箱才会走 Cilium CNI。生产上对这种系统组件要按滚动方式做，避免 DNS 同时全灭。

## 4.3 地址从哪来：IPAM

| 模式 | 地址谁分配 | 典型场景 |
|---|---|---|
| cluster-pool | operator 给每个节点一块 CIDR，agent 在块内取 IP | 自建、原生路由或隧道都行。最常见的默认 |
| kubernetes | 使用 Kubernetes 已经分给节点的 PodCIDR | 想和 `--cluster-cidr` / 节点 PodCIDR 对齐时 |
| multi-pool | 多块地址池，可按节点或命名空间选用 | 同一集群里要分地址域 |
| ENI / Azure / 阿里云 | 向云 API 要地址，Pod IP 往往就是 VPC 地址 | 云上希望 Pod 与虚拟机同一张路由表，少一层隧道 |

cluster-pool 下，Pod 网段的「节点切片」由 Cilium operator 维护，不必再依赖 kube-controller-manager 的 NodeCIDR 分配。这和 Flannel 常见的「每个节点一块 /24」是同一类拓扑，只是分配者换成了 operator。1.20 支持从 cluster-pool 迁到 multi-pool，也给 ENI 补了 IPv6 前缀（Beta）。

云上的 IPAM 若让 Pod IP 直接可被 VPC 路由，就走原生路由，一般不再对集群内部流量做隧道封装。出 VPC 的流量是否伪装，由 masquerade 和云路由共同决定。1.18 起在 ENI 模式下，IPv4 masquerade 的默认值改成了关闭，因为地址本身已经是 VPC 能路由的。

## 4.4 包怎么到另一台机器

两条主路径，加两种把路由「教」给网络的办法。

**隧道（overlay）。** 节点之间只要 IP 能通。Pod 包在出口节点被封装进 VXLAN 或 Geneve，外层地址是节点 IP，到对端再解开。底层交换机不用认识 Pod 网段。代价是 MTU 要扣掉封装头，以及多一次加解封。身份可以放在隧道元数据里带到对端，接收端不必只靠源 IP 猜对方是谁。

**原生路由（native routing）。** 主机路由表知道「这段 Pod CIDR 从哪块网卡出去、下一跳是哪个节点」。包不封装，源和目的就是 Pod IP，MTU 不打折。前提是这段路由在全网成立：同一二层里用 L2 邻居发现，三层之间用 BGP，云上则用 VPC 路由表。Cilium 自带 BGP 控制面（1.20 使用的 GoBGP 已到 v4 系，BGP CRD 的存储版本迁到 v2），用来把 Pod CIDR 或 LoadBalancer 地址宣告出去。

两种模式下，KPR 都可以打开。选型看的是「底层网络认不认 Pod 网段」，不是看 Service 用不用 eBPF。

出集群的包还差一次源地址处理：外部网络没有回到 Pod CIDR 的路由时，要把源改成节点 IP。这就是 3.4 里的 masquerade。原生路由加上一张真正会回到 Pod 的网络时，集群内部不需要这次改写，这也是「Pod 之间不 NAT」那条不变量的落地方式。

## 4.5 本章小结

cilium-cni 只是 kubelet 的入口，决策在每节点的 agent：分配 IP、插设备、加载 `bpf_lxc`、写身份和策略。全集群只做一次的 IP 池和身份回收在 operator。隧道用节点 IP 当外层，原生路由要求网络认识 Pod CIDR，BGP 和云 IPAM 都是在完成这件事。Pod 能通信之后，还要决定「这个 IP 代表谁」。下一章讲身份号怎样从标签算出来，并被对端拿来执行策略。

---

# 五、身份与策略：键不再是 Pod IP

## 5.1 预留身份：编号是数据面契约

每个端点带一个 32 位数字身份。高 8 位是作用域，低位才是具体编号。`pkg/identity/numericidentity.go` 写明了作用域：`0x00` 全局与预留，`0x01` 本机为 CIDR 分配的身份，`0x02` 远端节点。用户标签分配到的全局身份从 **256** 起；**128～255** 留给用户自定义的保留用途。

预留编号在 BPF 与 Go 两侧是同一套。BPF 侧是 `bpf/lib/identity.h` 的 `enum identity`，Go 侧通过 `pkg/datapath` 的常量引用，不在业务代码里另起一套数字。

| 编号 | 名字 | 含义 |
|---|---|---|
| 0 | unknown | 还没判定 |
| 1 | host | 本机 |
| 2 | world | 集群外。双栈时还会细分 |
| 3 | unmanaged | 不受 Cilium 管理的端点 |
| 4 | health | 本机 cilium-health |
| 5 | init | 端点已创建，标签尚未变成正式身份 |
| 6 | local-node / remote-node | 节点。远程节点在策略里按 remote-node 理解 |
| 7 | kube-apiserver | 提供 apiserver 的那些节点身份 |
| 8 | ingress | 从 Ingress 代理出来的连接使用的源身份 |
| 9 / 10 | world-ipv4 / world-ipv6 | 双栈下按地址族区分的集群外 |
| 11–14 | aggregate cluster / cluster-mesh / world / remote-node | **只出现在策略 map 的聚合项里，不会打到报文上**（1.20） |

`identity.h` 里的注释把「哪些预留身份可以安到端点上」也说死了：unmanaged、health、init 会安到端点；unknown、host、world、remote-node、kube-apiserver 属于保留给数据面逻辑的身份。128 及以上才保证来自用户输入（标签或 CIDR）。

另外还有一组给集群组件准备的固定身份，从 100 起，包括 CoreDNS、kube-dns、cilium-operator，以及它们在「命名空间带默认标签」（Kubernetes 1.21 的 `NamespaceDefaultLabelName`）前后的两套编号。这样在通用标签分配完成之前，策略也可以指向「集群 DNS」这个众所周知的身份。

CIDR 策略（「允许访问 1.2.3.0/24」）使用本机作用域：`CIDR_IDENTITY_RANGE_START` 等于 `(1 << 24) + 1`，与 Go 里的 `MinLocalIdentity` 对齐。这类身份只在本节点的策略计算里有意义，不参与全局分配。

Cluster Mesh 还要在身份里腾出若干位给集群 ID，具体位数受 `max-connected-clusters` 影响。报文 mark 里，身份与集群 ID 的比特布局由 `set_identity_mark` / `get_identity` 完成：普通情况下身份放在 mark 的高位，集群 ID 放在低位。读策略代码时如果看到「身份从 skb mark 里取出来还要重排」，就是这一层。

## 5.2 标签怎样变成编号，编号怎样跟着包走

```
Pod 标签 + 命名空间标签
        │  排序、规范化成一个键
        ▼
CiliumIdentity（集群级 CRD，相同标签组共用一个编号）
        │
        ▼
本端 endpoint 记录这个编号
        │
        ├─ 隧道模式：编号放进 VXLAN / Geneve 的元数据
        ├─ 原生路由：编号放进 skb mark，或由对端用源 IP 查 ipcache
        └─ ipcache：所有节点学到「这个 IP 目前是这个身份」
```

接收端执行策略时，优先相信包上带来的身份；对没有带身份的流量（集群外、尚未被 Cilium 管理的主机），用源 IP 查 ipcache，查不到就落到 world。`inherit_identity_from_host` 这段逻辑区分了几种 mark：从 ingress 代理回来的、从 egress 代理回来的、已经带身份的、本机发出的。代理回来的包会打上标记，避免接收端再次把它送进代理，造成循环。这就是 L7 重定向能和 L4 策略叠在一起的原因。

ipcache 是「地址到身份」的缓存，不是策略本身。一个身份对应多少个 IP 都可以。Deployment 滚动时，旧 IP 从 ipcache 删除，新 IP 指向同一个身份号。允许「frontend 访问 backend」的那条策略一直指向这两个号，接收节点不用因为 IP 变了去改 policy map 的键。

这就是相对「按 IP 写 iptables」的规模差异。iptables 策略的键是地址，地址是 Pod 的寿命；身份的键是标签组，标签组是应用的寿命。控制面要传播的更新，从「每个节点刷新与该 IP 有关的所有规则」变成「更新一条 IP 到身份的映射，外加在 IP 所属节点装好端点」。

## 5.3 策略写在端点上，默认仍是放行

Kubernetes NetworkPolicy 的语义是：**没有策略选中这个 Pod 时，全部允许；一旦有策略选中它，就变成「只放行策略列出的流量」。** Cilium 实现这条语义，并加上自己的 CRD。

| 对象 | 范围 | 多出来的能力 |
|---|---|---|
| NetworkPolicy | 命名空间 | 官方 L3/L4：Pod 选择器、命名空间选择器、CIDR、端口 |
| CiliumNetworkPolicy | 命名空间 | 实体（host、world、remote-node、cluster、kube-apiserver、ingress、1.20 起的 cluster-mesh）、FQDN、L7 |
| CiliumClusterwideNetworkPolicy | 集群 | 同上，不绑在单个命名空间 |
| Cluster Network Policy（KCNP） | 集群，上游 API | 1.20 开始由 Cilium 实现。管理员策略，和 CCNP 这条 CRD 线并行 |

计算发生在 agent：把选中本端点的策略编译成一张 map，键里包含对端身份、端口和协议。报文进出端点时，`bpf_lxc` 查这张表。身份侧的聚合项（编号 11–14）让「整个集群」「所有远端节点」「整个世界」可以各占少量条目，而不是为每个对端身份铺开。1.20 的发布说明指出，这在某些集群里能把 policy map 的占用减少数千条。

默认放行意味着：只装 Cilium、不写 NetworkPolicy，连通性和「没有策略的 Flannel」一样开放。安全收益来自写下去的策略，不是来自把 CNI 换成 Cilium 这个动作本身。

丢弃的方向要分清。入向策略在接收端点执行，出向策略在发送端点执行。1.20 对 IPv6 出向拒绝会回 ICMPv6 Destination Unreachable；IPv4 上是否回 ICMP、还是直接丢，要看具体配置。应用侧多数时候仍表现为超时或连接失败，Hubble 里才能看到是哪条策略、哪一个身份被拒绝。

## 5.4 FQDN 与 L7：名字和 HTTP 不在 BPF 里解析

BPF 程序适合固定偏移的头部查找，不适合做完整的 HTTP 解析，也不适合在 DNS 响应里提取「这次查询允许的 IP」。这两件事交给本节点的 Envoy，BPF 只负责把「需要看 L7 的流」重定向过去。

**DNS / FQDN 策略**（允许访问 `api.example.com` 或 `*.trusted.com`）的实际顺序是：

1. 出向策略允许 Pod 向 DNS 服务查询（通常就是身份表里那个 CoreDNS）。
2. 代理看到允许的查询回答了哪些 A/AAAA 记录。
3. 这些 IP 以有 TTL 的方式放进策略状态，后续发往这些 IP 的连接才能通过。
4. TTL 过期或答案变化，允许的 IP 集合跟着变。

所以 FQDN 策略不是「在 BPF 里匹配域名字符串」。域名在 DNS 响应里出现一次，之后数据面匹配的仍是 IP，只是这些 IP 的寿命绑在 DNS 上，并且只对做过允许查询的那个身份有效。

**HTTP / gRPC 策略**匹配方法、路径、头。报文在发送端被转到 Envoy，Envoy 允许之后再把连接送到真实后端，并带上 ingress 身份（编号 8）这一类标记，接收端认得「这是代理已经检查过的流量」，不会再送回代理。1.20 的 Envoy 跟随 Cilium 的 envoy 构建更新到 1.37 系列；Gateway 与 L7 策略共用这条每节点代理，而不是每个业务 Pod 注入 sidecar。

Kafka 感知策略在更早的版本里存在过。1.20 的升级说明把它列为升级时要处理的遗留项。新的策略按 DNS、HTTP、gRPC 和 Gateway API 来写。

套接字层还有一条与「选 Service 后端」不同的加速：本机两端、或一端是本机代理时，可以在 TCP 进入 ESTABLISHED 之后把后续发送直接对送到对端套接字。它优化的是已经允许的本地流量，不代替 policy map，也不代替第六章的 Service LB。

## 5.5 本章小结

身份是标签组的数字编号，预留的 0–14 在 `bpf/lib/identity.h` 里写成数据面契约，全局用户身份从 256 起，CIDR 身份占用 `0x01` 作用域。策略 map 的键是身份而不是 Pod IP，因此滚动更新只改 ipcache。没有策略时默认放行。L7 和 FQDN 由本节点 Envoy 处理，BPF 负责把选定的流送过去并防止再入代理。Service 的虚拟 IP 还没讲：下一章看这个编号体系之外的那张负载均衡表。

---

# 六、Service 负载均衡，以及 kube-proxy 替换

## 6.1 两种负载均衡，对应两类流量

Service 的后端选择在 Cilium 里分成两条实现，因为有的流量握着本机套接字，有的流量只是路过这块网卡。

| | 套接字 LB | 逐包 LB |
|---|---|---|
| 钩子 | cgroup v2：`connect`（TCP 与已连接 UDP）、`sendmsg`（UDP）、`recvmsg`（UDP），以及 getpeername/getsockname | 设备上的 TC，或更早的 XDP |
| 典型流量 | Pod 或主机进程访问 ClusterIP、以及 KPR 打开时从本机发起的 NodePort | 从集群外进入的 NodePort / LoadBalancer、没有套接字钩子的流量、显式绕过套接字 LB 的 Pod |
| 改的是什么 | 套接字的目的地址改成后端 PodIP:端口 | 报文的目的地址，并在 CT map 里记住 |
| conntrack / NAT | 这一跳不再做 NAT。线上就是 Pod 到 Pod | 需要 CT，回程按记录改回 |
| 应用看到的对端 | 仍是 ClusterIP。反向映射把 `getpeername` 的结果改回去 | 应用若在远端，看到的是它连接的那个 NodeIP:NodePort |

官方对东西向的表述是：在 `connect()` 重写，避免逐包 NAT，用来替换 kube-proxy。对南北向的表述是：XDP 或 TC 上的 L4 负载均衡，可选用 DSR 和 Maglev。

KPR 依赖套接字 LB。cgroup v2 默认由 DaemonSet 的 init 容器挂到 `/run/cilium/cgroupv2`（需要短暂挂载主机 `/proc`）。若节点已经自己挂好了 cgroup2，可以关掉自动挂载并指定 `cgroup.hostRoot`。

## 6.2 东西向：一次 connect 里发生的事

用 [`k8s介绍.md`](./k8s介绍.md) 里同一组地址。order Pod `10.244.1.2` 访问 `user-service` 的 ClusterIP `10.96.124.37:80`，后端是 `10.244.2.5:8080`。KPR 打开且套接字 LB 生效时：

```
order 进程调用 connect(10.96.124.37:80)
        │
        ▼
cgroup BPF（bpf_sock.c）
        │  查 Service map：10.96.124.37:80 的后端列表
        │  按算法选中 10.244.2.5:8080
        │  把套接字目的地址改成后端
        │  记下反向项：这个套接字对外仍呈现为 ClusterIP
        ▼
内核按目的 10.244.2.5 正常建连
        │
        ├─ 同节点：bpf_lxc 查策略，转到本机 veth
        └─ 跨节点：路由或 VXLAN/Geneve，对端 bpf_lxc 再查策略
        │
        ▼
user Pod 上的进程 accept。它看到的源地址是 10.244.1.2，
目的地址是自己的 10.244.2.5:8080
```

和 iptables 模式对照，差异集中在「地址从哪一刻开始变成 Pod IP」：

| 观察点 | iptables kube-proxy | Cilium 套接字 LB |
|---|---|---|
| 应用 `connect` 的地址 | 10.96.124.37:80 | 10.96.124.37:80 |
| `getpeername` | 10.96.124.37:80 | 10.96.124.37:80（反向映射改回去的） |
| Pod 出去的报文目的地址 | 仍可能是 ClusterIP，要到 nat OUTPUT/PREROUTING 才 DNAT | 已经是 10.244.2.5:8080 |
| 策略引擎看到的目的 | 取决于它挂在 DNAT 之前还是之后 | 后端 Pod 的身份与地址 |
| 回程 | conntrack 把源地址改回 ClusterIP | 后端直接把包发回 10.244.1.2，连接的另一端本来就是后端 |
| 节点上的 nf_conntrack | 有一条 DNAT 记录 | 没有「ClusterIP ↔ Pod」这一条 NAT 记录 |

第三行决定了 NetworkPolicy 怎么写。套接字改写之后，线上没有 ClusterIP 这个目的地址。客户端的出向策略要允许它访问 **后端 Pod 的身份**，后端的入向策略要允许 **客户端身份**。只写「允许访问 ClusterIP 网段」在这条路径上对不上报文。`socketLB.hostNamespaceOnly=true` 或关闭套接字 LB、退回 TC 逐包 LB 时，1.20 起 NodePort 也会在客户端 Pod 出去时就选好后端，策略同样要按后端身份来写。

这也是 sidecar 网格和 Cilium 套接字 LB 容易冲突的原因：sidecar 需要在 Pod 网络命名空间里劫持发往 ClusterIP 的连接，自己再转出去。套接字若已被 Cilium 改成 Pod IP，sidecar 看到的目的地「太真实」了。需要 sidecar 继续工作时，用前面的 `socketLB.hostNamespaceOnly` 把 Pod 命名空间排除在套接字改写之外。

后端选择还要尊重 Kubernetes 已经表达的意图：会话保持（session affinity）、正在终止的后端不再接新连接但放完老连接（graceful termination）、EndpointSlice 上的权重。1.20 的 Maglev 后端开始使用 EndpointSlice 权重，并支持 `PreferSameZone` 与 `PreferSameNode`。状态输出里的 `Backend Selection: Random` 表示没改成 Maglev 时的默认挑选方式。

## 6.3 南北向：NodePort、LoadBalancer、externalIP、hostPort

这些流量的客户端不在本节点执行 `connect()`，套接字钩子无包可改。报文到达节点网卡时，目的是 `节点IP:30080` 或云厂商转到节点上的地址。逐包路径：

```
客户端 → 节点 IP:NodePort
        │
        ▼
eth0 上的 XDP（若启用）或 TC（bpf_host.c）
        │  查 Service map，选后端
        │  模式决定源、目的怎样改
        ▼
转发到本机 Pod，或再转到其他节点上的 Pod
        │
        ▼
回程按同一模式把地址改成客户端期望的样子
```

KPR 打开后，状态里会列出已经接管的类型：ClusterIP、NodePort（端口段默认 30000–32767，与 kube-proxy 的习惯一致）、LoadBalancer、externalIPs、hostPort。hostPort 不再需要 docker 或 kube-proxy 在主机上做端口映射。

几个和 iptables 时代同名、但实现位置不同的行为：

- **externalTrafficPolicy=Local** 的意图不变：只把流量送给本节点上的后端，从而保留客户端源地址。没有本地后端时就失败，而不是偷偷转到别的节点再 SNAT。
- **源地址保留** 在 DSR 下更进一步。DSR（Direct Server Return）让请求经过负载均衡节点改写后送到后端，**响应由后端直接回到客户端**，不再折返均衡节点。Cilium 的 NodePort 模式可以是 SNAT（默认，状态里会写 `Mode: SNAT`）、DSR 或混合。DSR 要求后端节点在发出响应时使用客户端期望的服务地址作为源，这个改写由后端节点上的 BPF 完成。
- **Maglev** 用一致哈希选后端。后端集合变化时，只搬移大约 `1/n` 的流量，适合「同一客户端尽量打到同一后端、但后端会扩缩」的场景。它是选择算法，不是一种独立的转发模式，可以和 SNAT 或 DSR 组合。
- **NodePort 与临时端口重叠** 时，Cilium 会把 NodePort 段写入 `ip_local_reserved_ports`，避免主机上一个随机源端口正好等于某个 NodePort，把无关连接拐进 Service。`nodePort.autoProtectPortRanges=false` 可以关掉。默认还会阻止主机网络命名空间里的进程 `bind` 到 NodePort 上，避免应用偷偷占掉本该属于 Service 的端口。

LoadBalancer 类型的对外地址还有一半不在节点数据面里：云厂商的负载均衡器要把流量送到节点。Cilium 可以分配并宣告这些地址（BGP 或 L2），真正把 `VIP:端口` 转到 Pod 的仍是上面的逐包 LB。Gateway API 则是再上一层的 HTTP 路由，落到后端时还是 Service，见第七章。

## 6.4 kubeProxyReplacement 的两种安装

Helm 默认 `kubeProxyReplacement=false`。1.14 起这个值只接受 `true` / `false`；更早的 `partial`、`strict`、`probe`、`disabled` 不要再写进新的 values。

**false：Cilium 做 CNI 和策略，Service 的「对外部分」留给 kube-proxy。**

- 集群内、从 Cilium 管理的端点出发的 ClusterIP，由 Pod 出向路径上的逐包 LB 处理。
- NodePort、LoadBalancer、externalIP、hostPort 仍由 kube-proxy 编程。
- 节点上应当能看到 kube-proxy 的规则（iptables、ipvs 或 nftables，取决于 kube-proxy 的 mode）。
- 这是「先把 Flannel/Calico 换成 Cilium，先不动 Service 数据面」的路径。

**true：不部署 kube-proxy，Service 全部由 Cilium 编程。**

- 必须让 agent 知道 apiserver 的地址（`k8sServiceHost` 与 `k8sServicePort`）。没有 kube-proxy 时，谁都还没为 `kubernetes` 这个 Service 配好 ClusterIP，agent 不能靠 Service 环境变量自己发现 apiserver。
- 内核若缺少套接字 LB，agent 退出，而不是退回 iptables。
- 隧道模式和原生路由都可以。
- 装好之后用 `cilium-dbg status` 核对 Socket LB、协议（TCP/UDP）、设备、SNAT 还是 DSR、以及五种 Service 类型是否 Enabled。

kubeadm 集群若在 `kubeadm init` 时就跳过 kube-proxy，初始化输出里的 `KUBERNETES_SERVICE_HOST` 没有人去实现，所以上面的 host/port 是安装参数的一部分，不是可选项。官方 kubeproxy-free 文档的 Helm 片段就是 `kubeProxyReplacement=true` 再加上这两个参数。

从 false 切到 true 时，要先确认没有第二套数据面还在改同一批地址，然后移走 kube-proxy，再核对 NodePort 与外部负载均衡。回退则相反：先恢复 kube-proxy，再关 KPR，避免中间有一段「谁都不转发 NodePort」。

## 6.5 和 nftables kube-proxy 怎样选

1.33 之后，只想改进 Service 规模、又要保留现有 CNI 的集群，可以把 kube-proxy 切到 nftables，而不引入 Cilium。已经决定用 Cilium 做策略和连通性的集群，还有三种合理组合：

| 组合 | Service 谁做 | 策略谁做 | 适合 |
|---|---|---|---|
| 现有 CNI + kube-proxy nftables | kube-proxy | 该 CNI，若它实现了 NetworkPolicy | 只想换掉 iptables 规则链表 |
| Cilium，KPR=false + kube-proxy（iptables、ipvs 或 nftables） | 集群内 ClusterIP 偏向 Cilium 逐包路径；NodePort 等仍是 kube-proxy | Cilium | 分阶段迁移，先替换 CNI |
| Cilium，KPR=true，无 kube-proxy | Cilium 套接字 LB + 逐包 LB | Cilium | 希望 Service、策略、观测在同一数据面 |

第三种才使用第六章前三节的全部机制。第一种不需要读 Cilium 的 map。第二种要记住 3.4 的分工，排错时两套工具都要会。

## 6.6 本章小结

东西向 ClusterIP 在 `connect()` 里改成后端地址，应用仍通过反向映射看到 ClusterIP，策略和抓包看到的是 Pod IP，这一跳不再占用 NAT conntrack。南北向和没有套接字的流量在 TC 或 XDP 上逐包选择后端，可以用 SNAT、DSR 和 Maglev。Helm 默认不替换 kube-proxy；替换必须显式打开，并告诉 agent apiserver 的地址。nftables 是 kube-proxy 家族里的哈希 NAT，和 Cilium 的套接字改写不是同一步。下一章把观测、加密和集群入口接到这条已经选定的数据面上。

---

# 七、可观测、加密、入口与多集群

## 7.1 Hubble：同一条路径上的流，而不是事后抓包

Hubble server 嵌在 cilium-agent 里，读取 BPF 已经在转发时发出的事件：谁（身份和标签）、连谁、哪一个端口、判决是转发还是丢弃、丢弃原因、是否是回复方向。L7 代理参与过的流还可以带上 DNS 或 HTTP 的摘要。

这些事件先留在本节点。`hubble observe` 可以直接看本机；要看全集群，就经过 hubble-relay。hubble-ui 用 relay 的数据画服务依赖。Prometheus 指标从同一套观测里导出，用来做「丢弃速率」「某身份的连接数」，而不是用来替代流日志。

它和 tcpdump 的分工：tcpdump 看的是某一块网卡上的报文，能看到封装前后的字节；Hubble 看的是数据面已经做出的判决，能对上身份和策略。套接字 LB 之后，在 Pod 里抓包会看到后端地址，在 Hubble 里应能同时看到 Service 语义和这个后端。两边不一致时，先确认看的是不是同一段路径（套接字改写之前还是之后）。

1.20 给 `hubble observe` 增加了按回复方向过滤的开关，并把策略审计与隐式拒绝关联起来。排错「为什么是 DENIED」时，优先看身份和策略名，而不是只看 IP。

## 7.2 加密：在节点之间，不在应用里

WireGuard 与 IPsec 都是把节点到节点的流量加密，Pod 里的进程不持有密钥、也不改代码。

| | WireGuard | IPsec |
|---|---|---|
| 内核机制 | WireGuard 接口 | xfrm，加密标记打在包上，由内核变换 |
| 运维手感 | 密钥和 peer 较直观 | 状态多，和策略路由、MTU 的交互更细 |
| 在 Cilium 里的位置 | 节点间加密的常用选择 | 数据面文档里那条「L3 Encryption」对象：入向解密后再交给后续 BPF，出向按目的 IP 选双方都有的密钥 |

加密和 NetworkPolicy 是两层。加密保证链路上的字节不被旁路读取；策略决定这个身份能不能连那个身份。只开加密、不写策略，集群内仍是默认可互通，只是互通的那段隧道或路由是密文。

1.20 废弃的是 Beta 互认证（工作负载之间用身份做双向 TLS 的那条实验路径），不是废弃 WireGuard / IPsec。新集群的「节点之间保密」选后两者。

## 7.3 Gateway API 与 Ingress

北向 HTTP 入口在 Cilium 里由每节点 Envoy 实现。Ingress 资源仍能用。新的能力写在 Gateway API 上：1.20 跟随到 Gateway API 1.6 一带，包括 TCPRoute、UDPRoute、ListenerSet（一个 Gateway 上由不同命名空间挂载大量监听器）、HTTPRoute 的 ExternalAuth、BackendTLSPolicy，以及若干重定向状态码。仓库 README 上的符合性标记也指向 Gateway API v1.6。

请求的路径是：

```
集群外客户端
    → 云 LB 或宣告出来的 VIP
    → 节点上的 Envoy（Gateway 监听器）
    → 选中的 Service
    → 第六章的 LB，到达 Pod
```

Gateway 不做 Pod 网段路由，也不替换 NetworkPolicy。它决定「哪一个 Host / 路径到哪一个 Service」。Service 到 Pod 仍是数据面负载均衡。

## 7.4 Cluster Mesh

多个 Kubernetes 集群各自运行 Cilium 时，Cluster Mesh 把它们的身份和 Service 发现接起来：一个集群里的 Pod 可以按全局服务名访问另一个集群的后端，策略仍按身份匹配，而不是按「对端集群的 Pod CIDR」去写一长串规则。1.20 把 Multi-Cluster Services API 的支持标为稳定，实现改用该 API 的 v1beta1 CRD。

身份编号里的集群 ID 位（5.1）就是为这件事留的。mesh 越大，能同时接入的集群数和每个集群能分配的身份数要一起规划，`max-connected-clusters` 调的就是这对比特。

没有多集群需求时，不必安装 clustermesh-apiserver。它不是单集群转发的前提。

## 7.5 边界：什么不属于这份数据面

- **Tetragon** 观察进程与系统调用，用来做运行时安全。它和 `bpf_lxc` 不共享策略表。
- **kube-proxy nftables** 是另一条 Service 实现，见 6.5。
- **云厂商的负载均衡器** 把流量送到节点或直接送到 Pod（取决于 CNI 与云的组合）。送到节点之后的 NodePort / host 接收，才进入 Cilium 的逐包 LB。
- **应用层的 Spring Cloud LoadBalancer、Feign、服务注册中心** 若仍在 Pod 里做客户端负载均衡，流量会直接发往 Pod IP 或发往它们自己的注册地址，根本不经过 ClusterIP，Cilium 的 Service map 不会参与选择。迁到 Kubernetes 之后，常见做法是让客户端只访问 Service 名字，把选择权交给本章的数据面。这和 [`k8s介绍.md`](./k8s介绍.md) 第十一章的迁移对应关系是同一件事。

## 7.6 本章小结

Hubble 把身份、判决和丢包原因从 BPF 事件里取出来，relay 负责聚合成集群视图。加密在节点之间完成。Gateway API 用每节点 Envoy 做北向路由，背后仍是 Service。Cluster Mesh 把身份模型延伸到多个集群。这些都依赖前六章的数据面，都不替代它。下一章把一次真实调用从 Pod 创建串到策略拒绝。

---

# 八、贯通视图：同一组地址，iptables 与 Cilium 各走一遍

地址与 [`k8s介绍.md`](./k8s介绍.md) 对齐，方便两边一起看：

| 对象 | 地址 |
|---|---|
| Service 网段 | 10.96.0.0/12 |
| user-service ClusterIP | 10.96.124.37:80，目标端口 8080 |
| order Pod | 节点 A，10.244.1.2 |
| user Pod | 节点 B，10.244.2.5 |
| 节点 | A = 10.0.0.11，B = 10.0.0.12 |
| NodePort | 30080 |

## 8.1 时间线一：user Pod 被调度到节点 B

**iptables 时代（Flannel 或 Calico + kube-proxy）**

1. 调度器绑定 Pod。
2. kubelet 建网络命名空间，CNI 从节点 B 的 Pod CIDR 取出 10.244.2.5，接上网桥或路由。
3. kube-proxy 收到 EndpointSlice 更新，在 **每个节点** 的 `KUBE-SVC-*` 链上增加（或按权重调整）一条通往 10.244.2.5:8080 的 DNAT。节点 A 上的规则也要变，尽管新 Pod 不在 A。
4. 若有按 IP 编写的 NetworkPolicy 实现，节点上的 filter 规则把 10.244.2.5 写进去。

**Cilium**

1. 调度器绑定 Pod。
2. cilium-cni 让节点 B 的 agent 取出 10.244.2.5，创建 veth 或 netkit，加载 `bpf_lxc`。
3. 标签算出身份，例如 1944（若已有同样标签的 user Pod，就复用 1944）。ipcache 增加 `10.244.2.5 → 1944`，这条映射传播到节点 A。
4. Service map 的后端列表增加 10.244.2.5:8080。这是 LB 表的增量，不是每个节点重放一份 iptables 文本。
5. 策略 map 的键仍是身份 1944。若没有新标签，策略内容不变。

滚动更新时，两边的差异会放大：iptables 侧是「删旧 IP 的规则、加新 IP 的规则」；Cilium 侧是「ipcache 与后端列表替换 IP，身份号不动」。

## 8.2 时间线二：order 调用 user-service

**iptables kube-proxy，隧道 CNI**

```
order 进程 connect(10.96.124.37:80)
    → 报文目的仍是 ClusterIP，从 Pod 进入节点 A
    → nat OUTPUT/PREROUTING 命中 KUBE-SERVICES
    → KUBE-SVC 按概率选中 KUBE-SEP，DNAT 成 10.244.2.5:8080
    → conntrack 记下这条 NAT
    → CNI 封装（例如 VXLAN），外层 10.0.0.11 → 10.0.0.12
    → 节点 B 解开，filter 策略按 IP 判断，送给 10.244.2.5
    → 回程由 conntrack 把源地址改回 10.96.124.37
```

在节点 A 上抓包，DNAT 前能看到 ClusterIP，DNAT 后看到 Pod IP。ping 10.96.124.37 不会进这条只匹配 TCP 80 的规则。

**Cilium，KPR=true，套接字 LB，隧道或原生路由只影响中段**

```
order 进程 connect(10.96.124.37:80)
    → cgroup BPF 查 Service map，套接字目的改为 10.244.2.5:8080
    → getpeername 仍返回 10.96.124.37（反向映射）
    → 报文从一开始就是 10.244.1.2 → 10.244.2.5:8080
    → 节点 A 的 bpf_lxc 用身份做出向策略
    → 隧道则封装并带上身份；原生路由则直接路由，身份在 mark 或由对端查 ipcache
    → 节点 B 的 bpf_lxc 用身份 做入向策略，送给 user Pod
    → 回程是普通的 Pod 回 Pod，没有 ClusterIP 的 NAT 记录
```

在 order Pod 里抓包，目的地址已经是 10.244.2.5。若此时仍去节点 A 的 `iptables -t nat -L` 找 `KUBE-SEP`，找不到是预期：这条路径没有那些链。

**Cilium，KPR=false**

集群内 ClusterIP 仍可能在 Cilium 的逐包路径上被改写；`30080` 这种 NodePort 要到 kube-proxy 的规则里找。先看 `cilium-dbg status` 里的 KubeProxyReplacement，再决定抓 iptables、nft 规则，还是抓 BPF map。

## 8.3 时间线三：一条「只允许 order 访问 user」的策略生效

策略意图：命名空间里，标签 `app=order` 的 Pod 可以访问 `app=user` 的 TCP 8080，其他人不行。

**按 IP 的实现**

每个 order Pod IP 与每个 user Pod IP 的组合都要出现在规则里。任意一边滚动，规则更新的规模是「相关 Pod 数 × 节点数」。DNAT 与 filter 的相对顺序还必须和作者的心智一致。

**Cilium**

1. 两个标签组对应两个身份，例如 order=26831，user=1944。
2. user 端点的入向 policy map 写入：允许身份 26831 访问 TCP 8080。
3. order 端点的出向 map 写入：允许访问身份 1944 的 TCP 8080。
4. 新的 order Pod 只要标签不变，身份仍是 26831，第 2 步的那条不用改。ipcache 多一条新 IP。
5. 别的 Pod 访问 user 时，接收端查 map 未命中允许项（一旦已有策略选中 user 端点，默认从「全放行」变成「默认拒绝」），报文丢弃。Hubble 里看到源身份、目的身份和 drop reason。

FQDN 或 HTTP 路径若也写在这条策略里，被选中的流会多一跳本节点 Envoy，判决仍然以这两个身份为上下文。身份号不会因为经过 Envoy 就换成「世界」。

## 8.4 把三条线叠在一张表里

| 问题 | 在 iptables 数据面里看哪里 | 在 Cilium（KPR=true）里看哪里 |
|---|---|---|
| Pod IP 是谁分的 | CNI 与节点 PodCIDR | agent / operator 的 IPAM，`cilium-dbg endpoint list` |
| 这次调用选中了哪个后端 | conntrack 的 DNAT 目标，或 ipvs 连接 | 套接字已连接的对端；`cilium-dbg bpf lb list` 看候选集 |
| 为什么被拒绝 | filter 计数器、策略控制器日志 | Hubble 的 DROPPED 与身份；`cilium-dbg policy get` |
| 集群外回来的包源地址像节点 IP | POSTROUTING MASQUERADE | 同样可能是 iptables masquerade，或 BPF NAT map |
| Service 规则是不是这个节点写的 | 每个节点一份 kube-proxy 规则 | 每个节点一份 BPF map，由本机 agent 按 watch 到的同一份 API 对象写成 |

## 8.5 本章小结

Pod 创建在 Cilium 里多出来的关键步骤是「标签 → 身份 → ipcache」，而不只是「分配 IP」。ClusterIP 调用在套接字 LB 下从 `connect()` 起就是 Pod IP，策略和抓包都要按这个事实读。策略生效改的是身份到身份的 map，滚动更新不改这张 map 的键。排错时先确认 KPR 开关，再选择 iptables、nft 或 `cilium-dbg`。

---

# 九、附录

## 9.1 和本目录文档一起读的顺序

1. [`k8s介绍.md`](./k8s介绍.md) 第二、四、六章：控制面、kube-proxy 的位置、三张网段。
2. [`iptables.md`](./iptables.md) 全文：五钩子和 kube-proxy 那组 `KUBE-*` 链。没有这张图，第二章的「链表与 DNAT」没有着落。
3. 本文第一、二章：为什么还会有 ipvs、nftables 和 eBPF。
4. 本文第五、六、八章：身份、Service、一次调用。
5. 第三、四、七章在要改安装方式、排封装问题或打开 Hubble 时再读。

## 9.2 选型

| 你的主要矛盾 | 更匹配的做法 |
|---|---|
| Service 很多，iptables 刷新和每包扫描已经能测出来，策略维持现状 | kube-proxy 切到 nftables（内核 ≥ 5.13），CNI 先不动 |
| 需要 NetworkPolicy，网络可以跑 BGP 或隧道，Service 规模中等 | Cilium 或具备策略的 CNI，KPR 先保持 false，kube-proxy 保留 |
| 希望去掉逐包 NAT、用身份做策略、用 Hubble 看丢包 | Cilium 且 `kubeProxyReplacement=true`，不部署 kube-proxy |
| 必须保留 sidecar 来劫持 ClusterIP | Cilium 可以，但打开 `socketLB.hostNamespaceOnly=true`，并按后端身份改写出向策略 |
| 只想要一个简单隧道、没有策略 | Flannel 一类插件仍说得通。Cilium 的收益在策略、Service 和观测，不在「能通」本身 |

默认不要同时让 kube-proxy 和 KPR=true 的 Cilium 处理同一批 NodePort。

## 9.3 排错时先跑的命令

在 agent 所在的 DaemonSet 上执行（容器名若不是默认的，用 `kubectl -n kube-system exec ds/cilium -c cilium-agent -- …`）：

```bash
# KPR、套接字 LB、设备、伪装模式、各类 Service 是否接管
kubectl -n kube-system exec ds/cilium -- cilium-dbg status --verbose

# 本节点端点：Pod IP、身份、状态
kubectl -n kube-system exec ds/cilium -- cilium-dbg endpoint list

# 身份号对应哪些标签
kubectl -n kube-system exec ds/cilium -- cilium-dbg identity list

# Service 与后端
kubectl -n kube-system exec ds/cilium -- cilium-dbg service list

# BPF 里的负载均衡表
kubectl -n kube-system exec ds/cilium -- cilium-dbg bpf lb list
```

Hubble 若已开启：

```bash
# 看某个 Pod 的转发与丢弃。安装方式不同，CLI 可能在本地，也可能要进 hubble-relay
hubble observe --pod <namespace>/<name> --verdict DROPPED
```

对照 kube-proxy 是否仍在场：

```bash
# iptables 模式
iptables -t nat -S | head
# nftables 模式
nft list ruleset
# ipvs 模式
ipvsadm -Ln
ip addr show kube-ipvs0
```

读输出的顺序建议固定成四步：KPR 是 true 还是 false；端点身份是不是还停在 init（5）；Service 的后端列表里有没有期望的 Pod IP；Hubble 的丢弃是策略、还是路由/封装。

## 9.4 术语速查

| 术语 | 在本文里的意思 |
|---|---|
| CNI | kubelet 调用的插件接口。cilium-cni 是入口，agent 才是实现 |
| kube-proxy | Kubernetes 自带的 service proxy。四种历史 mode：userspace（已删除）、iptables、ipvs、nftables |
| KPR | `kubeProxyReplacement`。true 表示由 Cilium 接管 Service 数据面 |
| 套接字 LB | 在 connect/sendmsg 改目的地址的 eBPF |
| 逐包 LB | 在 TC/XDP 上对每个包查 Service map |
| 身份 | 标签组的数字编号，策略的键 |
| ipcache | IP 到身份（以及隧道终点等信息）的映射 |
| veth / netkit | 把 Pod 网络命名空间接到主机的设备 |
| cilium_host | 节点在 Pod 网络里的主机侧设备 |
| DSR | 响应不折返负载均衡节点 |
| Maglev | 一致哈希的后端选择 |
| Hubble | 读 BPF 事件的观测组件 |
| operator | 集群级控制面，不转发报文 |

## 9.5 源码与文档索引

| 主题 | 位置（Cilium v1.20.3，除非另注） |
|---|---|
| 项目自我介绍、隧道与原生路由、套接字 LB、Cluster Mesh | 仓库 `README.rst` |
| 预留身份编号 | `bpf/lib/identity.h` 的 `enum identity` |
| 身份作用域、256 与 128 的下界 | `pkg/identity/numericidentity.go` |
| 容器侧、主机侧、隧道、XDP、套接字程序 | `bpf/bpf_lxc.c`、`bpf/bpf_host.c`、`bpf/bpf_overlay.c`、`bpf/bpf_xdp.c`、`bpf/bpf_sock.c`，公共逻辑在 `bpf/lib/` |
| 组件分工 | 官方文档 *Component Overview* |
| 钩子说明 | 官方文档 *eBPF Datapath Introduction* |
| 不部署 kube-proxy 的安装参数 | 官方文档 *Kubernetes Without kube-proxy* |
| KPR 布尔值 | 1.14 发布说明（弃用 partial/strict/probe/disabled） |
| kube-proxy nftables | Kubernetes KEP-3866；1.29 alpha，1.31 beta，1.33 GA |
| iptables 链的现场 | 本目录 [`iptables.md`](./iptables.md) |

升级 1.20 时，官方说明要求额外检查的遗留项包括：Beta 互认证、Kafka 感知策略、`cilium.io/v2alpha1` 的 `CiliumNodeConfig`、libnetwork 集成、自定义 CNI 配置。这些不影响「身份 + 套接字 LB」这条主线，但会影响从 1.19 往上翻的集群。
