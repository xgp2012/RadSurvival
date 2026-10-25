package com.ft.radsurvival.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.config.PluginConfig.CorrosionCurve;
import com.ft.radsurvival.core.CorrosionMeter.SettleResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * CorrosionMeter / RadiationMath 纯逻辑测试（plan.md §1.6，M2a）：
 * 累积/恢复速率各时间步与钳制、timeFactor×盔甲耦合两分支、三种 curve 钉死 0/50/100 三点、
 * 死亡 reset vs decay、阶段跨阈回调序列（50 上下反复穿越只在跨阈时回调）、
 * 首次 warn 一次性标记、PDC key 常量。无 Bukkit 服务器依赖。
 */
class CorrosionMeterTest {

    private static final double EPS = 1e-9;

    /** 仓库真实 config.yml（经 M1 校验）：默认口径的权威数值源。 */
    private static final String BASE_YAML = readRepoConfig();

    private static String readRepoConfig() {
        try {
            return Files.readString(Path.of("src", "main", "resources", "config.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PluginConfig load(String yaml) {
        PluginConfig.LoadResult result = PluginConfig.loadFromString(yaml);
        assertThat(result.errors())
                .withFailMessage(() -> String.join("\n", result.errors()))
                .isEmpty();
        return result.config();
    }

    private static CorrosionMeter meter(PluginConfig cfg) {
        return new CorrosionMeter(cfg.corrosion());
    }

    // ---------------------------------------------------------------- §1.6 步进内核

    @Test
    @DisplayName("step：露天按累积速率正向、遮蔽按恢复速率负向、elapsed 线性缩放、elapsed=0 不变")
    void kernelStepRates() {
        // 1%/s：0.5s→0.5、1s→1、30s→30
        assertThat(RadiationMath.step(0, 0.5, 1.0, 1.0, true)).isCloseTo(0.5, within(EPS));
        assertThat(RadiationMath.step(0, 1.0, 1.0, 1.0, true)).isCloseTo(1.0, within(EPS));
        assertThat(RadiationMath.step(0, 30.0, 1.0, 1.0, true)).isCloseTo(30.0, within(EPS));
        // 遮蔽恢复：50 −1×10 → 40
        assertThat(RadiationMath.step(50, 10.0, 1.0, 1.0, false)).isCloseTo(40.0, within(EPS));
        // 0.5%/s（夜晚口径）×10s → +5
        assertThat(RadiationMath.step(0, 10.0, 0.5, 1.0, true)).isCloseTo(5.0, within(EPS));
        // elapsed=0 → 原值
        assertThat(RadiationMath.step(73.5, 0.0, 1.0, 1.0, true)).isCloseTo(73.5, within(EPS));
        assertThat(RadiationMath.step(73.5, 0.0, 1.0, 1.0, false)).isCloseTo(73.5, within(EPS));
    }

    @Test
    @DisplayName("step 钳制 [0,100]：95+10s 封顶 100、2−5s 触底 0，便捷形态同口径")
    void kernelStepClamped() {
        assertThat(RadiationMath.step(95, 10.0, 1.0, 1.0, true)).isEqualTo(100.0);
        assertThat(RadiationMath.step(2, 5.0, 1.0, 1.0, false)).isEqualTo(0.0);
        assertThat(RadiationMath.stepExposed(100, 60, 1)).isEqualTo(100.0);
        assertThat(RadiationMath.stepCovered(0, 60, 1)).isEqualTo(0.0);
        assertThat(RadiationMath.clampCorrosion(-3.5)).isZero();
        assertThat(RadiationMath.clampCorrosion(250)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("累积速率耦合：dayAccum×timeFactor；armor-affects-accum 真/假两分支（§1.6 口径）")
    void accumRateTimeFactorAndArmorCoupling() {
        // 白天 tf=1.0 无甲 → 1%/s；夜晚 tf=0.5 → 0.5%/s（与 §1.3 伤害因子同口径缩放）
        assertThat(RadiationMath.effectiveAccumRatePerSec(1.0, 1.0, 0.0, true)).isCloseTo(1.0, within(EPS));
        assertThat(RadiationMath.effectiveAccumRatePerSec(1.0, 0.5, 0.0, true)).isCloseTo(0.5, within(EPS));
        // 晨昏同步插值：tf=0.75 → 0.75%/s
        assertThat(RadiationMath.effectiveAccumRatePerSec(1.0, 0.75, 0.0, true)).isCloseTo(0.75, within(EPS));
        // 盔甲作用于累积：铁全(0.70) 白天 → 1×(1−0.7)=0.3%/s
        assertThat(RadiationMath.effectiveAccumRatePerSec(1.0, 1.0, 0.70, true)).isCloseTo(0.3, within(EPS));
        // armor-affects-accum=false：只减免伤害、不减免累积 → 仍 0.5%/s
        assertThat(RadiationMath.effectiveAccumRatePerSec(1.0, 0.5, 0.70, false)).isCloseTo(0.5, within(EPS));
        // 全套下界合金 true → 0（既不累积也不掉血，§1.6）
        assertThat(RadiationMath.effectiveAccumRatePerSec(1.0, 1.0, 1.0, true)).isZero();
    }

    // ---------------------------------------------------------------- §1.3/§1.6 倍率与公式

    @Test
    @DisplayName("corrosionMult 三点钉死：linear 0/50/100 → 1.0/1.5/2.0")
    void corrosionMultLinearPinned() {
        assertThat(RadiationMath.corrosionMultiplier(0, 2.0, CorrosionCurve.LINEAR)).isCloseTo(1.0, within(EPS));
        assertThat(RadiationMath.corrosionMultiplier(50, 2.0, CorrosionCurve.LINEAR)).isCloseTo(1.5, within(EPS));
        assertThat(RadiationMath.corrosionMultiplier(100, 2.0, CorrosionCurve.LINEAR)).isCloseTo(2.0, within(EPS));
        // 自定义上限 max=3：linear 100 → 3.0、50 → 2.0
        assertThat(RadiationMath.corrosionMultiplier(100, 3.0, CorrosionCurve.LINEAR)).isCloseTo(3.0, within(EPS));
        assertThat(RadiationMath.corrosionMultiplier(50, 3.0, CorrosionCurve.LINEAR)).isCloseTo(2.0, within(EPS));
        // 越界输入先钳 [0,100]
        assertThat(RadiationMath.corrosionMultiplier(-50, 2.0, CorrosionCurve.LINEAR)).isCloseTo(1.0, within(EPS));
        assertThat(RadiationMath.corrosionMultiplier(150, 2.0, CorrosionCurve.LINEAR)).isCloseTo(2.0, within(EPS));
    }

    @Test
    @DisplayName("corrosionMult 三点钉死：sqrt 0/50/100 → 1.0/1+√0.5/2.0；exp(平方口径) → 1.0/1.25/2.0")
    void corrosionMultSqrtExpPinned() {
        assertThat(RadiationMath.corrosionMultiplier(0, 2.0, CorrosionCurve.SQRT)).isCloseTo(1.0, within(EPS));
        assertThat(RadiationMath.corrosionMultiplier(50, 2.0, CorrosionCurve.SQRT))
                .isCloseTo(1.0 + Math.sqrt(0.5), within(EPS)); // ≈1.7071：先高后猛
        assertThat(RadiationMath.corrosionMultiplier(100, 2.0, CorrosionCurve.SQRT)).isCloseTo(2.0, within(EPS));

        assertThat(RadiationMath.corrosionMultiplier(0, 2.0, CorrosionCurve.EXP)).isCloseTo(1.0, within(EPS));
        assertThat(RadiationMath.corrosionMultiplier(50, 2.0, CorrosionCurve.EXP)).isCloseTo(1.25, within(EPS)); // 先缓后爆
        assertThat(RadiationMath.corrosionMultiplier(100, 2.0, CorrosionCurve.EXP)).isCloseTo(2.0, within(EPS));
    }

    @Test
    @DisplayName("§1.3 最终伤害公式：base×tf×mult×(1−red) 手算比对；red=1 → 恒 0（免疫）")
    void finalDamageFormula() {
        // 白天、腐蚀 50、无甲：1.0×1.0×1.5×1.0 = 1.5
        assertThat(RadiationMath.finalDamage(1.0, 1.0, 1.5, 0.0)).isCloseTo(1.5, within(EPS));
        // 白天、腐蚀 50、皮革(0.25)：1×1×1.5×0.75 = 1.125
        assertThat(RadiationMath.finalDamage(1.0, 1.0, 50.0, 0.25, 2.0, CorrosionCurve.LINEAR))
                .isCloseTo(1.125, within(EPS));
        // 夜晚、腐蚀 100、铁(0.70)：1×0.5×2×0.3 = 0.3
        assertThat(RadiationMath.finalDamage(1.0, 0.5, 100.0, 0.70, 2.0, CorrosionCurve.LINEAR))
                .isCloseTo(0.3, within(EPS));
        // 全套下界合金：任何腐蚀/时段伤害恒 0（§1.3 达到 1.0 完全免疫）
        assertThat(RadiationMath.finalDamage(1.0, 1.0, 100.0, 1.0, 2.0, CorrosionCurve.LINEAR)).isZero();
        assertThat(RadiationMath.finalDamage(1.0, 0.5, 37.0, 1.0, 2.0, CorrosionCurve.SQRT)).isZero();
    }

    // ---------------------------------------------------------------- §1.6 阶段

    @Test
    @DisplayName("stageOf：到达即算（≥ 阈值含等号）、danger 优先：50→warn、75→danger")
    void stageThresholdsPinned() {
        assertThat(RadiationMath.stageOf(0, 50, 75)).isEqualTo(CorrosionMeter.STAGE_NORMAL);
        assertThat(RadiationMath.stageOf(49.999, 50, 75)).isEqualTo(CorrosionMeter.STAGE_NORMAL);
        assertThat(RadiationMath.stageOf(50, 50, 75)).isEqualTo(CorrosionMeter.STAGE_WARN);   // 含等号
        assertThat(RadiationMath.stageOf(74.999, 50, 75)).isEqualTo(CorrosionMeter.STAGE_WARN);
        assertThat(RadiationMath.stageOf(75, 50, 75)).isEqualTo(CorrosionMeter.STAGE_DANGER); // 含等号
        assertThat(RadiationMath.stageOf(100, 50, 75)).isEqualTo(CorrosionMeter.STAGE_DANGER);
    }

    @Test
    @DisplayName("死亡处理：reset-on-death → 0；decay-on-death:0.5 → c×0.5（互斥已由 M1 校验）")
    void deathResetVsDecay() {
        assertThat(RadiationMath.applyDeath(80.0, true, null)).isZero();
        assertThat(RadiationMath.applyDeath(80.0, false, 0.5)).isCloseTo(40.0, within(EPS));
        assertThat(RadiationMath.applyDeath(100.0, false, 0.5)).isCloseTo(50.0, within(EPS));
        // reset=true 时即使误传 decay 也按清零（防御；配置层互斥已保证不会出现）
        assertThat(RadiationMath.applyDeath(80.0, true, 0.5)).isZero();
        // decay 后仍钳 [0,100]
        assertThat(RadiationMath.applyDeath(-9, false, 0.5)).isZero();
    }

    // ---------------------------------------------------------------- 内存态/回调

    @Test
    @DisplayName("settle 序列：累积→跨 warn→再跨→回落只记 3 次回调；50 上下反复穿越仅跨阈时回调")
    void stageCallbacksOnlyOnCrossing() {
        CorrosionMeter meter = meter(load(BASE_YAML)); // 默认：accum 1、regen 1、warn 50、danger 75、armorAffects=true
        List<String> events = new ArrayList<>();
        meter.addStageListener((player, oldStage, newStage) -> events.add(oldStage + "->" + newStage));
        UUID id = UUID.randomUUID();

        // 基线 49.5（<50，无回调）
        assertThat(meter.setValue(id, 49.5).stage()).isEqualTo(CorrosionMeter.STAGE_NORMAL);
        assertThat(events).isEmpty();

        // timeFactor=0 的结算步：累积速率 = dayAccum×0 = 0 → 值纹丝不动（口径：晨昏插值末端等效冻结）
        SettleResult r1 = meter.settle(id, true, 0.0, 0.0, 1.0);
        assertThat(r1.value()).isCloseTo(49.5, within(EPS));

        // 推进到 50.5 跨 warn：回调 0->1，firstWarnNotice=true
        SettleResult r2 = meter.setValue(id, 50.5);
        assertThat(r2.stage()).isEqualTo(CorrosionMeter.STAGE_WARN);
        assertThat(events).containsExactly("0->1");
        assertThat(r2.firstWarnNotice()).isTrue();

        // 51.0（段内移动）→ 无回调
        assertThat(meter.setValue(id, 51.0).stage()).isEqualTo(CorrosionMeter.STAGE_WARN);
        // 50.4（遮蔽小幅回落，仍 ≥50）→ 无回调
        assertThat(meter.setValue(id, 50.4).stage()).isEqualTo(CorrosionMeter.STAGE_WARN);
        // 49.4 → 跨回 normal：回调 1->0，首次 warn 标记不再触发
        SettleResult r3 = meter.setValue(id, 49.4);
        assertThat(r3.stage()).isEqualTo(CorrosionMeter.STAGE_NORMAL);
        assertThat(r3.firstWarnNotice()).isFalse();
        // 再上 50.4 → 再次回调 0->1，但 firstWarnNotice 恒 false（一次性）
        SettleResult r4 = meter.setValue(id, 50.4);
        assertThat(r4.stage()).isEqualTo(CorrosionMeter.STAGE_WARN);
        assertThat(r4.firstWarnNotice()).isFalse();

        // 跨 danger：1->2；总序列钉死
        meter.setValue(id, 76.0);
        assertThat(events).containsExactly("0->1", "1->0", "0->1", "1->2");
    }

    @Test
    @DisplayName("settle 主路径：白天露天 1%/s 逐步累积至 clamp；遮蔽 1%/s 回落到 0；夜晚半速")
    void settleAccumAndRegenPaths() {
        CorrosionMeter meter = meter(load(BASE_YAML));
        UUID id = UUID.randomUUID();
        // 主循环口径（M2b）：每 tick-interval=20tick=1.0s 结算一次
        for (int i = 0; i < 10; i++) {
            meter.settle(id, true, 1.0, 0.0, 1.0); // 白天裸装
        }
        assertThat(meter.get(id)).isCloseTo(10.0, within(EPS));
        // 夜晚半速：再 4s → +2
        for (int i = 0; i < 4; i++) {
            meter.settle(id, true, 0.5, 0.0, 1.0);
        }
        assertThat(meter.get(id)).isCloseTo(12.0, within(EPS));
        // 遮蔽恢复 20s → −20 → clamp 不低于 0
        for (int i = 0; i < 100; i++) {
            meter.settle(id, false, 0.5, 0.0, 1.0);
        }
        assertThat(meter.get(id)).isZero();
        // 120s 露天 → 封顶 100
        for (int i = 0; i < 120; i++) {
            meter.settle(id, true, 1.0, 0.0, 1.0);
        }
        assertThat(meter.get(id)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("armor-affects-accum 两分支端到端：铁甲(0.70) 白天 10s → 累积 3% vs 10%")
    void armorAffectsAccumBranchesEndToEnd() {
        CorrosionMeter on = meter(load(BASE_YAML)); // 默认 true
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            on.settle(id, true, 1.0, 0.70, 1.0);
        }
        assertThat(on.get(id)).isCloseTo(3.0, within(EPS)); // 1×(1−0.7)×10

        CorrosionMeter off = meter(load(BASE_YAML.replace(
                "armor-affects-accum: true", "armor-affects-accum: false")));
        UUID id2 = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            off.settle(id2, true, 1.0, 0.70, 1.0);
        }
        assertThat(off.get(id2)).isCloseTo(10.0, within(EPS)); // 只减免伤害不减免累积
    }

    @Test
    @DisplayName("onDeath 端到端：默认 reset 清零并回调降档；decay:0.5 减半（80→40，danger→normal 回调）")
    void onDeathEndToEndResetAndDecay() {
        CorrosionMeter resetMeter = meter(load(BASE_YAML)); // reset-on-death: true
        UUID id = UUID.randomUUID();
        List<String> events = new ArrayList<>();
        resetMeter.addStageListener((p, o, n) -> events.add(o + "->" + n));
        resetMeter.setValue(id, 55.0); // 0->1
        resetMeter.setValue(id, 80.0); // 1->2（逐段跨阈各回调一次；直接跳档时只回调 0->2 一次）
        assertThat(events).containsExactly("0->1", "1->2");
        resetMeter.onDeath(id);
        assertThat(resetMeter.get(id)).isZero();
        assertThat(events).containsExactly("0->1", "1->2", "2->0"); // 死亡一次性降回 normal

        CorrosionMeter decayMeter = meter(load(BASE_YAML.replace(
                "reset-on-death: true", "reset-on-death: false\n  decay-on-death: 0.5")));
        UUID id2 = UUID.randomUUID();
        decayMeter.setValue(id2, 80.0); // danger
        decayMeter.onDeath(id2);
        assertThat(decayMeter.get(id2)).isCloseTo(40.0, within(EPS)); // 80×0.5，仍 <50 → normal
        assertThat(decayMeter.currentStage(id2)).isEqualTo(CorrosionMeter.STAGE_NORMAL);
    }

    @Test
    @DisplayName("未知玩家缺省 0.0；setValue/addValue 钳制；forget 清除会话（含阶段基线重置）")
    void valueAccessorsAndForget() {
        CorrosionMeter m = meter(load(BASE_YAML));
        UUID id = UUID.randomUUID();
        assertThat(m.get(id)).isZero();
        assertThat(m.currentStage(id)).isEqualTo(CorrosionMeter.STAGE_NORMAL);

        assertThat(m.setValue(id, 150).value()).isEqualTo(100.0);
        assertThat(m.setValue(id, -20).value()).isZero();
        assertThat(m.addValue(id, 60).value()).isCloseTo(60.0, within(EPS));
        assertThat(m.addValue(id, -100).value()).isZero();

        m.setValue(id, 60.0); // 阶段 warn
        assertThat(m.currentStage(id)).isEqualTo(CorrosionMeter.STAGE_WARN);
        m.forget(id);
        assertThat(m.get(id)).isZero();
        assertThat(m.currentStage(id)).isEqualTo(CorrosionMeter.STAGE_NORMAL); // 基线重置
    }

    @Test
    @DisplayName("阶段回调按注册顺序分发给多个监听器；clearStageListeners 后不再触发")
    void listenerRegistrationLifecycle() {
        CorrosionMeter m = meter(load(BASE_YAML));
        UUID id = UUID.randomUUID();
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        m.addStageListener((p, o, n) -> a.add(p + ":" + o + "->" + n));
        m.addStageListener((p, o, n) -> b.add(o + "->" + n));
        m.setValue(id, 50.0);
        assertThat(a).hasSize(1);
        assertThat(b).containsExactly("0->1");
        m.clearStageListeners();
        m.setValue(id, 75.0);
        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1);
    }

    @Test
    @DisplayName("首次 warn 一次性标记按玩家隔离：新玩家跨 warn 仍触发 firstWarnNotice")
    void firstWarnIsPerPlayer() {
        CorrosionMeter m = meter(load(BASE_YAML));
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        assertThat(m.setValue(p1, 50.0).firstWarnNotice()).isTrue();
        assertThat(m.setValue(p1, 51.0).firstWarnNotice()).isFalse();
        // p2 独立计数；且 forget(p1) 不清除其已播报标记（规则说明每人次一次，§1.6）
        assertThat(m.setValue(p2, 50.0).firstWarnNotice()).isTrue();
        m.forget(p1);
        m.setValue(p1, 50.0);
        assertThat(m.get(p1)).isCloseTo(50.0, within(EPS));
    }

    // ---------------------------------------------------------------- PDC 常量

    @Test
    @DisplayName("PDC key 常量钉死：radsurvival:corrosion（§1.6/§6 命名空间）")
    void pdcKeyConstants() {
        assertThat(CorrosionMeter.PDC_KEY_STRING).isEqualTo("radsurvival:corrosion");
        assertThat(CorrosionMeter.PDC_NAMESPACE).isEqualTo("radsurvival");
        assertThat(CorrosionMeter.PDC_CORROSION_KEY).isEqualTo("corrosion");
        assertThat(CorrosionMeter.CORROSION_PDC_KEY.getNamespace()).isEqualTo("radsurvival");
        assertThat(CorrosionMeter.CORROSION_PDC_KEY.getKey()).isEqualTo("corrosion");
    }

    // ---------------------------------------------------------------- PlayerRadState

    @Test
    @DisplayName("PlayerRadState：M2b 状态 POJO 字段镜像可用（默认值 + 读写）")
    void playerRadStatePojo() {
        PlayerRadState state = new PlayerRadState();
        assertThat(state.isExposed()).isFalse();
        assertThat(state.getTimeFactor()).isEqualTo(1.0); // 默认白天
        assertThat(state.getCorrosion()).isZero();
        assertThat(state.getArmorReduction()).isZero();
        assertThat(state.getLastDamage()).isZero();
        assertThat(state.getLastSettleStamp()).isZero();
        assertThat(state.isFirstWarnAnnounced()).isFalse();

        state.setExposed(true);
        state.setTimeFactor(0.75);
        state.setCorrosion(66.5);
        state.setArmorReduction(0.48);
        state.setLastDamage(1.125);
        state.setLastSettleStamp(123456L);
        state.setFirstWarnAnnounced(true);
        assertThat(state.isExposed()).isTrue();
        assertThat(state.getTimeFactor()).isEqualTo(0.75);
        assertThat(state.getCorrosion()).isEqualTo(66.5);
        assertThat(state.toString()).contains("corrosion=66.5").contains("firstWarnAnnounced=true");
    }
}
