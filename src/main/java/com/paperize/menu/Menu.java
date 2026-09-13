package com.paperize.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/** 菜单声明：标题 + 容量 + 槽位控件表（actionId 路由）。 */
public final class Menu {

    private final Component title;
    private final int size;
    private final Map<Integer, MenuItem> items = new LinkedHashMap<>();

    public Menu(Component title, int size) {
        if (size <= 0 || size % 9 != 0 || size > 54) {
            throw new IllegalArgumentException("菜单容量必须为 9 的倍数且 ≤54: " + size);
        }
        this.title = title;
        this.size = size;
    }

    public Menu setItem(int slot, MenuItem item) {
        if (slot >= 0 && slot < size) {
            items.put(slot, item);
        }
        return this;
    }

    public Menu setItem(int slot, ItemStack stack, String actionId) {
        return setItem(slot, new MenuItem(stack, actionId));
    }

    public Menu setStatic(int slot, ItemStack stack) {
        return setItem(slot, MenuItem.staticItem(stack));
    }

    public Menu setSlot(int slot, ItemStack stack, String actionId) {
        return setItem(slot, MenuItem.slot(stack, actionId));
    }

    public MenuItem itemAt(int slot) {
        return items.get(slot);
    }

    public boolean interactiveAt(int slot) {
        MenuItem item = items.get(slot);
        return item != null && item.interactive();
    }

    public Component title() {
        return title;
    }

    public int size() {
        return size;
    }

    public Map<Integer, MenuItem> items() {
        return items;
    }

    /** 用装饰物品填充所有未定义槽位。 */
    public Menu fillEmpty(ItemStack filler) {
        for (int i = 0; i < size; i++) {
            if (!items.containsKey(i)) {
                setStatic(i, filler);
            }
        }
        return this;
    }

    /** 范围填充：{from, to} 闭区间。 */
    public Menu fillRange(int from, int to, ItemStack filler) {
        for (int i = Math.max(0, from); i <= Math.min(size - 1, to); i++) {
            if (!items.containsKey(i)) {
                setStatic(i, filler);
            }
        }
        return this;
    }
}
