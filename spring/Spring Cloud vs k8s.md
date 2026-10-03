# Spring Cloud vs Kubernetes：能力重叠深度对比与选型指南

> **面向场景**：SpringBoot 4.1.1 + JDK 25 + Oracle 19c + 3 微服务架构；团队评估哪些能力交给 Spring Cloud 代码实现、哪些能力下沉到 K8s 平台。
>
> **核心观点**：两者不是"二选一"关系，而是**分层治理 + 平台与业务解耦**的组合关系。Spring Cloud 治理「应用层语义」，K8s / Service Mesh 治理「平台层流量」。

---

## 目录

1. [能力重叠总览对比表](#1-能力重叠总览对比表)
2. [十大重叠能力逐一深度对比](#2-十大重叠能力逐一深度对比)
   - 2.1 服务发现与注册（Eureka / K8s Discovery vs K8s Service + CoreDNS）
   - 2.2 客户端负载均衡（Spring Cloud LB / Ribbon vs kube-proxy iptables/ipvs）
   - 2.3 API 网关（Spring Cloud Gateway vs Ingress / Gateway API / Istio Gateway）
   - 2.4 配置中心（Spring Cloud Config vs ConfigMap + Secret + SealedSecret）
   - 2.5 熔断限流与容错（Resilience4j/Sentinel vs Istio 流量管理 + K8s 资源限制）
   - 2.6 链路追踪（Micrometer Tracing / Sleuth vs Istio + Jaeger Sidecar）
   - 2.7 调用重试与超时（Spring Retry + Resilience4j vs Istio VirtualService / 超时/重试）
   - 2.8 安全鉴权（Spring Cloud Security + OAuth2 vs K8s RBAC + Istio AuthZ）
   - 2.9 灰度发布与流量染色（SC LoadBalancer 自定义/ Polaris vs Istio/K8s Gateway API Canary）
   - 2.10 服务间通信加密 mTLS（Spring SSL Bundle vs Istio Sidecar 透明 mTLS）
3. [四种组合部署模式选型矩阵](#3-四种组合部署模式选型矩阵)
4. [针对本项目（3 微服务 + JDK25 + SB4.1）的最终建议](#4-针对本项目3-微服务--jdk25--sb41的最终建议)
5. [迁移路线图：Spring Cloud 自研 → K8s 平台下沉](#5-迁移路线图spring-cloud-自研--k8s-平台下沉)

---

## 1. 能力重叠总览对比表

| # | 能力域 | Spring Cloud 实现 | K8s 原生实现 | K8s 进阶（Service Mesh / CRD） | 重叠度 | 推荐归属层 |
|---|--------|------------------|-------------|-------------------------------|--------|-----------|
| 1 | 服务发现/注册 | Eureka / Consul / Nacos / SC Kubernetes Discovery | K8s Service + CoreDNS + EndpointSlice | - | 90% | **K8s 平台** |
| 2 | 客户端负载均衡 | SC LoadBalancer（Reactor LB）/ Ribbon（停更） | kube-proxy（iptables / ipvs 四层 LB） | Cilium（eBPF LB） | 70% | **混合**：平台做 L4/L7 LB，SC 做带业务权重 |
| 3 | API 网关 | Spring Cloud Gateway（WebFlux，SCG） | Ingress（Nginx/Trafik 实现） | Gateway API CRD + Istio Gateway + Envoy | 85% | **边界用 SCG（业务网关）+ 平台用 Ingress/Gateway API（集群入口）** |
| 4 | 配置/密钥管理 | SC Config（Git/Vault/JDBC） + Spring Cloud Bus | ConfigMap / Secret（base64 非加密） | SealedSecret / ExternalSecrets / Vault Agent Injector | 80% | **敏感信息 K8s（Vault 注入），应用配置 SC Config + CM 混用** |
| 5 | 熔断/限流/舱壁 | Resilience4j / Sentinel / Hystrix（停更） | LimitRange / ResourceQuota（资源级粗粒度） | Istio DestinationRule + CircuitBreaker + 全局限流（Envoy RLS） | 60% | **混合：业务语义熔断（SC），平台级限流（Mesh）** |
| 6 | 分布式链路追踪 | Micrometer Tracing + OTel（Sleuth 已停更） | K8s 无原生（仅 Kubelet 指标） | Istio Sidecar + OTel + Jaeger/Tempo + Service Graph | 85% | **混合：SC 做应用层 Span，Mesh 做 L4/L7 流量 Span** |
| 7 | 调用重试 & 超时 | Spring Retry + Resilience4j TimeLimiter + HTTP Client 超时 | K8s Readiness/Liveness（健康检查层面） | Istio VirtualService retries + timeout | 75% | **SC 做业务级幂等重试，Mesh 做网络级超时/重试** |
| 8 | 安全认证/鉴权 | SC Security + OAuth2 AuthorizationServer + JWT + ResourceServer | K8s RBAC + ServiceAccount + OIDC Provider | Istio RequestAuthentication + AuthorizationPolicy + PeerAuthentication | 50% | **SC 做用户/业务级 RBAC，K8s/Mesh 做服务间 0 信任** |
| 9 | 灰度/金丝雀发布 | SC LoadBalancer + 自定义灰度策略（Meta 路由） / Tencent Polaris / 阿里 Sentinel 灰度 | K8s Deployment RollingUpdate（仅版本百分比，无请求维度） | Istio DestinationRule subsets + VirtualService 权重 + Gateway API HTTPRoute 头部匹配 | 70% | **平台层做灰度（推荐），SC 仅做细粒度业务灰度（如用户 ID 切流）** |
| 10 | 服务间通信加密（mTLS） | Spring SSL Bundle + 手动证书管理 + JKS/P12 | K8s 无原生（TLS Ingress 只是边缘） | Istio PeerAuthentication PERMISSIVE/STRICT + Sidecar 自动 Cert Rotate | 95% | **必须 Istio mTLS（Spring 手动完全不可维护）** |

> **重叠度图例**：≥90% = 几乎完全重合，一个能做的另一个也能做；60~90% = 部分重合，侧重不同；<60% = 层次不同，互补关系

---

## 2. 十大重叠能力逐一深度对比

---

### 2.1 服务发现与注册（Spring Cloud vs K8s Service + CoreDNS）

#### 🅐 Spring Cloud 实现架构（以 Nacos / Eureka 为例）

```
┌──────────────────────────────────────────────────────────────────┐
│  注册中心集群（Nacos 3 节点 / Eureka 3 节点 PeerAware）           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                       │
│  │  Nacos-1 │◄─┤  Nacos-2 ├─►│  Nacos-3 │  内部 RAFT/AP 同步    │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘                       │
└───────┼─────────────┼─────────────┼──────────────────────────────┘
        │  REGISTER/HEARTBEAT(30s) │  PULL 实例列表
        ▼                          ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  Service A Pod 1     Service A Pod 2     Service B Pod 1     │
  │  (SC Nacos Client)   (SC Nacos Client)   (SC LB + Feign)     │
  │  ┌────────────┐     ┌────────────┐     ┌──────────────────┐  │
  │  │启动时 REGISTER     REGISTER(IP:port+元数据)  NamingService│  │
  │  │定时心跳续约(5s)   心跳续约(5s)   查询 ServiceA 实例列表  │  │
  │  │Down 后 30s 剔除  Down 后 30s 剔除  本地缓存 30s 刷新     │  │
  │  └────────────┘     └────────────┘     └──────────────────┘  │
  └──────────────────────────────────────────────────────────────┘
```

**架构要点**：
- **数据面 + 控制面不分离**：每个应用 Pod 内嵌 `DiscoveryClient`，直接与注册中心 REST/gRPC 通信
- **AP 优先**：Eureka（纯 AP）/ Nacos（AP+CP 可切换），网络分区时宁可接受脏读也不阻塞注册
- **元数据丰富**：支持 `metadata.version=gray` / `metadata.region=cn-shanghai-1` 自定义标签，用于灰度路由
- **健康检查依赖客户端心跳**：应用 GC STW 导致心跳丢 → 被误摘除

#### 🅑 Kubernetes 原生实现架构

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Control Plane (kube-apiserver)                                          │
│    │ ▲ 写入 Service + EndpointSlice（Pod IP 动态变化由 kube-controller    │
│    │ │ 联动 EndpointSliceController 持续同步）                            │
│    ▼ │                                                                    │
│  ┌──────────────────────────┐   ┌──────────────────────────┐             │
│  │      CoreDNS (DaemonSet) │   │  kube-proxy (DaemonSet)   │             │
│  │   监听 Service + ESlice  │   │  监听 Service + ESlice    │             │
│  │   生成 A/SRV 记录        │   │  iptables / ipvs 规则     │             │
│  └──────┬───────────────────┘   └──────┬───────────────────┘             │
└─────────┼──────────────────────────────┼──────────────────────────────────┘
          │ DNS 查询 A 记录                │ TCP/IP 层包改写
          ▼                                ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  Service A Pod 1      Service A Pod 2      Service B Pod 1   │
  │  (普通应用，无注册逻辑)  (普通应用，无注册逻辑)  (无 SC LB)     │
  │  POST /login              GET /user              curl http://│
  │  :8080                    :8080                 service-a/api │
  │                                                         ↓ DNS │
  │                                                  CoreDNS 返回│
  │                                                  ClusterIP    │
  │                                                         ↓ 改写│
  │                                                  kube-proxy   │
  │                                                  RR 到 Pod1/2 │
  └──────────────────────────────────────────────────────────────┘
```

**架构要点**：
- **完全无侵入**：应用代码零依赖；Pod 启动被 K8s 分配 IP 后自动注册到 EndpointSlice
- **CP 优先（强一致）**：Service/EndpointSlice 存储在 etcd，Raft 共识；网络分区时不会有脏实例
- **健康检查依赖 Kubelet**：ReadinessProbe（HTTP/TCP/Exec）失败 → 自动从 EndpointSlice 摘除；与应用 GC 无关
- **元数据贫乏**：Service 只有 ports/selector，**无自定义标签**（无法按 `version=gray` 切流）

#### 🅒 对比与选型

| 维度 | Spring Cloud (Nacos/Eureka) | K8s Service + CoreDNS | 胜者 & 理由 |
|------|----------------------------|----------------------|------------|
| **代码侵入** | 必须引入 `spring-cloud-starter-{nacos,eureka,kubernetes}` + 注解 | ✅ **零侵入**，普通 Service 即可 | K8s |
| **组件运维成本** | 需要 3 节点高可用注册中心集群 + 监控 | ✅ **K8s 自带**，CoreDNS + kube-proxy 系统组件 | K8s |
| **元数据/标签路由能力** | ✅ 自定义 `metadata.*`，支持版本/灰度/机房亲和性 | ❌ Service 只有 selector，灰度需借助 Istio subset | Spring Cloud |
| **摘除时效性** | 心跳 5s + 剔除延迟 30s → **35s 以上** | ✅ Readiness 失败 → EndpointSlice **1s 内** 摘除 → kube-proxy 5s 内下发规则 | K8s |
| **跨语言/异构服务** | ❌ 仅限 Java（或有对应语言 SDK 的 Nacos 等） | ✅ **所有语言**通用（Sidecar/无 SDK） | K8s |
| **服务数量扩展性** | ≥ 5000 实例时 Nacos/Eureka 心跳 CPU 飙升 | ✅ 线性扩展（EndpointSlice 分片 + EndpointSliceProxying feature） | K8s |
| **跨命名空间/跨集群发现** | 需要注册中心联邦（Nacos 命名空间/集群分组） | ✅ CoreDNS `*.ns.svc.cluster.local` / K8s Multi-Cluster Services | K8s |

**选型结论（三服务规模）**：
```
✅ 推荐：Spring Cloud Kubernetes Discovery（最佳平衡点）
   配置：spring.cloud.kubernetes.discovery.enabled=true
   原理：K8s Service 注册是主路径，SC Discovery Client 仅从 K8s API 拉取 Pod Labels
   → 优点：零注册中心运维 + 同时获得 Pod Labels 作为 metadata（version/gray）用于 LB 灰度
❌ 不推荐：独立 Nacos/Eureka（纯冗余，3 服务规模无收益，徒增运维）
```

---

### 2.2 客户端负载均衡（Spring Cloud LB vs kube-proxy iptables/ipvs）

#### 🅐 Spring Cloud LoadBalancer 架构（替代已停更的 Ribbon）

```
调用方（ServiceB Feign/RestClient）
      │
      ▼
┌──────────────────────────────────────────────────┐
│  Spring Cloud LoadBalancer (Reactor 实现)         │
│  ┌─────────────────────────────────────────────┐ │
│  │ 1. ServiceInstanceListSupplier               │ │
│  │    - 从 Nacos/Eureka/K8s Discovery 取实例列表 │ │
│  │    - 支持 ZonePreference（同机房优先）         │ │
│  │    - 支持 HintBased（按 metadata.gray=xxx）   │ │
│  └─────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────┐ │
│  │ 2. ReactorLoadBalancer 策略                   │ │
│  │    - RoundRobin（默认，原子计数器）            │ │
│  │    - Random                                  │ │
│  │    - 自定义：WeightedResponseTime（权重）      │ │
│  │    - 自定义：Nacos NacosBalancer（按权重）    │ │
│  └─────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────┐ │
│  │ 3. 健康检查子系统（Spring Cloud LB 默认关闭！）│ │
│  │    - 需显式开：HealthCheckServiceInstanceList │ │
│  │    - 以 25s 周期对每个实例 ping /actuator/health│ │
│  │    - 失败 3 次剔除 → 但恢复需要 3 次成功       │ │
│  └─────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
      │
      ▼ 选中实例后，Feign/RestClient 直接调用 Pod IP:Port
```

#### 🅑 kube-proxy iptables vs ipvs 架构

```
Service B Pod
curl http://service-a.default.svc.cluster.local/api
        │
        │ ① DNS: 10.96.0.10 (CoreDNS) → 返回 ClusterIP 10.96.1.5
        ▼
        ② 出 Pod 前命中 netfilter 内核钩子
┌─────────────────────────────────────────────────────────────┐
│  Node 1 Netfilter 内核（kube-proxy 写规则）                   │
│                                                             │
│  ┌──────── 模式 A：iptables（O(n) 规则匹配，n = Service 数） ┐│
│  │ PREROUTING → KUBE-SERVICES → KUBE-SVC-XXXXXXXX           ││
│  │   → 随机概率 iptables -m statistic --mode random         ││
│  │     --probability 0.5 → DNAT → 10.244.1.2:8080          ││
│  │     --probability 1.0 → DNAT → 10.244.2.3:8080          ││
│  └──────────────────────────────────────────────────────────┘│
│                                                             │
│  ┌──────── 模式 B：ipvs（O(1) 哈希匹配，≥1000 Service 推荐） ┐│
│  │ ip_vs conn 调度算法：rr / wrr / lc / wlc / sh / dh...   ││
│  │ Virtual Server 10.96.1.5:8080                            ││
│  │   ├─ Real Server 10.244.1.2:8080  Masq                  ││
│  │   └─ Real Server 10.244.2.3:8080  Masq                  ││
│  │ 内核直接转发，无需遍历所有规则                             ││
│  └──────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
        │
        ▼ ③ 直接转发到 Service A Pod（同一 Node 走 veth，跨 Node 走 VXLAN/Calico）
```

#### 🅒 对比与选型

| 维度 | Spring Cloud LoadBalancer（应用层 L7） | kube-proxy iptables / ipvs（内核层 L4） | 胜者 & 理由 |
|------|--------------------------------------|----------------------------------------|------------|
| **负载均衡层** | L7（HTTP/HTTPS/Header/Cookie 可识别） | L4（TCP/UDP，只看 IP:Port） | 层次不同，互补 |
| **匹配策略精细度** | ✅ 按 Header/Cookie/Metadata/版本 灰度路由；按响应时间加权 | ❌ 仅 RR/Random（iptables 随机算法不是严格 RR）| SC |
| **性能** | Java 线程处理，单机 1~2 万 QPS | ✅ **内核态零拷贝**，单机 10~50 万 QPS，CPU 开销 <1% | K8s |
| **健康检查** | 默认关闭；开启后 25s 周期，额外 HTTP 开销 | ✅ 与 EndpointSlice + Readiness 深度联动，**秒级剔除** | K8s |
| **跨语言支持** | ❌ 仅 Java（其他语言要各自实现 SDK） | ✅ **全语言通用** | K8s |
| **会话保持（Sticky）** | SC 需要 `LoadBalancerUri` 自定义 + Cookie 存储 | ✅ ipvs sh/dh 算法 + Service `sessionAffinity: ClientIP` | 平手 |
| **故障隔离（熔断）** | ✅ HealthCheckServiceInstanceList 自动剔除坏实例 | ❌ 没有熔断，只依赖 Readiness；下游 5xx 不会自动剔除 | SC |
| **可观测性** | `spring.cloud.loadbalancer.*` Micrometer 指标（实例选中分布/健康检查失败） | ✅ kube-proxy sync_proxy_rules + ip_vs stats（Node-exporter） | K8s |

**选型结论（三服务规模）**：
```
✅ 推荐组合：
  ① 平台 L4 LB → kube-proxy ipvs 模式（全局高吞吐基础转发）
  ② 应用层 L7 精细路由 → SC LoadBalancer + k8s-discovery（读取 Pod label 做灰度/权重）
  ③ 关闭 SC LB HealthCheck → 完全信任 K8s Readiness（避免冗余心跳）
配置：
  spring.cloud.loadbalancer.health-check.enabled = false
  spring.cloud.loadbalancer.cache.ttl = 15s  → 折中：避免频繁请求 K8s API
```

---

### 2.3 API 网关（Spring Cloud Gateway vs K8s Ingress / Gateway API）

#### 🅐 Spring Cloud Gateway (SCG) 架构

```
                    ┌─────────────────────────────────────────────┐
                    │  Client（浏览器/移动端/OpenAPI）            │
                    └──────────────────┬──────────────────────────┘
                                       │ HTTPS + JWT
                                       ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Spring Cloud Gateway（WebFlux + Netty NIO，单 Pod 2~5 万 QPS）             │
│                                                                           │
│  Route Predicate Factory（匹配请求）：                                      │
│   - Path=/api/a/**  → Service A                                           │
│   - Header=x-gray-tag=v2  → Service A 灰度子集（SC LB metadata.gray=v2）  │
│   - After(T1)/Between(T1,T2)/Cookie/Host/Method/Query/RemoteAddr          │
│                                                                           │
│  GatewayFilter Factory（请求/响应处理链，Reactor 链）：                      │
│   ┌─ 全局过滤器：GlobalFilter（Trace Filter、RateLimiter、AccessLog）       │
│   ├─ 1. Token Relay（把 OAuth2 AccessToken 透传给下游）                     │
│   ├─ 2. RequestRateLimiter（Redis/Sentinel/令牌桶/滑动窗口）                │
│   ├─ 3. StripPrefix / PrefixPath / RewritePath / SetPath                   │
│   ├─ 4. AddRequestHeader / RemoveHopByHopHeaders / Baggage 透传            │
│   ├─ 5. Retry（基于 Resilience4j + HTTP 状态码/异常）                       │
│   ├─ 6. CircuitBreaker（R4j / Sentinel，下游慢/坏时 fallback）              │
│   └─ 7. ModifyRequestBody / ModifyResponseBody（JSON 加解密、脱敏）         │
│                                                                           │
│  下游路由：Netty Routing Filter → lb://service-a → SC LoadBalancer → Pod IP│
│                                                                           │
│  存储配置：                                                                │
│   - 内存式（application.yml routes 节点 + Spring Cloud Bus 动态刷新）        │
│   - Nacos Config / Redis JDBC 存储动态路由（走 RouteDefinitionRepository）   │
└────────────────────────────────────────────────────────────────────────────┘
```

#### 🅑 K8s Ingress / Gateway API + Istio Gateway 架构

```
                    Client
                       │
        ┌──────────────┴──────────────┐
        ▼ 云厂商 LB（四层，SLB/NLB/ELB）
        │
┌───────▼──────────────────────────────────────────────────────────────┐
│  Ingress Controller（DaemonSet/Deployment + hostNetwork）             │
│  （主流实现：Nginx / Traefik / Istio IngressGateway=Envoy / Kong）     │
│                                                                      │
│  ┌───────── 模式 A：Ingress v1（稳定，2015~至今） ──────────────────┐│
│  │ apiVersion: networking.k8s.io/v1                                  ││
│  │ kind: Ingress                                                     ││
│  │ spec.hosts[].http.paths[].path: /api/a                           ││
│  │ backend.service.name: service-a                                   ││
│  │ annotations: 各种 nginx.ingress.kubernetes.io/* hack             ││
│  │ 问题：每家实现 annotation 都不一样，迁移成本高；L7 能力弱          ││
│  └──────────────────────────────────────────────────────────────────┘│
│                                                                      │
│  ┌───────── 模式 B：Gateway API v1（GA 2024 K8s 1.29+）────────────┐│
│  │ 标准化 CRD：GatewayClass / Gateway / HTTPRoute / GRPCRoute /    ││
│  │                  TCPRoute / TLSRoute / ReferenceGrant            ││
│  │ 示例：HTTPRoute 按 header x-gray-tag=v2 把流量 10% 打到 service-a-v2││
│  │   spec.rules[].matches[].headers.name=x-gray-tag value=v2         ││
│  │   → backendRefs[0].name=service-a-v2 weight=10                   ││
│  │   → backendRefs[1].name=service-a-v1 weight=90                   ││
│  │ 优势：标准化 API，支持权重/Header 匹配/故障注入，不再依赖 annotation││
│  └──────────────────────────────────────────────────────────────────┘│
│                                                                      │
│  （Istio Gateway）Envoy 实例：                                        │
│   - TLS Termination（网关证书卸载）                                   │
│   - mTLS 下游（与 Service Mesh Sidecar 握手）                         │
│   - 全局限流 / 熔断 / WAF / 故障注入 / 金丝雀权重                      │
│   - OTel 原生 Span 生成 + 访问日志 = 无需应用埋点就有 L7 观测          │
└──────────────────────────────────────────────────────────────────────┘
```

#### 🅒 对比与选型

| 维度 | Spring Cloud Gateway（业务网关） | K8s Ingress / Gateway API + Istio（集群边缘网关） | 胜者 & 理由 |
|------|--------------------------------|-------------------------------------------------|------------|
| **部署位置** | K8s Service（内部服务）/ 配合 Ingress 当业务网关 | **集群边缘入口**（Node 主机端口 / 云 LB 后端） | 互补，定位不同 |
| **动态配置来源** | Nacos/SC Config + Bus 实时刷新 | K8s API Watch（Informer）→ CRD 变更秒级生效 | K8s（声明式 + GitOps 友好） |
| **业务语义扩展能力** | ✅ Java 代码自定义 Filter/GlobalFilter；可直接调用 Spring Bean（查 DB、注入用户上下文） | ❌ Lua/Nginx 脚本或 Envoy WASM 插件（Go/C++/Rust），难调试 | SC |
| **与 OAuth2/SSO 集成** | ✅ 深度集成 SC Security OAuth2 Client / ResourceServer（Token Relay + 授权码模式） | ✅ Istio + OIDC Provider + RequestAuthentication；或者 Nginx OAuth Proxy | 平手 |
| **性能（纯路由无逻辑）** | 1.5~3 万 QPS / 4C8G Pod，Netty | ✅ **Nginx 8~15 万；Envoy 5~12 万**（C/C++ 内核模式 + 零拷贝） | K8s |
| **GRPC/WebSocket/SSE** | ✅ 支持（WebFlux Netty） | ✅ Gateway API 有 `GRPCRoute`；Nginx annotation 也可 | K8s（标准化） |
| **灰度/金丝雀能力** | ⚠️ 需配合 SC LB 自定义灰度策略（代码写） | ✅ Istio + Gateway API 原生权重/header/cookie/用户维度金丝雀 + 故障注入 | K8s |
| **平台运维** | 每个 SCG 实例自己监控；无统一入口审计 | ✅ **集中式**：IngressGateway 做全站 WAF / DDoS / TLS 证书（cert-manager） | K8s |
| **服务治理平台化** | 网关能力绑定 SC，其他语言/非 Java 服务受限制 | ✅ **跨语言/跨框架通用**：Golang/Node/Python 微服务同样被网关治理 | K8s |

**选型结论（三服务规模）**：
```
✅ 最佳实践：双层网关架构（分层治理）

  Internet → [云 LB / 硬件防火墙] →
    [Istio Gateway + Gateway API]（K8s 边缘网关：TLS、WAF、DDoS、L7 灰度权重、
                                  跨语言通用；负责 80% 流量治理）→
      [Spring Cloud Gateway]（业务网关：JWT 鉴权业务逻辑、用户上下文注入、
                              自定义业务限流规则、动态 JSON 脱敏/加密；
                              负责 20% 业务语义强相关的能力）→ ServiceA/B/C

❌ 避免：
  - 只用 SCG 当边缘入口（失去 L4 抗攻击能力，证书管理混乱）
  - 只用 Ingress（无法做业务语义 Header 自定义注入/数据库联动鉴权）
```

---

### 2.4 配置中心 & 密钥管理（SC Config vs K8s ConfigMap + Secret + 生态）

#### 🅐 Spring Cloud Config 架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  Config Server（@EnableConfigServer）                               │
│                                                                     │
│  配置后端 Backend（择一）：                                           │
│  ├─ Git（默认，最佳实践：每个环境一个分支；Pull Request 走审批流）    │
│  │   - {application}-{profile}.yml / .properties                     │
│  │   - label=prod/release-1.0（支持 Git Tag 版本回滚）               │
│  ├─ Vault：密钥存 HashiCorp Vault，SC Config 代理 Vault API          │
│  ├─ JDBC：存 Oracle/MySQL 表 `PROPERTIES`（简单，不推荐多环境）        │
│  ├─ Native：本地文件 / Classpath                                     │
│  └─ Redis / Nacos Config（阿里生态常用）                             │
│                                                                     │
│  变更通知：                                                          │
│    Git Webhook → Config Server /monitor 端点 →                      │
│      Spring Cloud Bus（RabbitMQ/Kafka Topic `springCloudBus`）→     │
│        所有客户端 RefreshScope 刷新 @Value/@ConfigurationProperties │
│                                                                     │
│  客户端（Spring Cloud Config Client）                               │
│    bootstrap.yml / bootstrap phase 在 Application Context 之前加载 │
│    失败快速：spring.cloud.config.fail-fast=true                     │
│    重试：ConfigServer 不可用用 RetryTemplate 重试 6 次               │
└─────────────────────────────────────────────────────────────────────┘
```

#### 🅑 Kubernetes ConfigMap + Secret 架构 + 生态增强

```
┌─────────────────────────────────────────────────────────────────────┐
│  K8s API Server + etcd                                              │
│  ┌──────────────┐   ┌────────────────────────────────────────────┐ │
│  │  ConfigMap   │   │  Secret（⚠️ 仅 base64 编码，etcd 要加密！） │ │
│  │  - Key/Value │   │  Opaque: password / jdbc-url                │ │
│  │  - application.yml挂载为文件│ │  kubernetes.io/tls: cert.p12/key     │ │
│  └──────┬───────┘   └───────────────────┬────────────────────────┘ │
└─────────┼───────────────────────────────┼──────────────────────────┘
          │                               │
          ├─────────── 注入方式 1：Volume Mount → 热更新（无需重启 Pod）
          │  Pod.spec.containers[].volumeMounts[].mountPath=/config
          │  Symlink：每 60s kubelet 重新拉 → 应用若 watch 文件自动生效
          │
          ├─────────── 注入方式 2：Env（envFrom:configMapRef/secretRef）
          │  ❌ 变更后必须重启 Pod（K8s 限制）→ 需滚动更新
          │
          └─────────── 注入方式 3：Spring Cloud Kubernetes Config（推荐！）
                Pod 中 spring-cloud-starter-kubernetes-client-config
                → Informer Watch ConfigMap/Secret 变更 → 自动 Refresh Spring Context

━━━━━━━━━━━━━━━━━━━━ 密钥增强生态（解决 Secret base64 明文问题）━━━━━━━━━━━━━━━━━━
┌─────────────────────────────────────────────────────────────────────┐
│ ① SealedSecret（Bitnami）：公钥加密 → 可以 Git 公开托管              │
│   kubeseal --cert=pub.pem < plainsecret.yaml > sealedsecret.yaml   │
│   Git 提交 → SealedSecret Controller 在集群用私钥解密生成 Secret    │
│                                                                     │
│ ② External Secrets Operator (ESO)：外部密钥管理系统拉取              │
│   SecretStore: Vault / AWS Secrets Manager / 阿里云 KMS             │
│   ExternalSecret.spec.target: name=oracle-cred → 自动同步到 K8s Secret│
│                                                                     │
│ ③ Vault Agent Injector（Mutating Webhook）：Pod 启动时注入 sidecar  │
│   从 Vault 拉密钥 → 内存文件 /tmp/secrets/oracle.properties        │
│   Spring 从文件读：spring.config.import=file:/tmp/secrets/          │
└─────────────────────────────────────────────────────────────────────┘
```

#### 🅒 对比与选型

| 维度 | Spring Cloud Config | K8s ConfigMap + Secret + ESO/Vault Injector | 胜者 & 理由 |
|------|--------------------|-------------------------------------------|------------|
| **配置变更原子性/版本化** | ✅ Git 天然版本控制 + PR Review + Tag 回滚；变更历史永久保留 | ⚠️ ConfigMap/Secret 只是 K8s 对象（无版本号），需要 GitOps（ArgoCD/Flux）管理才有版本 | 平手（都用 GitOps 就一样） |
| **热更新机制** | Config Server + Bus + RefreshScope（毫秒级同步所有 Pod） | VolumeMount 文件 kubelet 60s 刷新 + SC K8s Config Watcher（<3s）；Env 方式不支持 | SC（瞬时全局广播） |
| **密钥安全** | ⚠️ SC Config Server Vault 代理模式；客户端密钥通过 HTTP 传输（必须 HTTPS + mTLS） | ✅ **Vault Injector sidecar 直写内存文件**，不经过应用 HTTP；SealedSecret 支持 Git 公开；ESO 支持 TTL 轮换 | K8s 生态 |
| **非 Java 应用支持** | ❌ Config Client 是 Spring 独有 | ✅ 任意语言都可以读 Env / Volume 挂载文件 | K8s |
| **运维平台化** | 每个应用都要引 Config Client + Bus 依赖 + 配置 URL | ✅ **平台统一**：平台团队只维护 ESO/Vault；应用零依赖，直接读 env 文件 | K8s |
| **Oracle/JDBC 多环境配置** | ✅ `application-{profile}.yml` + JDK System Property `-Dspring.profiles.active` | ✅ 同左，用 ConfigMap 不同 Name + Kustomize / Helm overlay | 平手 |
| **动态业务配置（运行期人工热改）** | ✅ Nacos Config / JDBC 后端 + 控制台一键改 | ✅ `kubectl edit cm` 或 GitOps PR；但 SC K8s Config 能 Watch 自动生效 | 平手 |

**选型结论（三服务 + Oracle 场景）**：
```
✅ 组合式方案：
  ① 非敏感配置（Spring yml 业务参数、开关、阈值）：
     → ConfigMap（每个 env 一个 overlay） + ArgoCD GitOps 管理 + Spring Cloud Kubernetes Config Watcher 热更新
  ② 敏感配置（Oracle jdbc-url/username/password、JWT Secret、Redis Password）：
     → ❌ 绝对不能用普通 Secret（base64 明文存 etcd，etcd 必须启用 encryption at rest）
     → ✅ Vault Agent Injector sidecar 写入 /tmp/secrets/oracle-*.properties
     → Spring 侧：spring.config.import=optional:file:/tmp/secrets/
  ③ 公共依赖（如公共 Logger Level 统一配置）：
     → 可选 SC Config（所有服务共用一个 Config Server Git），避免 3 个 ConfigMap 都改同样内容
```

---

### 2.5 熔断限流与容错（Resilience4j/Sentinel vs Istio 流量管理 + K8s 资源限制）

#### 🅐 Spring Cloud Resilience4j / Sentinel 应用层治理

```
调用方 Service A → RestClient/Spring HTTP Service → Service B
     │
     ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Resilience4j 注解链（在 Service A 应用内存里执行）                    │
│                                                                     │
│  ┌─────────────────────────┐                                        │
│  │ 1. Bulkhead（舱壁隔离） │  → 限制同一 Service B 并发调用 100 个   │
│  │ THREADPOOL: 固定线程池  │    （避免下游挂导致 A 线程池占满）       │
│  │ SEMAPHORE: 信号量计数   │    拒绝时抛 BulkheadFullException       │
│  └─────────────────────────┘                                        │
│              │                                                       │
│              ▼                                                       │
│  ┌─────────────────────────┐                                        │
│  │ 2. TimeLimiter（超时）   │ → CompletableFuture.get(500ms)         │
│  │ 底层：Future.cancel()   │    超时抛 TimeoutException              │
│  └─────────────────────────┘                                        │
│              │                                                       │
│              ▼                                                       │
│  ┌─────────────────────────┐                                        │
│  │ 3. Retry（幂等重试）     │ → maxAttempts=3，指数退避 50/100/200ms │
│  │ 仅重试读接口：GET/SELECT│    只重试 TimeoutException/SocketError  │
│  └─────────────────────────┘                                        │
│              │                                                       │
│              ▼                                                       │
│  ┌─────────────────────────────────────────────────────┐             │
│  │ 4. CircuitBreaker（熔断器）CountBased/SlidingWindow │             │
│  │  CLOSED →（错误率 >30% 或 慢调用率 >50%）→ OPEN    │             │
│  │  OPEN →（30s 冷却）→ HALF_OPEN →（Permitted Calls）│             │
│  │  状态变化记录 EventConsumer（打 WARN 日志 + 指标上报）│             │
│  └─────────────────────────────────────────────────────┘             │
│              │                                                       │
│              ▼ 失败时 fallbackMethod（返回兜底数据或缓存）            │
└─────────────────────────────────────────────────────────────────────┘

━━━━━━━━━━━━━━━ 阿里 Sentinel：额外多维度 ━━━━━━━━━━━━━━━━
- 热点参数限流：按 skuId=123 单独限 100/s（Resilience4j 无此能力）
- 系统自适应：CPU/Load 超过阈值自动降级（SC R4j 需手动配置）
- 集群限流：3 个 Pod 总共限 500/s（单应用限流器无法做到）
```

#### 🅑 Kubernetes 资源限制 + Istio 流量管理平台层治理

```
┌──────────────────────────── K8s 资源限制（粗粒度）──────────────────────────┐
│                                                                             │
│  ① LimitRange（命名空间默认值）：每个容器默认 cpu=500m mem=1Gi                │
│  ② ResourceQuota（命名空间配额）：所有 Pod 加起来 cpu ≤ 32 mem ≤ 128Gi       │
│  ③ Pod.spec.containers[].resources:                                          │
│      requests.cpu=1 memory=2Gi  → 调度器据此找 Node                         │
│      limits.cpu=2  memory=4Gi  → cgroups 限流                                │
│         CPU 超过 limits → CFS 调度限流（throttle，不是 kill）                │
│         Mem 超过 limits → OOMKilled（Pod 被 kill，重启）                     │
│  ④ PodDisruptionBudget：节点维护时保证 Service B ≥ 2 Pod 存活                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
┌──────────────────────────── Istio 流量治理（L4/L7 细粒度）────────────────────┐
│                                                                             │
│  ① DestinationRule.trafficPolicy.connectionPool：                            │
│     - tcp.maxConnections=500（A→B 总共 500 TCP 连接）                        │
│     - tcp.connectTimeout=200ms                                               │
│     - http.http1MaxPendingRequests=100（HTTP/1.1 等待队列）                  │
│     - http.http2MaxRequests=1000（HTTP/2 多路复用最大请求）                  │
│     - http.maxRequestsPerConnection=10（每个连接复用到 10 请求就关闭）        │
│                                                                             │
│  ② DestinationRule.trafficPolicy.outlierDetection：（Ejection 异常点摘除）    │
│     - consecutive5xxErrors=5                                                 │
│     - interval=30s                                                           │
│     - baseEjectionTime=60s（A 侧 Sidecar 把这个坏实例暂时踢出）              │
│                                                                             │
│  ③ Envoy 全局限流（Rate Limit Service gRPC）：                               │
│     domain=api descriptor_key=path  /api/create-order  → 500/s 所有调用方合计│
│     descriptor_key=remote_address  10.0.0.5  → 5/s 单 IP 防刷                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 🅒 对比与选型

| 维度 | Spring 应用层：Resilience4j / Sentinel | K8s 平台层：Resource Quota + Istio | 胜者 & 理由 |
|------|--------------------------------------|-----------------------------------|------------|
| **业务语义感知** | ✅ 知道哪个接口是 CreateOrder（写）/ QueryOrder（读）→ 决定是否重试 + fallback 数据 | ❌ 只知道 Path/HTTP 状态；无法区分「同一路径不同 userId 不同限流策略」 | SC |
| **治理一致性（所有语言）** | ❌ 只治理 Java；Golang/Node 服务要自己实现一套（Sentinel 有多语言，但规则不同） | ✅ Istio Sidecar 透明拦截，**所有语言全部统一治理**（A 是 Java、B 是 Go、C 是 Python 完全一致） | K8s Mesh |
| **故障传播阻断** | ⚠️ 只在调用方做；如果 B 没有引入 Resilience4j，B→C 的调用无法阻断 | ✅ **B 调 C 的调用即使 B 完全没改代码也被 Mesh Sidecar 自动熔断** | K8s Mesh |
| **热点参数/业务级限流** | ✅ Sentinel 支持 `@SentinelResource(value="querySku", blockHandler="block")` + 热点参数限流规则（按 skuId=123 单独限） | ❌ 只支持 L7 header/path/ip 维度；无法识别请求 JSON body 中某个字段 | SC |
| **集群限流（跨 Pod 全局）** | ⚠️ 需 Redis 令牌桶 / Sentinel Cluster Node（额外组件） | ✅ **Envoy RLS + Redis** 开箱即用；Istio 1.22+ 内建全局限流 | K8s Mesh |
| **资源过载保护（CPU/MEM）** | ⚠️ Sentinel 系统自适应规则（单 Pod）；跨 Pod 无法联动 | ✅ K8s requests/limits + HPA（CPU/MEM/Prom QPS 自动扩 Pod 数） | K8s |
| **Fallback 兜底数据返回** | ✅ 直接访问 Java Bean/Cache/DB 返回兜底缓存数据（例如用户订单返回 Redis 里的快照） | ❌ 只能返回固定字符串 / 重定向到另一个 service；无法访问业务缓存/DB | SC |
| **观测/指标关联** | R4j 自动 Micrometer 指标 + Micrometer Tracing Span Error | ✅ Istio 生成 Service Graph（拓扑图）+ 指标 + OTel Trace Span（应用零埋点即可获得基础链路） | 平手 |

**选型结论（三服务规模）**：
```
✅ 分层治理（各司其职，互不冲突叠加生效）：
  ① K8s 层（必选 0 成本）：
     - LimitRange/ResourceQuota：每个服务 requests/limits 配死（防 OOM + 公平调度）
     - HPA：基于 CPU（50%）+ Prometheus 自定义 QPS 阈值自动扩缩容
  ② Istio Mesh 层（如果上了 Istio）：
     - outlierDetection：5 个 5xx 就把坏实例踢掉 30s
     - connectionPool：maxConnections 防止雪崩；http2MaxRequests 配合 gRPC 性能
     - RLS 全局限流：按 path /api/create-order 集群总 QPS ≤ 500
  ③ Spring 应用层：
     - Resilience4j：带业务语义 Fallback（订单降级用缓存、库存降级返回 0）+ 写接口禁用重试
     - Sentinel（可选）：热点参数限流（如大促 SKU 单独限 + 秒杀系统自适应限流）
❌ 反模式：只用应用层治理（B→C 如果 B 没配 R4j，C 故障直接把 B 拖死 → 级联失败）
```

---

### 2.6 分布式链路追踪（Micrometer Tracing vs Istio + Jaeger）

#### 🅐 Spring Cloud Micrometer Tracing 架构（Sleuth 已停更）

```
应用代码（ServiceA → ServiceB → ServiceC）
  │
  ├── Spring HTTP Service（RestClient）拦截器 → 自动创建 Client Span
  ├── Web MVC / WebFlux DispatcherServlet → 自动创建 Server Span
  ├── JDBC（HikariCP + Oracle JDBC）→ datasource-micrometer → Span（SQL + oracle.sql_id）
  ├── @Async / ThreadPoolTaskExecutor → ContextSnapshot 切换 → 异步 Span
  └── Logback MDC：[%traceId, %spanId, %parentId] 自动注入
        │
        ▼
┌────────────────────────────────────────────────────────────────────┐
│ Micrometer Tracing Bridge（选择 OTel / Brave）                     │
│  OpenTelemetry 推荐（Brave 后续仅维护）                             │
│    - W3C TraceContext（traceparent/tracestate 默认）               │
│    - Baggage（x-user-id/x-request-id 跨服务透传）                  │
└───────────────────────┬────────────────────────────────────────────┘
                        │ OTLP gRPC 4317（Span+Trace）
                        ▼
               OTel Collector → Kafka → Tempo / Jaeger → Grafana UI
```

#### 🅑 Istio Sidecar + Envoy OTel 架构

```
Service A Pod                    Service B Pod
┌──────────────────────┐        ┌──────────────────────┐
│  App Container(Java) │        │  App Container(Java) │
│  ┌────────────────┐  │        │  ┌────────────────┐  │
│  │ 完全无 OTel SDK │  │        │  │ 完全无 OTel SDK │  │
│  └──────┬─────────┘  │        │  └──────┬─────────┘  │
│   localhost:8080     │        │   localhost:8080     │
│         │▲           │        │         │▲           │
│   Envoy Sidecar      │        │   Envoy Sidecar      │
│   ┌────▼┴────────┐   │        │   ┌────▼┴────────┐   │
│   │ Outbound Proxy│──┼────────┼──▶│ Inbound Proxy │   │
│   │ 生成 Span #1  │   │   L7   │ 生成 Span #2     │   │
│   │ 记录 HTTP URL │   │  mTLS  │ 记录 5xx/延迟、LB  │   │
│   │ 状态码/Header │   │        │ 选中的后端权重     │   │
│   └───────────────┘   │        │   └───────────────┘   │
└──────────────────────┘        └──────────────────────┘
        │ OTLP Span（Envoy native_accessors）
        ▼
  OTel Collector → Jaeger / Tempo（与应用 OTel Span 混在同一个 Trace）
```

#### 🅒 对比与选型

| 维度 | Micrometer Tracing + OTel SDK（应用层 Span） | Istio Sidecar Envoy Span（Mesh 层 Span） | 胜者 & 理由 |
|------|-------------------------------------------|------------------------------------------|------------|
| **代码侵入/接入成本** | ⚠️ 需引依赖 + 少量配置（SpringBoot 4.x Actuator 下几乎零代码）；自建拦截器/Baggage 需配置 | ✅ **零侵入**：Sidecar 注入即生效；Python/Go 也有 Span | K8s Mesh |
| **Span 深度（业务语义）** | ✅ **非常深**：JDBC SQL/Redis key/@Async/@Scheduled/自定义注解；错误 Exception StackTrace 直接写 Span Event | ❌ 仅 L7：URL/状态码/Header/后端；看不到 SQL；看不到代码内部哪个函数慢 | SC |
| **链路完整性** | ✅ 全链路：入口 → HTTP Client → JDBC → MQ；但如果是 gRPC/Dubbo 要自己加插件 | ⚠️ 仅 L4/L7 网络跳；应用内部 Span 0，必须配和应用 OTel SDK 才有完整 | 平手（互补） |
| **链路追踪开销** | Java Agent / SDK 5~15% CPU（全量采样时）；10% 采样 1~3% | ✅ **Envoy C++**，每跳 0.1~1% CPU，极低 | K8s Mesh |
| **服务拓扑图生成** | ⚠️ 从所有 Span 后计算（Tempo Service Graph / Jaeger System Architecture）→ 需要历史数据聚合 | ✅ Istio Telemetry 直接生成 ServiceGraph（Kiali UI 秒级刷新拓扑） | K8s Mesh |
| **Oracle 专用 Tags** | ✅ 自定义 db.oracle.sql_id / db.oracle.instance / hikaricp.usage | ❌ Mesh 只能看到 Service C → Oracle TCP 连接，看不到内部 SQL | SC |
| **未知 3rd 服务/数据库观测** | ❌ 不引入 SDK 就无法生成 Span → 数据库/Oracle、Redis、第三方 API 看不到 | ✅ 所有出站入站流量都在 Sidecar；即使没有 SDK 也能获得基础调用情况 + 错误率 | K8s Mesh |

**选型结论（三服务 + Oracle 场景）**：
```
✅ 必须同时使用，二者互补（混在同一条 Trace 中）：
  ① Istio Sidecar Span（Mesh 提供 L4/L7 网络 Span，免费）：
     - 生成 service-graph 拓扑 + 基础服务级错误率/延迟（应用无需改代码）
     - 观测数据库（Oracle/Redis）出站流量（是否有慢连接、TLS 握手失败）
  ② Micrometer Tracing（应用层 Span，核心）：
     - OTel SDK + Oracle/JDBC/HikariCP 插件：拿到 SQL_id 关联 Oracle AWR
     - @Async / ThreadPool / MQ 异步 Span（Sidecar 看不到）
     - 自定义业务 Span（订单处理分阶段耗时）
  ③ 混排：Istio Span（service-a sidecar → service-b sidecar → service-c sidecar）
     与 Java Span（A Server Span → Oracle Client Span → A Client Span → B Server Span ...）
     被 OTel Collector 按 W3C traceparent 合并为同一 Trace → Grafana 一键看全链路
```

---

### 2.7 调用重试 & 超时（Spring Retry + R4j vs Istio VirtualService）

#### 🅐 Spring 应用层重试与超时

```java
// Service A 调用 C（库存扣减接口，写操作，不能重试）
@PostExchange("/inventory/deduct")
@TimeLimiter(name = "service-c-writes", timeoutDuration = "3000ms",
             fallbackMethod = "deductFallback")
DeductResult deductInventory(DeductInventoryRequest req);
//  ↑ TimeLimiter 本质：在客户端 Future.cancel(true) 中断调用线程

// Service A 调 C（查询 SKU，读接口，可重试 3 次）
@GetExchange("/sku/{id}")
@Retry(name = "service-c-reads",
       maxAttempts = 3,
       waitDuration = "50ms",
       retryExceptions = {SocketTimeoutException.class, ResourceAccessException.class},
       excludeExceptions = {IllegalArgumentException.class})  // 参数错误绝不能重试
SkuVO querySku(Long id);
```
**要点**：
- **应用知道业务语义**：读/写操作、哪些异常是幂等的（网络超时是、参数错误不是）、第 N 次重试要换实例（Spring Retry `backoff`）
- **Baggage 透传不会断**：重试上下文在同一个 trace 里，Span 标 `retry attempt=2/3`
- **与业务逻辑深度绑定**：重试次数超了后可以 fallback 走 Redis 缓存

#### 🅑 K8s + Istio 平台层重试与超时

```yaml
# Istio VirtualService (K8s CRD)
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata:
  name: service-c-vs
spec:
  hosts: ["service-c.default.svc.cluster.local"]
  http:
  - route:
    - destination:
        host: service-c
    timeout: 5s   # 总超时（从请求发出到最后一字节收到，含重试）

    retries:
      attempts: 3
      perTryTimeout: 1.5s   # 每次尝试单独超时
      retryOn: "connect-failure,refused-stream,503,retriable-status-codes,cancelled"
      # ⚠️ 平台层无法识别「这个请求是写接口」，因此对所有请求生效！
      # 需要在服务端保证幂等（例如用 X-Request-Id 做去重表）
```

#### 🅒 对比与选型

| 维度 | Spring 应用层（Spring Retry + R4j） | Istio 平台层（VirtualService） | 胜者 & 理由 |
|------|-------------------------------------|---------------------------------|------------|
| **业务语义感知（最重要）** | ✅ 读/写/幂等接口可分别配置 retryExceptions；参数错绝不重试 | ❌ 只认网络/HTTP 状态码；**写接口如果被意外重试会造成双扣/重复下单** | SC 完胜 |
| **零代码接入** | ❌ 需要注解 + 配置 | ✅ CRD 声明式，不改代码 | K8s Mesh |
| **跨语言一致性** | ❌ 各语言各写一套（Python tenacity、Go backoff 库） | ✅ Java/Go/Python 重试策略 100% 一致 | K8s Mesh |
| **与熔断联动** | ✅ 与 R4j CircuitBreaker 事件流联动 | ✅ 与 Istio OutlierDetection 联动 | 平手 |
| **重试策略精细化** | ✅ 随机指数退避 + Jitter（避免惊群）；每类异常独立策略 | ⚠️ 固定 attempts + perTryTimeout；无随机退避（Istio 1.22+ 支持 retryRemoteLocalities） | SC |
| **重试副作用安全** | ✅ `excludeExceptions` 严格列表，配合业务幂等注解 | ❌ 必须靠约定「所有写接口幂等 + 用 X-Request-Id 去重表」，否则生产事故 | SC |

**选型结论（三服务规模）**：
```
✅ 组合原则（防止双重重试 = 3×3=9 次雪崩）：
  ① Istio 层：**关闭自动重试**（只保留 timeout=10s 兜底 + connect-failure 1 次尝试）
  ② Spring 应用层：
     - 读接口：@Retry maxAttempts=3 + 只重试网络相关异常 + 幂等
     - 写接口：@Retry maxAttempts=1（禁止重试）+ 超时短 + Fallback 返回明确错误
     - 所有接口幂等：X-Request-Id 落 Oracle 唯一索引 → 即使 Mesh 意外重试也安全
❌ 绝对禁止：SC @Retry(3) + Istio retries.attempts=3 同开 → 最糟糕时 9 次请求打爆后端
```

---

### 2.8 安全认证与鉴权（SC Security + OAuth2 vs K8s RBAC + Istio 0 信任）

#### 🅐 Spring Cloud Security 架构

```
Client（前端 SPA / App）
  │  Authorization Code 登录
  ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Spring Authorization Server (OAuth2 AS)                             │
│  - /oauth2/authorize（登录/授权同意）                                 │
│  - /oauth2/token → JWT(Access Token) + Refresh Token                 │
│  - 用户身份信息在 JWT sub / scope / claims: roles, userId, tenantId │
└────────────────────────┬────────────────────────────────────────────┘
                         │ Access Token: Bearer JWT
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Spring Cloud Gateway + ResourceServer（校验 JWT）                   │
│    JwtAuthenticationToken → SecurityContextHolder                    │
│    @PreAuthorize("hasRole('ADMIN') and #userId == authentication.claims['userId']") │
│    细粒度：ABAC（基于属性：部门、租户、时间、IP）                       │
│         自定义 SpEL：@securityService.canAccessOrder(#orderId)       │
└────────────────────────┬────────────────────────────────────────────┘
                         │ 转发：X-User-Id / X-Roles Header
                         ▼
                Service A → Service B → Service C
                每个都配：OAuth2 ResourceServer（校验 JWT 签名，防止绕过网关直连）
```

#### 🅑 Kubernetes RBAC + Istio 0 信任架构

```
┌────────────────────────── K8s 控制面 RBAC（平台操作权限）──────────────────┐
│  Role / ClusterRole：允许「谁」对「哪些资源」做「哪些动词」                    │
│  - dev-role: pods.get, pods.list（开发只能看日志 exec）                     │
│  - sre-role: pods.* deployments.* nodes.*（SRE 全权限）                    │
│  RoleBinding/ClusterRoleBinding → User / Group / ServiceAccount             │
│  ServiceAccount（Pod 内部使用）：Pod.spec.serviceAccountName=service-a-sa   │
└────────────────────────────────────────────────────────────────────────────┘
┌────────────────────────── Istio 服务间 0 信任（数据面流量权限）──────────────┐
│                                                                             │
│  ① PeerAuthentication（服务间身份）：                                        │
│     STRICT 模式：所有 Pod-to-Pod 必须 mTLS（自动证书 + Sidecar 握手）        │
│     身份凭证：SPIFFE ID（spiffe://cluster.local/ns/default/sa/service-a-sa）│
│                                                                             │
│  ② RequestAuthentication（入口 JWT 校验，与 Spring 等效，可替代）：          │
│     jwtRules: issuer=https://auth.example.com jwksUri=... → Istio IngressGw │
│     校验通过后把 JWT claims 转成 request.auth.claims.*                       │
│                                                                             │
│  ③ AuthorizationPolicy（服务间细粒度授权，基于 SPIFFE + JWT claims）：       │
│     selector.matchLabels.app=service-c （谁是被保护方）                      │
│     rules[0].from[0].source.principals=["cluster.local/ns/default/sa/..."]  │
│         → 只有 Service A/B 的 Sidecar 身份才能调 Service C（拒绝外部/其他 NS）│
│     rules[0].when[0].key: request.auth.claims[roles] values["ADMIN","OPS"]   │
│         → 必须 JWT roles=ADMIN 才能访问 POST /inventory/deduct              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 🅒 对比与选型

| 维度 | Spring Cloud Security + OAuth2（用户/业务层） | K8s RBAC + Istio Auth（平台/服务层） | 胜者 & 理由 |
|------|---------------------------------------------|-------------------------------------|------------|
| **认证授权层级** | 面向「人类用户」：OAuth2 登录、JWT 用户身份 claims 业务权限 | 面向「服务账号/平台身份」：谁能调用哪个服务的哪个 API；以及 SRE 操作 K8s 权限 | 完全互补，层次不同 |
| **细粒度业务权限** | ✅ SpEL + 自定义 Bean：`@PreAuthorize("@orderPolicy.canModify(#order, authentication)")` 连数据库查「订单创建者是否是本人」 | ❌ 只支持 Header/JWT claims 的简单匹配（key=value）；无法查 DB 动态判断 | SC |
| **零信任 & 0 代码** | ❌ 所有服务都必须引 ResourceServer 依赖 + 配置；改一处漏洞就绕过 | ✅ **Mesh 0 信任，应用 0 代码**：即使应用是老旧漏洞代码，网络层被强身份保护；没有 mTLS + 正确 SPIFFE 就连不上 | K8s Mesh |
| **跨语言一致性** | ❌ 各语言各写一套 OAuth2 中间件，策略不统一、容易漏 | ✅ **跨语言**：Python 服务即使没写任何安全代码也被 AuthZPolicy 保护 | K8s Mesh |
| **Oracle 行级安全 RLS** | ✅ Spring + Oracle RLS：JWT userId → Oracle 会话设置 CLIENT_IDENTIFIER → VPD 自动过滤行 | ❌ 无法触达应用内部 DB 会话 | SC |
| **Blast radius（漏洞爆炸半径）** | ⚠️ 如果某服务 JWT 校验没开（忘记引依赖）→ 攻击者可以绕过网关直连内网 Pod → 拖库 | ✅ Istio 全局 PeerAuthentication=STRICT → 即使没配 AuthZ，流量没有 Sidecar 身份（mTLS）也连不上，连 TCP 包都打不进 | K8s Mesh |
| **外部访问入口安全** | ✅ SC Security + OAuth2 AS（企业完整 SSO，对接 LDAP/OIDC/SAML/短信登录） | ✅ Istio Gateway + OAuth2 Proxy + Keycloak；或配合 Spring 用 | 平手 |

**选型结论（三服务规模）**：
```
✅ 双层安全（互补，不是替代）：
  ① K8s/Mesh 层（0 信任基础）：
     - PeerAuthentication=STRICT（mTLS 所有服务间流量）
     - AuthorizationPolicy 默认 DENY，仅显式允许 service-a → service-b, service-a → service-c, service-b → service-c
     - RequestAuthentication 在 Istio Gateway 层校验 JWT（兜底，防止 SC Gateway JWT 校验 bug）
  ② Spring 层（业务权限核心）：
     - Spring Authorization Server：统一登录中心 + JWT 签发
     - SC Gateway：JWT 深度校验 + 用户注入 + 粗粒度路径权限
     - Service A/B/C 每个都要配 ResourceServer（兜底，即使绕过网关也安全）
     - Oracle 19c：启用 VPD RLS + Spring 拦截器设置 CLIENT_IDENTIFIER=userId（行级安全，DBA 都看不到别人的数据）
```

---

### 2.9 灰度发布/金丝雀发布（SC LB 自定义 vs Istio/K8s Gateway API）

#### 🅐 Spring Cloud 应用层灰度（SC LoadBalancer 自定义）

```yaml
# 自定义 Hint 灰度策略（基于 SC LoadBalancer HintBased）
spring:
  cloud:
    loadbalancer:
      hint:
        service-a: v2   # Pod label version=v2 的实例优先
```
```java
// 自定义 GrayLoadBalancer：从请求 header X-Gray-Tag=v2，选择 metadata version=v2 的实例
public class GrayHintBasedLoadBalancer implements ReactorLoadBalancer<ServiceInstance> {
    public Mono<Response<ServiceInstance>> choose(Request request) {
        DefaultRequestContext ctx = (DefaultRequestContext)request.getContext();
        String grayTag = ctx.getHint(); // @LoadBalancer 注解或 request scope 传过来
        return serviceInstanceListSupplier.get().map(instances -> {
            List<ServiceInstance> grayInstances = instances.stream()
                .filter(i -> grayTag.equals(i.getMetadata().get("version")))
                .collect(Collectors.toList());
            return grayInstances.isEmpty() ? new DefaultResponse(pick(instances))
                                           : new DefaultResponse(pick(grayInstances));
        });
    }
}
```
**特点**：
- 只能做**应用语义的按请求维度灰度**（x-user-id=vip 切 v2）
- 每个调用方都要引这段配置；运维不统一；**SC Gateway 之外的直连流量（例如 Job 直接调）不受控**
- **权重只能在应用层写死**（5% / 10% → 需要改代码或配置 + Spring Cloud Bus 刷新）

#### 🅑 Kubernetes Deployment 滚动更新 + Istio 金丝雀 + Gateway API

```yaml
# Deployment 版本子集
apiVersion: apps/v1
kind: Deployment
metadata:
  name: service-a-v1
spec:
  replicas: 9
  template.metadata.labels.app=service-a, version=v1

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: service-a-v2   # 另一个 Deployment，不是滚动升级
spec:
  replicas: 1          # 1 Pod = 10% 物理实例
  template.metadata.labels.app=service-a, version=v2

---
# Istio DestinationRule（定义 subsets = 子集）
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata: name: service-a-dr
spec:
  host: service-a
  trafficPolicy:
    outlierDetection: {consecutive5xxErrors: 5, interval: 30s, baseEjectionTime: 60s}
  subsets:
  - name: v1, labels: {version: v1}
  - name: v2, labels: {version: v2}

---
# Istio VirtualService（10% 权重到 v2）+ 按 header 100% 切
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata: name: service-a-vs
spec:
  hosts: [service-a]
  http:
  - match:
    - headers:
        x-user-group:
          exact: internal-QA
    route:
    - destination: {host: service-a, subset: v2}  # QA 组 100% 切 v2

  - route:                                        # 其他用户：90% v1 + 10% v2
    - destination: {host: service-a, subset: v1}, weight: 90
    - destination: {host: service-a, subset: v2}, weight: 10
    timeout: 5s
    retries: {attempts: 1, perTryTimeout: 1.5s}
```
**新 K8s Gateway API（GA，2024 标准替代 Ingress）**：
```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: name: service-a-canary
spec:
  parentRefs: [{name: istio-gateway}]  # 或 Nginx Gateway API 实现
  hostnames: [api.example.com]
  rules:
  - matches: [{headers: [{name: x-gray-tag, value: v2, type: Exact}]}]
    backendRefs: [{name: service-a-v2, port: 8080, weight: 100}]
  - backendRefs:
      [{name: service-a-v1, port: 8080, weight: 90},
       {name: service-a-v2, port: 8080, weight: 10}]
```
**Argo Rollouts（发布编排 CRD，更强大）**：
- 自动化流程：v2 部署 10% → 等 5 分钟 Prom P95 延迟 <200ms + 错误率 <0.5% → 自动 30% → 60% → 100%；不满足则**自动回滚**（蓝绿/金丝雀/渐进式）

#### 🅒 对比与选型

| 维度 | Spring Cloud 自定义灰度（应用层） | Istio Subset + VS + Gateway API（平台层） | 胜者 & 理由 |
|------|--------------------------------|------------------------------------------|------------|
| **配置位置** | 应用代码 / 配置文件（每个服务分散） | ✅ **K8s CRD，集中声明式 + GitOps** | K8s 完胜 |
| **权重精细化** | ⚠️ 只能按实例数百分比（1 Pod / 10 Pod ≈10%）+ 自定义权重随机算法 | ✅ 精确权重（1% 步长）→ 99%:1% 不需要 99 个 Pod，1 Pod v2 也能按 1% 流量分配 | K8s |
| **请求维度切流** | ✅ 能写任意 Java 逻辑（按 userId 哈希 %，按用户表字段查 VIP 标签 100% 切 v2） | ✅ 支持 Header/Cookie/Query/Host 匹配；**复杂业务逻辑（查 DB）需要 Envoy WASM 插件** | 平手 |
| **跨调用方一致性** | ❌ 仅对带了 SC LB 的调用方生效；如果有 Go/Node 服务或 Job 直接调用 Service 不走 LB，灰度不生效 | ✅ **100% 生效**：所有 Pod 流量（不管用什么语言、有没有 SDK）都被 Sidecar 拦截；甚至 Mesh Peer 间流量都按权重走 | K8s |
| **自动化发布编排** | ❌ 没有（需要自研 + 脚本调用 SC Bus Refresh） | ✅ Argo Rollouts：自动 Prometheus 评估 → 逐步放量 → 失败自动回滚；Kiali UI 可视化灰度进度 | K8s |
| **数据库/Oracle Schema 变更联动** | ✅ 代码里能写 Flyway/Liquibase 灰度版本切换逻辑 | ❌ 平台不关心应用内部 DB 变更 | SC |

**选型结论（三服务规模）**：
```
✅ 组合：
  ① Istio VirtualService + Gateway API（平台层，主灰度机制）：
     - v1/v2 两个 Deployment；DestinationRule 定义 subsets
     - 10% → 30% → 100% 自动权重；QA 组 header x-user-group=internal-QA 切 100% v2
     - Argo Rollouts 评估 Tempo Trace 错误率 + Prom QPS/P95
  ② Spring Cloud LoadBalancer Hint（应用层，仅用于非常细的业务灰度）：
     - 只有场景：灰度按「用户 VIP 等级 / 订单归属租户」这类查 Oracle 动态切流
     - 其他 95% 灰度场景全部走 Istio（避免代码侵入 + 运维分散）
```

---

### 2.10 服务间通信加密 mTLS（Spring SSL Bundle vs Istio Sidecar）

#### 🅐 Spring 手动 mTLS（Spring SSL Bundle + JDK 25 Security）

```yaml
spring:
  ssl:
    bundle:
      jks:
        client-mtls-bundle:
          keystore:
            location: classpath:client-service-a.p12
            password: ${SERVICE_A_KEYSTORE_PASSWORD}   # K8s Secret 注入
            type: PKCS12
          truststore:
            location: classpath:truststore-ca.p12       # CA 根证书
            password: ${CA_TRUSTSTORE_PASSWORD}

  # Spring HTTP Service RestClient 使用此 bundle
  restclient:
    ssl:
      bundle: client-mtls-bundle
```
**痛点**：
- **证书管理爆炸**：3 服务 → 需要 3×2 = 6 个证书（server/client 各 1）；轮换证书时需要重新构建镜像 + 滚动部署，至少 30 分钟
- **信任链复杂**：新增 1 个 Service D → 所有服务 truststore 需要加 D 的公钥 → 全部 3 个服务滚动部署
- **证书有效期短（最佳实践 ≤ 90 天）**：每个季度一次全员滚动重启，运维噩梦
- **Oracle 加密 TCPS（PKI/TLS）**：另需 Oracle Wallet（`.sso` 文件）+ JDK oracle.net.ssl_server_dn_match=true；配置错误直接 ORA 错误

#### 🅑 Istio Sidecar 透明 mTLS

```
   SPIFFE 证书体系（Istio CA）
   每 24h 自动轮转 Istio Sidecar 私钥证书（不用重启 Pod！）
   根 CA 10 年；中间 CA 1 年；工作负载证书 24h

   Service A Pod 出流量                 Service B Pod 入流量
   ┌──────────────────────┐             ┌──────────────────────┐
   │  App（无 mTLS 代码） │             │  App（无 mTLS 代码） │
   │  http://service-b    │             │  监听 8080 HTTP      │
   └───────┬──────────────┘             └──────────────┬───────┘
           │ localhost HTTP（明文）                    ▲ localhost HTTP（明文）
   ┌───────▼──────────────┐   mTLS（双向 TLS 1.3）  ┌─┴────────────────────┐
   │  Envoy Sidecar A     │──────────────────────▶  │  Envoy Sidecar B      │
   │  证书：A SPIFFE ID   │   SNI 校验 + SAN 校验   │  证书：B SPIFFE ID    │
   │  自动把 HTTP 升 TLS  │   拒绝任何非 mTLS 入站  │  自动解密，转明文给App│
   └──────────────────────┘                          └──────────────────────┘
```

#### 🅒 对比与选型

| 维度 | Spring SSL Bundle 手动 mTLS | Istio Sidecar 透明 mTLS | 胜者 & 理由 |
|------|---------------------------|-------------------------|------------|
| **证书管理** | ❌ 手动申请、轮换、分发；每季度滚动全集群 Pod 一次；服务越多越崩溃 | ✅ **Istio CA（citadel）自动签发+自动 24h 轮转**；0 运维；新增服务 D 秒级自动获得证书 | K8s Mesh 完胜 |
| **代码侵入** | ❌ 每个 HTTP Client 都要配置 SSL Bundle（Feign/RestClient/gRPC/Dubbo 各配一次） | ✅ **0 代码 0 配置**；应用继续用 HTTP 明文 localhost，Sidecar 自动升 mTLS | K8s |
| **认证后身份利用（授权）** | ⚠️ 需要从 SSL Session 取 Client DN → 映射到服务名 → 自己写 Filter 做权限判断 | ✅ Istio SPIFFE ID 直接在 AuthorizationPolicy `source.principals` 中使用；声明式 | K8s |
| **TLS 性能（JDK 25）** | JDK 新 TLS 1.3 实现 + 硬件 AES-NI；性能不错；但握手全在 Java 线程 | ✅ Envoy C++ + BoringSSL 汇编优化；TLS 会话复用比 JDK 强 2 倍；握手不在业务线程 | K8s |
| **Oracle TCPS 数据库加密** | ✅ Spring DataSource 可直接走 TCPS；Oracle Wallet 配一次就行 | ⚠️ Mesh 对外部数据库（Sidecar 出口网关）做 mTLS/TCP 隧道，需要 Oracle 证书在 Istio 中维护；可行但复杂 | 平手（Spring 管 Oracle TCPS） |
| **故障排查难度** | ❌ SSLHandshakeException + PKIX path building failed 调试极难，要开 -Djavax.net.debug=ssl | ✅ `istioctl analyze` + Kiali 直接显示「哪个调用方证书过期 / SAN 不匹配」 | K8s |

**选型结论（三服务规模）**：
```
✅ 强烈推荐：
  ① 服务间流量：Istio PeerAuthentication=STRICT → 100% mTLS（应用完全无感知，不用写任何 SSL 代码）
  ② 数据库 Oracle TCPS：Spring SSL Bundle 配置 Oracle Wallet（只改一次，3 个服务共享）
  ③ 边缘入口：Istio Gateway + cert-manager + Let's Encrypt（自动签发/轮换 HTTPS 证书，不用手动买证书）
❌ 不推荐：Spring 手动 mTLS（除非未来永远不会新增服务，3 个永远是 3 个）
```

---

## 3. 四种组合部署模式选型矩阵

| # | 部署模式 | 适用场景 | 技术栈 | 优点 | 缺点 |
|---|---------|---------|--------|-----|------|
| **Ⅰ. 纯 Spring Cloud（无 K8s 治理）** | 裸金属/VM 部署；没有 K8s；或团队无 K8s 运维能力 | Eureka + Config Server + Gateway + Feign + Resilience4j + Sentinel + Sleuth + Zipkin + ELK | 开发体验统一，Java 团队内部闭环 | 【极端不推荐】注册中心/配置中心高可用运维重；无法扩非 Java；没有弹性伸缩；服务隔离差（一个服务 OOM 整台机） |
| **Ⅱ. 纯 K8s 原生（尽量用平台，不用 Spring Cloud 组件）** | 团队已有成熟 K8s SRE；多语言服务（Java/Go/Python）；SpringBoot 4.x 新项目 | K8s Service + CoreDNS + Ingress/Gateway API + ConfigMap/Secret + ESV/Vault + ResourceQuotas + HPA + Argo Rollouts + ArgoCD GitOps | 平台统一运维；跨语言通用；GitOps 可审计 | **Spring 应用层能力没了**：没有业务语义熔断/Fallback、没有细粒度权限、没有 JDBC SQL Span |
| **Ⅲ. 平台化 + Service Mesh（推荐给中大型团队）⭐⭐⭐⭐⭐ 本项目首选** | ≥ 5 个服务、未来会扩 20+；多语言；对 0 信任安全/金丝雀发布有强需求 | 模式 Ⅱ 全栈 + **Istio（PeerAuth mTLS + AuthZPolicy + OutlierDetection + VirtualService 灰度 + OTel Span）** | 0 代码治理 + 0 信任 + 自动化金丝雀；全链路 Mesh Span + 应用 Span 混排 | 学习曲线陡；Sidecar 10~15% 额外 CPU/Mem；Istio 版本升级需要规划；排查 CNI/Sidecar 问题需专家 |
| **Ⅳ. Spring Cloud 应用治理 + K8s 基础编排（轻量混合，小团队起步推荐）⭐⭐⭐⭐** | ≤10 服务；纯 Java；团队无 Istio 经验；先快速上线再补能力 | 模式 Ⅱ + Spring Cloud Kubernetes Discovery + SC LoadBalancer Hint + Spring HTTP Service + Resilience4j + Micrometer Tracing + Vault Injector | 兼顾 K8s 优势（免注册中心、自动 Readiness 摘除）+ Spring 应用层语义强（业务熔断/细粒度权限）；**无需 Mesh 学习成本** | 灰度能力弱（纯 SC LB Hint 无法精确 1% 流量）；没有 mTLS（可临时用 Spring SSL 但维护累）；未来多语言服务进来要改造 |

---

## 4. 针对本项目（3 微服务 + JDK25 + SB4.1）的最终建议

```
✅ 推荐架构：模式 Ⅳ（轻量混合）→ 12 个月后升级模式 Ⅲ（加 Istio）

┌──────────────────────────────────────────────────────────────────────┐
│  一、K8s 平台层（用起来，不重造轮子，这些 Spring Cloud 做的更差的）       │
├──────────────────────────────────────────────────────────────────────┤
│  1. 服务发现/注册 → ❌ 不搭 Nacos/Eureka，✅ 用 K8s Service + CoreDNS │
│     + Spring Cloud Kubernetes Discovery（读取 Pod Label 做 LB 元数据） │
│  2. L4 负载均衡 → ✅ kube-proxy ipvs 模式（关闭 SC LB 健康检查）       │
│  3. 密钥管理 → ✅ Vault Agent Injector 写入 /tmp/secrets/*.properties │
│     （Oracle JDBC Password / JWT Secret / Redis）                    │
│  4. 资源限制 & 扩缩容 → ✅ requests/limits + HPA（CPU + QPS 双指标）  │
│  5. 非敏感配置 → ✅ ConfigMap + ArgoCD GitOps + SC K8s Config 热更新  │
│  6. 集群入口网关 → ✅ Istio Gateway + Gateway API（或 Nginx Ingress） │
│     （TLS 证书 cert-manager + WAF 基础能力）                          │
│  7. 发布 → ✅ Deployment RollingUpdate（先不金丝雀）；大版本蓝绿        │
└──────────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────────┐
│  二、Spring Cloud 应用层（这些平台能力达不到的，业务语义必须用 SC）       │
├──────────────────────────────────────────────────────────────────────┤
│  1. HTTP 调用 → ✅ Spring HTTP Service（@HttpExchange + RestClient） │
│     + ObservationRegistry（一行开启 Trace/Metrics/Exemplar）          │
│  2. 业务网关 → ✅ Spring Cloud Gateway + OAuth2 ResourceServer        │
│     （JWT 用户上下文注入 + 自定义业务限流 + 响应脱敏）                  │
│  3. 熔断容错 → ✅ Resilience4j（@CircuitBreaker/@Retry/@Bulkhead 全打 │
│     在 @HttpExchange 接口上；写接口禁用重试；Fallback 返回缓存数据）    │
│  4. 链路追踪 → ✅ Micrometer Tracing OTel Bridge + Oracle JDBC 插件   │
│     （Span 注入 db.oracle.sql_id 关联 AWR）                           │
│  5. 用户安全 → ✅ Spring Authorization Server + ResourceServer        │
│     + Oracle VPD RLS（CLIENT_IDENTIFIER=userId）                      │
│  6. 配置公共项 → 可选 Spring Cloud Config（统一 Logger Level 刷新）   │
└──────────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────────┐
│  三、观测体系（用之前《Spring 观测体系设计》A 方案，和现有能力打通）       │
├──────────────────────────────────────────────────────────────────────┤
│  OTel Collector → Prometheus(指标) / Loki(日志) / Tempo(Trace)       │
│  + Arthas Tunnel + Grafana 单 UI                                     │
└──────────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────────┐
│  四、12 个月演进路线（服务量增长到 10+ / 引入 Go 服务时）               │
├──────────────────────────────────────────────────────────────────────┤
│  加 Istio（STRICT mTLS + AuthZPolicy + VirtualService 金丝雀权重）    │
│  + Argo Rollouts（自动 Prometheus 评估金丝雀）                        │
│  + Istio Gateway JWT 校验兜底（双层安全）                              │
│  + Mesh OTel Span 补全（获得 Python/Go 服务链路 Span）                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 5. 迁移路线图：Spring Cloud 自研 → K8s 平台下沉

| 阶段 | 时间点 | 动作 | 回滚方案 |
|------|--------|------|----------|
| **T+0 基线** | 现在 | 记录当前使用的 Spring Cloud 组件清单（Nacos/Eureka/Gateway/Feign/Config/Sleuth/Sentinel） | - |
| **T+1 周（第一步低风险：服务发现）** | +7D | 引入 `spring-cloud-starter-kubernetes-discovery` → 所有服务 discovery 改成 K8s API；下线 Nacos/Eureka 集群 | discovery 回切 Nacos；保留双写 2 周 |
| **T+2 周（配置与密钥）** | +14D | ConfigMap 替代 SC Config 的公共配置；Vault Injector 注入 Oracle 密码（原 SC Config Vault 代理下线） | 保留 Config Server 配置备份，SC Config 依赖不改 |
| **T+3 周（HTTP 客户端）** | +21D | Feign → Spring HTTP Service（@HttpExchange）迁移；Feign 依赖保留但不再用 | 接口映射表 + A/B 切换 Feature Flag |
| **T+4 周（网关）** | +28D | Ingress / Istio Gateway 接外部流量；SC Gateway 改为内部业务网关（不在边缘暴露） | 云 LB DNS 权重切回 SCG |
| **T+8 周（加 Mesh）** | +56D | Istio Sidecar 以 PERMISSIVE（允许 mTLS 或明文，先双活）；观测 Mesh Span 与应用 Span 合并；AuthZPolicy DENY ALL 默认，逐个加允许规则 | Istio Sidecar 先设 `istio-injection=disabled` 注入禁用 |
| **T+12 周（mTLS 开启）** | +84D | PeerAuth=STRICT（强制 mTLS）；Spring SSL 全部移除；Oracle TCPS 走 Spring SSL | PeerAuth 切回 PERMISSIVE |
| **T+16 周（发布平台化）** | +112D | Istio VirtualService + Argo Rollouts 灰度发布；SC LB Hint 仅保留业务细粒度灰度（VIP 用户） | Argo Rollouts 暂停 → 手工 Deployment RollingUpdate |

---

> **结语**：Spring Cloud 不是 Kubernetes 的替代品，而是「业务语义治理层」。K8s/Mesh 解决「平台侧可观测性、安全、流量」的通用问题；Spring Cloud 解决「业务侧熔断策略、用户权限、业务上下文传递、缓存降级」这类强语义问题。
>
> 在 2024~2026 年，**将通用能力尽可能下沉到平台（服务发现/mTLS/灰度/密钥/L4-L7 流量治理），将 Spring Cloud 专注于业务语义能力**，是 3 服务到 30 服务平滑演进的核心。
