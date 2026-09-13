package com.paperize.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 菜单界面标识：作为 {@link InventoryHolder} 绑定到自建界面。
 *
 * <p>事件判定必须基于 holder（创建时注入、随界面稳定存在），
 * 而不可用 {@code Inventory.equals} —— Paper 的容器包装不保证引用一致，
 * 用 equals 判定会导致界面事件被静默忽略。
 */
public final class MenuHolder implements InventoryHolder {

    private final String moduleId;
    private Inventory inventory;

    public MenuHolder(String moduleId) {
        this.moduleId = moduleId;
    }

    public String moduleId() {
        return moduleId;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
