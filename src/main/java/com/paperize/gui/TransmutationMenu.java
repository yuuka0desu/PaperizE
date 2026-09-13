package com.paperize.gui;

import com.paperize.PeStateIO;
import com.paperize.emc.EmcEngine;
import com.paperize.emc.PePlayerData;
import com.paperize.menu.Menu;
import com.paperize.menu.MenuModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转换台菜单：知识列表（分页取物）+ 学习槽 + 余额显示。
 *
 * <p>布局（54 格）：
 * <pre>
 *   0-44  已学习物品（按 EMC 升序，45 个/页）
 *   45    个人余额与知识量
 *   48    上一页      49 学习槽（可放入物品）      50 下一页
 * </pre>
 * 取物动作：左键 1 个 / 右键 8 个 / Shift+左键 64 个（余额允许时）。
 */
public final class TransmutationMenu implements MenuModule {

    public static final String ID = "paperize:transmutation";
    /** 每页条目数。 */
    public static final int PAGE_SIZE = 45;
    /**
     * 学习区：连续 5 格可交互槽位（46-50）。
     *
     * <p>连续区域是刻意的：玩家拖拽物品时的落点散布在相邻格，
     * 单格学习槽容易因碰到装饰格而被判为非法拖拽。
     */
    public static final java.util.List<Integer> LEARN_SLOTS = java.util.List.of(46, 47, 48, 49, 50);

    private final EmcEngine emc;
    private final PeStateIO state;
    private final Map<UUID, Integer> pages = new ConcurrentHashMap<>();

    public TransmutationMenu(EmcEngine emc, PeStateIO state) {
        this.emc = emc;
        this.state = state;
    }

    @Override
    public String id() {
        return ID;
    }

    /** 当前页码（供处理器翻页）。 */
    public int page(Player player) {
        return pages.getOrDefault(player.getUniqueId(), 0);
    }

    public void setPage(Player player, int page, int totalPages) {
        if (totalPages <= 0) {
            pages.put(player.getUniqueId(), 0);
            return;
        }
        int clamped = Math.max(0, Math.min(page, totalPages - 1));
        pages.put(player.getUniqueId(), clamped);
    }

    /** 知识列表（过滤无值项，按 EMC 升序）。 */
    public List<String> known(Player player) {
        PePlayerData data = state.player(player);
        List<String> known = new ArrayList<>(data.knowledge());
        known.removeIf(id -> emc.emcOf(id) <= 0);
        known.sort((a, b) -> Long.compare(emc.emcOf(a), emc.emcOf(b)));
        return known;
    }

    public int totalPages(List<String> known) {
        return Math.max(1, (known.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    @Override
    public Menu build(Player player) {
        PePlayerData data = state.player(player);
        List<String> known = known(player);
        int totalPages = totalPages(known);
        int page = Math.min(page(player), totalPages - 1);
        pages.put(player.getUniqueId(), page);

        Menu menu = new Menu(Component.text("转化桌", NamedTextColor.DARK_PURPLE), 54);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < known.size(); i++) {
            String id = known.get(start + i);
            ItemStack display = PeIcons.withEmcLore(PeIcons.build(id, 1), emc.emcOf(id));
            if (display == null) {
                continue;
            }
            menu.setItem(i, new com.paperize.menu.MenuItem(display, "take:" + id, false));
        }
        // 空位填充
        menu.fillRange(0, PAGE_SIZE - 1, PeIcons.filler());

        // 控制行：余额 / 学习区（46-50，可放入物品）/ 翻页
        menu.setStatic(45, PeIcons.button(Material.KNOWLEDGE_BOOK,
                "个人余额：" + data.emc() + " EMC", NamedTextColor.GOLD,
                "已学习 " + known.size() + " 种物品",
                "左键取 1 / 右键取 8 / Shift+左键取 64"));
        if (page > 0) {
            menu.setItem(51, new com.paperize.menu.MenuItem(
                    PeIcons.button(Material.ARROW, "上一页（" + page + "/" + totalPages + "）", NamedTextColor.YELLOW),
                    "page:prev", false));
        } else {
            menu.setStatic(51, PeIcons.filler());
        }
        menu.setStatic(52, PeIcons.button(Material.EXPERIENCE_BOTTLE, "学习 / 转换区（中间 5 格）", NamedTextColor.LIGHT_PURPLE,
                "放入物品 → 全部转换为 EMC 入账（首次同时学习）",
                "Shift+点击背包物品 或 拖入 或 手持点击",
                "点击上方列表可消耗余额取出物品"));
        if (page + 1 < totalPages) {
            menu.setItem(53, new com.paperize.menu.MenuItem(
                    PeIcons.button(Material.ARROW, "下一页（" + (page + 2) + "/" + totalPages + "）", NamedTextColor.YELLOW),
                    "page:next", false));
        } else {
            menu.setStatic(53, PeIcons.filler());
        }
        // 学习区（连续 5 格，可交互）
        for (int slot : LEARN_SLOTS) {
            menu.setItem(slot, new com.paperize.menu.MenuItem(null, "learn", true));
        }
        return menu;
    }
}
