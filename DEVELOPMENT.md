# RadSurvival 开发文档

> 面向**维护者与二次开发者**。使用者请看 [README.md](README.md)，只看配置/命令/API 看
> [ADVANCED.md](ADVANCED.md)，设计规格与决策记录看 [plan.md](plan.md)，实机验收证据看
> [ACCEPTANCE.md](ACCEPTANCE.md)。
>
> 最后更新：2026-10-25（补充 CI/CD 工作流、架构决策记录、扩展点速查）

---

## 0. 上手路径（新维护者从这里开始）

| 顺序 | 做什么 | 耗时 |
|---|---|---|
| 1 | 跑通 `mvn -B clean package`，确认 **265 条全绿** | ~2 min |
| 2 | 读 [§3 一轮结算的完整链路](#3-一轮结算的完整链路)，这是全插件的主动脉 | 5 min |
| 3 | 读 [§10 已知问题与踩坑](#10-已知问题与踩坑记录)，避开历史真实故障 | 10 min |
| 4 | 读 [§4 伤害出口](#4-伤害出口damageapplier)，改伤害相关代码前必看 | 5 min |
| 5 | 想动手时查 [§13 扩展点速查](#13-扩展点速查按需求找位置) | 随时 |
| 6 | **要改既有逻辑前**，先看 [§10.5 排查方法论](#105-v12-排查方法论怎么把看起来没问题的代码查出洞) | 8 min |

> **最容易被忽略的一条**：本插件的伤害数字**不是**靠 `DamageType` 区分的，
> 而是靠「结算瞬间摘掉盔甲 + 时间窗标记」这套机制。看到相关代码觉得绕，先读 §4.2。

---

## 1. 项目速览

| 项 | 值 |
|---|---|
| 目标平台 | Paper **1.21.11**（build 132 实测），`api-version: '1.21'` |
| 语言/JDK | Java **21**（`maven.compiler.release=21`） |
| 构建 | Maven 3.10+，JDK 21（本机验证：`D:\java21`，Azul Zulu 21.0.12.1） |
| 产物 | 自包含 fat jar，约 1.8 MB，内嵌库已 relocate 到 `com.ft.radsurvival.lib.*` |
| 测试规模 | **265 条**（纯内核 + MockBukkit 集成），全绿、无跳过 |
| NMS | **零依赖**（纯 Bukkit/Paper API） |

**一句话架构**：`config` 解析并校验 → `core` 七单例承载全部纯逻辑 → `RadiationTask` 每
`tick-interval` 跑一轮 → `feedback` 负责表现 → `item` 承载抗尘埃药 → `api` 对外收敛读写。

---

## 2. 包结构与职责

```
com.ft.radsurvival
├── RadSurvivalPlugin      插件入口：装配、reload、onDisable（薄壳，不含业务逻辑）
├── command/               命令层
│   ├── SubCommand          子命令枚举（路由表 / 别名 / 权限）
│   ├── RadCommand          /rad 各分支实现（status / dust / reload …）
│   └── CommandOutput       统一排版（fixed / percent / trim）
├── config/
│   ├── PluginConfig       纯 Java 配置模型 + 全量校验（不依赖 Bukkit 服务）
│   └── (内部 record)      ArmorConfig / CorrosionConfig / FeedbackConfig /
│                          CommandsConfig / AntiDustConfig / DustLevel
├── core/                  ★ 判定与结算内核（可纯单元测试）
│   ├── RadiationMath      伤害/累积/倍率/曲线的纯函数（单调有界）
│   ├── DayNightProvider   昼夜阶段与 timeFactor（含线性插值）
│   ├── ArmorCalculator    部位权重 × 材质系数 → 减免率
│   │                      + applyFloor() 最低杀伤保底（v1.2）
│   ├── DustImmunity       ★ 抗尘埃药免疫窗口（注入时钟，v1.2）
│   ├── SunExposureChecker 露天判定（段缓存 + 异步补扫 + 粘滞层）
│   ├── CorrosionMeter     腐蚀值权威存储（内存）+ PDC 持久化 + 阶段回调
│   ├── DamageApplier      事件广播 + 真实掉血（唯一伤害出口）
│   ├── RadiationTask      主循环：过滤链 → 判定 → 腐蚀步进 → 免疫 → 伤害
│   ├── PlayerRadState     单玩家结算镜像（HUD/调试读口）
│   └── RadiationServices  七单例容器（reload 时整体重建）
├── item/                  ★ 抗尘埃药（v1.2）
│   ├── DustItemFactory    物品构建 + PDC 档位标识 + 档位反查
│   ├── DustCraftStructure 多方块结构判定（发射器 + 上方铁砧 + 8 邻原木）
│   └── DustCraftGui       ★ 合成界面与触发（木棍右键铁砧 / 容器使用约束）
├── feedback/              表现层
│   ├── FeedbackService     HUD 双条 / 阶段播报 / 粒子音效 / BossBar（兼作受击监听器）
│   ├── HudRenderer         纯字符串渲染（等级条 / 腐蚀条 / ◆抗尘 标记，可纯单测）
│   ├── HurtFlashEnhancer   红闪抑制抽象（无协议库时的降级实现）
│   └── ProtocolLibHurtFlashEnhancer  红闪抑制（检测到 ProtocolLib 时启用）
├── listener/              5 个常驻监听器（+ 开启抗尘埃药时追加 2 个，共 7 个）：
│   ├── PlayerSessionListener    join/quit/death：状态预填、PDC 落盘、离线清理、清免疫
│   ├── RadiationMarkListener    在事件瞬态 PDC 打 radsurvival:source=radiation 标记
│   ├── SunRadiationListener     ★伤害门控：SunMark 时间窗 + classify 认领太阳伤害
│   │                            + restoreVanillaDefense 补偿外部伤害的原版减免
│   ├── DamageTagListener        死亡文案：时间窗 + GENERIC 无实体双判据
│   └── AntiDustListener         ★服药结算：降腐蚀 + 免疫 + 解毒 + 冷却（v1.2）
├── placeholder/           PlaceholderAPI 扩展（缺失时零加载）
│   ├── PlaceholderBridge      软依赖桥（无 PAPI 时零开销）
│   └── RadSurvivalExpansion   %radsurvival_*% 占位符实现
└── api/                   对外扩展点
    ├── RadiationExposureEvent    每轮辐射结算前后可介入
    ├── CorrosionStageChangeEvent 腐蚀阶段跳变广播
    └── RadSurvivalApi            只读者门面
```

> `DustCraftGui` 虽在 `item/` 包，但它实现了 `Listener` 并注册进 HandlerList，
> 所以那「7 个监听器」= `listener/` 里 4 个 + `FeedbackService` + `item/DustCraftGui` + `AntiDustListener`。

> 注册顺序在 `RadSurvivalPlugin#onEnable`：基础 5 处 `registerEvents`，再经
> `installAntiDust()` 追加 2 个（`DustCraftGui` + `AntiDustListener`），共 7 个。
> **SunRadiationListener 必须用 LOWEST 优先级**——它要先于第三方插件看到原始伤害值。
>
> **抗尘埃药的监听器只在首次装配时注册一次**：`installAntiDust()` 在 reload 路径上复用同一实例
> （两个类都从 `plugin.config()` 动态取配置，因此自动跟随新配置），只清掉旧冷却。
> 不要改成「每次 reload 都 registerEvents」——Bukkit 的 `HandlerList` 不支持按实例注销，
> 重复注册会让同一事件被处理多次。

**分层铁律**

1. `core` 与 `config` **不得**持有 Bukkit 服务引用的重逻辑——保证可脱离服务器单测。
2. 一切**内存态**（腐蚀值、PDC、状态镜像）收敛在 `CorrosionMeter` / `RadSurvivalApi`；
   禁止外部直接碰 `RadiationTask#statesView`。
3. 一切**伤害**只经 `DamageApplier` 出口，便于统一挂反馈与归因。
4. 监听器与命令**动态取** `plugin.services()`，不缓存容器引用（reload 会换实例）。

---

## 3. 一轮结算的完整链路

```
RadiationTask.run()
  └─ 遍历在线玩家
       ├─ ① 世界/维度白名单过滤（不在内 → 恢复分支：停腐蚀步进）
       ├─ ② 昼夜阶段 → timeFactor（夜晚 ×night-multiplier，可线性插值）
       ├─ ③ 露天判定（SunExposureChecker，安全区 → 恢复分支）
       ├─ ④ 盔甲减免（ArmorCalculator，Σ 部位权重 × 材质系数）
       ├─ ⑤ 腐蚀步进（先累后打；值域钳 [0, max]）
       ├─ ⑥ 抗尘埃药免疫窗口（DustImmunity → IMMUNE，跳过 ⑦⑧）
       ├─ ⑦ 伤害合成：max(base × timeFactor × corrosionMult × (1 − armorReduction),
       │                    base × timeFactor × corrosionMult × minDamageFraction)
       └─ ⑧ DamageApplier.apply(...) → RadiationExposureEvent → damage()
```

**三条口径（改动前必读）**

1. **先累后打**：腐蚀步进发生在伤害计算**之前**，本轮累加的腐蚀立刻计入倍率。
2. **无敌帧**：`tick-interval ≥ 20`（`PluginConfig` 校验下限），否则伤害会被原版无敌帧吞掉。
3. **不双重减免**：见 §10.3 —— 由「结算瞬间摘甲」保证，而非依赖伤害类型。

> **v1.2 变化**：⑥ 之前有一级「`armorReduction >= 1.0` → IMMUNE」短路，已删除。
> 现在 `RoundResult.IMMUNE` 的唯一来源是 ⑥ 的抗尘埃药窗口；护甲只能减伤，且被 ⑦ 的保底兜住。
> 免疫判定刻意放在 ⑤ **之后**——先累后打的口径不能因为免疫而松动（腐蚀照常在免疫窗口外累积，
> 窗口内则完全不步进，避免「喝药期间偷偷攒腐蚀」和「喝药反而被腐蚀」两种观感都不对）。

---

## 4. 伤害出口（`DamageApplier`）

```java
DamageApplier.apply(player, exposed, damage, armorReduction, corrosion, timeFactor)
```

- 广播 `RadiationExposureEvent`（可取消；取消即不落地伤害，腐蚀数值不回滚）；
- 事件之后经 `DamageExecutor` 注入点执行真实 `damage()`；
- 生产执行器 = `ARMOR_NEUTRALIZING_EXECUTOR`（摘下盔甲 → `damage()` → 还甲）；
- 挂点 `setDamageFeedbackHook(...)` 供 `FeedbackService` 记录掉血归因，**reload 必须解绑**。

### 4.1 伤害通道（`DamageChannel`）

| 通道 | id | 含义 |
|---|---|---|
| `SUN` | `SUN` | 本插件（日晒/辐射）结算的伤害 |
| `EXTERNAL` | `EXTERNAL` | 其他一切来源（第三方 `/damage`、雷击、生物…） |

`RadiationExposureEvent#getDamageChannel()` / `getDamageChannelId()` 对外暴露，
让第三方插件能区分「这是插件打的」还是「只是恰好也是 CUSTOM」。

### 4.2 `CUSTOM` 的归因难题与门控

Bukkit 的 `DamageCause.CUSTOM` **无法反查来源**，而 `/damage <p> 10 minecraft:generic`、
雷击等也会落到 `CUSTOM`。因此不能用类型判断归属。现方案（`SunRadiationListener`）：

1. 调用 `damage()` **之前**打一个 `SunMark{atMillis, amount}`（100ms 窗口）；
2. `EntityDamageEvent` 里用 `classify(cause, victimId, damagerId, amount, mark, now)` 判定，
   **四条同时成立**才认领为太阳伤害：`CUSTOM` + 窗口内 + 金额吻合（`1e-6` 容差）+ 伤害者≠受害者；
3. 认领 → 原样放行（公式已是最终值）；不认领 → `restoreVanillaDefense()` 按原版护甲公式
   把减免补回去，让第三方伤害保持原版手感。

---

## 5. 数据与持久化

### 5.1 内存权威（`CorrosionMeter`）

- `get/set/addValue` 全部钳制到 `[0, max]`，`setValue` 会触发**阶段变化回调**（跨阈值播报）；
- 阶段基线 `firstWarnSeen` 保留，避免重进服务器重复首次警告；
- `addStageListener` / `removeStageListener` 必须**成对使用**（reload 解绑）。

### 5.2 PDC（`player.dat`）

每轮值变化即写，强杀不丢整段会话；`quit` 与 `death` 各再兜一次。

### 5.3 键名

| key | 类型 | 含义 |
|---|---|---|
| `radsurvival:corrosion` | `DOUBLE` | 腐蚀值（随 `player.dat` 落盘，重进/重启不丢） |

伤害来源标记写在**事件瞬时 PDC**（`radsurvival:source=radiation`），不占玩家数据。

### 5.4 PlaceholderAPI

`%radsurvival_exposure%`、`%radsurvival_reduction%`、`%radsurvival_timemul%`、
`%radsurvival_corrosion%`。未安装 PAPI 时 `PlaceholderBridge` 完全不加载扩展类（零报错）；
**reload 会先卸旧再注册**（见 §10.3 ④）。

---

## 6. 命令层开发约定

所有子命令声明集中在 `SubCommand` 枚举（名称/别名/用法/权限/摘要），新增子命令只需：

1. 在 `SubCommand` 加枚举值；
2. 在 `RadCommand.onCommand` 的 `switch` 加分支；
3. 在 `onTabComplete` 的 `switch` 加补全规则。

**输出约定**（`CommandOutput`）：

- `header(...)` 标题行、`section(...)` 分组、`kv(键, 值)` 对齐键值、`verdict(徽标, 文本)` 结论；
- **对齐按显示宽度计算**：CJK 与 `▲△▓░◆✔✖■□` 在 Minecraft 默认字体占 2 列，其余 1 列。
  新增这类符号时**务必加进 `WIDE_SYMBOLS`**，否则整段错位；
- 数字：`fmt()` 去尾零（`1.0`→`1`）、`fixed(v, n)` 固定小数、腐蚀读数固定两位（与 PDC 精度一致）。

> 测试会断言若干文案锚点（如「控制台摘要」「内存权威」「预计本轮结算」「corrosion：」）。
> 改排版时请保留这些定位串，或同步更新 `RadCommandMockBukkitTest`。

---

## 7. 日志约定

| 级别 | 用途 |
|---|---|
| `INFO` | 仅启动 3 行摘要 + reload 结果 + 可选增强接入（ProtocolLib/PAPI） |
| `WARNING` | 可降级异常（反馈层隔离、单玩家结算异常、配置 reload 失败） |
| `SEVERE` | 配置非法拒绝启动 |

**降噪原则**：装配期的「降级说明」（如 WIHN→WHITE_SMOKE）不刷屏，细节挂
`-Dradsurvival.verbose=true`。启动摘要与 `/rad status` 控制台摘要**同源**
（`RadiationServices#describeFlags`），避免两处口径漂移——改配置项时只需改这一处。

> **Windows 控制台乱码**：插件输出 UTF-8，Windows 控制台是 GBK，日志里中文会花屏；
> 经 RCON/玩家聊天完全正常，**不是插件缺陷**。启动加 `-Dstdout.encoding=UTF-8` 或先
> `chcp 65001` 即可。

---

## 8. 测试

### 8.1 结构与运行

```
src/test/java/com/ft/radsurvival/
├── config/    PluginConfigValidation 等（纯 Java，无需服务器）
├── core/      RadiationMath / DayNight / Armor / Corrosion / SunExposure / DustImmunity（纯内核）
├── feedback/  HudRenderer 纯函数
├── listener/  SunRadiationListenerTest（伤害通道归因纯内核）
└── mockbukkit/ 集成测试（MockBukkit 4.116.3，包 org.mockbukkit.mockbukkit）
```

```powershell
$env:JAVA_HOME='D:\java21'
$env:MAVEN_OPTS='-Duser.language=en -Dfile.encoding=UTF-8'   # 避免 GBK 下中文编译错误
mvn -B clean package            # 全量（265 条）
mvn -B test -Dtest=RadCommandMockBukkitTest   # 单类
```

### 8.2 MockBukkit 的已知限制与应对

- `BlockData#isOccluding` **未实现** → 露天判定通过 `RadiationTask.ExposureResolver` 注入假实现；
  伤害通过 `DamageApplier.DamageExecutor` 注入。
  **除这两个观测点外，过滤链/腐蚀步进/免疫短路/事件/伤害全部走生产代码。**
- `damage(double, DamageSource)` **未实现** → 真实摘甲执行器在测试里会抛
  `UnimplementedOperationException`，因此「摘甲/还甲」用异常路径专门验证（见
  `DamageChannelAndReloadMockBukkitTest#armorRestoredEvenWhenDamageThrows`）。
- `RadSurvivalPlugin` **不能加 `final`**：MockBukkit 用 byte-buddy 子类化插件实例做装载钩子，
  `final` 会被拒（"Cannot subclass ... final types"）。
- TabComplete 直接构造 `new RadCommand(plugin)` 断言，不依赖 mock 的 TabCompleter 存取。
- **`org.bukkit.Tag` 在纯 JUnit 下不可用**：其静态初始化要 Bukkit 注册表，无服务器时抛
  `ExceptionInInitializerError`。任何走 `Tag.ANVIL` / `Tag.LOGS` 的判定（如
  `DustCraftStructure.isAnvil`）**必须放 `mockbukkit/`**，不能按「纯函数」归类。
  这是把 `DustCraftStructureTest` 从 `item/` 挪到 `mockbukkit/DustCraftStructureMockBukkitTest` 的原因。
- **方块容器必须走实时 `BlockInventoryHolder`**：`block.getState()` 返回的是**快照**，
  对它 `getInventory().setItem(...)` 后再 `update()` 在 MockBukkit 里不可靠，
  且生产代码「读中心格 → 写回剩余数量」需要作用于同一份实时容器。
  统一用 `DustCraftStructure.inventoryOf(block)`（内部优先 `BlockInventoryHolder`）。
  测试里也走同一个方法，这样测试与生产是同一路径。
- `ItemMeta#displayName()` 的纯文本序列化在 MockBukkit 里会带方括号包裹，
  断言用 `contains("名称")` 而非 `isEqualTo`。
- `Consumable` 的组件读取方法是 `consumeEffects()`（不是 `effects()`），可用 `javap` 复核：
  `javap -cp target/api-check io.papermc.paper.datacomponent.item.Consumable`。

### 8.3 新增测试的约定

- 纯逻辑优先放 `core/`（快、无依赖）；
- 只要碰 Bukkit 事件/PDC/命令，就放 `mockbukkit/` 并用 `Support.install(...)` 统一装配；
- 「回归钉子」请在注释里写清**钉的是什么 bug**（本仓库历史修复均有此标注）。

---

## 9. 构建与发布

```powershell
$env:JAVA_HOME='D:\java21'
mvn -B clean package                # 默认：fat jar（生产用）
mvn -B clean package -Plibraries-channel   # thin jar + plugin.yml libraries:（需服务器能连镜像）
```

**发布检查单**

1. `mvn -B clean package` → `Tests run: 265, Failures: 0`
2. 产物大小约 1.8 MB；jar 内 `com/ft/radsurvival/lib/*` ≥ 1259 项；
   `org/spongepowered/*`、`com/github/benmanes/*` **零泄漏**；
3. 内嵌 `config.yml` 与 `src/main/resources/config.yml` **逐字节一致**；
4. `plugin.yml` 无 `libraries:` 块（fat jar 口径）；
5. 同步 `target/` → `dist/` → `test-server/plugins/`，三处 SHA-256 一致；
6. 实机冒烟：删 `.paper-remapped` 缓存 → 启服 → 确认 `Enabling` 且**无异常栈**。

---

## 10. 已知问题与踩坑记录

| 问题 | 结论 |
|---|---|
| `libraries:` 运行期下载 | 本环境 Central 镜像被重置 → `Error resolving libraries` 拒载；**默认改 shade+relocate**，thin 通道保留 |
| WIHN 粒子 | 1.21.11 无该枚举 → 降级 `WHITE_SMOKE` |
| `damage(double, DamageCause)` | 1.21.11 已移除 → 改用 `DamageSource` + `DamageType.GENERIC` |
| MockBukkit 坐标 | `com.github.seeseemelk` 旧包钉死 1.21.1 会崩 → 用 `org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3` |
| 段缓存布尔误判 | 旧实现缓存"是否露天"→ 同段不同眼位互相污染；现缓存**顶遮挡高度**，任意 eyeY 精确派生 |
| HUD 陈旧镜像 | 旧实现读结算镜像，被过滤轮次不刷新 → 改读 `previewRound` 实时快照 |
| 腐蚀只在 quit 落盘 | 强杀丢整段会话 → 改为**每轮值变化即写** PDC |
| **原版护甲二次减免（v1.1 修复）** | GENERIC **走**护甲减免（旧验收用裸僵尸测出的"不吃甲"是空甲巧合）→ 双重减免 + 安全区伤害被吞成 0。改为结算瞬间摘甲/还甲 + 太阳伤害门控，详见 10.3 |
| **reload 泄漏与占位符（v1.1 修复）** | 旧 `FeedbackService` 的容器阶段回调与 `DamageApplier` 挂点未解绑；`PlaceholderBridge` 从不重注册 → 见 10.3 |
| **`PlayerLoginEvent` 的能力代价** | 任何插件监听它都会让 Paper 打印 `[HorriblePlayerLoginEventHack]` 并使 re-configuration API 失效 → **本项目刻意不监听**；异步登录阶段触达玩家状态会触发线程断言 |
| **`getState()` 是快照（v1.2 踩坑）** | 对 `block.getState()` 的容器 `setItem` 后 `update()` 不保证生效，且「读中心格→写回剩余」会互相覆盖。容器读写统一走 `BlockInventoryHolder#getInventory()`（`DustCraftStructure.inventoryOf`） |
| **`org.bukkit.Tag` 需注册表（v1.2 踩坑）** | 纯 JUnit 下 `Tag.ANVIL` 静态初始化直接 `ExceptionInInitializerError`。用 Tag 的判定只能放 `mockbukkit/` 测 |
| **`CommandOutput.percent` 收的是比例** | 传 `0.72` 输出 `72%`；早期误乘 100 导致 `/rad status` 印出 `7200%`（测试抓到） |
| **原版 `USE_COOLDOWN` 不能写在自定义药水上** | 它的冷却分组是原版共享的，写进去会和原版食物/药水互相卡冷却。冷却靠取消 `PlayerItemConsumeEvent`（取消不掉物品）自管 |
| **构建混淆（ProGuard）** | **不可行，已放弃**，详见下节 |

### 10.1 为什么不做混淆（重要，勿重复尝试）

按需求评估过 ProGuard 7.6.1（rename-only 档），四个死结：

1. **`-dontpreverify` 绝不能用**：Java 21（class 65）加载期强制校验 StackMapTable，
   缺帧直接 `VerifyError: Expecting a stackmap frame at branch target 42` → 插件拒载。
2. 内嵌 Caffeine 的私有 `BoundedLocalCache$*Limiter`（两个同级类共享 `Function` 接口，
   **无共同父类**）会触发 ProGuard 预校验的 `IncompleteClassHierarchyException`。
3. 为绕开 ②，必须把 `lib/*` 排除出混淆输入；此时输入**必须取自 shade 后的类**
   （已 relocate）。若误用 `target/classes`，回灌后引用的是未重定位的
   `com.github.benmanes.*` → 运行期 `NoClassDefFoundError`（已实测）。
4. 即使全部打通，`shade` 在 `package` 末尾替换主产物，与 ProGuard/antrun 的**阶段顺序存在竞态**，
   反复出现"回灌到未混淆 jar"。

排除上述死结后，实际被改名的只有几个内部辅助类（`CommandOutput`→`a` 之类），
**收益远低于维护与事故成本**。若确有需求，请用成熟商业混淆器对**独立 jar** 离线处理，
并完整走一遍第 9 节检查单 + 实机验收。

### 10.2 CI/CD 工作流（`.github/workflows/build-release.yml`）

推送到 `main` / 手动触发 → 构建 + 全量测试 + 发布 Release；PR 只构建不发布。

**产物命名**：`RadSurvival-<yyyy>-<MM>-<dd>-<当天第几次构建>.jar`，如 `RadSurvival-2026-10-25-3.jar`。

| 决策 | 原因 |
|---|---|
| 序号 = 当天**已成功完成**的构建数 + 1 | 用「已触发」会在并发排队时让两个 run 抢到同一个号 |
| 日期按 Asia/Shanghai 分桶 | Runner 是 UTC，直接 `date` 会跨日错位 |
| 用 `jq` 的 `fromdateiso8601 \| . + 28800 \| strftime(...)` 而非 `date -d` | `date -d` 在各种实现下行为不一致（Git Bash 的 `date -u` 实测不换算时区），jq 是确定性的 |
| `-Drevision=<yyyy-MM-dd-N>` 注入 `plugin.yml` | 让游戏内 `/version` 与下载文件名一致；本地不传该参数则仍是 `1.0.0-SNAPSHOT` |
| 门禁：测试数 ≥ 100 | 防配置漂移导致 Surefire 静默跳过测试、坏包照发 |
| `concurrency.cancel-in-progress: false` | 中途取消会让序号出现空洞 |

**踩过的坑**

| 坑 | 教训 |
|---|---|
| `.gitignore` 写了未锚定的 `config/` | 连带忽略 `src/main/java/.../config/` 源码包 → CI 报 `package does not exist`。**排除目录必须锚定根目录**（`/config/`） |
| Windows 上 git 默认 CRLF | Actions 的 `run:` 块遇 `\r` 报 `unexpected character` → 必须用 `.gitattributes` 钉 `eol=lf` |
| 本地 `mvn` 绿 ≠ CI 绿 | 本地有 `.m2` 缓存与未入库文件，**必须真跑一次 CI** 才算验证 |

> **排查 CI 的常用命令**
> ```powershell
> gh run list --limit 5
> gh run view <id> --log-failed            # 只打失败步骤
> gh run watch <id> --exit-status          # 阻塞等待并取退出码
> ```
> 注意：PowerShell 里给 `gh --jq` 传含空格/引号的表达式容易被拆参，
> 稳妥写法是 `gh ... --json x | ConvertFrom-Json` 再用管道处理。

### 10.3 v1.1 判定链路加固（四处真实缺陷）

> 起因：一次「排查代码确定功能真实可用」的复核。全部结论均以**真实 1.21.11 客户端 + Paper 132**
> 实测为准，不靠推理。回归测试见 `SunRadiationListenerTest`、`DamageChannelAndReloadMockBukkitTest`。

**① 原版护甲二次减免 / 安全区伤害被吞（最严重）**

- 旧 `DamageApplier` 直接用 `player.damage(amount)` 结算，而这会**再走一遍原版护甲减免**；
  同时 `RadiationTask` 在 `armorReduction >= 1.0` 时**直接跳过 `damage()`**。
  两者叠加的后果不是"少扣一点"，而是**安全区内的第三方伤害全部归零**：
  实测「全套下界合金玩家被 `/damage … 10 minecraft:generic` / 被雷劈」＝ 掉 0 血。
- 旧验收 §C.1 之所以判定"GENERIC 不吃甲"，是因为它用 `/summon zombie` 做靶——
  **僵尸的盔甲槽本来就是空的**，往物品栏塞胸甲不等于穿在 `armor.chest` 上。
- 现方案（`DamageApplier.ARMOR_NEUTRALIZING_EXECUTOR`）：在 `damage()` 的**同步栈内**
  把四件盔甲从装备槽取出、调用后原样放回。护甲点数归零 → 原版防御恒 0，公式成为唯一减免路径。
  摘下的是**同一 `ItemStack` 实例**（实测岩浆浸泡后胸甲仍在且耐久递增），不是复制体。
  关停兜底 `restoreSuspendedArmorAll()` 幂等，防 crash 留空槽。
- **不要**改成"监听 `EntityDamageEvent` 再折算回去"：那会在事件里做一次不可逆的数值改写，
  与第三方插件的减免监听叠加顺序不可控。摘甲是唯一能保证"原版防御恰好为 0"的手段。

**② 外部伤害与太阳伤害共用 `CUSTOM`，无法区分**

- Bukkit 的 `DamageCause.CUSTOM` 无法反查来源，而 `/damage` 与雷击也会落到 CUSTOM。
- 现方案：`SunRadiationListener` 在调用 `damage()` 前打一个 100ms 的 `SunMark`
  （`时间戳 + 金额`），`classify()` 要求 **CUSTOM + 窗口内 + 金额吻合 + 伤害者≠受害者** 四条同时成立，
  才认领为太阳伤害；否则视为外部伤害，交由 `restoreVanillaDefense()` **按原版公式把减免补回去**
  （直接作用于事件数值，而非改写 `armor-damage-reduction` 属性——1.21.11 的 Bukkit API
  没有该常量，`Attribute` 里只有 `ARMOR` / `ARMOR_TOUGHNESS`）。
- `RadiationExposureEvent#getDamageChannel()` 对外暴露 `SUN` / `EXTERNAL`，第三方插件可据此区分。

**③ reload 泄漏：旧反馈实例仍收回调**

- 旧 `FeedbackService.shutdown()` 只停了 HUD 任务，没有解绑 `CorrosionMeter` 的阶段回调
  和 `DamageApplier` 的受击挂点 → reload 后旧实例继续被调用（日志重影、内存泄漏、双重播报）。
- 现方案：构造函数里把回调保存成 `hookRef` / `stageRef` 再注册，`shutdown()` 用**同一个方法引用**
  反注册（`CorrosionMeter.removeStageListener` / `setDamageFeedbackHook(null)`）；
  新增 `CorrosionMeter.forgetAll(Collection<UUID>)` 清理离线残留。

**④ 占位符 reload 后不刷新**

- 旧 `PlaceholderBridge` 的 `registerIfAvailable` 直接 `new` 并注册，**从不 unregister**
  → reload 后 PAPI 仍指向旧插件实例与旧版本号。
- 现方案：静态 `volatile RadSurvivalExpansion registered`，注册前先卸旧的；
  reload 末尾调 `PlaceholderBridge.registerIfAvailable(this)`，`onDisable` 调 `unregister()`。

**踩坑提醒（本轮实际踩到，勿重复）**

| 坑 | 事实 |
|---|---|
| `DamageApplier` 静态字段顺序 | `ARMOR_NEUTRALIZING_EXECUTOR` 的 lambda 引用 `armorSnapshot`，该字段**必须声明在 lambda 之前**，否则 `illegal forward reference` |
| `Player.getAttributeValue(...)` | Bukkit 无此方法；正确写法是 `player.getAttribute(Attribute.ARMOR).getValue()`（注意 `AttributeInstance` 可能为 null） |
| `Attribute.ARMOR_DAMAGE_REDUCTION` | **1.21.11 不存在**（javap 确认），别照着高版本 wiki 写 |
| `PlayerLoginEvent` | 监听它会让 Paper 打印 `[HorriblePlayerLoginEventHack]` 并**禁用 re-configuration API**；本项目不需要它，**不要加回来** |
| 验收环境的回血干扰 | `difficulty peaceful` 或饱食度满时自然回血 ≈1HP/0.7s，血量读数会把伤害盖掉；测血量差必须切 `difficulty hard` 或先在 <1s 内完成读数 |
| `gamerule` 名称 | 实测该环境只认下划线形式（`keep_inventory` ✓，`keepInventory` ✗），别只照着 wiki 写 camelCase |

### 10.4 v1.2 抗尘埃药（结构 + 物品 + 免疫，本轮踩坑）

需求是「发射器上放铁砧，用木棍右键铁砧当合成工具，发射器 UI 作为合成页」。
以下是实现时**实际踩到**的坑，改这块之前先读一遍。

| 坑 | 事实与结论 |
|---|---|
| **这不是配方，是多方块结构** | 用户明确否掉了「数据包 JSON 配方」和「工作台」。`DustCraftStructure` 判的是**世界里真实方块**：核心（发射器/投掷器）+ 正上方铁砧 + 同层 8 邻原木。别改回 `RecipeChoice` 思路 |
| **「9 格外围一圈中间农作物」指的是容器 UI，不是世界方块** | 8 个原木是世界结构；GUI 里那 9 格是发射器自己的 `Inventory`，外围 8 格**只出不进**（放入被取消，取出放行），中心索引 `4` 才是作物位。别写成「只读」——「只读」会让人以为放进去还能拿回来 |
| **`block.getState()` 返回快照** | 对它 `getInventory().setItem(...)` 再 `update()` 在本环境下不可靠；且「读中心格 → 写回剩余数量」若分两次拿快照会互相覆盖。统一用 `BlockInventoryHolder#getInventory()`（封装成 `DustCraftStructure.inventoryOf`） |
| **`Tag.ANVIL` / `Tag.LOGS` 需要 Bukkit 注册表** | 纯 JUnit 下静态初始化直接抛 `ExceptionInInitializerError`。用 Tag 的判定只能放进 `mockbukkit/` 测（`DustCraftStructureMockBukkitTest`） |
| **`org.bukkit.Tag` 的常量名** | 是 `Tag.LOGS`，不是 `Tag.ITEMS_LOGS`（后者不存在）。`Material` 是普通 enum 且实现 `Keyed`，但**不是** `ItemType`，需要时用 `material.asItemType()` |
| **不能给自定义物品注册新 ID** | Bukkit/数据包都不允许插件造新物品。抗尘埃药 = `HONEY_BOTTLE` 基底 + PDC 档位标识（`radsurvival:dust_level`）+ 组件覆盖外观与行为 |
| **`USE_COOLDOWN` 不要写** | 它的冷却分组是原版共享的，写进去会与原版食物/药水互相卡冷却。插件的 20s 冷却靠**取消 `PlayerItemConsumeEvent`** 实现——按 Bukkit 契约取消该事件时**物品不会被移出背包**（见 ADR-7），于是「冷却中喝不掉且不亏东西」自然成立 |
| **`CONSUMABLE` 要写、但 `consumeEffects` 留空** | 写它是为了拿到原版饮用动作（动画/音效/扣物品）；效果列表必须空，否则绕过冷却与降腐蚀逻辑 |
| **`Consumable` 的读口是 `consumeEffects()`** | 不是 `effects()`。API 事实用 `javap -cp target/api-check io.papermc.paper.datacomponent.item.Consumable` 复核，别靠记忆 |
| **`PotionEffectTypeWrapper.values0()` 不存在** | 枚举效果统一用 `PotionEffectType.getByName(String)` 拿 |
| **免疫期间必须「不累积」，不只是「不受伤」** | 这一条**曾经写错过**：免疫判定放在腐蚀步进之后、只做提前 `return`，结果腐蚀照常上涨并落盘，而 HUD 却显示 `◆抗尘`——玩家看得见腐蚀条还在动，与「免疫」二字直接矛盾。正确做法是把免疫翻译成「本轮视作遮蔽」传给结算内核（`settle(id, exposed && !dustImmune, ...)`），让 `step()` 走**恢复分支**。这样「先累后打」的顺序不变，变的只是本轮累积速率取什么值。**任何新增的「免疫/免伤」类机制都要问一句：累积速率归零了吗？** |
| **免疫是内存态，quit / death 都要清** | `DustImmunity` 不落盘。`AntiDustListener#onQuit` 清冷却，`PlayerSessionListener#onDeath` 清免疫——两处都要，否则「下线喝药上线免伤」「喝药自杀复活免伤」都能白嫖。死亡清理原先漏了，是 v1.2 排查时补的 |
| **抗尘埃药的监听器只注册一次，但 reload 要换绑 factory** | 两个监听器 reload 时复用实例（都从 `plugin.config()` 动态取配置）。但 `DustCraftGui` 构造时注入的 `DustItemFactory` 内含**旧 `PluginConfig`**，reload 后若不换绑就是「判定用新配置、产出用旧配置」。现由 `installAntiDust` 调 `rebind(factory)` 处理，字段是 `volatile`。关开关时用 `HandlerList.unregisterAll(...)` **真正摘掉**，不能只靠「各 handler 自查配置后 return」——那样发射器会沦为免费 9 格仓库 |
| **`CommandOutput.percent` 收比例** | `/rad status` 里传 `0.72` 会印 `72%`；乘 100 会印 `7200%`。这个 bug 是被 `statusExplainsCoveredAndNoLongerImmune` 抓到的 |
| **护甲「完全免疫」已删，`IMMUNE` 枚举保留** | `RoundResult.IMMUNE` 现在是抗尘埃药专用。`PluginConfig` 硬校验任何材质「权重和 × 系数 ≥ 1.0」直接拒载，旧 `NETHERITE: 1.00` 配置会报错 |
| **HUD 段改名要同步弃用旧常量** | `◆免疫` → `◆抗尘`；旧 `IMMUNE_MARK` 与 `renderLevelBar(..., double)` 重载保留 `@Deprecated`，仅为不打断按字符串匹配的外部插件 |
| **MockBukkit 的 `displayName()` 带方括号** | 断言物品名用 `contains`，别用 `isEqualTo` |
| **「先扣后补」是最容易写出刷物品的地方** | 原实现是「先扣作物 → `addItem` → 放不进背包就把**整组原始 stack** 写回去」，等于作物没扣、药水照掉 ⇒ 背包满时**无限刷药**。而且 `addItem` 可能**部分成功**（药水堆叠 8、背包只剩 3 位时塞进 3 个并返回剩下的），旧代码仍把整瓶再 drop 一次 ⇒ 复制。**结论：涉及「消耗 A 产出 B」的路径一律写成「先给 B，确认成功后再扣 A」**；实在要先扣，回滚必须用计算后的剩余量，绝不能写回原始 stack |
| **取消事件 ≠ 什么都不做** | GUI 里「中心格只收农作物」不能只看 `getCursor()`：Shift+点击（`MOVE_TO_OTHER_INVENTORY`）、数字键（`HOTBAR_SWAP`）、双击收集（`COLLECT_TO_CURSOR`）三条路径的光标都是空的，全部绕过校验。必须按 `ClickType` 分流，对「最终会落到核心容器」的所有路径统一校验。做容器 UI 时请**穷举 `ClickType`**，别只测普通左右键 |
| **`InventoryMoveItemEvent` 要拦双向** | 只拦 `getDestination()` 是核心，漏斗仍能**从核心抽出**作物，加工台会被自动化搬空。source 或 destination 任一为核心都要取消 |
| **`RawSlot` 只对顶层容器成立** | `event.getRawSlot() >= 容器大小` 表示点击发生在玩家背包侧；但此时 `event.getInventory()` 仍返回核心容器。两侧都要判断，别只判 `rawSlot < 9` |
| **RCON 单次只可靠执行一条命令** | 本仓库 `tools/rconlib.ps1` 因首行注释编码损坏而失效（`function` 关键字被吞），需要时临时写独立脚本；`setblock` 到未加载区块会报「That position is not loaded」，先 `forceload add` |

### 10.5 v1.2 排查方法论（怎么把「看起来没问题」的代码查出洞）

v1.2 上线前做了一轮定向排查，从「代码已通过全部测试」的状态里又挖出 **9 个真实缺陷**，
其中 4 个是玩家立刻能感知的（刷物品、免疫名不副实、死亡保留免疫、一次右键出两瓶）。
这一节记的是**方法**，不是结论——下次排查照这个清单走。

#### ① 先怀疑「测试绿」本身

测试全绿只说明「测过的路径是对的」，不说明「路径穷举完了」。这次的两个典型案例：

- **假绿 1（容差掩盖错值）**：`SurvivalBudgetRegressionTest#nightNaked` 期望夜晚裸装 `44s`，
  实际 `37s`。±20% 容差把 37 也兜进去了，所以一直是绿。这个 44 显然来自
  「白天 22s × 2」的**误推**——夜晚伤害和累积同时减半，但倍率增长被拉长，不能线性翻倍。
  **对策**：数值断言要同时满足「容差能兜住实现波动」和「兜不住一个数量级推理错误」；
  拿不准就独立复算一次（写个几十行的裸 Java 主循环，别复用测试自己的 simulate）。
- **假绿 2（断言没覆盖真实差异）**：给「免疫期间腐蚀不涨」写的第一版回归测试，
  在**回退修复后依然通过**——因为 MockBukkit 默认判遮蔽，新旧实现在遮蔽下都走恢复分支，
  测不出差异。改用 `Support.install` 强制露天、并先断言「前提成立（不免疫时确实在涨）」
  之后才真正生效。
  **对策**：每条新回归测试都要做一次**反向验证**（临时把修复改回去，确认测试变红）。
  不做这一步的回归测试，有一半是装饰品。

#### ② 空值 / 边界 / 生命周期三连问

对每个新入口问三句：

1. **空值**：`craft(player, core, anvil)` 是 `public`，命令层和测试都会直接调，
   传 `null` 会 NPE 吗？`Block#getWorld()` 在卸载世界时是 `null` 吗？
2. **边界**：`addItem` 部分成功怎么办？背包满怎么办？数量减到 0 怎么办？
3. **生命周期**：玩家 **quit** 了状态清吗？**death** 呢？**reload** 后旧实例还在被调用吗？
   **开关关掉**后监听器真的摘了吗（还是只是「自查配置后 return」，容器仍可被滥用）？

这次 9 个缺陷里，有 5 个是第 3 条挖出来的。

#### ③ 「文档说的」和「代码做的」逐条对照

很多缺陷不是逻辑错，是**承诺与实现不符**：

- 注释说「免疫期间零累积增长」，代码只挡了伤害 → 玩家看见腐蚀条还在涨。
- 注释说「背包满时把作物放回去」，代码写回的是整组原始 stack → 无限刷药。
- 注释说「关闭开关后行为退回原版」，代码只让 handler 早退 → 发射器成了免费仓库。
- 注释说「死亡/quit 都清免疫」，代码只清了 quit。

**对策**：排查时把注释当成「需求声明」来读，逐条去代码里找对应语句。
找不到的那条，就是缺陷。

#### ④ 多路审计 + 人工复核，别照单全收

本次并行跑了三路独立审计（抗尘埃药代码 / 护甲平衡 / 测试与文档一致性），
交叉验证很有效，但也**报了两条错的**，必须人工复核后才落地：

| 审计结论 | 复核结果 |
|---|---|
| 「取消 `PlayerItemConsumeEvent` 不阻止扣物品，冷却期喝药会白亏」 | **错**。官方 Javadoc 原文：*"If the event is cancelled the effect will not be applied and the item will not be removed from the player's inventory."* 原前提成立。但仍加了 `setReplacement()` 双保险 |
| 「仓库编译不过（缺 `ClickType` import）」 | **错**。审计读到的是编辑中途的中间态，import 一直都在 |
| 「`readFactorMap` 静默忽略畸形配置」 | **对**，已修（列表/裸标量改报错，缺键给提示） |
| 「`nightNaked` 期望值错，实际 37s」 | **对**，已修，并把容差收紧到 ±10% |

审计给的是**线索**，不是结论。凡涉及「API 契约」的判断，一律 `javap` 或查官方 Javadoc 复核。

#### ⑤ 批量文本替换必须抽查

这次用脚本把文档里的 `255` 全局替换成 `265` 时，顺手把
`ACCEPTANCE.md` 里的 RCON 端口 `25565` 改成了 `26565`。
**对策**：批量替换后 `grep` 一遍替换结果里形似「端口/版本号/坐标」的数字，
或把替换限定在更长的上下文串上（先替换 `255 条` 而不是裸 `255`）。

---

## 11. 后续方向（plan.md 文末候选）

1. **§7 服务端品牌自适应 Profile**（Folia/Spigot 防御识别）——优先级最高，
   涉及 `RadiationServices` 的调度抽象，需同步改 `ScanDispatcher` 注入点。
2. **§4.1 辐射病模式**——腐蚀阶段挂 Debuff，挂点在 `CorrosionMeter` 的阶段回调。
3. **§4.2 防护装备体系**——建议以 `ProtectionProvider` SPI 接入，与 `ArmorCalculator` 并列。

新增玩法时请优先复用 `RadiationServices` 装配，不要在 `RadSurvivalPlugin` 里堆逻辑。

---

## 12. 交接说明（给下一位维护者）

**当前状态**：M1–M4 完成并实机验收；命令层与日志优化已完成并验证；构建混淆已评估并
**明确放弃**；v1.1 判定链路加固已完成；**v1.2 护甲削弱 + 抗尘埃药已完成（265 条测试全绿）**；
CI/CD 自动发行已上线（`RadSurvival-<年>-<月>-<日>-<序号>.jar`）。

**产物从哪里拿**：CI/CD 上线后**以 Release 为准**，不要再用本地目录当发布源。

```
GitHub Releases（唯一发布源）
  └── RadSurvival-<yyyy>-<MM>-<dd>-<N>.jar  +  .jar.sha256
```

本地构建仍产出 `target/RadSurvival.jar`（版本号 `1.0.0-SNAPSHOT`），仅用于自测与实机冒烟。
`test-server/` **不入库**（世界存档 + 明文凭据），验证脚本在 [tools/](tools/)。

**接手后第一件事**：跑一次 `mvn -B clean package` 确认 265 条全绿。

> 文档改动不涉及代码，产物无需重建。

**改动入口速查**

| 想改什么 | 去哪里 |
|---|---|
| 伤害数值 / 曲线 | `core/RadiationMath`（纯函数，改完跑 `SurvivalBudgetRegressionTest`） |
| 判定过滤条件 | `core/RadiationTask` 的过滤链（①~⑦ 链路见 §3） |
| 露天判定/缓存策略 | `core/SunExposureChecker` + `config.yml` 的 `async-exposure-scan` |
| 外部伤害与原版护甲的交互 | `listener/SunRadiationListener`（门控 + `classify`） |
| 新增配置项 | `config/PluginConfig` + `src/main/resources/config.yml` **两处都要改**，并跑配置校验测试 |
| 新增子命令 | `command/SubCommand` 枚举 → `RadCommand` 的 `onCommand` / `onTabComplete` |
| HUD/播报表述 | `feedback/HudRenderer`（纯函数，有单测）、`feedback/FeedbackService` |
| 对外 API | `api/RadSurvivalApi`（只加方法不改签名，保持二进制兼容） |
| 启动/重载日志 | `core/RadiationServices#describeFlags`（唯一口径来源，勿在别处拼配置摘要） |

**改代码前必读**：§3「三条口径」（先累后打 / 无敌帧 / 不双重减免）、§10 踩坑记录。
这些是历史上出过真实故障的地方。

**不要做的事**

- 不要给 `RadSurvivalPlugin` 加 `final`（MockBukkit 无法子类化）。
- 不要加 `-dontpreverify` 或任何 ProGuard 混淆（§10.1）。
- 不要让 `core`/`config` 依赖 Bukkit 服务做重逻辑（会毁掉纯单测能力）。
- 不要绕过 `DamageApplier` 直接调 `player.damage()`。
- 不要在监听器里缓存 `plugin.services()`（reload 会换实例）。
- 不要监听 `PlayerLoginEvent`（会禁用服务端 re-configuration API，见 §10.3）。
- 不要在异步线程读方块/区块/玩家状态（会触发 Paper 线程断言）。

**待办候选**：见 §11（§7 品牌自适应 Profile 优先级最高）。

---

## 13. 扩展点速查（按需求找位置）

### 13.1 常见改动配方

| 需求 | 改哪里 | 连带要改 | 验证方式 |
|---|---|---|---|
| 加配置项 | `PluginConfig` 的校验 + record | `config.yml`（**两处必须同步**） | `PluginConfigValidationTest` + `/rad reload --dry-run` |
| 加子命令 | `SubCommand` 枚举 | `RadCommand.onCommand` / `onTabComplete` 两个 switch | `RadCommandMockBukkitTest` |
| 调伤害公式 | `RadiationMath`（纯函数） | — | `SurvivalBudgetRegressionTest` 会失败 → 说明预算表要重排 |
| 改盔甲模型 | `ArmorCalculator` | `config.yml` 的 `armor.*` | `ArmorCalculatorTest` |
| 改腐蚀放大曲线 | `RadiationMath` 的 curve 分支 | `corrosion.curve` 合法值集合 | `RadiationMathTest` |
| 改 HUD 显示 | `HudRenderer`（纯函数） | 新宽字符要加进 `WIDE_SYMBOLS` | `HudRendererTest` |
| 加表现层效果 | `FeedbackService` | 用完要能在 `shutdown()` 解绑 | `CorrosionStageFeedbackMockBukkitTest` |
| 改露天判定 | `SunExposureChecker`（内核）+ 适配层 | 异步路径见 §8 | `SunExposureCheckerTest` |
| 加对外事件 | `api/` 包 | 只加不改，保持二进制兼容 | `RadiationExposureEventChainTest` |
| 改抗尘埃药档位/作物 | `config.yml` 的 `anti_dust.levels` | 档位顺序即优先级，重复作物会拒载 | `PluginConfigValidationTest` + `AntiDustChainMockBukkitTest` |
| 加一个新的「自制物品」 | `item/` 包（照 `DustItemFactory`） | 基底材质 + PDC 标识 + 组件；**不能造新 ID** | 物品工厂测试（PDC 往返 / 组件存在） |
| 加一个新的多方块结构 | `item/` 包（照 `DustCraftStructure`） | 纯 `Material` 判定与真实方块读取要分两层 | 必须放 `mockbukkit/`（Tag 需注册表） |

### 13.2 可直接注入的测试缝

| 缝 | 位置 | 用途 |
|---|---|---|
| `RadiationTask.ExposureResolver` | 露天判定 | 绕开 MockBukkit 未实现的 `BlockData#isOccluding` |
| `DamageApplier.DamageExecutor` | 真实掉血 | 绕开 MockBukkit 未实现的 `damage(double, DamageSource)` |
| `SunExposureChecker.ScanDispatcher` | 异步补扫 | 单测注入直连/队列执行器，生产传 `runTaskAsynchronously` |
| `SunRadiationListener` 的 `LongSupplier clock` | 时间窗判定 | 免 sleep 测窗口过期 |
| `DustImmunity` 的 `LongSupplier clock`（v1.2） | 免疫窗口 | 免 sleep 精确测边界（`DustImmunityTest`） |
| `CorrosionMeter` 阶段回调 | 阶段播报 | 记录/断言跨阈值 |

> 除这些缝之外，**过滤链 / 腐蚀步进 / 免疫短路 / 事件链 / 伤害全部走生产代码**——
> 测试不是在测替身。加新缝时请守住这条，别把核心逻辑也 mock 掉。
>
> ⚠️ **注入缝的默认值会影响测试能不能测出东西**：`Support.Fixture.exposed` 默认
> `true`，但**直接 `MockBukkit.load(RadSurvivalPlugin.class)` 得到的生产 `ExposureResolver`
> 在 MockBukkit 里恒判遮蔽**（`BlockData#isOccluding` 未实现）。所以想在测试里观察
> 「露天才发生」的行为（腐蚀累积、辐射掉血），**必须走 `Support.install(...)`**，
> 否则新旧实现在遮蔽分支下行为相同，测试会「怎么改都绿」（详见 §10.5 ①）。

### 13.3 reload 时必须成对处理的东西

`/rad reload` 会**整体重建** `RadiationServices`，凡是「注册到旧实例上的回调」都必须解绑，
否则旧实例被持续调用（内存泄漏 + 日志重影 + 双重播报）。

| 注册 | 解绑 | 守它的测试 |
|---|---|---|
| `CorrosionMeter.addStageListener(...)` | `removeStageListener(同一个引用)` | `DamageChannelAndReloadMockBukkitTest` |
| `DamageApplier.setDamageFeedbackHook(...)` | `setDamageFeedbackHook(null)` | 同上 |
| `PlaceholderBridge.registerIfAvailable(...)` | `PlaceholderBridge.unregister()` | 同上（无 PAPI 时验证静默） |
| HUD/BossBar 周期任务 | `cancel()` + 移除 BossBar | `CorrosionStageFeedbackMockBukkitTest` |
| `DustCraftGui` / `AntiDustListener`（v1.2，**只注册一次**） | reload 复用实例：清冷却 `clearCooldowns()` + **换绑 factory** `dustGui.rebind(newFactory)`；**关闭开关时**改用 `HandlerList.unregisterAll(...)` 真正摘除 | `AntiDustChainMockBukkitTest` |
| `DustImmunity`（v1.2） | `services.shutdown()` 调 `clearAll()`；另外 death 与 quit 也要 `clear(uuid)` | `DustImmunityTest` |

> **为什么 reload 也要换绑 factory**：`DustCraftGui` 构造时注入的 `DustItemFactory`
> 内含当时的 `PluginConfig`。只复用实例不换绑，会出现「判定用新配置、产出用旧配置」——
> 改了档位表的减免/时长/作物归属后，合成出的药水 Lore 与 PDC 仍是旧值。
> 这是 §13.3 里唯一一个「监听器不用重建、但它持有的对象要换」的例子。

**写法约定**：构造函数里把回调存成字段（`hookRef` / `stageRef`），解绑时用**同一个字段**，
不要现场写新的 lambda——新 lambda 的引用不同，`remove` 会静默失败。

---

## 14. 架构决策记录（ADR 摘要）

只记「为什么这样选」和「什么情况下该推翻它」。

### ADR-1 伤害走 `DamageType.GENERIC` + 结算瞬间摘甲

- **背景**：需要伤害不被原版护甲二次减免，但 1.21.11 没有能表达「无视护甲」的稳定伤害类型。
- **选择**：`damage(amount, DamageSource)` 前后把四件盔甲从装备槽移出/放回。
- **代价**：每次结算有 2 次装备槽写入；必须在 `onDisable` 兜底还原（`restoreSuspendedArmorAll()`）。
- **推翻条件**：若未来 Paper 提供稳定的「忽略护甲」伤害属性，立即改用，摘甲机制整体删除。

### ADR-2 不用 `PlayerLoginEvent`

- **代价**：玩家数据只能在 `PlayerJoinEvent` 恢复，比登录阶段晚。
- **原因**：监听登录事件会让 Paper 禁用 re-configuration API（`[HorriblePlayerLoginEventHack]`），
  对需要 `/reload config` 的服务器是硬伤。
- **推翻条件**：Paper 移除该限制，且确有「join 前就要拦截」的需求。

### ADR-3 腐蚀值内存权威 + 每轮变化即写 PDC

- **选择**：内存是唯一真值，PDC 只是持久化副本；值变化就写，不等 quit。
- **代价**：写放大（仅变化时写，且免疫/满值玩家零写）。
- **推翻条件**：出现高频写入导致的 TPS 问题（届时改为节流写 + `onDisable` 强制刷盘）。

### ADR-4 露天扫描异步化的边界

- **选择**：只把**方块扫描**放异步；`damage()` / 事件 / 粒子 / actionbar / PDC 全留主线程。
- **原因**：Paper 对异步读区块有断言，异步触达玩家状态会直接抛异常。
- **兜底**：异步任务内异常一律吞掉、不发布、下轮重试（宁可慢一轮，不可污染主线程）。

### ADR-5 发布产物用 shade+relocate 而非 `libraries:`

- **背景**：Paper 的 `libraries:` 走 `maven-central.storage-download.googleapis.com`，
  本环境被重置 → `Error resolving libraries` → 插件**直接拒载**。
- **选择**：默认 fat jar，Caffeine/Configurate 等 relocate 到 `com.ft.radsurvival.lib.*`。
- **代价**：产物 ~1.8 MB。
- **推翻条件**：目标服务器环境能稳定访问该镜像，且希望减小下载体积 → 用 `-Plibraries-channel`。

### ADR-6 抗尘埃药用「原版基底 + PDC」，不造新物品（v1.2）

- **背景**：需求要一个全新物品。但 Bukkit 与数据包都**不允许**插件注册新物品 ID。
- **选择**：`HONEY_BOTTLE` 基底 + `DataComponentTypes` 覆盖名称/Lore/堆叠/外观/饮用动作
  + PDC `radsurvival:dust_level` 存档位语义。工具物品（木棍）只作触发钥匙，不消耗。
- **代价**：物品在创造模式物品栏里不可搜索（需 `/rad dust give` 或实机合成）；
  `HONEY_BOTTLE` 自带的原版饮用音效需要接受。
- **推翻条件**：Paper 提供稳定的自定义物品注册 API（届时迁移，PDC 标识可保留作兼容）。

### ADR-7 冷却靠取消 `PlayerItemConsumeEvent`（v1.2）

- **背景**：冷却有两种写法——原版 `USE_COOLDOWN` 组件，或插件自管。
- **选择**：插件自管，冷却中直接 `setCancelled(true)`。Bukkit 取消该事件时**物品不消耗**，
  于是「不扣东西」不需要额外代码，也不会出现「先扣后判」的中间态。
- **代价**：冷却状态是内存态，重启/重连清零（`onQuit` 显式清，避免下线喝药上线免伤）。
- **不选 `USE_COOLDOWN` 的原因**：它的冷却分组是原版共享的，写进去会让本插件药水
  与原版食物/药水互相卡冷却，玩家体验变怪且难以解释。
- **推翻条件**：需要跨重启保持冷却时，改为 PDC + 时间戳落盘（注意与原版分组彻底隔离）。

---

## 15. 文档维护约定

四个文档职责不重叠，改完代码请对照下表决定更新哪一份：

| 文档 | 面向 | 写什么 | 不写什么 |
|---|---|---|---|
| [README.md](README.md) | 玩家/服主 | 玩法、安装、命令、权限、FAQ | 任何 API 名、包名、测试细节 |
| [ADVANCED.md](ADVANCED.md) | 进阶服主/插件作者 | 完整配置表、结算语义、API/事件、排错 | 内部实现细节、踩坑过程 |
| **DEVELOPMENT.md**（本文） | 维护者 | 包结构、链路、测试、构建、**踩坑**、ADR | 面向玩家的玩法介绍 |
| [CHANGELOG.md](CHANGELOG.md) | 所有人 | 每版的 Added/Changed/Fixed + **升级注意** | 设计意图、实现细节 |
| [plan.md](plan.md) | 设计者 | 原始规格与需求 | 实现现状（会过时） |
| [ACCEPTANCE.md](ACCEPTANCE.md) | 验收者 | 实机证据、裁决结论 | 设计意图 |

> **发版时别忘了 CHANGELOG**：`CHANGELOG.md` 是唯一记录「这版玩家会感觉到什么变化」的地方，
> 面向玩家的破坏性变更**必须**写进对应版本的「升级注意」，而不是只写在本文的踩坑表里。

**硬性要求**

1. 改了数值口径 → 同步 §3、ADVANCED §6、`SurvivalBudgetRegressionTest`，**三处必须一致**。
2. 新增踩坑 → 进 §10，写清「现象 / 根因 / 正确做法」，别只写结论。
3. 新增回归测试 → 注释里写明**钉的是哪个 bug**，否则下一个人会删掉它。
   **并且做一次反向验证**（把修复改回去确认测试变红）——不做这一步的回归测试有一半是装饰品（见 §10.5 ①）。
4. 改了 CI 行为 → 同步 §10.2 与本表。
5. **注释即需求**：凡是注释里写了「零累积 / 不消耗 / 退回原版 / 已清理」这类承诺，
   必须能在下一行代码里找到对应语句。写不出来的就从注释里删掉，别让它变成谎言（见 §10.5 ③）。
6. **批量文本替换后要抽查**：全局换数字（测试条数、版本号）极易误伤端口/坐标/版本串，
   替换后 `grep` 一遍形似端口或坐标的结果（见 §10.5 ⑤）。

> **文档里的相对链接请检查锚点**：GitHub 把标题转锚点时**会去掉点号**，
> `### 3.2 armor — 盔甲减免` → `#32-armor-盔甲减免`（不是 `#3-2-...`）。改标题后旧链接会失效。
