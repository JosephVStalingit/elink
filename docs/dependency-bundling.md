# 运行时依赖与打包方式

本文记录 WebRTC 与 MQTT 两个库的打包约束，以及最终选定的方案。结论都对照下方引用的上游源码/文档核对过。

## 需要的库

| 用途 | 坐标 |
| --- | --- |
| WebRTC Java 绑定 | `dev.onvoid.webrtc:webrtc-java:0.18.0` |
| WebRTC 原生库（Windows x86-64） | `dev.onvoid.webrtc:webrtc-java:0.18.0:windows-x86_64`（classifier） |
| MQTT 信令 | `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` |

## 约束一：`webrtc-java` 不能被重定位或 shade

`webrtc-java` 通过 `dev.onvoid.webrtc.internal.NativeLoader` 加载原生库，而它是用**写死的资源名**去找的
（原生 jar 的根部只有一个 `webrtc-java-<os>-<arch>.dll`）。用 shadow/relocation 插件改包名或类名、或者用
fat-JAR 把原生 jar 摊平，都会破坏这个查找。因此 `webrtc-java` 必须作为独立、未被修改的 JAR 留在 classpath
上，资源保持原样。

## 约束二：Loader 只认可两种进入 classpath 的途径

Fabric Loader **不会**把任意 JAR 加进 classpath：

* `mods/` 下没有 `fabric.mod.json` 的 JAR 只会被"报告"，不会被加载：`FabricLoaderImpl.setup()` 调用
  `dumpNonFabricMods(discoverer.getNonFabricMods())`，而 `dumpNonFabricMods` 只是打印
  `Found %d non-fabric mod(s)`，没有任何地方为它们调用 `addToClassPath`。
* 嵌套 JAR 只有在父模组的 `fabric.mod.json` 的 `jars` 数组里列出时才会被发现（`ModDiscoverer` 从
  `metadata.getJars()` 开始），而没有元数据的嵌套 JAR 会被丢弃：`ModDiscoverer.computeJarStream()` 在拿到
  没有 `fabric.mod.json` 的流时返回 `null`（这类顶层文件会被放进 `nonFabricMods`）。
* 模组（含嵌套模组）确实会进入 classpath：`FabricLoaderImpl.finishModLoading()` 遍历已加载模组，对每个
  code source 调用 `FabricLauncherBase.getLauncher().addToClassPath(path)`。而 `KnotClassDelegate` 通过
  启动器类加载器解析类与资源（`classLoader.getResource(...)` / `findResourceFwd(...)`），因此这些 JAR 内
  部的资源（包括 WebRTC 的 DLL）对 `NativeLoader` 依然可达。

Loom 的 `include` 配置一次性满足这两点。官方 Loom 参考文档写道：

> `include`：声明一个应当以 jar-in-jar 形式放进最终产物里的依赖。该配置不传递。**对于非模组依赖，Loom 会
> 用「模组 ID 作为名字、版本号保持不变」生成一个带 `fabric.mod.json` 的模组 jar。**

这正是缺的那一块：Loom 会替我们合成 Loader 需要的元数据，于是像 `webrtc-java` 和 Paho 这样的普通（非模组）
库可以被嵌套，并且在完全不改名的情况下进入 classpath。

## 选定方案

1. 把库当作普通依赖声明，并交给 `include`，例如
   `include(implementation("dev.onvoid.webrtc:webrtc-java:0.18.0"))`；原生库则带 classifier（Windows 用
   `windows-x86_64`，其他平台按需追加）。
2. 模组自己的 `fabric.mod.json` 里**不手写** `jars` 数组，交给 Loom 维护。
3. 不使用 `shadowJar`/relocation。上游 JAR 原样放在我们的模组 JAR 内，玩家只需要把
   `mcp2p-<mc>-<loader>-<version>.jar` 丢进 `mods/`。

Forge 与 NeoForge 侧不需要 `include`：Loom 的 jar-in-jar 机制同样打进了它们的产物（`META-INF/jars` 与
`mods.toml` 一起工作）。

## 实测结果

`./gradlew :1.20.1-fabric:buildAndCollect` 产出的 `build/libs/mcp2p-fabric-0.1.0-SNAPSHOT+mc1.20.1.jar`
（约 9.1 MB）内容如下：

| 嵌套 JAR | 大小 | 说明 |
| --- | --- | --- |
| `META-INF/jars/webrtc-java-0.18.0.jar` | 119 KB | API jar，与上游完全一致 |
| `META-INF/jars/webrtc-java-0.18.0-windows-x86_64.jar` | 8.7 MB | 根部就是 `webrtc-java-windows-x86_64.dll` |
| `META-INF/jars/org.eclipse.paho.client.mqttv3-1.2.5.jar` | 242 KB | 与上游完全一致 |

模组的 `jars` 数组由 Loom 自动填充，无需手写：

```json
"jars":[{"file":"META-INF/jars/org.eclipse.paho.client.mqttv3-1.2.5.jar"},
        {"file":"META-INF/jars/webrtc-java-0.18.0-windows-x86_64.jar"},
        {"file":"META-INF/jars/webrtc-java-0.18.0.jar"}]
```

每个嵌套 JAR 都是原样拷贝 + 一个合成的描述符：

```json
{"schemaVersion":1,"id":"dev_onvoid_webrtc_webrtc-java_windows-x86_64","version":"0.18.0",
 "name":"webrtc-java","custom":{"fabric-loom:generated":true}}
```

### 原生库需要声明两次

只有 `include` 是不够的。Loom 仅在**打包**模组时把 included JAR 嵌进去；从 IDE 启动游戏时它们并不在
classpath 上，于是 `NativeLoader` 会去拷贝一个 `null` 流（`NativeLoader.java:64`）：

```
[main/WARN] (mcp2p/rtc) Native WebRTC library is not available on this platform, P2P connections are disabled
    at dev.onvoid.webrtc.internal.NativeLoader.loadLibrary(NativeLoader.java:64)
```

因此原生库的坐标要声明两次：

```kotlin
add("include", "dev.onvoid.webrtc:webrtc-java:$webrtcVersion:$nativesClassifier")
add("runtimeOnly", "dev.onvoid.webrtc:webrtc-java:$webrtcVersion:$nativesClassifier")
```

这样之后，开发服务器会打印：

```
[16:14:52] [main/INFO] (mcp2p/rtc) Native WebRTC library loaded
```

### 还需人工确认的部分

从**发布出去**的模组 jar 内部加载 DLL（此时原生库是通过 Loader 而不是 Gradle 进入 classpath）还没有在真实
客户端安装里跑过。Loader 一侧的原理是清楚的（`FabricLoaderImpl.finishModLoading` 会为每个已加载模组调用
`addToClassPath`，嵌套模组也算），但第一次把构建产物丢进真实 `mods/` 目录时值得再确认一次。

若将来这里出问题，退路是：把这两个库做成自带 `fabric.mod.json` 的独立 JAR（即 Loom 生成的那份元数据），因为
Fabric Loader 会忽略没有元数据的顶层 JAR。
