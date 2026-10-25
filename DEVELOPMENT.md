# RadSurvival 开发文档

> 面向**维护者与二次开发者**。使用者请看 [README.md](README.md)，设计规格与决策记录看
> [plan.md](plan.md)，实机验收证据看 [ACCEPTANCE.md](ACCEPTANCE.md)。
>
> 最后更新：2026-10-25（v1.1 判定链路加固：摘甲结算 + 太阳伤害门控 + reload 解绑）

---

## 1. 项目速览

| 项 | 值 |
|---|---|
| 目标平台 | Paper **1.21.11**（build 132 实测），`api-version: '1.21'` |
| 语言/JDK | Java **21**（`maven.compiler.release=21`） |
| 构建 | Maven 3.10+，JDK 21（本机验证：`D:\java21`，Azul Zulu 21.0.12.1） |
| 产物 | 自包含 fat jar，约 1.8 MB，内嵌库已 relocate 到 `com.ft.radsurvival.lib.*` |
| 测试规模 | **188 条**（纯内核 + MockBukkit 集成），全绿、无跳过 |
| NMS | **零依赖**（纯 Bukkit/Paper API） |

**一句话架构**：`config` 解析并校验 → `core` 六单例承载全部纯逻辑 → `RadiationTask` 每
`tick-interval` 跑一轮 → `feedback` 负责表现 → `api` 对外收敛读写。

---

## 2. 包结构与职责

```
com.ft.radsurvival
├── RadSurvivalPlugin      插件入口：装配、reload、onDisable（薄壳，不含业务逻辑）
├── SubCommand / RadCommand / CommandOutput   命令层（路由 / 别名 / 权限 / 排版）
├── config/
│   ├── PluginConfig       纯 Java 配置模型 + 全量校验（不依赖 Bukkit 服务）
│   └── (内部 record)      ArmorConfig / CorrosionConfig / FeedbackConfig / CommandsConfig
├── core/                  ★ 判定与结算内核（可纯单元测试）
│   ├── RadiationMath      伤害/累积/倍率/曲线的纯函数（单调有界）
│   ├── DayNightProvider   昼夜阶段与 timeFactor（含线性插值）
│   ├── ArmorCalculator    部位权重 × 材质系数 → 减免率
│   ├── SunExposureChecker 露天判定（段缓存 + 异步补扫 + 粘滞层）
│   ├── CorrosionMeter     腐蚀值权威存储（内存）+ PDC 持久化 + 阶段回调
│   ├── DamageApplier      事件广播 + 真实掉血（唯一伤害出口）
│   ├── RadiationTask      主循环：过滤链 → 判定 → 腐蚀步进 → 伤害
│   ├── PlayerRadState     单玩家结算镜像（HUD/调试读口）
│   └── RadiationServices  六单例容器（reload 时整体重建）
├── feedback/              表现层（HUD 双条 / 阶段播报 / 粒子音效 / BossBar / 红闪抑制）
├── listener/              会话生命周期、事件+PDC、伤害标记、伤害门控、死亡文案
├── placeholder/           PlaceholderAPI 扩展（缺失时零加载）
└── api/                   对外扩展点（事件 + 门面）
```

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
       ├─ ⑥ 伤害合成：base × timeFactor × corrosionMult × (1 − armorReduction)
       └─ ⑦ DamageApplier.apply(...) → RadiationExposureEvent → damage()
```

**三条口径（改动前必读）**

1. **先累后打**：腐蚀步进发生在伤害计算**之前**，本轮累加的腐蚀立刻计入倍率。
2. **无敌帧**：`tick-interval ≥ 20`（`PluginConfig` 校验下限），否则伤害会被原版无敌帧吞掉。
3. **不双重减免**：见 §10.3 —— 由「结算瞬间摘甲」保证，而非依赖伤害类型。

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
├── core/      RadiationMath / DayNight / Armor / Corrosion / SunExposure（纯内核）
├── feedback/  HudRenderer 纯函数
├── listener/  SunRadiationListenerTest（伤害通道归因纯内核）
└── mockbukkit/ 集成测试（MockBukkit 4.116.3，包 org.mockbukkit.mockbukkit）
```

```powershell
$env:JAVA_HOME='D:\java21'
$env:MAVEN_OPTS='-Duser.language=en -Dfile.encoding=UTF-8'   # 避免 GBK 下中文编译错误
mvn -B clean package            # 全量（188 条）
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

1. `mvn -B clean package` → `Tests run: 188, Failures: 0`
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
**明确放弃**；v1.1 判定链路加固已完成（188 条测试全绿）。

**可直接使用的产物**（三处哈希一致，已通过 Paper 1.21.11-132 实机验收）：

```
target/RadSurvival.jar
dist/RadSurvival-1.0.0-SNAPSHOT.jar
test-server/plugins/RadSurvival-1.0.0-SNAPSHOT.jar
```

**接手后第一件事**：跑一次 `mvn -B clean package` 确认 188 条全绿。

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
