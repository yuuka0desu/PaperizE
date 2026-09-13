package com.paperize.menu;

import org.bukkit.inventory.ItemStack;

/**
 * 菜单控件：物品外观 + 动作 id + 是否可交互（允许放入/取出真实物品）。
 *
 * <p>{@code interactive=false} 的槽位为静态装饰/按钮：点击会被取消并路由到动作处理器；
 * {@code interactive=true} 的槽位放行原版物品移动（用于机器槽位、学习槽等）。
 */
public final class MenuItem {

    private final ItemStack stack;
    private final String actionId;
    private final boolean interactive;

    public MenuItem(ItemStack stack, String actionId, boolean interactive) {
        this.stack = stack;
        this.actionId = actionId;
        this.interactive = interactive;
    }

    public MenuItem(ItemStack stack, String actionId) {
        this(stack, actionId, false);
    }

    public ItemStack stack() {
        return stack;
    }

    public String actionId() {
        return actionId;
    }

    public boolean interactive() {
        return interactive;
    }

    public static MenuItem staticItem(ItemStack stack) {
        return new MenuItem(stack, null, false);
    }

    public static MenuItem button(ItemStack stack, String actionId) {
        return new MenuItem(stack, actionId, false);
    }

    public static MenuItem slot(ItemStack stack, String actionId) {
        return new MenuItem(stack, actionId, true);
    }
}
