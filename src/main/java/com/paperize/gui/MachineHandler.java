package com.paperize.gui;

import com.paperize.PaperizEPlugin;
import com.paperize.menu.ActionMapEventHandler;
import com.paperize.menu.MenuItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * 机器窗体交互：功能槽放行物品移动；凝聚器目标槽变化时记录目标 id，
 * 并在下一 tick 刷新信息位（进度/目标）。
 */
public final class MachineHandler extends ActionMapEventHandler {

    private final PaperizEPlugin plugin;
    private final MachineMenu menu;

    public MachineHandler(PaperizEPlugin plugin, MachineMenu menu) {
        this.plugin = plugin;
        this.menu = menu;

        bind("machine:slot", (player, item, event) -> scheduleRefresh(player));
        bind("machine:info", (player, item, event) -> scheduleRefresh(player));
        bind("machine:fuel", (player, item, event) -> scheduleRefresh(player));
        bind("machine:input", (player, item, event) -> scheduleRefresh(player));
        bind("machine:output", (player, item, event) -> scheduleRefresh(player));
        bind("machine:target", (player, item, event) -> scheduleRefresh(player));
        // 能量之星槽：仅接受克莱因之星
        bind("machine:star", (player, item, event) -> {
            var cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                String id = plugin.emc().idOf(cursor);
                if (id == null || !id.contains("klein_star")) {
                    event.setCancelled(true);
                    player.sendActionBar(net.kyori.adventure.text.Component
                            .text("能量之星槽仅接受克莱因之星", net.kyori.adventure.text.format.NamedTextColor.RED));
                    return;
                }
            }
            scheduleRefresh(player);
        });
    }

    @Override
    public void handleClick(Player player, MenuItem item, InventoryClickEvent event) {
        // 功能槽点击（interactive=true，事件放行）；调度信息刷新
        scheduleRefresh(player);
    }

    private void scheduleRefresh(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            var top = player.getOpenInventory().getTopInventory();
            if (top.getSize() < 54) {
                return;
            }
            // 凝聚器：读取目标槽（第一行第一位）物品 → 记录目标 id（供节拍产出）
            if (menu.machine().type().condenser()) {
                var target = top.getItem(com.paperize.machine.MachineState.CONDENSER_TARGET_SLOT);
                if (target != null && !target.getType().isAir()) {
                    long value = menu.emcOf(target);
                    if (value > 0) {
                        menu.machine().setTargetId(plugin.emc().idOf(target));
                    }
                }
            }
        });
    }
}
