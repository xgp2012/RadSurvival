# RadSurvival Bug 排查报告

> 排查范围：`src/main/java` 全部 33 个类（约 5,400 行）+ `plugin.yml` / `config.yml` / `pom.xml`
> 排查方式：人工精读核心链 + 4 个子代理并行审计（物品 / 反馈+命令 / 配置+构建 / API+测试有效性）
> 判定标准：每条缺陷都必须能说出「触发条件 → 影响」，并能写出可复现的断言
> 基线：排查前 268 测试全绿；排查后 **277 测试全绿**（新增 9 条复现用例）

---

## 零、结论摘要

| 严重度 | 数量 | 含义 |
|---|---|---|
| **P0** | 2 | 可被玩家反复利用的刷物品 / 数据污染，必须发版前修 |
| **P1** | 8 | 行为与设计承诺不符，或配置校验存在静默放行 |
| **P2** | 7 | 观感 / 文档 / 死代码 / 潜在漂移 |
| 文档一致性 | 3 | 注释与实现已经对不上，会误导后续维护者 |

**最值得先修的三件**：

1. **背包满刷药**（`DustCraftGui.craft()`）—— 唯一一个玩家可以无限刷物品的漏洞，且现有测试把它固化成了"期望行为"。
2. **NaN/Infinity 贯穿配置与事件两条入口** —— 一处 `.nan` 就能让全场伤害、腐蚀、PDB 落盘全部变成 NaN，268 个测试一条都没抓到。
3. **`/rad force-check` 与 `force-check` 无权限、无冷却** —— 玩家可自助把结算节律从「每 20 tick」变成「每次按键」。

---

## 一、P0 缺陷（2 条，已用测试复现）

### P0-1 背包满时药水照掉、作物不扣 —— 可无限刷药

**位置**：[DustCraftGui.java:244-254](src/main/java/com/ft/radsurvival/item/DustCraftGui.java#L244-L254)

```java
ItemStack potion = items.create(level, 1);
var overflow = player.getInventory().addItem(potion);
if (!overflow.isEmpty()) {
    for (ItemStack rest : overflow.values()) {
        player.getWorld().dropItemNaturally(player.getLocation(), rest);  // ← 已产出
    }
    player.sendActionBar(note("§e背包已满§8 · §7药水掉在脚下（作物未消耗）"));
    playFail(player);
    return 0;                                                            // ← 作物没扣
}
```

**触发**：中心格有作物 + 玩家背包 36+9 格全满。
**影响**：`dropItemNaturally` 已经把药水放进世界，但 `return 0` 跳过了后面的扣作物逻辑。**每执行一次就多一瓶药，作物一个不少**，无限刷。提示文案「（作物未消耗）」还把漏洞说成了特性。

**复现**：[BugHuntRegressionTest · BackpackFullDuplicateDust](src/test/java/com/ft/radsurvival/mockbukkit/BugHuntRegressionTest.java) 连续调用 3 次，断言世界里掉出 **3 瓶药**、中心格作物恒为 **2**。

**加重情节**：现有测试 [AntiDustChainMockBukkitTest.java:192-211](src/test/java/com/ft/radsurvival/mockbukkit/AntiDustChainMockBukkitTest.java#L192-L211) 的 `fullInventoryDoesNotConsumeCrop` 把「返回 0 + 作物不变 + 药水掉出来」直接断言成了正确行为，**刷药路径被写进了回归基线**。

**修复建议**：改成「先确认药水真正落到玩家手里，再扣作物」：

```java
ItemStack potion = items.create(level, 1);
var overflow = player.getInventory().addItem(potion);
// 溢出的部分先尝试二次分配，失败才落地——落地也算 "已交付"，
// 因此支付已经发生，必须扣作物，返回 1。
for (ItemStack rest : overflow.values()) {
    player.getWorld().dropItemNaturally(player.getLocation(), rest);
}
// 到这里药水一定已经交付（背包或地面），扣 1 个作物
int remaining = center.getAmount() - 1;
...
return 1;
```

同步把 `fullInventoryDoesNotConsumeCrop` 改为断言「作物 −1 且药水总量 == 产出数」。若产品上更希望「背包满就整单失败、什么都不产出」，则应把 `dropItemNaturally` 整段删掉、`addItem` 之前先判空位，绝不出现「产出但未支付」。

---

### P0-2 配置层数值入口接受 NaN / Infinity

**位置**：[PluginConfig.java:860-874](src/main/java/com/ft/radsurvival/config/PluginConfig.java#L860-L874)（`toDouble`）+ [:931-933](src/main/java/com/ft/radsurvival/config/PluginConfig.java#L931-L933)（`fmt`）

```java
if (raw instanceof Number n) {
    return n.doubleValue();   // NaN / Infinity 原样放行
}
```

**触发**：`config.yml` 写 `night-multiplier: .nan`、`base-damage: .inf` 等（SnakeYAML 把 `.nan`/`.inf` 解析成对应的 double 特殊值）。
**影响**（两种都被测试证实）：

- `.nan` → 所有 `<` / `>` 比较恒为 false → **静默通过全部校验**。随后 `timeFactor`、伤害、腐蚀倍率全变 NaN，`Double.compare(NaN, x)` 也恒不等，于是每轮都判定「值有变化」并**把 NaN 写进 player.dat**。
- `.inf` → 触发越界分支 → 该分支用 `fmt(value)` 拼错误信息 → `BigDecimal.valueOf(Infinity)` 抛 `NumberFormatException` → 被 `loadFromFile` 的兜底 catch 吞掉，最终报「**YAML 解析失败**」。服主会去查缩进，而真实原因是数值非法。

**复现**：[ConfigNonFiniteNumberTest](src/test/java/com/ft/radsurvival/config/ConfigNonFiniteNumberTest.java)（3 条全绿 = 缺陷成立）。

**修复建议**（一处修好，覆盖 base-damage / night-multiplier / corrosion.* / armor.* / anti_dust.* 五段）：

```java
private static Double toDouble(Object raw, String label, List<String> errors) {
    Double v = /* 现有逻辑 */;
    if (v != null && !Double.isFinite(v)) {
        errors.add(label + "：必须是有限数值，实际为 " + raw);
        return null;
    }
    return v;
}

private static String fmt(double v) {
    return Double.isFinite(v) ? BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
                              : String.valueOf(v);
}
```

同时给 `RadiationMath.clampCorrosion` / `clampUnit` 补 `!Double.isFinite → 返回边界值`，形成第二道防线（见 P1-5）。

---

## 二、P1 缺陷（8 条）

### P1-1 任意发射器/投掷器都被当成制药台（不看结构）

**位置**：[DustCraftGui.java:464-473](src/main/java/com/ft/radsurvival/item/DustCraftGui.java#L464-L473) 的 `coreOf()`

只判 `InventoryType.DISPENSER` + `holder instanceof Dispenser/Dropper`，**完全不看铁砧与原木环**。后果：全服**每一台**发射器/投掷器的 UI 都会被「中心格只收农作物、外围只出不进、漏斗双向禁止」接管，玩家没法再把它当红石元件或普通容器用。

讽刺的是 `craft()` 自己会调 `DustCraftStructure.inspect(core)` 校验结构——**放物品时检查结构，收物品时却不检查**。

**复现**：[BugHuntRegressionTest · NonStructureDispenserIsHijacked](src/test/java/com/ft/radsurvival/mockbukkit/BugHuntRegressionTest.java)。
**修复**：`coreOf()` 里追加 `if (!DustCraftStructure.inspect(block).ok()) return null;`（或仅在「结构成立」时才施加写入限制，"取出"方向始终放行）。

### P1-2 重载后 `SunRadiationListener` 不再被 quit 清理（标记表泄漏）

**位置**：[RadSurvivalPlugin.java:111](src/main/java/com/ft/radsurvival/RadSurvivalPlugin.java#L111)（只在 onEnable 注册并绑定）+ [FeedbackService.java:455](src/main/java/com/ft/radsurvival/feedback/FeedbackService.java#L455)

`attachSunRadiationListener()` **只在 onEnable 调用**；`/rad reload` 后新建的 `FeedbackService` 里 `sunRadiationListener` 字段是 `null`，于是 `onQuit` 里的 `forget(id)` 被判空跳过 —— `SunRadiationListener.marks` 里的 UUID 从此只增不减。UUID 复用（离线玩家数据被管理员清掉后用同一账号/同 UUID 数据）时，可能把**别人的** GENERIC 伤害误判成太阳伤害，从而跳过 `restoreVanillaDefense`，表现为「外部伤害莫名其妙不吃护甲」。

**修复**：把 `attachSunRadiationListener(sunRadiation)` 提到 `reloadConfigFromDisk()` 里，与 `registerEvents` 同处（顺带说明为什么监听器复用同一实例）。

### P1-3 `/rad force-check` 对任意玩家开放、无冷却

**位置**：[RadCommand.java:67-86](src/main/java/com/ft/radsurvival/command/RadCommand.java#L67-L86)

`plugin.yml` 声明了 `radsurvival.use`，但 `onCommand` **从未检查它**；`force-check` 走的是真实 `settleRound()`，会真的步进腐蚀、真的掉血、真的写 PDC。

**复现**：[BugHuntRegressionTest · ForceCheckIsFree](src/test/java/com/ft/radsurvival/mockbukkit/BugHuntRegressionTest.java) —— 普通玩家（无 admin 权限）连按两次，腐蚀被连续推进两次、`DamageApplier` 被调两次。

**影响**：结算节律从「每 `tick-interval`」变成「每次按键」，腐蚀推进速度完全由玩家决定。伤害本身受无敌帧保护（不会叠加秒杀），但**腐蚀值可以被玩家自助刷高**（配合 P0-2 的 `.nan` 则更糟）。

**修复**：`onCommand` 入口加 `radsurvival.use` 检查；`force-check` 额外要求 `radsurvival.admin`，或加 1~2 秒的 per-sender 冷却。

### P1-4 `/rad status <他人>` 公开暴露他人装备与预计伤害

**位置**：[RadCommand.java:225](src/main/java/com/ft/radsurvival/command/RadCommand.java#L225)

`previewRound(target)` 对 target 调 `armorCalculator.reductionOf(target)`，输出「盔甲减免 xx%」与「本轮预计伤害」。任何玩家都能查任意在线玩家的防护状态——PVP 服上是情报泄漏。且 `previewRound` 对**被过滤链跳过的玩家**也照样算减免（filter 命中时 `armorReduction` 被固定成 0，但 status 用的是未过滤的预览），显示口径也不一致。

**修复**：非 admin 且非本人时，把「盔甲减免 / 预计伤害」替换为「—」或仅在 `sender == target || hasPermission(admin)` 时输出。

### P1-5 `RadiationMath` 的钳制函数不拦 NaN

**位置**：[RadiationMath.java:25-32](src/main/java/com/ft/radsurvival/core/RadiationMath.java#L25-L32)

`Math.min(100, Math.max(0, NaN))` = `NaN` —— 注释写着「钳到 [0,100]」，实现却在 NaN 输入下原样返回。`CorrosionMeter` 的 `setValue` / `addValue` / `apply` 三个入口全都依赖它兜底，因此 NaN 能一路进内存权威值、进 PDC、进 HUD。`stageOf(NaN)` 静默返回 normal，`corrosionMultiplier(NaN)` 返回 NaN。

**修复**：两个 clamp 开头加 `if (!Double.isFinite(value)) return CORROSION_MIN;`（或对应边界值）。

### P1-6 `RadiationExposureEvent.setFinalDamage` 不校验、不钳制

**位置**：[RadiationExposureEvent.java:155-158](src/main/java/com/ft/radsurvival/api/RadiationExposureEvent.java#L155-L158)

javadoc 写着「负值按 0 处理（不产生回血）」，**实现直接把入参存下来**。而 `DamageApplier.apply` 的守卫是 `amount <= 0.0` —— 对 `NaN` 恒为 false，于是 NaN 通过守卫进入 `damage(NaN)`（Paper 视作 0，伤害静默消失）与反馈挂点，进而污染死亡文案与 `/rad status`。Infinity 则直接秒杀。

**修复**：

```java
public void setFinalDamage(double finalDamage) {
    this.finalDamage = Double.isFinite(finalDamage) ? Math.max(0.0, finalDamage) : 0.0;
}
```

### P1-7 `corrosion.stages.warn/danger` 完全没有校验

**位置**：[PluginConfig.java:510-511](src/main/java/com/ft/radsurvival/config/PluginConfig.java#L510-L511)

全文件唯一一对「读了但一次都没校验」的数值键：不校验 ∈ [0,100]，也不校验 `warn < danger`。
- `warn: 90, danger: 60` → warn 档永远不可达（danger 先命中），阶段播报与 BossBar 配色直接跳过一级；
- `danger: 300` → danger 永不触发，最高只播报 warn；
- `warn: -5` → 玩家刚进服就播「危险」。

三者都是**合法数字、无任何日志**，上线后极难发现。

**修复**：加范围与顺序校验，错误信息里点名两个键的关系。

### P1-8 `bossbar-corrosion` 下玩家重连后腐蚀条永久消失

**位置**：[FeedbackService.java:272-274](src/main/java/com/ft/radsurvival/feedback/FeedbackService.java#L272-L274)

```java
if (!bar.getPlayers().contains(player)) {
    bar.addPlayer(player);
}
```

BossBar 缓存在 `bossbars` map 里，但客户端侧的 BossBar 会在死亡、跨维度、传送时被原版清掉（Bukkit 侧不可感知）。此时 `getPlayers()` 仍然包含该玩家 → 永远不重新 `addPlayer` → **腐蚀条在客户端永久消失**，直到下次 reload 或重进。

**修复**：去掉 `getPlayers()` 判断，每轮直接 `bar.addPlayer(player)`（重复 add 是幂等 no-op）；或在 `PlayerRespawnEvent` / `PlayerChangedWorldEvent` 里显式重建。

---

## 三、P2 缺陷（7 条）

| 编号 | 位置 | 问题 | 修复 |
|---|---|---|---|
| P2-1 | [FeedbackService.java:174-184](src/main/java/com/ft/radsurvival/feedback/FeedbackService.java#L174-L184) | shutdown 里用 `Bukkit.getPlayer(uuid)` 做前置条件；玩家已断线时只 `setVisible(false)`、不 `removePlayer`，BossBar 残留在客户端 | 改用 `BossBar.removeAll()` |
| P2-2 | [FeedbackService.java:256-271](src/main/java/com/ft/radsurvival/feedback/FeedbackService.java#L256-L271) | 阶段配色用原始 double，展示百分比用取整值：44.7% + 阈值 45 时显示「45%」却是蓝色 | stage 改用 `corrosionDisplayPercent()` 驱动 |
| P2-3 | [FeedbackService.java:153-196](src/main/java/com/ft/radsurvival/feedback/FeedbackService.java#L153-L196) | `shutdown()` 不调 `HandlerList.unregisterAll(this)`，旧实例永久滞留 HandlerList（靠 `plugin.feedback() != this` 守卫兜底） | 末尾补一行注销（同类已有先例） |
| P2-4 | [DustCraftGui.java:120-128](src/main/java/com/ft/radsurvival/item/DustCraftGui.java#L120-L128) | 200ms 毫秒窗去重 + 先 `setCancelled` 后判重复：真实双击间隔 < 200ms 时第二次右键被取消但没合成，玩家感觉「点了没反应」 | 去重键改 `(uuid, 方块坐标)` + tick 口径；或把判重提到 `setCancelled` 之前 |
| P2-5 | [RadCommand.java:115-117](src/main/java/com/ft/radsurvival/command/RadCommand.java#L115-L117) | `dust list <player>` 会给出玩家名补全，但该参数根本不被消费 | 补全加 `op.equals("give")` 条件 |
| P2-6 | [CommandOutput.java:83-88](src/main/java/com/ft/radsurvival/command/CommandOutput.java#L83-L88) | CJK 宽度区间 `0x2E80–0xA4CF` 含若干实际 1 列的字符，以之为首的键会多算宽度 | 拆成 `4E00–9FFF ∪ 3000–303E ∪ FF01–FF60` |
| P2-7 | [ProtocolLibHurtFlashEnhancer.java:55-60](src/main/java/com/ft/radsurvival/feedback/ProtocolLibHurtFlashEnhancer.java#L55-L60) | `packet.getIntegers().read(0)` / `getBytes().read(0)` 裸下标，字段口径随版本漂移时只能整包放行 | 改用 ProtocolLib 的 `getEntityModifier()` / `getEntityStatuses()` 高层结构 |

其它登记在案、暂不建议动的小项：

- `CommandOutput.table()` / `rule()` 无任何调用点（死代码）。
- `SubCommand.suggest()` 把别名也纳入 Levenshtein，会建议出 `rl` 而非 `reload`。
- `RadiationMarkListener` 写入的 `radsurvival:source` **在本插件内部没有任何消费者**（`SunRadiationListener` 走的是时间窗+数值判据），是一条只写不读的链路——建议在 javadoc 里明确「仅对外部插件公开」，避免后人以为它是归因主链而误删。
- `plugin.yml` 的 `softdepend` 声明了 `NBTAPI`、`Vault`，代码里零引用，只会无谓推迟自身加载顺序。
- `PluginConfig` 全文件没有「未知键检测」，`dimension:`（漏 s）这类拼写误差会被静默忽略、回落到默认值。

---

## 四、文档 / 一致性偏差（会误导维护者）

1. **[RadiationTask.java:39-41](src/main/java/com/ft/radsurvival/core/RadiationTask.java#L39-L41)** 的类 javadoc 仍写着「减免 ≥ 1.0 完全免疫短路」，这项机制在 v1.2 已被删除。`IMMUNE` 枚举的注释是正确的，两处自相矛盾。
2. **[DayNightProvider.java:18-19](src/main/java/com/ft/radsurvival/core/DayNightProvider.java#L18-L19)** 的时段表写「12000..13000 黄昏」，而 `smooth=false` 分支把整个 `[12000, 23000)` 判为夜晚。结果 `/rad status` 显示「黄昏」时实际按夜晚倍率扣血。注释 [:122-126](src/main/java/com/ft/radsurvival/core/DayNightProvider.java#L122-L126) 有解释，但同一文件里的表没同步。
3. **[AntiDustChainMockBukkitTest.java:192-211](src/test/java/com/ft/radsurvival/mockbukkit/AntiDustChainMockBukkitTest.java#L192-L211)** 把 P0-1 的刷药行为断言成期望值（见 P0-1）。

---

## 五、子代理提出但经核实**不成立**的项

| 说法 | 核实结论 |
|---|---|
| 「`DamageApplier`/`CorrosionMeter` 存在线程安全问题」 | **不成立**。`listeners` 是 `CopyOnWriteArrayList`，受击挂点是 `volatile`，`bossbars`/`lastRadiationHits`/`states` 是 `ConcurrentHashMap`；BossBar/actionbar/title/sound 全在主线程任务里调用。 |
| 「`String.repeat` 可能收到负数值抛异常」 | **不成立**。`CommandOutput` 三处 `repeat` 全部有 `Math.max(0, …)` 或 `10 - filled`（`filled` 恒 ∈ [0,10]）。子代理自己也复核后撤回了这条。 |
| 「`getHandlerList` 缺失导致事件注册失败」 | **不成立**。两个事件类都正确提供了 static `getHandlerList()`。 |
| 「`DayNightProvider` 存在除零」 | **不成立**。`TRANSITION_TICKS` 是常量 1000。真实问题只是注释与实现不同步（见第四节）。 |
| 「`RadiationExposureEvent` 的 `NamespacedKey` 静态初始化会炸」 | **理论成立、实际极低**。`NamespacedKey` 构造只做格式校验，`radsurvival` / `source` 都是合法字符。属防御性建议，不列为缺陷。 |
| 「CI 版本门禁永远匹配不上」 | **不成立**。提出该条的子代理自查后主动撤回：`BUILD_DATE` 与 maven 过滤的 `${project.version}` 同源，能匹配。 |

---

## 六、测试有效性：「假绿」缺口

现有 268 条测试质量整体不错（无 `@Disabled`、无空断言），但以下几处属于**结构性假绿**，会在未来重构时静默退化：

| 编号 | 位置 | 为什么是假绿 |
|---|---|---|
| T-1 | [DamageChannelAndReloadMockBukkitTest.java:149-169](src/test/java/com/ft/radsurvival/mockbukkit/DamageChannelAndReloadMockBukkitTest.java#L149-L169) | `try { executor.damage(...); } catch (RuntimeException expected) {}` —— 不断言异常真的发生，也不比对 `ItemStack` 本体（附魔/自定义名丢了照样绿） |
| T-2 | [RadiationTaskMockBukkitTest.java:89-110](src/test/java/com/ft/radsurvival/mockbukkit/RadiationTaskMockBukkitTest.java#L89-L110) | `within(1e-3)` 容差恰好能同时通过「先累后打」和「先打后累」两种实现——**顺序不变量其实没被证伪** |
| T-3 | `SessionLifecycleMockBukkitTest` | 没有 join → quit → **join** 的往返用例；现有「恢复」测试是 `forget()` + 手写 PDC 造的假离线 |
| T-4 | `Support.java:116` | 用 `player.damage(double)` 代替真实 `damage(double, DamageSource)`，**完全绕过无敌帧**。`tick-interval=20` 恰在无敌帧边界，实机「每轮是否真掉血」没有任何测试能证明 |
| T-5 | `CorrosionStageFeedbackMockBukkitTest` | 用 `announcementsPlayed` 计数器当作「播报已投递」的证据，但该计数器在 `sound` 开关与在线判空**之后**才自增，语义不符 |
| T-6 | `SurvivalBudgetRegressionTest` | 8 条预算断言均用 ±20% 容差，注释自己承认「容差恰好把错值兜住」 |

建议优先补的三条：**T-4（无敌帧替身 + 20/40 tick 对比）**、**T-2（容差收紧到 1e-9 并显式断言 `corrosionAfter − corrosionBefore`）**、**T-1（注入确定抛异常的 executor + 断言同一性）**。

---

## 七、本次新增的复现测试

| 文件 | 用例数 | 覆盖缺陷 |
|---|---|---|
| [BugHuntRegressionTest.java](src/test/java/com/ft/radsurvival/mockbukkit/BugHuntRegressionTest.java) | 6 | P0-1、P1-1、P1-3、P1-4、P1-2、配置键拼写 |
| [ConfigNonFiniteNumberTest.java](src/test/java/com/ft/radsurvival/config/ConfigNonFiniteNumberTest.java) | 3 | P0-2 |

这些用例断言的是「当前代码真的会这样」，**因此它们是红的才是修好了** —— 修复缺陷时请同步把这些断言改成期望行为，并保留 `DisplayName` 里的缺陷编号便于追溯。

全量结果：**277 tests, 0 failures, 0 errors, 0 skipped**。

---

## 八、建议的修复顺序

1. **P0-1 + P0-2**（刷物品 + NaN 总闸）—— 改动都在 10 行内，收益最大。
2. **P1-5 + P1-6**—— 与 P0-2 同源，一起修能把 NaN 从三条入口全部封死。
3. **P1-1 + P1-3 + P1-4**—— 三个「不该暴露给玩家」的面，都是加一行判定的事。
4. **P1-2 + P1-7 + P1-8**—— 泄漏与校验缺口。
5. **P2 与文档同步**—— 可并入同一次发版。

---

## 九、修复记录（已全部落地并验证）

修复遵循同一套纪律：**先让复现用例在修复后变红**（证明缺陷确实存在且被触及），
再把断言改写成期望行为，最后跑全量回归。下表「红→绿」列即该转换的实证。

全量结果：**310 tests, 0 failures, 0 errors, 0 skipped**（基线 268，本次新增 42）。

| 编号 | 修复位置 | 做法要点 | 红→绿实证 |
|---|---|---|---|
| P0-1 | `DustCraftGui.craft()` + 新增 `hasRoomFor()` | 精确预判「放不放得下」：能并进同类未满堆的算容量、空位按最大堆叠算。放不下 → **整单失败**（不产出、不掉落、不扣作物）；放得下 → `addItem` 必然全收，收货后**无条件**扣 1 个作物。api 无 `hasSpaceFor`（`javap` 核实），故自实现 | `BackpackFullDuplicateDust` 由「断言掉落 3 瓶」变为修复后 `Expecting actual not to be empty` 失败 |
| P0-2 | `PluginConfig.toDouble` / `fmt` | 入口拦 `!isFinite`；`fmt` 对非有限值原样输出（`BigDecimal.valueOf(NaN)` 会抛异常，把真实错误伪装成「YAML 解析失败」） | `.nan` 由「静默通过」变红；`.inf` 由报 YAML 错变红 |
| P1-1 | `DustCraftGui.coreOf` / 新增 `restrictable()` | 刻意**不**在 `coreOf` 里查结构（`craft()`/`openFor()` 承诺「结构不完整也能先备料」）；写入方向另用 `restrictable` 筛一次，结构不成立即退回原版 | `NonStructureDispenserIsHijacked` 由「裸发射器被拦截」变红 |
| P1-2 | `RadSurvivalPlugin` 字段化 + `reload` 重绑 | 门控监听提升为字段并在 reload 时新建/注册/`attach`；旧实例靠 `plugin.sunRadiation() != this` 沉默，不再向其 `marks` 记账 | 新增 `SunMarkReloadLeakMockBukkitTest`（3 用例），修复前 `marks` 残留 |
| P1-3 | `RadCommand.handleForceCheck` | 要求 `radsurvival.admin`（与 `corrosion set/add` 同级，因它真实结算并改写 PDC）；普通玩家改用 `/rad status` | `ForceCheckIsFree` 由「连按两次都步进腐蚀」变红 |
| P1-4 | `RadCommand.handleStatus` | `canSeeEquipment = canInspectOthers(sender) \|\| sender == target`，遮蔽他人盔甲减免/最低杀伤/预计伤害三行 | `StatusLeaksArmorOfTarget` 由「能读到 72%」变红 |
| P1-5 | `RadiationMath.clampCorrosion/clampUnit` | 非有限值一律落到**下界**（腐蚀越高伤害越大，解释成「满腐蚀」等于凭空造伤害） | 新增 `NonFiniteNumberGuardTest`（4 用例） |
| P1-6 | `RadiationExposureEvent.setFinalDamage` | 非有限值按 0、负值钳 0；下游 `amount <= 0.0` 守卫对 NaN 恒为 false，故必须写入点封死 | 同上 |
| P1-7 | `PluginConfig` corrosion.stages | warn/danger 校验 ∈[0,100] 且 `warn < danger`（此前读了却零校验，三种错配全部静默） | 新增 `StageThresholdValidationTest`（6 用例） |
| P1-8 | `FeedbackService.renderOne` | `bar.addPlayer(player)` 改**无条件**（死亡/跨维度/传送会让客户端丢条，而 `getPlayers()` 仍含该玩家 → 永不补发） | — |
| P2-1 | `FeedbackService.shutdown` | 改 `BossBar.removeAll()`，不再依赖 `Bukkit.getPlayer(uuid)` | — |
| P2-2 | `FeedbackService.renderOne` | 阶段色改由 `corrosionDisplayPercent()` 驱动，与标题同口径 | — |
| P2-3 | `FeedbackService.shutdown` | 末尾补 `HandlerList.unregisterAll(this)` | — |
| P2-4 | `DustCraftGui.isDuplicate` | 去重键改 `(uuid, 方块坐标)`、窗口 200→150ms、判重提到 `setCancelled` **之前** | — |
| P2-5 | `RadCommand.tabDust` | 玩家名补全加 `op.equals("give")`；`levels` 为空时也退化到内置表 | — |
| P2-6 | `CommandOutput.charWidth` | 区间拆为 `4E00–9FFF ∪ 3000–303E ∪ FF01–FF60 ∪ FFE0–FFE6`（原区间含彝文等实为 1 列的码位） | 新增 `CommandOutputWidthTest`（13 用例，该包此前**零测试**） |
| 配置键 | `PluginConfig` + `config.yml` | `hud-immume-mark` → 正确拼写 `hud-immune-mark`；**两种拼写都接受**、正确优先，并存时给出提示 | `MisspelledConfigKey` 由「正确拼写被静默忽略」变红 |

**未修（登记在案，需产品决策）**：

- P2-7（ProtocolLib 裸下标）：改动面涉及外部插件版本口径，且当前路径已是「解析失败即整包放行」的安全降级。
- `fullNetheriteNoLongerImmune` 用 `within(1e-3)` 断言，无法区分「先累后打」与「先打后累」——需改用可辨识顺序的断言。
- `Support.java:116` 的伤害替身绕过原版无敌帧，部分伤害链测试因此失真。
- `CorrosionStageFeedbackMockBukkitTest` 的 `announcementsPlayed` 只在 `sound`/在线守卫之后自增，断言口径偏弱。

**文档同步**：`RadiationTask` 类注释里「减免 ≥ 1.0 完全免疫短路」「全下界合金零伤害零累积」为 v1.1 语义，
已改写为 v1.2 实际链路（无免疫分支、恒有保底伤害，唯一真免疫来自抗尘埃药窗口）。
`DayNightProvider` 注释与实现经核对**一致**（四段左闭右开、黄昏 12000–13000），无需改动。
