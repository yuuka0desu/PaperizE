package com.paperize.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * 菜单模块：一个菜单 = 一个模块（构建 + 关闭回调）。
 *
 * <p>{@link #build(Player)} 在每次打开/刷新时调用，返回新的菜单声明；
 * {@link #onClose} 用于把界面内容回写到领域状态（机器槽位等）。
 */
public interface MenuModule {

    /** 模块 id（全局唯一，openMenu 用）。 */
    String id();

    /** 构建菜单内容（每次打开/刷新调用）。 */
    Menu build(Player player);

    /** 关闭回调（保存界面状态）。 */
    default void onClose(Player player, Inventory inventory) {
    }
}
