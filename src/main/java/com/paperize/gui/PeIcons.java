package com.paperize.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面图标工具：按钮/装饰/信息物品构造，以及 CraftEngine 内容物品构建。
 *
 * <p>对齐 PD 的 {@code gui.yml} 图标思路：按钮优先取内容物品（CE id），
 * 缺失时回退原版材质，保证界面在任何加载状态下都可用。
 */
public final class PeIcons {

    private PeIcons() {
    }

    /** 由内容 id 构建物品（CE 自定义物品优先，否则原版材质）。 */
    public static ItemStack build(String id, int amount) {
        try {
            BukkitItemDefinition definition = CraftEngineItems.byId(Key.of(id));
            if (definition != null) {
                ItemStack stack = definition.buildBukkitItem();
                if (stack != null && !stack.getType().isAir()) {
                    stack.setAmount(Math.max(1, amount));
                    return stack;
                }
            }
        } catch (Throwable ignored) {
            // 回退原版材质
        }
        Material material = Material.matchMaterial(id);
        if (material == null || material.isAir()) {
            return null;
        }
        return new ItemStack(material, Math.max(1, amount));
    }

    /** 按钮：材质 + 名称 + 说明。 */
    public static ItemStack button(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) {
                    if (line != null && !line.isEmpty()) {
                        lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                    }
                }
                meta.lore(lines);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 内容物品按钮：CE 物品 + 追加说明。 */
    public static ItemStack contentButton(String id, Material fallback, String name, NamedTextColor color, String... lore) {
        ItemStack stack = build(id, 1);
        if (stack == null) {
            stack = new ItemStack(fallback);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            }
            if (lore.length > 0) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) {
                    if (line != null && !line.isEmpty()) {
                        lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                    }
                }
                meta.lore(lines);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 装饰填充物（黑色玻璃板，全局统一占位）。 */
    public static ItemStack filler() {
        ItemStack stack = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 空槽位占位（黑色玻璃板，提示可放入）。 */
    public static ItemStack emptySlot(String hint) {
        ItemStack stack = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(hint == null ? " " : hint, NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 在物品上追加 EMC 说明与取出提示。 */
    public static ItemStack withEmcLore(ItemStack stack, long value) {
        if (stack == null) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text("EMC: " + value, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("点击取出（左键 1 / 右键 8 / Shift 64）", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
