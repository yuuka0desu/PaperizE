package com.paperize.gui;

import com.paperize.BagStore;
import com.paperize.PaperizEPlugin;
import com.paperize.PeStateIO;
import com.paperize.emc.EmcEngine;
import com.paperize.machine.MachineState;
import com.paperize.menu.MenuManager;
import com.paperize.menu.MenuModule;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 界面门面：转换台 / 机器 / 炼金袋的统一打开入口，以及"机器被接管"查询。
 *
 * <p>机器被接管期间（界面打开）节拍器跳过其物品处理，避免界面内容与状态槽竞争。
 */
public final class GuiService {

    private final PaperizEPlugin plugin;
    private final MenuManager menus;
    private final EmcEngine emc;
    private final PeStateIO state;
    private final BagStore bags;
    private final TransmutationMenu transmutationMenu;
    private final TransmutationHandler transmutationHandler;

    public GuiService(PaperizEPlugin plugin, MenuManager menus, EmcEngine emc,
                      PeStateIO state, BagStore bags) {
        this.plugin = plugin;
        this.menus = menus;
        this.emc = emc;
        this.state = state;
        this.bags = bags;
        this.transmutationMenu = new TransmutationMenu(emc, state);
        this.transmutationHandler = new TransmutationHandler(plugin, emc, state, transmutationMenu, menus);
        menus.registerModule(transmutationMenu, transmutationHandler);
    }

    public MenuManager menus() {
        return menus;
    }

    /** 打开转化桌。 */
    public void openTransmutation(Player player) {
        menus.openMenu(player, TransmutationMenu.ID);
    }

    /** 打开机器窗体。 */
    public void openMachine(Player player, MachineState machine) {
        MachineMenu module = new MachineMenu(machine, emc);
        MachineHandler handler = new MachineHandler(plugin, module);
        menus.openMenu(player, module, handler);
    }

    /** 打开炼金袋。 */
    public void openBag(Player player, String bagSimple, ItemStack hand) {
        String key = player.getUniqueId() + ":" + bagSimple;
        menus.openMenu(player, new BagMenu(key, bagSimple, bags), null);
    }

    /** 某机器当前是否被界面接管。 */
    public boolean isMachineOpen(MachineState machine) {
        for (MenuModule module : menus.activeModules()) {
            if (module instanceof MachineMenu menu && menu.machine() == machine) {
                return true;
            }
        }
        return false;
    }

    /** 关闭所有正在查看该机器玩家的界面（方块被破坏时）。 */
    public void closeViewers(MachineState machine) {
        for (var entry : menus.activeSessions().entrySet()) {
            if (entry.getValue().module() instanceof MachineMenu menu && menu.machine() == machine) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    player.closeInventory();
                }
            }
        }
    }

    public int openCount() {
        return menus.openCount();
    }

    public String describe() {
        return menus.describe();
    }
}
