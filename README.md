# MC P2P

> 一个让 Minecraft Java 版玩家**绕过中转服务器**直接互联的客户端模组。

---

## 项目简介

MC P2P 是一个轻量级的 Minecraft Java 版客户端模组，目标是把“局域网联机”的能力带到互联网两端
互相不可见的玩家之间。模组在**双方玩家的机器之间**直接建立 P2P 数据通道，让你的 Minecraft 客户端
在多人游戏列表里就像看到朋友开了一个本地局域网一样可以直接加入。

它解决了两个常见问题：

- **公网 IP / 端口转发**：你和朋友都没有公网 IP，或者你不想（不会）配置路由器端口转发；
- **服务器租赁**：你只想跟两三个朋友开一局生存/创造，并不想为此租一台 24 小时在线的专用服务器。

核心思路是**双管齐下**的 NAT 穿透：

| 通道 | 用途 | 适用场景 |
| --- | --- | --- |
| WebRTC 数据通道（DTLS + SCTP） | 主通道，由 MQTT 协商 SDP/ICE 后建立 | 双方其中一方在 NAT/路由器后；双方其中一方有公网 IPv6 |
| UDP 生日悖论打洞（自实现 QUIC 风格管道） | 备选通道，命中后**完全替代** WebRTC 承载 Minecraft 流量 | **双方都在对称 NAT 后**，连 STUN 反射地址都互不可达——这是传统 STUN/TURN 失败的场景 |

模组内置 9 个分布式 STUN 服务器（默认开启），配合“用样本推断 NAT 端口分配规律”+“N 个本地端口并行扫描”
的生日悖论算法，对称 NAT 下也能在数秒内打出一条直连路径；只有打洞也失败时才退回到 TURN 中继。

### 主要特性

- **完全点对点**：游戏数据（TCP 流量）在玩家之间直传，没有任何中转服务器经手流量；
- **多版本多加载器**：同一份源码编译到 Minecraft 1.20.1 与 1.21.1，覆盖 Fabric、Forge（1.21.1 还覆盖 NeoForge）；
- **LittleSkin 账号集成**：可选启用账号白名单，让房主能精确控制谁能进（基于 Yggdrasil `join`/`hasJoined` 挑战应答）；
- **房间密钥（HMAC-SHA256）**：默认开启，每条信令自动签名，**无法仅凭房间码接入**；
- **AES-256-GCM 加密 + 可靠 UDP**：UDP 打洞通道上的所有游戏流量都被加密并保证有序到达；
- **依赖全打包**：单个 jar 即可部署，**不依赖客户端任何其他 mod**；
- **隐私默认开启**：账号令牌在 Windows 上由 DPAPI 绑定当前账户加密保存；非 Windows 平台用本地密钥文件。

### 与传统解决方案的对比

| 方案 | 流量路径 | 延迟 | 服务器依赖 | 抗 NAT |
| --- | --- | --- | --- | --- |
| 内网穿透（frp / zerotier） | 客户端 → 中转服务器 → 客户端 | 高（取决于中转） | 必须有一台公网中转 | 强 |
| 公共 Minecraft 服务器 | 客户端 → 服务器 → 客户端 | 中 | 必须租用 | 强 |
| 端口转发 | 客户端 → 路由器 → 客户端 | 低 | 双方都需要路由器权限 | 弱 |
| **MC P2P** | **客户端 ↔ 客户端直连** | **最低** | **不需要**（只借 MQTT 信令） | **强**（含对称 NAT） |

---

## 目标矩阵

| Minecraft | 加载器 | Java 工具链 | 版本目录 |
| --- | --- | --- | --- |
| 1.20.1 | Fabric、Forge | 17 | `versions/1.20.1-fabric`、`versions/1.20.1-forge` |
| 1.21.1 | Fabric、Forge、NeoForge | 21 | `versions/1.21.1-fabric`、`versions/1.21.1-forge`、`versions/1.21.1-neoforge` |

新增一个版本只要两步：在 `settings.gradle.kts` 里加一行
（`mc("<Minecraft 版本>", "fabric", "forge")`），再加一个
`versions/dependencies/<Minecraft 版本>.properties`。`versions/*-*` 下的内容全部由 Stonecutter 从
共享的 `src/` 生成，不提交到仓库。

> 两点说明：NeoForge 1.21.1 的源码与元数据已经就绪，但只有在能访问 `maven.neoforged.net` 时才建议启用
> （在 `settings.gradle.kts` 中取消注释即可）；1.20.1 的 NeoForge 仍发布在 `net.neoforged:forge`
> 坐标下，而当前 Loom 的 NeoForge provider 不接受该坐标，所以 1.20.1 只产出 Fabric 与 Forge。

## 环境要求

- **JDK 21** 用来运行 Gradle 本身：Stonecraft 1.14.2 与 Stonecutter 0.9.8 都按 Java 21 编译，所以构建
  所用的 JVM 必须是 21 或更高，`JAVA_HOME` 应指向它。
- 机器上还需**装有 JDK 17**，用于 Minecraft 1.20.x 的编译工具链（Stonecraft 按版本配置工具链；
  Minecraft 1.21.x 使用 Java 21 工具链）。
- 首次运行时 Gradle wrapper 会自动下载 Gradle 9.7.1。

Gradle 通过工具链自动探测寻找 JDK，而自动探测并不总能找到本机安装的 JDK（例如装在非常规目录下的
Microsoft JDK 17），因此 `gradle.properties` 里的 `org.gradle.java.installations.paths` 明确列出了 JDK
路径；JDK 装在别处时请修改或删除该行。

如果 `PATH` 上的 `java` 低于 21，构建前先把 wrapper 指向 JDK 21（`set JAVA_HOME=...`），否则插件解析会
报 `Dependency requires at least JVM runtime version 21. This build uses a Java 17 JVM.`

## 常用命令

以下命令都在项目根目录执行：

```shell
./gradlew buildActive               # 只构建当前激活的 Stonecutter 版本
./gradlew chiseledBuildAndCollect   # 构建全部 Minecraft/加载器组合，产物汇总到 build/libs
./gradlew runActive                 # 启动带模组的 Minecraft 客户端
./gradlew runActiveServer           # 启动带模组的专用服务器
./gradlew tasks                     # 列出切换版本的任务与各版本的任务
```

`buildActive`、`runActive`、`runActiveServer` 都跟随**当前激活的版本**，即 `stonecutter.gradle.kts`
里的 `stonecutter active "..."` 那一行。用 Stonecutter 生成的任务来切换（它们也在 `./gradlew tasks`
的 `stonecutter` 分组中列出）：

```shell
./gradlew "Set active project to 1.21.1-fabric"
./gradlew "Reset active project"     # 回到默认版本，提交代码前请执行一次
```

也可以直接指定某个版本的任务，例如 `./gradlew :1.20.1-fabric:buildAndCollect`、
`:1.20.1-forge:runClient`、`:1.21.1-forge:runServer`。

## 目录结构

| 路径 | 用途 |
| --- | --- |
| `src/main/java/com/example/mcp2p` | 入口，以及 `command`（`/mcp2p` 命令）、`client`（LAN 端口）、`config`、`identity`（账号）、`signalling`、`rtc`、`net`、`tunnel`、`lan`、`platform`（加载器差异）、`punch`（UDP 打洞 + QUIC 风格管道） |
| `src/main/resources` | 共享资源：`fabric.mod.json`、`META-INF/mods.toml`、`META-INF/neoforge.mods.toml`、`mcp2p.accesswidener` |
| `versions/dependencies/*.properties` | 按 Minecraft 版本的依赖锁定（`loader_version`、`fabric_version`、`forge_version`、`neoforge_version`） |
| `docs/` | 设计说明，例如 [依赖打包方式](docs/dependency-bundling.md)、[架构说明](docs/architecture.md)、[代码导读](docs/代码导读.md) |
| `build.gradle.kts`、`settings.gradle.kts` | Stonecraft/Stonecutter 配置 |

`build.gradle.kts` 是唯一的构建脚本：Stonecraft 已经应用了绝大部分配置，`modSettings { }` 用于个性化设置。
加载器之间的差异集中在 `platform/Platform`（配置目录、模组版本、是否客户端、命令注册时机），其余代码是
跨加载器通用的普通 Java。

## 协议与架构（高层概览）

完整说明见 [docs/architecture.md](docs/architecture.md) 与 [docs/代码导读.md](docs/代码导读.md)，下面是
最关键的两条路径：

```
┌──────────┐    MQTT(Subscribe room topic)    ┌──────────┐
│ Player A │ ◀───────────────────────────────▶ │ Player B │
└────┬─────┘                                  └────┬─────┘
     │ 1. SDP offer/answer + ICE candidates          │
     │    （决定谁先发起：installId 字典序小的一方）   │
     │ 2.（可选）STUN_SAMPLES + PUNCH_PORT          │
     │    — 用于双方都在对称 NAT 时跳过 STUN         │
     ▼                                              ▼
┌────────────────┐                          ┌────────────────┐
│ WebRTC DataCh  │ ◀═════ 直连通道 ═════════▶│ WebRTC DataCh  │
│   (DTLS/SCTP)  │      （首选）             │   (DTLS/SCTP)  │
└────────┬───────┘                          └────────┬───────┘
         │ 上两者至少一条成功                       │
         ▼                                          ▼
┌─────────────────────────────────────────────────────────────┐
│  TunnelMultiplexer（自定义帧协议）— 把 Minecraft TCP 流量   │
│  复用到上面任一可用通道                                     │
└─────────────────────────────────────────────────────────────┘
```

**QUIC 风格的 UDP 打洞通道**（双侧对称 NAT 场景）：模块位于 `punch/`，由以下三部分构成：

1. **`StunClient`**：RFC 5389 最小实现，零依赖，用来向多个 STUN 服务器查询“反射端口”；
2. **`HolePuncher`**：生日悖论扫描（N 个本地端口 × 对方预测端口区间），命中后返回一条直连路径；
3. **`UdpLink`**：自实现的加密可靠 UDP 管道（AES-256-GCM + 序号+确认位图 + 重传 + 乱序缓冲）。

P2P 通信的“持有房间密钥即证明身份”机制：

```
每条 MQTT 消息 → proof = HMAC-SHA256(roomSecret, room + "|" + from + "|" + type)
```

收端用同样的密钥算一遍并常量时间比对，不匹配直接丢弃。

## 注意事项

- `fabric.mod.json` 是模板：`${id}`、`${name}`、`${version}`、`${group}`、`${description}`、
  `${minecraftVersion}`、`${fabricVersion}` 由 Stonecraft 按版本填充；`accessWidener` 字段与
  `META-INF/jars` 数组由 Loom 写入。
- `META-INF/mods.toml`（Forge，以及 NeoForge 1.20.1）与 `META-INF/neoforge.mods.toml`（NeoForge 1.21.1）
  使用同一套变量替换；Forge 依赖的加载器版本取自 `forge_version`，NeoForge 1.21.1 取自
  `neoforge_version`。
- `mcp2p.accesswidener` 目前没有任何条目；它的头部命名空间必须保持 `named`（Mojmap），否则 Loom 在
  setup 阶段会报 `Namespace mismatch, expected named got official`。
- `runActiveServer` 需要先接受一次 EULA：创建内容为 `eula=true` 的 `run/eula.txt`（`run/` 是生成目录，
  已被 git 忽略）。
- `webrtc-java` 与 Paho 都打包在模组 jar 内，玩家只需要这一个文件；为什么不能 shade 见
  [docs/dependency-bundling.md](docs/dependency-bundling.md)，信令协议与分层见
  [docs/architecture.md](docs/architecture.md)。

## 配置文件

配置位于 `config/mcp2p.properties`，首次启动时会自动写入默认值：

| 键 | 默认值 | 含义 |
| --- | --- | --- |
| `broker` | `tcp://broker.emqx.io:1883` | 信令用的 MQTT 服务器；自建后改成自己的地址 |
| `topic-prefix` | `mcp2p` | MQTT 主题前缀 |
| `room` | 自动生成 | 分享给好友的房间码，例如 `MCP2P-7F3A9C` |
| `room-secret` | 由 `/mcp2p host` 生成 | 协商前必须证明自己知道的房间密钥；留空表示房间开放 |
| `littleskin-url` | `https://littleskin.cn/api/yggdrasil` | 账号服务地址，可换成其他 LittleSkin 兼容服务 |
| `access-token` | 空 | 账号令牌，由 `/mcp2p login` 写入；加密保存，配置文件里只留空值 |
| `client-token` | 空 | 与 access token 配套的 client token |
| `friends` | 空 | 允许连接的账号（逗号分隔）；留空表示“持有房间密钥即可” |
| `identity-cache-minutes` | `10` | 账号验证结果复用时长（分钟），`0` 表示不复用 |
| `stun-servers` | **9 个分布式公共 STUN** | 逗号、分号或空格分隔的 STUN 地址；多填几个能收集到更多反射样本，对称 NAT 下的端口推断更准 |
| `turn-servers` | 空 | TURN 地址（`turn:` / `turns:`），打洞也失败时的兜底中继 |
| `turn-username` / `turn-password` | 空 | TURN 凭证（同一组凭证用于 `turn-servers` 里的所有地址） |
| `ice-transport-policy` | `all` | `all` = 直连优先、中继兑底；`relay` = 强制只走 TURN（不向对方暴露 IP） |
| `ice-restart-attempts` | `2` | 打洞失败后自动重启 ICE 的次数，`0` 表示不重启 |
| `ice-port-min` / `ice-port-max` | `0` / `0` | 本地候选端口范围；`0` 表示不限（收窄后某些 NAT 的映射更稳定） |
| `enable-ipv6` | `true` | 允许收集 IPv6 候选；双方都有公网 IPv6 时完全绕开 NAT |
| `data-channel` | `mcp2p` | 承载隧道的 WebRTC 数据通道标签 |
| `local-port` | `0` | 加入方监听的本地端口；`0` 表示**自动选择空闲端口**（推荐，实际端口会广播给客户端），填具体值则固定 |
| `forward-port` | `0` | 托管方要共享的游戏端口；`0` 表示使用“对局域网开放”生成的端口 |
| `auto-join` | `false` | 启动即加入房间，并按 `forward-port` 自动扮演托管或加入 |

令牌不会以明文写入配置文件：它们保存在 `config/mcp2p.secrets`，密钥在 `config/mcp2p.key`（Windows 上用
DPAPI 绑定当前账户；其他平台为本地密钥文件）。旧版本遗留在配置文件里的明文令牌会在启动时自动迁移。

### 默认 STUN 服务器列表

模组默认配置了 9 个分布式公共 STUN（按地理与稳定性挑选），对**对称 NAT 推断**最关键的“样本数”有直接
影响——样本越多，预测对方可能使用的端口区间越准。下列默认列表可以整体覆盖（用空字符串）或逐个增删：

```
stun.l.google.com:19302        stun.cloudflare.com:3478    stun.nextcloud.com:3478
stun.miwifi.com:3478           stun.chat.bilibili.com:3478 stun1.l.google.com:19302
stun2.l.google.com:19302       stun3.l.google.com:19302     stun4.l.google.com:19302
```

如果仍处于对称 NAT 后且**STUN 反射地址互不可达**（两端都看不到对方的“反射地址”），最终会退到 TURN。
这时需要配置 `turn-servers` / `turn-username` / `turn-password`。

## 安装与使用

### 1. 部署模组

从 [Releases](../../releases) 下载对应版本，扔到客户端或服务端的 `mods/` 目录即可：

| 平台 | 文件名 | 备注 |
| --- | --- | --- |
| 1.20.1 Fabric | `mcp2p-fabric-0.1.0-SNAPSHOT+mc1.20.1.jar` | Fabric Loader 0.14+ |
| 1.21.1 Fabric | `mcp2p-fabric-0.1.0-SNAPSHOT+mc1.21.1.jar` | Fabric Loader 0.16+ |
| 1.20.1 Forge | `mcp2p-forge-0.1.0-SNAPSHOT+mc1.20.1.jar` | Forge 47.4 及以上 |
| 1.21.1 Forge | `mcp2p-forge-0.1.0-SNAPSHOT+mc1.21.1.jar` | Forge 52.1 及以上 |

### 2. 第一次启动

启动游戏（或服务器）后会自动生成这些东西：

| 文件 | 内容 |
| --- | --- |
| `config/mcp2p.properties` | 全部设置，可随时编辑（改完重启游戏生效） |
| `config/mcp2p-id.txt` | 本安装的固定 ID，用来在对端之间区分你 |
| `config/mcp2p.key` + `config/mcp2p.secrets` | 账号令牌的加密存储（Windows 上主密钥由 DPAPI 绑定当前账户） |

日志里应出现 `The native WebRTC library is ready`，看到它就说明 WebRTC 可以用了；若看到
`WebRTC is unavailable on this platform`，说明当前平台缺少原生库（目前只打包了 Windows x86-64）。

### 3. 房主：共享自己的世界

1. 进入单人世界，按 `Esc` → **对局域网开放** → 开始（这一步是必要的，模组要从这里拿到世界监听的端口）；
2. 在聊天栏执行：

```
/mcp2p host
```

3. 聊天栏会给出房间码与房间密钥，形如：

```
Sharing the world on 127.0.0.1:54123
Friends join with /mcp2p join MCP2P-7F3A9C 5f2c1d8e9a4b6c3d1e0f7a8b9c0d1e2f
```

4. 把最后那一整条 `join` 命令私下发给好友即可。

### 4. 好友：加入房间

在聊天栏执行房主给你的那条命令（房间没有密钥时可以只写房间码）：

```
/mcp2p join MCP2P-7F3A9C 5f2c1d8e9a4b6c3d1e0f7a8b9c0d1e2f
```

然后打开 **多人游戏**：列表里会自动出现一条 `MC P2P MCP2P-7F3A9C - <房主名>`，点它加入即可。本地监听的
端口由系统自动分配，不需要手动输入；`/mcp2p status` 会显示实际端口，需要手动直连时用它。

### 5. 命令速查

| 命令 | 作用 |
| --- | --- |
| `/mcp2p` 或 `/mcp2p status` | 查看房间、角色、隧道连接数、账号与密钥状态 |
| `/mcp2p host` | 共享当前“对局域网开放”的世界（首次会生成房间密钥） |
| `/mcp2p join <房间码> [密钥]` | 加入房间并开始本地监听 |
| `/mcp2p leave` | 离开房间、关闭隧道、撤回服务器列表条目 |
| `/mcp2p login <邮箱> <密码>` | 用 LittleSkin 账号登录（令牌写入加密存储）；邮箱与密码可含 `@` `.` `+` `-` 等特殊字符 |
| `/mcp2p whoami` | 查看当前登录的账号 |
| `/mcp2p friends` | 查看好友名单 |
| `/mcp2p friends <名字或UUID…>` | 只允许这些账号连接（同时开启账号校验） |
| `/mcp2p friends clear` | 清空好友名单，回到“有房间密钥即可” |

### 6. 专用服务器

服务器上同样放 jar，然后编辑 `config/mcp2p.properties`：

```properties
auto-join=true
forward-port=25565        # 换成你服务器的实际端口
```

这样开服即自动加入房间并共享该端口，房间码与密钥可在日志里看到，也可以用 `/mcp2p status` 查询。

### 7. 限制谁能进来

房间有两道防线。

- **房间密钥**（默认开启）：`/mcp2p host` 首次生成，之后每条信令都携带
  `HMAC-SHA256(secret, room|sender|type)` 证明，没有证明的消息在任何 WebRTC 动作之前就被丢弃。它是“持有即可
  用”的凭据，请私下传递。
- **账号白名单**（可选）：先 `/mcp2p login <邮箱> <密码>`，再 `/mcp2p friends Alice Bob 069a79f4-…`。此后房主
  要求每位来客证明自己拥有该账号：来客用随机 id 向 LittleSkin 声明正在加入
  （`session/minecraft/join`），房主再问 LittleSkin 该账号是否真的用这个 id 加入了（`hasJoined`）。只有账号
  持有者能完成第一步（它需要令牌），证明通过前房主完全忽略对方。

### 8. 排查

| 现象 | 处理 |
| --- | --- |
| `/mcp2p host` 提示 `Open this world to LAN first` | 还没有“对局域网开放”；专用服务器上请设置 `forward-port` |
| 提示 `Could not reach the signalling broker` | 连不上 `broker`，换一个 MQTT 服务器（自建 Mosquitto / EMQX 均可） |
| 日志出现 `Native WebRTC library is not available` | 当前平台缺少 WebRTC 原生库（目前只打包了 Windows x86-64） |
| 多人游戏列表里没有条目 | 确认隧道处于“加入”状态（`/mcp2p status` 显示 Joining）；条目由本机广播生成，需在多人游戏界面稍等片刻 |
| 双方都在线却连不上 | 双方必须使用同一个 `broker` 与同一个房间；日志里会打印候选类型统计，并按情况提示是否需要 TURN |
| 日志提示“两侧都没有 relay 候选” | 双方很可能都在对称 NAT 后：配置 `turn-servers` / `turn-username` / `turn-password`；或保留默认的 9 个 STUN，让模组先尝试打洞通道 |
| 日志看到 `[punch <peerId>] gave up` | 在这次会话内未能命中；可以重新 `/mcp2p leave` + `/mcp2p join` 让打洞重跑 |
| 有 IPv6 却仍走 IPv4 | 确认 `enable-ipv6=true`（默认开启），并检查系统是否真的拿到了公网 IPv6 |
| 想隐藏双方真实 IP | 把 `ice-transport-policy` 设为 `relay`，所有流量强制走 TURN |
| 日志出现 `does not prove that it knows the room secret` | 房间密钥不一致，用最新的密钥重新 `join` |
| 账号校验一直失败 | 密码或令牌过期：重新 `/mcp2p login`；或先 `/mcp2p friends clear` 关闭白名单 |
| 想彻底重置 | 关闭游戏，删除 `config/mcp2p.properties`、`mcp2p-id.txt`、`mcp2p.key`、`mcp2p.secrets` |

### 9. 隐私提示

- `config/mcp2p.key` 与 `config/mcp2p.secrets` 保存着你的账号令牌（Windows 上由 DPAPI 绑定当前账户），不要
  发给别人，也不要把整个 `config` 目录打包分享；
- 房间密钥同样是“持有即可进入”的凭据，请私下传递；
- broker 上可见房间主题与信令内容（SDP、ICE、名字），看不到游戏数据 —— 游戏数据全部走 WebRTC
  直连通道或打洞后的 UDP 加密通道。

## 许可证

MIT，见 [LICENSE](LICENSE)。
