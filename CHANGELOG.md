# 更新日志

本文件记录 RadSurvival 的版本变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> **阅读提示**：破坏性变更（升级后旧配置/旧玩法会变）集中在
> [v1.2.0 的「升级注意」](#升级注意v11-v12) 一节，升级前请先看那一节。

---

## [未发布]

### v1.4.1 — 制药台结构改为竖列三格（进行中）

#### 变更

- **抗尘埃药合成结构从「9 方块平面」改为「3 方块竖列」**：
  - 旧：发射器/投掷器为核心，**正上方铁砧** + **同层 8 邻一圈原木**；
  - 新：**最上面铁砧** → **中间发射器/投掷器** → **最下面铁块**。
  - 四个水平方向放什么方块**不再参与判定**，占地从 3×3 缩到 1×1。
- `DustCraftStructure`：`validate(core, above)` → `validate(core, above, below)`；
  新增 `isBase(Material)`（只认 `IRON_BLOCK`）；删除 `RING_FACES` 常量与环校验分支。
  `isWood(Material)` 保留（`Tag.LOGS` 口径不变），但**生产路径不再调用**。
- 诊断信息与帮助文案同步：缺下方铁块会明确点名「核心正下方必须是铁块（当前：XXX）」；
  `/rad dust help` 与启动摘要改为三行结构描述；`Result#summary()` 通过时输出
  「结构完整：铁砧 + 发射器 + 铁块」。
- `config.yml` 的 `anti_dust` 段落注释同步（**纯注释，无新配置键，无键值变更**）。

#### 升级注意

- **存量制药台会失效**：旧结构（8 邻原木环）不再被识别，需要按新形状重建：
  拆掉原木环，在发射器**正下方**补一块**铁块**即可（铁砧位置不变）。
- 本改动**不影响**任何数值、配置键、物品 PDC、命令与权限；已产出的药水照常可用。

#### 测试

- `DustCraftStructureMockBukkitTest` 由 11 → 12 条（新增 `isBase` 与「缺铁块」诊断、去掉环相关断言）；
- `AntiDustChainMockBukkitTest` / `BugHuntRegressionTest` 的夹具改为搭三格竖列；
- 全量 333 条测试在 JDK 21 下全绿。

---

### v1.4.0 — 服务端品牌自适应 Profile：识别与防御（待发版）

> 落地 [plan.md](plan.md) §7 的**识别 + 防御**部分，并按实施决策**缓建**按品牌调参的
> 预设体系（原因见下「未实施」）。功能上对既有 Paper 服**零行为变化**：
> 数值公式、判定链路、三条口径、配置键全部未动，`/rad status` 与启动日志各多一行信息。

#### 新增

- **服务端品牌识别**（`core/ServerBrand`）：归一化为
  `paper / leaf / purpur / spigot / folia / unknown`，并记录**识别来源**
  （类探测 / `server.getName()` / 兜底），随启动日志与 `/rad status` 的 `platform`
  行展示。识别为纯 Java（只接受 `String` 与 `ClassProbe`），不依赖 Bukkit，
  因此可脱离服务端做单元测试。

  **判定顺序即安全性排序**——先判「跑不跑得起来」，再判「是不是某分支」：

  | 顺序 | 判定 | 依据 | 结果 |
  |---|---|---|---|
  | ① | Folia | `RegionizedServer` 类存在 | **拒绝加载** |
  | ② | Purpur / Leaf / Folia | `getName()` 唯一命中（这些名字不会被别的分支占用） | 按识别结果 |
  | ③ | Paper | `org.bukkit.damage.DamageSource` 可加载（= 本插件的实际编译依赖） | 放行 |
  | ④ | Spigot | `getName()` 含 `spigot` 且类探测阴性 | **拒绝加载** |
  | ⑤ | unknown | 兜底 | 放行但明确标注「未实测分支」 |

- **防御分支**（`RadSurvivalPlugin#guardServerBrand`，在 `onEnable` **最前面**，
  先于 `saveDefaultConfig()` 与一切 Paper API 调用）：
  - **Spigot**：`paper-api` 专属类（`DamageSource`/`Component`）缺失会让 `onEnable` 抛
    `NoClassDefFoundError`，且抛出点常在类初始化期间，管理员从堆栈里看不出「换服务端」
    才是解法。现在改为启动首步就给出确定结论 + 中文指引，然后 `disablePlugin`。
  - **Folia**：`BukkitScheduler` 的**全局任务**在 Folia 上直接抛
    `UnsupportedOperationException`，而本插件的主循环 / HUD / 露天异步补扫全部依赖全局
    调度。继续加载只会得到「启动成功但完全不工作」的假象，比拒绝加载更难排查，
    故一并拒绝（plan.md §7.2 原写「WARNING + 禁用」，实施时明确为**不继续启用**）。
  - **unknown**：按 Paper 同面 API 放行，日志追加「未实测分支，如遇问题请附本行」。
    **绝不对未实测分支拒绝加载**——那样每个小众分支都装不上，收益为负。

- **`plugin.yml` 新增 `folia-supported: true`**（v1.4 跨分支实测驱动）。
  **这不是「支持 Folia」的声明**，而是让我们的拒绝提示**能真的弹出来**：
  缺省该字段时，Folia 在**插件加载阶段**（类尚未载入）就拒绝，报一句英文
  `not marked as supporting Folia`，`guardServerBrand()` 的 FOLIA 分支成了**死代码**
  ——这是 MockBukkit 单测无法发现的（它只验证识别函数返回值，验证不了那段代码是否可达）。
  声明后插件被载入、识别、打印中文原因并自我禁用。验证记录见 ACCEPTANCE §M.2。

#### 跨服务端实测（v1.4.0）

| 分支 | 版本 | 结果 |
|---|---|---|
| Paper | 1.21.11-132 | ✅ 正常启用，`platform` 行 = `Paper（类探测）` |
| Leaf | 1.21.11-179 | ✅ 正常启用，`platform` 行 = `Leaf（server.getName()）` |
| Folia | 1.21.11-14 | ✅ 拒绝加载 + 三条中文提示 |
| Purpur | 1.21.11-2568 | ⚠️ 下载源不可达，**未实测** |

实测脚本与结论见 [test-servers/run-smoke.ps1](test-servers/run-smoke.ps1) 与
[ACCEPTANCE.md](ACCEPTANCE.md) §M。**Purpur 仍为「理论可用、未实测」**，不因本次
改动升级为「已验证」。

#### 未实施（有意缓建，非遗漏）

- `adaptive-profile: auto|off|<brand>` 配置键、`profiles.<brand>` 预设段、
  「用户显式值 > 品牌 profile > 内置默认」三层优先级、Configurate 节点 presence 检测
  ——**整体不做**。plan.md §7.2 的边界表里，唯一随品牌变化的维度是**主线程压力特征**，
  而本项目 M2 后主线程稳态已近零开销（露天补扫走异步池）。在没有任何跨服务端实测数据
  的前提下按品牌改 `tick-interval` / 缓存 TTL / HUD 频率，等于拿实测数据换猜测，
  且会引入「同一份 config.yml 在不同服务端生效值不同」的困惑。
- 恢复条件：确有服务端侧实测差异（例如某分支上异步补扫显著更慢）时，再引入最小配置段，
  并按 §7.5「每个 profile 视为一次支持矩阵声明」跑完整回归。

#### 测试

- 新增 `ServerBrandTest`(11)：Paper/Leaf/Purpur 放行、Folia 类优先于 `getName`、
  Folia 与 `DamageSource` 并存时仍判 Folia、Spigot 拒绝、Spigot 若能加载 Paper 类则不误拒、
  unknown 放行、`getName` 为 null/空串/空白不抛异常、`describe()` 不伪造 Paper、
  大小写与空白不敏感、生产探测器对 `DamageSource` 的真实存活。
- 新增 `ServerBrandWiringMockBukkitTest`(3)：正常服务端不被误杀、`brand` 在 `onEnable`
  写入、reload 后 `brand` 保持不变。
- 新增 `ServerBrandSpigotIsolatedTest`(3)：用**屏蔽 `DamageSource` 的受限 ClassLoader**
  模拟 Spigot（官方不发预编译 jar，真机不可得），验证「类不可加载 → SPIGOT + 拒绝」，
  并附两条防误伤断言：未知名分支仍放行、`getName` 自称 Paper 但探测为阴时不得谎报支持。
  **本类明确不验证**「真 Spigot 上 `onEnable` 会执行到拒绝分支」——那是关于 Spigot 行为的推断。
- **反向验证**（四项，均确认「改坏 → 变红」）：
  1. 去掉 Folia 类探测优先判断 → `foliaWinsOverPaperClass` 与
     `foliaDetectedByClassEvenIfNameSaysPaper` 两条变红；
  2. `defaultProbe()` 改为恒 `false` → `defaultProbeFindsPaperClassAtTestRuntime` 变红；
  3. `UNKNOWN` 改为不可运行 → `unknownIsTreatedAsRunnable` 变红；
  4. 受限 ClassLoader 的屏蔽条件改为永不命中 → `ServerBrandSpigotIsolatedTest` 3 条全红。
- 测试总数 315 → **332**，全绿（`332 tests, 0 failures, 0 errors, 0 skipped`）。

#### 升级注意（v1.3.x → v1.4.0）

- **无需改配置**：未新增/未删除任何配置键。
- **纯 Spigot 服**：此前是「启动时报 `NoClassDefFoundError` 堆栈」，现在是
  「明确的中文拒绝加载提示」。行为方向一致（都跑不起来），只是提示变可读。
- **Folia 服**：此前是「启用后主循环抛 `UnsupportedOperationException`」，现在是启动即拒绝。
- **Paper / Leaf / Purpur 服**：无变化。启动日志多一行 `平台 · Paper（类探测）`。

---

### v1.3.1 — 全量缺陷排查修复（待发版）

> 由一次覆盖 `src/main/java` 全部 33 个类的系统性排查驱动，共修复 17 项缺陷
> （2 项 P0 / 8 项 P1 / 7 项 P2）。完整触发条件、影响与实证见 [BUGHUNT.md](BUGHUNT.md)。
> 每项修复都以「复现用例先变红、改写断言后变绿」的方式验证。
> 测试总数 268 → **310**，全绿。

#### 严重（影响数据正确性 / 可被反复利用）

1. **背包满时用药水掉落、却不扣作物 —— 可无限刷抗尘埃药**
   旧逻辑在 `addItem` 溢出时把药水 `dropItemNaturally` 到地上，随后 `return 0` 跳过了扣作物。
   每执行一次多一瓶药、作物一个不少，且提示文案还把漏洞写成特性「（作物未消耗）」。
   现改为先精确预判背包容量（能并进同类未满堆的算容量、空位按最大堆叠算）：**放不下就整单失败**
   ——不产出、不掉落、不扣作物；放得下才交付，交付后**无条件**扣 1 个作物。
   注意 `paper-api 1.21.11` 的 `Inventory` / `PlayerInventory` **没有** `hasSpaceFor`（已用 `javap` 核实），
   故容量判断由插件自实现。
   *既有测试 `fullInventoryDoesNotConsumeCrop` 曾把该漏洞断言为正确行为，已改写为 `fullInventoryConsumesNothing`。*

2. **NaN / Infinity 可从配置注入，污染全场伤害与落盘数据**
   `corrosion.stages.*` 之外的所有数值键共用 `toDouble`，而 `Double.isNaN` 参与的比较恒为 false，
   于是 `.nan` 能**静默通过全部校验**；`.inf` 则会在格式化错误信息时抛
   `NumberFormatException`，被兜底捕获后**误报成「YAML 解析失败」**，把服主引向错误方向。
   现在入口一律拒绝非有限数，错误信息明确写「必须是有限数值」。

#### 安全 / 行为契约

3. **任意发射器/投掷器都被当成制药台**：写入约束此前只看容器类型、不看结构，等于把全服的
   发射器变成「只收农作物」的容器。现在结构不成立（或结构暂时读不出）即完全退回原版行为。
   `craft()` 仍允许在结构不完整时先把作物备好——两处口径已分别注释说明。
4. **`/rad status <他人>` 泄露对方盔甲减免与预计伤害**：PVP 场景下这是免费的对手情报，
   而该命令没有任何权限门槛。现在这三行仅管理员或本人可见。
5. **`/rad force-check` 对所有人开放且无冷却**：它走的是**真实结算**（步进腐蚀 + 广播事件 +
   真实掉血 + 写 PDC），玩家连按就能把结算节律从「每 tick-interval」变成「每次按键」。
   现要求 `radsurvival.admin`（与 `corrosion set/add` 同级），普通玩家改用 `/rad status`（只读、零副作用）。
6. **reload 后全场抗尘埃药免疫被静默清空**：`DustImmunity` 随服务容器重建，而免疫窗口只存在内存里。
   已确认这是**设计上的内存态**，将其持久化会改变玩法语义，故本次仅登记、未改行为。
7. **reload 后太阳伤害标记表无界增长**：门控监听在 reload 时新建，但新反馈层没拿到引用，
   退服清理恒被空判跳过 → `marks` 只增不减，UUID 复用时可能把别人的普通伤害认成太阳伤害。
   现在监听被字段化，reload 时重新注册并重新绑定，旧实例以「非当前实例」守卫彻底沉默。

#### 校验缺口

8. **`corrosion.stages.warn/danger` 读了却零校验**：`warn ≥ danger` 时 warn 档永不可达
   （阶段播报直接跳级）、`danger > 100` 永不触发、`warn < 0` 一进服就播「危险」——全部静默。
   现校验两者 ∈[0,100] 且 `warn < danger`。

#### 显示 / 维护性

9. 腐蚀条 BossBar 在死亡、跨维度、传送后会**永久消失**：客户端丢条但 `getPlayers()` 仍含该玩家，
   旧代码的「不在列表才 add」永远不再补发。现改为每轮无条件 `addPlayer`（幂等）。
10. BossBar 阶段配色与标题百分比不同源（44.7% 显示「45%」却是蓝色），现统一由显示百分比驱动。
11. `shutdown()` 未调 `HandlerList.unregisterAll(this)`，每次 reload 都在 HandlerList 里多留一份失效实例；已补。
    BossBar 清理改用 `removeAll()`，不再因玩家刚断线而漏清。
12. 制药台去重改为「**同一方块 + 150ms**」并且判重提前到取消事件**之前**：
    旧实现只看时间不看方块，换个铁砧点第二下会被取消却不合成，玩家感觉「点了没反应」。
13. `/rad dust list|help` 会给不被消费的玩家名参数做补全；`levels` 为空时补全空手。均已修正。
14. 命令行表格宽度把 `0x2E80–0xA4CF` 整段当全角，其中彝文等实为 1 列的码位会让整列对齐漂移。
    区间收紧为真正的全角范围（`command` 包此前**没有任何单元测试**，本次补 13 条）。

#### 配置

15. **`anti_dust.hud-immume-mark` 拼写错误（双 m）**：正确拼写 `hud-immune-mark` 会被静默忽略。
    现在**两种拼写都接受**、正确拼写优先，两者并存时给出明确提示；`config.yml` 已改用正确拼写。
    老配置无需修改。

#### 露天判定（实机验证发现，见下条 v1.3.2）

16. **负 Y 区间（y < 0）露天判定整段漏判 —— 地下露天玩家白天不掉血**
    `SunExposureChecker` 把列扫描下界写死为 `MIN_BUILD_HEIGHT(0) - 1 = -1`，注释理由
    「1.21 世界 y 从 -64 起，0 已在其上」是错的：1.21 主世界 y 范围是 **-64..319**，
    y<0 是正常可站立层（深板岩层、矿洞、部分出生点）。扫描停在 -1 意味着 `[-64,-1]`
    区间的方块**从未被读过**，无遮挡列因此返回哨兵 `-2` → `eyeY(-60) > -2` 为假 →
    判成「遮蔽」→ 白天在地下露天区域**完全不掉血**（漏判危险，正是 §1.1 最忌讳的方向）。
    实机复现：玩家站在 y=-60、头顶一路到 319 全空，`/rad status` 报「遮蔽/豁免维度（安全）」。

    现改为由 `world.getMinHeight()` 驱动的 `columnScanStartY(worldMinHeight)`，
    扫描下界 = `worldMinHeight - 1`，任何世界都不留盲区；旧无参重载保留为
    `@Deprecated` 的纯内核默认值（取 -64）。

#### 测试

- 新增 42 条用例：`BugHuntRegressionTest`(9)、`ConfigNonFiniteNumberTest`(5)、
  `NonFiniteNumberGuardTest`(4)、`StageThresholdValidationTest`(6)、
  `SunMarkReloadLeakMockBukkitTest`(3)、`CommandOutputWidthTest`(13)、
  `PluginConfigValidationTest`(+1 历史拼写兼容)、`AntiDustChainMockBukkitTest`(+1)。
- `AntiDustChainMockBukkitTest` 中把漏洞断言为正确行为的两条用例已改写为期望行为。

#### v1.3.2 — 实机验收驱动的负 Y 漏判修复（待发版）

> 由一次「Paper 1.21.11-132 + 原版 1.21.11 客户端」的实机验收驱动。
> 该缺陷**在 310 条测试全绿的情况下漏网**，因为既有 `SunExposureCheckerTest`
> 的所有用例眼高都是 `y ≥ 64`，从未覆盖负 Y 区间。

##### 修复

- **负 Y 露天判定漏判**（缺陷 16）：`SunExposureChecker` 的列扫描下界改为由
  `world.getMinHeight()` 驱动，不再写死 -1。实机复验：同一位置由
  「遮蔽/豁免维度（安全）」翻转为「露天（头顶可见天空）」，腐蚀开始累积、伤害开始落地。

##### 测试

- 新增 `NegativeYExposureRegressionTest`(5)：负 Y 露天/遮蔽/世界最低层/正 Y 对照/
  扫描起点契约。**反向验证**：把 `columnScanStartY` 改回返回 -1，3 条负 Y 用例
  立即变红（正 Y 对照仍绿），证明钉子确实咬住该缺陷。
- `SunExposureCheckerTest` 中 4 处写死 `(0 - 1)` 的扫描格数断言改为
  `columnScanStartY(MIN_HEIGHT)` 派生，避免把错误下界定死进测试。
- 测试总数 310 → **315**，全绿。
- 全量：**310 tests, 0 failures, 0 errors, 0 skipped**。

#### 文档

- `RadiationTask` 类注释里「减免 ≥ 1.0 完全免疫短路」「全下界合金零伤害零累积」是 v1.1 语义，
  与 v1.2 起「减免恒 < 1.0、恒有保底伤害、唯一真免疫来自抗尘埃药」不符，已改写。

---

### v1.3.0 — 露天判定稳定性修复（待发版）

> 只动**判定精度与开销**，不动任何玩法数值：白天/夜晚系数、腐蚀、护甲、抗尘埃药口径一律未变。

#### 修复

1. **头顶明明没有方块，却被判定为露天掉血 / 判定时有时无（主要症状）**
   露天判定的缓存键原先包含**眼睛所在的高度段**（`sectionY`），而扫描起点也取该段底。由此产生
   两个真实后果：
   - **同列判定来回切换**：同一方块列被不同高度段各扫一次，每次只从「该段底」往上扫，各自算出
     一个**互不相同且偏高**的顶遮挡哨兵值写进缓存 → 高眼位写下的值会让低眼位误判遮蔽、低眼位写下
     的值又漏掉真实遮挡，玩家在两个值之间被反复推来推去。
   - **判定随动作漂移**：默认眼高 1.62 落在第 4 段、蹲下 1.27 落在第 3 段，**蹲下站起就换键**，
     于是判定结果跟着动作跳跃。

   现改为按**方块列**缓存（键 = 世界 + x/z 的 16 格段，不含 y），扫描统一从世界底部之下起算
   **整列**，同列所有眼位共享同一个顶遮挡高度，判据 `eyeY > 顶遮挡` 对列内任意眼位精确成立。

2. **判定开销随玩家所处高度变化（重复整列扫描）**
   旧实现下，玩家每跨过一次 16 格段界（跳跃、上下台阶、站起蹲下）就触发一次整列重扫；在地面附近
   活动的玩家几乎每轮都在扫。v1.3 后一列只扫一次、60s TTL 内零扫描，扫描量与眼高彻底解耦。

3. **缓存规模按 16³ 段计算（顺带收紧）**
   旧实现每 16³ 段一条缓存，同一方块列的多个 y 段各占一条；现在每 16×16 方块列一条，
   条目数不再随玩家上下移动增长。

#### 变更

- 缓存键 `ColumnKey` 去掉 y 维度，改为 `(world, sectionX, sectionZ)`；
  旧的 `ColumnKey.of(world, x, y, z)` 重载保留并标 `@Deprecated`（y 被忽略），不打断既有调用点。
- 新增 `SunExposureChecker#columnScanStartY()`：整列扫描起点，取世界底部之下，保证顶遮挡是
  **整列**属性而非「段内」属性。
- 未改：异步补扫管线（`async-exposure-scan`）、双层缓存结构（权威层 TTL 60s + 粘滞层）、
  冷启动主线程直算、异常吞掉下轮重试、透明方块口径、维度豁免 —— 全部原样保留。

#### 升级注意（v1.2 → v1.3）

- **无配置变更**：`config.yml` 一个键都没变，可以直接覆盖 jar 升级，无需 `/rad reload --dry-run` 预检。
- **玩家可感知的差异**：进门/贴墙/上下台阶时，掉血判定不再「闪」，行为会更稳定；
  之前被误判掉血的遮蔽处会明显安静下来。如果服主此前为压制抖动把 `tick-interval` 调大了，
  升级后建议改回 `20`（默认值）。
- **回归钉子**：新增 3 条测试钉住本次修复——
  `verticalMovementDoesNotRescanOrFlip`（列内上下移动不重扫、判定不翻转）、
  `columnScanCoversBelowEyeHeight`（扫描必须覆盖整列，低眼位不得被高眼位写下的值误判遮蔽）、
  `cacheEntriesAreColumnScoped`（缓存条目按方块列计，跨 y 段不新增条目）。
  测试规模 **265 → 268**；三条均做过反向验证（把键改回含 y 段后测试变红）。

#### 内部质量（v1.3）

- 发布流水线加强：测试门禁从「≥100」升级为「≥200 且无失败无跳过」；新增 fat jar 自检五项
  （内嵌库完整 / 零泄漏 / 无 `libraries:` / 版本对得上 / 内嵌配置逐字节一致）；
  同日重跑改为**原位更新** Release（不再报错、不产生重复发布）。详见 ADVANCED §1。

---

## [v1.2.0] — 2026-10-25

本次为**玩法平衡性大版本**：护甲体系整体削弱 + 移除下界合金免疫，并新增「抗尘埃药」
作为唯一可主动获得辐射免疫的途径。

### 新增

#### 抗尘埃药

- **多方块合成结构**：`发射器/投掷器` + **正上方一格铁砧** + **同层 8 邻原木**。
  手持**木棍右键铁砧**即产出（木棍不消耗，只作「钥匙」）。
  这不是数据包配方，也不是工作台——判定的是世界里真实存在的方块。
- **合成界面 = 发射器自身容器**：右键发射器打开，圆环 8 格为摆放示意，
  **正中间（槽位 4）**放农作物。结构不完整时界面照样能开，只给诊断提示不扣东西。
- **四档药效，共 22 种农作物**（不同作物 → 不同档位）：

  | 档位 | 腐蚀减免 | 免疫时长 | 农作物 |
  |---|---|---|---|
  | 精制 `refined` | −50% | 30s | 金胡萝卜、紫颂果、发光浆果、附魔金苹果 |
  | 强效 `strong` | −35% | 20s | 下界疣、绯红菌、诡异菌、甜浆果 |
  | 改良 `improved` | −25% | 15s | 南瓜、西瓜片、甘蔗、可可豆、棕蘑菇、红蘑菇 |
  | 粗制 `crude` | −15% | 10s | 小麦、胡萝卜、马铃薯、甜菜根、海带、干海带、竹子、仙人掌 |

- **免疫窗口内**：**零辐射伤害**，且**腐蚀完全停止累积**（按「遮蔽」口径回落，
  不会出现「HUD 显示 ◆抗尘 但腐蚀条还在涨」），同时解除 `POISON`/`HUNGER`/`NAUSEA`/
  `WEAKNESS`/`SLOWNESS`。
- **每玩家 20 秒冷却**：冷却期内喝药会被拦下且**不消耗物品、不返还玻璃瓶**，
  actionbar 提示剩余时间。
- **死亡 / 退出登录立即结束免疫窗口**——不能靠「喝药 → 自杀 → 复活」带着免伤重生。
- `/rad dust list` 查看档位表；`/rad dust give <玩家> <档位> [数量]` 直接发放（OP）。
- 新增占位符 `%radsurvival_dust%` = 免疫剩余秒数（未免疫为 `0`）。

### 变更

#### 护甲全面削弱（温和档，仍有明确梯度）

| 材质 | 旧减免 | 新减免 | 旧满配存活 | 新满配存活 |
|---|---|---|---|---|
| 皮革 | 25% | **15%** | — | — |
| 金 / 锁链 | 50% | **30%** | — | — |
| 铁 | 75% | **48%** | — | — |
| 钻石 | 90% | **60%** | — | — |
| **下界合金** | **100%（完全免疫）** | **72%** | 永不死亡 | **≈56 秒** |

- 部位权重同步调整为 `头盔 0.12 / 胸甲 0.46 / 护腿 0.30 / 靴子 0.12`（权重和 = 1.0）。
- **「全套下界合金 = 完全免疫」已移除**：穿满下界合金依然会被太阳晒死，只是死得慢。
- 新增**最低杀伤保底** `armor.min-damage-fraction: 0.10`：任何减免都不能把伤害压到零。
  默认数值下碰不到这层兜底（最高 72%），它是防止「改配置改出免疫」的保险。
- 配置校验**硬拒**任何让 `权重和 × 材质系数 ≥ 1.0` 的配置，从源头堵死完全免疫。

### 修复

本次排查发现并修复了 9 个真实缺陷，其中 4 个是玩家可直接感知的：

- **背包满时可无限刷药**：旧的「先扣作物 → 放不进背包就把整组写回」逻辑，
  写回的是原始整组 → 作物没扣、药水照掉，等于白给。改为「先给后扣」，
  背包满时**不扣作物**，只有真正溢出的部分掉在脚下。
  （同时修掉了 `addItem` 部分成功时整瓶重复掉落造成的复制。）
- **免疫期间腐蚀照涨**：免疫判定原先放在腐蚀步进之后、只做提前 return，
  腐蚀照常上涨并落盘，与 HUD 上那段 `◆抗尘` 直接矛盾。改为把免疫翻译成
  「本轮视作遮蔽」传给结算内核，「零累积」现在是真的。
- **退出登录/死亡不清免疫**：玩家可「下线喝药、上线免伤」或「喝药自杀、复活免伤」。
- **一次右键出两瓶药**：主手/副手各派发一次事件导致重复合成，加 200ms 去重窗口。

其余修复：

- **`/rad reload` 后合成产物仍按旧配置生成**：GUI 持有了首次装配时的 item factory，
  改档位后「判定用新配置、产出用旧配置」。改为 reload 时换绑（`rebind`）。
- **铁砧交互被无条件霸占**：结构不完整也 `setCancelled(true)`，导致原版/其他插件
  无法用木棍点该铁砧；潜行时也会被吞。改为只在真正会产出时接管。
- **`anti_dust.enabled: false` 时发射器变成免费 9 格仓库**：监听器原先只靠「自查配置后
  return」失效，关闭后容器完全原版。改为真正从 `HandlerList` 摘除。
- **漏斗可从核心抽出作物**：原先只拦「灌入」方向，加工台可被自动化搬空。改为双向拦截。
- **GUI 槽位校验可绕过**：Shift+点击 / 数字键 / 双击收集三条路径能把非作物塞进中心格或外围格。
  改为按 `ClickType` 分流，对所有「落地到核心容器」的路径做最终意图校验。
- **配置项被静默忽略**：`armor.slot-weights` / `material-factors` 若被写成列表或裸标量，
  旧实现会静默套用默认值（「看起来改了其实没读进去」）。现在显式报错；缺键也会提示
  「将使用默认值」。

### 内部质量

- 测试 **188 → 265 条**（+77），全绿无跳过。新增 `DustImmunityTest`、
  `DustCraftStructureMockBukkitTest`、`DustItemFactoryMockBukkitTest`、
  `AntiDustChainMockBukkitTest`（走真实方块 + 真实 `PlayerItemConsumeEvent`）。
- 新增 `armor.slot-weights` 权重和 ≤ 1.0 校验。
- 文档：`README.md`（用户向）、`ADVANCED.md`（配置/API 参考）、`DEVELOPMENT.md`
  （架构与踩坑）、`ACCEPTANCE.md`（验收记录）全部同步 v1.2；包结构树补全
  `command/`、`feedback/`、`placeholder/`、`api/` 下的具体文件。
- 修正 `SurvivalBudgetRegressionTest#nightNaked` 的错误期望值（44s → 实际 37s，
  旧值靠 ±20% 容差蒙混通过），并把容差收紧到 ±10%。

### 升级注意（v1.1 → v1.2）

1. **护甲数值全部变化**：如果你改过 `armor.material-factors`，请重新确认——
   现在任何令 `权重和 × 系数 ≥ 1.0` 的配置会**直接拒绝启动**（以前允许 1.0 = 免疫）。
2. **`armor.slot-weights` 现在要求 4 个部位键齐全**：缺键会报错提示（不再静默用默认值），
   权重和必须 ≤ 1.0。
3. **玩家已持有的下界合金装备不再免疫**：上线后晒太阳会真的掉血，建议提前公告。
4. **新增 `anti_dust` 整节配置**：旧 `config.yml` 缺这一节时按默认值启用
   （`enabled: true`，4 档全开）。不想要就把 `anti_dust.enabled` 设为 `false`，
   此时对应监听器会被真正摘除，发射器退回原版行为。
5. 建议先跑 `/rad status` 确认 `armor≤72%`，再让玩家试穿一套下界合金验伤。

---

## [v1.1.0] — 2026-10-25（历史）

- 判定链路加固：修复四处真实缺陷（腐蚀持久化时机、无敌帧、双重减免、事件广播顺序）。
- 新增 `RadiationExposureEvent` 第三方可介入点、`CorrosionStageChangeEvent` 阶段广播。
- 建立自动化构建/发行流水线（`.github/workflows/build-release.yml`，测试数 ≥ 100 门禁）。
- 测试 166 → 188 条。
- 文档：README 拆分为用户向 `README.md` + 技术向 `ADVANCED.md`，新增 `DEVELOPMENT.md`、
  `ACCEPTANCE.md`。

---

## [v1.0.0] — 2026-10-25（历史）

首个可用版本：

- 露天判定（`SunExposureChecker`：段缓存 + 异步补扫 + 粘滞层）。
- 昼夜时间因子、腐蚀条累积/恢复、伤害倍率曲线（linear / sqrt / exp）。
- 盔甲部位加权减免、腐蚀阶段播报、HUD 双条（actionbar + BossBar）。
- 死亡文案、红闪抑制（可选 ProtocolLib）、PlaceholderAPI 扩展。
- 命令 `/rad status|toggle|reload|corrosion|help`。
