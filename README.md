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

## 一起联机

1. 房主照常开一个单人世界并"对局域网开放"，然后执行 `/mcp2p host`；聊天栏会打印房间码与房间密钥，
   形如 `/mcp2p join MCP2P-7F3A9C 4f1c9b…`。
2. 好友执行 `/mcp2p join MCP2P-7F3A9C 4f1c9b…`（房间没有密钥时可省略密钥）。
3. 共享的世界会自动出现在好友的多人游戏列表里，名字是 `MC P2P MCP2P-7F3A9C - <房主>`，地址是
   `127.0.0.1:<自动选择的端口>`（`/mcp2p status` 会显示这个端口）；手动直连同一地址也可以。
4. `/mcp2p status` 显示角色、房间、是否启用密钥以及当前隧道连接数；`/mcp2p leave` 关闭全部并撤回列表条目。

专用服务器不需要命令：设置 `auto-join=true` 与 `forward-port=25565` 即可。

所有人必须使用同一个 broker 和同一个房间。broker 不转发游戏数据，它只承载建立连接所需的 SDP 与 ICE 消息。

### 账号与房间密钥

房间有两道防线。

**房间密钥**把陌生人挡在信令之外：公共 broker 上任何人只要知道主题名就能订阅，而房间码又容易猜。
`/mcp2p host` 首次会生成密钥，之后每条信令都携带 `HMAC-SHA256(secret, room|sender|type)` 证明；没有证明
的消息在任何 WebRTC 动作之前就被丢弃。它是"持有即可用"的凭据，请私下分享。

**账号证明**决定谁受欢迎。用 `/mcp2p login <邮箱> <密码>` 登录一次即可；游戏会把令牌与身份交给加密存储
（`config/mcp2p.secrets`）。设置好友名单后（`/mcp2p friends Alice Bob 069a79f4-…`，用
`/mcp2p friends clear` 清空），房主要求每个新玩家证明自己的账号：新玩家用随机 id 向 LittleSkin 声明自己
正在加入（`session/minecraft/join`），房主再问 LittleSkin 该账号是否真的用这个 id 加入了（`hasJoined`）。
只有账号持有者才能完成第一步，因为它需要令牌。在证明通过之前，房主完全忽略该玩家。

其余子命令：`/mcp2p whoami` 查看已登录账号，`/mcp2p status` 查看房间、隧道与账号状态，直接输入 `/mcp2p`
等同于 `status`。

## 许可证

MIT，见 [LICENSE](LICENSE)。
