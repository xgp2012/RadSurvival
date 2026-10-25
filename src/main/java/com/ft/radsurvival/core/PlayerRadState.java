package com.ft.radsurvival.core;

/**
 * 玩家辐射会话状态（plan.md §2「玩家状态」：Map&lt;UUID, PlayerRadState&gt; 的条目）。
 *
 * <p>简单可变 POJO：仅由主循环（M2b RadiationTask）在服务器主线程上读写，无同步需求；
 * 生命周期由 M2b 的 PlayerSessionListener 管理（join 创建并 CorrosionMeter#loadFromPlayer、
 * quit 落盘后移除 → 离线冻结，§1.6）。腐蚀值以 {@link CorrosionMeter} 的内存态为权威，
 * 本类 {@link #corrosion} 仅作主循环每轮结算后的镜像，供 HUD/命令等只读消费（§1.5/M3）。</p>
 */
public final class PlayerRadState {

    /** §1.1 最近一轮露天判定结果（豁免维度恒 false）。 */
    private boolean exposed;
    /** §1.2 当前昼夜伤害因子（白天 1.0 / 夜晚 night-multiplier / 晨昏插值）。 */
    private double timeFactor = 1.0;
    /** §1.6 腐蚀值镜像 [0,100]（权威在 CorrosionMeter）。 */
    private double corrosion;
    /** §1.4 当前盔甲减免 [0,1]。 */
    private double armorReduction;
    /** §1.3 最近一轮实际结算的伤害（0 = 被跳过/抗尘埃药免疫；M4 无敌帧断言可用）。 */
    private double lastDamage;
    /** §2 上次结算时间戳（毫秒，System.currentTimeMillis 口径；离线期间不推进 → 冻结）。 */
    private long lastSettleStamp;
    /** §1.6 首次到达 warn 阶段的规则说明是否已播报（一次性标记；跨会话不持久化）。 */
    private boolean firstWarnAnnounced;

    public boolean isExposed() {
        return exposed;
    }

    public void setExposed(boolean exposed) {
        this.exposed = exposed;
    }

    public double getTimeFactor() {
        return timeFactor;
    }

    public void setTimeFactor(double timeFactor) {
        this.timeFactor = timeFactor;
    }

    public double getCorrosion() {
        return corrosion;
    }

    public void setCorrosion(double corrosion) {
        this.corrosion = corrosion;
    }

    public double getArmorReduction() {
        return armorReduction;
    }

    public void setArmorReduction(double armorReduction) {
        this.armorReduction = armorReduction;
    }

    public double getLastDamage() {
        return lastDamage;
    }

    public void setLastDamage(double lastDamage) {
        this.lastDamage = lastDamage;
    }

    public long getLastSettleStamp() {
        return lastSettleStamp;
    }

    public void setLastSettleStamp(long lastSettleStamp) {
        this.lastSettleStamp = lastSettleStamp;
    }

    public boolean isFirstWarnAnnounced() {
        return firstWarnAnnounced;
    }

    public void setFirstWarnAnnounced(boolean firstWarnAnnounced) {
        this.firstWarnAnnounced = firstWarnAnnounced;
    }

    @Override
    public String toString() {
        return "PlayerRadState[exposed=" + exposed
                + ", timeFactor=" + timeFactor
                + ", corrosion=" + corrosion
                + ", armorReduction=" + armorReduction
                + ", lastDamage=" + lastDamage
                + ", lastSettleStamp=" + lastSettleStamp
                + ", firstWarnAnnounced=" + firstWarnAnnounced + "]";
    }
}
