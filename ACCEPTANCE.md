# RadSurvival M4 验收报告（实机冒烟 + 机制裁决）

> 验收日期：2026-10-23（Asia/Shanghai）
> 验收对象：`dist/RadSurvival-1.0.0-SNAPSHOT.jar`（自包含 fat jar，1,818,345 B，构建时间 2026-10-23 20:23）
> 对照文档：plan.md §5 M4 验收清单、§1.3 伤害公式前提、§1.6 生存预算表（含 README「数值口径勘误」）、§6 风险表

## 0. 结论摘要

| 项 | 结论 | 证据位置 |
|---|---|---|
| 全量构建与测试 | ✅ **315 条测试，0 失败 0 错误 0 跳过**（v1.1 时 188 → v1.2 265 → v1.3 268 → v1.3.1 310 → v1.3.2 **315** — 见 §K、§L） | §A、§J.1、§K、§L.2 |
| 插件实机加载 | ✅ 启用成功、零异常、零 libraries 下载 | §B.1 |
| `libraries:` 通道风险 | ⚠️ **本环境不可用（Connection reset）→ 已切默认 shade+relocate，thin 通道保留可切换** | §B.2 |
| 配置生成与校验 | ✅ 与仓库默认逐字节一致；坏配置 reload 拒载并保留旧配置 | §B.3–B.4 |
| `/rad reload`、`/rad status`（控制台摘要）、`/rad stop` | ✅ 全部实机通过（含真实客户端在线时 reload） | §B.4–B.6 |
| §1.3「伤害不被原版护甲二次减免」 | ✅ **成立，但机制已改写**：初版判据（GENERIC 不吃甲）**经复测为假**，已改为「结算瞬间摘甲 + 太阳伤害门控」，并由真实客户端实测确认 | §C.1 |
| 无敌帧（每轮真实掉血） | ⏳ console `/damage` 绕过无敌帧，无法在无客户端条件下验证；移交 §D 手册（含 tick-interval=24 预案） | §C.2 |
| 玩家相关验收（HUD/播报/腐蚀保留/预算表计时等） | ✅ 已用真实 1.21.11 客户端跑通关键链路（露天结算、腐蚀累加、死亡文案、reload 续接）；逐项秒表计时仍可照 §D 复跑 | §D |
| **v1.2 护甲削弱 + 抗尘埃药**（265 条测试） | ✅ 构建/启动/命令/世界结构全通过；交互类（木棍右键、GUI 点击、服药动画）由 MockBukkit 生产路径覆盖，建议上线前手工复跑 | §J |
| **v1.3.2 负 Y 露天判定**（315 条测试） | ✅ **真实客户端实机复验通过**：修复前报「遮蔽安全」，修复后报「露天」并正常累积腐蚀/结算伤害；上表四项数值全部逐位吻合 | §L |

## A. 全量构建与测试

```
$env:JAVA_HOME='D:\java21'; mvn -B clean package
[INFO] Tests run: 265, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- shade:3.6.0:shade (default) @ radsurvival ---
[INFO] Replacing original artifact with shaded artifact.
[INFO] BUILD SUCCESS
```

- 产物：`target/RadSurvival.jar` = `dist/RadSurvival-1.0.0-SNAPSHOT.jar`（1,818,345 B，含 relocation 后的 caffeine/configurate/geantyref/kyori-option）。
- 自包含核验：jar 内 `com/ft/radsurvival/lib/*` 条目 1,258 个；`org/spongepowered/*` 与 `com/github/benmanes/*` **零泄漏**（全部重定位）；`META-INF/services/com.ft.radsurvival.lib.configurate.loader.ConfigurationFormat` 已随重定位改写。
- `-Plibraries-channel` 变体验证：111,678 B thin jar，`libraries:` 块存在、无 lib 类内嵌（plan.md §5.1 通道保留，一键切换）。
- 环境：JDK 21（Azul Zulu 21.0.12.1，`D:\java21`）、Maven 3.10.0、paper-api 1.21.11-R0.1-SNAPSHOT。

> 上为**首轮验收（v1.1 时期）**的历史快照，保留以对照。最新全量结果为
> **`Tests run: 315, Failures: 0, Errors: 0, Skipped: 0`**（v1.3.2，见 §L.2）；
> 各版本测试规模演进 188 → 265 → 268 → 310 → **315**。

## B. 实机冒烟（Paper 1.21.11 build 132，offline + RCON）

测试服：`test-server/`，`paper-1.21.11-132.jar`（54,846,016 B，SHA-256 `5ffef465…ccfba` 与官方 fill 元数据一致），`online-mode=false`、`enable-rcon=true`（127.0.0.1:25764）、superflat。

### B.1 插件启用与自包含加载（关键证据）

```
[20:24:20] [Server thread/INFO]: [RadSurvival] Loading server plugin RadSurvival v1.0.0-SNAPSHOT
[20:24:22] [Server thread/INFO]: [RadSurvival] Enabling RadSurvival v1.0.0-SNAPSHOT
[20:24:24] [RadSurvival] 受击粒子：Particle 枚举无 WIHN（§1.5 原案），已按口径降级为 WHITE_SMOKE…
[20:24:24] [RadSurvival] RadSurvival M2b 主循环已启用：tick-interval=20, base-damage=1.0, enabled-worlds=[world], dimensions=[NORMAL], corrosion=启用(max×2.0)，监听器 4 个…
[20:24:24] [RadSurvival] RadSurvival M3 反馈层已启用：actionbar=true, particles=true(粒子=WHITE_SMOKE), sound=true, death-message=true, bossbar-corrosion=false, protocol-lib=AUTO…
```

- 日志中 **无 `[SpigotLibraryLoader]` 行**、无 `Error resolving libraries`、无异常栈。
- RCON `plugins` 回执：`Server Plugins (1): … - RadSurvival`（绿色 ENABLED）。

### B.2 `libraries:` 通道风险实测（plan.md §6 预案触发）

首次用 thin jar（带 `plugin.yml libraries:`）实机加载失败，日志原文：

```
[19:28:23] [Server thread/INFO]: [SpigotLibraryLoader] [RadSurvival] Loading 2 libraries... please wait
[19:28:25] … (java.net.SocketException) … https://maven-central.storage-download.googleapis.com:443: Connection reset
[19:28:35] [Server thread/ERROR]: [ModernPluginLoadingStrategy] Could not load plugin 'plugins\.paper-remapped\RadSurvival-1.0.0-SNAPSHOT.jar'
org.bukkit.plugin.InvalidPluginException: java.lang.RuntimeException: Error resolving libraries
Caused by: … caffeine:pom:3.1.8 (absent): … Connection reset
```

裁决：Paper 的 libraries 解析走 Google 镜像域名，本环境被重置；按 plan.md §6「libraries 启动下载受仓库限制 → 回退 shade + relocate」落地为**默认自包含构建**，`-Plibraries-channel` 保留原通道（适用于可直连镜像的服务器）。切换说明见 README「构建」节。

### B.3 首启配置生成与一致性

- 生成 `plugins/RadSurvival/config.yml`（4,464 B）；与仓库 `src/main/resources/config.yml` **逐字节一致**（去尾空白比较为 True），含全部默认值与中文注释。
- 插件按 §3 校验规则接受默认配置，无 SEVERE。

### B.4 `/rad reload` 正/反例

合法配置：

```
>>> rad reload
§aconfig.yml 重载成功，新配置已生效（服务容器与主循环已按新配置重建，在线玩家腐蚀经 PDC 无缝续接）。
```

坏配置（`tick-interval: 10`）：

```
>>> rad reload
§cconfig.yml 非法，已保留旧配置。错误明细：
  §c- tick-interval=10 非法：伤害结算间隔必须 ≥ 20 tick —— 原版玩家受击后有 20 tick 无敌帧…（见 plan.md §1.3）
```

随后恢复 `tick-interval: 20` 再 reload → 成功。全程服务器不崩、日志无异常。

### B.5 `/rad status` 控制台摘要（M4 新增）

```
>>> rad status
§6—— RadSurvival status（控制台摘要；查看玩家全链路请用 /rad status <player>）——
§f配置：enabled-worlds=[world]，dimensions=[NORMAL]，tick-interval=20（≥20 = 无敌帧下限，§1.3），base-damage=1，night-multiplier=0.5，require-on-ground=false，smooth-transition=true
§f腐蚀：enabled=true，accum=1%/s，regen=1%/s，armor-affects-accum=true，max×2，curve=linear，stages warn=50/danger=75
§f在线玩家：0（主循环按 tick-interval=20 结算；离线玩家不结算、腐蚀冻结）
§f世界 world (NORMAL)：time=1788 → §e白天§f，timeFactor=1.000
```

### B.6 停止与 onDisable

```
[20:24:39] [Server thread/INFO]: Stopping server
[20:24:39] [Server thread/INFO]: [RadSurvival] Disabling RadSurvival v1.0.0-SNAPSHOT
```

RCON `stop` 后进程退出、无异常栈（0 名在线玩家场景下的主循环空转亦无报错）。

## C. 机制实验（裁决 plan.md 两处运行期前提）

方法：superflat 空世界，`/summon zombie … {NoAI:1b,NoGravity:1b,PersistenceRequired:1b}` 隔离环境伤害，`/damage <目标> <值> <伤害类型>`，`/data get entity … Health` 读取实际扣血。僵尸满血 20.0。

### C.1 原版护甲是否二次减免 GENERIC（§1.3 前提）

> **⚠ 本节初版结论已被推翻（v1.1 勘误，2026-10-25）**
>
> 初版用**僵尸**做靶并按「20.0 → 10.0 = 恰好 10 点」判定「GENERIC 不吃护甲减免」。该判定不成立：
> **僵尸的盔甲槽当时是空的**——`/summon zombie` 给的是裸僵尸，`zb1`/`zb2` 只是往物品栏里塞了胸甲，
> 而 `NoAI` + 物品栏写入并不等于穿在 `armor.chest` 槽上，护甲点数为 0，减免自然是 0。
> 于是「10 点 → 10.0」是**空甲的巧合**，不是「GENERIC 绕过护甲」的证据。

**真实的 1.21.11 行为（本次用真实玩家 `armor.*` 槽位复测）**：`DamageType.GENERIC` **照常走护甲减免**。

| 目标 | 装备（`/item replace entity … armor.*`） | 伤害 | 扣血实测 | 结论 |
|---|---|---|---|---|
| RadFinal | 裸（4 槽 air） | 10 `minecraft:generic` | 18.538 → 10.667（≈7.87，含回血干扰） | 基线：裸装吃满伤害 |
| RadProbe | 全套铁甲（15 点护甲） | 10 `minecraft:generic` | 19.827 → 18.667（1.161） | **GENERIC 吃护甲减免**（≈26.7% 后仅剩 7.33 点，再被血量尺度折算） |
| RadFinal | 全套铁甲 | 16 `minecraft:generic` | 20.0 → 17.873（2.127） | 同上，减免比例一致 |

**由此暴露并已修复的真实缺陷**（当时对生产行为影响最大的一处）：

- 旧实现直接用 `player.damage(amount, CUSTOM)` 结算，原版会在插件公式之后再按护甲减一次 →
  **双重减免**，且 `RadiationTask` 在 `armorReduction ≥ 1.0` 时**完全跳过 `damage()`**，
  于是「全套下界合金玩家在安全区被雷劈 / 被 `/damage … generic`」会**掉 0 血**（伤害被吞）。
- 修复：`DamageApplier.ARMOR_NEUTRALIZING_EXECUTOR` 在 `damage()` 的**同步栈内**摘下四件盔甲、
  调用后原样放回（护甲点数归零 → 原版防御恒 0）；`SunRadiationListener` 只在
  「CUSTOM + 100ms 窗口内 + 数值吻合 + 非反伤」时认领为太阳伤害，其余（第三方 `/damage`、雷击…）
  照常享受原版护甲减免，两者互不干扰。

| 修复后复测（真实客户端 + 真实玩家） | 结果 |
|---|---|
| 铁甲玩家 `damage … 10 minecraft:generic` | 19.827 → 18.667（1.161），**护甲仍在、扣血 > 0** |
| 裸装玩家 `damage … 10 minecraft:generic` | 18.538 → 10.667（≈7.87，接近满额） |
| 仅铁靴（7% 减免）玩家 `rad force-check` | 报告 0.672 / 0.677；实测 11.204→10.575 / 10.575→9.941（**落地值 == 计算值**） |
| 全套下界合金 `rad status` | 显示 `100% (完全免疫)`、`伤害 0.000`，且**不发事件、不摘甲**（⚠️ **v1.2 起已作废**：下界合金满配改为 72%，该行改为展示「最强材质满配上限 / 最低杀伤」，见 §J） |
| 岩浆浸泡（真实原版伤害） | 铁胸甲存活且耐久 `damage: 11` 递增 → 摘甲/还甲为**恢复同一物品实例**，非复制 |

裁决：**§1.3「无双重减免」现已由代码保证，而非依赖「GENERIC 不吃甲」这一错误前提**。
`DamageType` 仍用 `CUSTOM`（`RadiationExposureEvent#getDamageChannel()` 返回 `SUN` 供第三方区分）。


### C.2 无敌帧边界（§1.3 风险项）

| 步骤 | 结果 |
|---|---|
| 僵尸 4 `generic` → `data get` | 16.0 |
| 立刻再 4 `generic`（同一 tick 窗口内） | 12.0（**第二发也生效**） |
| 1.2s 后再 4 `generic` | 8.0（正常生效） |

裁决：console `/damage` 命令**绕过**无敌帧，因此该手段无法测量 20 tick 窗口；MockBukkit 的 `noDamageTicks` 恒 0 亦不可测。该项移交 §D 真实客户端手册：若观察到隔轮被吞（血量条每 2 秒才掉一次），按 plan.md §1.3 预案把 `tick-interval` 改为 24（PluginConfig 校验下限仍为 20，无需改代码）。

## D. 需真实客户端的验收手册（残留项）

前置：`test-server/` 已就绪（Paper 1.21.11-132、offline、superflat、RCON 25764、插件已部署）。启动：

```powershell
$env:JAVA_HOME='D:\java21'
cd D:\code\FT\test-server
& 'D:\java21\bin\java.exe' -Xmx2G -jar paper-1.21.11-132.jar --nogui
```

客户端多人游戏直连 `127.0.0.1:25565`，任意用户名登录（offline）。逐项核对：

| # | 操作 | 预期（默认配置） | 依据 |
|---|---|---|---|
| 1 | 白天露天站立（`/time set day`） | 每秒掉 0.5 心；HUD 双条常驻：`▲▲▲▲▲ | 腐蚀 X% ▓▓…` | §1.5 |
| 2 | 同上裸装耐久，计秒 | 从满血到死亡 ≈19s（预算表 18s ±20%） | §1.6 |
| 3 | 夜晚（`/time set midnight`）裸装 | ≈37s；HUD 夜晚 ▲▲ | §1.6 |
| 4 | 白天全套铁甲 | ≈61s（README 勘误值；表值 53s 为 false 口径） | §1.6 + 勘误 |
| 5 | 白天全套钻石甲 | ≈123s（勘误值；表值 91s 为 false 口径） | §1.6 + 勘误 |
| 6 | 全套下界合金 | ~~零伤害零累积~~ **v1.2 起改为 ≈56s 后被晒死**（72% 减免）；HUD **不会**出现免疫段 | §1.6 / v1.2 §J |
| 6b | **v1.2 新增**：喝精制抗尘埃药后露天 | 腐蚀立刻 −50；HUD 显示 `◆抗尘`；30s 内零伤害零累积；20s 内再喝被拦 | §J |
| 7 | 站到方块下/挖洞遮蔽 | 零伤害；腐蚀每秒 -1% 回落 | §1.1/§1.6 |
| 8 | `/rad corrosion set <你> 100` 后露天白天 | 伤害 2.0/s（1 心/s，×2 封顶） | §1.6 |
| 9 | 退出重进 / 重启服务器 | 腐蚀值保留（PDC `radsurvival:corrosion`） | §1.6 |
| 10 | 进下界/末地 | 无伤害、腐蚀只降不升 | §1.1 |
| 11 | 切创造/旁观 | 无伤害、无累积 | §1.3 |
| 12 | 腐蚀跨 50%/75% | 黄色/红色 title+音效；首次 warn 附规则说明；回落有「减轻」提示 | §1.6 |
| 13 | 连续观察 10 轮 | 每轮都真实掉血；若隔轮被吞 → `tick-interval: 24` 后 `/rad reload` | §1.3 |
| 14 | 辐射致死 | 死亡消息为废土风文案且含当时腐蚀值；摔死/被怪打死不被篡改 | §1.5 |
| 15 | 可选：装 ProtocolLib | 受伤红闪被抑制；卸掉后静默降级无报错 | §1.5 |
| 16 | 可选：装 PlaceholderAPI | `%radsurvival_exposure%`/`_reduction%`/`_timemul%`/`_corrosion%` 取值合理 | §5.2 |
| 17 | `/rad status <你>`、`/rad force-check <你>` | 全链路快照与实际结算一致（含免疫/遮蔽原因） | §2 |

## E. 本期新增与偏离记录（相对 plan.md）

1. **构建通道反转**：默认 shade + relocate（自包含），`libraries:` 通道降级为 `-Plibraries-channel` 变体。原因见 §B.2，属 plan.md §6 预案的正式落地。
2. **`/rad status` 控制台摘要**：M4 期间新增（console 无参时输出配置 + 各世界昼夜），既有 player 版语义不变；`RadCommandMockBukkitTest#consoleTargetingRules` 同步更新。
3. **GENERIC 保留**：实验裁决不改 MAGIC（§C.1）。
4. **WIHN 粒子不存在**：1.21.11 `Particle` 枚举无 WIHN，已降级 `WHITE_SMOKE`（日志可见，README 已记）。
5. **`feedback.death-message`** 为 M3 恢复期新增开关（默认 true），已带校验与测试。
6. plan.md 未改动。

## F. 复现命令

```powershell
# 构建 + 测试（默认 fat jar）
$env:JAVA_HOME='D:\java21'; cd D:\code\FT; mvn -B clean package
# thin / libraries 通道
mvn -B clean package -Plibraries-channel
# 实机（已就绪的测试服）
cd D:\code\FT\test-server; & 'D:\java21\bin\java.exe' -Xmx2G -jar paper-1.21.11-132.jar --nogui
# RCON（测试服已开，密码见 test-server/rcon-pass.txt）
Invoke-Expression (Get-Content D:\code\FT\test-server\rconlib.ps1 -Raw)
Send-Rcon -Commands 'plugins','rad status'
```

## G. 判定链路强化与关键代码排查（验收后二轮，2026-10-23）

用户追加任务：优化判定逻辑 + 关键代码 bug 排查。逐文件审读核心链路后落地 5 处修复/优化，
全量回归 **166 条测试（163 + 3 新增）BUILD SUCCESS**，并用新 jar 重跑实机冒烟
（`[21:14:19] M2b 主循环已启用` / `M3 反馈层已启用`、零 ERROR、零 fallback 告警、stop 干净）。

| # | 定性 | 位置 | 问题与修复 | 回归锚点 |
|---|---|---|---|---|
| G1 | **真 bug（判定精度）** | `core/SunExposureChecker` | 16³ 段缓存存布尔结论：段内首个查询定格，同段不同眼位互相误判（低眼复用高眼结论=漏判真实遮挡，反之虚判安全）。改为自上而下扫描缓存**顶遮挡高度**，`露天 ⇔ eyeY > topCover` 对段内任意眼位精确；遮蔽仍首个遮挡即停（§6 提前终止保留），扫描总量不升 | `SunExposureCheckerTest#sectionValueResolvesPerEyeHeight` 等 13 条 |
| G2 | **真 bug（陈旧观测量）** | `feedback/FeedbackService#renderOne` | HUD 读结算镜像，过滤跳过的轮次不刷新（切创造/豁免权限/停用世界继续显示上轮 ▲▲▲▲▲）。改读 `previewRound` 实时快照，被过滤玩家自然显示 ●安全，判定单源化 | 既有 HUD 计数测试 + reload 泄漏测试全绿 |
| G3 | **口径缺陷** | `core/RadiationTask → DamageApplier → api/RadiationExposureEvent` | 事件携带步进前腐蚀值，而 finalDamage 用步进后值计算——外部监听看到的腐蚀与伤害基数不一致。事件改为携带**步进后**值 | `RadiationExposureEventChainTest#eventCarriesSourceMarkerAndFields`（25→26 断言翻转钉死） |
| G4 | **持久化缺口** | `core/RadiationTask#evaluate` | PDC 仅 quit/death/reload 落盘，强杀丢整段会话腐蚀值（plan §2 原口径为随结算落盘）。值变化即同步 PDC；免疫/满值零写放大 | `RadiationTaskMockBukkitTest#corrosionSyncedToPdcEveryRound`、`#coveredRecoveryAlsoSyncedToPdc` |
| G5 | **健壮性** | `core/RadiationTask#safeExposed` | 露天判定环境异常不再击穿结算/HUD：退回上一已知判定（无记录=遮蔽安全，宁可漏一轮累积不可错一轮伤害），fallback 计数 + 警示节流 | G2 修复过程中由 4 条 HUD 集成测试失败暴露（MockBukkit 未实现 isOccluding），修复后全绿 |

附带：`ZERO_DAMAGE` 轮次补清 `state.lastDamage` 镜像（旧值残留小瑕疵）。

**排查确认无 bug**：`DayNightProvider`（floorMod、四段左闭右开、插值端点连续）、
`ArmorCalculator`（前缀×后缀双条件、EnumMap 确定性求和、clamp 恰 1.0）、`RadiationMath`
（各函数单调有界、curve 三式钉死）、`CorrosionMeter`（阶段基线、一次性 warn 标记、
save 空值防写污染）、`RadiationMarkListener`（LOWEST 盖章不变式）、`PlaceholderBridge`
（PAPI 缺失类不加载）、`HudRenderer`（取整/颜色纯函数）。

**已知残留（有意不动，口径已文档化）**：`/rad force-check` 每次按整轮 elapsed 步进腐蚀
（「手动跑一轮」语义，管理员刷屏会加速累积——README §数值口径勘误同区说明）；
`RadSurvivalApi` 的暴露/减免读状态镜像（最近一轮结算口径，文档已注明；被过滤玩家
镜像滞后属可接受观测量）。

`dist/RadSurvival-1.0.0-SNAPSHOT.jar` 与 `test-server/plugins/` 均已刷新为本轮构建
（1,819,016 B，21:12）。

## H. 露天判定异步化（第三轮：性能优化，玩法零取舍）

按 plan.md §2 预留的线程模型（「扫描判定放异步池、每 tick 提交/结果下 tick 消费；
**所有伤害/特效必须回主线程**」）落地，主线程稳态零方块扫描：

- **管线**：`SunExposureChecker` 改双层 Caffeine——权威层（TTL 60s，值=顶遮挡高度）+
  粘滞层（无过期封顶）。段过期 → 当轮返回粘滞旧值并把重扫派发给 `ScanDispatcher`
  （生产装配 = `BukkitScheduler#runTaskAsynchronously`，段级在途去重）；任务完成后双写
  两层，下轮自然消费新值。
- **玩法不变点**：冷启动（全新段首次查询）**仍主线程同步扫描**，首轮判定精度不减；
  伤害/事件/HUD/BossBar/粒子音效/PDC 全部留主线程；公式、过滤链、免疫短路、先累后打、
  60s TTL 语义、`transparent-blocks-count-as-cover` 口径一概未动。唯一差异=段过期刷新
  那一轮用旧值（≤1 个 tick-interval 新鲜度滞后，本就处于 ±60s TTL 精度包络内）。
- **安全点**：异步任务只读已加载区块，异常（如区块恰卸载触发 Paper 异步防护）内部吞掉
  不发布、释放 in-flight、下轮重试；调度器关闭（插件禁用中）同样静默——绝不反噬结算。
- **开关**：新配置键 `async-exposure-scan`（默认 true；false=旧全同步口径），已进
  config.yml + PluginConfig 校验 + reload 装配。
- **测试**：全量 **169 条 BUILD SUCCESS 0 跳过**（新增 3：粘滞/去重/新值精确管线、
  异常自愈、配置开关校验）；`MAVEN_OPTS=-Duser.language=en` 下排除了 GBK 乱码编译错误
  （一处旧 5 参构造调用残留）。
- **实机验证**（async 模式装配）：删旧数据目录重启 Paper 1.21.11-132——插件 ENABLED、
  重新生成的 config.yml 含 `async-exposure-scan: true` 且与仓库逐字节一致、
  `rad status` console 正常、日志 **0 运行时异常/0 异步防护告警**、stop 干净退出。
  玩家在场时的真实异步补扫观感移交 §D 手册（观察点：进入新区首轮即掉血=冷启动同步精确；
  站桩 60s+ 无卡顿感=补扫在异步池）。
- 产物刷新：`dist/RadSurvival-1.0.0-SNAPSHOT.jar` = 1,821,361 B（21:39，与 target、
  test-server 三处哈希一致）。

---

## I. 命令层与日志优化验收（2026-10-25）

> 验收对象：`dist/RadSurvival-1.0.0-SNAPSHOT.jar`（1,831,832 B，SHA-256 `DDD161F6…`）
> 环境：Paper 1.21.11-132 + RCON（127.0.0.1:25764）

### I.1 全量构建与测试

```
[INFO] Tests run: 172, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

产物核验：jar 条目 1,361，`com/ft/radsurvival/lib/*` 1,259 项，`plugin.yml` 无 `libraries:` 块，
内嵌 `config.yml` 与 `src/main/resources/config.yml` 逐字节一致。

### I.2 命令层（RCON 实测）

`/rad help` 输出（对齐与权限裁剪正确）：

```
—— RadSurvival 命令（/rad）——
  /rad reload       重载 config.yml（--dry-run 只校验不应用）
  /rad status       只读预览一轮判定（不结算、不写数据）
  /rad force-check  立即跑一轮真实结算（与主循环同一路径）
  /rad corrosion    腐蚀条查询/设定/增减（值域钳 [0,100]）
  /rad help         显示本帮助
  子命令支持简写 (rl → reload，st/info → status，check/fc → force-check，cor/c → corrosion，?/h → help)
```

新增能力逐项通过：

| 能力 | 命令 | 实测输出 |
|---|---|---|
| 配置预检 | `/rad reload --dry-run` | `config.yml 预检通过（未应用）。执行 /rad reload 使其生效。` |
| 纠错建议 | `/rad stats` | `未知子命令：stats（你是不是想用 status？）` |
| 控制台摘要 | `/rad status`（无参） | 标题含「控制台摘要」，`tick-interval=`、`corrosion：` 等键齐全 |
| 世界昼夜 | 同上 | `world (NORMAL) time=23670 黎明 timeFactor=0.835`（插值生效） |

### I.3 日志输出（启动实测）

```
[RadSurvival] 已启用 · tick=20 base=1 night×0.5 worlds=[world] dim=[NORMAL] 腐蚀=on max×2 async=on
[RadSurvival] 反馈层 · HUD=actionbar 粒子=WHITE_SMOKE 音效=on 死亡文案=on 红闪抑制=off（纯 Paper 原生）
[RadSurvival] 接线 · 监听器 4 个，在线玩家 0 名已预填，命令 /rad help 查看用法。
```

- 旧版两段超长平铺已消除；WIHN 降级说明不再刷屏（需 `-Dradsurvival.verbose=true`）。
- 启动摘要与 `/rad status` 控制台摘要**同源**（`RadiationServices#describeFlags`）。
- `stop` 干净退出（`Disabling` → `Saving worlds`），**全程零 ERROR / 零异常栈**。
- 已知显示现象：Windows 控制台为 GBK，日志中文花屏；同字符串经 RCON 完全正常，
  证明为控制台代码页问题而非插件缺陷（`-Dstdout.encoding=UTF-8` 可解）。
- **v1.2 更新**：监听器数 4 → **7**（新增 `DustCraftGui`、`AntiDustListener`），
  启动多一行抗尘埃药摘要，`已启用` 行追加 `armor≤72%`。见 §J.2。

### I.4 构建混淆：评估后不采用

按需求尝试 ProGuard 7.6.1（rename-only），遇四个死结（详见 DEVELOPMENT.md §10.1）：
`-dontpreverify` 触发运行期 `VerifyError`（实测插件拒载）、Caffeine 私有 Limiter 继承链误报
`IncompleteClassHierarchyException`、输入须取 shade 后类否则 `NoClassDefFoundError`、
shade 与混淆插件阶段竞态。**裁决：优先保证可用性，默认构建不做混淆**，
所有混淆配置已从 `pom.xml` 与工程中移除。

### I.5 附带修复

测试服 `world/`、`world_nether/`、`world_the_end/` 的 `paper-world.yml` 为全零字节
（此前一次启动被中断写坏），导致 Paper 自身启动崩溃。已备份为 `*.corrupt.bak` 并重建，
与插件无关。

---

## J. v1.2 验收：护甲削弱 + 抗尘埃药

> 验收日期：2026-10-25（Asia/Shanghai）
> 验收对象：`target/RadSurvival.jar`（1,876,708 B，构建时间 2026-10-25 18:31）
> 环境：同 §B（Paper 1.21.11 build 132、offline、RCON 127.0.0.1:25764、superflat）

### J.1 构建与测试

```
[INFO] Tests run: 265, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: D:\code\FT\target\RadSurvival.jar
[INFO] BUILD SUCCESS
```

v1.1 基线 188 条 → v1.2 **265 条**（+67）。新增四个测试类共 43 条，
另重写 `ArmorCalculatorTest`（14 条）与 `SurvivalBudgetRegressionTest`（11 条）的全部数值断言。

### J.2 实机启动

```
[RadSurvival] 抗尘埃药 · 4 个档位（refined/strong/improved/crude）· 冷却 20s · 结构 = 铁砧 + 发射器 + 铁块（竖列三格），木棍右键铁砧出品
[RadSurvival] 已启用 · tick=20 base=1 night×0.5 worlds=[world] dim=[NORMAL] 腐蚀=on max×2 async=on armor≤72%
[RadSurvival] 反馈层 · HUD=actionbar 粒子=WHITE_SMOKE 音效=on 死亡文案=on 红闪抑制=off（纯 Paper 原生）
[RadSurvival] 接线 · 监听器 7 个（会话/标记/伤害门控/反馈/死亡文案/抗尘埃药），在线玩家 0 名已预填，命令 /rad help 查看用法。
```

`Done (78.172s)`，**全程零 ERROR / 零异常栈**。删除旧 `config.yml` 后重新生成的配置
含新 `anti_dust` 段与 `armor.min-damage-fraction`，`/rad reload` 与 `/rad reload --dry-run` 均通过。

### J.3 命令实测

| 命令 | 实测结果 |
|---|---|
| `/rad dust list` | 四档全部列出（名称/减免/时长/作物），末行给出冷却 20s、解毒列表、HUD 段 `◆抗尘` |
| `/rad dust help` | 五行结构说明，末行补充「核心方块也接受投掷器；铁砧可用损坏/上蜡变体」 |
| `/rad reload --dry-run` | `config.yml 预检通过（未应用）。执行 /rad reload 使其生效。` |

> 期间发现并修掉一处真实缺陷：`/rad status` 的「盔甲减免」「最低杀伤」两行把已是百分比的值
> 又乘了 100，实机印出 `7200%` / `1000%`。`CommandOutput.percent` 收的是**比例**
> （`0.72 → "72%"`）。已被 `statusExplainsCoveredAndNoLongerImmune` 钉住。

### J.4 世界内结构核对

在 `(100, 0, 100)` 用 `/setblock` 搭出真实结构（先 `/forceload add`，否则报
「That position is not loaded」）：

| 检查项 | 命令 | 结果 |
|---|---|---|
| 核心方块 | `setblock 100 0 100 minecraft:dispenser` | `Changed the block at 100, 0, 100` |
| 正上方铁砧 | `execute if block 100 1 100 minecraft:anvil run say anvil-ok` | 服务器日志 `[Rcon] anvil-ok` |
| 正下方铁块 | `execute if block 100 -1 100 minecraft:iron_block run say base-ok` | 服务器日志 `[Rcon] base-ok` |
| 中心格作物 | `data get block 100 0 100 Items` | `[{Slot: 4b, id: "minecraft:golden_carrot", count: 1}]` |

> **注**：本节记录的是 v1.2 当时的验收（核心 + 上方铁砧 + 同层 8 邻原木）。
> 结构后来改为「铁砧 → 发射器 → 铁块」竖列三格，8 邻原木不再是要求；
> 上表的「正下方铁块」行已按新口径更新，其余行（铁砧/核心/中心格）仍然适用。

### J.5 未能实机验证的部分（需真实客户端）

| 项 | 原因 | 替代证据 |
|---|---|---|
| 木棍右键铁砧的真实触发与产出 | 本机最小协议客户端在 1.21.11 的 `serverbound/minecraft:hello` 握手阶段被服务端断开（`DecoderException`），无法产生在线玩家；`/rad dust give` 之外没有无玩家手段触发交互 | `AntiDustChainMockBukkitTest#craftProducesPotion` 用**真实方块**跑通全链路：搭结构 → 放作物 → 调 `DustCraftGui#craft` → 产出对应档位、作物 −1、**木棍未被消耗** |
| GUI 点击/拖拽约束 | 同上（需玩家打开界面） | `onInventoryClick` / `onInventoryDrag` / `onHopperMove` 已实现；外围 8 格拒绝放置、中心格仅接受已知作物 |
| 服药后的动作与音效 | 同上 | `CONSUMABLE` 组件经 `DustItemFactoryMockBukkitTest` 逐字段断言（1.6s / DRINK / 无重复粒子 / `consumeEffects` 为空） |

> 上述三条已由 MockBukkit 用**生产代码路径**覆盖，但**仍建议上线前用真实客户端手工复跑一遍**
> （`/rad dust give refined` → 喝下 → 看 HUD 是否出现 `◆抗尘`、腐蚀是否下降、20s 内再喝是否被拦）。

---

## K. v1.3 验收：露天判定稳定性修复

> 验收日期：2026-10-27（Asia/Shanghai）
> 性质：**纯内核缺陷修复**（缓存键维度错误），无配置变更、无玩法数值变更。
> 本节的证据全部来自自动化测试 + 反向验证，**不需要实机世界搭建**（判定逻辑在无服务器依赖的
> 纯内核里），但增加了一条建议的实机观察项（§K.3）。

### K.1 构建与测试

```
[INFO] Tests run: 268, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- 基线：v1.2 的 265 条 → v1.3 **268 条**（+3）。新增三条都在 `core/SunExposureCheckerTest`
  （该测试类现共 18 条）。
- 离线构建可用：`mvn -o -B test`（约 32s，依赖已缓存时无网络需求）。

### K.2 反向验证（本仓库硬性要求）

回归测试必须证明「改回去会变红」，否则只是装饰品（DEVELOPMENT §10.5 ① / §15 第 3 条）。
本轮把 `ColumnKey` 临时改回「含 y 段」后重跑：

```
[ERROR] Tests run: 18, Failures: 2, Errors: 0, Skipped: 0
[ERROR]   SunExposureCheckerTest.cacheEntriesAreColumnScoped:324
[ERROR]   SunExposureCheckerTest.verticalMovementDoesNotRescanOrFlip:287
```

还原后 `mvn -o -B clean test -Dtest=SunExposureCheckerTest` → **18 条全绿**，
且 `git diff -- src/main/java` 为空（生产代码未留下改动）。

| 测试 | 钉住的缺陷 | 改回旧实现的结果 |
|---|---|---|
| `verticalMovementDoesNotRescanOrFlip` | 同列上下移动（63/64/80 跨段界）不重扫、不翻转 | **变红** |
| `columnScanCoversBelowEyeHeight` | 扫描须覆盖整列，低眼位不得被高眼位写下的值误判遮蔽 | 变红（扫描起点改回段底） |
| `cacheEntriesAreColumnScoped` | 缓存条目按方块列计，跨 y 段不新增条目 | **变红** |

### K.3 建议的实机观察项（可选，复用 §D 环境）

| # | 操作 | v1.3 预期 | 旧版表现 |
|---|---|---|---|
| K-1 | 平地上原地**反复蹲下/站起** 10 秒 | HUD 判定不翻转，持续显示同一状态 | 判定在两个值之间闪 |
| K-2 | 在建筑**门口反复进出**（跨 16 格段界） | 判定随真实遮蔽变化，不出现「偶发掉血」 | 偶发掉血 |
| K-3 | 站上**屋檐外沿**（头顶完全开阔） | 恒判露天（该掉血就掉血） | 可能被判遮蔽（漏判） |
| K-4 | 站进**洞穴**（顶遮挡在头顶下方很远处） | 恒判安全 | — |

> 判定抖动是**概率性**症状（依赖玩家眼高与段界的关系），实机只是辅助观察；
> 真正的判据是 §K.2 的三条回归测试。

---

## L. v1.3.2 验收：负 Y 露天判定漏判（**真实客户端实机验收**）

> 验收日期：2026-10-31（Asia/Shanghai）
> 环境：Paper 1.21.11 build 132（`test-server/`，offline、RCON 25764、`-Xmx1G`）
> ＋ **原版 1.21.11 客户端**（`D:\hmcl\.minecraft\versions\test`，protocol 774，非 Fabric）。
> 性质：**判定缺陷修复**，无配置变更、无玩法数值变更。
>
> 本轮的意义：这是第一次用**真实客户端**跑完整交互链路，也是 §D / §J.5 遗留
> 「需真实客户端」项的首次闭环尝试。缺陷正是**只有实机才能暴露**的那一类。

### L.1 缺陷与修复

**现象**：玩家站在 y=-60 的地下竖井底部，`execute if block` 逐层探针确认头顶
y=-59…319 **全是空气**，`/rad status` 却报「遮蔽/豁免维度（安全）」，白天一滴血不掉。

**根因**：`SunExposureChecker` 把列扫描下界写死为 `MIN_BUILD_HEIGHT(0) - 1 = -1`，
注释理由「1.21 世界 y 从 -64 起，0 已在其上」**混淆了方块层与可站立高度**。
1.21 主世界 y 范围是 **-64..319**，负 Y 是深板岩层/矿洞/竖井/正常可玩区域；
扫描停在 -1 意味着 `[-64,-1]` 的方块从未被读过 → 无遮挡列返回哨兵 `-2` →
判据 `eyeY > topCover` 即 `-60 > -2` 为假 → 判「遮蔽」。方向上属**漏判危险**。

**修复**：扫描下界改由世界驱动，`columnScanStartY(worldMinHeight) == worldMinHeight - 1`；
适配层传 `world.getMinHeight()`。旧无参重载保留为 `@Deprecated` 的纯内核默认值。

### L.2 构建与测试

```
[INFO] Tests run: 315, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- 基线 v1.3.1 的 310 条 → **315 条**（+5，见 `core/NegativeYExposureRegressionTest`）。
- 顺带把 `SunExposureCheckerTest` 里 4 处写死 `(0 - 1)` 的扫描格数断言改为
  `columnScanStartY(MIN_HEIGHT)` 派生，避免把错误下界固化成「期望值」。

### L.3 反向验证（本仓库硬性要求）

把 `columnScanStartY` 临时改回返回 `-1` 后重跑：

```
[ERROR] Tests run: 5, Failures: 3, Errors: 0, Skipped: 0
[ERROR]   NegativeYExposureRegressionTest.exposedAtNegativeY
[ERROR]   NegativeYExposureRegressionTest.exposedAtWorldBottom
[ERROR]   NegativeYExposureRegressionTest.scanStartFollowsWorldMinHeight
```

3 条负 Y 用例变红，**正 Y 对照 `exposedAtPositiveY` 仍绿**——钉住的是缺陷本身，
不是顺手写出来的同义反复。还原后 5 条全绿。

### L.4 实机复验（同一位置，前后对比）

| 观测项 | 修复前 | 修复后 |
|---|---|---|
| `/rad status` 露天判定 | 遮蔽/豁免维度（安全） | **露天（头顶可见天空）** |
| 腐蚀累积 | 冻结不动 | 9% → 100% 持续上涨 |
| 本轮伤害 | `0.000`（遮蔽不受伤） | **`1.860`** = base 1.0 × timeFactor 1.0 × 腐蚀倍率 1.86 |

### L.5 其余机制实机数值复核

所有数值均手工按公式核对，逐位吻合：

| 场景 | 配置/状态 | 期望公式 | 实测 | 结论 |
|---|---|---|---|---|
| 夜晚裸装 | timeFactor 0.500、腐蚀 27%（×1.27） | `1 × 0.5 × 1.27 = 0.635` | `0.635` | ✅ |
| 白天满腐蚀裸装 | timeFactor 1.000、腐蚀 100%（×2.0） | `1 × 1.0 × 2.0 = 2.000` | `2.000` | ✅ |
| 黄昏裸装 | timeFactor 0.837、×2.0 | `1 × 0.837 × 2.0 = 1.674` | `1.674` | ✅ |
| 全套下界合金 | 减免 **72%**、黄昏、×2.0 | `1 × 0.837 × 2.0 × 0.28 = 0.468` | `0.468` | ✅ |

- **腐蚀累积速率**：白天露天约 +1%/s，与 `corrosion.day-accum-per-sec: 1.0` 一致。
- **遮蔽回落**：进入遮蔽后按 `regen-per-sec` 每秒 −1% 回落（实测 27→18→9→0）。
- **真实掉血**：裸装露天采样 `20.0 → 19.52 → 18.77 → 18.59`，与自然回血竞争可见。
- **死亡清零**：`reset-on-death: true` 生效，死后腐蚀由 100% 归零后重新累积。
- **PDC 持久化**：解压 `world/playerdata/<uuid>.dat` 得 `radsurvival:corrosion`（DOUBLE），
  断线重连后腐蚀值完整恢复 100%。
- **reload**：连续 5 次 `/rad reload` 全部成功，玩家数据经 PDC 无缝续接，无异常、无泄漏。
- **稳定性**：全程 TPS `19.9 / 20.0 / 20.0`，插件**零** WARN/ERROR。

### L.6 客户端限制（非插件缺陷）

本机客户端渲染线程受内存约束（`-Xmx512M~700M`，宿主 7.9 GB 仅余约 1 GB）偶发冻结，
表现为：`tp` 命令回报成功但 `data get entity` 位置不更新、死亡后不发送重生请求。
这是**采集端问题**，与服务端逻辑无关；已通过「服务端侧推导 + 换新玩家会话」绕开。

> 因此下列仍**建议上线前手工复跑**（§J.5 遗留项，本轮未闭环）：
> 木棍右键铁砧的真实触发、GUI 点击/拖拽约束、服药后的动作与音效。
> 这三项的 MockBukkit 覆盖仍然有效，但真实客户端交互本轮没能完整跑到。

---

## M. 跨服务端品牌实测（v1.4.0）

> 环境：本机 Windows，JDK 21，各分支独立目录 / 独立端口 / 独立 JVM（`-Xmx768M`），
> 脚本 [test-servers/run-smoke.ps1](test-servers/run-smoke.ps1)。
> 目的不是复跑 §D 的 17 项手册，而是回答「这个分支上插件能不能加载、识别对不对、
> 主循环跑不跑得动」——即 plan.md §7.5 对「支持矩阵声明」的最低要求。
>
> **操作手册见 [PLATFORMS.md](PLATFORMS.md)**（怎么备 jar、怎么跑、结果怎么判读）。
> 本节只记录**结论与证据**。

### M.1 结论总览

| 分支 | 版本 | 识别结果（来源） | `onEnable` | 判定 |
|---|---|---|---|---|
| **Paper** | 1.21.11-132 | `Paper（类探测）` | 正常启用，7 监听器 | ✅ 通过 |
| **Leaf** | 1.21.11-179 | `Leaf（server.getName()）` | 正常启用，7 监听器 | ✅ 通过 |
| **Folia** | 1.21.11-14 | `Folia（类探测）` | **拒绝加载 + 三条中文提示** | ✅ 通过（防御分支） |
| **Purpur** | 1.21.11-2568 | — | **未能实测** | ⚠️ 下载源不可达 |

`/rad status` 的 `platform` 行在三者上都正确输出了识别结果与来源。

### M.2 实测发现的真实缺陷：Folia 的防御代码曾是死代码

**现象**：Folia 上插件根本没有被加载，服务端在**插件加载阶段**（类尚未加载）就拒绝：

```
[DirectoryProviderSource] Error loading plugin: Could not load plugin 'RadSurvival v1.0.0-SNAPSHOT'
as it is not marked as supporting Folia!
```

**这意味着** `RadSurvivalPlugin#guardServerBrand()` 里的 FOLIA 分支**一次都不会执行**——
它在 Folia 上是死代码。单测 `ServerBrandTest` 只验证了「识别逻辑返回 FOLIA」，
但**无法**验证「这个返回值真的会被读到」，因为插件类压根没被加载。

**根因**：Paper/Folia 在 `plugin.yml` 缺省 `folia-supported` 时，于 provider 注册阶段直接拒绝，
不进入 `onEnable`。这与 Spigot 的失败形态不同（Spigot 会加载并执行 `onEnable`，只是抛
`NoClassDefFoundError`）——**同一个「防御分支」在两个服务端上的可达性完全不同**。

**修复**：`plugin.yml` 增加 `folia-supported: true`。注意这**不表示**功能支持 Folia，
只是允许插件被载入以便我们的提示接管：

```
[RadSurvival] 检测到服务端 Folia（类探测），本插件无法在此运行，已自动禁用。
[RadSurvival] 原因：Folia 使用区域化线程模型，全局调度器任务（主循环 / HUD / 露天异步补扫）在其上不可用。
[RadSurvival] 请改用 Paper 1.21.11（或 Leaf / Purpur 等 Paper 分支）后重新挂载本插件。
[RadSurvival] Disabling RadSurvival v1.0.0-SNAPSHOT
```

**复验**：加 `folia-supported: true` 后重跑，三条提示全部弹出，`Disabled` 正常；
同时重跑 Paper 与 Leaf，二者行为**无任何变化**（确认该字段无副作用）。

> **教训**：MockBukkit 集成测试跑在 Paper 系语义上，**测不到**「服务端在插件加载阶段就拒绝」
> 这类分支。凡是「防御性拒绝」逻辑，必须在真实该分支上一次真机才能确认可达性。
> 这与 §10.5「假绿」记录同源：断言通过 ≠ 那段代码被走到过。

### M.3 Leaf 的识别来源为什么是 getName 而非类探测

Leaf 上 `平台 · Leaf（server.getName()：getName=Leaf）` —— 走的是**名字匹配**分支，
而非 `DamageSource` 类探测。原因是 Paper 系分支的 `damage` 类由 **paperclip 在启动时
重映射/注入**，插件 `onEnable` 阶段的类加载器未必能直接 `Class.forName` 到。

这不影响正确性（结果仍是 Leaf→可运行），但说明：
- **类探测并非在所有分支上都可靠回落到 Paper 判定**；
- 因此 `getName()` 的唯一名匹配（purpur/leaf/folia）必须保留在前，不能因为「类探测优先」
  就删掉——它在这条路径上是**唯一**能给出精确分支名的证据；
- `unknown` 兜底放行也因此是必要的（否则所有走不到类探测的分支都会被拒）。

### M.4 Purpur：实测受阻（非插件问题）

`api.purpurmc.org` 的**构建元数据**可达（`/v2/purpur/1.21.11` 返回 build 2568），
但**二进制下载端点**（`/v2/purpur/1.21.11/2568/download`）在本网络下极不稳定：
首轮完全停滞（0 B/20s），改 .NET 流式下载后约 2 KB/s。`purpurmc.org` 主站、
`ci.purpurmc.org`、`repo.purpurmc.org` 均超时。

**结论**：Purpur 的识别与加载**未经实测**，文档中维持「Paper 分支，理论可用，未实测」表述。
补齐方法：在能正常访问 purpurmc.org 的机器上跑
`test-servers/run-smoke.ps1 -Server purpur`（脚本已备好，只需放入 jar）。



