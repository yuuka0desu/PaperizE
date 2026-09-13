package com.paperize.gui;

import com.paperize.BagStore;
import com.paperize.menu.Menu;
import com.paperize.menu.MenuItem;
import com.paperize.menu.MenuModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 炼金袋窗体：54 格随身存储（内容随袋子颜色独立持久化）。
 */
public final class BagMenu implements MenuModule {

    public static final String ID_PREFIX = "paperize:bag:";

    private final String bagKey;
    private final String bagName;
    private final BagStore store;

    public BagMenu(String bagKey, String bagName, BagStore store) {
        this.bagKey = bagKey;
        this.bagName = bagName;
        this.store = store;
    }

    @Override
    public String id() {
        return ID_PREFIX + bagKey;
    }

    @Override
    public Menu build(Player player) {
        Menu menu = new Menu(Component.text("炼金袋", NamedTextColor.LIGHT_PURPLE), 54);
        ItemStack[] stored = store.get(bagKey);
        for (int i = 0; i < 54; i++) {
            ItemStack stack = stored == null || i >= stored.length ? null : stored[i];
            menu.setItem(i, MenuItem.slot(stack, "bag:slot"));
        }
        return menu;
    }

    @Override
    public void onClose(Player player, Inventory inventory) {
        ItemStack[] stored = new ItemStack[54];
        for (int i = 0; i < 54; i++) {
            stored[i] = inventory.getItem(i);
        }
        store.put(bagKey, stored);
    }
}
