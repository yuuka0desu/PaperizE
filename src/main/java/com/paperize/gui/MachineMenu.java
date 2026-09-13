package com.paperize.gui;

import com.paperize.emc.EmcEngine;
import com.paperize.machine.MachineState;
import com.paperize.menu.Menu;
import com.paperize.menu.MenuItem;
import com.paperize.menu.MenuModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;

/**
 * 机器窗体：收集器 / 继电器 / 凝聚器 / 炼金箱。
 *
 * <p>槽位映射（界面槽 = 状态槽，前 N 格）：功能槽为可交互槽位（放行物品移动），
 * 其余为装饰与信息位。打开时状态槽物品进入界面、关闭时写回（接管式防复制）。
 */
public final class MachineMenu implements MenuModule {

    public static final String ID_PREFIX = "paperize:machine:";

    private final MachineState machine;
    private final EmcEngine emc;

    public MachineMenu(MachineState machine, EmcEngine emc) {
        this.machine = machine;
        this.emc = emc;
    }

    public MachineState machine() {
        return machine;
    }

    @Override
    public String id() {
        return ID_PREFIX + machine.key();
    }

    private static String titleOf(MachineState.Type type) {
        return switch (type) {
            case COLLECTOR_MK1 -> "能量收集器 MK1";
            case COLLECTOR_MK2 -> "能量收集器 MK2";
            case COLLECTOR_MK3 -> "能量收集器 MK3";
            case RELAY_MK1 -> "反物质继电器 MK1";
            case RELAY_MK2 -> "反物质继电器 MK2";
            case RELAY_MK3 -> "反物质继电器 MK3";
            case CONDENSER_MK1 -> "物质凝聚器 MK1";
            case CONDENSER_MK2 -> "物质凝聚器 MK2";
            case ALCHEMY_CHEST -> "炼金术箱子";
            case DM_FURNACE -> "暗物质熔炉";
            case RM_FURNACE -> "红物质熔炉";
        };
    }

    @Override
    public Menu build(Player player) {
        Menu menu = new Menu(Component.text(titleOf(machine.type()), NamedTextColor.DARK_AQUA), 54);
        if (machine.type().collector()) {
            buildCollector(menu);
        } else if (machine.type().relay()) {
            buildRelay(menu);
        } else if (machine.type().furnace()) {
            buildFurnace(menu);
        } else if (machine.type().condenser()) {
            buildCondenser(menu);
        } else {
            buildDefault(menu);
        }
        return menu;
    }

    /**
     * 继电器分区渲染。
     *
     * <p>燃料（提取）槽自列 1 起（MK1 2 列 / MK2 3 列 / MK3 4 列）；
     * 输入槽 = 列 5 行 3；行 1 列 6-8 = 存储总量进度条；
     * 行 4 列 6-8 = 输入槽剩余进度条；列 7 行 2-3 = 能量之星槽；余下占位。
     */
    private void buildRelay(Menu menu) {
        org.bukkit.inventory.ItemStack[] slots = machine.slots();
        // 燃料（提取）槽
        for (int slot : machine.relayFuelSlots()) {
            if (slot < slots.length) {
                menu.setItem(slot, MenuItem.slot(slots[slot], "machine:fuel"));
            }
        }
        // 输入槽
        menu.setItem(MachineState.RELAY_INPUT_SLOT,
                MenuItem.slot(slots[MachineState.RELAY_INPUT_SLOT], "machine:input"));
        // 存储总量进度条（行 1）
        long capacity = machine.type().capacity();
        double storageProgress = capacity > 0 ? Math.min(1.0, (double) machine.emc() / capacity) : 0;
        int[] storageSlots = machine.relayStorageProgressSlots();
        int storageFilled = (int) Math.round(storageProgress * storageSlots.length);
        for (int i = 0; i < storageSlots.length; i++) {
            menu.setStatic(storageSlots[i], relayPane(i < storageFilled, "储能",
                    "存储：" + machine.emc() + " / " + capacity,
                    String.format("储量：%.1f%%", storageProgress * 100)));
        }
        // 输入槽剩余进度条（行 4）
        org.bukkit.inventory.ItemStack input = slots[MachineState.RELAY_INPUT_SLOT];
        long pendingTotal = 0;
        if (input != null && !input.getType().isAir()) {
            pendingTotal = emc.emcOf(input) * input.getAmount();
        }
        long pending = machine.work();
        double inputProgress = pendingTotal > 0 ? Math.min(1.0, (double) pending / pendingTotal) : 0;
        int[] inputSlots = machine.relayInputProgressSlots();
        int inputFilled = (int) Math.round(inputProgress * inputSlots.length);
        for (int i = 0; i < inputSlots.length; i++) {
            menu.setStatic(inputSlots[i], relayPane(i < inputFilled, "待提取",
                    "剩余：" + pending + (pendingTotal > 0 ? " / " + pendingTotal : ""),
                    String.format("剩余量：%.1f%%", inputProgress * 100)));
        }
        // 能量之星槽
        for (int slot : machine.relayStarSlots()) {
            if (slot < slots.length) {
                menu.setItem(slot, MenuItem.slot(slots[slot], "machine:star"));
            }
        }
        // 余下占位
        for (int i = 0; i < 54; i++) {
            if (!menu.items().containsKey(i)) {
                menu.setStatic(i, PeIcons.filler());
            }
        }
    }

    /**
     * 熔炉分区渲染。
     *
     * <p>行 0/行 5/列 0/列 8 占位；列 1-2 行 1-4 = 预备输入槽（8 格）；
     * 列 6-7 行 1-4 = 备用输出槽（8 格）；列 3 行 1 = 原料槽、列 3 行 3 = 燃料槽；
     * 列 5 行 1 = 输出槽；左下信息位显示 EMC 缓冲与速率。
     */
    private void buildFurnace(Menu menu) {
        org.bukkit.inventory.ItemStack[] slots = machine.slots();
        // 预备输入槽
        for (int slot : machine.furnacePreInputSlots()) {
            if (slot < slots.length) {
                menu.setItem(slot, MenuItem.slot(slots[slot], "machine:fuel"));
            }
        }
        // 备用输出槽
        for (int slot : machine.furnaceBackupOutputSlots()) {
            if (slot < slots.length) {
                menu.setItem(slot, MenuItem.slot(slots[slot], "machine:output"));
            }
        }
        // 原料 / 燃料 / 输出
        menu.setItem(MachineState.FURNACE_INPUT_SLOT,
                MenuItem.slot(slots[MachineState.FURNACE_INPUT_SLOT], "machine:input"));
        menu.setItem(MachineState.FURNACE_FUEL_SLOT,
                MenuItem.slot(slots[MachineState.FURNACE_FUEL_SLOT], "machine:fuel"));
        menu.setItem(MachineState.FURNACE_OUTPUT_SLOT,
                MenuItem.slot(slots[MachineState.FURNACE_OUTPUT_SLOT], "machine:output"));
        menu.setStatic(53, infoButton());
        // 原模组贴图外框
        applyFrame(menu);
    }

    /** 机器类型 → 原模组贴图键（与 gen_gui_tiles.py 一致）。 */
    private static String textureKey(MachineState.Type type) {
        return switch (type) {
            case COLLECTOR_MK1 -> "collector_mk1";
            case COLLECTOR_MK2 -> "collector_mk2";
            case COLLECTOR_MK3 -> "collector_mk3";
            case RELAY_MK1 -> "relay_mk1";
            case RELAY_MK2 -> "relay_mk2";
            case RELAY_MK3 -> "relay_mk3";
            case CONDENSER_MK1 -> "condenser_mk1";
            case CONDENSER_MK2 -> "condenser_mk2";
            case ALCHEMY_CHEST -> "alchemical_chest";
            case DM_FURNACE -> "dm_furnace";
            case RM_FURNACE -> "rm_furnace";
        };
    }

    /**
     * 填充空位占位（容器皮肤已由资源包替换 generic_54 底图，此处仅补黑色玻璃占位）。
     */
    private void applyFrame(Menu menu) {
        java.util.Set<Integer> functional = new java.util.HashSet<>();
        for (int slot : machine.functionalSlots()) {
            functional.add(slot);
        }
        for (int i = 0; i < 54; i++) {
            if (functional.contains(i)) {
                continue;
            }
            com.paperize.menu.MenuItem existing = menu.itemAt(i);
            if (existing != null) {
                if (existing.interactive()) {
                    continue;
                }
                org.bukkit.inventory.ItemStack stack = existing.stack();
                boolean replaceable = stack == null
                        || stack.getType() == org.bukkit.Material.BLACK_STAINED_GLASS_PANE;
                if (!replaceable) {
                    continue;
                }
            }
            menu.setStatic(i, PeIcons.filler());
        }
    }

    /** 继电器进度玻璃板（绿=亮段 / 灰=暗段）。 */
    private org.bukkit.inventory.ItemStack relayPane(boolean on, String label, String... lore) {
        org.bukkit.Material material = on
                ? org.bukkit.Material.LIME_STAINED_GLASS_PANE
                : org.bukkit.Material.GRAY_STAINED_GLASS_PANE;
        org.bukkit.inventory.ItemStack pane = new org.bukkit.inventory.ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(label, NamedTextColor.GREEN)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            java.util.List<net.kyori.adventure.text.Component> lines = new java.util.ArrayList<>();
            for (String line : lore) {
                if (line != null && !line.isEmpty()) {
                    lines.add(net.kyori.adventure.text.Component.text(line, NamedTextColor.GRAY)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
            }
            meta.lore(lines);
            pane.setItemMeta(meta);
        }
        return pane;
    }

    /**
     * 收集器分区渲染。
     *
     * <p>行 0/行 5/列 0/列 8 占位；燃料槽自列 1 起（MK1 2 列 / MK2 3 列 / MK3 4 列）；
     * 列 6：行 1 输出、行 4 输入；列 7 能量之星槽；燃料槽右侧至列 5 的上下两段为进度条。
     */
    private void buildCollector(Menu menu) {
        org.bukkit.inventory.ItemStack[] slots = machine.slots();
        // 燃料槽
        for (int slot : machine.collectorFuelSlots()) {
            if (slot < slots.length) {
                menu.setItem(slot, MenuItem.slot(slots[slot], "machine:fuel"));
            }
        }
        // 进度条（EMC 累积 / 容量）
        double progress = machine.type().capacity() > 0
                ? Math.min(1.0, (double) machine.emc() / machine.type().capacity()) : 0;
        int[] progressSlots = machine.collectorProgressSlots();
        int filled = (int) Math.round(progress * progressSlots.length);
        for (int i = 0; i < progressSlots.length; i++) {
            menu.setStatic(progressSlots[i], emcPane(i < filled, progress));
        }
        // 输入 / 输出槽
        menu.setItem(MachineState.COLLECTOR_INPUT_SLOT,
                MenuItem.slot(slots[MachineState.COLLECTOR_INPUT_SLOT], "machine:input"));
        menu.setItem(MachineState.COLLECTOR_OUTPUT_SLOT,
                MenuItem.slot(slots[MachineState.COLLECTOR_OUTPUT_SLOT], "machine:output"));
        // 能量之星槽
        for (int slot : machine.collectorStarSlots()) {
            if (slot < slots.length) {
                menu.setItem(slot, MenuItem.slot(slots[slot], "machine:star"));
            }
        }
        // 原模组贴图外框
        applyFrame(menu);
    }

    /** 收集器进度玻璃板（绿=储满段 / 灰=未满段），lore 显示 EMC 与容量。 */
    private org.bukkit.inventory.ItemStack emcPane(boolean on, double progress) {
        org.bukkit.Material material = on
                ? org.bukkit.Material.LIME_STAINED_GLASS_PANE
                : org.bukkit.Material.GRAY_STAINED_GLASS_PANE;
        org.bukkit.inventory.ItemStack pane = new org.bukkit.inventory.ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component
                    .text(on ? "储能" : "蓄能中", NamedTextColor.GREEN)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(
                    net.kyori.adventure.text.Component.text(
                                    "EMC：" + machine.emc() + " / " + machine.type().capacity(),
                                    NamedTextColor.GRAY)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                    net.kyori.adventure.text.Component.text(
                                    String.format("储能：%.1f%%", progress * 100), NamedTextColor.AQUA)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                    net.kyori.adventure.text.Component.text(
                                    "生成：" + machine.type().generation() + " EMC/s", NamedTextColor.DARK_GRAY)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            pane.setItemMeta(meta);
        }
        return pane;
    }

    /** 收集器 / 继电器 / 炼金箱：功能槽连续排布 + 信息位。 */
    private void buildDefault(Menu menu) {
        org.bukkit.inventory.ItemStack[] slots = machine.slots();
        int functional = Math.min(slots.length, 53);
        for (int i = 0; i < functional; i++) {
            menu.setItem(i, MenuItem.slot(slots[i], "machine:slot"));
        }
        for (int i = functional; i < 53; i++) {
            menu.setStatic(i, PeIcons.filler());
        }
        menu.setStatic(53, infoButton());
    }

    /**
     * 凝聚器分区渲染。
     *
     * <p>MK1：目标（第一行首）+ 行 1-4 燃料/产物同区（行首其余占位）。
     * MK2：目标 + 第一行其余为**进度条**（灰色/绿色玻璃板）、第五列分隔，
     * 左侧 4 列燃料区、右侧 4 列成品区。
     */
    private void buildCondenser(Menu menu) {
        org.bukkit.inventory.ItemStack[] slots = machine.slots();
        // 目标产物（第一行第一位）
        org.bukkit.inventory.ItemStack targetStack = slots[MachineState.CONDENSER_TARGET_SLOT];
        menu.setItem(MachineState.CONDENSER_TARGET_SLOT,
                MenuItem.slot(targetStack, "machine:target"));

        boolean mk2 = machine.type() == MachineState.Type.CONDENSER_MK2;
        long target = targetValue(slots);
        double progress = target > 0 ? Math.min(1.0, (double) machine.work() / target) : 0;
        int filled = (int) Math.round(progress * 8);

        // 第一行其余 8 格：进度条（MK1/MK2 一致）
        int col = 1;
        for (int slot : MachineState.headerSlots()) {
            menu.setStatic(slot, progressPane(col <= filled, target, progress));
            col++;
        }

        // 燃料区
        for (int slot : machine.fuelSlots()) {
            if (slot >= slots.length) {
                continue;
            }
            menu.setItem(slot, MenuItem.slot(slots[slot], "machine:fuel"));
        }
        // 产物区（MK2 右侧；MK1 与燃料区重合，已在上一步设置）
        for (int slot : machine.outputSlots()) {
            if (slot >= slots.length || isFuel(slot)) {
                continue;
            }
            menu.setItem(slot, MenuItem.slot(slots[slot], "machine:output"));
        }
        // 第五列分隔（MK2）
        for (int slot : machine.separatorSlots()) {
            menu.setStatic(slot, PeIcons.filler());
        }
        // MK1 底部信息位（MK2 底行属于成品区，信息改由进度条承载）
        if (!mk2) {
            menu.setStatic(53, infoButton());
        }
        // 原模组贴图外框
        applyFrame(menu);
    }

    private boolean isFuel(int slot) {
        for (int fuel : machine.fuelSlots()) {
            if (fuel == slot) {
                return true;
            }
        }
        return false;
    }

    /** 目标物品价值（EMC）：优先取目标槽物品，回退记录 id。 */
    private long targetValue(org.bukkit.inventory.ItemStack[] slots) {
        org.bukkit.inventory.ItemStack target = slots.length > MachineState.CONDENSER_TARGET_SLOT
                ? slots[MachineState.CONDENSER_TARGET_SLOT] : null;
        if (target != null && !target.getType().isAir()) {
            long value = emc.emcOf(target);
            if (value > 0) {
                return value;
            }
        }
        String recorded = machine.targetId();
        return recorded == null ? 0 : emc.emcOf(recorded);
    }

    /** 进度玻璃板（绿=已填充 / 灰=未填充），lore 显示点数与百分比。 */
    private org.bukkit.inventory.ItemStack progressPane(boolean on, long target, double progress) {
        org.bukkit.Material material = on
                ? org.bukkit.Material.LIME_STAINED_GLASS_PANE
                : org.bukkit.Material.GRAY_STAINED_GLASS_PANE;
        org.bukkit.inventory.ItemStack pane = new org.bukkit.inventory.ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component
                    .text(on ? "进度" : "待产出", NamedTextColor.GREEN)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(
                    net.kyori.adventure.text.Component.text(
                                    "点数：" + machine.work() + (target > 0 ? " / " + target : "（未设定目标）"),
                                    NamedTextColor.GRAY)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                    net.kyori.adventure.text.Component.text(
                                    String.format("完成度：%.1f%%", progress * 100), NamedTextColor.AQUA)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            pane.setItemMeta(meta);
        }
        return pane;
    }

    @Override
    public void onClose(Player player, Inventory inventory) {
        org.bukkit.inventory.ItemStack[] slots = machine.slots();
        // 先清空：只回写功能槽位，避免装饰玻璃板/进度条混入机器状态
        java.util.Arrays.fill(slots, null);
        for (int slot : machine.functionalSlots()) {
            if (slot < slots.length && slot < inventory.getSize()) {
                slots[slot] = inventory.getItem(slot);
            }
        }
    }

    private org.bukkit.inventory.ItemStack infoButton() {
        Material icon = switch (machine.type()) {
            case COLLECTOR_MK1, COLLECTOR_MK2, COLLECTOR_MK3 -> Material.SUNFLOWER;
            case RELAY_MK1, RELAY_MK2, RELAY_MK3 -> Material.REDSTONE_TORCH;
            case CONDENSER_MK1, CONDENSER_MK2 -> Material.ANVIL;
            case ALCHEMY_CHEST -> Material.CHEST;
            case DM_FURNACE, RM_FURNACE -> Material.FURNACE;
        };
        List<String> lore = new java.util.ArrayList<>();
        lore.add("EMC：" + machine.emc() + (machine.type().capacity() > 0 ? " / " + machine.type().capacity() : ""));
        if (machine.type().collector()) {
            lore.add("生成：" + machine.type().generation() + " EMC/s（光照 ≥12 全额）");
            lore.add("输入槽（第 10 格）放入物品可燃烧为 EMC");
        }
        if (machine.type().relay()) {
            lore.add("EMC 中转（容量 " + machine.type().capacity() + "）");
        }
        if (machine.type().condenser()) {
            lore.add("进度：" + machine.work());
            lore.add("目标：" + (machine.targetId() == null ? "未设定" : machine.targetId()));
            lore.add("输入区放入物品燃烧，目标槽设定产物");
        }
        if (machine.type().furnace()) {
            lore.add("烧炼：" + machine.type().smeltPerTick() + " 个/秒（单次 " + machine.type().smeltCost() + " EMC）");
            lore.add(machine.type() == MachineState.Type.RM_FURNACE
                    ? "金属提炼：金属矿石 50% 双倍产出"
                    : "暗物质熔炉：高效烧炼");
            lore.add("左侧预备输入、右侧备用输出（自动转入相邻箱子）");
        }
        return PeIcons.button(icon, machine.type().name(), NamedTextColor.GOLD, lore.toArray(new String[0]));
    }

    /** 信息按钮刷新（供处理器调用）。 */
    public org.bukkit.inventory.ItemStack refreshInfo() {
        return infoButton();
    }

    /** 功能槽数量（供处理器判定）。 */
    public int functionalSlots() {
        return Math.min(machine.slots().length, 53);
    }

    public long emcOf(org.bukkit.inventory.ItemStack stack) {
        return emc.emcOf(stack);
    }
}
