package com.paperize;

import com.paperize.emc.EmcEngine;
import com.paperize.gui.GuiService;
import com.paperize.item.PeItemLogic;
import com.paperize.machine.MachineRunner;
import com.paperize.machine.MachineState;
import com.paperize.machine.MachineStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件桥：CraftEngine 自定义方块（放置/破坏/交互）+ 玩家交互兜底 + 玩家载入。
 *
 * <p>方块交互为**双通道**：
 * <ol>
 *   <li>主通道 {@link CustomBlockInteractEvent}（CE 派发）；</li>
 *   <li>兜底通道 {@link PlayerInteractEvent} + CE 方块状态识别
 *       （覆盖 CE 因同 tick 交互去重等原因未派发的情况）；</li>
 *   <li>两通道共享 3 tick 打开防抖，保证只打开一次界面。</li>
 * </ol>
 */
public final class PeListener implements Listener {

    /** 打开防抖窗口（tick）。 */
    private static final int OPEN_DEBOUNCE_TICKS = 3;

    private final PaperizEPlugin plugin;
    private final MachineStore machines;
    private final MachineRunner runner;
    private final GuiService gui;
    private final PeItemLogic itemLogic;
    private final EmcEngine emc;
    private final Map<UUID, Integer> lastOpenTick = new ConcurrentHashMap<>();

    public PeListener(PaperizEPlugin plugin, MachineStore machines, MachineRunner runner,
                      GuiService gui, PeItemLogic itemLogic, EmcEngine emc) {
        this.plugin = plugin;
        this.machines = machines;
        this.runner = runner;
        this.gui = gui;
        this.itemLogic = itemLogic;
        this.emc = emc;
    }

    private static String simple(net.momirealms.craftengine.core.util.Key key) {
        return key.value();
    }

    // ---- 放置 / 破坏 ----

    @EventHandler
    public void onPlace(CustomBlockPlaceEvent event) {
        var key = event.customBlock().id();
        if (!"projecte".equals(key.namespace())) {
            return;
        }
        MachineState.Type type = MachineState.Type.of(simple(key));
        if (type == null) {
            return;
        }
        Location location = event.location();
        machines.getOrCreate(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), type);
        plugin.getLogger().info("机器登记：" + key + " @ " + PeItemLogic.format(location));
    }

    @EventHandler
    public void onBreak(CustomBlockBreakEvent event) {
        var key = event.customBlock().id();
        if (!"projecte".equals(key.namespace())) {
            return;
        }
        Location location = event.location();
        MachineState state = machines.get(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (state == null) {
            return;
        }
        gui.closeViewers(state);
        event.setDropItems(false);
        // 只掉落功能槽位物品（装饰/进度位不计入）
        org.bukkit.inventory.ItemStack[] slots = state.slots();
        for (int slot : state.functionalSlots()) {
            if (slot >= slots.length) {
                continue;
            }
            ItemStack stack = slots[slot];
            if (stack != null && !stack.getType().isAir()) {
                location.getWorld().dropItemNaturally(location.clone().add(0.5, 0.5, 0.5), stack);
            }
        }
        machines.remove(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        plugin.getLogger().info("机器注销：" + key + " @ " + PeItemLogic.format(location));
    }

    // ---- 方块交互：主通道 ----

    @EventHandler
    public void onInteract(CustomBlockInteractEvent event) {
        var key = event.customBlock().id();
        if (!"projecte".equals(key.namespace())) {
            return;
        }
        Player player = event.player();
        Location location = event.location();
        if (openFor(player, location, simple(key))) {
            event.setCancelled(true);
        }
    }

    // ---- 方块交互：兜底通道 ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onBlockUseFallback(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        String simple = projecteBlockSimple(block);
        if (simple == null) {
            return;
        }
        if (openFor(event.getPlayer(), block.getLocation(), simple)) {
            event.setCancelled(true);
        }
    }

    /** 识别 projecte 自定义方块的简单名（非 projecte 返回 null）。 */
    private String projecteBlockSimple(Block block) {
        try {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state == null || state.isEmpty()) {
                return null;
            }
            var key = state.owner().value().id();
            return "projecte".equals(key.namespace()) ? key.value() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 打开方块对应的界面（机器面板 / 转化桌），带 3 tick 防抖。
     *
     * @return true 表示已处理（调用方应取消事件）
     */
    private boolean openFor(Player player, Location location, String simple) {
        // 暗物质台座：手持物品右键放入 / 空手（或潜行）右键取下（11³ 范围效果由节拍驱动）
        if ("dm_pedestal".equals(simple)) {
            return itemLogic.pedestalInteract(player, location.getBlock(),
                    player.getInventory().getItemInMainHand());
        }
        MachineState.Type type = MachineState.Type.of(simple);
        boolean transmutation = "transmutation_table".equals(simple);
        if (type == null && !transmutation) {
            return false;
        }
        // 防抖（双通道去重）
        int tick = Bukkit.getCurrentTick();
        Integer last = lastOpenTick.get(player.getUniqueId());
        if (last != null && tick - last < OPEN_DEBOUNCE_TICKS) {
            return true;
        }
        lastOpenTick.put(player.getUniqueId(), tick);

        if (type != null) {
            MachineState state = machines.getOrCreate(location.getWorld().getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ(), type);
            gui.openMachine(player, state);
            plugin.getLogger().info("打开机器界面：" + simple + " @ " + PeItemLogic.format(location)
                    + " by " + player.getName());
            return true;
        }
        gui.openTransmutation(player);
        plugin.getLogger().info("打开转化桌：" + PeItemLogic.format(location) + " by " + player.getName());
        return true;
    }

    // ---- 物品使用（世界转换 / 戒指 / 平板等） ----

    @EventHandler
    public void onUseItem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            return;
        }
        var data = plugin.state().player(player);

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            if (itemLogic.onBlockRightClick(player, hand, event.getClickedBlock())) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (itemLogic.onUse(player, hand, data)) {
                event.setCancelled(true);
            }
        }
    }

    /** 暗物质/红物质近战武器的横扫：命中时对周围目标溅射伤害。 */
    @EventHandler
    public void onWeaponDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || event.getEntity() == player) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String id = emc.idOf(hand);
        if (id == null) {
            return;
        }
        boolean red = id.contains(":rm_sword") || id.contains(":rm_axe") || id.contains(":rm_katar")
                || id.contains(":rm_morning_star");
        boolean dark = id.contains(":dm_sword") || id.contains(":dm_axe") || id.contains(":dm_hammer");
        if (!red && !dark) {
            return;
        }
        double splash = red ? 0.7 : 0.4;
        for (org.bukkit.entity.Entity nearby : player.getNearbyEntities(3.0, 2.0, 3.0)) {
            if (nearby instanceof org.bukkit.entity.LivingEntity living
                    && living != player && !living.equals(event.getEntity())) {
                living.damage(event.getDamage() * splash, player);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var data = plugin.state().player(player);
        emc.idOf(player.getInventory().getItemInMainHand());
        if (data.knowledgeCount() == 0) {
            player.sendMessage(Component.text("PaperizE 已就绪：右键转化桌打开界面（放入物品学习价值）",
                    NamedTextColor.LIGHT_PURPLE));
        }
    }
}
