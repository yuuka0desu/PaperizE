package com.paperize;

import com.paperize.emc.EmcEngine;
import com.paperize.emc.PePlayerData;
import com.paperize.gui.GuiService;
import com.paperize.gui.PeIcons;
import com.paperize.machine.MachineRunner;
import com.paperize.machine.MachineStore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * /paperize（别名 /pe）—— 炼金命令：
 * emc（价值查询）、balance（余额）、learn（学习手持）、give（给予）、
 * table（打开转换台）、machines（机器列表）、reload（重算 EMC）、stats（统计）。
 */
public final class PeCommand implements BasicCommand {

    private final PaperizEPlugin plugin;
    private final EmcEngine emc;
    private final MachineStore machines;
    private final MachineRunner runner;
    private final GuiService gui;

    public PeCommand(PaperizEPlugin plugin, EmcEngine emc, MachineStore machines,
                     MachineRunner runner, GuiService gui) {
        this.plugin = plugin;
        this.emc = emc;
        this.machines = machines;
        this.runner = runner;
        this.gui = gui;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            help(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "emc" -> queryEmc(sender, args);
            case "balance" -> balance(sender);
            case "learn" -> learn(sender);
            case "give" -> give(sender, args);
            case "table" -> table(sender);
            case "machines" -> machines(sender);
            case "reload" -> reload(sender);
            case "stats" -> stats(sender);
            default -> help(sender);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return List.of("help", "emc", "balance", "learn", "give", "table", "machines", "reload", "stats");
        }
        if (args.length == 2 && "emc".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String id : emc.knownIds()) {
                if (id.startsWith(prefix) && out.size() < 40) {
                    out.add(id);
                }
            }
            return out;
        }
        return List.of();
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("PaperizE 命令：", NamedTextColor.LIGHT_PURPLE));
        sender.sendMessage(Component.text("/pe emc [物品] — 查询 EMC 价值（默认手持）", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/pe balance — 个人 EMC 余额与知识量", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/pe learn — 学习手持物品", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/pe give <物品> [数量] — 给予内容物品", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/pe table — 打开转换台", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/pe machines — 机器列表", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/pe reload — 重算 EMC 表", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/pe stats — 引擎统计", NamedTextColor.GRAY));
    }

    private void queryEmc(CommandSender sender, String[] args) {
        String id;
        if (args.length >= 2) {
            id = args[1].contains(":") ? args[1] : "minecraft:" + args[1];
        } else if (sender instanceof Player player) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            id = emc.idOf(hand);
            if (id == null) {
                sender.sendMessage(Component.text("手持物品无法识别", NamedTextColor.RED));
                return;
            }
        } else {
            sender.sendMessage(Component.text("用法：/pe emc <物品id>", NamedTextColor.RED));
            return;
        }
        long value = emc.emcOf(id);
        if (value <= 0) {
            sender.sendMessage(Component.text(id + " 无 EMC 值", NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text(id + " = " + value + " EMC", NamedTextColor.AQUA));
        }
    }

    private void balance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("仅玩家可用", NamedTextColor.RED));
            return;
        }
        PePlayerData data = plugin.state().player(player);
        sender.sendMessage(Component.text("余额 " + data.emc() + " EMC / 已学习 " + data.knowledgeCount() + " 种",
                NamedTextColor.GOLD));
    }

    private void learn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("仅玩家可用", NamedTextColor.RED));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String id = emc.idOf(hand);
        long value = emc.emcOf(id);
        if (value <= 0) {
            sender.sendMessage(Component.text("手持物品无 EMC 价值", NamedTextColor.RED));
            return;
        }
        PePlayerData data = plugin.state().player(player);
        boolean fresh = data.learn(id);
        sender.sendMessage(Component.text(fresh
                ? "已学习 " + id + "（EMC " + value + "）"
                : "已掌握 " + id, NamedTextColor.GREEN));
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("paperize.admin")) {
            sender.sendMessage(Component.text("缺少权限 paperize.admin", NamedTextColor.RED));
            return;
        }
        if (!(sender instanceof Player player) || args.length < 2) {
            sender.sendMessage(Component.text("用法：/pe give <物品id> [数量]", NamedTextColor.RED));
            return;
        }
        String id = args[1].contains(":") ? args[1] : "projecte:" + args[1];
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(2304, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                // 保持默认
            }
        }
        ItemStack stack = PeIcons.build(id, amount);
        if (stack == null) {
            sender.sendMessage(Component.text("无法构造物品: " + id, NamedTextColor.RED));
            return;
        }
        player.getInventory().addItem(stack).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        sender.sendMessage(Component.text("已给予 " + amount + " × " + id, NamedTextColor.GREEN));
    }

    private void table(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("仅玩家可用", NamedTextColor.RED));
            return;
        }
        gui.openTransmutation(player);
    }

    private void machines(CommandSender sender) {
        sender.sendMessage(Component.text("机器 " + machines.size() + " 台：", NamedTextColor.LIGHT_PURPLE));
        for (String line : runner.describe()) {
            sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("paperize.admin")) {
            sender.sendMessage(Component.text("缺少权限 paperize.admin", NamedTextColor.RED));
            return;
        }
        String note = plugin.rebuildEmc();
        sender.sendMessage(Component.text("EMC 重算完成：" + note, NamedTextColor.GREEN));
    }

    private void stats(CommandSender sender) {
        sender.sendMessage(Component.text("PaperizE 统计", NamedTextColor.LIGHT_PURPLE));
        sender.sendMessage(Component.text("  " + emc.describe(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  " + plugin.state().describe(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  打开界面 " + gui.openCount() + " 个 / 炼金袋 " + plugin.bagStore().count() + " 个",
                NamedTextColor.GRAY));
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission("paperize.use") || sender.isOp();
    }
}
