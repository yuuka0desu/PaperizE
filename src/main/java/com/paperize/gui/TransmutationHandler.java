package com.paperize.gui;

import com.paperize.PaperizEPlugin;
import com.paperize.PeStateIO;
import com.paperize.emc.EmcEngine;
import com.paperize.emc.PePlayerData;
import com.paperize.menu.ActionMapEventHandler;
import com.paperize.menu.MenuItem;
import com.paperize.menu.MenuManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 转换台交互：翻页、取物（左键 1 / 右键 8 / Shift 64）、学习槽结算。
 *
 * <p>学习槽为可交互槽位（放行原版移动），点击后下一 tick 结算：
 * 首次学习消耗 1 个，已掌握则不消耗；剩余物品归还玩家。
 */
public final class TransmutationHandler extends ActionMapEventHandler {

    private final PaperizEPlugin plugin;
    private final EmcEngine emc;
    private final PeStateIO state;
    private final TransmutationMenu menu;
    private final MenuManager menus;

    public TransmutationHandler(PaperizEPlugin plugin, EmcEngine emc, PeStateIO state,
                                TransmutationMenu menu, MenuManager menus) {
        this.plugin = plugin;
        this.emc = emc;
        this.state = state;
        this.menu = menu;
        this.menus = menus;

        bind("page:prev", (player, item, event) -> {
            List<String> known = menu.known(player);
            menu.setPage(player, menu.page(player) - 1, menu.totalPages(known));
            menus.refresh(player);
        });
        bind("page:next", (player, item, event) -> {
            List<String> known = menu.known(player);
            menu.setPage(player, menu.page(player) + 1, menu.totalPages(known));
            menus.refresh(player);
        });
        bind("learn", (player, item, event) ->
                Bukkit.getScheduler().runTask(plugin, () -> settleLearn(player)));
    }

    @Override
    public void handleClick(Player player, MenuItem item, InventoryClickEvent event) {
        String action = item.actionId();
        if (action != null && action.startsWith("take:")) {
            take(player, action.substring("take:".length()), event.isShiftClick(), event.isRightClick());
            return;
        }
        super.handleClick(player, item, event);
    }

    /** 取物：消耗余额 → 发放物品。 */
    private void take(Player player, String id, boolean shift, boolean right) {
        long unit = emc.emcOf(id);
        if (unit <= 0) {
            player.sendActionBar(Component.text("该物品无 EMC 价值", NamedTextColor.RED));
            return;
        }
        PePlayerData data = state.player(player);
        int count = shift ? 64 : (right ? 8 : 1);
        long maxAffordable = data.emc() / unit;
        count = (int) Math.min(count, Math.max(0, maxAffordable));
        if (count <= 0) {
            player.sendActionBar(Component.text("余额不足（需要 " + unit + " EMC）", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            return;
        }
        ItemStack give = PeIcons.build(id, count);
        if (give == null) {
            player.sendActionBar(Component.text("无法构造物品: " + id, NamedTextColor.RED));
            return;
        }
        long cost = unit * count;
        data.spendEmc(cost);
        player.getInventory().addItem(give).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.5f);
        player.sendActionBar(Component.text("取出 " + count + " × " + id + "（-" + cost + " EMC）", NamedTextColor.GREEN));
        menus.refresh(player);
    }

    /** 学习区结算（点击/拖入后下一 tick）：逐格处理 46-50。 */
    private void settleLearn(Player player) {
        if (!menus.hasSession(player) || !TransmutationMenu.ID.equals(menus.sessionModule(player))) {
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        PePlayerData data = state.player(player);
        boolean changed = false;
        for (int slot : TransmutationMenu.LEARN_SLOTS) {
            ItemStack stack = top.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            String id = emc.idOf(stack);
            long unit = emc.emcOf(id);
            if (unit <= 0) {
                player.sendActionBar(Component.text("该物品无 EMC 价值（已归还）", NamedTextColor.RED));
            } else {
                // 转化桌语义：放入即消耗 → 转换为 EMC 入账；未学过则同时学习
                int amount = stack.getAmount();
                long total = unit * amount;
                boolean fresh = data.learn(id);
                long accepted = data.addEmc(total);
                player.sendActionBar(Component.text(
                        (fresh ? "已学习并转换 " : "已转换 ") + amount + " × " + id
                                + " = +" + accepted + " EMC（余额 " + data.emc() + "）",
                        NamedTextColor.GREEN));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                        0.5f, fresh ? 1.8f : 1.2f);
            }
            // 归还该槽剩余（转换成功后为空；无价值物品原样归还）
            ItemStack rest = top.getItem(slot);
            top.setItem(slot, null);
            if (rest != null && !rest.getType().isAir()) {
                player.getInventory().addItem(rest).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
            changed = true;
        }
        if (changed) {
            menus.refresh(player);
        }
    }
}
