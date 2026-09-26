# MC P2P

基于 WebRTC 数据通道 + MQTT 信令的 Minecraft 点对点联机模组。

项目用 [Stonecraft](https://stonecraft.meza.gg)（由它应用
[Architectury Loom](https://docs.architectury.dev/loom/introduction) 与各加载器插件）和
[Stonecutter](https://stonecutter.kikugie.dev) 构建，因此同一份共享源码会被编译到每个已注册的
Minecraft 版本上。

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
| `src/main/java/com/example/mcp2p` | 入口，以及 `command`（`/mcp2p` 命令）、`client`（LAN 端口）、`config`、`identity`（账号）、`signalling`、`rtc`、`net`、`tunnel`、`lan`、`platform`（加载器差异） |
| `src/main/resources` | 共享资源：`fabric.mod.json`、`META-INF/mods.toml`、`META-INF/neoforge.mods.toml`、`mcp2p.accesswidener` |
| `versions/dependencies/*.properties` | 按 Minecraft 版本的依赖锁定（`loader_version`、`fabric_version`、`forge_version`、`neoforge_version`） |
| `docs/` | 设计说明，例如 [依赖打包方式](docs/dependency-bundling.md) 与 [架构说明](docs/architecture.md) |
| `build.gradle.kts`、`settings.gradle.kts` | Stonecraft/Stonecutter 配置 |

`build.gradle.kts` 是唯一的构建脚本：Stonecraft 已经应用了绝大部分配置，`modSettings { }` 用于个性化设置。
加载器之间的差异集中在 `platform/Platform`（配置目录、模组版本、是否客户端、命令注册时机），其余代码是
跨加载器通用的普通 Java。

## 注意事项

- `fabric.mod.json` 是模板：`${id}`、`${name}`、`${version}`、`${group}`、`${description}`、
  `${minecraftVersion}`、`${fabricVersion}` 由 Stonecraft 按版本填充；`accessWidener` 字段与
  `META-INF/jars` 数组由 Loom 写入。
- `META-INF/mods.toml`（Forge，以及 NeoForge 1.20.1）与 `META-INF/neoforge.mods.toml`（NeoForge 1.21.1）
  使用同一套变量替换；Forge 依赖的加载器版本取自 `forge_version` 去掉 Minecraft 前缀后的主版本。
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
| `friends` | 空 | 允许连接的账号（逗号分隔）；留空表示"持有房间密钥即可" |
| `identity-cache-minutes` | `10` | 账号验证结果复用时长（分钟），`0` 表示不复用 |
| `stun-servers` | 一个公共 STUN | 逗号、分号或空格分隔的 STUN / TURN 地址 |
| `data-channel` | `mcp2p` | 承载隧道的 WebRTC 数据通道标签 |
| `local-port` | `0` | 加入方监听的本地端口；`0` 表示**自动选择空闲端口**（推荐，实际端口会广播给客户端），填具体值则固定 |
| `forward-port` | `0` | 托管方要共享的游戏端口；`0` 表示使用"对局域网开放"生成的端口 |
| `auto-join` | `false` | 启动即加入房间，并按 `forward-port` 自动扮演托管或加入 |

令牌不会以明文写入配置文件：它们保存在 `config/mcp2p.secrets`，密钥在 `config/mcp2p.key`（Windows 上用
DPAPI 绑定当前账户；其他平台为本地密钥文件）。旧版本遗留在配置文件里的明文令牌会在启动时自动迁移。

处于对称 NAT 后的玩家需要 TURN 服务器，填进 `stun-servers` 即可。

## 使用方法

### 1. 安装

把对应 Minecraft 版本与加载器的 jar 放进 `mods/`（WebRTC 与 MQTT 库已经打包在 jar 内部，无需另外下载）：

| 你的环境 | 放进 `mods/` 的文件 | 额外需要 |
| --- | --- | --- |
| 1.20.1 Fabric | `mcp2p-fabric-0.1.0-SNAPSHOT+mc1.20.1.jar` | Fabric Loader ≥ 0.16.14、Fabric API ≥ 0.92.12 |
| 1.21.1 Fabric | `mcp2p-fabric-0.1.0-SNAPSHOT+mc1.21.1.jar` | Fabric Loader ≥ 0.16.14、Fabric API ≥ 0.116.17 |
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
| `/mcp2p host` | 共享当前"对局域网开放"的世界（首次会生成房间密钥） |
| `/mcp2p join <房间码> [密钥]` | 加入房间并开始本地监听 |
| `/mcp2p leave` | 离开房间、关闭隧道、撤回服务器列表条目 |
| `/mcp2p login <邮箱> <密码>` | 用 LittleSkin 账号登录（令牌写入加密存储） |
| `/mcp2p whoami` | 查看当前登录的账号 |
| `/mcp2p friends` | 查看好友名单 |
| `/mcp2p friends <名字或UUID…>` | 只允许这些账号连接（同时开启账号校验） |
| `/mcp2p friends clear` | 清空好友名单，回到"有房间密钥即可" |

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
  `HMAC-SHA256(secret, room|sender|type)` 证明，没有证明的消息在任何 WebRTC 动作之前就被丢弃。它是"持有即可
  用"的凭据，请私下传递。
- **账号白名单**（可选）：先 `/mcp2p login <邮箱> <密码>`，再 `/mcp2p friends Alice Bob 069a79f4-…`。此后房主
  要求每位来客证明自己拥有该账号：来客用随机 id 向 LittleSkin 声明正在加入
  （`session/minecraft/join`），房主再问 LittleSkin 该账号是否真的用这个 id 加入了（`hasJoined`）。只有账号
  持有者能完成第一步（它需要令牌），证明通过前房主完全忽略对方。

### 8. 排查

| 现象 | 处理 |
| --- | --- |
| `/mcp2p host` 提示 `Open this world to LAN first` | 还没有"对局域网开放"；专用服务器上请设置 `forward-port` |
| 提示 `Could not reach the signalling broker` | 连不上 `broker`，换一个 MQTT 服务器（自建 Mosquitto / EMQX 均可） |
| 日志出现 `Native WebRTC library is not available` | 当前平台缺少 WebRTC 原生库（目前只打包了 Windows x86-64） |
| 多人游戏列表里没有条目 | 确认隧道处于"加入"状态（`/mcp2p status` 显示 Joining）；条目由本机广播生成，需在多人游戏界面稍等片刻 |
| 双方都在线却连不上 | 双方必须使用同一个 `broker` 与同一个房间；对称 NAT 下需要 TURN，填进 `stun-servers` |
| 日志出现 `does not prove that it knows the room secret` | 房间密钥不一致，用最新的密钥重新 `join` |
| 账号校验一直失败 | 密码或令牌过期：重新 `/mcp2p login`；或先 `/mcp2p friends clear` 关闭白名单 |
| 想彻底重置 | 关闭游戏，删除 `config/mcp2p.properties`、`mcp2p-id.txt`、`mcp2p.key`、`mcp2p.secrets` |

### 9. 隐私提示

- `config/mcp2p.key` 与 `config/mcp2p.secrets` 保存着你的账号令牌（Windows 上由 DPAPI 绑定当前账户），不要
  发给别人，也不要把整个 `config` 目录打包分享；
- 房间密钥同样是"持有即可进入"的凭据，请私下传递；
- broker 上可见房间主题与信令内容（SDP、ICE、名字），看不到游戏数据 —— 游戏数据全部走 WebRTC 直连。

## 许可证

MIT，见 [LICENSE](LICENSE)。
