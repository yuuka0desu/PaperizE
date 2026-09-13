package com.paperize.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/** 菜单点击处理器（actionId 对应一个处理器）。 */
@FunctionalInterface
public interface ClickHandler {

    void handle(Player player, MenuItem item, InventoryClickEvent event);
}
