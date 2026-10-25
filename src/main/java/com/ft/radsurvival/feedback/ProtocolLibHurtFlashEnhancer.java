package com.ft.radsurvival.feedback;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;

/**
 * ProtocolLib 受伤红闪抑制实现（plan.md §5.2 ProtocolLib 行「隐藏原版受伤红闪」，M3 唯一增强项）。
 *
 * <p>原版受伤红闪由 server→client 的 <b>EntityStatus</b> 包（status=2，LivingEntity HURT）驱动；
 * 辐射每轮掉血都会触发，与 §1.5 自有受击反馈（粒子/音效）叠加观感过重。本实现监听
 * {@code PacketType.Play.Server.ENTITY_STATUS}（ProtocolLib 命名以<b>发送端</b>为前缀：
 * Play.Server = 服务器发往客户端，走 {@code onPacketSending}；1.21.11 编译核实：
 * Play.Client <b>无</b> ENTITY_STATUS 常量），当「包目标实体 == 接收者自身」时取消——
 * 只压自己的红闪，不影响其他实体的状态动画，也不触碰伤害数值。</p>
 *
 * <p><b>降级姿态</b>（§5.2/§6 对策）：本类只在 {@link FeedbackService} 检测到 ProtocolLib
 * 已启用时被构造；构造/attach 的任何异常由调用方捕获留桩；包字段解析失败单包放行
 * （宁可漏抑制也不误伤功能）。本类类型不出现在核心链路，ProtocolLib 缺失时零影响。</p>
 */
final class ProtocolLibHurtFlashEnhancer implements HurtFlashEnhancer {

    /** EntityStatus 原版语义：2 = LivingEntity hurt（受伤红闪/摇头动画）。 */
    private static final int STATUS_LIVING_ENTITY_HURT = 2;

    private ProtocolManager protocolManager;
    private PacketAdapter adapter;

    @Override
    public String label() {
        return "ProtocolLib-受伤红闪抑制";
    }

    @Override
    public void attach(Plugin plugin) {
        protocolManager = ProtocolLibrary.getProtocolManager();
        adapter = new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_STATUS) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    Player recipient = event.getPlayer();
                    if (recipient == null) {
                        return;
                    }
                    PacketContainer packet = event.getPacket();
                    // 字段序（1.21.x ClientboundSetEntityStatusPacket）：[0]=entity id（varint），[1]=status（byte）
                    int entityId = packet.getIntegers().read(0);
                    if (entityId != recipient.getEntityId()) {
                        return; // 只拦发给自己的那一条
                    }
                    byte status = packet.getBytes().read(0);
                    if (status == STATUS_LIVING_ENTITY_HURT) {
                        event.setCancelled(true); // 抑制原版红闪；数值与事件链不受影响
                    }
                } catch (Exception ex) { // RuntimeException ⊂ Exception（multi-catch 非法），单包放行
                    // 解析口径漂移：单包放行，绝不误伤
                }
            }
        };
        protocolManager.addPacketListener(adapter);
    }

    @Override
    public void detach() {
        if (protocolManager != null && adapter != null) {
            try {
                protocolManager.removePacketListener(adapter); // 1.21.11/5.3.0 核实：仅 PacketListener 重载
            } catch (Exception ignored) {
                // 摘除失败不阻断停用流程
            }
        }
        protocolManager = null;
        adapter = null;
    }
}
