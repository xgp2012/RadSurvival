package com.ft.radsurvival.feedback;

import org.bukkit.plugin.Plugin;

/**
 * 反馈增强体隔离接口（plan.md §1.5 ProtocolLib 可选路径 / §5.2「缺省用 Paper 原生」，M3）。
 *
 * <p><b>隔离目的（反射-free）</b>：ProtocolLib 是 {@code compileOnly} 软依赖，
 * 其类型只出现在 {@link ProtocolLibHurtFlashEnhancer} 内部；{@link FeedbackService}
 * 仅在运行期检测到 ProtocolLib 已启用时于 {@code try/catch(Throwable)} 中构造实现类——
 * 类缺失（NoClassDefFoundError）或 API 漂移一律留桩降级，核心功能与原生反馈主线零影响。</p>
 *
 * <p><b>本期增强范围（刻意最小化，低风险）</b>：仅抑制原版受伤红闪
 * （取消发给本人的 client-bound EntityStatus(HURT=2) 包）；
 * 包级 HUD 全量替换为 §4 扩展项，本期不做。</p>
 */
public interface HurtFlashEnhancer {

    /** 可读名（日志/排障）。 */
    String label();

    /**
     * 挂载监听（抛任何异常 = 未启用，调用方留桩降级）。
     *
     * @param plugin 宿主插件（ProtocolLib 监听器注册主体）
     */
    void attach(Plugin plugin);

    /** 摘除监听（onDisable/reload 交接；必须幂等）。 */
    void detach();
}
