# RadSurvival —— 辐射生存 Paper 插件设计计划

> 目标平台：Paper **1.21.11**（`io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`），Java **21**
> ✅ 已核实（fill.papermc.io/v3，2026-10）：Paper 1.21.11 真实存在（build 34~132），最低 Java 21；该版本 Paper 已停止维护 → **API 已冻结，无漂移风险**，锁定 build 132 部署即可。注意后续 MC 已进入 26.x 新版本命名，未来升级需重验 `api-version` 写法（不在本期）。
> 交付物：单插件 jar，无 NMS 依赖（纯 Bukkit/Paper API 可实现），配置驱动，预留扩展 API

---

## 1. 核心玩法（本期实现）

| 规则 | 描述 |
|---|---|
| 日晒辐射伤害 | 玩家在**露天**（头顶可见天空）**白天**按 1s 间隔受持续伤害，基准半心/s，再被腐蚀条放大（§1.3/§1.6） |
| 夜晚减半 | 同一露天判定在**夜晚**成立时，伤害 ×0.5（设定解释：夜间仍有残留落尘辐射，强度减半） |
| 腐蚀条 | 在阳光下停留随时间累积腐蚀度（0~100%），**腐蚀度越高，受到的辐射伤害越高**（默认最高 ×2）；遮蔽于室内后缓慢回落 |
| 盔甲减免 | 穿戴盔甲按**部位权重 × 材质系数**减免伤害；全套下界合金 = 100% 免疫，皮革只能减免少量 |
| 遮蔽安全 | 头顶被方块遮挡（室内/树下密度足够遮挡）则不受伤害——鼓励"寻找掩体"的生存循环 |

### 1.1 露天判定（Sun Exposure）
- 从玩家眼睛所在方块逐格向上扫描至 `world.getMaxHeight()`，遇到**不透明方块**即判定为遮蔽（等价于天空可见性，比 `Location#canSee` 长射线更可控、可加缓存）。
- 仅对 `Environment.NORMAL` 世界生效（下界/末地无太阳，默认豁免，可配置）。
- 结果按 `(x, y, z)` chunk 段缓存，60s TTL，避免每 tick 扫描。

### 1.2 昼夜判定（Time Provider）
- 以 `world.getTime()` 判定：`0..12000` 白天、`12000..13000` 黄昏、`13000..23000` 夜晚、`23000..24000` 黎明。
- 黄昏/黎明按线性插值缩放伤害系数（白天 1.0 → 夜晚 0.5），避免瞬间跳变；配置可关闭插值改为硬切换。

### 1.3 伤害结算公式
```
最终伤害 = base × timeFactor × corrosionMult × (1 - armorReduction)
timeFactor：白天 1.0，夜晚 0.5（config.night-multiplier）
corrosionMult = 1 + (corrosion/100) × (maxMultiplier - 1)
    默认 maxMultiplier 2.0 → 腐蚀 0% ≈ ×1.0，100% ≈ ×2.0
base：默认 1.0（半心/s；调参依据见 §1.6 生存预算表，原版生命上限仅 20 点，基准过高会秒杀）
armorReduction ∈ [0,1]，达到 1.0 时完全免疫（不结算、不播提示音）
```
- **无敌帧约束（关键）**：玩家受击后有 20 tick 伤害免疫期，窗口内 `damage()` 会被**静默吞掉** → `tick-interval` 下限强制 20（PluginConfig 校验）；默认 20 恰好贴帧边界，M2 必须用测试断言"每轮掉血真的发生"，若有边界抖动改 24。
- **无双重减免**：`DamageCause.CUSTOM` 不会被原版护甲值/附甲二次减免，我们的公式是唯一减免路径，穿戴装备不叠加减伤（防 double-dipping）。
- **伤害标记主线（全稳定 API）**：`damage(amount, DamageCause.CUSTOM)` + 自有 `RadiationExposureEvent` 广播 + LOWEST 监听在事件 PDC 写 `radsurvival:source=radiation`；Paper `CustomDamageMeta` 属 @UnstableApi，仅作配置开关、默认关。
- **游戏模式过滤**：CREATIVE/SPECTATOR 无条件跳过；ADVENTURE 可配（`damage-game-modes`）。
- **"站在地面上"字面口径**：`require-on-ground: true` 时仅 `player.isOnGround()` 参与结算（跳跃/鞘翅空中不累积不掉血）；默认 false = 露天即算。

### 1.4 盔甲减免模型
- `减免 = Σ(部位权重 × 材质系数)`，空槽位计 0。
- 部位权重（默认）：头盔 0.20 / 胸甲 0.40 / 护腿 0.30 / 靴子 0.10。
- 材质系数（默认，config 可调）：

| 材质 | 系数 |
|---|---|
| 无 | 0.00 |
| 皮革 | 0.25 |
| 金 / 锁链 | 0.45 |
| 铁 | 0.70 |
| 钻石 | 0.85 |
| 下界合金 | 1.00 |

→ 全套下界合金恰好 100% 免疫；只穿下界合金胸甲减免 40%，符合"穿得越高级免疫越多"。

### 1.5 反馈表现（默认开启，可关）
- Action bar 实时显示双条：辐射等级条（无遮挡白天 ▲▲▲▲▲ / 夜晚 ▲▲ / 遮蔽 ●安全）+ **腐蚀条**（`腐蚀 67% ▓▓▓▓▓▓▓░░`，颜色随 warn/danger 阶段变黄/红）；检测到 ProtocolLib 时升级为包级 HUD 并抑制原版受伤红闪，缺失则纯 Paper actionbar 降级，也可配置 BossBar 常驻显示腐蚀条。
- 受击时轻微 `WIHN` 粒子 + 低功耗音效；可选叠加 `POISON`/`HUNGER` 效果（默认关闭，留给扩展）。

### 1.6 腐蚀条系统（Corrosion）
- 数值范围 `0~100%`，内部浮点，显示取整；是本期"辐射暴露的记忆体"——同样露天，待得越久越危险。
- **累积**（仅在露天判定成立时）：
  - 白天 +1%/s，夜晚 = 白天 × night-multiplier（默认 +0.5%/s），黎明/黄昏与 timeFactor 同步插值；
  - 盔甲同样作用于累积速率：`累积 × (1 - armorReduction)` → 全套下界合金既不累积也不掉血（= 免疫）；`armor-affects-accum: false` 可改为只减免伤害不减免累积。
- **恢复**：处于遮蔽状态或豁免维度（下界/末地）时 -1%/s；仅在线结算，离线冻结（重新上线从原值继续）。
- **对伤害的放大**：`corrosionMult = 1 + c/100 × (maxMultiplier - 1)`；`curve: linear | sqrt | exp` 预留非线性曲线（先高后猛/先缓后爆两种手感）。
- **阶段与提示**：`stages: {warn: 50, danger: 75}`，穿越阈值时 title+音效播报（"辐射腐蚀加重！"），首次到达 warn 阶段附一句规则说明，解决"莫名其妙掉血"；阶段接口预留负面效果挂点（行动迟缓、发光轮廓、辐射病 Debuff）。
- **持久化**：写入 Player PDC key `radsurvival:corrosion`（随 player.dat 自动落盘，重进服/重启不丢，本期无需数据库）；`reset-on-death: true` 默认死亡清零，可选 `decay-on-death: 0.5` 减半不清零。
- **管理命令**：`/rad corrosion get|set|add <player> [值]`（权限 `radsurvival.admin`）。
- **生存预算表（默认参数推导：base 1.0、累积 1%/s、×2 封顶；不含食物回血与药水）**：

  | 场景 | 预计死亡/等效时间 | 死亡时腐蚀约值 |
  |---|---|---|
  | 白天·裸装 | ~18s | ~18%（死于封顶前） |
  | 白天·皮革全 | ~24s | ~24% |
  | 白天·铁全 | ~53s | ~53% |
  | 白天·钻石全 | ~91s | ~91% |
  | 夜晚·裸装 | ~37s | ~18%（累积也减半） |
  | 夜晚·铁全 | ~105s | ~53% |
  | 夜晚·钻石全 | ~180s | ~90% |
  | 下界合金全 / 室内 | 免疫 / 腐蚀 100%→0 需 100s | — |

- **节奏验收**：白天露天裸装≈死刑（必须穿甲或快速进出），夜晚钻石勉强可活动，满饱和食物回血约再延 15~25% → "白天赶路办事、傍晚归洞府"的废土作息成立；100% 腐蚀上限实战只有重甲长曝或 admin `set` 可达（平时玩家停留在 0~90% 区间，符合"越晒越痛但不瞬间死"手感）。

---

## 2. 架构与包结构

```
com.ft.radsurvival
├── RadSurvivalPlugin.java        // onEnable/onDisable、服务装配、命令注册
├── config/PluginConfig.java      // 基于 Configurate 的 config.yml 加载/校验/reload，全部数值外置
├── core/
│   ├── RadiationTask.java        // 主循环：Bukkit 全局任务，每 tick 间隔遍历在线玩家
│   ├── SunExposureChecker.java   // 露天判定 + Caffeine 位置缓存
│   ├── DayNightProvider.java     // 昼夜因子计算（含插值）
│   ├── ArmorCalculator.java      // 盔甲减免计算
│   ├── CorrosionMeter.java       // 腐蚀条：累积/恢复/钳制/阶段跳变事件，PDC 持久化读写
│   └── DamageApplier.java        // 结算（含 corrosionMult 放大）与自定义伤害标记
├── feedback/FeedbackService.java // actionbar / 粒子 / 音效
├── listener/
│   ├── PlayerSessionListener.java// join/quit 初始化与清理 PlayerRadState
│   └── DamageTagListener.java    // 死亡日志文案定制（可选）
├── command/RadCommand.java       // /rad reload | /rad status | /rad force-check | /rad corrosion get|set|add
└── api/                          // 对外扩展点（见 §4）
    ├── RadiationExposureEvent.java   // Cancellable，结算前抛出（携带 corrosion 值，可改最终伤害）
    ├── CorrosionStageChangeEvent.java// 腐蚀阶段跳变（0→warn→danger），可拦截
    └── RadSurvivalApi.java           // 其他插件获取/覆写玩家辐射系数与腐蚀值的统一入口（读 PDC、写走 CorrosionMeter）
resources/
├── plugin.yml                    // api-version: '1.21'; libraries: Caffeine/Configurate; softdepend: ProtocolLib/PAPI/NBT-API/Vault
└── config.yml
```

- **线程模型**：扫描判定与缓存计算放在异步线程池（每 tick 提交、结果下 tick 消费）；**所有伤害/特效必须回主线程**。~~若嫌复杂，本期先主线程单任务（玩家数 <200 完全够用），异步化列入扩展。~~ **【M4 后已落地】**：露天扫描异步管线（双层缓存 + 粘滞值 + 段级在途去重，冷启动仍主线程保首轮精确；开关 `async-exposure-scan`，默认开），主线程稳态零方块扫描；伤害/事件/HUD/PDC 全部留主线程。见 ACCEPTANCE.md §H。
- **玩家状态**：`Map<UUID, PlayerRadState>`（是否露天、当前 factor、**腐蚀值 corrosion**、上次结算时间）；腐蚀值以 Player PDC 持久化（重进服/重启不丢，每秒结算时 `setDouble` 为内存写、随 player.dat 落盘，无 IO 压力），其余字段仅内存；伤害来源标记写在**事件** PDC 上，不占玩家数据。

## 3. 配置（config.yml 草案）

```yaml
enabled-worlds: [world]    # 世界白名单（与 dimensions 取交集）
dimensions: NORMAL         # 仅主世界有太阳；下界/末地默认豁免（豁免时腐蚀只降不升）
tick-interval: 20          # 伤害结算间隔（tick）；PluginConfig 强制下限 20 —— 原版无敌帧 20tick，低于此值伤害会被吞
base-damage: 1.0           # 白天露天基准伤害（半心/s；MC 生命上限仅 20，生存预算见 §1.6）
damage-game-modes: [SURVIVAL, ADVENTURE]   # 创造/旁观无条件免疫
require-on-ground: false   # true = 仅"站在地面上"才结算（需求字面口径），默认露天即算
night-multiplier: 0.5
smooth-transition: true    # 黎明/黄昏线性插值
armor:
  slot-weights: {helmet: 0.20, chestplate: 0.40, leggings: 0.30, boots: 0.10}
  material-factors: {LEATHER: 0.25, GOLD: 0.45, CHAINMAIL: 0.45, IRON: 0.70, DIAMOND: 0.85, NETHERITE: 1.00}
corrosion:
  enabled: true
  day-accum-per-sec: 1.0    # 白天露天累积 %/s（夜晚自动 × night-multiplier）
  regen-per-sec: 1.0        # 遮蔽/豁免维度时恢复 %/s（仅在线生效）
  armor-affects-accum: true # 盔甲减免同时作用于累积速率（下界合金=不累积）
  max-multiplier: 2.0       # 腐蚀 100% 时的伤害倍率上限
  curve: linear             # linear | sqrt | exp（非线性预留）
  reset-on-death: true      # 与 decay-on-death 二选一（decay-on-death: 0.5 = 死亡减半）
  stages: {warn: 50, danger: 75}
feedback:
  actionbar: true
  particles: true
  sound: true
commands:
  reload-permission: radsurvival.reload
```

权限：`radsurvival.use`（默认 true，豁免可配 `radsurvival.exempt`）、`radsurvival.admin`。

配置校验（M1 即实现，非法值启动即报错拒加载）：`tick-interval ≥ 20`（无敌帧下限）、`reset-on-death` 与 `decay-on-death` 互斥、权重/系数/倍率 ∈ [0,1]（maxMultiplier ≥1）、材质枚举合法、世界列表非空。

## 4. 预留扩展空间（本期不实现，仅留接口/数据结构）

1. **辐射值深化系统**：腐蚀条已实现"累积 + 放大伤害 + PDC 持久化"第一阶段；后续升级：累积到阈值才扣血的"辐射病"模式、SQLite/MySQL 跨服共享持久化、腐蚀高阶段长期负面状态链。
2. **防护装备体系**：`ArmorCalculator` 接口化，防化服（自定义物品/装备插件 Vault 式查询）、手持伞/斗篷等"非盔甲减免源"可注册为 `ProtectionProvider`。
3. **药物与治疗**：抗辐宁 / 血清 = 调用 `CorrosionMeter#add(-n)` 快速清除腐蚀条；命令与物品模板占位，与 §4.2 防护体系共用 `ProtectionProvider`。
4. **天气与核爆事件**：降雨冲刷降低地表残留、随机"落尘事件"期间夜晚伤害也不减半——`DayNightProvider` 抽象成 `HazardLevelProvider` 即可挂接。
5. **生物群系系数**：废土/沙化群系伤害加成（`SunExposureChecker` 输出增加 biome 因子位）。
6. **夜出玩法正向增益**：夜晚不仅减伤，还可给予速度/夜视奖励（夜间探索更安全 → 玩法核心矛盾已立住）。
7. **PlaceholderAPI / 其他插件联动**：`%radsurvival_level%`、`%radsurvival_corrosion%` 等；`softdepend` 预留。
8. **API 事件**：`RadiationExposureEvent`（cancellable + 可改伤害）本期就建好，方便后续服务端玩法包接入。
9. **服务端品牌自适应 Profile（§7，v1.4 已实施识别+防御）**：识别运行时服务端分支（Paper/Leaf/Purpur/Folia/Spigot…），按品牌应用调优预设，三层优先级"用户显式值 > 品牌 profile > 内置默认"。详见 §7。

## 7. 服务端品牌自适应 Profile（v1.4 已实施「识别 + 防御」，调参部分缓建）

> 背景：M4 后插件主线程稳态已近零开销（§H 异步管线），但为后续重型功能（怪物辐射、落尘事件、
> 群系系数）预留"按服务端分支调优"的框架位；同时提供 Folia/Spigot 的**防御性识别**。
>
> **✅ 实施状态（v1.4.0）**：§7.1 识别与 §7.4 防御分支**已实施**（`core/ServerBrand`
> + `RadSurvivalPlugin#guardServerBrand`）；§7.3 三层优先级与 `profiles.<brand>` 预设段
> **有意缓建**，理由见 §7.6。

### 7.1 识别方式

```java
String brand = server.getName();     // "Paper" / "Leaf" / "Purpur" / "Spigot" / "Folia"
String full  = server.getVersion();  // 含构建号
// 兜底：类存在性探测（如 Folia 的 RegionizedServer、Paper 独有包），避免 getName 被改
```
归一化为枚举：`paper | leaf | purpur | spigot | folia | unknown`。

> **实施修正**：计划里把类探测写成「兜底」，实测口径相反——`getName()` 只是可被分支服务端
> 随意改写的字符串（Leaf/Purpur 都返回自己的名字），无法证明「Paper 专属 API 真的存在」；
> 类存在性是编译期事实。因此实际实现**类探测优先**：
> ①`RegionizedServer` → Folia；②名字唯一命中 Purpur/Leaf；③`DamageSource` 可加载 → Paper；
> ④名字含 spigot 且探测阴性 → Spigot；⑤兜底 unknown。
> 其中 ③ 之所以成立，是因为 `DamageSource` 正是本插件自己编译依赖的类——
> 「能加载它」与「本插件的字节码能跑」在实践中等价。

### 7.2 能调什么（重要边界）

| 维度 | 随品牌变化？ | 处理 |
|---|---|---|
| Bukkit/Paper API 行为 | ❌ | Leaf/Purpur 均为 Paper 分支，API 同面；判定/事件/伤害管线不分支化 |
| MC 机制参数（无敌帧 20tick、GENERIC 不吃护甲、昼夜时刻表） | ❌ | 协议/客户端层事实，**禁止**按品牌"调优"（负优化） |
| 主线程压力特征（实体/区块/红石） | ✅ | profile 只允许调**保守旋钮**：`async-exposure-scan`、缓存 TTL、`tick-interval`、HUD 频率、BossBar 默认值 |
| Folia（区域化线程） | ⚠️ 模型不同 | 本期识别后 WARNING + 自我禁用（不做区域化改造）；全局任务在其上直接抛异常 |

### 7.3 三层优先级（防"自动改值吓到管理员"）

> ⚠️ **本节未实施**（v1.4 缓建，见 §7.6）。以下为原始设计，保留供后续引入时参考。

```
用户在 config.yml 显式写的键  >  品牌 profile 预设  >  内置默认值
```
- Configurate 节点 presence 检测：仅对"用户未写"的键填入 profile 值；
  （可行性已核实：`PluginConfig` 本就基于 `YamlConfigurationLoader`，该能力现成）
- config.yml 新增可选段：`adaptive-profile: auto|off|generic|paper|leaf` + `profiles.<brand>: {键: 值}`（管理员可覆写任何预设）；
- 透明化：启用日志打印"检测到 X → profile=Y，预设生效键列表/用户覆写键列表"；`/rad status` 增 profile 与来源展示。

### 7.4 防御分支

- **Spigot**：插件以 paper-api 编译（`DamageSource`、`Component` 等为 Paper 专属），NoClassDefFoundError 时 try/catch 并提示"需要 Paper 系服务端"；
  → 实施为：在 `onEnable` **最前面**用类探测先给出确定结论再 `disablePlugin`，不依赖异常兜底
  （异常抛出点常在类初始化期间，从堆栈看不出「换服务端」才是解法）。
- **Folia**：WARNING + 禁用（见 7.2）；
  → 实施为：同样在 `onEnable` 最前面拒绝。注意 Folia 的 `RegionizedServer` 使其**同时**
  满足「能加载 Paper 类」，所以 Folia 判定必须排在 Paper 之前。
- **unknown**：按 `generic` 跑（=现默认值）+ 日志提示可手动指定——**绝不对未实测分支启用激进预设**。

### 7.5 实施边界与验收

- ~~初期只内置 `generic`（=现值）与 `paper`（=现值）；`leaf` 槽位留空壳~~
  → 实际实施时不引入任何预设，因此 `leaf` 无需空壳；
- 每个 profile 视为一次"支持矩阵"声明：须按 §5-M4 同款清单回归后才能写入非默认预设；
- ~~预估规模：检测器 + presence 优先级 + 配置段 + status/日志展示 + 测试 ≈ 1 个子代理任务~~
  → 实际落地：`core/ServerBrand`（约 200 行，纯 Java）+ `guardServerBrand`（约 30 行）
  + 启动日志/status 各一行 + 14 条测试，**未触碰核心判定数学**，既有 315 条语义不变。

### 7.6 为什么缓建「按品牌调参」

§7.2 的边界表里，唯一随品牌变化的维度是**主线程压力特征**，而 M2 后主线程稳态已近零开销
（露天补扫走异步池）。在没有任何跨服务端实测数据的前提下，按品牌改
`tick-interval` / 缓存 TTL / HUD 频率有两重问题：

1. **收益为负**：这些旋钮的值域受玩家可感知体验约束（`tick-interval` 改小会撞无敌帧，
   改大会让伤害变「跳」），可调空间本就极窄；
2. **可解释性变差**：同一份 config.yml 在不同服务端生效值不同，而 `profiles.<brand>`
   又允许管理员覆写，最终「这个服到底跑的是哪组数」需要三层叠加才能算出来——
   与 §7.3 自己提出的「防自动改值吓到管理员」初衷相悖。

**恢复条件**：拿到真实跨服务端实测差异（例如某分支上异步补扫显著更慢、或某分支默认
view-distance 导致缓存命中率骤降），再引入**只含 1~2 个键**的最小配置段，不做通用预设框架。

## 5. 构建、第三方依赖与里程碑

### 5.1 构建
- Maven + `paper-api` 仓库 `https://repo.papermc.io/repository/maven-public/`；Java 21 toolchain。
- **允许引入第三方 API 库**，采用两条通道：
  - **纯 Java 库**（Caffeine、Configurate 等）→ 优先走 Paper `plugin.yml` 的 `libraries:` 字段启动时自动下载，不打 fat jar，避免类冲突；仅当库不在可用仓库时改用 shade + relocate。
  - **插件型 API**（ProtocolLib、PlaceholderAPI、NBT-API、Vault）→ `compileOnly` + `softdepend`，功能全部带 Paper 原生降级路径，缺失时核心玩法不受影响。

### 5.2 第三方依赖清单

| 库 | 坐标 | 用途 | 引入方式 |
|---|---|---|---|
| Caffeine | `com.github.ben-manes.caffeine:caffeine` | 露天判定/位置缓存：TTL + 权重淘汰，替代手写 Map+过期逻辑 | `libraries:` 运行时 |
| Configurate | `org.spongepowered:configurate-yaml` | 类型安全配置加载、注释保留、reload；替代 Bukkit `YamlConfiguration` | `libraries:` 运行时 |
| ProtocolLib | `com.comphenix.protocol:ProtocolLib` | 高级辐射 HUD（action bar/boss 栏包级控制）、隐藏原版受伤红闪 | `compileOnly` + `softdepend`，缺省用 Paper 原生 actionbar |
| PlaceholderAPI | `me.clip:placeholderapi` | `%radsurvival_exposure%` / `%radsurvival_reduction%` / `%radsurvival_timemul%` / `%radsurvival_corrosion%`，配套 expansion 类本期即建好 | `compileOnly` + `softdepend` |
| NBT-API (ItemNBT) | `de.tr7zw:item-nbt-api` | 识别防化服、抗辐宁等自定义物品（供 `ProtectionProvider` 查询自定义 tag） | `compileOnly` + `softdepend` |
| Vault | `net.milkbowl.vault:VaultAPI` | 预留：抗辐药物商店/货币挂钩（本期只接线不实现） | `compileOnly` + `softdepend` |
| MockBukkit | `com.github.seeseemelk:MockBukkit-v1.21` | 监听器与主循环（RadTask tick 推进）单元测试；若其版本滞后于 Paper 1.21.11，该类测试自动跳过（四模块已与 Bukkit 解耦，纯逻辑测试不受影响） | `test` scope |
| JUnit 5 + AssertJ | `org.junit.jupiter` / `org.assertj:assertj-core` | `ArmorCalculator`/`DayNightProvider` 等纯逻辑测试 | `test` scope |

> 注：伤害标记主线是 Paper **稳定 API**（`DamageCause.CUSTOM` + 事件 PDC），**不依赖**任何第三方即可成立；`CustomDamageMeta`（@UnstableApi）只是可选开关；上表软依赖插件仅增强表现与联动。

- **M1**：工程骨架 + Maven（`libraries:`/`softdepend` 接线、shade 判定）+ Configurate 配置 + plugin.yml，`/rad status` 打印判定结果。
- **M2**：露天/昼夜/盔甲/腐蚀条四模块 + 主循环伤害结算（Caffeine 缓存、腐蚀 PDC 读写；JUnit5+AssertJ 纯逻辑测试，MockBukkit 跑监听器与 tick 推进）。
- **M3**：反馈表现（辐射条 + 腐蚀条 HUD、阶段跳变播报）+ 事件 API + 死亡文案。
- **M4**：实机验收（白天露天裸装初始伤害半心/s、夜晚 1/4 心/s；`/rad corrosion set 100` 后伤害 ×2 = 1 心/s；全皮革 ~25% 减免、全下界合金零伤害零累积、室内零伤害且 1%/s 回腐蚀；跨维度豁免；创造/旁观跳过；**重进服/重启腐蚀值保留**；warn/danger 播报触发；裸装/铁/钻石实测存活时间对照 §1.6 预算表 ±20% 内；连续 10 轮结算每轮都实际掉血——无敌帧断言）。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| 露天扫描开销 | 逐格扫描提前终止（不透明即停）+ chunk 顶面缓存；后续可换 `getLight` 方案压测 |
| 高频 `damage()` 与其他插件伤害监听冲突 | 自定义 PDC 标记 + 独立 Event，文档要求其他插件识别 `radsurvival:source` |
| 1.21.x 版本 API 差异（damage 重载、PDC key） | 以 1.21.11 paper-api 编译；该版本 Paper 已 EOL、build 停在 132，API 冻结不会漂移；主线只用稳定 API，@UnstableApi 全部关在开关后 |
| 玩家被"关在室内仍受伤"误判 | 判定用逐格方块扫描而非光照等级，透明方块（玻璃）默认算遮蔽=安全，配置 `transparent-blocks-count-as-cover` 可改 |
| 第三方插件更新滞后于 MC 大版本（ProtocolLib/PAPI 等） | 全部软依赖功能带 Paper 原生降级路径；核心伤害循环不依赖任何插件型软依赖，缺失仅失去 HUD 增强与占位符 |
| `libraries:` 启动下载受仓库限制 | Caffeine/Configurate 均在 Maven Central；若私服离线部署，则回退 shade+relocate（构建 profile 一键切换） |
| 玩家不理解腐蚀机制，感觉"莫名掉血越来越痛" | 双条 HUD 常驻 + 首次 warn/danger 阈值 title 播报规则说明；倍率数值全部走配置可现场调平 |
| 腐蚀 PDC 被其他插件乱写 | key 带 `radsurvival:` 命名空间；读写收敛在 `CorrosionMeter`，外部一律经 `RadSurvivalApi` setter（走事件广播，可拦截） |
| 无敌帧吞伤害：`damage()` 落在 20tick 免疫窗口内被静默无效，表现为"腐蚀在涨但血不掉" | `tick-interval` 校验下限 20；M2/M4 用断言逐轮核对血量实际下降，边界抖动则默认值改 24 |
| 数值失衡（MC 生命仅 20 点，秒伤基准极易做成秒杀） | 基准定为半心/s 并按 §1.6 预算表验证自洽；任何改 base/累积/倍率 的配置变更须同步更新预算表数值，作为回归断言 |

---
**实施状态（2026-10-25）**：M1–M4 全部完成并实机验收（**172 条测试全绿**；Paper 1.21.11-132 实机 ENABLED，
详见 ACCEPTANCE.md 与 README）。实施期对本文的三处已核实修正（原文保留于 README 勘误节）：
① 1.21.11 已移除 `damage(double, DamageCause)` → 主线改用 `damage(amount, DamageSource) + DamageType.GENERIC`
（实测 GENERIC 不被原版护甲二次减免，§1.3 前提成立）；② `libraries:` 通道在实机环境因 Central 镜像不可达拒载 →
默认构建改为 **shade + relocate** 自包含 fat jar，`-Plibraries-channel` 保留原通道；③ §1.6 预算表受甲行
按「累积恒 1%/s」推导，与默认 `armor-affects-accum: true`（M4「下界合金零累积」硬项所系）存在系统性偏差，
受甲场景以重导值对照（裸装两口径等价）。

**2026-10-25 增量**：命令层重构（子命令/别名/权限收敛到 `SubCommand`，新增 `help`、`reload --dry-run`、
错误纠错建议、按显示宽度对齐的输出排版）与控制台日志降噪（启动 3 行摘要，与 `/rad status` 同源）；
FATAL 级考量后**不采用构建混淆**（ProGuard 在 Java 21 + 内嵌 Caffeine 组合下存在运行期
`VerifyError` 与阶段竞态，收益不抵风险，详见 DEVELOPMENT.md §10.1）。二次开发请以
[DEVELOPMENT.md](DEVELOPMENT.md) 为准。
**2026-10-27 增量（v1.3）**：露天判定稳定性修复——缓存键去掉 y 维度，改为按**方块列**
`(world, sectionX, sectionZ)` 缓存，扫描起点改为整列底部（`columnScanStartY()`）。本文 §1.1
原案「按 `(x, y, z)` chunk 段缓存」的写法**已被实现修正**：顶遮挡是整列属性，与眼高无关，
把 y 段编进键会让同一列被不同高度段各算一次、结果互相覆盖（判定抖动），且蹲起跨段界即重扫。
玩法数值与其余设计口径全部未变。测试 265 → 268，详见 CHANGELOG v1.3 与 DEVELOPMENT §3.1 / §10.6。

**2026-10-31 增量（v1.4）**：§7 落地「识别 + 防御」——`core/ServerBrand` 归一化
`paper/leaf/purpur/spigot/folia/unknown` 并记录识别来源，`onEnable` 首步据其拒绝
Spigot / Folia；**§7.3 三层优先级与 `profiles.<brand>` 预设段有意缓建**（理由见 §7.6）。
玩法数值、判定口径、配置键全部未变。测试 315 → 332，详见 CHANGELOG v1.4 与
DEVELOPMENT §10.8；跨分支实测结论见 ACCEPTANCE §M，操作手册见 [PLATFORMS.md](PLATFORMS.md)。

> **实测修正了计划里的一处设计假设**：原定「类探测作兜底、`getName()` 为主」，
> 实测证明应反过来（`getName()` 可被分支服务端改写，类存在性才是编译期事实）；
> 但同时发现 Leaf 上 paperclip 重映射导致类探测不可用，名字匹配仍是必需的。
> **Folia 的拒绝还需 `plugin.yml` 声明 `folia-supported: true` 才能生效**——否则服务端
> 在插件加载阶段就拒了，`onEnable` 一行都跑不到，防御代码成为死代码。见 DEVELOPMENT §10.8.1。

**下一步（候选）**：§4.1 辐射病模式 → §4.2 防护装备体系 →（有条件）§7.3 最小预设段
（需先拿到跨服务端实测差异，见 §7.6）。
