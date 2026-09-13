package com.paperize.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动作映射处理器：把 {@code actionId} 绑定到 {@link ClickHandler}。
 *
 * <p>对齐 PD/PE2 的菜单事件层：点击路由 + 拖拽钩子 + Shift 快速移动判定。
 */
public abstract class ActionMapEventHandler {

    private final Map<String, ClickHandler> handlers = new LinkedHashMap<>();

    protected final ActionMapEventHandler bind(String actionId, ClickHandler handler) {
        handlers.put(actionId, handler);
        return this;
    }

    public final ClickHandler handler(String actionId) {
        return actionId == null ? null : handlers.get(actionId);
    }

    /** 点击路由（由 MenuManager 调用）。 */
    public void handleClick(Player player, MenuItem item, InventoryClickEvent event) {
        ClickHandler handler = handler(item.actionId());
        if (handler != null) {
            handler.handle(player, item, event);
        }
    }

    /** 拖拽钩子：默认拒绝向静态槽位拖拽。 */
    public void handleDrag(Player player, MenuItem item, InventoryDragEvent event) {
        if (!item.interactive()) {
            event.setCancelled(true);
        }
    }

    /** Shift 快速移动判定：可交互槽位允许移入。 */
    public boolean canQuickMove(Player player, MenuItem item, ItemStack stack) {
        return item.interactive();
    }
}
