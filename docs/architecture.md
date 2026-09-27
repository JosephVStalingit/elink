# 架构说明

## 数据流

```
Minecraft 世界  ->  P2P 桥接  ->  WebRTC 数据通道  ->  对端 P2P 桥接  ->  对端的世界
                        ^
                        |
                  MQTT 信令（offer / answer / ICE candidate，按房间组织）
```

各层按数据经过的顺序排列：

| 层 | 包 | 职责 |
| --- | --- | --- |
| 命令 | `com.example.mcp2p.command` | `/mcp2p host`、`join`、`status`、`leave` |
| 客户端桥接 | `com.example.mcp2p.client` | 读取集成服务器的"对局域网开放"端口 |
| 服务器列表桥接 | `com.example.mcp2p.lan` | 把隧道伪装成局域网世界，使其出现在多人游戏列表里 |
| 局域网桥接 | `com.example.mcp2p.tunnel` | 在一条数据通道上复用 TCP 连接：托管方拨号游戏端口，加入方本地监听 |
| 会话编排 | `com.example.mcp2p.net` | 加入房间、决定发起方、把信令接到 peer connection、对外提供可靠字节通道 |
| 信令 | `com.example.mcp2p.signalling` | MQTT 传输、房间主题、消息编解码、房间密钥证明 |
| WebRTC | `com.example.mcp2p.rtc` | peer connection 生命周期、数据通道、ICE/连接状态 |
| 账号 | `com.example.mcp2p.identity` | 账号服务调用：登录、刷新、证明账号、校验证明、结果缓存 |
| 平台差异 | `com.example.mcp2p.platform` | 加载器差异：配置目录、模组版本、是否客户端、命令注册时机 |
| 配置 | `com.example.mcp2p.config` | broker 地址、房间、端口、ICE 服务器、安装 ID、加密存储 |

P2P 链路上没有任何 Minecraft API，都是普通 Java，因此同一份代码在客户端和专用服务器上都能跑，也便于在游
戏之外测试。

## 信令

MQTT 只负责"碰头"：数据通道一旦建立，就再也不用了（ICE 重启除外）。

### 主题

一个房间由一个便于分享的短码标识，例如 `MCP2P-7F3A9C`。所有主题都在可配置的前缀下（默认 `mcp2p`）：

| 主题 | 方向 | 用途 |
| --- | --- | --- |
| `<前缀>/<房间>` | 广播 | offer / answer / ICE candidate，用 `from`/`to` 字段寻址 |
| `<前缀>/<房间>/presence` | 广播 | 加入与离开通知，以及周期性心跳 |

发布者的 MQTT client ID 是"每个安装固定"的标识（`mcp2p-<installId>`），所以重启后不会被当成新玩家。

### 消息

消息是 JSON 对象。每条消息都带协议版本、发送者，以及（在需要时）目标接收者，因此收到与自己无关的流量可以
直接忽略。

| `type` | 载荷 | 说明 |
| --- | --- | --- |
| `hello` | `fromName`、`from`、`hosting`、`uuid`、`requiresVerification` | 加入时发送；收到广播后也会以定向方式回一次 |
| `bye` | `reason` | 正常退出 |
| `offer` | `sdp`、`sdpType` | 发起方在打开数据通道后立即生成 |
| `answer` | `sdp`、`sdpType` | 应答方发送 |
| `candidate` | `sdpMid`、`sdpMLineIndex`、`sdp` | ICE 收集过程中持续发送 |
| `challenge` | `challenge` | 房主要求对方证明账号，携带一个随机 id |
| `challenge_response` | `challenge`、`uuid` | 对方已完成 `join`，回执该 id |
| `verified` | — | 房主确认对方证明通过，可以开始协商 |

`installId` 字典序较小的一方发起，另一方应答。这个规则是确定性的，不需要额外往返，也不会出现双方同时发
offer。除 `hello` 外，每条消息都带协议 `version` 与房间；`to` 为空表示"房间里的所有人"。

`version` 不匹配的消息会被丢弃；房间不匹配的消息同样丢弃。账号身份由 `challenge`/`challenge_response` 流程
证明（见下文），而不是靠自称。

## WebRTC

直接使用 `dev.onvoid.webrtc`（webrtc-java）：

* 每个进程一个 `PeerConnectionFactory`，首次使用时惰性创建。
* 每个 peer 一个 `RTCPeerConnection`，由 `RTCConfiguration` 构建（STUN/TURN 来自配置）。
* 隧道跑在 SCTP 数据通道上：目前是一条可靠、有序的通道（`mcp2p`）；将来若某条"车道"需要无序/不可靠，
  再加通道即可。
* ICE candidate 一产生就通过 MQTT 投递（`onIceCandidate`）；由于房间主题是广播，远端描述尚未设置时到达的
  candidate 必须由接收方缓存后再喂给 WebRTC。

线程模型：WebRTC 的回调都在它自己的原生线程上，Paho 也在自己的线程上。两者都不能阻塞 Minecraft 主线程，
因此回调只做入队，真正的世界交互留给游戏线程。

原生库：`NativeLoader` 会把 `webrtc-java-<os>-<arch>.dll` 从 classpath 解压到临时文件并 `System.load`。
这就是它必须以未修改形式嵌套在模组 jar 里的原因，见 [dependency-bundling.md](dependency-bundling.md)。

## 隧道

Minecraft 说的是 TCP，而数据通道是面向消息的，因此在上面加了一个小的多路复用层：

```
帧 = [streamId:int][type:byte][payload]      type: OPEN | DATA | CLOSE
```

* 一条数据通道消息 = 一帧。通道本身有序可靠，所以同一流的帧天然按序到达，不需要重组逻辑；超过 16 KiB 的载
  荷会拆成连续的多个帧。
* 加入方在 `local-port` 上接受 TCP 连接（该配置默认是 `0`，也就是由系统挑选一个空闲端口，避免端口写死导致
  冲突），分配 stream id 并发送 `OPEN`。托管方对每个 `OPEN` 拨号 `127.0.0.1:<游戏端口>`，也就是它共享的世界。
* 背压方向相反：读取端在数据通道的 `bufferedAmount` 超过 512 KiB 时等待，从而把节流推回给对端，而不是让
  队列无限增长。朝本地 socket 的方向使用 1024 段的有界队列；一旦溢出就丢弃该流，而不是让进程耗尽内存。
* `CLOSE` 在一端关闭 socket，并在另一端触发同样的关闭。每个流各有一个守护读线程与写线程，因此隧道的任何
  工作都不会跑在游戏线程上。

## 出现在服务器列表里

如果朋友还得手输地址，隧道体验会差很多，所以加入方还会把隧道伪装成局域网世界广播出去。原版客户端监听组播
`224.0.2.60` 的 4445 端口，把 `[MOTD]<文本>[/MOTD][AD]<端口>[/AD]` 形式的载荷变成一个服务器条目，条目地址
是**数据包的来源地址**加上 `<端口>`。

因此广播器每 1.5 秒从**回环地址**发出这样的载荷，于是条目显示为 `127.0.0.1:<本地端口>`，正是隧道真正监听的
地址；本地端口默认由系统自动分配（`local-port` 留 `0`），因此客户端总是从广播里拿到真实端口，玩家不需要知道
任何端口号。若从本机局域网地址广播，条目的地址就没有 socket 在监听。1.20.1 与 1.21.1 的客户端都核对过：两者
都不过滤来源地址，而按检测器同样的方式绑定的 `MulticastSocket` 确实能收到回环数据报。

## 准入控制

公共 broker 上任何人只要知道主题名就能订阅，房间码也很短、可被猜中，因此房间可以设置密钥。设置之后每条信令
都在 `proof` 字段里携带 `HMAC-SHA256(secret, room|sender|type)`，无法给出正确证明的对端会在创建任何 peer
connection 之前被丢弃。把证明绑定到房间、发送者与消息类型，意味着被截获的消息无法换个类型或换个发送者重放；
被放进房间的对端则被视为可信。

## 对称 NAT 与打洞成功率

WebRTC 直连能否成功，取决于双方的 NAT 行为。**对称 NAT**（Symmetric NAT）的特点是：同一个内网端口访问
不同目标时，NAT 会分配**不同**的公网端口，于是双方交换的"公网映射地址"对彼此都无效，普通打洞失败。

### 已经做的优化（按代码位置）

| 措施 | 位置 | 为什么有用 |
| --- | --- | --- |
| 每个 STUN 地址单独作为一个 ICE 服务器，可配置多个 | `P2PSession.rtcConfiguration` | 收集到的反射候选越多，对端能尝试的路径越多。当只有一侧是对称 NAT 时（最常见的真实情况），成功依赖的正是这一侧收集到的候选 |
| IPv6 候选默认开启 | 同上（`PortAllocatorConfig.setEnableIpv6`） | 只要双方都有公网 IPv6，流量直接走 IPv6，完全不经过 NAT |
| 本地候选端口范围可收窄 | 同上（`ice-port-min` / `ice-port-max`） | 把候选限制在一段固定区间，某些 NAT 会更稳定地复用同一个映射 |
| 打洞失败自动重启 ICE，并重发带新凭证的 offer | `PeerSession.restartIceBecauseOfFailure` | 对称 NAT 常按顺序分配端口，重收集一次有机会落到能通的路径上；这是没有 TURN 时唯一还能自动尝试的手段。只由发起方执行，避免双方同时重启 |
| 失败时输出候选类型统计与 STUN/TURN 错误码 | `PeerSession.reportIceTrouble` / `onIceCandidateError` | 把"连不上"变成可解释的问题：能直接看出是缺少 relay 候选，还是 STUN 服务器不可达 |
| TURN 配置与凭证（含强制中继策略） | `McP2pConfig.turnServerUrls` / `ice-transport-policy` | 双方都在对称 NAT 后时，中继是唯一可靠的通路；`relay` 策略还能隐藏双方 IP |

### 什么时候必须用 TURN

| 双方 NAT 情况 | 直连可行吗 | 说明 |
| --- | --- | --- |
| 都不是对称 NAT（家里常见路由器） | 可以 | 靠 srflx 候选打洞 |
| 一侧对称 NAT，另一侧不是 | 通常可以 | 非对称侧收集候选，对称侧主动发包；失败时会自动重启 ICE 再试 |
| 双方都是对称 NAT | 基本不行 | 必须配置 TURN（本模组会把 TURN 作为 ICE 候选，自动在直连失败后使用） |
| 双方都有公网 IPv6 | 可以 | 完全绕过 NAT，最稳 |

TURN 配置示例（`config/mcp2p.properties`）：

```properties
turn-servers=turn:your.turn.server:3478
turn-username=your-user
turn-password=your-password
ice-restart-attempts=2
```

## 账号证明

房间还可以要求对端拥有某个 LittleSkin 账号，这就是好友名单开启的能力。它使用的正是 Minecraft 自己认证玩家
的机制，因此不需要额外服务：

1. 房主给新玩家一个随机 id（`CHALLENGE`）。
2. 新玩家用自己的令牌把这个 id 告知账号服务：
   `POST /sessionserver/session/minecraft/join`。只有账号持有者能做到，因为这一步需要那个密钥令牌。
3. 房主查询 `GET /sessionserver/session/minecraft/hasJoined?username=…&serverId=…`。返回了 profile，就说明该
   账号确实属于这个对端。
4. 房主再对照好友名单，通过后才发送 `VERIFIED`，之后才开始协商。在此之前，该对端的 offer 与 candidate 一律
   被忽略。

对着真实服务核对过的端点：`authserver/validate`、`authserver/refresh`、`authserver/authenticate`（它要求
Yggdrasil 的 `agent` 对象 —— 第一次没带时返回 `400 agent must be an object`）、
`sessionserver/session/minecraft/join`、`hasJoined`、`api/profiles/minecraft` 均与文档一致。
`GET /users/{username}` 与 `GET /sessionserver/session/minecraft/profile/{uuid}` 在 LittleSkin 上没有路由，
因此名称查询走 `POST /api/profiles/minecraft`，权威名称则来自 `join`/`hasJoined` 的返回值。

## 实现进度

| 组件 | 状态 |
| --- | --- |
| `config.McP2pConfig`、`config.InstallId`、`config.SecretStore` | 完成：配置文件、默认值、房间码、端口、固定安装 ID、令牌加密存储 |
| `signalling.SignalMessage`、`signalling.RoomSecret`、`signalling.SignallingClient` | 完成：JSON 协议、HMAC 房间证明、Paho 传输、房间订阅与自动重连 |
| `rtc.WebRtcEngine`、`rtc.PeerSession` | 完成：原生库探测、共享工厂、offer/answer、ICE 透传与缓存、数据通道 |
| `net.P2PSession` | 完成：加入房间、发起方选举、托管声明、peer 注册表、账号证明状态机、背压查询 |
| `tunnel.TunnelFrame`、`tunnel.TunnelStream`、`tunnel.TunnelMultiplexer`、`tunnel.TunnelSession` | 完成：帧协议、按 peer 复用、流线程、托管与加入两种角色 |
| `identity.LittleSkinClient`、`identity.IdentityService`、`identity.PlayerIdentity`、`identity.ResultCache` | 完成：登录、刷新、join、hasJoined、名称查询、令牌存储、验证结果缓存 |
| `command.McP2pCommand` | 完成：`/mcp2p host`、`join`、`status`、`leave`、`login`、`whoami`、`friends` |
| `client.McP2pClient` | 完成：把"对局域网开放"的端口交给通用代码 |
| `lan.LanAnnouncer` | 完成：把隧道广播成局域网世界，出现在多人游戏列表里 |
| `platform.Platform` | 完成：Fabric / Forge / NeoForge 的差异集中在此 |
| `McP2p` | 完成：整体装配、服务端的自动加入与自动角色 |

已验证：开发环境下原生库能加载、broker 能连接、隧道能打开本地端口并接受真实 TCP 连接（无人托管时会给出明
确日志并拒绝）。广播器用真实类对着"按客户端方式绑定"的 socket 跑过，从 `127.0.0.1` 发出
`[MOTD]…[/MOTD][AD]25566[/AD]`。准入控制通过向运行中的房间注入三条 HELLO 验证：无证明与错误证明被忽略，正
确证明被接受并开始协商。账号服务则用无效凭据对真实 LittleSkin 端点跑过：URL、错误解析、204/403 处理与名称
查询均正常 —— 缺失的 Yggdrasil `agent` 字段就是这么发现的。

构建矩阵方面，Fabric 与 Forge 的 1.20.1、1.21.1 都已构建通过（`buildAndCollect`）；NeoForge 1.21.1 的源码与
元数据已就绪，但 `maven.neoforged.net` 在部分网络下不可达，因此节点默认注释，见 `settings.gradle.kts`。

还未验证的部分：真实的双端往返（同一房间内一个托管、一个加入），这需要两个游戏实例或两台机器。
