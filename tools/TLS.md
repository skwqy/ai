# TLS 深度解析（写给初学者的协议全景与工程落地）

> **本文依据的"源"**：TLS 是协议而非某一份代码，因此本文的证据链来自两层——①**协议规范**（RFC 8446 TLS 1.3、RFC 5246 TLS 1.2、RFC 9001 QUIC-TLS、RFC 2818/9113 HTTPS、RFC 3207 SMTP STARTTLS 等）；②**本地可读的实现源码**：JDK 27（`D:\soft\jdk\jdk-27\lib\src.zip`，Oracle OpenJDK `27+35-2325`，2026-09-15 构建，`sun/security/ssl` 与 `javax/net/ssl` 包共 500 个文件，本文已解压至 `D:\tmp\tls-src\java.base`，行号均对该快照实际读取所得）、Netty **4.2.18.Final**（`D:\code\3rd\netty-netty-4.2.18.Final`）、Apache Kafka **4.5.0-SNAPSHOT**（`D:\code\3rd\kafka`）、Redis **8.10**（`D:\code\3rd\redis`）。
>
> **版本取舍说明**：TLS 1.3（RFC 8446，2018-08）是当前的主线，TLS 1.2（RFC 5246）仍是存量最大的部署版本，两者在文中**并行展开、逐节对比**；TLS 1.0/1.1 已被 RFC 8996（2021）正式废弃，SSL 3.0 更早被 RFC 7568（2015）判死刑，只在"版本演进"与"安全专题"里作为历史教训出现。JDK 的 TLS 栈（SunJSSE，`sun.security.ssl` 包）自 11 起完整支持 TLS 1.3，11 → 27 骨架高度稳定，本文结论对 JDK 11+ 同样适用；文中另注明了两处 JDK 27 才有的新东西（QUIC-TLS 引擎、0-RTT 的实现现状）。
>
> **阅读约定**：每章"先白话、后证据"——先用一两句人话讲清"这是什么、为什么需要它"，再给出证据链。JDK 源码证据格式为 `sun/security/ssl/类名.java` + 行号 + 代码片段（源码可从任意 JDK 的 `lib/src.zip` 中按路径解出）；Netty/Kafka/Redis 证据直接给本地路径。行号只对该快照精确，读者按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 TLS 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的白话段、第二~五章各"本章小结"、第七章（协议全景）与第八章开头（三种接入模式）。目标是能回答：TLS 的加密和身份认证分别靠什么实现？一次 HTTPS 握手双方各发什么消息？TLS 1.2 和 1.3 的本质区别是什么？Kafka/Redis/MySQL 这些"非 HTTP 协议"是怎么接上 TLS 的？
- **第二遍（深入细节）**：顺序建议：第四章（1.2 握手，一切的地基）→ 第五章（1.3 握手）→ 第三章（记录层）→ 第八章（逐协议处理流程，挑你正在用的协议读）→ 第六章（证书与信任）→ 第九章（工程落地）→ 第十章（安全专题，随用随查）。

---

# 一、总览：TLS 的定位、设计哲学与协议全景

## 1.1 一句话定位

**TLS（Transport Layer Security，传输层安全）是一个夹在可靠传输协议（TCP）与应用协议（HTTP、Kafka、SMTP、Redis 协议……）之间的安全协议：它把一条"谁知道都能读、都能改"的裸字节流，变成一条"窃听者只能看到字节长度、篡改者会被当场识破、冒充者拿不出身份证明"的加密字节流。**

它为上层提供三件套（安全界的行话叫 CIA）：

| 威胁 | TLS 的对策 | 靠什么实现 |
|---|---|---|
| **窃听**（保密性） | 对称加密所有应用数据 | AEAD（AES-GCM / ChaCha20-Poly1305） |
| **篡改**（完整性） | 每条记录带认证标签 + 单调递增序号 | AEAD tag + sequence number |
| **冒充**（身份认证） | 证书链验证 + 握手过程数字签名 | X.509 + CertificateVerify/Finished |

理解 TLS 的关键是**分层视角**：它自己内部还分两层——**握手协议**（Handshake，负责"怎么协商出密钥、怎么验明正身"）和**记录协议**（Record，负责"怎么用协商好的密钥安全地搬运数据"）。协商是一次性的，搬运是持续的；密钥从协商流向搬运，这就是整个协议的主干。

TLS 解决的也不是"加密"这一个孤立问题，而是**在开放的互联网上建立信任**的问题：你和 `api.example.com` 之间隔着一堆你控制不了的路由器、代理和 Wi-Fi 热点，TLS 让你不需要信任其中任何一个。

## 1.2 设计哲学：读协议前先记住四句话

1. **框架与算法分离，算法可协商（算法敏捷性）**。TLS 协议本身只定义"如何协商、如何搬运"，真正的加密/签名/哈希算法由 **CipherSuite（密码套件）** 这个"菜单"决定：客户端报上自己会做的菜，服务端点其中一道。RC4 过时了就下架，ChaCha20 出来了就上新——20 多年协议框架基本没变，算法换了好几代。1.3 的套件只有 5 道（见 5.8 节），JDK 里那 5 个就是一行一个的枚举常量。
2. **协商出来的东西必须被"绑定"（transcript 绑定）**。握手是明文开始的，中间人理论上可以改写每一条消息。TLS 的对策是：每条握手消息都被哈希进 **transcript（握手转录）**，最后的 Finished 消息对整个 transcript 做 MAC，CertificateVerify 对它做签名——**任何一条被改过的消息都会让最后的校验对不上账**。这是 TLS 安全性的"总闸"，第六章会看到它在 JDK 里的实现（`handshakeHash.digest()`）。
3. **前向安全优先（PFS，Forward Secrecy）**。用 (EC)DHE 每次握手临时生成密钥，长期私钥只用来签名身份——服务器的私钥明天泄露，昨天的抓包历史依然安全。TLS 1.3 把这一条从"可选项"变成了"强制项"：RSA 密钥传输（私钥泄露即历史全灭）被彻底移除。
4. **加密尽早、暴露尽量少**。TLS 1.3 从 ServerHello 之后的所有握手消息（证书、签名、扩展）全部加密，只有两条 Hello 是明文；证书、SNI 之外的意图、双方支持的算法都藏进了密文。连"兼容老中间盒"的 ChangeCipherSpec 空壳记录都做成 dummy（假动作）——后面 5.7 节有 JDK 注释为证。

## 1.3 协议分层全景

TLS 是一族子协议的集合，它们共享同一个"信封外壳"（记录协议）。从下往上看：

```
┌─────────────────────────── 应用协议 ───────────────────────────┐
│  HTTP/1.1  HTTP/2  Kafka 协议  RESP(Redis)  SMTP  MQTT  AMQP…  │
├───────────────────────── TLS 上层子协议 ────────────────────────┤
│  Handshake（握手：协商密钥/算法/身份，type=22 之内）             │
│  Application Data（业务数据，type=23）                           │
│  Alert（告警：出错就断，type=21）                                │
│  ChangeCipherSpec（1.2 的"切换密钥"开关，type=20；1.3 仅剩兼容壳）│
│  ── 1.3 新增（复用 type=22 的握手外壳）──                        │
│  NewSessionTicket / KeyUpdate / CertificateRequest(post-handshake)│
├───────────────────────── 记录协议（Record Layer）────────────────┤
│  分片 → 压缩(已废弃) → 加密 + 认证 → 加序号 → 组帧               │
│  记录头：type(1B) + legacy_version(2B) + length(2B)              │
├───────────────────────── 可靠传输 ──────────────────────────────┤
│  TCP（主流）/ UDP（DTLS 变体）/ QUIC（1.3 握手被拆开嵌入，见 8.8）│
└──────────────────────────────────────────────────────────────────┘
```

四种 content type 的编号与版本归属，JDK 里就是一个枚举（注意 `CHANGE_CIPHER_SPEC` 的 supportedProtocols 是 `PROTOCOLS_TO_12`——1.3 中它只剩兼容空壳）：

【源码证据】`sun/security/ssl/ContentType.java` 第 31-41 行（JDK 27）：

```java
enum ContentType {
    INVALID             ((byte)0,   "invalid",
                            ProtocolVersion.PROTOCOLS_OF_13),
    CHANGE_CIPHER_SPEC  ((byte)20,  "change_cipher_spec",
                            ProtocolVersion.PROTOCOLS_TO_12),
    ALERT               ((byte)21,  "alert",
                            ProtocolVersion.PROTOCOLS_TO_13),
    HANDSHAKE           ((byte)22,  "handshake",
                            ProtocolVersion.PROTOCOLS_TO_13),
    APPLICATION_DATA    ((byte)23,  "application_data",
                            ProtocolVersion.PROTOCOLS_TO_13);
```

## 1.4 关键问题 → TLS 方案映射（全文导览）

| 网络通信的关键问题 | TLS 的方案 | 详见 |
|---|---|---|
| 传输内容被窃听 | 记录协议 AEAD 加密，密钥只存在于两端 | 第三章 |
| 传输内容被篡改/重排/重放 | AEAD 认证标签 + 隐式单调序号（含在 MAC 输入里） | 第三章 3.3 |
| "对面是不是真的服务器" | 证书链验证（PKIX）+ 服务端私钥签名 | 第六章 |
| "握手过程被中间人改写" | transcript 哈希贯穿始终，Finished 对它做 MAC、CertificateVerify 对它做签名 | 第四章 4.3、第五章 5.2 |
| "被诱导退回老版本/弱算法"（降级） | supported_versions 扩展 + ServerHello.random 降级哨兵（"DOWNGRD"魔数） | 第五章 5.4 |
| 私钥泄露波及历史流量 | (EC)DHE 临时密钥实现前向安全；1.3 移除 RSA 密钥传输 | 第二章 2.3、第四章 4.4 |
| 握手太慢（1.2 要 2-RTT） | 1.3 压到 1-RTT，PSK 恢复 1-RTT/0-RTT | 第五章 |
| 老中间盒不认识新协议 | 兼容性设计：记录版本字段恒写 0x0303、dummy CCS 记录 | 第三章 3.1、第五章 5.7 |
| 一次连接中途密钥可能用旧 | 1.3 KeyUpdate 在线换密钥（QUIC 下被禁用，QUIC 有自己的 key phase） | 第五章 5.7 |

## 1.5 版本演进：SSL 2.0 → TLS 1.3

| 版本 | 发布 | 规范 | 一句话主题 | 结局 |
|---|---|---|---|---|
| SSL 2.0 | 1995 | Netscape 私有 | 第一个广泛部署的安全传输协议 | 2011 被 RFC 6176 禁用 |
| SSL 3.0 | 1996 | RFC 6101 | 修复 SSL 2.0 大量缺陷 | POODLE 攻击（2014）后 RFC 7568（2015）禁用 |
| TLS 1.0 | 1999 | RFC 2246 | SSL 3.0 标准化改名 | RFC 8996（2021）正式废弃 |
| TLS 1.1 | 2006 | RFC 4346 | 修 CBC 显式 IV、防填充预言攻击 | RFC 8996（2021）正式废弃 |
| **TLS 1.2** | 2008 | RFC 5246 | SHA-256 PRF、AEAD（GCM）套件、加密 then MAC 思路落地 | 存量最大，仍广泛部署 |
| **TLS 1.3** | 2018-08 | RFC 8446 | 1-RTT/0-RTT、HKDF、强制 PFS、砍掉一批历史包袱 | 当前主线 |

TLS 1.3 相对 1.2 的关键变化（后续章节逐条展开）：

1. **握手 2-RTT → 1-RTT**，PSK 恢复场景 0-RTT 可选（5.1、5.5 节）；
2. **密钥推导 PRF → HKDF**（Extract/Expand 两段式，5.2 节）；
3. **移除**：RSA/静态 DH 密钥交换、CBC 套件、RC4、压缩、重协商、数字签名 MD5/SHA-1（5.8 节对照表）；
4. **握手消息从 ServerHello 之后全部加密**，新增 EncryptedExtensions（5.1 节）；
5. **会话恢复重构**：session ID/ticket → NewSessionTicket + PSK + binder（5.6 节）；
6. **ServerHello.random 内嵌降级哨兵**（"DOWNGRD" 8 字节魔数，5.4 节）；
7. 兼容性外壳：记录版本字段恒 0x0303、dummy CCS（3.1、5.7 节）。

## 1.6 全文章节地图

- **第二章 密码学地基**：TLS 用到的五样原材料——哈希/HMAC、AEAD、(EC)DHE、数字签名、X.509 证书，每一件只讲"TLS 拿它干什么"。
- **第三章 记录协议**：所有数据走的那层"信封"：记录格式、1.2 与 1.3 加密方式差异、序号与 nonce。
- **第四章 TLS 1.2 握手**：完整消息流、PRF 密钥推导、RSA 密钥交换的命运、会话恢复与重协商。
- **第五章 TLS 1.3 握手**：1-RTT 消息流、HKDF 密钥调度、HelloRetryRequest、降级哨兵、0-RTT、PSK 恢复，1.2 vs 1.3 对照表。
- **第六章 身份认证**：X.509 证书里有什么、证书链怎么验、hostname 怎么验、吊销为什么难、mTLS。
- **第七章 TLS 可以用在哪些协议中**：三种接入模式（端口级直包 / 明文升级 / 内嵌重构）+ 协议全景表。
- **第八章 各协议中的处理流程**：HTTPS（含代理 CONNECT）、STARTTLS 家族（SMTP/LDAP/XMPP）、数据库自定义协商（PostgreSQL/MySQL）、Kafka、Redis、MQTT、gRPC、QUIC/HTTP3，逐个画出接入序列图。
- **第九章 工程实践（JVM 视角）**：SSLSocket vs SSLEngine 两种驱动模型、Netty SslHandler 的实现、OpenSSL Provider、ALPN、性能清单。
- **第十章 安全专题**：MITM、降级攻击史、重协商注入、0-RTT 重放、Heartbleed——每个攻击对应协议的哪道防线。
- **第十一章 贯通视图**：把 TCP 三次握手 + TLS 握手 + HTTP 请求三条时间线叠成一张全景图。
- **第十二章 附录**：套件速查、消息与扩展编号表、端口速查、动手实验与学习路线。

---

# 二、密码学地基：TLS 用到的五样原材料

> 本章对应源码：`sun/security/ssl/CipherSuite.java`（套件定义）、`sun/security/ssl/SSLBasicKeyDerivation.java`（HKDF 封装）。TLS 不自己发明密码学原语，它只负责把下面的原材料按正确的顺序、以正确的方式组合——所以理解这五样材料，后面所有协议细节都会变得"理所当然"。

## 2.1 哈希与 HMAC：完整性的一对工具

**哈希**（SHA-256/SHA-384）把任意长度的输入压成固定长度的"指纹"，不可逆、抗碰改。**HMAC** = 哈希 + 钥匙：`HMAC(key, message)`，只有持有 key 的人才能算出/验证这个指纹。

TLS 拿它们做三件事：

1. **transcript**：把所有握手消息按顺序哈希，得到一条"历史账本的摘要"，后面所有签名/MAC 都对它操作（这是 1.2 设计哲学第 2 条的落地）；
2. **Finished 校验**：对 transcript 做 HMAC（第四章 4.3、第五章 5.2）；
3. **1.2 非 AEAD 套件的数据完整性**：每条记录附 HMAC 值（3.2 节）。

## 2.2 AEAD：加密和认证一次完成

**AEAD**（Authenticated Encryption with Associated Data，认证加密）把传统的"先加密、再算 MAC"两步合成一步：`AEAD(key, nonce, plaintext, aad)` 输出 `ciphertext + tag`。tag 是校验值——解密方算出的 tag 与收到的 tag 不一致，就说明密文被改过（或密钥不对），**解密直接失败，攻击者没有任何改写空间**。

TLS 主流的两套 AEAD：

| 套件 | 加密 | 认证 | 适用场景 |
|---|---|---|---|
| AES-128/256-GCM | AES 分组 | GHASH | 主流选择，x86 有 AES-NI 硬件指令，几乎免费 |
| ChaCha20-Poly1305 | ChaCha20 流密码 | Poly1305 | 纯软件实现快，适合无 AES 硬件加速的移动/嵌入式设备 |

两个必须刻在脑子里的约束：

- **nonce 绝不能在同一个 key 下重复**。GCM  nonce 重用 = 密码学灾难（可恢复明文、可伪造）。TLS 的做法是用"每方向固定的 write IV XOR 每条记录的序号"来构造 nonce——序号单调递增，天然不重复（3.3 节）。
- **AAD（附加认证数据）**：不加密但必须认证的部分（记录头、1.2 的显式 nonce）。攻击者改了 AAD，tag 校验同样失败。

TLS 1.3 只保留了 AEAD 套件——CBC 和"纯流密码"模式全部出局，这一刀砍掉了 Lucky13（CBC 时序侧信道）一类整族攻击。

## 2.3 (EC)DHE 与前向安全：每次握手换一把新钥匙

**Diffie-Hellman** 密钥交换的魔法：双方公开交换"半成品"（DH 公钥），各自与自己的私钥计算，得到**只有两端能算出、窃听者算不出**的同一个值。ECDHE 用椭圆曲线实现，密钥短（256 位椭圆曲线 ≈ 3072 位有限域 DH 的安全强度）、快得多。

TLS 用它实现**前向安全**：每次握手临时生成一对 ECDHE 密钥，握手结束即丢弃。会话密钥由 ECDHE 共享秘密派生，**长期私钥（证书里的那把）只用来做签名验身份**。于是：服务器私钥明天泄露 → 攻击者能冒充服务器（ until 换证书），但**解不开昨天抓的包**——因为昨天的 ECDHE 临时密钥早已销毁。

这就是 TLS 1.3 砍掉 RSA 密钥交换的根本原因：RSA 模式下，会话密钥直接用服务器证书公钥加密传输——私钥一泄，所有历史抓包全部解密（第四章 4.4 节展开）。

## 2.4 数字签名与 X.509 证书：身份认证的载体

**签名**用私钥生成、公钥验证（RSA-PSS/ECDSA/Ed25519）。TLS 中签名的对象从来不是业务数据，而是**transcript/握手参数**——"我用这把私钥担保：刚才这场协商我说了这些话"。

但"这把公钥真的是 example.com 的吗？"——公钥本身需要一个信任背书，这就是 **X.509 证书**：由 CA 用自己的私钥对"主体名 + 公钥 + 有效期 + 扩展"这组信息（TBSCertificate）做签名。形成链条：根 CA（浏览器/操作系统预置）签中间 CA，中间 CA 签站点证书。验证 = 沿链逐级验签直到信任锚（第六章展开）。

## 2.5 HKDF：TLS 1.3 的密钥推导机

**HKDF**（RFC 5869）= **Extract**（吸出高熵密钥材料）+ **Expand**（用标签把密钥材料展开成多把独立密钥）两段：

```
PRK  = HKDF-Extract(salt, IKM)          # 从不均匀的输入里"提纯"
OKM  = HKDF-Expand(PRK, info, L)        # 按 info（标签/上下文）展开成 L 字节
```

它解决两个问题：一是把 ECDHE 输出、PSK 这类"不一定均匀"的输入规范化成均匀密钥；二是**用不同的 info 标签从同一个主秘密派生出多把互相独立的密钥**（握手密钥、应用密钥、恢复密钥……），一把泄露不波及其他。

TLS 1.3 把 HKDF 用到了极致——5.2 节的密钥调度图里每条箭头都是一次 Extract 或 Expand。JDK 里 HKDF 的 info 结构（长度 + 标签 + 上下文）和所有 RFC 8446 标签都是现成的代码：

【源码证据】`sun/security/ssl/SSLSecretDerivation.java` 第 121-135 行（HkdfLabel 的 info 编码，与 RFC 8446 §7.1 的 `struct HkdfLabel` 一致）：

```java
    public static byte[] createHkdfInfo(
            byte[] label, byte[] context, int length) {
        byte[] info = new byte[4 + label.length + context.length];
        ByteBuffer m = ByteBuffer.wrap(info);
        ...
        Record.putInt16(m, length);
        Record.putBytes8(m, label);
        Record.putBytes8(m, context);
        ...
    }
```

【源码证据】`sun/security/ssl/SSLSecretDerivation.java` 第 137-156 行（RFC 8446 §7.1 的全部标签，一字不差）：

```java
    private enum SecretSchedule {
        // Note that we use enum name as the key/secret name.
        TlsSaltSecret                       ("derived"),
        TlsExtBinderKey                     ("ext binder"),
        TlsResBinderKey                     ("res binder"),
        TlsClientEarlyTrafficSecret         ("c e traffic"),
        TlsEarlyExporterMasterSecret        ("e exp master"),
        TlsClientHandshakeTrafficSecret     ("c hs traffic"),
        TlsServerHandshakeTrafficSecret     ("s hs traffic"),
        TlsClientAppTrafficSecret           ("c ap traffic"),
        TlsServerAppTrafficSecret           ("s ap traffic"),
        TlsExporterMasterSecret             ("exp master"),
        TlsResumptionMasterSecret           ("res master");

        private final byte[] label;

        SecretSchedule(String label) {
            this.label = ("tls13 " + label).getBytes();
        }
    }
```

## 本章小结

- HMAC 管"有没有被改"，AEAD 管"既加密又防改"，(EC)DHE 管"密钥怎么凭空对上且不落纸面"，签名 + X.509 管"对面是谁"，HKDF 管"一把主秘密怎么安全地拆成 N 把专用密钥"。
- TLS 1.2 → 1.3 的算法层面演进方向就是：**能换 AEAD 的都换 AEAD，能上 PFS 的都上 PFS，密钥推导统一到 HKDF**。
- 所有这些"组合方式"本身都是协议规定的——下一章开始看组合的载体：记录协议。

---

# 三、记录协议（Record Layer）：所有数据的统一信封

> 本章对应源码：`sun/security/ssl/Record.java`（记录尺寸常量）、`sun/security/ssl/Authenticator.java`（序号）、`sun/security/ssl/SSLEngineOutputRecord.java`（1.3 记录加密）。记录协议是 TLS 中最"不起眼"但所有流量都过它手的层：无论握手消息、告警还是业务数据，一律切成记录再出门。

## 3.1 记录格式：5 字节的信封头

明文记录（TLS 1.2 及握手阶段的 1.3）头 5 字节，大端：

```
struct {
    uint8  type;        // 20 CCS / 21 alert / 22 handshake / 23 app data
    uint16 version;     // 记录层版本
    uint16 length;      // 后续字节数
    opaque payload[length];
} TLSPlaintext;
```

两个非常人性化的兼容设计（TLS 1.3）：

1. **外层 version 字段恒写 0x0303**（即 TLS 1.2），真正的版本协商结果在握手扩展 `supported_versions` 里——因为大量中间盒看到不认识的版本号会直接掐断连接，1.3 选择"假装是 1.2"通过它们（第一章 1.2 节"暴露尽量少"哲学的体现）；
2. **1.3 的记录外层 type 恒写 23（application_data）**，真正的类型（握手/告警/CCS）加密后藏在密文内部末尾——嗅探者从此看不出"这条记录是握手还是数据"。

【源码证据】`sun/security/ssl/ContentType.java` 第 32-35 行：`CHANGE_CIPHER_SPEC` 仅注册到 `PROTOCOLS_TO_12`，1.3 的 dummy CCS 只是伪造该类型的空壳记录（见 5.7 节 JDK 注释）。

## 3.2 TLS 1.2 的记录加密：三种模式与历史包袱

TLS 1.2 记录层支持三种加密模式（由套件的 `bulkCipher` 决定）：

1. **流密码**（RC4）：已淘汰；
2. **分组密码 CBC**：**MAC-then-encrypt**（先算 HMAC 再加密），历史包袱所在——解密方要先解密才能验 MAC，攻击者能通过"解密失败 vs 验 MAC 失败"的时间差发动**时序侧信道**（Lucky13，2013）。补救措施 encrypt-then-MAC 扩展（RFC 7366）出现太晚、部署太少，1.3 直接一刀切；
3. **AEAD**（AES-GCM 等）：1.2 的现代模式，记录布局为 `显式 nonce(8B) + ciphertext + tag(16B)`，MAC 不再单独存在。

1.2 完整性的一块基石与 1.3 相同：**每条记录一个单调递增的 64 位序号**，连同记录头一起进入 MAC/AAD 计算——重排、丢改、重放任何一条记录都会让校验失败。JDK 的序号实现是一个 8 字节数组 + 递增逻辑：

【源码证据】`sun/security/ssl/Authenticator.java` 第 37-44 行（类注释）与第 144-160 行（序号递增）：

```java
 * This class represents an SSL/TLS message authentication token,
 * which encapsulates a sequence number and ensures that attempts to
 * ...
    protected final byte[] block;   // at least 8 bytes for sequence number
```

```java
     * Increase the sequence number.
     ...
     * The sequence number in the block array is a 64-bit
     * unsigned number stored in big-endian format.
```

## 3.3 TLS 1.3 的记录加密：AEAD-only 与"构造 nonce"

TLS 1.3 记录层只有 AEAD 一种模式，并且把两处结构做得更彻底：

1. **类型藏进密文**：3.1 节说过，真正的 content type + padding 被加密在记录末尾，外层永远是 application_data；
2. **nonce 构造**：`per_record_nonce = static_write_iv XOR left_pad_0(sequence)`。static IV 从密钥调度里来（每方向一把），序号 64 位大端左补零——同一个 key 下序号永不重复，所以 nonce 永不重复。这与 1.2 的显式 nonce 方案不同，1.3 连显式 nonce 都省了，记录里只带 5 字节头 + 密文 + tag。

密文上限也收紧了：明文最大 2^14（16384）字节，1.3 密文最多再加 256 字节余量（1.2 是 +2048，留给 CBC padding 和显式 nonce）。这些常量在 JDK 里一眼可见：

【源码证据】`sun/security/ssl/Record.java` 第 40-48 行：

```java
interface Record {
    int    maxMacSize = 48;        // the max supported MAC or
                                   // AEAD tag size
    int    maxDataSize = 16384;    // 2^14 bytes of data
    int    maxPadding = 256;       // block cipher padding
    int    maxIVLength = 16;       // the max supported IV length

    int    maxFragmentSize = 18432;    // the max fragment size
                                       // 2^14 + 2048
```

## 3.4 分片与重组：应用写大块，线上走小包

两条规则（RFC 5246 §6.2.1 / RFC 8446 §5.1）：

- **一次 write → 多条记录**：应用写 1MB 数据，TLS 自动切成 64 条 16KB 记录；Netty 的 `SslHandler` 甚至会主动把多条小写合并成一次 `wrap()` 再切记录（第九章 9.3 节）；
- **一条记录 → 多条握手消息 / 一条握手消息跨多条记录**：握手消息理论上可跨记录，但 1.3 明确要求**握手消息不得与其他类型消息在记录内交错**（例外仅限 HelloRetryRequest 重试场景的 ClientHello）——这让"逐记录解密推进握手状态机"的实现成为可能。

## 本章小结

- 记录协议 = 分片 + 加密 + 认证 + 组帧；信封头 5 字节，1.3 的信封会"伪装"（版本恒 0x0303、类型恒 23），真实信息都在密文里。
- 序号是防重放/重排的隐形防线：1.2 进 MAC 输入，1.3 进 nonce 构造，思路相同——**任何一条记录的位置动了，解密就失败**。
- 1.2 → 1.3 的记录层演进：MAC-then-encrypt → AEAD-only；显式 nonce → 构造 nonce（IV XOR 序号）；类型明示 → 类型加密。
- 记录层加密用的密钥从哪来？下一章看 1.2 的答案：一次 2-RTT 的明文协商 + PRF。

---

# 四、TLS 1.2 握手：一切的地基

> 本章对应源码：`sun/security/ssl/SSLHandshake.java`（消息类型枚举）、`sun/security/ssl/SSLMasterKeyDerivation.java`（master secret）、`sun/security/ssl/Finished.java`（verify_data）、`sun/security/ssl/RSAClientKeyExchange.java`（RSA 密钥交换）。1.2 的握手在明文里完成，但它的**结构**（Hello → 证书 → 密钥交换 → Finished 四段式）是 TLS 一切的骨架——1.3 只是把同样的结构搬进了加密并加速，所以先读懂 1.2 再看 1.3，事半功倍。

## 4.1 握手消息格式与类型

握手消息共享同一个外壳（都封装在 type=22 的记录里）：

```
struct {
    uint8  msg_type;     // 消息类型
    uint24 length;       // 3 字节长度
    opaque body[length];
} Handshake;
```

JDK 把所有消息类型定义成一个枚举，编号与 RFC 5246 §7.4 完全一致：

【源码证据】`sun/security/ssl/SSLHandshake.java`（节选，行号 JDK 27）：

```java
    HELLO_REQUEST ((byte)0x00, "hello_request",          // L45
    CLIENT_HELLO  ((byte)0x01, "client_hello",           // L60
    SERVER_HELLO  ((byte)0x02, "server_hello",           // L75
    NEW_SESSION_TICKET          ((byte)0x04, "new_session_ticket",  // L129
    ENCRYPTED_EXTENSIONS        ((byte)0x08, "encrypted_extensions",// L150，1.3 用
    SERVER_HELLO_DONE           ((byte)0x0E, "server_hello_done",   // L238
    KEY_UPDATE                  ((byte)0x18, "key_update",          // L358，1.3 用
```

## 4.2 全流程：一次 (EC)DHE 套件的 2-RTT 握手

以 `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`（1.2 时代的主流套件）为例，`-->` 为明文，`{...}` 为已加密：

```
客户端                                              服务端
  │                                                    │
  │ ClientHello                                        │  ┐
  │  random(32B) / session_id / cipher_suites          │  │
  │  扩展: SNI, ALPN, supported_groups,                │  │ 第 1 个 RTT
  │        signature_algorithms, renegotiation_info    │  │ （明文）
  │ --------------------------------------------------▶│  │
  │                                     ServerHello    │  │
  │                     选定套件 / session_id / random │  │
  │                                     Certificate    │  │ 证书链（明文）
  │                              ServerKeyExchange     │  │ ECDHE 公钥 + 签名
  │                        CertificateRequest（可选）  │  ┘
  │                                 ServerHelloDone    │
  │◀--------------------------------------------------│
  │                                                    │
  │ ClientCertificate（可选，mTLS）                    │  ┐
  │ ClientKeyExchange                                  │  │ 第 2 个 RTT
  │           （ECDHE 公钥，或 RSA 加密的 premaster） │  │ 前 3 条明文
  │ CertificateVerify（可选，客户端证书时）           │  │
  │ ChangeCipherSpec      {开始用新密钥写}            │  │
  │ {Finished}            （MAC=PRF(master,…,hash)）   │  ┘
  │ --------------------------------------------------▶│
  │                                                    │
  │                     ChangeCipherSpec + {Finished}  │
  │◀--------------------------------------------------│
  │                                                    │
  │ {Application Data}（type=23，走记录协议）          │
  │◀------------------------------------------------▶│
```

三个设计要点：

1. **双方各贡献 32 字节随机数**（client_random / server_random），最终密钥是两者的函数——防重放（同一密钥不会用两次），也防"服务器用可预测随机数"的历史坑；
2. **ServerKeyExchange 只在 (EC)DHE 套件出现**：服务器的临时 ECDHE 公钥 + 用证书私钥对参数（曲线、公钥、两端 random）的签名。签名是身份与密钥交换的绑定点：中间人可以转发证书，但拿不出签名；
3. **ChangeCipherSpec（CCS）是"分水岭"**：发送方在此之后的所有记录立即用新密钥加密，Finished 是第一条加密消息，内容是对此前全部握手 transcript 的 PRF 校验值。

## 4.3 密钥推导：PRF 与 master secret

TLS 1.2 的密钥推导是"一锅端"式 PRF（基于套件哈希的 HMAC-P_hash）：

```
pre_master_secret                    # (EC)DHE 共享秘密 或 RSA 加密送达的 46B
master_secret(48B) = PRF(pre_master, "master secret",
                         client_random || server_random)
key_block = PRF(master_secret, "key expansion",
                server_random || client_random)
# key_block 依次切出：client_write_MAC_key / server_write_MAC_key /
#                     client_write_key / server_write_key / client_IV / server_IV
```

预共享一个细节：MAC key 在 1.2 的 AEAD 套件里长度为 0（完整性由 AEAD tag 承担）。JDK 里 master secret 的推导入口：

【源码证据】`sun/security/ssl/SSLMasterKeyDerivation.java` 第 111-141 行：

```java
            } else {
                if (protocolVersion.id >= ProtocolVersion.TLS12.id) {
                    masterAlg = "SunTls12MasterSecret";       // L113
                    hashAlg = cipherSuite.hashAlg;
                } else {
                    masterAlg = "SunTlsMasterSecret";
                    hashAlg = H_NONE;
                }
            }

            TlsMasterSecretParameterSpec spec;
            if (context.handshakeSession.useExtendedMasterSecret) {
                // reset to use the extended master secret algorithm
                masterAlg = "SunTlsExtendedMasterSecret";     // L124

                // For the session hash, use the handshake messages up to and
                // including the ClientKeyExchange message.
                context.handshakeHash.utilize();
                byte[] sessionHash = context.handshakeHash.digest();
                spec = new TlsMasterSecretParameterSpec(
                        preMasterSecret,
                        (majorVersion & 0xFF), (minorVersion & 0xFF),
                        sessionHash,                          // EMS：用 session hash
                        hashAlg.name, hashAlg.hashLength, hashAlg.blockSize);
            } else {
                spec = new TlsMasterSecretParameterSpec(
                        preMasterSecret,
                        (majorVersion & 0xFF), (minorVersion & 0xFF),
                        context.clientHelloRandom.randomBytes,
                        context.serverHelloRandom.randomBytes, // L140：双 random 做 seed
                        hashAlg.name, hashAlg.hashLength, hashAlg.blockSize);
            }
```

顺带看到 **extended master secret（EMS，RFC 7627）**：普通模式只用两端 random 当 seed，random 是明文的、握手本身没被绑定进去，某些三明治攻击（如 三重握手）正是钻这个空子；EMS 改用"到 ClientKeyExchange 为止的 transcript hash"，把 master secret 与整场握手绑定。RFC 8446 里 EMS 是唯一形态（1.3 的密钥调度天然全绑定）。

Finished 的校验值（verify_data）= `PRF(master_secret, "client finished" / "server finished", Hash(transcript))`，固定 12 字节：

【源码证据】`sun/security/ssl/Finished.java` 第 275-324 行（TLS 1.2 生成器，节选）：

```java
            String tlsLabel;
            if (useClientLabel) {
                tlsLabel = "client finished";                 // L291
            } else {
                tlsLabel = "server finished";
            }
            ...
                byte[] seed = handshakeHash.digest();
                String prfAlg = "SunTls12Prf";
                ...
                TlsPrfParameterSpec spec = new TlsPrfParameterSpec(
                    masterSecretKey, tlsLabel, seed, 12,      // L308：12 字节
                    hashAlg.name, hashAlg.hashLength, hashAlg.blockSize);
```

1.2 还有一个容易忽略的细节：**客户端发 Finished 前要发 CCS，服务端发 Finished 前也要发 CCS**——两个方向各自切换写密钥，互不影响读密钥。JDK 的 Finished 生产者里能看到 CCS 是被顺手一起发出去的：

【源码证据】`sun/security/ssl/Finished.java` 第 383-392 行：

```java
        private byte[] onProduceFinished(ClientHandshakeContext chc,
                HandshakeMessage message) throws IOException {
            // Refresh handshake hash
            chc.handshakeHash.update();

            FinishedMessage fm = new FinishedMessage(chc);

            // Change write cipher and delivery ChangeCipherSpec message.
            ChangeCipherSpec.t10Producer.produce(chc, message);   // L391
```

## 4.4 RSA 密钥交换及其命运

如果套件是 `TLS_RSA_WITH_…`，就没有 ServerKeyExchange/ClientKeyExchange 的 ECDHE 流程，取而代之：

1. 客户端自己生成 46 字节 premaster secret；
2. 用**服务器证书里的 RSA 公钥**加密，放进 ClientKeyExchange；
3. 服务器用私钥解密——premaster 从此只有两端知道。

这个设计的致命伤：**会话密钥与私钥直接相关，没有前向安全**。攻击者今天抓包存档，十年后拿到私钥（或法院责令提供），所有历史流量明文复活。此外 RSA 加密还有 Bleichenbacher 选择密文攻击（1998）：解密行为的差异（格式错/解密错）像一道神谕，可逐步剥离加密。服务端实现必须对所有错误"装作若无其事"，JDK 的注释就是这条防御的化石：

【源码证据】`sun/security/ssl/SSLMasterKeyDerivation.java` 第 152-153 行：

```java
                // For RSA premaster secrets, do not signal a protocol error
                // due to the Bleichenbacher attack. See comments further down.
```

【源码证据】`sun/security/ssl/RSAClientKeyExchange.java` 第 182-188 行（客户端侧 premaster 的生成与加密）：

```java
            RSAPremasterSecret premaster;
            ...
                premaster = RSAPremasterSecret.createPremasterSecret(chc);
                chc.handshakePossessions.add(premaster);
                ...
                        chc, premaster, publicKey);
```

TLS 1.3 的裁决：**RSA 密钥交换整体移除**，RSA 公钥只保留"签名"职能（证书链 + CertificateVerify）。今天的 1.2 部署也应优先选 `ECDHE_…_GCM` 套件、禁用 `TLS_RSA_WITH_…`。

## 4.5 会话恢复：session ID 与 session ticket

完整握手要 2-RTT + 一次非对称运算，对短连接是纯浪费。TLS 1.2 两条恢复路线：

| 方案 | 机制 | 状态存哪 | 特点 |
|---|---|---|---|
| session ID（RFC 5246） | 服务端在 ServerHello 里发 session_id，客户端复用时带上 | 服务端缓存 | 需要服务端存状态/做集群共享 |
| session ticket（RFC 5077） | 服务端把"会话状态加密打包"发给客户端，客户端存着下次原样带回 | 客户端（加密票据） | 服务端无状态，但必须有稳定的 ticket 加密密钥 |

恢复握手：ClientHello（带 session_id 或 ticket）→ ServerHello + ChangeCipherSpec + Finished，客户端回 CCS + Finished——**1 个 RTT**，premaster 直接从缓存的 master secret 来，无需证书与非对称运算。

恢复会带来一个经典的运维坑：多台服务器共用 ticket 密钥才能互相恢复；ticket 密钥长期不轮换，反而成了"比证书私钥还值钱"的资产（它使恢复会话可被解密）。

## 4.6 重协商：1.2 的可选项，1.3 的禁选项

TLS 1.2 允许在已建立的连接上**重新握手**（renegotiation）：服务端收到特定请求后发 HelloRequest，双方重走一遍握手、换一套密钥。初衷是"会话中途升级安全等级"，实际制造了 CVE-2009-3555（2009）：中间人把恶意前缀注入旧连接，新握手与旧应用数据在服务端视角"拼接"成功。修复是 RFC 5746 的 `renegotiation_info` 扩展（把新旧两次握手绑定），而 TLS 1.3 干脆移除重协商，"中途换密钥"的需求由 KeyUpdate 满足（只换密钥、不重新认证，见 5.7 节）。

JDK 的扩展表里能看到这条历史的两端：

【源码证据】`sun/security/ssl/SSLExtension.java` 第 505-514 行：

```java
    CH_RENEGOTIATION_INFO   (0xff01, "renegotiation_info",   // L505
    SH_RENEGOTIATION_INFO   (0xff01, "renegotiation_info",   // L514
```

Kafka 作为服务端实现则直接拒绝 1.2 之下的重协商（1.3 的 KeyUpdate 放行），注释写得明明白白：

【源码证据】`D:\code\3rd\kafka\clients\src\main\java\org\apache\kafka\common\network\SslTransportLayer.java` 第 600-608 行：

```java
                // reject renegotiation if TLS < 1.3, key updates for TLS 1.3 are allowed
                if (unwrapResult.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING &&
                        unwrapResult.getHandshakeStatus() != HandshakeStatus.FINISHED &&
                        unwrapResult.getStatus() == Status.OK &&
                        !sslEngine.getSession().getProtocol().equals(TLS13)) {
                    log.error("Renegotiation requested, but it is not supported, channelId {}, " +
                        "appReadBuffer pos {}, netReadBuffer pos {}, netWriteBuffer pos {} handshakeStatus {}", channelId,
                        appReadBuffer.position(), netReadBuffer.position(), netWriteBuffer.position(), unwrapResult.getHandshakeStatus());
                    throw renegotiationException();
                }
```

## 本章小结

- 1.2 握手四段式：**Hello（对齐版本/套件/随机数）→ 证书（验明正身）→ 密钥交换（premaster，RSA 或 ECDHE）→ Finished（transcript 校验 + 换密钥）**；完整握手 2-RTT，恢复 1-RTT。
- 密钥推导一锅端 PRF：`master_secret = PRF(pre_master, "master secret", CR‖SR)`；Finished 是对 transcript 的 PRF 校验值（12 字节）。
- RSA 密钥交换无前向安全 + Bleichenbacher 神谕，是 1.3 首先砍掉的对象；EMS 修的是"master secret 没绑定 transcript"的洞。
- 重协商 = 1.2 的安全债，RFC 5746 补丁 → 1.3 直接移除。
- 下一章：同样这套结构，1.3 如何把它压缩到 1-RTT、全部塞进加密、并换成 HKDF 密钥调度。

---

# 五、TLS 1.3 握手：更快、更安全、更激进

> 本章对应源码：`sun/security/ssl/ServerHello.java`（1.3 客户端密钥推导、HRR 消费）、`sun/security/ssl/RandomCookie.java`（HRR 魔数与降级哨兵）、`sun/security/ssl/SSLSecretDerivation.java`（HKDF 标签）、`sun/security/ssl/PreSharedKeyExtension.java` / `NewSessionTicket.java` / `KeyUpdate.java`（PSK 恢复与密钥更新）。RFC 8446 于 2018-08 定稿，是 TLS 20 年来最大的一次重构——同样的四段式结构，全部重新落地。

## 5.1 1-RTT 全流程：ServerHello 之后全是密文

```
客户端                                                  服务端
  │                                                       │
  │ ClientHello ──────────────── 明文 ──────────────────▶│  ┐
  │  key_share（本地预生成的 ECDHE 公钥）                 │  │ 1 个 RTT
  │  signature_algorithms / supported_versions           │  │ 明文只有
  │  psk_key_exchange_modes / pre_shared_key（恢复时）   │  │ 这两条
  │                                                      │  ┘
  │◀──────────────── ServerHello ─────── 明文 ───────────│
  │                    选定 key_share 中的 ECDHE 公钥    │
  │                 ──── 从这里起，记录全部加密 ────     │
  │◀──────── {EncryptedExtensions}（第一个加密消息）     │
  │◀──────── {Certificate}  证书链                       │
  │◀──────── {CertificateVerify} 用证书私钥签 transcript │
  │◀──────── {Finished}  HMAC(finished_key, transcript)  │
  │        ── 客户端此时已可验证服务端身份并发数据 ──    │
  │──────── {（Certificate/CertificateVerify，mTLS 时）}▶│
  │──────── {Finished} ─────────────────────────────────▶│
  │                                                      │
  │ {Application Data}（KeyUpdate/NST 等也混在这条流里）  │
  │◀───────────────────────────────────────────────────▶│
```

对比 1.2 的关键变化：

1. **密钥交换前置**：客户端在 ClientHello 里就带上 key_share（预猜的 ECDHE 公钥），服务端直接回自己的 share——省掉 1.2 的 ClientKeyExchange 一个来回，2-RTT 压到 1-RTT；
2. **证书、签名、扩展全部加密**（EncryptedExtensions 是 1.3 的新消息类型，`SSLHandshake.java` L150：`ENCRYPTED_EXTENSIONS ((byte)0x08, "encrypted_extensions"…`），明文里只剩两条 Hello；
3. **没有独立的 CCS**：换密钥由消息流本身驱动（服务端发完 ServerHello 即切换握手密钥，客户端收到 ServerHello 即切换；应用密钥在各自 Finished 之后切换）；
4. 客户端收到服务端 Finished 时握手即告完成——**它不需要等服务端再回什么就能发业务数据**。

客户端侧从 ServerHello 推导握手密钥、创建读密钥/写密钥的完整过程：

【源码证据】`sun/security/ssl/ServerHello.java` 第 1354-1384 行、第 1405-1415 行（JDK 27，客户端 T13 消费者）：

```java
            SSLKeyExchange ke = chc.handshakeKeyExchange;
            ...
            SSLKeyDerivation handshakeKD = ke.createKeyDerivation(chc);
            SecretKey handshakeSecret = handshakeKD.deriveKey(       // L1362
                    "TlsHandshakeSecret");
            SSLTrafficKeyDerivation kdg =
                SSLTrafficKeyDerivation.valueOf(chc.negotiatedProtocol);
            ...
            SSLKeyDerivation secretKD =
                    new SSLSecretDerivation(chc, handshakeSecret);   // L1374

            // update the handshake traffic read keys.
            SecretKey readSecret = secretKD.deriveKey(
                    "TlsServerHandshakeTrafficSecret");              // L1378

            SSLKeyDerivation readKD =
                    kdg.createKeyDerivation(chc, readSecret);
            SecretKey readKey = readKD.deriveKey("TlsKey");          // L1382
            IvParameterSpec readIv =
                    new IvParameterSpec(readKD.deriveData("TlsIv")); // L1384
```

```java
            chc.baseReadSecret = readSecret;                         // L1405
            chc.conContext.inputRecord.changeReadCiphers(readCipher);
            ...
            chc.baseWriteSecret = writeSecret;                       // L1436
            chc.conContext.outputRecord.changeWriteCiphers(
                    writeCipher, (serverHello.sessionId.length() != 0));
```

`TlsKey`/`TlsIv` 展开时的真实标签带 `tls13 ` 前缀（`SSLTrafficKeyDerivation.java` 第 192-193、199 行：`TlsKey("key")`、`TlsIv("iv")`、`this.label = ("tls13 " + label).getBytes()`），即 RFC 8446 §7.1 的 "tls13 key"/"tls13 iv"。

## 5.2 密钥调度：一张 HKDF 链图

RFC 8446 §7.1 的 key schedule（← 这张图值得背下来）：

```
        0
        |                                    PSK（恢复/0-RTT 时非空）
        v                                    v
  HKDF-Extract(0, 0) ──────────────► Early Secret
        |                                   │
        │   c e traffic ←── Derive-Secret ──┤  （0-RTT 数据的密钥）
        │   e exp master ←── Derive-Secret ─┤
        v                                   │
  Derive-Secret(., "derived", CH)           │
        |                                   │
        v                                   │
  HKDF-Extract(early, (EC)DHE) ────► Handshake Secret
        |                                   │
        │   c hs traffic ←── Derive-Secret ─┤  （客户端→服务端握手密钥）
        │   s hs traffic ←── Derive-Secret ─┤  （服务端→客户端握手密钥）
        v                                   │
  Derive-Secret(., "derived", CH…SH)        │
        |                                   │
        v                                   │
  HKDF-Extract(handshake, 0) ─────► Master Secret
        |
        │   c ap traffic ←── Derive-Secret ─  （应用数据密钥·客户端方向）
        │   s ap traffic ←── Derive-Secret ─  （应用数据密钥·服务端方向）
        │   exp master   ←── Derive-Secret ─  （exporter，应用层借用）
        └─ (客户端 Finished 之后) res master ←─ 恢复用主秘密
```

读图要点：

- **三次 Extract 串联**：每段密钥材料（PSK、(EC)DHE、0）被"搅拌"进一条不断分叉的链；前一段的输出经 `derived` 标签派生后成为下一段的 salt——**分段隔离**，握手密钥泄露不波及应用密钥，应用密钥泄露不波及恢复密钥；
- **每把 traffic secret 再经 `tls13 key`/`tls13 iv` Expand 成 AEAD 的 key 和 IV**（`SSLTrafficKeyDerivation`），5.1 节的 JDK 代码正是这条链上 "s hs traffic → TlsKey/TlsIv" 一段的实现；
- 1.2 的 `master_secret` 一把钥匙用到底（握手、应用、恢复全靠它），1.3 的密钥是**分身且单向演化**的。

## 5.3 HelloRetryRequest：服务端说"你猜错了，再来一次"

客户端预生成的 key_share 只覆盖它偏好的椭圆曲线；如果服务端不支持（比如客户端猜了 X25519、服务端只要 P-256），1.2 的做法是双方各自补发一轮——1.3 的做法是**服务端重发一条 ServerHello，指定新的群和新的 key_share 条目**，这就是 HelloRetryRequest（HRR）。

HRR 在线上看起来就是第二条 ServerHello，区别在 random：一个全 TLS 世界统一的魔数：

【源码证据】`sun/security/ssl/RandomCookie.java` 第 43-52 行（与 RFC 8446 §4.1.3 的值逐字节一致）：

```java
    private static final byte[] hrrRandomBytes = new byte[] {
            (byte)0xCF, (byte)0x21, (byte)0xAD, (byte)0x74,
            (byte)0xE5, (byte)0x9A, (byte)0x61, (byte)0x11,
            (byte)0xBE, (byte)0x1D, (byte)0x8C, (byte)0x02,
            (byte)0x1E, (byte)0x65, (byte)0xB8, (byte)0x91,
            (byte)0xC2, (byte)0xA2, (byte)0x11, (byte)0x16,
            (byte)0x7A, (byte)0xBB, (byte)0x8C, (byte)0x5E,
            (byte)0x07, (byte)0x9E, (byte)0x09, (byte)0xE2,
            (byte)0xC8, (byte)0xA8, (byte)0x33, (byte)0x9C
        };
```

客户端用 `isHelloRetryRequest()`（`RandomCookie.java` 第 113-115 行，`MessageDigest.isEqual` 常量时间比较）识别后，走 `ServerHello.java` 的 `t13HrrHandshakeConsumer`（第 1011 行）——补一个指定群的 key_share、重发 ClientHello。注意 HRR 让握手变成 2-RTT，所以现代实现都在客户端预置服务端常用的群来避免它。HRR 还有一个用途：DTLS 的 cookie 防 DoS（确认客户端地址可达后才分配状态）。

## 5.4 降级保护：藏在 random 里的哨兵

降级攻击（downgrade）指中间人或配置缺陷让双方"协商成"更老的版本/更弱的套件。1.3 的兜底：**支持 1.3 的服务器如果与客户端协商成了 1.2 或更低，必须在 ServerHello.random 的最后 8 字节写哨兵**——客户端若发现自己本支持 1.3 却收到 1.2 应答且哨兵在位，立即断链。

【源码证据】`sun/security/ssl/RandomCookie.java` 第 54-62 行、第 71-98 行：

```java
    private static final byte[] t12Protection = new byte[] {
            (byte)0x44, (byte)0x4F, (byte)0x57, (byte)0x4E,     // "DOWNGRD"
            (byte)0x47, (byte)0x52, (byte)0x44, (byte)0x01      // + 0x01
        };

    private static final byte[] t11Protection = new byte[] {
            (byte)0x44, (byte)0x4F, (byte)0x57, (byte)0x4E,     // "DOWNGRD"
            (byte)0x47, (byte)0x52, (byte)0x44, (byte)0x00      // + 0x00
        };
```

```java
        // TLS 1.3 has a downgrade protection mechanism embedded in the
        // server's random value.  TLS 1.3 servers which negotiate TLS 1.2
        // or below in response to a ClientHello MUST set the last eight
        // bytes of their Random value specially.                    // L75-78
        byte[] protection = null;
        if (context.maximumActiveProtocol.useTLS13PlusSpec()) {
            if (!context.negotiatedProtocol.useTLS13PlusSpec()) {
                if (context.negotiatedProtocol.useTLS12PlusSpec()) {
                    protection = t12Protection;
                } else {
                    protection = t11Protection;
                }
            }
        }
```

0x44 0x4F 0x57 0x4E 0x47 0x52 0x44 = ASCII **"DOWNGRD"**。客户端侧的检测在 `ServerHello.java` 第 1057-1060 行：

```java
            if (serverHello.serverRandom.isVersionDowngrade(chc)) {
                throw chc.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                    "A potential protocol version downgrade attack");
            }
```

注意哨兵只能防"支持 1.3 却被压到 1.2"的场景，防不了双方都被压到 1.2/1.1 以内——真正的解药是**服务端直接禁用老版本**（第十章展开整个降级攻击史）。

## 5.5 0-RTT（early data）：快但有代价

恢复会话时，客户端可以在 ClientHello 里就带上 `early_data` 扩展和**第一批加密的应用数据**（用 `c e traffic` 密钥加密）——服务器还没回话，数据已经上路，这就是 0-RTT。

代价是三重的：

1. **可重放**：0-RTT 数据没有新鲜性保证（没有服务端参与就没有 nonce 挑战），抓包重放整条 0-RTT 连接，服务器可能"傻傻再执行一次"。防线：PSK 票据单次使用（服务端记账）、`obfuscated_ticket_age` 时间窗校验、应用层幂等。浏览器只对"安全方法"（GET/HEAD 等）自动重发 0-RTT；
2. **弱前向安全**：纯 PSK 模式（无 DHE）下，0-RTT 密钥由 PSK 直接派生，PSK 泄露即历史明文复活；
3. **接受不确定**：服务端可以拒绝（回正常 1-RTT），客户端要用 END_OF_EARLY_DATA 消息划清"早期数据"与"正式数据"的边界。

**JDK 的现状值得注意**：JSSE 只声明了 `early_data` 扩展的三个位置，并未挂任何生产者/消费者——即**截至 JDK 27，JSSE 尚未实现客户端发送 0-RTT**（恢复会话支持 PSK，但不发早期数据）：

【源码证据】`sun/security/ssl/SSLExtension.java` 第 336-338 行（无 consumer/producer 参数，对照第 340 行起其他扩展的完整挂载）：

```java
    CH_EARLY_DATA           (0x002A, "early_data"),
    EE_EARLY_DATA           (0x002A, "early_data"),
    NST_EARLY_DATA          (0x002A, "early_data"),
```

## 5.6 会话恢复：NewSessionTicket + PSK + binder

1.2 的恢复状态是"服务端缓存里的 master secret"；1.3 把它重新设计成 **PSK（预共享密钥）**：

1. **握手完成后**，服务端随时下发 NewSessionTicket（可多条）：`ticket_lifetime(4B) + ticket(2B 长度前缀) + extensions`（`NewSessionTicket.java` 第 110-127 行有逐字段解析；扩展里的 `early_data` 携带 `max_early_data_size`，告知客户端"恢复时允许发多少 0-RTT"）；
2. **恢复时**，客户端在 ClientHello 的 `pre_shared_key` 扩展里带回 ticket（PSK identity）+ 混淆年龄，并附上 **binder**：对本条 ClientHello 前缀的 HMAC——防止中间人偷走 ticket 后改写 Hello 的其余部分（`PreSharedKeyExtension.java` 第 75-103 行定义 identity/obfuscatedAge/binders 结构）；
3. **psk_key_exchange_modes** 扩展（`SSLExtension.java` 第 400 行）声明恢复模式：`psk_ke`（纯 PSK，1-RTT、无 PFS）或 `psk_dhe_ke`（PSK + ECDHE，1-RTT 且保 PFS）——**生产环境应坚持 psk_dhe_ke**。

恢复密钥的来源在 key schedule 里已经就位：恢复会话的 PSK 从 `res master`（resumption master secret）派生，ext/res binder 的标签 `ext binder`/`res binder` 也早在 5.2 节那张标签表里等着。

## 5.7 配角们：KeyUpdate、post-handshake 认证与 dummy CCS

- **KeyUpdate**（消息类型 0x18）：握手完成后在线更新应用流量密钥——`update_not_requested`（我只是滚动更新）或 `update_requested`（你也立刻换）。长连接（如 Kafka 集群内通道）用它对抗"同一把对称密钥加密太久"。JDK 的定义（`KeyUpdate.java` 第 129-130 行）：
  ```java
        NOTREQUESTED        ((byte)0, "update_not_requested"),
        REQUESTED           ((byte)1, "update_requested");
  ```
  但 QUIC 场景明确禁用（`KeyUpdate.java` 第 273 行注释：`Quic doesn't allow KEY_UPDATE TLS message. It has its own Quic specific…`——QUIC 的包保护有自己的 key phase 机制，8.8 节）；
- **post-handshake 客户端认证**：1.3 允许握手完成后随时补发 CertificateRequest 要求客户端证书。注意它与 HTTP/2 不兼容（RFC 8740 明确禁止 h2 连接上做 post-handshake 认证），所以 mTLS 的 gRPC/HTTP2 服务要求在**初始握手**就完成认证；
- **dummy ChangeCipherSpec**：1.3 自己不用 CCS，但为了穿过"见到 1.3 记录就掐线"的老中间盒，握手期间会插入内容为空的 CCS 记录当"烟幕弹"。JDK 的注释直说它是 dummy：

【源码证据】`sun/security/ssl/ServerHello.java` 第 1457-1459 行：

```java
            // update the consumers and producers
            //
            // The server sends a dummy change_cipher_spec record immediately
```

对照 1.2：同样的 CCS 在 `Finished.java` 第 391 行是真枪实弹的"切换写密钥"动作（4.3 节）。**同一个字节序列，1.2 是开关，1.3 是道具**——这就是 1.3 兼容性设计的全部精髓。

## 5.8 TLS 1.2 vs 1.3 对照表

| 维度 | TLS 1.2（RFC 5246） | TLS 1.3（RFC 8446） |
|---|---|---|
| 完整握手 RTT | 2-RTT | 1-RTT（HRR 时 2-RTT） |
| 恢复握手 | session ID / ticket，1-RTT | NewSessionTicket + PSK + binder，1-RTT；可选 0-RTT |
| 密钥交换 | RSA 传输 / (EC)DHE（可选） | (EC)DHE / PSK（强制，RSA 移除） |
| 前向安全 | 取决于套件 | 强制（psk_dhe_ke / 纯 DHE） |
| 密钥推导 | PRF 一把 master secret | HKDF 三段 Extract 链，密钥分身 |
| 握手消息可见性 | 全部明文（CCS 后才加密） | ServerHello 后全部加密（证书/签名/扩展） |
| 套件数量 | 几十个（含 CBC/RC4/RSA） | **5 个**：TLS_AES_128_GCM_SHA256(0x1301)、TLS_AES_256_GCM_SHA384(0x1302)、TLS_CHACHA20_POLY1305_SHA256(0x1303)、TLS_AES_128_CCM_SHA256(0x1304)、TLS_AES_128_CCM_8_SHA256(0x1305) |
| 重协商 | 允许（带 RFC 5746 补丁） | 移除（换密钥用 KeyUpdate） |
| 降级防护 | 靠套件优先级配置 | supported_versions + "DOWNGRD" random 哨兵 |
| 记录层版本字段 | 实际版本 | 恒 0x0303（兼容中间盒） |
| CCS 消息 | 真实的密钥切换开关 | dummy 烟幕弹 |

1.3 套件在 JDK 里是明晃晃的五个枚举（0x1301 与 0x1303）：

【源码证据】`sun/security/ssl/CipherSuite.java` 第 68-72 行：

```java
    TLS_AES_128_GCM_SHA256(
            0x1301, true, "TLS_AES_128_GCM_SHA256",             // L68-69
    ...
    TLS_CHACHA20_POLY1305_SHA256(
            0x1303, true, "TLS_CHACHA20_POLY1305_SHA256",       // L71-72
```

## 本章小结

- 1.3 的提速来自**密钥交换前置**（ClientHello 带 key_share），安全来自**全加密握手 + transcript 全绑定 + 强制 PFS**，兼容来自**伪装（版本字段/CCS）**。
- 密钥调度是一张 HKDF 链：early → handshake → master 三段 Extract，`c/s hs traffic`、`c/s ap traffic`、`res master` 等分身各司其职；JDK 的 `SecretSchedule` 标签表就是这张图的代码形态。
- HRR 用魔数 random 标识自己，DOWNGRD 哨兵防降级——**TLS 1.3 把"协议级元信息"藏进 random 的设计思路**（random 是明文里唯一双方都无法伪造意义的 32 字节空间）值得体会。
- 0-RTT 的重放风险决定了它的应用边界（幂等请求），JDK 截至 27 未实现客户端 0-RTT。
- 握手之后，所有协议数据都变成了"记录流"。接下来的问题是：TLS 怎么嵌进 HTTP、Kafka、Redis 这些千差万别的协议里？——先看全景（第七章），再逐个拆流程（第八章）。

---

# 六、身份认证：证书、信任链与 mTLS

> 本章对应源码：`sun/security/ssl/X509TrustManagerImpl.java`（信任管理入口）、`sun/security/ssl/CertificateVerify.java`（签名绑定 transcript）、`sun/security/ssl/SSLExtension.java` 的 SNI 相关配置。加密解决"别人看不懂"，身份认证解决"对面真的是它"——没有后者的加密只是给中间人做了嫁衣。

## 6.1 X.509 证书里有什么

一张站点证书（RFC 5280）≈ 三段：**待签名的信息（TBSCertificate）+ 签名算法 + CA 的签名**。TBSCertificate 里的关键字段：

| 字段 | 内容 | TLS 用它干什么 |
|---|---|---|
| Subject / Issuer | 主体名 / 签发者名 | 沿链攀爬的线索 |
| Validity | notBefore / notAfter | 过期即拒 |
| SubjectPublicKeyInfo | 主体公钥 + 算法 | 握手签名/密钥交换用它 |
| **扩展 SAN** | subjectAltName（域名列表） | **hostname 验证的唯一依据**（CN 已废弃） |
| 扩展 BasicConstraints | CA=TRUE/FALSE + pathLen | 限定"谁有资格当 CA" |
| 扩展 EKU | serverAuth / clientAuth | 限定证书用途 |
| 扩展 SKI/AKI | 密钥标识符 | 链构建加速 |
| 扩展 AIA | OCSP/CRL 访问点 | 吊销检查（6.4） |

内网自建 TLS（Kafka/ES/Redis 常见做法）时，管理员用 `openssl`/`keytool` 自签一个"根 CA"并分发到客户端信任库——**证书格式的世界是同一个，变的只是"谁当根"**。

## 6.2 证书链验证：从叶子爬到信任锚

TLS 握手中的 Certificate 消息是一条**有序证书链**：第一张是站点自己的（叶子），后面是中间 CA，通常不含根（根在客户端信任库里）。客户端的验证工作：

1. **逐级验签**：用链条下一张证书的公钥验证上一张的签名；
2. **逐级查字段**：有效期、EKU（服务器证书必须是 serverAuth）、BasicConstraints（中间 CA 必须 CA=TRUE）、keyUsage；
3. **终点必须是信任锚**：本地 TrustStore 里的自签根；
4. **吊销检查**（6.4）与 **hostname 匹配**（6.3）。

JDK 的入口是 TrustManager——服务器证书链进来，通过则继续握手，否则握手当场 fatal：

【源码证据】`sun/security/ssl/X509TrustManagerImpl.java` 第 83-92 行、第 107-109 行：

```java
    X509TrustManagerImpl(String validatorType, PKIXBuilderParameters params) {  // L83
        ...
    }

    private X509Certificate[] checkTrusted(X509Certificate[] chain, ...) {
        Validator v = getValidator(Validator.VAR_TLS_SERVER);        // L90
        serverValidator = v;
        ...
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType)  // L107
```

`Validator` 内部走 JCA 的 PKIX 路径构建（`PKIXBuilderParameters`）——即"从叶子到锚点找一条每级都合格的路径"，找不到就抛异常终止握手。

## 6.3 hostname 验证与 SNI：证书必须"叫对名字"

证书链验证只回答"这张证书是我信任的 CA 签的"，不回答"这张证书属于我要访问的主机"——后者由 **hostname 验证**完成：把连接目标（或 SNI 指定名）与证书 SAN 的 DnsName 匹配，通配符 `*.example.com` 只匹配一层。跳过这一步（信任所有证书、或只验链不验名）是内网服务最常见的安全事故：攻击者只要申请到一张**任何域名的合法证书**就能实施 MITM。

**SNI（Server Name Indication，RFC 6066）**解决的是"一台服务器/一个 IP 上托管多套证书"：客户端在 ClientHello 明文里带上目标主机名，服务端按名选证书。它是明文扩展，有隐私争议（ECH 加密 ClientHello 正在解决），但也是 CDN/网关按域名分流的基础。JDK 提供开关：

【源码证据】`sun/security/ssl/SSLExtension.java` 第 765-768 行：

```java
            // Switch off SNI extension?
            ...
                "jsse.enableSNIExtension", true);        // L768：默认开启
```

## 6.4 吊销：为什么"作废证书"这么难

证书签错了/私钥丢了，怎么办？理论答案漂亮，工程答案尴尬：

| 机制 | 做法 | 现实困境 |
|---|---|---|
| CRL | CA 定期发布"黑名单"全量列表 | 列表巨大、有窗口期，客户端很少真下载 |
| OCSP（RFC 6960） | 客户端实时问 CA"这张还有效吗" | 增加握手延迟、CA 知道你在访问谁（隐私） |
| OCSP Stapling（RFC 6066/6961） | 服务端定期把 CA 签名的"有效证明"捎带在握手里 | 部署率低；staple 缺失时多数客户端 soft-fail |

**soft-fail** 是行业的真实默认：吊销状态拿不到就当有效——因为硬 fail 一次 CA 的 OCSP 故障就能瘫痪半个互联网。业界趋势是用**短有效期证书**（90 天，ACME 自动续期）替代吊销：让"作废"变成"等它自己过期"。

## 6.5 CertificateVerify：把身份签进这场握手

证书链证明了"服务器拥有这个域名和这把私钥"，但还没证明"**这场握手的密钥协商**也是同一把私钥参与的"——中间人理论上可以原样转发证书再偷换 key exchange 参数（1.2 的 ServerKeyExchange 签名、1.3 的 CertificateVerify 都是堵这个洞的）。

TLS 1.3 把签名内容固定为：`transcript_hash + "TLS 1.3, " + 方向 + " CertificateVerify"`（一段固定前缀字符串防跨协议/跨方向重放）。JDK 的实现签名/验签各一段：

【源码证据】`sun/security/ssl/CertificateVerify.java` 第 83-96 行（服务端产出签名）与第 141-146 行（客户端验证）：

```java
                byte[] hashes = chc.handshakeHash.digest(algorithm,   // L83
                        chc.handshakeExtensions.get(SSLExtension.CH_SIGNATURE_ALGORITHMS));
                ...
                this.signature = temporary;                           // L96
```

```java
            // read and verify the signature                            // L121
            this.signature = Record.getBytes16(m);
            ...
                byte[] hashes = shc.handshakeHash.digest(algorithm,    // L141
                ...
                if (!signer.verify(signature)) {                       // L144
                    throw shc.conContext.fatal(Alert.HANDSHAKE_FAILURE,
                        "Invalid CertificateVerify message: invalid signature");
                }
```

`handshakeHash` 就是贯穿全程的 transcript——**证书、签名、密钥交换从此焊死在同一场协商里**。1.2 中 ServerKeyExchange/CertificateVerify 签的也是"截至当前的握手消息哈希"，思想相同，只是 1.3 把它标准化成了固定的 context 字符串。

## 6.6 mTLS：双向认证

默认只有客户端验服务器。**mTLS（双向 TLS）**让服务端也发 CertificateRequest 要求客户端证书：

- 1.2：CertificateRequest 出现在服务端消息束里（明文），客户端回 ClientCertificate + CertificateVerify；
- 1.3：CertificateRequest 在**加密后**（EncryptedExtensions 之后）下发，客户端证书同样藏进密文——`SSLHandshake.java` 的 `CERTIFICATE_REQUEST` 与 `CertificateRequest.java` 的 T12/T13 两套消息实现（T12 在第 438 行起）正是两代协议各一套的体现；
- 证书用途要选对：客户端证书的 EKU 必须含 **clientAuth**。

mTLS 的用武之地：服务间调用（Kafka broker↔client↔broker 全链路、gRPC 服务网格的默认互信方式）、运维通道（etcd、Elasticsearch 节点间）、支付/开放平台的回调接口。它的信任模型是"私有 CA"——平台自己当根，只给自家服务签证书，天然与互联网公证书体系隔离。

## 本章小结

- 认证三件套：**证书链**（CA 背书公钥）+ **CertificateVerify**（私钥绑定本场握手）+ **hostname 匹配**（名字对得上）；三者缺一不可，"跳过任何一个"都是内网事故的 Top 来源。
- SNI 是明文的按名选证书机制，是 CDN 的地基也是隐私争议点。
- 吊销在工程上接近失败（soft-fail 是常态），短期证书是事实上的解药。
- mTLS = 把服务器的认证义务复制一份给客户端，1.3 下客户端证书也进密文；服务网格把它变成了基础设施默认值。

---

# 七、TLS 可以用在哪些协议中：全景与三种接入模式

> 前六章讲"TLS 自己是什么"，从本章起回答"它怎么服务别的协议"。TLS 的设计目标是**协议无关**——只要承载它的是一条可靠的字节流（或它自己改造 DTLS 去 UDP 上凑一条），任何应用协议都能受益。差异只在于：**TLS 从连接的第几个字节开始**。

## 7.1 三种接入模式

| 模式 | 接法 | 谁决定切换 | 明文里泄露什么 | 代表协议 |
|---|---|---|---|---|
| **A 端口级直包**（implicit TLS） | 连接的**第一个字节**就是 ClientHello，应用协议整体套在 TLS 里面 | 没有协商——端口即协议约定 | SNI、两端 IP/端口 | HTTPS 443、Kafka SSL、Redis tls-port、MQTT 8883、ldaps 636 |
| **B 明文升级**（STARTTLS / 协议内协商） | 先明文跑一段应用协议，双方"约定一个动作"后**原地切换**到 TLS，切换后继续原协议的会话状态 | 应用协议自己的命令/标志位 | 协议能力清单、主机名、账户名（视协议而定） | SMTP STARTTLS、IMAP/POP3 STARTTLS、LDAP StartTLS、XMPP、PostgreSQL/MySQL 的 SSLRequest |
| **C 内嵌/重构** | TLS 的**组件被拆开**嵌入其他协议：1.3 握手保留、记录层由宿主协议替代 | 宿主协议 | 视宿主而定 | QUIC/HTTP3（记录层→包保护）、DTLS（TCP→UDP 改造） |

模式 A 是历史最短但现状最主流的形态——2018 年 RFC 8314 甚至推动了邮件协议从 B 回归 A（8.9 节）。模式 B 的存在原因主要是**历史端口复用**（143/587/389 上已有明文流量，不可能另开端口时，升级是唯一迁移路径）和**渐进部署**（服务端还没准备好 TLS 时客户端能优雅回退——但这个"优雅"正是降级攻击的温床，见 8.2）。模式 C 则是性能驱动的新一代设计（8.8 节专章展开）。

## 7.2 协议全景表

**模式 A（端口级直包）：**

| 协议 | 端口 | 备注 |
|---|---|---|
| HTTPS (HTTP/1.1、HTTP/2) | 443 | RFC 2818；h2 由 ALPN 协商（8.1） |
| HTTP/3 / QUIC | 443/UDP | 模式 C 的代表（8.8） |
| WebSocket (wss://) | 443 | 先 TLS 后 HTTP Upgrade，本质还是 A（8.1） |
| SMTP 隐式 TLS (smtps) | 465 | RFC 8314 推荐（8.9） |
| IMAPS / POP3S | 993 / 995 | — |
| FTPS（隐式） | 990 | 控制通道；数据通道 989 |
| LDAPS | 636 | LDAP over TLS，非官方标准的"事实端口" |
| XMPP（旧式隐式） | 5223 | 现代 XMPP 用 5222+STARTTLS |
| MQTT | 8883 | IoT 主力（8.6） |
| AMQP (amqps) | 5671 | RabbitMQ/Tencent/RocketMQ 生态 |
| Kafka SSL | 自定义（常 9093） | listener 级配置（8.4） |
| Redis TLS | 自定义（tls-port） | 6.0+ 原生支持（8.5） |
| MongoDB | 27017（tls=true） | 端口复用的 A 模式 |
| DNS over TLS | 853 | RFC 7858 |
| NTP Security (NTS) | 4460 | RFC 8915，TLS 只做密钥协商，时间同步走 NTS-KE |
| syslog over TLS | 6514 | RFC 6514 |
| SIP over TLS | 5061 | VoIP 信令 |

**模式 B（明文升级）：**

| 协议 | 端口 | 升级动作 | 规范 |
|---|---|---|---|
| SMTP（提交/中继） | 587 / 25 | `STARTTLS` 命令 | RFC 3207 |
| IMAP / POP3 | 143 / 110 | `STARTTLS` 命令 | RFC 2595 |
| LDAP | 389 | StartTLS Extended Operation（OID 1.3.6.1.4.1.1466.20037） | RFC 4511 §4.14 |
| XMPP | 5222 | `<starttls/>` 流特性 | RFC 6120 |
| FTP | 21 | `AUTH TLS` | RFC 4217 |
| NNTP | 119 | `STARTTLS` | RFC 4642 |
| PostgreSQL | 5432 | 启动序列 SSLRequest 包（code 80877103） | PG 协议文档 |
| MySQL | 3306 | 握手包能力位 CLIENT_SSL (0x0800) + SSLRequest 包 | MySQL 协议文档 |
| RDP | 3389 | X.224 连接请求里的 TLS 能力协商 | MS-RDPBCGR |

**模式 C（内嵌/重构）+ 常见误认：**

| 协议 | 与 TLS 的关系 |
|---|---|
| QUIC / HTTP/3 | **1.3 握手协议被完整嵌入**，记录层被 QUIC 包保护取代（8.8） |
| DTLS | TLS 的 UDP 适配版（RFC 9147 是 DTLS 1.3），CoAP/IoT 常用 |
| **SFTP** | 与 TLS 无关——它跑在 SSH（自带完整加密体系）之上，名字里的 S 是 Secure 不是 SSL |
| **WireGuard / SSH / Signal** | 各用各的密码协议（Noise/自研），不用 TLS |
| **HTTPS 代理流量** | 代理只看到 CONNECT 隧道，隧道内容仍是完整 TLS（8.1） |

## 7.3 怎么选：一张决策图

```
能否占用一个新端口/新端口已约定？
├── 能  → 模式 A（implicit TLS）：最简单、无降级面、调试直观
│         （反例风险：老中间盒按端口白名单放行，注意企业代理）
└── 不能（老端口存量明文用户）
    ├── 客户端可控（如自家数据库驱动）→ 协议内协商（PG/MySQL 式）：
    │     切换点精确、失败可回退，但要防"静默回退"
    └── 生态碎片化（邮件/聊天）→ STARTTLS：机会式升级
          必须配强制策略（MTA-STS/HSTS）否则等于没升
```

## 本章小结

- TLS 本身不在乎里面跑什么协议——**接入模式的本质是"切换点放在哪"**：放在端口约定（A）、放在应用命令（B）、还是干脆拆开重组（C）。
- 面向新系统：默认模式 A；存量老协议：模式 B + 强制策略；追求单 RTT 极致与拥塞控制革新：模式 C（QUIC）。
- 下一章把表里的重点协议逐个拆开，画出每一家的 TLS 处理流程。

---

# 八、各协议中的 TLS 处理流程（逐协议深挖）

> 本章对应源码：JDK HttpClient（`java.net.http/jdk/internal/net/http/AbstractAsyncSSLConnection.java`、`AsyncSSLTunnelConnection.java`）、Kafka（`clients/src/main/java/org/apache/kafka/common/network/SslTransportLayer.java`）、Redis（`src/tls.c`）、JDK 的 QUIC-TLS 支持（`sun/security/ssl/QuicTLSEngineImpl.java`、`QuicTransportParametersExtension.java`）。PostgreSQL/MySQL 的流程依据各自官方协议文档（本地无源码）。

## 8.1 HTTPS：最主流的模式 A

**基本流程（TLS 1.3）：**

```
客户端                                       服务端 (443)
  │  TCP 三次握手（1-RTT）                     │
  │◀────────────────────────────────────────▶│
  │                                           │
  │ ClientHello [SNI=api.example.com]         │   ← 告诉网关要哪张证书
  │            [ALPN=h2, http/1.1]            │   ← 告诉服务器要跑哪个协议
  │            [key_share=…]                  │
  │◀──────────────────────────────────────────│   ServerHello + {证书/Finished…}
  │ {Finished} ──────────────────────────────▶│
  │                                           │
  │ {GET / HTTP/1.1\r\nHost: …}               │   ← HTTP 报文按 16KB 记录切分加密
  │◀─ {HTTP/1.1 200 OK …} ───────────────────│
  │           （keep-alive：同一条 TLS 连接继续跑 N 个请求）
  │ close_notify ────────────────────────────▶│   ← Alert 类型，优雅关闭
```

四个 HTTPS 特有的机制：

1. **SNI 决定证书**：CDN/网关同一 IP 挂 N 个站点，靠 ClientHello 里的主机名选证书（6.3 节）；
2. **ALPN 决定上层协议**：TLS 握手里顺带完成"HTTP/2 还是 HTTP/1.1"的协商，省去明文时代的 `Upgrade` 头。JDK 扩展表里 ALPN 在三个握手消息上各有一个位置（CH/SH/EE）：

【源码证据】`sun/security/ssl/SSLExtension.java` 第 186-204 行：

```java
    CH_ALPN                 (0x0010, "application_layer_protocol_negotiation",  // L186
    SH_ALPN                 (0x0010, "application_layer_protocol_negotiation",  // L195
    EE_ALPN                 (0x0010, "application_layer_protocol_negotiation",  // L204
```

3. **HTTP/2 的强制绑定**：RFC 9113 §3.3 要求浏览器场景的 h2 必须"TLS 1.2+ 且 ALPN 协商出 h2"，明文 h2c 在浏览器被禁——协议升级与加密绑定后，"降级到 1.1"这类协商歧义彻底消失；
4. **wss:// 就是它**：WebSocket over TLS = 先按 HTTPS 建立连接，再在加密通道里跑 `Upgrade: websocket`——TLS 之下 HTTP 与 WS 没有任何区别。

**过代理时的流程（CONNECT 隧道）**：HTTP 代理看到的是"明文 CONNECT + 域名"，之后一切字节原样转发，TLS 照常从隧道里跑：

【源码证据】`java.net.http/jdk/internal/net/http/AsyncSSLTunnelConnection.java` 第 38-40 行、第 55-56 行：

```java
 * An SSL tunnel built on a Plain (CONNECT) TCP tunnel.

class AsyncSSLTunnelConnection extends AbstractAsyncSSLConnection {
    ...
        this.plainConnection = new PlainTunnelingConnection(originServer, addr, proxy, client,
                proxyHeaders, label);
```

**JDK HttpClient 内部形态**：每条 HTTPS 连接一个 `SSLEngine`，被包进响应式流水线（`SSLTube`），握手状态机的驱动与数据搬运全部异步化——这也是 9.1 节"SSLEngine 是为 NIO 而生"的最好例证：

【源码证据】`java.net.http/jdk/internal/net/http/AbstractAsyncSSLConnection.java` 第 70 行、第 90-96 行：

```java
    protected final SSLEngine engine;
    ...
        engine = createEngine(context, serverName.name(), originServer.port(), sslParameters); // L90
    ...
    abstract SSLTube getConnectionFlow();                    // L93
    ...
    final CompletableFuture<String> getALPN() {              // L95
        return getConnectionFlow().getALPN();
    }
```

## 8.2 STARTTLS 家族：先明文、后升级（以 SMTP 为例）

```
客户端                                             服务端 (587)
  │◀──── 220 mail.example.com ESMTP ────────────────│   明文 banner
  │────── EHLO client.example.com ─────────────────▶│
  │◀──── 250-mail.example.com … 250-STARTTLS … ─────│   能力清单里有 STARTTLS
  │────── STARTTLS ────────────────────────────────▶│
  │◀──── 220 Ready to start TLS ────────────────────│
  │═══════ TLS 握手（完整四段式） ═══════════════════│
  │────── EHLO client.example.com（必须重发！） ────▶│   RFC 3207 §4
  │◀──── 250-AUTH PLAIN … ──────────────────────────│   新能力清单（加密后）
  │────── AUTH LOGIN / MAIL FROM … ────────────────▶│
```

三个要点：

1. **切换后协议状态要"重置"**：RFC 3207 要求客户端丢弃 TLS 前获得的知识并**重发 EHLO**——服务器在加密后给出的能力（如 AUTH）才是可信的。LDAP 的 StartTLS（Extended Operation）与 IMAP 的 STARTTLS 同样要求各自"回到初始态"；
2. **机会式安全的困境**：SMTP 客户端通常"对方支持就升，不支持就明文发"——中间人只需在 250 能力清单里**抹掉 STARTTLS**，就把通信打回明文（TLS stripping）。解药是**把"必须加密"写进带外策略**：邮件领域的 MTA-STS（RFC 8460，用 HTTPS 发布策略）与 DANE（RFC 7672，DNSSEC 签名的 TLSA 记录）；Web 领域的对应物是 HSTS；
3. **升级前明文段泄露多少，取决于协议**：SMTP 会暴露 EHLO 主机名与能力清单；PostgreSQL/MySQL 的协商包（8.3）几乎不暴露业务信息——所以"协议内协商"比"独立命令"暴露面更小。

## 8.3 数据库的协议内协商：PostgreSQL 与 MySQL

PG/MySQL 没有另开 TLS 端口，而是把 TLS 开关做进自己的协议握手包（模式 B 的变体）。

**PostgreSQL（5432，协议文档 SSL Support 一节）：**

```
客户端                                        服务端
  │────── SSLRequest: Int32(8) + Int32(80877103) ──▶│   特殊启动包
  │◀───── 'S'（单字节：同意） ───────────────────────│   'N' 则拒绝
  │═══════ TLS 握手 ════════════════════════════════│
  │────── StartupMessage（用户名/数据库） ─────────▶│   从此全部加密
  │◀───── Authentication / ReadyForQuery ───────────│
```

80877103 = 0x04D2162F，是一个刻意选的"魔法数"；先问后连，客户端拿到 'N' 可以自行决定回退还是报错（JDBC 的 `sslmode=require` 就是在这里强制失败）。

**MySQL（3306，协议文档 Connection Phase 一节）：**

```
客户端                                        服务端
  │◀───── Initial Handshake Packet（含能力位） ─────│   SERVER_CAPABILITIES 里带 CLIENT_SSL(0x0800)
  │────── SSLRequest Packet（能力位 |= CLIENT_SSL）▶│   与认证包同构、不带凭据
  │═══════ TLS 握手 ════════════════════════════════│
  │────── HandshakeResponse（用户名+认证数据） ────▶│   凭据永远不落明文
  │◀───── Auth 切换 / OK ───────────────────────────│
```

MySQL 的设计比 PG 更进一步：**认证响应（密码哈希）必须走加密通道**；`caching_sha2_password` 的 full authentication 在无 TLS 时会退化到"向服务器要 RSA 公钥加密密码"的方案——TLS 可用性直接决定认证链路的强度。连接器侧的 `sslmode`/`verifyServerCertificate` 语义与 PG 对齐（REQUIRED 只加密，VERIFY_IDENTITY 才验证书名）。

**与 STARTTLS 的差别**：切换点是协议包的"标志位"而非独立命令，且**切换失败的行为由客户端策略显式决定**（配置 REQUIRED 就失败，绝不静默明文重连）——这正是对"机会式回退"的制度性修正。

## 8.4 Kafka：端口级直包 + 分层安全模型

Kafka 的 TLS 是**纯模式 A**：每个 broker 配置多个 listener，listener 与安全协议绑定（`PLAINTEXT` / `SSL` / `SASL_SSL` / `SASL_PLAINTEXT`），客户端连哪个端口就用哪种安全层。分层视角：

```
┌────────────────────────────────────────────┐
│ 应用协议（Produce/Fetch 请求、元数据）      │
├────────────────────────────────────────────┤
│ SASL（SASL_SSL 时：SCRAM/OAUTHBEARER…）    │ ← 认证层，跑在 TLS 之上
├────────────────────────────────────────────┤
│ TLS（SslTransportLayer）                   │ ← 加密+服务端/客户端认证
├────────────────────────────────────────────┤
│ TCP                                        │
└────────────────────────────────────────────┘
```

TLS 层的实现是 NIO 直驱 `SSLEngine` 的教科书案例——`handshake()` 读到的网络字节先进引擎，引擎状态机要求写就注册 OP_WRITE：

【源码证据】`D:\code\3rd\kafka\clients\src\main\java\org\apache\kafka\common\network\SslTransportLayer.java` 第 58 行、第 280-302 行、第 335-341 行：

```java
public class SslTransportLayer implements TransportLayer {
    ...
    public void handshake() throws IOException {                       // L280
        if (state == State.NOT_INITIALIZED) {
            try {
                startHandshake();
            } catch (SSLException e) {
                maybeProcessHandshakeFailure(e, false, null);
            }
        }
        if (ready())
            throw renegotiationException();                            // L289
        ...
            doHandshake();                                             // L302
```

```java
    private void doHandshake() throws IOException {                    // L335
        boolean read = key.isReadable();
        boolean write = key.isWritable();
        handshakeStatus = sslEngine.getHandshakeStatus();
        if (!flush(netWriteBuffer)) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE); // NIO 写不下→注册写事件
            return;
        }
```

工程要点：

- **mTLS 即配置**：`ssl.client.auth=required` + 客户端证书 = 服务间强认证，Kafka 集群内通信与 ACL 联动常用；
- **主机名验证默认开启**：`endpoint.identification.algorithm=HTTPS`（默认值），即按 6.3 节规则验证书 SAN——很多内网故障的根因是"自签证书没配 SAN"，解法不是关验证，而是签证书时写对 SAN；
- **SASL_SSL 的含义**：TLS 负责"加密 +（可选）证书认证"，SASL 负责"应用层身份认证"（SCRAM/PLAIN/OAUTHBEARER）——两层职责清晰分离，PLAIN 在 TLS 内传输才安全。

## 8.5 Redis：把 TLS 做成一种"连接类型"

Redis 6.0（2020）开始原生支持 TLS，其实现形态对理解"协议如何接 TLS"很有启发：Redis 把每个客户端连接抽象为 `ConnectionType`，TLS 就是与明文并列的一个类型——**同一实例可以同时监听明文端口和 TLS 端口**（例如 `port 6379` 与 `tls-port 6380` 并存，或 `port 0` 只开 TLS），主从复制、集群总线、哨兵也都各自有 `tls-replication`/`tls-cluster` 开关。

【源码证据】`D:\code\3rd\redis\src\tls.c` 第 1315-1360 行（连接类型虚表，节选）：

```c
static ConnectionType CT_TLS = {
    /* connection type */
    .get_type = connTLSGetType,

    /* connection type initialize & finalize & configure */
    .init = tlsInit,
    ...
    /* ae & accept & listen & error & address handler */
    .ae_handler = tlsEventHandler,
    .accept_handler = tlsAcceptHandler,
    ...
    /* connect & accept */
    .connect = connTLSConnect,
    ...
    /* IO */
    .read = connTLSRead,
    .write = connTLSWrite,
    .writev = connTLSWritev,
```

握手驱动是经典的 OpenSSL 阻塞/非阻塞混合模型——客户端连接调 `SSL_connect`、服务端 accept 调 `SSL_accept`：

【源码证据】`D:\code\3rd\redis\src\tls.c` 第 728 行、第 753 行：

```c
                ret = SSL_connect(conn->ssl);      // L728（客户端方向）
        ...
                ret = SSL_accept(conn->ssl);       // L753（服务端方向）
```

对 RESP 协议本身，TLS 完全透明——`GET key` 与 `+OK\r\n` 的字节格式一个都没变，变的只是这些字节经 AEAD 信封传输。这正是模式 A 的纯粹形态：**应用协议零改动，安全层整体替换传输层**。

## 8.6 MQTT / AMQP：IoT 场景的 TLS

MQTT（8883）与 AMQP（5671）都是端口级直包。IoT 场景给 TLS 带来了三个特色话题：

1. **连接成本敏感**：设备休眠唤醒、NAT 重绑定都会导致重连，**会话恢复（PSK/1-RTT）直接省电省流量**——broker 通常开启 session ticket 并把恢复窗口调大（5.6 节的 NewSessionTicket 生命周期配置）；
2. **设备身份**：给每台设备签客户端证书（X.509 设备证书 + mTLS）是大厂通行做法（如 AWS IoT 的 JITP 流程），证书即设备身份，吊销/轮换走证书体系；PSK 模式（对称预共享）则是低算力设备的轻量替代；
3. **SNI 多租户**：同一 broker 集群按 SNI 分发不同租户的证书与 ACL。

## 8.7 gRPC：HTTP/2 over TLS + 服务网格的 mTLS

gRPC 的传输层就是"HTTP/2 over TLS"，因此它继承 HTTPS 的全部机制（ALPN 强制 h2、证书链、会话恢复），再加两条 gRPC 特色：

1. **mTLS 是服务网格的默认值**：Istio/Envoy 等 sidecar 在毫秒级重建全网格 mTLS，业务进程完全不感知证书——TLS 终结点从"业务进程"前移到"sidecar"，这是"认证基础设施化"的代表形态；
2. **凭据分层**：channel credentials（TLS/mTLS）管通道级安全，call credentials（如 OAuth token）管调用级身份——与 Kafka 的 TLS+SASL 分层异曲同工。

## 8.8 QUIC / HTTP3：TLS 被拆开嵌入（模式 C 代表）

QUIC（RFC 9000/9001/9114）对 TLS 1.3 的使用方式独此一家：**握手协议原封保留，记录协议整个被替换**。

| TLS 组件 | 在 QUIC 里的形态 |
|---|---|
| 握手消息（CH/SH/EE/Cert/CV/Finished） | 放进 QUIC 的 **CRYPTO 帧**，按流传输、有序可靠 |
| 记录层（AEAD 信封） | 被 **QUIC 包保护**取代：包负载加密 + **头部掩码**（连包号都加密） |
| 序号/nonce | QUIC 包号空间自带单调性；密钥分 Initial→Handshake→1-RTT 多档，各自的 write IV + 包号构造 nonce |
| KeyUpdate | **禁用**（QUIC 有自己的 key phase 位换密钥）——JDK 注释直说（`KeyUpdate.java` 第 273 行） |
| 会话恢复/0-RTT | NewSessionTicket 走加密的 1-RTT 包；early_data 与 QUIC 的 0-RTT 包级别机制对接 |
| ALPN | **强制**（QUIC 必须 ALPN 协商出 h3 等） |
| 传输参数（初始流控/最大包长…） | 作为 TLS 扩展传输——**QUIC 的握手参数住在 TLS 的扩展里** |

【源码证据】`sun/security/ssl/SSLExtension.java` 第 483-493 行：

```java
    CH_QUIC_TRANSPORT_PARAMETERS     (0x0039, "quic_transport_parameters",  // L483
    ...
    EE_QUIC_TRANSPORT_PARAMETERS     (0x0039, "quic_transport_parameters",  // L493
```

【源码证据】`sun/security/ssl/QuicTransportParametersExtension.java` 第 36 行、第 115 行：

```java
 * Pack of the "quic_transport_parameters" extensions [RFC 9001].
    ...
                // RFC 9001: endpoints MUST send quic_transport_parameters
```

**Initial 密钥**是 QUIC-TLS 最妙的设计：握手第一轮的密钥不由握手本身派生，而由**连接 ID（DCID）**公开派生（`HKDF-Extract(初始盐值, DCID)`）——任何看到第一个包的人都能算出 Initial 密钥，它的作用不是保密，而是**把"未加密"变成"可解密但已认证"**，让协议从第一个字节起就有完整性保护，也逼中间盒按 QUIC 规则解析而不是按老 TLS 记录规则瞎猜。

JDK 27 的 TLS 引擎已经内置了 QUIC 模式——`QuicTLSEngineImpl` 直接实现 QUIC 的 TLS 接口（握手引擎 + 密钥逐级回调）：

【源码证据】`sun/security/ssl/QuicTLSEngineImpl.java` 第 68 行、第 729 行：

```java
public final class QuicTLSEngineImpl implements QuicTLSEngine, SSLTransport {
    ...
    public void deriveHandshakeKeys() throws IOException {      // L729
```

（对照 5.1 节 `ServerHello.java` 第 1445-1455 行：客户端在推导完握手密钥后会检查 `sslConfig.isQuic` 并回调 `engine.deriveHandshakeKeys()`——**同一套 TLS 状态机，TCP 模式自己管记录层，QUIC 模式把密钥交给 QUIC 栈用**。）

## 8.9 邮件全家桶：implicit 与 STARTTLS 的共存与归一

| 协议 | 端口（明文） | 端口（STARTTLS） | 端口（implicit TLS） | 现状 |
|---|---|---|---|---|
| SMTP 提交 | — | 587 | **465** | RFC 8314（2018）重新确立 465 implicit 为推荐——STARTTLS 二十年的"机会式"教训（8.2）促使回归模式 A |
| SMTP 中继 | 25 | 25 | — | 服务器间：STARTTLS + MTA-STS/DANE 策略 |
| IMAP | 143 | 143 | 993 | 993 为主流 |
| POP3 | 110 | 110 | 995 | 995 为主流 |

邮件是三种模式变迁的最佳观察样本：993/995/465（A）与 143/587（B）长期并存，最终 RFC 8314 用"推荐 implicit"一锤定音——**能用 A 就用 A，是整个行业二十多年试错后的共识**。

## 8.10 汇总对比

| 协议 | 模式 | 切换点在哪 | 明文里可见 | 失败语义 |
|---|---|---|---|---|
| HTTPS / wss | A | 端口约定 | SNI、ALPN | 连接失败即失败，无回退 |
| SMTP/IMAP/LDAP/XMPP | B | 应用命令 | 能力清单、主机名 | 可配置"必须升"或机会式回退（需策略兜底） |
| PostgreSQL / MySQL | B' | 协议包标志位 | 极少（一个魔法数/能力位） | 客户端策略显式决定（sslmode） |
| Kafka / Redis / MQTT / MongoDB | A | 端口/监听器配置 | SNI | 失败即失败 |
| gRPC | A | 继承 HTTPS | SNI、ALPN | 失败即失败 |
| QUIC/HTTP3 | C | 无"切换"——握手与传输一体 | DCID、ALPN | 同上 |

## 本章小结

- **HTTPS**：TLS + SNI（选证书）+ ALPN（选协议）+ CONNECT 隧道（过代理），HTTP/2 把"协议升级"焊死在握手里。
- **STARTTLS 族**：升级动作在应用层命令里，必须重置协议状态、必须警惕 TLS stripping，强制策略（MTA-STS/DANE/HSTS）是这类模式的安全前提。
- **PG/MySQL**：把开关做进协议握手包，客户端用 sslmode 显式表态——比机会式回退严谨。
- **Kafka/Redis/MQTT**：纯模式 A 的三种实现姿势——listener 绑定安全层（Kafka）、连接类型抽象（Redis）、端口即协议（MQTT）。
- **QUIC**：TLS 1.3 的握手与传输参数被完整保留，记录层职责由 QUIC 包保护接管；"传输参数住在 TLS 扩展里"是协议协作的漂亮范例。
- 所有这些都建立在"如何在代码里驱动 TLS"之上——下一章进入 JVM 工程实践。

---

# 九、工程实践：JVM 里的 TLS 怎么写、怎么跑

> 本章对应源码：JDK（`javax/net/ssl/SSLEngineResult.java`、`sun/security/ssl/SSLSocketImpl.java`、`sun/security/ssl/SSLEngineImpl.java`）、Netty（`D:\code\3rd\netty-netty-4.2.18.Final\handler\src\main\java\io\netty\handler\ssl\SslHandler.java`、`SslProvider.java`）。协议读完，最后落到"我怎么用它"——JVM 世界提供了两套驱动模型，Netty 在其上包了一层"把 TLS 当普通 ChannelHandler 用"的糖。

## 9.1 两套 API：SSLSocket（透明代理式）与 SSLEngine（状态机式）

JDK 的 `javax.net.ssl` 给出两种形状的 TLS：

| | SSLSocket / SSLServerSocket | SSLEngine |
|---|---|---|
| 编程模型 | **透明**：握手自动完成，之后就是 InputStream/OutputStream | **手动**：应用喂明文/密文字节缓冲区，自己驱动状态机 |
| 握手发起 | 连接建立时自动（或 `startHandshake()` 显式） | `beginHandshake()` + wrap/unwrap 轮转 |
| 适用 | 阻塞式简单客户端/服务端 | NIO、Netty、Kafka、JDK HttpClient——一切非阻塞框架 |
| 状态归属 | socket 内部 | 返回值 `SSLEngineResult` 摆在明面上 |

阻塞式的那一套（SSLSocket）握手是"自动挡"——`startHandshake()` 就是 kickstart + 读到握手完成：

【源码证据】`sun/security/ssl/SSLSocketImpl.java` 第 425-455 行（节选）：

```java
    public void startHandshake() throws IOException {          // L425
        ...
                conContext.kickstart();                        // L448（发第一条 ClientHello）
            ...
                    readHandshakeRecord();                     // L455（循环吃到握手结束）
```

非阻塞世界必须用 SSLEngine——它的两个方法把 TLS 变成了**明文域与密文域之间的双向转换器**：

```java
public SSLEngineResult wrap(ByteBuffer[] srcs, int offset, int len,    // 明文 → 密文
        ByteBuffer dst)                                                // SSLEngineImpl.java L118/124
public SSLEngineResult unwrap(ByteBuffer src,                          // 密文 → 明文
        ByteBuffer[] dsts, int offset, int length)                     // SSLEngineImpl.java L478/485
```

## 9.2 驱动 SSLEngine：状态机循环

`SSLEngineResult` 每次调用返回两个枚举（`javax/net/ssl/SSLEngineResult.java` 第 65 行 `Status`、第 110 行 `HandshakeStatus`）：

- **Status（对这次 wrap/unwrap 本身的描述）**：`OK` / `CLOSED` / `BUFFER_UNDERFLOW`（入参不够，等更多网络数据）/ `BUFFER_OVERFLOW`（出参缓冲区太小，扩容重试）；
- **HandshakeStatus（状态机接下来想干什么）**：`NOT_HANDSHAKING` / `FINISHED` / `NEED_TASK`（先跑委托任务，如证书路径验证、密钥计算）/ `NEED_WRAP` / `NEED_UNWRAP`。

驱动循环的骨架（所有非阻塞框架都是这个模式的变体）：

```java
engine.beginHandshake();                       // SSLEngineImpl.java L95
while (true) {
    switch (engine.getHandshakeStatus()) {
        case NEED_TASK -> {                    // 阻塞任务交给线程池
            Runnable task;
            while ((task = engine.getDelegatedTask()) != null) executor.submit(task);
        }
        case NEED_WRAP -> {                    // 状态机要发握手数据
            engine.wrap(app, net); networkWrite(net);
        }
        case NEED_UNWRAP -> {                  // 状态机在等对端数据
            networkRead(net); engine.unwrap(net, app);
        }
        case FINISHED, NOT_HANDSHAKING -> { break; }
    }
}
// 之后：应用数据 = wrap(明文出网)；网络数据 = unwrap(入网明文)
```

两个高频坑：**wrap 与 unwrap 必须在同一个线程配对推进**（状态机不是线程安全的，委托任务除外）；**缓冲区尺寸必须按 peer 告知/协议上限预留**（`BUFFER_OVERFLOW` 处理不当是最常见的握手卡死原因）。

## 9.3 Netty SslHandler：把 TLS 变成一个 ChannelHandler

Netty 的答案是把上面那个循环包成 pipeline 上的一层：`SslHandler` 同时继承 **`ByteToMessageDecoder`**（入站：密文→明文）和实现 **`ChannelOutboundHandler`**（出站：明文→密文）——TLS 在 pipeline 里就占一个普通 handler 的位置：

```
pipeline: [SslHandler] ← [HttpClientCodec] ← [业务 Handler]
             ↑ 最外层：入站先解密、出站最后加密
```

【源码证据】`D:\code\3rd\netty-netty-4.2.18.Final\handler\src\main\java\io\netty\handler\ssl\SslHandler.java` 第 169 行、第 436-437 行：

```java
public class SslHandler extends ByteToMessageDecoder implements ChannelOutboundHandler {
    ...
    private SslHandlerCoalescingBufferQueue pendingUnencryptedWrites;   // L436
    private Promise<Channel> handshakePromise = new LazyChannelPromise(); // L437
```

它的三个关键机制：

**① 出站写先排队，握手完成统一加密**。业务代码在握手期间照常 `write()`——明文被放进 `pendingUnencryptedWrites` 队列（第 795-798 行），`flush()` 触发 `wrapAndFlush`（第 801-831 行）把队列里的明文批量 `engine.wrap()` 后刷出。队列还是个**合并队列**（CoalescingBufferQueue）：多个小 write 合并进同一次 wrap，减少记录数量、提高密文密度——这就是 3.4 节"小写合并"的落地。`handshakePromise`（第 437 行）是用户侧观察握手成败的 Future（第 645 行 `handshakeFuture()`）。

**② 握手在连接激活时自动启动**：`channelActive`（第 2287 行）→ `startHandshakeProcessing`（第 2148 行）→ `handshake()`（第 2215 行）——客户端发 ClientHello，服务端等对端。

**③ STARTTLS 模式的通用开关**：构造 SslHandler 可传 `startTls=true`——第一笔 write 不加密直接放行，然后才启动握手：

【源码证据】`SslHandler.java` 第 801-812 行：

```java
    public void flush(ChannelHandlerContext ctx) throws Exception {
        // Do not encrypt the first write request if this handler is
        // created with startTLS flag turned on.
        if (startTls && !isStateSet(STATE_SENT_FIRST_MESSAGE)) {    // L805
            setState(STATE_SENT_FIRST_MESSAGE);
            pendingUnencryptedWrites.writeAndRemoveAll(ctx);
            forceFlush(ctx);
            // Explicit start handshake processing once we send the first message. ...
            startHandshakeProcessing(true);                          // L810
            return;
        }
```

**这正是 8.2 节 STARTTLS 协议在客户端的通用实现范式**：明文段（`STARTTLS` 命令本身）直接穿过 SslHandler 发出去，随后协议代码触发握手，之后的流量自动进入加密通道——Netty 把三种接入模式中的"切换点"抽象成了这一个布尔参数。

**④ 入站解密按 provider 分三条路径**：JDK 引擎、Conscrypt、OpenSSL 各有一套 unwrap 适配（第 202、253、302 行起的三段实现），OpenSSL 路径支持多 ByteBuffer 批量解密（第 209 行注释引用 `OpenSslEngine#unwrap(ByteBuffer[], ByteBuffer[])`）——引擎差异被封装在 handler 内部，pipeline 感知不到。

## 9.4 SslProvider：JDK 引擎还是 OpenSSL 引擎

【源码证据】`D:\code\3rd\netty-netty-4.2.18.Final\handler\src\main\java\io\netty\handler\ssl\SslProvider.java` 第 27-40 行：

```java
public enum SslProvider {
    JDK,             // L31：纯 Java（SunJSSE）
    OPENSSL,         // L35：netty-tcnative 绑定（BoringSSL/OpenSSL）
    OPENSSL_REFCNT;  // L40：同上 + 引用计数管理 native 资源
```

选型经验：**默认 JDK**（零依赖、升级 JDK 即升级 TLS 实现）；追求吞吐或需要 OpenSSL 特有优化（多缓冲 wrap/unwrap、更快的会话票据加解密）时上 **netty-tcnative + BoringSSL**（注意需引入对应的 native 依赖并在 x86/arm 上分别验证）；OpenSSL provider 还默认启用更激进的写合并。Kafka/RocketMQ 等基于 Netty 的中间件客户端同理。

## 9.5 ALPN：协议协商在两条栈上的接线

HTTP/2 要求"ALPN 协商出 h2"（8.1 节），Netty 为三种 provider 各备了 ALPN 适配（`JdkAlpnSslEngine` / `ConscryptAlpnSslEngine` / `BouncyCastleAlpnSslEngine`，同目录可见），并把"协商结果"变成 pipeline 事件：握手完成后 `ApplicationProtocolNegotiationHandler` 按 ALPN 结果分流——h2 则装 HTTP/2 编解码器，http/1.1 则装 HttpServerCodec（`ApplicationProtocolNames.HTTP_2` / `HTTP_1_1` 常量即为此设计）。服务端多域名证书则由 `SniHandler` 按 ClientHello 里的 SNI 动态挑选 SslContext。

## 9.6 证书与信任配置清单

| 要配什么 | JDK 原生 | Netty |
|---|---|---|
| 服务端证书链 | `KeyManagerFactory` + PKCS12/JKS keystore | `SslContextBuilder.forServer(cert, key)`（支持 PEM 直读） |
| 信任锚 | `TrustManagerFactory` + TrustStore（`-Djavax.net.ssl.trustStore`） | `SslContextBuilder.trustManager(...)` |
| 客户端认证 | `SSLParameters.setNeedClientAuth(true)` | `SslContextBuilder.clientAuth(ClientAuth.REQUIRE)` |
| 协议/套件过滤 | `SSLParameters.setProtocols/setCipherSuites` | `SslContextBuilder.protocols/ciphers` |
| SNI | 系统属性 `jsse.enableSNIExtension`（默认 true）+ `SSLParameters.setServerNames` | `SniHandler`（服务端路由） |
| 会话缓存 | `SSLSessionContext.setSessionCacheSize/setSessionTimeout` | `SslContext.sessionCacheSize()/sessionTimeout()` |

内网自建 CA 的标准姿势：openssl/keytool 造根证书 → 给每个服务签发带 SAN 的证书 → 客户端 TrustManager 只信任这个根——**把"信任什么"收敛成一个文件，是内网 TLS 运维的一半工作**。

## 9.7 性能清单（按收益排序）

1. **连接复用**：TLS 握手（哪怕 1-RTT）永远比复用贵——HTTP/2、gRPC、Kafka 长连接天然受益；
2. **会话恢复**：1.3 下 NST+PSK（1-RTT）、1.2 下 ticket/ID；Netty 的 `sessionCache` 与 JDK 的 `SSLSessionContext` 都要给足容量和超时；内网高并发短连接场景收益最大；
3. **选对 provider**：OpenSSL/BoringSSL provider 在高吞吐下通常领先纯 Java 引擎（AES-NI/多缓冲批处理）；无 native 依赖时 JDK 引擎的 AES-GCM 也已用 intrinsics 加速，差距主要在小包密集场景；
4. **写合并**：小包多的大流量服务（如网关）确认 SslHandler 的合并队列在生效（9.3 节①）；
5. **0-RTT 谨慎开**：只用于幂等请求路径，且服务端要配单次票据/防重放（5.5 节）；
6. **线程与缓冲**：SSLEngine 的委托任务交给专用线程池，别让事件循环跑证书验证；netRead/appRead 缓冲按实际流量定型（Kafka 的 `ssl.*` 配置族同理）。

## 本章小结

- 阻塞世界用 SSLSocket（透明），非阻塞世界用 SSLEngine（状态机）——**理解 `SSLEngineResult` 的双枚举循环，就读懂了 Netty/Kafka/HttpClient 里所有 TLS 代码的骨架**。
- Netty SslHandler = 状态机循环 + 出站合并队列 + handshakePromise + startTls 开关，把"协议如何接 TLS"（三种接入模式）全部抽象成 pipeline 配置。
- 性能三板斧：复用连接、开启恢复、选对 provider；0-RTT 与重协商属于"明确知道自己在做什么才打开"的选项。

---

# 十、安全专题：攻击与防御的编年史

> TLS 的每个"看似多余的机制"背后几乎都躺着一个真实攻击。本章把第一~九章出现过的防御机制按攻击史串一遍——理解"为什么"，比记住"是什么"重要得多。

## 10.1 中间人（MITM）：加密不等于安全

最常见的生产事故不是密码学攻击，而是**配置性裸奔**：

- 客户端 `TrustManager` 信任所有证书（`X509TrustManager` 全 return true）——证书机制整体失效，任何中间人都能换上自己的证书；
- 只验链不验 hostname（6.3 节）——攻击者拿任意合法域名证书即可顶替；
- `sslmode=REQUIRED` 但证书过期/自签也照单全收——防窃听不防冒充。

记一条准则：**证书验证的三个环节（链、名、期）少验任何一个，加密只是给中间人做了嫁衣**。

## 10.2 降级攻击编年史：从 POODLE 到 DOWNGRD 哨兵

| 年份 | 攻击 | 机制 | 修复 |
|---|---|---|---|
| 2014 | **POODLE** | 强迫回退 SSL 3.0 后打 CBC padding 神谕 | RFC 7568 全面禁用 SSL 3.0 |
| 2015 | **FREAK** | 让双方"协商出"512 位出口级 RSA，离线破解后解密 | 服务器/客户端拒绝出口套件，最小密钥长度约束 |
| 2015 | **Logjam** | 同思路打 512 位出口级 DH，可预计算 | 淘汰出口组、DHE 参数下限 |
| 长期 | **TLS stripping**（模式 B 特有） | 抹掉 STARTTLS/HSTS 等明文协商信号，让双方"自愿"明文 | 带外强制策略：HSTS（Web）、MTA-STS/DANE（邮件）；或干脆用模式 A |
| 2018 | — | **TLS 1.3 内建兜底**：1.3 服务器协商出旧版本时在 ServerHello.random 写 "DOWNGRD" 哨兵（5.4 节 JDK 证据） | 客户端检测即 fatal |

规律：**明文协商的信号都可以被中间人抹掉**——所以 1.3 把版本协商搬进 `supported_versions` 扩展并配 random 哨兵双保险，而行业层面则推动"端口即协议"（模式 A）把降级面直接取消。

## 10.3 重协商注入（CVE-2009-3555）：一次"功能"的死亡

1.2 的重协商让中间人可以把恶意前缀注入连接（4.6 节）。修复链：RFC 5746 补丁扩展 → 各框架主动拒绝（Kafka `SslTransportLayer.java:600-608`）→ 1.3 移除重协商、以 KeyUpdate 只换密钥不重认证。"能重新握手"这个看似无害的功能，最终被整个行业判处死刑。

## 10.4 0-RTT 重放：用"快"换来的新攻击面

0-RTT 数据无服务端挑战参与（5.5 节），抓包重放整条连接服务器可能再次执行。防线三件套：单次使用票据 + 时间窗校验（协议层）、只对幂等方法启用（应用层）、业务幂等键（代码层）。浏览器内核只自动重放 GET/HEAD 即此原因。

## 10.5 CBC 时代的时间侧信道（Lucky13 与 padding oracle 家族）

MAC-then-encrypt 下，"解密失败"与"MAC 失败"的耗时差异泄露明文信息。1.2 时代的补救（常量时间去 padding、encrypt-then-MAC 扩展）都属"续命"，1.3 的 AEAD-only（3.3 节）才是根治——**认证标签先于一切明文处理**，时序侧信道失去土壤。

## 10.6 Heartbleed：协议无罪，实现有罪

2014 年的 Heartbleed 是 OpenSSL 的心跳扩展越界读（memcpy 少验长度），不是 TLS 协议缺陷——但它造成的证书私钥泄露潮推动了三件事：证书轮换自动化（ACME/90 天证书）、内存安全语言/模糊测试在密码学库的普及、以及"吊销机制靠不住"（10.7）的进一步坐实。**协议设计得再好，也要给实现留出错的空间**——这是 TLS 学习里最容易被忽略的一课。

## 10.7 吊销的尴尬与"短期证书"的胜利

6.4 节已展开：CRL 太大、OCSP 有隐私与延迟、stapling 部署率低、行业默认 soft-fail。工程结论：**不要依赖吊销做安全边界**，用短有效期 + 自动续期让"作废"自然发生。

## 10.8 攻击 → 防线总表

| 攻击 | 打击点 | 协议/工程防线 | 本文位置 |
|---|---|---|---|
| 窃听/篡改/重放记录 | 记录层 | AEAD + 序号进 nonce/MAC | 第三章 |
| 伪造身份 | 握手 | 证书链 + CertificateVerify + Finished | 第四~六章 |
| 改写协商 | 握手 | transcript 绑定（握手哈希） | 4.3、5.2 |
| 降级 | 版本协商 | supported_versions + DOWNGRD 哨兵 + 禁用老版本 | 5.4、10.2 |
| 重协商注入 | 1.2 重协商 | RFC 5746 → 1.3 移除 | 4.6、10.3 |
| 0-RTT 重放 | 早期数据 | 单次票据 + 幂等 + 方法白名单 | 5.5、10.4 |
| 时序侧信道 | CBC | AEAD-only（1.3） | 3.2、10.5 |
| 无 PFS | RSA 密钥交换 | 强制 (EC)DHE（1.3 移除 RSA 交换） | 4.4 |
| 配置性裸奔 | 客户端代码 | 链/名/期三验 + sslmode 显式声明 | 10.1 |

---

# 十一、贯通视图：一次 HTTPS 请求的全景时间线

把 TCP、TLS 1.3、HTTP 三条时间线叠成一张图（对比 1.2 标注）：

```
时间 ──────────────────────────────────────────────────────────▶

客户端                                                        服务端
  │                                                            │
  │ ① TCP 三次握手 (SYN, SYN-ACK, ACK)              1-RTT      │
  │◀────────────────────────────────────────────────────────▶ │
  │                                                            │
  │ ② ClientHello (key_share, SNI, ALPN)          ┐            │
  │◀──────────────────────────────────────────────│            │
  │            ServerHello (+HRR 时 2-RTT)        │ TLS 1-RTT  │
  │◀──── {EncryptedExtensions/Certificate/        │ 1.2 则需   │
  │        CertificateVerify/Finished}            │ 2-RTT      │
  │──── {Finished} ───────────────────────────────┘            │
  │                                                            │
  │ ③ {GET /index.html}                            0-RTT 时   │
  │◀─── {200 OK …} ───────────── 密钥已就绪，数据即发 ─────────│
  │                                                            │
  │ ④ 复用：同连接第 2..N 个请求（无握手）                      │
  │ ⑤ 会话终止：close_notify（Alert）双向各一                   │
  │    （1.3：close 后可重建；QUIC：TCP+TLS 合并为 1 个 0/1-RTT）│
```

读图三点：

1. **1.2 与 1.3 的差别全部浓缩在第②段**：1.2 的②要 2-RTT（Hello 往返 + key exchange 往返），1.3 只要 1-RTT——这解释了为什么全球迁移 1.3 对"高 RTT 跨境链路"收益最大；
2. **0-RTT 的收益边界**：QUIC 0-RTT 甚至把①②合并进第一个飞行包——但重放风险决定了它只服务幂等请求（5.5 节）；
3. **close_notify 是优雅关闭**：不等它、直接 FIN，对端无法区分"数据发完了"和"被掐断"——对消息完整性敏感的应用（如文件下载）必须检查 close_notify。

---

# 十二、附录

## 12.1 密码套件速查

**TLS 1.3（全部 5 个，JDK `CipherSuite.java:68` 起逐个定义）：**

| 套件 | 编号 | 密钥交换* | AEAD | 哈希 |
|---|---|---|---|---|
| TLS_AES_128_GCM_SHA256 | 0x1301 | (EC)DHE / PSK | AES-128-GCM | SHA-256 |
| TLS_AES_256_GCM_SHA384 | 0x1302 | 同上 | AES-256-GCM | SHA-384 |
| TLS_CHACHA20_POLY1305_SHA256 | 0x1303 | 同上 | ChaCha20-Poly1305 | SHA-256 |
| TLS_AES_128_CCM_SHA256 | 0x1304 | 同上 | AES-128-CCM | SHA-256 |
| TLS_AES_128_CCM_8_SHA256 | 0x1305 | 同上 | AES-128-CCM(8) | SHA-256 |

\* 1.3 的密钥交换不再编入套件名（1.2 的做法），由 key_share/PSK 扩展决定——这也是套件数量从几十个瘦身到 5 个的原因。

**TLS 1.2 常用套件（仍大量部署）：**

| 套件 | 编号 | 备注 |
|---|---|---|
| TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 | 0xC02F | 1.2 时代主力 |
| TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 | 0xC02B | ECDSA 证书版 |
| TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256 | 0xCCA8 | 无 AES 硬件加速的设备 |
| TLS_RSA_WITH_AES_128_GCM_SHA256 | 0x009C | **无 PFS，应禁用** |

## 12.2 消息类型编号表（JDK `SSLHandshake.java` 行号实证）

**记录 content type**（`ContentType.java:31`）：CCS=20（1.3 仅兼容壳）、Alert=21、Handshake=22、ApplicationData=23。

**握手消息**：

| 消息 | 编号 | 版本 | JDK 行号 |
|---|---|---|---|
| hello_request | 0x00 | 1.2 | L45 |
| client_hello / server_hello | 0x01 / 0x02 | 全版本 | L60 / L75 |
| hello_retry_request | 0x02 | 1.3（魔数 random 区分） | L98 |
| new_session_ticket | 0x04 | 1.2(RFC5077)/1.3 | L129 |
| end_of_early_data | 0x05 | 1.3 | L147 |
| encrypted_extensions | 0x08 | 1.3 | L150 |
| certificate | 0x0B | 全版本 | L169 |
| server_key_exchange | 0x0C | 1.2（1.3 并入 key_share） | L192 |
| certificate_request | 0x0D | 全版本 | L207 |
| server_hello_done | 0x0E | 1.2 | L238 |
| certificate_verify | 0x0F | 全版本 | L253 |
| client_key_exchange | 0x10 | 1.2 | L292 |
| finished | 0x14 | 全版本 | L310 |
| key_update | 0x18 | 1.3 | L358 |
| message_hash | 0xFE | 1.3 内部（binder 用） | L393 |

## 12.3 TLS 扩展编号速查（JDK `SSLExtension.java` 行号实证）

| 扩展 | 编号 | 作用 | JDK 行号 |
|---|---|---|---|
| server_name (SNI) | 0x0000 | 按名选证书 | L38 |
| status_request | 0x0005 | OCSP stapling | L98 |
| supported_groups | 0x000A | 可用椭圆曲线/DH 组 | L138 |
| signature_algorithms | 0x000D | 签名算法清单 | L317 |
| application_layer_protocol_negotiation | 0x0010 | ALPN（CH/SH/EE 三个位置） | L186 |
| session_ticket | 0x0023 | 1.2 的 RFC 5077 ticket | L295 |
| early_data | 0x002A | 0-RTT（JDK 仅声明未实现） | L336 |
| supported_versions | 0x002B | 版本协商（CH/SH/HRR 四个变体） | L340 |
| psk_key_exchange_modes | 0x002D | psk_ke vs psk_dhe_ke | L400 |
| key_share | 0x0033 | ECDHE 公钥交换 | L450 |
| quic_transport_parameters | 0x0039 | QUIC 传输参数住进 TLS 扩展 | L483 |
| renegotiation_info | 0xff01 | RFC 5746 重协商补丁 | L505 |

## 12.4 端口速查（模式归属见第七章）

| 端口 | 协议 | | 端口 | 协议 |
|---|---|---|---|---|
| 443 | HTTPS / HTTP/3 | | 5432 | PostgreSQL（协议内协商） |
| 465 | SMTP 隐式 TLS | | 5671 | AMQP over TLS |
| 587 | SMTP + STARTTLS | | 636 | LDAPS |
| 993 / 995 | IMAPS / POP3S | | 853 | DNS over TLS |
| 990 | FTPS 隐式 | | 8883 | MQTT over TLS |
| 25/143/110 | 明文 + STARTTLS | | 5061 | SIP over TLS |
| 3306 | MySQL（协议内协商） | | 9093* | Kafka SSL 常见约定 |
| 16379* | Redis tls-port 常见约定 | | 6514 | syslog over TLS |

\* 自定义端口，非 IANA 固定分配。

## 12.5 动手实验（按性价比排序）

1. **看一次真实握手**（10 分钟）：`openssl s_client -connect example.com:443 -servername example.com -alpn h2,http/1.1 -tls1_3`，对照第四章流程逐条辨认消息；加 `-state` 看状态机推进。
2. **Wireshark 解密自己的流量**：设环境变量 `SSLKEYLOGFILE=D:\tmp\sslkeys.log`（Chrome/Firefox 会写会话密钥），Wireshark → Preferences → Protocols → TLS 指向该文件，抓 https 流量看明文——直观理解"密钥只有两端有，抓包者（你）靠自己什么都看不到"。
3. **JDK 状态机可视化**：任意 Java 程序加 `-Djavax.net.debug=ssl:handshake:verbose`，对照 9.2 节状态机看 NEED_WRAP/NEED_UNWRAP/NEED_TASK 轮转。
4. **自建 CA + mTLS**：openssl 造根 CA → 给服务端/客户端各签一张带 SAN 的证书 → Kafka 或 Redis（`tls-port` + `tls-cert-file`）开 TLS + `tls-auth-clients yes`，完成一条完整链路。
5. **读源码**：从本文引用过的 `ServerHello.java`（T13 消费者）与 `SslHandler.java`（flush→wrapAndFlush）两条线各自走一遍，前者看协议，后者看工程。

## 12.6 学习路线

- **协议主线**：RFC 8446（TLS 1.3，先读 §2 架构与 §4 握手）→ RFC 8449/5246 按需查 1.2 细节 → RFC 9001（QUIC-TLS，进阶）。
- **实现主线**：JDK `sun.security.ssl`（本文证据链）→ Netty `io.netty.handler.ssl`（工程化）→ OpenSSL 状态机（`SSL_connect/SSL_accept`，Redis/Redis 源码是很好的最小宿主）。
- **运维主线**：证书生命周期（ACME/短证书）→ 套件与协议版本基线（禁 1.0/1.1、禁 TLS_RSA_ 套件、开 1.3）→ 抓包排障方法论（Wireshark + keylog）。

**全文完。** 一句话收束全文：TLS 的一切复杂性，都是在回答同一个问题——**"两个从未见过面、隔着不可信网络的进程，如何安全地共享一个秘密并确认彼此的身份？"** 记住这句话，再回看记录协议、握手协议、证书体系与三种接入模式，每一段设计都会变得理所当然。
