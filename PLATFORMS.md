# 跨服务端验证手册

> **这份文档解决的问题**：MockBukkit 跑在 Paper 语义上，测不到「别的服务端会不会
> 在插件加载阶段就把你拦掉」。v1.4 的 Folia 防御分支就是这么变成死代码的
> （详见 [DEVELOPMENT.md](DEVELOPMENT.md) §10.8.1）。
>
> **适用场景**：改了 `core/ServerBrand`、动了 `plugin.yml` 的平台声明、
> 想给某个 Paper 分支加「支持」声明、或者怀疑某个分支上有加载期问题。

---

## 1. 为什么不能只靠单元测试

同一个「检测到不支持 → 拒绝加载」的逻辑，在两个服务端上的**可达性完全不同**：

| 分支 | 拒绝发生在哪 | `onEnable` 会不会执行 |
|---|---|---|
| **Spigot** | 插件类内部（缺 `DamageSource` 等） | ✅ 会执行，然后抛 `NoClassDefFoundError` |
| **Folia**（未声明 `folia-supported`） | **provider 注册阶段，早于类加载** | ❌ **完全不执行** |

也就是说：`guardServerBrand()` 即便写得完美、单测全绿、反向验证全做，
在 Folia 上它**仍然一次都不会被调用**——服务端在插件类存在之前就拒了。

这类缺陷的特征：
- 不是「算错了」，而是「没跑到」；
- 所有**函数级**测试天然测不到它（它们都在验证返回值）；
- `ServerBrandWiringMockBukkitTest` 也测不到，因为它跑在 Paper 语义上，没有这一层拦截。

**结论：防御性代码必须真机验证。** 这不是流程洁癖，是 v1.4 实际付过学费的。

---

## 2. 工具

| 文件 | 作用 |
|---|---|
| [test-servers/run-smoke.ps1](test-servers/run-smoke.ps1) | 一键起服 → 用 RCON 读 `/rad status` → 判定「启用 / 拒绝 / 失败」，跑完自动杀进程 |
| [test-servers/server.properties.template](test-servers/server.properties.template) | 各分支共用的极简配置（超平坦、小视距、离线模式） |

**`run-smoke.ps1` 每个分支分配独立游戏端口与 RCON 端口**，互不干扰：

| 分支 | 游戏端口 | RCON |
|---|---|---|
| paper | 25570 | 25770 |
| leaf | 25571 | 25771 |
| purpur | 25572 | 25772 |
| folia | 25573 | 25773 |

> 与 `test-server/`（25565 / 25764）错开，两套可以同时跑。

### 2.1 准备 jar

`test-servers/<分支>/` 下放同名 jar（脚本按固定文件名找）：

| 分支 | 期望文件名 | 获取方式 |
|---|---|---|
| paper | `paper-1.21.11-132.jar` | [PaperMC fill v3](https://fill.papermc.io/v3/projects/paper) |
| leaf | `leaf-1.21.11-179.jar` | [GitHub Releases](https://github.com/Winds-Studio/Leaf)（`ver-1.21.11`） |
| purpur | `purpur-1.21.11-2568.jar` | [purpurmc.org](https://api.purpurmc.org/v2/purpur/1.21.11) —— **注意下载源可能不可达** |
| folia | `folia-1.21.11-14.jar` | [PaperMC fill v3](https://fill.papermc.io/v3/projects/folia) |

> Paper 132 的 SHA-256 应为
> `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`，可用来核对。

### 2.2 运行

```powershell
cd D:\code\FT
$env:JAVA_HOME="D:\java21"
mvn -B package -DskipTests          # 先产出 target/RadSurvival.jar

cd test-servers
# 执行策略通常禁止直接跑 .ps1，用 scriptblock 加载（与 tools/rconlib.ps1 同一手法）
$sb = [scriptblock]::Create((Get-Content .\run-smoke.ps1 -Raw -Encoding utf8))
& $sb -Server paper -root "D:\code\FT\test-servers"      # 单跑
& $sb -Server paper,leaf,folia -root "D:\code\FT\test-servers"   # 多个
& $sb -Server all -root "D:\code\FT\test-servers"                # 全部
```

### 2.3 读结果

脚本输出四列：`Server / Status / Brand / Detail`。

| Status | 含义 | 是否通过 |
|---|---|---|
| `OK` | 服务端起来了、插件启用、RCON 能读到 `platform` 行 | ✅ 该分支可用 |
| `REFUSED-OK` | 插件主动拒绝加载并打印了原因 | ✅ 防御分支生效 |
| `LOADED-NO-PLUGIN` | 服务端起来但插件没启用 | ❌ 查 `smoke-console.log` |
| `SKIP` | jar 缺失或不完整 | — |
| `FAIL` | 服务端没在超时内就绪 | ❌ 查日志 |

`Detail` 里出现 `STACKTRACE:` 前缀才是真异常栈；服务端自身的 `[ERROR]` 提示行
（EULA、超平坦配置）不算——脚本已按「堆栈行特征」过滤，不要退回成匹配裸 `Error`。

---

## 3. 各分支的快速判据

### 3.1 Paper

**期望**：`OK`，`Brand = Paper（类探测）`，7 个监听器。

```
[RadSurvival] 平台 · Paper（类探测）
```

### 3.2 Leaf

**期望**：`OK`，但品牌来源是 **`getName()`** 而非类探测：

```
[RadSurvival] 平台 · Leaf（server.getName()：getName=Leaf）
```

**这不是 bug**。Paper 系分支的 damage 类由 paperclip 在启动时重映射/注入，
插件 `onEnable` 阶段的类加载器未必能 `Class.forName` 到 `DamageSource`。
若这里变成 `Paper（类探测）`，反而要检查名字匹配分支是不是被误删了
（见 [DEVELOPMENT.md](DEVELOPMENT.md) §10.8.2）。

### 3.3 Folia —— **必须看到三条中文提示**

```
[RadSurvival] 检测到服务端 Folia（类探测），本插件无法在此运行，已自动禁用。
[RadSurvival] 原因：Folia 使用区域化线程模型，全局调度器任务（主循环 / HUD / 露天异步补扫）在其上不可用。
[RadSurvival] 请改用 Paper 1.21.11（或 Leaf / Purpur 等 Paper 分支）后重新挂载本插件。
[RadSurvival] Disabling RadSurvival v1.0.0-SNAPSHOT
```

**如果你看到的是这句**（英文、且没有我们的 `[RadSurvival]` 前缀）：

```
[DirectoryProviderSource] Error loading plugin: ... as it is not marked as supporting Folia!
```

说明 `plugin.yml` 的 `folia-supported: true` **被删掉了**，我们的防御代码成了死代码。
这个字段看起来像「声明支持 Folia」，实际只是「允许插件被载入，好让提示接管」——
删它之前请读 [DEVELOPMENT.md](DEVELOPMENT.md) §10.8.1。

### 3.4 Purpur

**期望**：与 Leaf 同路径（`OK` + `getName()` 来源）。

**但当前未实测**：`purpurmc.org` 主站 / CI / Maven 仓库在本网络全部超时，
二进制端点限速约 1.6 KB/s（52 MB 需约 8.6 小时）。
若你所在的网络能正常下载，请补跑并把结论写进
[ACCEPTANCE.md](ACCEPTANCE.md) §M，同时把 ADVANCED §12.1 的「未实测」改掉。

### 3.5 Spigot

**无法用本脚本测**：Spigot 官方从不发布预编译 jar（需 BuildTools 自行构建），
且其源码仓库对外返回 HTTP 451。

现有替代方案是 `ServerBrandSpigotIsolatedTest`——用**屏蔽 `DamageSource` 的受限
`URLClassLoader`** 验证识别逻辑。但它**明确不断言**「真 Spigot 上 `onEnable` 会执行到
拒绝分支」；那是关于 Spigot 行为的推断，不是已验证的事实（被 Folia 教训过之后，
这种推断不该写成测试）。

---

## 4. 排查清单

起不来时按顺序看：

1. **`<分支>/smoke-console.log`** 里找 `[RadSurvival]` 开头的行——插件自己的话都在这里。
2. **有没有我们的 `平台 ·` 行**？没有 = 插件没启用或没进 `onEnable`。
3. **有没有 `Error loading plugin`**？有 = 卡在加载阶段，检查 `plugin.yml` 的
   `folia-supported` / `api-version` / `main`。
4. **`Done (xx s)` 出现了吗**？没出现 = 服务端本身没起来，与插件无关。
5. **RCON 连不上**？核对 `server.properties` 里的 `rcon.port` 与 `rcon.password`
   是否被脚本正确覆写——模板缺键会让 `-replace` 静默失效，服务端回落到默认 25575
   （v1.4 踩过：一度误判为「服务端未就绪」）。脚本现在有键存在性断言，别把它删掉。

**控制台中文乱码**：Windows 控制台是 GBK，插件输出 UTF-8。日志文件本身是好的
（`Get-Content -Encoding utf8` 即可正常读），RCON 回包也正常——**不是插件缺陷**。

---

## 5. 新增一个分支支持声明时

按 plan.md §7.5 的口径，一个 profile 就是一次「支持矩阵声明」。请依次完成：

1. `core/ServerBrand` 的 `Brand` 枚举加项 + `detect()` 加判据；
2. `ServerBrandTest` 补用例（含「类探测与名字冲突时谁优先」）；
3. `run-smoke.ps1` 的 `portMap` / `jarMap` 加项；
4. **真机跑一次**，把版本号与结论记进 ACCEPTANCE §M；
5. 同步 ADVANCED §12.1 支持矩阵表（**逐分支标注是否真机验证过**）；
6. CHANGELOG 写清「新增某某分支支持」还是「仅在某某分支上验证了拒绝」。

> **不要**因为单测通过就把它写成「已验证」。
> [DEVELOPMENT.md](DEVELOPMENT.md) §15 第 9 条规定了「验证层级」的写法。

---

## 6. 相关文档

| 想知道 | 看 |
|---|---|
| 为什么 Folia 的防御代码曾是死代码 | [DEVELOPMENT.md](DEVELOPMENT.md) §10.8.1 |
| 品牌识别的判断顺序与理由 | [DEVELOPMENT.md](DEVELOPMENT.md) §10.8 |
| 实测证据（逐分支日志） | [ACCEPTANCE.md](ACCEPTANCE.md) §M |
| 支持矩阵（面向服主） | [ADVANCED.md](ADVANCED.md) §12.1 |
| 版本变更 | [CHANGELOG.md](CHANGELOG.md) v1.4.0 |
