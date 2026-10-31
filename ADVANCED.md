# RadSurvival 高级文档

> 面向**服主进阶配置 / 插件作者 / 二次开发者**。
> 只想快速装上玩起来？看 [README.md](README.md)。
>
> 相关文档：[plan.md](plan.md)（设计规格与决策记录）· [ACCEPTANCE.md](ACCEPTANCE.md)（实机验收证据）·
> [DEVELOPMENT.md](DEVELOPMENT.md)（包结构 / 扩展点 / 测试 / 踩坑）

---

## 目录

1. [下载与自动发行](#1-下载与自动发行)
2. [部署与构建](#2-部署与构建)
3. [完整配置参考](#3-完整配置参考)
4. [命令与权限全表](#4-命令与权限全表)
5. [结算链路与判定语义](#5-结算链路与判定语义)
6. [数值口径与勘误](#6-数值口径与勘误)
7. [对外 API 与事件](#7-对外-api-与事件)
8. [露天判定异步化](#8-露天判定异步化)
9. [判定链路强化记录](#9-判定链路强化记录)
10. [构建混淆：已评估，不采用](#10-构建混淆已评估不采用)
11. [MockBukkit 测试接线结论](#11-mockbukkit-测试接线结论)
12. [已知限制与排错](#12-已知限制与排错)
    - [12.1 服务端支持范围（v1.4 新增）](#121-服务端支持范围v14-新增)
13. [里程碑状态](#13-里程碑状态)

> **换服务端遇到问题？** 先看 [PLATFORMS.md](PLATFORMS.md)：那里有各分支的
> 支持状态、期望日志、以及「装不上时按什么顺序排查」。

---

## 1. 下载与自动发行

每次推送到 `main`（或手动触发）都会自动构建、跑全量测试并发布 Release：

**[→ 下载最新构建](https://github.com/xgp2012/RadSurvival/releases/latest)**

产物命名：**`RadSurvival-<年>-<月>-<日>-<当天第几次构建>.jar`**

| 示例 | 含义 |
|---|---|
| `RadSurvival-2026-10-25-1.jar` | 2026-10-25 当天第 1 次构建 |
| `RadSurvival-2026-10-25-3.jar` | 2026-10-25 当天第 3 次构建 |

- 日期按 **Asia/Shanghai (UTC+8)** 计算。Runner 是 UTC，若直接 `date` 会跨日错位，
  故把 run 的 `created_at` 用 `jq` 换算到 +08:00 再分桶。
- 序号取「当天该分支**已成功完成**的构建数 + 1」，用已完成而非已触发，
  避免并发排队时两个 run 抢到同一个号。
- `plugin.yml` 的 `version` 与文件名一致（`-Drevision=` 注入），游戏内 `/version RadSurvival` 可直接对上。
- 每次发布附带 `.jar.sha256`，下载后可 `sha256sum -c` 校验完整性。
- **发布门禁（v1.3 起加强）**，任一不满足即中止、不发坏包：
  1. 全量测试通过，且**总数 ≥ 200、Failures/Errors/Skipped 全 0**——防配置漂移导致
     Surefire 静默跳过或测试类整批消失；实测总数会写进 Release 说明，不是一句「全绿」空话；
  2. **fat jar 自检五项**（自动执行 DEVELOPMENT §9 发布检查单第 2~4 项）：内嵌 relocated 条目
     ≥ 1200、`org/spongepowered`/`com/github/benmanes` 零泄漏、`plugin.yml` 无 `libraries:`
     （防 thin 通道混入）、`plugin.yml` 的 `version` == 构建号、jar 内 `config.yml` 与仓库逐字节一致。
  PR 也全程执行这两关——问题在评审时暴露，而不是合并后发布失败。
- **同日重跑幂等**：产物名（日期+序号）即 Release tag；重跑时 tag 已存在则**原位更新**说明与
  产物（`gh release upload --clobber`），不会失败、也不会产生第二个发布。序号统计若因 API
  抖动失败会告警并回退「当天第 1 次」，由幂等发布兜住。

工作流定义见 [`.github/workflows/build-release.yml`](.github/workflows/build-release.yml)。
触发：push/PR 到 `main`（PR 只构建不发布）+ `workflow_dispatch` 手动。

---

## 2. 部署与构建

### 2.1 部署

1. 将 jar 放入 Paper 服务器 `plugins/` 目录；
2. 默认 fat jar 为自包含：**无需服务器联网**；仅 `-Plibraries-channel` 构建需要能访问 Paper 的 Maven Central 镜像；
3. 启动服务器，插件自动生成默认 `plugins/RadSurvival/config.yml`；
4. 配置非法时插件会记录 SEVERE 错误明细并**自我禁用**（拒绝带病服务）。

### 2.2 本地构建

要求：JDK 21（本项目验证环境为 `D:\java21`，Azul Zulu 21.0.12.1）+ Maven 3.10+。

```powershell
$env:JAVA_HOME='D:\java21'   # 若系统 JAVA_HOME 已指向 21 可跳过
mvn -e -B clean package                            # 默认：自包含 fat jar（推荐用于生产）
mvn -e -B clean package -Plibraries-channel        # 可选：thin jar + plugin.yml libraries: 运行期下载
mvn -e -B clean package -Drevision=2026-10-25-3    # 让 plugin.yml 版本号带上构建号（CI 用此法）
mvn -o -B clean package                            # 离线构建（依赖已缓存时）
```

产物：`target/RadSurvival.jar`（默认约 1.8 MB fat jar；Caffeine/Configurate/Geantyref/Kyori-Option
已重定位到 `com.ft.radsurvival.lib.*` 内嵌）。

> **通道选择（plan.md §5.1 / §6）**：`libraries:` 运行期下载在实机环境曾因 Paper 使用的
> `maven-central.storage-download.googleapis.com` 被重置而 **`Error resolving libraries` 直接拒载**
> （ACCEPTANCE.md §B.2 有日志原文），故默认改为 shade+relocate；能直连该镜像的服务器可用
> `-Plibraries-channel` 产 thin jar（111 KB），两者 plugin.yml 由构建 profile 自动切换。

> **构建环境提示**：远程仓库偶发 socket 挂起（与代码无关，与 ProtocolLib/PAPI 同源问题）。
> 依赖已齐时用 `mvn -o -B clean package` 离线构建（332 条测试离线全绿）。

> **依赖仓库实况（2026-10-18 实测）**：extendedclip→PAPI 2.12.3 pom/jar 可拉（极慢，~5–13KB/s）；
> dmulloy2→ProtocolLib 5.3.0 pom/元数据可拉但 jar 通道挂死——本地 `.m2` 已用**官方 GitHub Release
> 同版本产物 install-file 补装**（plugin.yml 内嵌版本核验 5.3.0），pom 坐标与仓库配置保持官方不变；
> 他人环境构建若 dmulloy2 正常则直接在线解析。CI 上该仓库返回 403 的元数据警告可忽略。

### 2.3 测试

当前测试规模：**332 条全绿**（M1 校验 20 + M2a 纯内核 61 + M2b MockBukkit 集成 48 +
M3 反馈层 38 + M4 判定链路强化 3 + 露天判定异步化 3 + 命令层优化 4 + v1.1 判定链路加固 11 +
v1.2 抗尘埃药与护甲削弱 43 + v1.3 露天判定稳定性 3 + **v1.3.1 全量缺陷排查 42** +
**v1.3.2 负 Y 露天判定 5** + **v1.4.0 品牌识别与防御 14 + 隔离类加载 3**；
`com.ft.radsurvival.mockbukkit` 与 `com.ft.radsurvival.listener` 包），无跳过项。

> ⚠️ **跑测试必须用 JDK 21**。若系统默认 JDK 是 25，`mockbukkit` 包的 127 条用例会全体报
> `NullPointerException: No jar file selected`——这是 MockBukkit 的类加载与 JDK 25 反射封装
> 不兼容，**与插件代码无关**。指定方式：`$env:JAVA_HOME='D:\java21'` 后再 `mvn`。

按包实测分布（`mvn -B clean package` 的 Surefire 汇总）：

| 包 | 条数 |
|---|---|
| `mockbukkit`（集成，走生产代码路径） | 141 |
| `core`（纯内核） | 99 |
| `config`（纯 Java 校验） | 60 |
| `command`（渲染与路由） | 13 |
| `feedback`（纯字符串渲染） | 11 |
| `listener`（伤害通道归因） | 8 |
| **合计** | **332** |

v1.3 新增（露天判定稳定性，均做过反向验证）：

| 类 · 方法 | 钉住什么 |
|---|---|
| `core/SunExposureCheckerTest#verticalMovementDoesNotRescanOrFlip` | 同一方块列内上下移动（跨 16 格段界）不重扫、判定不翻转 |
| `core/SunExposureCheckerTest#columnScanCoversBelowEyeHeight` | 扫描必须覆盖整列：高眼位写入的顶遮挡不得让低眼位误判遮蔽 |
| `core/SunExposureCheckerTest#cacheEntriesAreColumnScoped` | 缓存条目按方块列计，同一列任意眼高只占 1 条 |

v1.3.2 新增（负 Y 露天判定，实机验收驱动）：

| 类 · 方法 | 钉住什么 |
|---|---|
| `core/NegativeYExposureRegressionTest#exposedAtNegativeY` | 站在 y=-60、头顶全空、脚下实心 → **必须判露天**（修复前误判遮蔽） |
| `core/NegativeYExposureRegressionTest#coveredAtNegativeY` | 负 Y 上空有实心顶 → 仍必须判遮蔽（防修过头） |
| `core/NegativeYExposureRegressionTest#exposedAtWorldBottom` | 世界最低层 y=-63 露天也必须判露天 |
| `core/NegativeYExposureRegressionTest#exposedAtPositiveY` | 正 Y 区间口径不受影响 |
| `core/NegativeYExposureRegressionTest#scanStartFollowsWorldMinHeight` | 扫描起点契约：`columnScanStartY(min) == min - 1` |

> 反向验证：把 `columnScanStartY` 改回返回 `-1`，前 3 条立即变红、正 Y 对照仍绿。

v1.2 新增的四个测试类：

| 类 | 条数 | 钉住什么 |
|---|---|---|
| `core/DustImmunityTest` | 8 | 免疫窗口边界（左开右闭）、重复服用取较长、clear 语义、惰性清理 |
| `mockbukkit/DustCraftStructureMockBukkitTest` | 12 | 结构判定（铁砧/铁块/发射器）、Tag、槽位映射、诊断信息 |
| `mockbukkit/DustItemFactoryMockBukkitTest` | 8 | PDC 往返、`CONSUMABLE` 存在且 `consumeEffects` 为空、堆叠 8、档位失效 |
| `mockbukkit/AntiDustChainMockBukkitTest` | 24 | **端到端**：搭真方块结构（三格竖列）→ 放作物 → 木棍敲铁砧 → 产出 → 服用 → 免疫生效 → 冷却拦截 |

```powershell
mvn -B test -Dtest=RadCommandMockBukkitTest    # 单类
mvn -B test -Dtest=A,B                         # 多类用逗号（不能用 +）
```

---

## 3. 完整配置参考

`plugins/RadSurvival/config.yml`。**所有键在启动时全量校验**：非法值报错明细并拒绝启用；
`/rad reload` 时非法则保留旧配置运行。

### 3.1 顶层

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled-worlds` | `[world]` | 世界白名单，与 `dimensions` 取交集，**均满足**才结算。不可为空 |
| `dimensions` | `NORMAL` | 生效维度：`NORMAL`/`NETHER`/`THE_END`。仅主世界有太阳，下界/末地默认豁免；豁免时腐蚀只降不升 |
| `tick-interval` | `20` | 结算间隔（tick）。**校验下限 20**：原版受击后 20 tick 无敌帧，低于此值会出现「腐蚀在涨但血不掉」 |
| `base-damage` | `1.0` | 白天露天基准伤害（半心/s；生命上限仅 20 点，基准过高会秒杀） |
| `damage-game-modes` | `[SURVIVAL, ADVENTURE]` | 参与结算的游戏模式；创造/旁观无条件免疫 |
| `require-on-ground` | `false` | `true` = 仅 `isOnGround()` 才结算（需求字面口径）；默认露天即算 |
| `night-multiplier` | `0.5` | 夜晚系数，校验 ∈ [0,1]；累积速率同倍率减半 |
| `smooth-transition` | `true` | 黎明/黄昏按线性插值缩放 `timeFactor`，避免瞬间跳变 |
| `transparent-blocks-count-as-cover` | `true` | 玻璃等透明方块算遮蔽（玻璃顶=安全） |
| `async-exposure-scan` | `true` | 露天扫描异步化，详见 [§8](#8-露天判定异步化) |

### 3.2 `armor` — 盔甲减免

减免率 = `Σ(部位权重 × 材质系数)`，空槽计 0；随后叠加**最低杀伤保底**（见下）。

| 键 | 默认 |
|---|---|
| `armor.slot-weights` | `{helmet: 0.12, chestplate: 0.46, leggings: 0.30, boots: 0.12}` |
| `armor.material-factors` | `{LEATHER: 0.15, GOLD: 0.30, CHAINMAIL: 0.30, IRON: 0.48, DIAMOND: 0.60, NETHERITE: 0.72}` |
| `armor.min-damage-fraction` | `0.10` |

各值校验 ∈ [0,1]；材质键必须在上述集合内。

**v1.2 硬约束：禁止任何材质达成完全免疫。** 启动校验会逐材质算出「部位权重和 × 材质系数」，
任一 `≥ 1.0` 即**拒绝加载**并给出该材质的实际乘积。旧的 `NETHERITE: 1.00` 配置现在会直接报错。
允许 `armor-affects-accum: true` 与新的系数共存——72% 减免仍留 28% 累积速率，不存在零累积角落。

**最低杀伤保底** `min-damage-fraction` ∈ `(0,1]`：单轮伤害不得低于
`base × timeFactor × 腐蚀倍率 × min-damage-fraction`。即使把系数配到接近 1.0，
阳光也永远保有这一份杀伤力（默认 10%）。

满配对照（权重和 = 1.00）：

| 材质 | 满配减免 | 满配受击 |
|---|---|---|
| 皮革 | 15% | 85% |
| 金 / 锁链 | 30% | 70% |
| 铁 | 48% | 52% |
| 钻石 | 60% | 40% |
| 下界合金 | 72% | 28% |

### 3.3 `corrosion` — 腐蚀条

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关闭则伤害不随暴露时长放大 |
| `day-accum-per-sec` | `1.0` | 白天露天累积 %/s（夜晚自动 × `night-multiplier`） |
| `regen-per-sec` | `1.0` | 遮蔽/豁免维度恢复 %/s（仅在线生效，离线冻结） |
| `armor-affects-accum` | `true` | 盔甲减免是否同时作用于累积速率。`true` = 减免越高累积越慢（v1.2 后最高 72%，仍会累积） |
| `max-multiplier` | `2.0` | 腐蚀 100% 时倍率上限，校验 ≥ 1（0% ≈ ×1.0） |
| `curve` | `linear` | 放大曲线：`linear` \| `sqrt` \| `exp` |
| `reset-on-death` | `true` | 死亡清零；与 `decay-on-death` 互斥 |
| `decay-on-death` | *(未设置)* | 死亡按比例保留（如 `0.5` = 减半），校验 ∈ (0,1)，**仅当 `reset-on-death: false`** 时可设 |
| `stages` | `{warn: 50, danger: 75}` | 穿越阈值时 title+音效提示 |

### 3.4 `anti_dust` — 抗尘埃药（v1.2 新增）

制药台是**多方块结构**，不是合成表：竖着叠三格 —— 最上面铁砧、中间发射器/投掷器、最下面铁块；
作物放进核心方块 UI 的**中心格（索引 4）**，手持木棍右键铁砧完成合成（木棍不消耗）。
四个水平方向放什么方块都不参与判定（旧版要求的「同层 8 邻原木」已移除）。

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | `false` = 不注册任何监听器，发射器完全退回原版行为 |
| `cooldown-seconds` | `20` | 同一玩家两次服药最小间隔，校验 ≥ 0（0 = 无冷却） |
| `cures` | `[POISON, HUNGER, NAUSEA, WEAKNESS, SLOWNESS]` | 服药瞬间无条件解除的状态效果（`PotionEffectType` 名） |
| `hud-immume-mark` | `"◆抗尘"` | 免疫期间 HUD 显示的抗性段，不能为空 |
| `levels.<id>` | 四档 | 见下表 |

档位字段：`display-name`（物品名）、`color`（`#RRGGBB`）、`reduction`（服药降低的腐蚀百分点，
校验 `(0,100]`）、`immune-seconds`（免疫时长，校验 ≥ 0）、`items`（触发该档位的作物 `Material` 名）。

**档位顺序即优先级，取首个匹配**；同一作物被两个档位重复认领会**拒绝加载**。
配置里没有 `anti_dust` 段时走内置默认表，老配置文件升级不会炸。

| 档位 id | 默认名 | reduction | immune-seconds | items |
|---|---|---|---|---|
| `refined` | 精制抗尘埃药 | 50.0 | 30 | GOLDEN_CARROT, CHORUS_FRUIT, GLOW_BERRIES, ENCHANTED_GOLDEN_APPLE |
| `strong` | 强效抗尘埃药 | 35.0 | 20 | NETHER_WART, CRIMSON_FUNGUS, WARPED_FUNGUS, SWEET_BERRIES |
| `improved` | 改良抗尘埃药 | 25.0 | 15 | PUMPKIN, MELON_SLICE, SUGAR_CANE, COCOA_BEANS, BROWN_MUSHROOM, RED_MUSHROOM |
| `crude` | 粗制抗尘埃药 | 15.0 | 10 | WHEAT, CARROT, POTATO, BEETROOT, KELP, DRIED_KELP, BAMBOO, CACTUS |

实现要点（改代码时必读）：

- 药水是「原版基底 + PDC 标识」：基底 `HONEY_BOTTLE`，档位存 `radsurvival:dust_level`。
  Minecraft 不允许插件注册新物品 ID，数据包同样做不到。
- 写入 `DataComponentTypes.CONSUMABLE` 只为拿到原版的**饮用动作**（动画/音效/扣物品），
  `consumeEffects` 故意留空——真正的效果由 `AntiDustListener` 在 `PlayerItemConsumeEvent` 结算。
- **冷却靠取消该事件实现**：Bukkit 取消 `PlayerItemConsumeEvent` 不扣物品，
  于是「冷却中喝不掉且不亏东西」自然成立，不需要手写「先判后扣」。
- **不写 `USE_COOLDOWN` 组件**：它的冷却分组是原版共享的，写进去会让本插件药水与原版食物互相卡冷却。
- 免疫窗口是内存态（`DustImmunity`），quit 时清除——防止「下线喝药、上线免伤」。

### 3.5 `feedback` — 表现层

| 键 | 默认 | 说明 |
|---|---|---|
| `actionbar` | `true` | 双条（辐射等级条 + 腐蚀条），独立 1s 周期任务，与结算解耦 |
| `particles` | `true` | 受击粒子（1.21.11 无 WIHN 枚举时自动降级烟雾类） |
| `sound` | `true` | 受击音效 + 阶段跳变播报（warn/danger title） |
| `death-message` | `true` | 辐射致死时替换废土风死亡文案（掉血归因 + 时间窗双保险，不误伤其他死因） |
| `bossbar-corrosion` | `false` | `true` = 腐蚀条改用 BossBar 常驻，actionbar 仅显示辐射等级条 |
| `protocol-lib` | `auto` | `auto` \| `true` \| `false`；仅做「抑制原版受伤红闪」，检测不到/异常时留桩降级 |

### 3.6 `commands`

| 键 | 默认 | 说明 |
|---|---|---|
| `reload-permission` | `radsurvival.reload` | `/rad reload` 所需权限节点（console 与持该节点玩家可执行） |

---

## 4. 命令与权限全表

子命令/别名/用法/权限收敛在 `SubCommand` 枚举，输出统一走 `CommandOutput` 按**显示宽度**对齐
（CJK 与 `▲▸●▓` 等符号占 2 列）。

| 子命令 | 别名 | 说明 | 权限 |
|---|---|---|---|
| `/rad reload [--dry-run]` | `rl` | 重载 config.yml。`--dry-run` 只校验磁盘配置并报结果、**不应用**。合法时**服务容器整体重建**，在线玩家腐蚀经 PDC 无缝续接；控制台可执行 | `commands.reload-permission`（默认 `radsurvival.reload`） |
| `/rad status [player]` | `st` / `info` | 只读预览一轮：昼夜阶段与 timeFactor、露天判定、当前腐蚀与倍率、盔甲减免、预计本轮伤害/跳过或免疫原因。**控制台/RCON 无参**时输出配置摘要 + 各启用世界昼夜 | `radsurvival.use` |
| `/rad force-check [player]` | `check` / `fc` | 立即对目标跑一轮**真实结算**（与主循环同一 `DamageApplier` 路径：事件广播→掉血）。免疫/过滤跳过时明确报告原因 | 无需管理权限（操作自身角色） |
| `/rad corrosion <get\|set\|add> <player> [值]` | `cor` / `c` | `set` 钳 [0,100]、`add` 支持负数同样钳制；写后同步内存权威、状态镜像与 Player PDC（`radsurvival:corrosion`）；掉线玩家报错。`get` 同时显示内存权威与 PDC 落盘值 | `radsurvival.admin` |
| `/rad dust <list\|give\|help>` | `d` / `anti-dust` | `list` 打印四档与作物归属；`give <档位> [player]` 直接发药（档位 id 或作物名均可）；`help` 打印制药台结构 | `radsurvival.admin` |
| `/rad help [子命令]` | `?` / `h` | 子命令按发送者权限裁剪，附当前生效配置一行 | — |

- 无参 `/rad` 直接出帮助；打错子命令给**最近似建议**（`/rad stats` → 「你是不是想用 status？」）。
- 腐蚀读数保留两位小数（与 PDC 精度一致），阶段越阈自动改黄/红。

---

## 5. 结算链路与判定语义

### 5.1 主循环顺序

```
tick-interval（≥20 全局任务）
 └─ 过滤链：enabled-worlds 白名单 → dimensions → damage-game-modes
            → radsurvival.exempt 豁免 → require-on-ground
     └─ 露天判定（60s chunk 段缓存；豁免维度在扫描前短路 = 非露天）
         └─ ★ 先腐蚀步进、后伤害结算
             └─ 抗尘埃药免疫窗口短路（不掉血、不发事件、不播反馈；v1.2 唯一免疫来源）
                 └─ 公式伤害 → 最低杀伤保底 max(公式, 基线 × min-damage-fraction)
                     └─ RadiationExposureEvent（可取消、可 setFinalDamage 改伤）
                         └─ 稳定 API 掉血
```

> v1.2 之前这里还有一级「盔甲减免 ≥100% 完全免疫短路」，已随护甲削弱删除——
> 护甲现在只能减伤，`IMMUNE` 结果只由抗尘埃药窗口产生。

**伤害来源标记**：`radsurvival:source=radiation` 由 LOWEST 优先级的自有监听写在**事件瞬态 PDC** 上，
其他插件在 NORMAL 及以后优先级即可读到。

### 5.2 三条必须遵守的口径

1. **先累后打**：腐蚀步进发生在伤害计算之前，本轮累加的腐蚀立刻计入倍率。
2. **无敌帧**：`tick-interval ≥ 20`，否则伤害被原版无敌帧吞掉。
3. **不双重减免**：由「结算瞬间摘甲」保证，不依赖伤害类型（见 [§9](#9-判定链路强化记录)）。

### 5.3 HUD 文案语义

| 显示 | 含义 |
|---|---|
| `▲▲▲▲▲` | 露天白天满强度（5 格 = 基准伤害） |
| `▲▲△△△` | 夜晚 timeFactor 0.5 → 2 格 |
| `●安全` | 有遮蔽 / 被过滤链跳过 |
| `◆抗尘` | 抗尘埃药免疫窗口内且露天（v1.2；旧的 `◆免疫` 已废弃） |
| `腐蚀 67% ▓▓▓▓▓▓▓░░` | 腐蚀条 10 格，颜色按阶段（normal 白 / warn 黄 / danger 红） |

`HudRenderer.IMMUNE_MARK`（`◆免疫`）保留为 `@Deprecated` 常量仅为兼容外部按字符串匹配的插件，
正常情况下不会再出现在 HUD 上；`renderLevelBar` / `renderActionBar` 的旧 `double armorReduction`
重载同样标 `@Deprecated`，第三个参数已改为 `boolean dustImmune`。

---

## 6. 数值口径与勘误

**v1.2 护甲全面削弱**是本期最大的数值变更，原 §1.6 预算表与旧断言全部随之重算。

### 6.1 v1.2 护甲削弱对照

| 材质 | 旧系数 | 新系数 | 旧满配 | 新满配 | 白天死亡（旧→新） |
|---|---|---|---|---|---|
| 皮革 | 0.25 | 0.15 | 25% | 15% | ≈22s → ≈22s |
| 金 / 锁链 | 0.45 | 0.30 | 45% | 30% | — |
| 铁 | 0.70 | 0.48 | 70% | 48% | ≈70s → ≈33s |
| 钻石 | 0.85 | 0.60 | 85% | 60% | ≈91s → ≈42s |
| 下界合金 | 1.00 | 0.72 | **100%（免疫）** | 72% | **∞（免疫）→ ≈56s** |

部位权重同时调整：头盔 0.20→0.12、胸甲 0.40→0.46、护腿 0.30 不变、靴子 0.10→0.12
（权重和仍为 1.00，胸甲更贵、四肢更便宜，符合「护住要害」的直觉）。

**两处语义删除**：

1. `RadiationTask` 里 `armorReduction >= 1.0 → IMMUNE` 的短路分支已移除。
   `RoundResult.IMMUNE` 这个枚举值**保留**，但现在唯一来源是抗尘埃药免疫窗口。
2. `PluginConfig` 新增硬校验：任何材质「权重和 × 系数 ≥ 1.0」直接拒绝加载。
   旧配置里的 `NETHERITE: 1.00` 会在启动时报错并指出实际乘积。

### 6.2 最低杀伤保底

`finalDamage = max(公式伤害, base × timeFactor × 腐蚀倍率 × min-damage-fraction)`。
纯函数实现为 `ArmorCalculator.applyFloor(damage, baseline, minDamageFraction)`；
`baseline ≤ 0`（如夜晚因子 0）时原样返回，不会凭空造伤害。

### 6.3 `corrosion.armor-affects-accum` 偏差（仍然存在，量级已变）

默认 `true` 下盔甲同时减伤与减累积，与「累积 1%/s」的预算表口径存在系统性偏差
（方向：穿得越厚死得越晚）。v1.2 下调系数后偏差仍成立但绝对值变了：

- 白天·铁全：不参与累积 ≈33s，参与累积 ≈36s
- 白天·钻石全：不参与累积 ≈42s，参与累积 ≈42s
- 裸装两口径完全等价（无甲 → ×(1−0)=1）

方向与量级被 `SurvivalBudgetRegressionTest#armorAffectsAccumDefaultDeviatesFromTable` 钉死为回归断言。
**对照预算表时，受甲行应预期偏慢。**

伤害基准不变：`base-damage: 1.0`（半心/s）、`max-multiplier: 2.0`（×2 封顶）、
夜晚 `night-multiplier: 0.5`（累积同倍率减半，timeFactor 统一口径）。

### 6.4 取整与浮点

公式浮点原值直传 `damage()`，**不取整**（默认参数下半心 = 1.0 点）。

### 6.5 CustomDamageMeta 延后（伤害主线 API 事实记录）

- Paper `CustomDamageMeta`（`@UnstableApi`）配置开关**本期不做**；伤害主线只用稳定 API 即成立：
  自有 `RadiationExposureEvent` 广播 + 事件 PDC 标记 `radsurvival:source=radiation`（LOWEST 盖章）。
- **1.21.11 API 适配事实（jar 核实，与 plan §1.3 引用的旧签名不同）**：
  `Damageable.damage(double, DamageCause)` 重载在该版本已移除（顶层 `DamageCause` 枚举亦收敛为
  `EntityDamageEvent.DamageCause` 嵌套形态）。结算改用同为稳定 API 的
  **`player.damage(amount, DamageSource)` + `DamageType.GENERIC`**。
- **护甲二次减免**：旧验收（裸僵尸靶）判定「无」，**已被真实客户端推翻**——GENERIC 照常减免。
  现由「结算瞬间摘甲」消除，详见 [§9](#9-判定链路强化记录)。
- **无敌帧**：console `/damage` 会绕过无敌帧（连发两发均生效），无法离线测量 20 tick 窗口。
  若观察到隔轮被吞，把 `tick-interval` 改为 24（校验下限仍为 20，无需改代码）。

---

## 7. 对外 API 与事件

### 7.1 `RadiationExposureEvent`

- 可取消；取消即不落地伤害（腐蚀数值不回滚）。
- `setFinalDamage(double)` 改伤。
- 携带的腐蚀值是**步进后**值，与倍率基数严格一致。
- `getDamageChannel()` / `getDamageChannelId()`：`SUN`（本插件结算）或 `EXTERNAL`（其他一切来源）。

`DamageCause.CUSTOM` 无法反查来源，`/damage`、雷击等也会落到 CUSTOM。插件用「距离 + 金额」
时间窗（`SunMark`，100ms）配合四条件 `classify` 认领太阳伤害；不认领的视为外部伤害，
按原版公式把减免补回去，保持第三方伤害的原版手感。

### 7.2 `CorrosionStageChangeEvent`

包装 `CorrosionMeter` 的内存阶段回调。**Cancellable = 仅抑制播报，不改数值**。

### 7.3 `RadSurvivalApi`

只加方法不改签名，保持二进制兼容。**不要**直接碰 `RadiationTask#statesView`。

### 7.4 PlaceholderAPI

| 占位符 | 含义 |
|---|---|
| `%radsurvival_exposure%` | 当前是否露天 |
| `%radsurvival_reduction%` | 盔甲减免率（v1.2 上限 0.72） |
| `%radsurvival_timemul%` | 当前 timeFactor |
| `%radsurvival_corrosion%` | 腐蚀值 |
| `%radsurvival_dust%` | 抗尘埃药免疫剩余秒数（v1.2；未免疫为 `0`） |

未安装 PAPI 时 `PlaceholderBridge` 完全不加载扩展类（零报错）。reload 会先卸旧再注册。

`RadSurvivalApi` 在 v1.2 新增两个方法（只加不改，二进制兼容）：

| 方法 | 语义 |
|---|---|
| `isImmune(Player)` | **语义变更**：现在只回答「抗尘埃药窗口是否生效」，不再看盔甲（护甲已无法免疫） |
| `getImmunityRemainingSeconds(Player)` | 新增：免疫剩余秒数（0 = 未免疫） |

第三方插件若用 `getArmorReduction(p) >= 1.0` 判断免疫，v1.2 后恒为 `false`——请改用 `isImmune`。

---

## 8. 露天判定异步化

主线程稳态**零方块扫描**，玩法与公式零取舍。

- **缓存粒度 = 方块列（v1.3）**：缓存键是「世界 + x/z 的 16 格段」，**不含高度**。一整个方块列
  只扫一次，值为该列的**顶遮挡高度**；列内任意眼高用同一个值判定（`eyeY > 顶遮挡` 即露天）。
  旧版键里带高度段，玩家蹲下站起就会换键重扫，且同一列的不同高度段各写一份互不相同的值，
  导致判定在相邻两轮之间来回切换——这就是「头顶没方块却掉血 / 判定时有时无」的根因。
- **扫描范围 = 整列**：从世界底部之下起算，不是从眼睛附近起算。这样才能保证「顶遮挡」
  是整列的真实属性，低处的方块不会漏。
- **双层缓存**：权威层（TTL 60s，值为顶遮挡高度）+ 粘滞层（无过期、容量封顶）。
  列过期后的补扫派发给 `ScanDispatcher`（生产 = `BukkitScheduler#runTaskAsynchronously`，
  列级在途去重，同列多玩家共享一次扫描），当轮以粘滞旧值应答——判定**机制**与结算/伤害/
  免疫/恢复分支完全不变，仅缓存新鲜度滞后 ≤1 个结算周期（本就被 60s TTL 支配）。
- **冷启动零妥协**：玩家进入从未记录过的列时仍主线程同步扫描，首轮判定精确度与同步口径一致。
- **异常安全**：异步任务只读已加载区块；恰被卸载触发 Paper 异步防护等异常一律内部吞掉
  （不发布、下轮重试），绝不反噬主线程；调度器关闭（禁用中）同样静默。
- **主线程守护**：判定入口在非主线程被调用时一律按「露天」保守应答（宁多掉血，不误判安全区），
  由下一轮主线程判定纠正。
- **开关**：`async-exposure-scan: true`（默认）→ 异步管线；`false` → 全同步旧口径
  （极小地图服或想杜绝 1s 滞后可切）。带校验，reload 后随服务容器自动重建装配。
- **收益**：每结算轮每玩家的主线程成本 = 2 次缓存查找（原：过期段最多 ~390 次
  `getBlockAt`+`getBlockData` 对象分配），且扫描次数与玩家所在高度**完全解耦**
  （v1.3 前，地面附近上下移动的玩家几乎每轮都在重扫）。
- **其余必须主线程的操作不变**：`damage()`/事件广播/粒子音效/actionbar/BossBar/PDC 写入
  全部留在主线程；腐蚀数值是纯内存运算无需异步。
- 测试：`SunExposureCheckerTest#asyncRefillPipelineWithStickyValue`（冷启动同步零派发、
  过期粘滞 + 去重派发 1 个、刷新后精确新值）、`#asyncScanFailureIsContained`（异常吞掉
  不发布、在途释放、下轮自愈重试）、`PluginConfigValidationTest#asyncExposureScanSwitch`；
  v1.3 新增 `#verticalMovementDoesNotRescanOrFlip`、`#columnScanCoversBelowEyeHeight`、
  `#cacheEntriesAreColumnScoped`。

> **排错提示（v1.3）**：升级后如果仍有「明明天空开阔却掉血」，先确认**真的对着天空**——
> 树叶、藤蔓、玻璃顶、半砖、地毯、雪层、脚手架等都可能算遮蔽（`transparent-blocks-count-as-cover`
> 只影响「不挡光但不可通行」的方块）。`/rad status <玩家>` 会直接给出该轮的露天判定与顶遮挡推导。

---

## 9. 判定链路强化记录

### 9.1 v1.1 判定链路加固（四处真实缺陷）

起因：一次「排查代码确定功能真实可用」的复核。全部结论均以**真实 1.21.11 客户端 + Paper 132**
实测为准。回归测试见 `SunRadiationListenerTest`、`DamageChannelAndReloadMockBukkitTest`。

**① 原版护甲二次减免 / 安全区伤害被吞（最严重）**

- 旧实现直接用 `player.damage(amount)` 结算，会**再走一遍原版护甲减免**；同时 `RadiationTask`
  在 `armorReduction >= 1.0` 时**直接跳过 `damage()`**。叠加后果不是「少扣一点」，而是
  **安全区内的第三方伤害全部归零**：实测全套下界合金玩家被 `/damage … 10 minecraft:generic`
  或被雷劈 = 掉 0 血。
- 旧验收判定「GENERIC 不吃甲」是因为用 `/summon zombie` 做靶——**僵尸盔甲槽本来就是空的**。
- 现方案（`ARMOR_NEUTRALIZING_EXECUTOR`）：在 `damage()` 的**同步栈内**把四件盔甲从装备槽取出、
  调用后原样放回。护甲点数归零 → 原版防御恒 0，公式成为唯一减免路径。摘下的是**同一
  `ItemStack` 实例**（实测岩浆浸泡后胸甲仍在且耐久递增），关停兜底 `restoreSuspendedArmorAll()` 幂等。
- **不要**改成「监听 `EntityDamageEvent` 再折算回去」：那是不可逆的数值改写，与第三方插件的
  减免监听叠加顺序不可控。摘甲是唯一能保证「原版防御恰好为 0」的手段。

**② 外部伤害与太阳伤害无法区分** — 见 [§7.1](#71-radiationexposureevent)。

**③ reload 泄漏** — 旧 `FeedbackService.shutdown()` 未解绑 `CorrosionMeter` 阶段回调与 `DamageApplier`
挂点，reload 后旧实例继续被调用。现用 `hookRef`/`stageRef` 成对解绑，并新增
`CorrosionMeter.forgetAll(Collection<UUID>)` 清理离线残留。

**④ 占位符 reload 后不刷新** — 旧 `PlaceholderBridge` 只注册不卸载，reload 后仍指向旧实例。
现用静态 `volatile registered`，注册前先卸旧；reload 末尾重注册，`onDisable` 卸载。

### 9.2 M4 二轮排查（5 处修复/优化）

1. **露天段缓存共享误差（真 bug）**：旧实现按 16³ 段缓存**布尔结论**，段内首个查询定格结果
   ——同段不同眼睛高度互相误判。现缓存改存**顶遮挡高度**（自上而下扫描，首个遮挡即停），
   段内任意 eyeY 由同一扫描结果精确派生，扫描次数不变。
2. **HUD 陈旧镜像（真 bug）**：actionbar 原读结算镜像 `PlayerRadState`，被过滤链跳过的轮次不刷新
   （切创造仍显示 ▲▲▲▲▲）。现改读 `previewRound` 实时判定快照，被过滤玩家自然显示 `●安全`。
3. **事件腐蚀值口径不一致**：`RadiationExposureEvent` 原携带**步进前**值，而 `finalDamage` 用
   **步进后**值计算。现事件携带步进后值。
4. **崩溃丢失最近腐蚀进度**：PDC 原只在 quit/death/reload 落盘。现每轮值变化即同步 PDC
   （无变化不写，免疫/满值玩家零写放大）。
5. **露天判定 fail-safe**：环境读取异常（区块未加载等）不再击穿结算/HUD——退回上一已知判定
   （无记录按遮蔽=安全），fallback 计数可观测、警示节流。附带修正 `ZERO_DAMAGE` 轮次
   `lastDamage` 未清零的小瑕疵。

**排查确认干净**：`DayNightProvider`（floorMod/边界左闭右开）、`ArmorCalculator`（前缀×后缀
双条件归类、EnumMap 确定性求和 + clamp）、`RadiationMath`（全函数单调有界）、
`CorrosionMeter`（阶段基线/一次性标记/PDC 读写）、`RadiationMarkListener`、`PlaceholderBridge`
（类加载隔离）、`HudRenderer`（取整/颜色纯函数）。

---

## 10. 构建混淆：已评估，不采用

曾尝试 ProGuard 7.6.1（rename-only 档），结论是该插件不适合混淆此产物，已回退：

1. `-dontpreverify` 不可用——Java 21（class 65）加载期强制校验 StackMapTable，缺少帧会
   `VerifyError: Expecting a stackmap frame at branch target 42` → **插件直接拒载**。
2. 内嵌 Caffeine 的私有 `*Limiter` 继承链会让 ProGuard 预校验误报
   `IncompleteClassHierarchyException`，只能靠把 `lib/*` 排除在输入外绕过。
3. 排除 `lib/*` 后必须从 **shade 后的类**（已 relocate）取输入；若用 `target/classes`，
   回灌后引用的是未重定位的 `com.github.benmanes.*` → 运行期 `NoClassDefFoundError`。
4. 即使打通，`shade` 在 `package` 末尾替换主产物，插件间阶段顺序难以稳定，
   反复出现「回灌到未混淆 jar」的竞态，收益（仅改名内部辅助类）远低于维护成本。

**结论：优先保证可用性，默认构建不做混淆。** 如需减少代码可读性，建议在部署侧用成熟方案
（如商业混淆器）对**独立 jar** 离线处理，并完成实机验收后再上线。

---

## 11. MockBukkit 测试接线结论

| 事项 | 结论 |
|---|---|
| 首选坐标 `com.github.seeseemelk:MockBukkit-v1.21` | **不可用**：最新 3.133.2 钉死 paper-api 1.21.1，`mock()` 初始化内部材质标签表时引用 1.21.11 已移除条目（`minecraft:chain`）直接崩溃 `InternalTagMisconfigurationException` |
| 实际采用坐标 | `org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3`（同项目后继），与 paper-api 1.21.11 兼容；排除其 junit-jupiter-api 6.x 传递依赖，运行时钉 JUnit 5.11.4 |
| 可测（已全部进套件） | 插件装配与 tick 推进（`performTicks`）、join/quit/death 事件与 PDC 往返、过滤链（白名单/游戏模式/exempt/着地）、免疫零副作用、先累后打数值（每轮伤害 1.01→1.10 精确断言）、事件取消/改伤、LOWEST PDC 标记、命令层（status/force-check/corrosion/TabComplete/权限） |
| 不可测 → 实机清单 | ① `damage(double, DamageSource)` 真实实现（`LivingEntityMock` 未实现，测试经注入执行器走 `damage(double)`）；② `BlockData#isOccluding` 方块遮蔽扫描（`BlockDataMock` 未实现，露天经 `ExposureResolver` 注入）；③ 原版无敌帧（MockBukkit `noDamageTicks` 恒 0）；④ 原版护甲/附魔对 GENERIC 的二次减免 |

---

## 12. 已知限制与排错

| 现象 | 判断 |
|---|---|
| 日志里中文花屏 | Windows 控制台是 GBK，插件输出 UTF-8。经 RCON/玩家聊天完全正常，**不是插件缺陷**。启动加 `-Dstdout.encoding=UTF-8` 或先执行 `chcp 65001` |
| 腐蚀在涨但血不掉 | `tick-interval` 低于 20，伤害被无敌帧吞掉 |
| 服务器拒载 `Error resolving libraries` | 用了 `-Plibraries-channel` 的 thin jar 但服务器连不上镜像；换默认 fat jar |
| 玻璃顶不算安全 | 检查 `transparent-blocks-count-as-cover` 是否为 `true` |
| 下界/末地也掉血 | 检查 `dimensions` 是否把 `NETHER`/`THE_END` 纳入 |
| 配置改了没生效 | 先 `/rad reload --dry-run` 只校验不应用，确认合法后再 `/rad reload` |
| 玩家想免除辐射 | 给 `radsurvival.exempt` 权限节点 |
| 想看装配细节 | `/rad status`（控制台无参出配置摘要）；启动日志 3 行摘要与之同源 |
| 制药台敲铁砧没反应 | `/rad dust help` 对照三条：从上到下是**铁砧 → 发射器/投掷器 → 铁块**、中心格有**农作物**。缺任一条都不产出，但不会扣物品 |
| 抗尘埃药喝了没反应 | 提示「冷却中」→ 等 `anti_dust.cooldown-seconds`；提示「配方已失效」→ 该档位已被配置删除，`/rad dust list` 看现存档位 |
| 旧配置启动报「完全免疫」错误 | v1.2 禁止任何材质满配 ≥ 1.0。把该材质系数调低（默认下界合金 0.72） |
| 玩家反映穿下界合金也被晒死 | **v1.2 有意如此**（72% 减免、仍吃 28%）。想免疫请用抗尘埃药 |
| **头顶看着是天空却仍掉血（v1.3 后）** | 先 `/rad status <玩家>` 看该轮判定。若判定为露天，说明列内确实有方块算遮蔽以外的判定成立；v1.3 已消除「判定抖动 / 无方块处掉血」。注意树叶、藤蔓、半砖、地毯、雪层、脚手架都可能算遮蔽 |
| **判定时有时无 / 进门出门掉血不稳（v1.3 前）** | 已知缺陷，v1.3 已修复（缓存键含高度段导致同列结果互相覆盖）。升级 jar 即可，无需改配置 |
| **站桩显示安全，跳一下就掉血（v1.3 前）** | 同上：跨 16 格段界换键重扫导致。v1.3 起一列只扫一次，与眼高无关 |
| **日志出现「检测到服务端 Spigot…，本插件无法在此运行」** | v1.4 起的主动拒绝（见 §12.1）。换 Paper 系服务端即可；这不是配置问题 |
| **日志出现「检测到服务端 Folia…」** | v1.4 起的主动拒绝（见 §12.1）。本插件依赖全局调度器，未做区域化改造 |
| **想知道当前跑在什么分支上** | 启动日志 `平台 ·` 行，或 `/rad status` 的 `platform` 行；两者都带识别来源（类探测 / getName / 兜底） |

### 12.1 服务端支持范围（v1.4 新增）

插件在 `onEnable` **最前面**识别服务端分支，并对无法承载本实现的分支**主动拒绝加载**
（而不是让异常自然抛出）。

| 分支 | 状态 | 依据 |
|---|---|---|
| **Paper 1.21.11** | ✅ 已实测（132） | M4/§G–§L 全部验收在此完成；v1.4 冒烟复测通过 |
| **Leaf 1.21.11** | ✅ 已实测可加载（179） | v1.4 冒烟：正常启用、7 监听器、`platform` 识别为 `Leaf（getName）`。**未做完整玩法验收** |
| **Purpur 1.21.11** | ⚠️ 理论可用，**未实测** | 下载源在本网络不可达（build 元数据可达、二进制端点超时）。识别逻辑与 Leaf 同路径 |
| **其他 Paper 分支** | ⚠️ 显示为「未知分支」并照常加载 | 日志追加「未实测分支，如遇问题请附本行」 |
| **Spigot** | ❌ 拒绝加载（**未实测**，仅隔离类加载验证） | 以 paper-api 编译，缺 `DamageSource`/`Component` 等类。真 Spigot 无法获取（官方不发预编译 jar） |
| **Folia 1.21.11** | ❌ 拒绝加载（✅ 已实测，14） | 区域化线程模型：`BukkitScheduler` 的**全局任务**在其上抛 `UnsupportedOperationException`，而主循环 / HUD / 露天异步补扫全部依赖全局调度 |

识别以**类存在性为准**，`getName()` 只作补充（它可被分支服务端改写）：
`RegionizedServer` 存在 → Folia；名字含 purpur/leaf → 对应分支；
`org.bukkit.damage.DamageSource` 可加载 → Paper；名字含 spigot 且探测阴性 → Spigot；否则 unknown。
**品牌不参与任何数值决策**——伤害公式、判定链路、配置默认值在任何分支上完全相同，
`/rad status` 的 `platform` 行只报「你是谁」，不改「怎么算」。

> **v1.4 实测的一个反直觉结论**：Leaf 上走的是 `getName()` 匹配而非 `DamageSource` 类探测——
> Paper 系分支的 damage 类由 paperclip 在启动时重映射/注入，插件 `onEnable` 阶段的类加载器
> 未必能 `Class.forName` 到。因此「类探测优先」是**容错设计**，不是「name 匹配可删」的理由：
> 在走不到类探测的分支上，唯一名匹配是**唯一**能给出精确分支名的证据。详见 ACCEPTANCE §M.3。
>
> **Folia 的 `plugin.yml` 需 `folia-supported: true` 才能让我们的提示生效**：缺省该字段时
> Folia 在插件加载阶段就拒绝，`onEnable` 一行都跑不到，识别与提示成为死代码。
> 该字段**不代表支持 Folia**，只代表把拒绝的发言权交给插件（以便给出中文原因）。见 ACCEPTANCE §M.2。

**实机验收环境**：`test-server/`（Paper 1.21.11-132、离线模式、RCON 25764、superflat）**不入库**
（含世界存档与明文凭据）。验证脚本见 [tools/](tools/)（RCON 客户端、日志读取、最小协议客户端），
启动方式与 17 项人工验收清单见 [ACCEPTANCE.md](ACCEPTANCE.md) §D。
跨分支冒烟脚本见 [test-servers/run-smoke.ps1](test-servers/run-smoke.ps1)，
操作手册见 **[PLATFORMS.md](PLATFORMS.md)**，结论见 ACCEPTANCE §M。

---

## 13. 里程碑状态

- **M1 ✅**：Maven 工程 + plugin.yml（`libraries:`/`softdepend` 接线）+ Configurate 配置加载与全量校验 + `/rad reload`、`/rad status` + 校验单元测试。
- **M2 ✅**：露天/昼夜/盔甲/腐蚀条四核心模块（M2a，纯内核+薄适配，58 条单测）+ 主循环伤害结算接线（M2b：`RadiationTask`/`DamageApplier`/`RadiationExposureEvent`/会话与标记监听器/`RadSurvivalApi` 门面/命令完善/腐蚀 PDC 持久化，MockBukkit 48 条集成测试）。
- **M3 ✅**：反馈层与对外 API——`CorrosionStageChangeEvent` + `HudRenderer`（纯函数双条）+ `FeedbackService`（actionbar 独立 1s 任务、阶段跳变 title+音效、受击粒子+音效、BossBar、reload 重建无泄漏、onDisable 全清）+ `DamageFeedbackHook` 受击挂点 + `DamageTagListener` 死亡文案 + `PlaceholderBridge` / PAPI + ProtocolLib 可选红闪抑制。新配置键 `bossbar-corrosion`(false)、`death-message`(true)、`protocol-lib`(auto) 全部带校验。
- **M4 ✅**：实机验收（`test-server/` + Paper 1.21.11-132 + RCON）：插件实机启用/生成配置/坏配置 reload 拒载/控制台 status/onDisable 全部通过；**`libraries:` 通道实测失败 → 默认改 shade+relocate**；玩家侧项目转 17 项人工手册（§D）。
- **命令层与日志优化 ✅（2026-10-25）**：`/rad` 子命令/别名/权限收敛到 `SubCommand` 枚举，输出按显示宽度对齐；启动日志改为 3 行紧凑摘要，与 `/rad status` 同源（`RadiationServices#describeFlags`）；粒子降级细节需 `-Dradsurvival.verbose=true` 才输出。
- **v1.1 ✅**：判定链路加固 + 真实客户端复验（全绿）——M4 的 §C.1 裁决被**推翻**，修掉四处真实缺陷（[§9.1](#91-v11-判定链路加固四处真实缺陷)），移除会禁用服务端
  re-configuration API 的 `PlayerLoginEvent` 监听。已用真实 1.21.11 客户端跑通：露天结算、
  腐蚀累加、夜晚 ×0.5、死亡文案、`/rad reload` 续接、铁甲玩家外部伤害按原版减免且护甲完好。
- **v1.2 ✅**：护甲全面削弱 + 移除下界合金免疫 + 抗尘埃药（265 条测试全绿）。
  - **护甲**：六材质系数 0.15/0.30/0.30/0.48/0.60/0.72，部位权重 0.12/0.46/0.30/0.12；
    新增硬校验禁止任何材质满配 ≥ 1.0；新增 `armor.min-damage-fraction`（默认 0.10）最低杀伤保底。
  - **免疫重构**：删除 `RadiationTask` 的 `armorReduction >= 1.0` 短路；`RoundResult.IMMUNE`
    改由抗尘埃药窗口产生；HUD 段从 `◆免疫` 换成 `◆抗尘`。
  - **抗尘埃药**：多方块结构（发射器 + 正上方铁砧 + 同层 8 邻原木）+ 发射器 UI 中心格放作物 +
    木棍右键铁砧合成（木棍不消耗）；四档作物表、降腐蚀、限时免疫、解毒、20s 冷却全部配置驱动；
    `/rad dust list|give|help` 管理命令；`RadSurvivalApi#getImmunityRemainingSeconds`。
  - **测试**：188 → 265 条（新增 `DustImmunityTest`、`DustCraftStructureMockBukkitTest`、
    `DustItemFactoryMockBukkitTest`、`AntiDustChainMockBukkitTest` 共 43 条），
    重写 `ArmorCalculatorTest` / `SurvivalBudgetRegressionTest` 全套数值断言。
  - **实机复验**（Paper 1.21.11-132 + RCON）：插件启用无异常、启动日志含抗尘埃药装配行、
    `rad dust list/help`、`rad reload` / `--dry-run`、世界内结构（铁砧就位 + 8 邻为
    `#minecraft:logs`）与中心格作物全部核对通过。
- **v1.3 ✅（本期）**：露天判定稳定性修复（315 条测试全绿）。
  - **根因**：缓存键含眼睛所在的高度段，且扫描起点取该段底 → ① 同一方块列被不同高度段各扫一次、
    结果互相覆盖 → 判定在相邻两轮之间来回切换；② 蹲下/站起/上下台阶跨 16 格段界即换键 →
    重复整列扫描。
  - **修复**：缓存键改为**方块列** `(world, sectionX, sectionZ)`（不含 y），扫描统一从世界
    底部之下起算**整列**，同列任意眼位共享同一顶遮挡高度；缓存条目由「每 16³ 段一条」降为
    「每 16×16 列一条」。旧 `ColumnKey.of(world, x, y, z)` 保留为 `@Deprecated`（y 被忽略）。
  - **未改**：任何玩法数值（昼/夜系数、腐蚀、护甲、抗尘埃药）、异步管线、双层缓存结构、
    冷启动同步直算、透明方块口径、维度豁免。
  - **测试**：265 → 268（`verticalMovementDoesNotRescanOrFlip`、`columnScanCoversBelowEyeHeight`、
    `cacheEntriesAreColumnScoped`），三条均做过反向验证。
  - **升级**：`config.yml` 无任何变更，直接覆盖 jar 即可。
- **v1.4 ✅（本期）**：服务端品牌识别与防御分支（332 条测试全绿）。
  - **实施范围**：只做 plan.md §7 的**识别 + 防御**。`paper/leaf/purpur/spigot/folia/unknown`
    归一化 + 识别来源记录（启动日志 `平台 ·` 行、`/rad status` 的 `platform` 行）；
    Spigot / Folia 在 `onEnable` 首步拒绝加载并给出中文原因。
  - **明确未做**：按品牌调参（`adaptive-profile`、`profiles.<brand>`、三层优先级、
    Configurate presence 检测）。理由见 plan.md §7.6——唯一随品牌变化的维度是主线程压力
    特征，而本插件主线程稳态已近零开销，无实测数据就按品牌改旋钮是负收益。
  - **未改**：任何玩法数值、判定链路、既有配置键、`RadiationServices` 装配、
    `ScanDispatcher` 注入点。Paper/Leaf 上**玩法行为完全不变**。
  - **跨分支实测**：Paper 132 ✅、Leaf 179 ✅、Folia 14 ✅（拒绝分支生效）、
    Purpur 未实测（下载源不可达）、Spigot 未实测（仅隔离类加载验证）。详见 §12.1 与 ACCEPTANCE §M。
  - **`plugin.yml` 增 `folia-supported: true`**：不是功能声明，是为了让拒绝提示能真正执行
    （缺省时 Folia 在加载阶段就拒载，`onEnable` 跑不到）。Paper/Leaf 上复验无副作用。
  - **测试**：315 → 332（`ServerBrandTest` 11 + `ServerBrandWiringMockBukkitTest` 3 +
    `ServerBrandSpigotIsolatedTest` 3），反向验证共 4 项，记录见 [CHANGELOG.md](CHANGELOG.md) v1.4.0。
  - **升级**：`config.yml` 无任何变更，直接覆盖 jar 即可。
