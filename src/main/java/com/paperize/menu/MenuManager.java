package com.paperize.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 菜单管理器：会话管理 + 点击/拖拽/关闭路由 + 点击节流。
 *
 * <p>对齐 PD 的 {@code MenuManager}：
 * <ul>
 *   <li>打开会话按玩家索引；模块按 id 注册（{@link MenuModule}）；</li>
 *   <li>静态控件（interactive=false）点击取消并路由到 {@link ActionMapEventHandler}；</li>
 *   <li>可交互槽位（interactive=true）放行原版物品移动（机器槽位/学习槽）；</li>
 *   <li>2 tick 点击节流：防止单次点击被多个处理器重复消费；</li>
 *   <li>关闭时回调模块 {@code onClose}，把界面内容写回领域状态。</li>
 * </ul>
 */
public final class MenuManager implements Listener {

    /** 打开会话：模块 + 菜单声明 + 实际物品栏 + 界面标识 holder（关闭判定基准）。 */
    public record MenuSession(MenuModule module, Menu menu, Inventory inventory,
                              ActionMapEventHandler handler, MenuHolder holder) {
        public ActionMapEventHandler handlerOrDefault(ActionMapEventHandler fallback) {
            return handler == null ? fallback : handler;
        }
    }

    private final Map<UUID, MenuSession> openSessions = new ConcurrentHashMap<>();
    private final Map<String, MenuModule> modules = new ConcurrentHashMap<>();
    private final Map<String, ActionMapEventHandler> handlers = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastClickTick = new ConcurrentHashMap<>();
    private final Logger log;
    private org.bukkit.plugin.Plugin plugin;

    public MenuManager(Logger log) {
        this.log = log;
    }

    /** 注入插件引用（调度静态槽清理任务）。 */
    public void attach(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
    }

    private org.bukkit.plugin.Plugin plugin() {
        if (plugin == null) {
            throw new IllegalStateException("MenuManager 未注入插件引用（attach 未调用）");
        }
        return plugin;
    }

    public void registerModule(MenuModule module, ActionMapEventHandler handler) {
        modules.put(module.id(), module);
        if (handler != null) {
            handlers.put(module.id(), handler);
        }
    }

    /** 打开菜单；模块不存在返回 false。 */
    public boolean openMenu(Player player, String moduleId) {
        MenuModule module = modules.get(moduleId);
        if (module == null) {
            log.warning("菜单模块未注册: " + moduleId);
            return false;
        }
        return openMenu(player, module, handlers.get(moduleId));
    }

    /** 打开动态模块（机器等按实例打开，处理器随会话携带）。 */
    public boolean openMenu(Player player, MenuModule module, ActionMapEventHandler handler) {
        Menu menu = module.build(player);
        MenuHolder holder = new MenuHolder(module.id());
        Inventory inventory = Bukkit.createInventory(holder, menu.size(), menu.title());
        holder.bind(inventory);
        for (Map.Entry<Integer, MenuItem> entry : menu.items().entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue().stack());
        }
        openSessions.put(player.getUniqueId(), new MenuSession(module, menu, inventory, handler, holder));
        player.openInventory(inventory);
        log.info(String.format("[菜单] 打开 %s（%d 格）玩家=%s 会话数=%d",
                module.id(), menu.size(), player.getName(), openSessions.size()));
        return true;
    }

    /** 事件来源判定：界面是否为本系统创建的菜单（基于 holder，引用稳定）。 */
    private MenuSession sessionOf(Player player, Inventory top) {
        if (top == null || !(top.getHolder() instanceof MenuHolder holder)) {
            return null;
        }
        MenuSession session = openSessions.get(player.getUniqueId());
        if (session == null || !session.module().id().equals(holder.moduleId())) {
            return null;
        }
        return session;
    }

    /**
     * 重开当前菜单（刷新内容，保持在会话中）。
     *
     * <p>重开在下一 tick 执行：{@code openInventory} 会同步触发旧界面的关闭事件，
     * 若在同一 tick 内先建新会话再关闭旧界面，关闭事件会误删新会话
     * （表现为"第一次操作有效、之后界面失去响应"）。
     */
    public void refresh(Player player) {
        MenuSession session = openSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        MenuModule module = session.module();
        session.module().onClose(player, session.inventory());
        openSessions.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin(), () -> {
            if (player.isOnline() && !hasSession(player)) {
                openMenu(player, module, session.handler());
            }
        });
    }

    public void closeMenu(Player player) {
        openSessions.remove(player.getUniqueId());
        player.closeInventory();
    }

    public boolean hasSession(Player player) {
        return openSessions.containsKey(player.getUniqueId());
    }

    public String sessionModule(Player player) {
        MenuSession session = openSessions.get(player.getUniqueId());
        return session == null ? null : session.module().id();
    }

    public int openCount() {
        return openSessions.size();
    }

    // ---- 事件路由 ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        MenuSession session = sessionOf(player, event.getView().getTopInventory());
        if (session == null) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() == null || !(event.getClickedInventory().getHolder() instanceof MenuHolder)) {
            // 玩家背包区：Shift 点击 → 快速移入第一个可交互槽位（PD 风格）
            if (event.isShiftClick() && event.getClickedInventory() != null) {
                quickMove(player, session, event);
            }
            return;
        }
        int slot = event.getSlot();
        if (slot < 0 || slot >= session.menu().size()) {
            event.setCancelled(true);
            return;
        }
        MenuItem item = session.menu().itemAt(slot);
        if (item == null) {
            event.setCancelled(true); // 未定义槽位：保护
            return;
        }
        // 点击节流（2 tick）
        int tick = Bukkit.getCurrentTick();
        Integer last = lastClickTick.get(player.getUniqueId());
        if (last != null && tick - last < 2) {
            event.setCancelled(true);
            return;
        }
        lastClickTick.put(player.getUniqueId(), tick);

        ActionMapEventHandler handler = session.handler();
        if (item.interactive()) {
            // 放行物品移动；同时通知模块（可自行取消）
            if (handler != null) {
                handler.handleClick(player, item, event);
            }
            return;
        }
        event.setCancelled(true);
        if (handler != null) {
            try {
                handler.handleClick(player, item, event);
            } catch (Throwable t) {
                log.warning("菜单点击处理异常 [" + session.module().id() + "/" + item.actionId() + "]: " + t);
            }
        }
    }

    /**
     * 拖拽处理（HIGHEST：在其它插件之后最终裁决）。
     *
     * <p>落点全部为可交互槽位 → 放行；落点含静态槽位 → 拒绝（物品弹回是取消的固有行为）。
     * 每次裁决写入日志，便于在日志中定位"拖拽被谁拦截"。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        MenuSession session = sessionOf(player, event.getView().getTopInventory());
        if (session == null) {
            return;
        }
        boolean touchesMenu = false;
        boolean allInteractive = true;
        for (int slot : event.getRawSlots()) {
            if (slot < 0 || slot >= session.menu().size()) {
                continue; // 玩家背包区
            }
            touchesMenu = true;
            MenuItem item = session.menu().itemAt(slot);
            if (item == null || !item.interactive()) {
                allInteractive = false;
                break;
            }
        }
        if (!touchesMenu) {
            return; // 纯背包内拖拽
        }
        if (allInteractive) {
            event.setCancelled(false);
            log.info(String.format("[界面拖拽] 放行 模块=%s 落点=%s",
                    session.module().id(), event.getRawSlots()));
        } else {
            event.setCancelled(true);
            log.info(String.format("[界面拖拽] 拒绝（落点含静态槽）模块=%s 落点=%s",
                    session.module().id(), event.getRawSlots()));
        }
    }

    /** Shift 点击快速移入：把背包物品送入第一个可交互空/同类槽位。 */
    private void quickMove(Player player, MenuSession session, InventoryClickEvent event) {
        ItemStack moving = event.getCurrentItem();
        if (moving == null || moving.getType().isAir()) {
            return;
        }
        ActionMapEventHandler handler = session.handler();
        for (Map.Entry<Integer, MenuItem> entry : session.menu().items().entrySet()) {
            MenuItem item = entry.getValue();
            if (!item.interactive()) {
                continue;
            }
            if (handler != null && !handler.canQuickMove(player, item, moving)) {
                continue;
            }
            ItemStack slot = session.inventory().getItem(entry.getKey());
            boolean empty = slot == null || slot.getType().isAir();
            boolean stackable = !empty && slot.isSimilar(moving)
                    && slot.getAmount() < slot.getMaxStackSize();
            if (!empty && !stackable) {
                continue;
            }
            event.setCancelled(true);
            if (empty) {
                session.inventory().setItem(entry.getKey(), moving.clone());
                event.setCurrentItem(null);
            } else {
                int space = slot.getMaxStackSize() - slot.getAmount();
                int move = Math.min(space, moving.getAmount());
                slot.setAmount(slot.getAmount() + move);
                int rest = moving.getAmount() - move;
                if (rest <= 0) {
                    event.setCurrentItem(null);
                } else {
                    moving.setAmount(rest);
                }
            }
            log.info(String.format("[界面快捷移入] 模块=%s 目标槽=%d 物品=%s",
                    session.module().id(), entry.getKey(), moving.getType().name()));
            if (handler != null) {
                handler.handleClick(player, item, event);
            }
            return;
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        MenuSession session = openSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder) || holder != session.holder()) {
            // 关闭的不是当前会话的界面（如刷新流程中 openInventory 触发的旧界面关闭）：
            // 不能删除当前会话，否则界面将失去事件路由（表现为"第二次操作无效"）。
            log.info(String.format("[菜单] 忽略非当前界面的关闭 模块=%s 玩家=%s",
                    session.module().id(), player.getName()));
            return;
        }
        openSessions.remove(player.getUniqueId());
        lastClickTick.remove(player.getUniqueId());
        log.info(String.format("[菜单] 关闭 %s 玩家=%s", session.module().id(), player.getName()));
        try {
            session.module().onClose(player, session.inventory());
        } catch (Throwable t) {
            log.warning("菜单关闭回调异常 [" + session.module().id() + "]: " + t);
        }
    }

    /** 当前打开的模块实例（机器接管判定用）。 */
    public java.util.List<MenuModule> activeModules() {
        return openSessions.values().stream().map(MenuSession::module).toList();
    }

    /** 当前会话视图（UUID → 会话）。 */
    public Map<UUID, MenuSession> activeSessions() {
        return Map.copyOf(openSessions);
    }

    /** 会话摘要（命令/调试）。 */
    public String describe() {
        return "打开会话 " + openSessions.size() + " / 模块 " + modules.size() + "（" + String.join(", ", modules.keySet()) + "）";
    }

    /** 便捷：构建空物品栏图标占位。 */
    public static ItemStack nullSafe(ItemStack stack) {
        return stack == null ? null : stack;
    }

    /** 便捷：把 Map<String,Object> 描述转 Component（模块自定义标题用）。 */
    public static Component plain(String text) {
        return Component.text(text);
    }
}
