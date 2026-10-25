# RadSurvival

Minecraft **Paper 1.21.11** 辐射生存插件：白天露天受持续辐射伤害（夜晚减半、可寻遮蔽躲避），
腐蚀条随曝晒累积并放大伤害，穿戴盔甲按部位权重×材质系数减免（全套下界合金免疫）。
设计与里程碑见 [plan.md](plan.md)；**M1–M4 全部完成**，实机验收证据见 [ACCEPTANCE.md](ACCEPTANCE.md)；
二次开发（包结构 / 扩展点 / 测试 / 踩坑记录）见 **[DEVELOPMENT.md](DEVELOPMENT.md)**。

## 下载与自动发行

每次推送到 `main`（或手动触发）都会自动构建、跑全量测试并发布 Release：

**[→ 下载最新构建](https://github.com/xgp2012/RadSurvival/releases/latest)**

产物命名：**`RadSurvival-<年>-<月>-<日>-<当天第几次构建>.jar`**

| 示例 | 含义 |
|---|---|
| `RadSurvival-2026-10-25-1.jar` | 2026-10-25 当天第 1 次构建 |
| `RadSurvival-2026-10-25-3.jar` | 2026-10-25 当天第 3 次构建 |

- 日期按 **Asia/Shanghai (UTC+8)** 计算；序号取「当天该分支**已成功完成**的构建数 + 1」，
  用已完成而非已触发，避免并发排队抢到同一个号。
- 同名 `plugin.yml` 版本号与文件名一致，游戏内 `/version RadSurvival` 可直接对上。
- 每次发布附带 `.jar.sha256`，下载后可 `sha256sum -c` 校验完整性。
- 发布前会跑门禁：全量测试必须通过且**测试数量 ≥ 100**，否则不发布（防配置漂移静默跳过测试）。

**安装**：把 jar 放入服务端 `plugins/` → 重启 → 首次运行生成 `plugins/RadSurvival/config.yml`
→ `/rad status` 验证装配、`/rad help` 查看命令。

工作流定义见 [`.github/workflows/build-release.yml`](.github/workflows/build-release.yml)。

## 构建

要求：JDK 21（本项目验证环境为 `D:\java21`，Azul Zulu 21.0.12.1）+ Maven 3.10+。

```powershell
$env:JAVA_HOME='D:\java21'   # 若系统 JAVA_HOME 已指向 21 可跳过
mvn -e -B clean package                      # 默认：自包含 fat jar（推荐用于生产）
mvn -e -B clean package -Plibraries-channel  # 可选：thin jar + plugin.yml libraries: 运行期下载
mvn -e -B clean package -Drevision=2026-10-25-3   # 让 plugin.yml 版本号带上构建号（CI 用此法）
```

产物：`target/RadSurvival.jar`（默认约 1.8 MB fat jar；Caffeine/Configurate/Geantyref/Kyori-Option
已重定位到 `com.ft.radsurvival.lib.*` 内嵌，服务器**无需联网**）。

> **通道选择（plan.md §5.1 / §6）**：`libraries:` 运行期下载在实机环境曾因 Paper 使用的
> `maven-central.storage-download.googleapis.com` 被重置而 **`Error resolving libraries` 直接拒载**
> （ACCEPTANCE.md §B.2 有日志原文），故默认改为 shade+relocate；能直连该镜像的服务器可用
> `-Plibraries-channel` 产 thin jar（111 KB），两者 plugin.yml 由构建 profile 自动切换。

当前测试规模：**188 条全绿**（M1 校验 20 + M2a 纯内核 61 + M2b MockBukkit 集成 48 +
M3 反馈层 38 + M4 判定链路强化 3 + 露天判定异步化 3 + 命令层优化 4 +
v1.1 判定链路加固 11；`com.ft.radsurvival.mockbukkit` 与 `com.ft.radsurvival.listener` 包，
无跳过项）。

## 命令层与日志优化（2026-10-25）

**命令层**（`/rad`，子命令/别名/用法/权限收敛到 `SubCommand` 枚举，输出统一走 `CommandOutput` 对齐排版）：

| 子命令 | 别名 | 说明 |
|---|---|---|
| `reload [--dry-run]` | `rl` | `--dry-run` 只校验磁盘配置并报结果，**不应用** |
| `status [player]` | `st` / `info` | 只读预览一轮：昼夜/露天/辐射条/盔甲减免/腐蚀条/本轮预计伤害；控制台无参出配置摘要 + 各启用世界昼夜 |
| `force-check [player]` | `check` / `fc` | 立即跑一轮真实结算，报告实际掉血/免疫/被事件取消 |
| `corrosion <get\|set\|add> <p> [值]` | `cor/ c` | 值域钳 [0,100]；get 同时显示内存权威与 PDC 落盘值 |
| `help [子命令]` | `?` / `h` | 子命令按发送者权限裁剪，附当前生效配置一行 |

- 无参 `/rad` 直接出帮助；打错子命令给**最近似建议**（`/rad stats` → "你是不是想用 status？"）。
- 中文键名按**显示宽度**对齐（CJK/`▲▸●▓` 等占 2 列），修正了早期按字符数对齐导致的错位。
- 腐蚀读数保留两位小数（与 PDC 精度一致），阶段越阈自动改黄/红。

**控制台常规输出**：启动日志由两段超长平铺改为 3 行紧凑摘要，与 `/rad status` 控制台摘要同源
（`RadiationServices#describeFlags`），避免两处口径漂移；`reload` 成功日志同样带完整生效参数。
粒子降级细节（WIHN → WHITE_SMOKE）不再每次装配刷屏，需 `-Dradsurvival.verbose=true` 才输出。

## 关于构建混淆：已评估，**不采用**

曾尝试 ProGuard 7.6.1（rename-only 档），结论是该插件不适合混淆此产物，已回退：

1. `-dontpreverify` 不可用——Java 21（class 65）加载期强制校验 StackMapTable，缺少帧会
   `VerifyError: Expecting a stackmap frame at branch target 42` → **插件直接拒载**。
2. 内嵌 Caffeine 的私有 `*Limiter` 继承链会让 ProGuard 预校验误报
   `IncompleteClassHierarchyException`，只能靠把 `lib/*` 排除在输入外绕过。
3. 排除 `lib/*` 后必须从 **shade 后的类**（已 relocate）取输入；若用 `target/classes`，
   回灌后引用的是未重定位的 `com.github.benmanes.*` → 运行期 `NoClassDefFoundError`。
4. 即使打通，`shade` 在 `package` 末尾替换主产物，插件间阶段顺序难以稳定，
   反复出现「回灌到未混淆 jar」的竞态，收益（仅改名内部辅助类）远低于维护成本。

**结论：优先保证可用性，默认构建不做混淆。** 如需减少代码可读性，建议按需在部署侧
用成熟方案（如商业混淆器）对**独立 jar** 离线处理，并由本人完成实机验收后再上线。

## 判定链路强化（M4 二轮排查，2026-10-23）

对关键代码逐项排查后落地的 5 处修复/优化（全部有回归测试钉死，ACCEPTANCE.md §G 留档）：

1. **露天段缓存共享误差（真 bug）**：旧实现按 16³ 段缓存**布尔结论**，段内首个查询
   定格结果——同段不同眼睛高度互相误判（低眼位复用高眼位结论会把头顶真实遮挡漏判为
   露天，反之虚判遮蔽）。现缓存改存**顶遮挡高度**（自上而下扫描，首个遮挡即停），
   段内任意 eyeY 由同一扫描结果精确派生，扫描次数不变、遮蔽场景仍提前终止。
2. **HUD 陈旧镜像（真 bug）**：actionbar 原读结算镜像 `PlayerRadState`，被过滤链跳过的
   轮次不刷新（切创造仍显示 ▲▲▲▲▲ 等）。现改读 `previewRound` 实时判定快照，
   与结算语义严格一致（被过滤玩家自然显示 ●安全）。
3. **事件腐蚀值口径不一致**：`RadiationExposureEvent` 原携带**步进前**值，而 finalDamage
   用**步进后**值计算——外部监听改伤时看到的不是参与计算的数值。现事件携带步进后值，
   与倍率基数严格一致（§1.6 先累后打口径不变）。
4. **崩溃丢失最近腐蚀进度**：PDC 原只在 quit/death/reload 落盘；服务器被强杀会丢整段
   会话的结算值。现每轮值变化即同步 PDC（§2「随 player.dat 落盘」口径回归；无变化不写，
   免疫/满值玩家零写放大）。
5. **露天判定 fail-safe**：环境读取异常（区块未加载等）不再击穿结算/HUD——退回该玩家
   上一已知判定（无记录按遮蔽=安全），fallback 计数可观测、警示节流。附带修正
   `ZERO_DAMAGE` 轮次状态镜像 `lastDamage` 未清零的小瑕疵。

排查确认干净：`DayNightProvider`（floorMod/边界左闭右开）、`ArmorCalculator`（前缀×后缀
双条件归类、EnumMap 确定性求和 + clamp）、`RadiationMath`（全函数单调有界）、
`CorrosionMeter`（阶段基线/一次性标记/PDC 读写）、`RadiationMarkListener`、`PlaceholderBridge`
（类加载隔离）、`HudRenderer`（取整/颜色纯函数）。

## 露天判定异步化（plan §2 线程模型预留项，本轮落地）

主线程稳态**零方块扫描**，玩法与公式零取舍：

- **双层缓存**：权威层（TTL 60s，值为顶遮挡高度）+ 粘滞层（无过期、容量封顶）。
  段过期后的补扫派发给 `ScanDispatcher`（生产 = `BukkitScheduler#runTaskAsynchronously`，
  段级在途去重，同段多玩家共享一次扫描），当轮以粘滞旧值应答——判定**机制**与结算/伤害/
  免疫/恢复分支完全不变，仅缓存新鲜度滞后 ≤1 个结算周期（本就被 60s TTL 支配）。
- **冷启动零妥协**：玩家进入从未记录过的段时仍主线程同步扫描，**首轮判定精确度与同步
  口径一致**（新探索区域不掉首轮精度）。
- **异常安全**：异步任务只读已加载区块；恰被卸载触发 Paper 异步防护等异常一律内部吞掉
  （不发布、下轮重试），绝不反噬主线程；调度器关闭（禁用中）同样静默。
- **开关**：`async-exposure-scan: true`（默认）→ 异步管线；`false` → 全同步旧口径
  （极小地图服或想杜绝 1s 滞后可切）。带校验，reload 后随服务容器自动重建装配。
- **收益**：每结算轮每玩家的主线程成本 = 2 次缓存查找（原：过期段最多 ~390 次
  `getBlockAt`+`getBlockData` 对象分配）；HUD 每秒预览同样受益（与结算共用同一管线）。
- **其余必须主线程的操作不变**（§2 铁律）：`damage()`/事件广播/粒子音效/actionbar/
  BossBar/PDC 写入全部留在主线程；腐蚀数值本身是纯内存运算无需异步。
- 测试：`SunExposureCheckerTest#asyncRefillPipelineWithStickyValue`（冷启动同步零派发、
  过期粘滞 + 去重派发 1 个、刷新后精确新值）、`#asyncScanFailureIsContained`（异常吞掉
  不发布、在途释放、下轮自愈重试）、`PluginConfigValidationTest#asyncExposureScanSwitch`。

> 构建提示：本环境远程仓库偶发 socket 挂起（与代码无关，前文 ProtocolLib/PAPI 同源问题）。
> 依赖已齐时可用 `mvn -o -B clean package` 离线构建（本轮 188 条测试即离线全绿）。

> M3 依赖仓库实况（2026-10-18 构建环境实测）：extendedclip→PAPI 2.12.3 pom/jar 可拉（极慢，
> ~5–13KB/s）；dmulloy2→ProtocolLib 5.3.0 pom/元数据可拉但 jar 通道挂死——本地 .m2 已用
> **官方 GitHub Release 同版本产物 install-file 补装**（plugin.yml 内嵌版本核验 5.3.0），
> pom 坐标与仓库配置保持官方不变；他人环境构建若 dmulloy2 正常则直接在线解析。

## 部署

1. 将 `RadSurvival.jar`（或 `dist/RadSurvival-1.0.0-SNAPSHOT.jar`）放入 Paper 服务器 `plugins/` 目录；
2. 默认 fat jar 为自包含：**无需服务器联网**；仅 `-Plibraries-channel` 构建需要能访问 Paper 的 Maven Central 镜像；
3. 启动服务器，插件自动生成默认 `plugins/RadSurvival/config.yml`；
4. 配置非法时插件会记录 SEVERE 错误明细并自我禁用（拒绝带病服务）。

实机验收用的 `test-server/`（Paper 1.21.11-132、离线模式、RCON 25764、superflat）随仓库保留，
启动方式与 17 项人工验收清单见 [ACCEPTANCE.md](ACCEPTANCE.md) §D。

## /rad 命令速览

| 命令 | 说明 | 权限 |
|---|---|---|
| `/rad reload` | 重载 config.yml（非法时保留旧配置并回报错误；合法时**服务容器整体重建**，在线玩家腐蚀经 PDC 无缝续接；控制台可执行） | `commands.reload-permission`（默认 `radsurvival.reload`） |
| `/rad status [player]` | 只读预览一轮：昼夜阶段与 timeFactor（DayNightProvider 统一口径）、露天判定、当前腐蚀与倍率、盔甲减免、预计本轮伤害/跳过或免疫原因；**控制台/RCON 无参时输出配置摘要 + 各启用世界昼夜** | `radsurvival.use` |
| `/rad force-check [player]` | 立即对目标跑一轮**真实结算**（与主循环同一 DamageApplier 路径：事件广播→掉血）；免疫/过滤跳过时明确报告原因 | 无需管理权限（操作自身角色） |
| `/rad corrosion get\|set\|add <player> [值]` | 腐蚀条管理：set 钳 [0,100]、add 支持负数同样钳制；写后同步内存权威、状态镜像与 Player PDC（`radsurvival:corrosion`）；掉线玩家报错 | `radsurvival.admin` |

主循环：`tick-interval`（≥20，M1 校验=原版无敌帧下限）全局任务 → 过滤链（enabled-worlds 白名单 →
游戏模式 → `radsurvival.exempt` 豁免 → require-on-ground）→ 露天判定（§1.1，60s chunk 段缓存；
豁免维度在扫描前短路=非露天）→ **先腐蚀步进、后伤害结算**（§1.6/§1.3 同序）→ 减免 ≥100%
完全免疫短路（不掉血、不发事件、不播反馈）→ `RadiationExposureEvent`（可取消、可 `setFinalDamage`
改伤）→ 稳定 API 掉血。伤害来源标记 `radsurvival:source=radiation` 由 LOWEST 自有监听写在
**事件瞬态 PDC** 上（§1.3），其他插件在 NORMAL 及以后优先级即可读到。

## 数值口径勘误（M2b 拍板补充）

**`corrosion.armor-affects-accum` 保持默认 `true`**（plan.md §1.6「盔甲同样作用于累积速率……
全套下界合金既不累积也不掉血」），由此产生一处与 §1.6 生存预算表的系统性偏差，现予勘误说明：

- 预算表表头按「累积 1%/s」推导各行死亡时间，隐含**盔甲不减免累积**（= `false` 口径）——
  表中「死亡时腐蚀 ≈ 暴露秒数」（铁全 ~53s/53% 等）即为证据；
- 默认 `true` 下重甲长曝场景累积更慢、死亡更晚：**白天·铁全 ≈61s（+15%，仍在 ±20% 容差边缘）、
  白天·钻石全 ≈123s（+35%，超容差）**；裸装两口径完全等价（无甲 → ×(1−0)=1）；
- 该偏差方向与量级已被 `SurvivalBudgetRegressionTest#armorAffectsAccumDefaultDeviatesFromTable`
  钉死为回归断言；**M4 实机对照预算表时，受甲行应预期偏慢**（预算表受甲行按简化假设对待），
  若要求表实相符可改配置 `armor-affects-accum: false` 或重排表值——本期不改默认，
  因为「全下界合金零伤害零累积」是 M4 硬性验收项，只有 `true` 能同时成立；
- plan.md 本体按约定不作修改。

伤害基准不变：`base-damage: 1.0`（半心/s）、`max-multiplier: 2.0`（×2 封顶）、
夜晚 `night-multiplier: 0.5`（累积同倍率减半，timeFactor 统一口径）。

## CustomDamageMeta 延后（伤害主线 API 事实记录）

- Paper `CustomDamageMeta`（@UnstableApi）配置开关**本期不做**；伤害主线只用稳定 API 即成立：
  自有 `RadiationExposureEvent` 广播 + 事件 PDC 标记 `radsurvival:source=radiation`（LOWEST 盖章）。
- **1.21.11 API 适配事实（jar 核实，与 plan §1.3 引用的旧签名不同）**：
  `Damageable.damage(double, DamageCause)` 重载在该版本已移除（顶层 `DamageCause` 枚举亦收敛为
  `EntityDamageEvent.DamageCause` 嵌套形态）。结算改用同为稳定 API 的
  **`player.damage(amount, DamageSource)` + `DamageType.GENERIC`**（无归因外部伤害，语义最接近
  旧 CUSTOM：不携带实体/弹射物、不触发战斗逻辑）。§1.3「不被原版护甲/附魔二次减免」与无敌帧
  「每轮真实掉血」属运行期行为，MockBukkit 不模拟两者，已由 M4 实机裁决：
  - **护甲二次减免：实测无**（ACCEPTANCE.md §C.1）。穿下界合金胸甲的僵尸受 `generic 10` 扣血
    仍是 10.0，受 `magic 10` 也是 10.0；同条件 `mob_attack 4` 只扣 2.633（≈34% 减免，证明装备生效）。
    → 保持 `DamageType.GENERIC`，插件公式是唯一减免路径。
  - **无敌帧：console `/damage` 会绕过无敌帧**（连发两发均生效），无法离线测量 20 tick 窗口；
    已转为真实客户端验收项（ACCEPTANCE.md §D #13），若观察到隔轮被吞，把 `tick-interval` 改为 24
    （PluginConfig 校验下限仍为 20，无需改代码）。
- 取整口径：公式浮点原值直传 `damage()`，不取整（默认参数下半心 = 1.0 点）。

## MockBukkit 测试接线结论（plan.md §5.2）

| 事项 | 结论 |
|---|---|
| 首选坐标 `com.github.seeseemelk:MockBukkit-v1.21` | **不可用**：最新 3.133.2 钉死 paper-api 1.21.1，`mock()` 初始化内部材质标签表时引用 1.21.11 已移除条目（`minecraft:chain`）直接崩溃 `InternalTagMisconfigurationException`（实测） |
| 实际采用坐标 | `org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3`（同项目后继，2026-08 发布），与 paper-api 1.21.11 兼容；排除其 junit-jupiter-api 6.x 传递依赖，运行时钉 JUnit 5.11.4 |
| 可测（已全部进套件） | 插件装配与 tick 推进（`performTicks`）、join/quit/death 事件与 PDC 往返、过滤链（白名单/游戏模式/exempt/着地）、免疫零副作用、先累后打数值（每轮伤害 1.01→1.10 精确断言）、事件取消/改伤、LOWEST PDC 标记、命令层（status/force-check/corrosion/TabComplete/权限） |
| 不可测 → M4 实机清单 | ① `damage(double, DamageSource)` 真实实现（`LivingEntityMock` 未实现，测试经注入执行器走 `damage(double)` 记录链）；② `BlockData#isOccluding` 方块遮蔽扫描（`BlockDataMock` 未实现，露天经 `ExposureResolver` 注入，扫描内核逻辑由 M2a 单测覆盖；豁免维度短路走真路径）；③ 原版无敌帧（MockBukkit `noDamageTicks` 恒 0、连续两击都掉血，本期退化为「每轮 damage() 调用+事件链完整+血量下降」断言，见上）；④ 原版护甲/附魔对 GENERIC 的二次减免 |

## 里程碑状态

- **M1 ✅**：Maven 工程 + plugin.yml（`libraries:`/`softdepend` 接线）+ Configurate 配置加载与全量校验 + `/rad reload`、`/rad status` + 校验单元测试。
- **M2 ✅**：露天/昼夜/盔甲/腐蚀条四核心模块（M2a，纯内核+薄适配，58 条单测）+ 主循环伤害结算接线（M2b：`RadiationTask`/`DamageApplier`/`RadiationExposureEvent`/会话与标记监听器/`RadSurvivalApi` 门面/命令完善/腐蚀 PDC 持久化，MockBukkit 48 条集成测试；19+58 既有测试全保留全绿）。
- **M3 ✅**：反馈层与对外 API——`CorrosionStageChangeEvent`（包装 `CorrosionMeter` 内存阶段回调；Cancellable=仅抑制播报、不改数值）+ `HudRenderer`（纯函数双条：白天 ▲×5/夜晚 timeFactor 比例/遮蔽 ●安全/减免≥1 ◆免疫；腐蚀条 10 格取整、normal 白/warn 黄/danger 红）+ `FeedbackService`（actionbar 独立 1s 任务、阶段跳变 title+音效、首次 warn 附规则说明、受击 WHITE_SMOKE 粒子+低功耗音效、BossBar 常驻腐蚀条、reload 重建无泄漏、onDisable 全清）+ `DamageApplier.DamageFeedbackHook` 受击挂点（掉血归因记录反馈全关也写入）+ `DamageTagListener` 死亡文案（时间窗+GENERIC 无实体归因双判据，非辐射死因不误改）+ `RadSurvivalExpansion`/`PlaceholderBridge`（%radsurvival_exposure/reduction/timemul/corrosion%，PAPI 缺失零加载零报错）+ ProtocolLib 可选红闪抑制（`PacketType.Play.Server.ENTITY_STATUS`，5.3.0 编译核实；缺失/异常留桩降级）。新配置键 `feedback.bossbar-corrosion`(默认 false)、`feedback.death-message`(默认 true)、`feedback.protocol-lib`(auto|true|false) 全部进 config.yml 且带校验。38 条新测试，既有 125 条零回归。
- **M4 ✅**：实机验收（`test-server/` + Paper 1.21.11-132 + RCON，完整证据见 [ACCEPTANCE.md](ACCEPTANCE.md)）：
  163 条测试全绿；插件实机启用/生成配置/坏配置 reload 拒载/控制台 status/onDisable 全部通过；
  **`libraries:` 通道因 Central 镜像不可达实测失败 → 默认构建改 shade+relocate（thin 通道以
  `-Plibraries-channel` 保留）**；GENERIC 不被原版护甲二次减免已实测裁决；无敌帧与 HUD/播报/
  腐蚀保留/预算表计时等玩家侧项目转 17 项人工手册（§D）。期间另补 `/rad status` 控制台摘要。
- **v1.1 ✅**：判定链路加固 + 真实客户端复验（188 条测试全绿）——M4 的 §C.1 裁决被**推翻**：
  用裸僵尸测出的"GENERIC 不吃护甲"实为空甲的巧合，真实行为是**照常减免**。由此修掉四处真实缺陷：
  ①双重减免与安全区伤害被吞成 0（改为结算瞬间摘甲/还甲，同一 `ItemStack` 实例，关停兜底）；
  ②外部伤害与太阳伤害无法区分（`SunMark` 时间+金额窗口 + 四条件 `classify`，新增
  `RadiationExposureEvent#getDamageChannel()` 暴露 `SUN`/`EXTERNAL`）；③reload 后旧反馈实例
  仍收回调（`hookRef`/`stageRef` 成对解绑）；④占位符 reload 后不刷新（先卸旧再注册）。
  另移除会禁用服务端 re-configuration API 的 `PlayerLoginEvent` 监听。
  已用真实 1.21.11 客户端跑通：露天结算、腐蚀累加、夜晚 ×0.5、死亡文案、`/rad reload` 续接、
  铁甲玩家外部伤害按原版减免且护甲完好。
