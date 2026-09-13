package com.paperize;

import com.ceplus.CEPlusPlugin;
import com.paperize.content.PeRegistry;
import com.paperize.emc.EmcEngine;
import com.paperize.emc.EmcGraphMapper;
import com.paperize.emc.EmcLoader;
import com.paperize.emc.EmcTagResolver;
import com.paperize.gui.GuiService;
import com.paperize.menu.MenuManager;
import com.paperize.item.PeItemLogic;
import com.paperize.machine.MachineRunner;
import com.paperize.machine.MachineStore;
import com.paperize.world.PeWorldTransmutation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * PaperizE 主入口：ProjectE（等价交换）内容项目，由 CEPlus 引擎承载。
 *
 * <p>职责：
 * <ol>
 *   <li>向 CEPlus 注册内容提供者（资产 + 注册表），并把注册表转为 CraftEngine 内容包 YAML；</li>
 *   <li>构建 EMC 引擎（标签 → 转换/配方 → 图求解 → 运行时查询表）；</li>
 *   <li>装配机器（收集器/继电器/凝聚器/炼金箱）、GUI 与命令。</li>
 * </ol>
 */
public final class PaperizEPlugin extends JavaPlugin {

    private PeContentProvider provider;
    private EmcEngine emc;
    private EmcTagResolver tags;
    private MachineStore machines;
    private MachineRunner runner;
    private PeStateIO state;
    private BagStore bagStore;
    private GuiService gui;
    private MenuManager menus;
    private PeItemLogic itemLogic;
    private PeWorldTransmutation worldTransmutation;
    private PeRegistry registry;
    /** 暗物质台座上的物品："world@x,y,z" → 物品。 */
    private final java.util.Map<String, ItemStack> pedestalItems = new java.util.concurrent.ConcurrentHashMap<>();
    private int loadIndex;

    @Override
    public void onEnable() {
        CEPlusPlugin engine = (CEPlusPlugin) Bukkit.getPluginManager().getPlugin("CEPlus");
        if (engine == null) {
            getLogger().severe("未找到 CEPlus 引擎：PaperizE 需要它作为加载引擎。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        provider = new PeContentProvider(this);

        // ---- 标签（EMC 依赖标签展开，先行装载） ----
        tags = new EmcTagResolver(getLogger());
        try {
            PeTagLoader.Result result = PeTagLoader.load(this, provider, tags);
            getLogger().info(String.format("标签装载完成：写入/更新 %d 个文件，装载 %d 个标签文件（原版补齐 %d，标签总数 %d）",
                    result.written(), result.loadedFiles(), result.vanityTags(), result.tagCount()));
        } catch (Exception e) {
            getLogger().warning("标签装载失败: " + e.getMessage());
        }

        // ---- 内容注册表 + CE 内容包 ----
        PeRegistry registry;
        try {
            registry = PeRegistry.load(provider);
            this.registry = registry;
            getLogger().info("内容注册表：物品 " + registry.items().size() + " / 方块 " + registry.blocks().size());
        } catch (Exception e) {
            getLogger().severe("内容注册表装载失败: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        boolean changed = false;
        try {
            changed = PeCeConverter.emit(this, provider, registry, tags);
        } catch (Exception e) {
            getLogger().severe("ProjectE 内容包生成失败: " + e.getMessage());
        }

        // 资产版本戳比对：资产变化（模型/贴图/界面分块增删改）同样需要触发 CE 重载，
        // 否则启动期生成的资源包会缺失新资产（表现为物品贴图丢失）。
        boolean assetsChanged = false;
        try {
            java.nio.file.Path stampFile = getDataFolder().toPath().resolve("asset-stamp.txt");
            String currentStamp = provider.versionStamp();
            String lastStamp = java.nio.file.Files.isRegularFile(stampFile)
                    ? java.nio.file.Files.readString(stampFile).trim() : "";
            assetsChanged = !currentStamp.equals(lastStamp);
            if (assetsChanged) {
                java.nio.file.Files.writeString(stampFile, currentStamp);
                getLogger().info("资产版本戳变更（" + lastStamp + " → " + currentStamp + "），将请求重载。");
            }
        } catch (Exception e) {
            getLogger().warning("资产版本戳比对失败: " + e.getMessage());
        }

        engine.contentProviders().register(provider);
        getLogger().info(String.format("已向 CEPlus 注册内容提供者：%s（资产 %d 个）",
                provider.id(), provider.packAssets().size()));

        if (changed || assetsChanged) {
            boolean scheduled = com.ceplus.engine.compat.ce.CeReloadScheduler.request(this, 40L);
            getLogger().info(scheduled
                    ? "PaperizE 内容包已生成/更新，已请求 CraftEngine 重载（单飞闸）。"
                    : "PaperizE 内容包更新，重载请求被单飞闸合并（已有在飞重载）。");
        } else {
            getLogger().info("PaperizE 内容包无变化（跳过重载调度）。");
        }

        // ---- 世界转换表 ----
        worldTransmutation = PeWorldTransmutation.load(provider, getLogger());
        getLogger().info(worldTransmutation.describe());

        // ---- EMC 引擎 ----
        emc = new EmcEngine(this);
        try {
            installContent();
        } catch (Exception e) {
            getLogger().warning("内容解包失败: " + e.getMessage());
        }
        getLogger().info("EMC 构建：" + rebuildEmc());

        // ---- 机器 ----
        machines = new MachineStore(getDataFolder().toPath().resolve("machines.json"), getLogger());
        runner = new MachineRunner(machines, emc, getLogger());

        // ---- 状态 ----
        state = new PeStateIO(getDataFolder().toPath().resolve("players.json"), machines, getLogger());
        bagStore = new BagStore(getDataFolder().toPath().resolve("bags.json"), getLogger());
        state.load();
        bagStore.load();

        // ---- 清理历史污染：非功能槽（装饰/进度位）一律置空 ----
        int cleaned = 0;
        for (com.paperize.machine.MachineState state : machines.all()) {
            org.bukkit.inventory.ItemStack[] slots = state.slots();
            java.util.Set<Integer> functional = new java.util.HashSet<>();
            for (int slot : state.functionalSlots()) {
                functional.add(slot);
            }
            for (int i = 0; i < slots.length; i++) {
                if (!functional.contains(i) && slots[i] != null) {
                    slots[i] = null;
                    cleaned++;
                }
            }
        }
        if (cleaned > 0) {
            getLogger().info("机器状态清理：移除 " + cleaned + " 个非功能槽残留（界面回写历史污染）");
        }

        // ---- 界面与物品逻辑（PD 风格 Menu API） ----
        menus = new MenuManager(getLogger());
        menus.attach(this);
        getServer().getPluginManager().registerEvents(menus, this);
        gui = new GuiService(this, menus, emc, state, bagStore);
        runner.setTakenOverCheck(gui::isMachineOpen);
        itemLogic = new PeItemLogic(this, emc, machines, worldTransmutation);
        getServer().getPluginManager().registerEvents(
                new PeListener(this, machines, runner, gui, itemLogic, emc), this);
        getLogger().info("界面系统就绪：" + gui.describe());

        // ---- 命令 ----
        registerCommand("paperize", "PaperizE 炼金命令", List.of("pe"),
                new PeCommand(this, emc, machines, runner, gui));

        // ---- 调度：机器节拍（每秒）+ 周期落盘 ----
        getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                runner.tick();
            } catch (Throwable ignored) {
                // 节拍容错：单次异常不中断调度
            }
        }, 100L, 20L);
        // 功能物品节拍：黑洞指环被动磁力 + 暗物质台座范围效果（每秒）
        getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    ItemStack main = player.getInventory().getItemInMainHand();
                    String id = emc.idOf(main);
                    if (id != null && id.contains("black_hole_band")) {
                        itemLogic.blackHolePull(player);
                    }
                }
                for (var entry : new java.util.HashMap<>(pedestalItems).entrySet()) {
                    String key = entry.getKey();
                    String[] parts = key.split(":");
                    if (parts.length < 2) {
                        continue;
                    }
                    String[] coords = parts[1].split("[@,]");
                    if (coords.length < 4) {
                        continue;
                    }
                    org.bukkit.World world = Bukkit.getWorld(coords[0]);
                    if (world == null) {
                        continue;
                    }
                    org.bukkit.block.Block block = world.getBlockAt(
                            Integer.parseInt(coords[1]), Integer.parseInt(coords[2]), Integer.parseInt(coords[3]));
                    String blockId = "minecraft:" + block.getType().getKey().getKey();
                    try {
                        var state = net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(block);
                        if (state != null && !state.isEmpty()) {
                            blockId = state.owner().value().id().toString();
                        }
                    } catch (Throwable ignored) {
                    }
                    if (!blockId.contains("dm_pedestal")) {
                        pedestalItems.remove(key);
                        continue;
                    }
                    itemLogic.pedestalTick(block, entry.getValue());
                }
            } catch (Throwable ignored) {
                // 节拍容错
            }
        }, 100L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            try {
                state.save();
                bagStore.save();
            } catch (Throwable ignored) {
                // 落盘容错
            }
        }, 6000L, 6000L);

        // 启动自检（等 CE 重载完成后输出验证报告）
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                PeSelfTest.run(this);
            } catch (Throwable t) {
                getLogger().warning("自检执行失败: " + t);
            }
        }, 300L);

        getLogger().info("PaperizE 就绪：EMC 已装载，机器与界面已注册（/pe）。");
    }

    @Override
    public void onDisable() {
        if (runner != null) {
            runner.validate();
        }
        if (state != null) {
            state.save();
        }
        if (bagStore != null) {
            bagStore.save();
        }
        getLogger().info("PaperizE 已卸载（状态已落盘）。");
    }

    // ---- 对外访问 ----

    public EmcEngine emc() {
        return emc;
    }

    public EmcTagResolver tags() {
        return tags;
    }

    public MachineStore machines() {
        return machines;
    }

    public MachineRunner runner() {
        return runner;
    }

    public PeStateIO state() {
        return state;
    }

    public BagStore bagStore() {
        return bagStore;
    }

    public GuiService gui() {
        return gui;
    }

    public MenuManager menus() {
        return menus;
    }

    public PeWorldTransmutation worldTransmutation() {
        return worldTransmutation;
    }

    public PeRegistry registry() {
        return registry;
    }

    /** 台座物品存储。 */
    public java.util.Map<String, ItemStack> pedestalStore() {
        return pedestalItems;
    }

    /** 台座存储封装（键标准化）。 */
    public void pedestalSet(String key, ItemStack stack) {
        pedestalItems.put(key, stack);
    }

    public ItemStack pedestalRemove(String key) {
        return pedestalItems.remove(key);
    }

    public void openTransmutation(Player player) {
        gui.openTransmutation(player);
    }

    public void openBag(Player player, String bagSimple, ItemStack hand) {
        gui.openBag(player, bagSimple, hand);
    }

    public void openMachine(Player player, com.paperize.machine.MachineState machine) {
        gui.openMachine(player, machine);
    }

    /** 重算 EMC 表（配置 + 配方 + 服务器配方 → 图求解）。 */
    public String rebuildEmc() {
        try {
            Path root = getDataFolder().toPath();
            EmcLoader loader = new EmcLoader(tags, getLogger());
            EmcLoader.LoadResult result = loader.load(
                    root.resolve("conversions"), root.resolve("recipes"), true);
            EmcGraphMapper.Result solved = EmcGraphMapper.solve(loader.collector());
            emc.install(solved.values());
            loadIndex++;
            return String.format("固定值 %d / 转换 %d（标签展开 %d）/ 已求值 %d 项（%d 轮%s，载次 %d）",
                    result.fixedValues(), result.conversions(), result.tagExpansions(),
                    solved.values().size(), solved.rounds(), solved.converged() ? "" : "未完全收敛", loadIndex);
        } catch (Exception e) {
            getLogger().warning("EMC 重算失败: " + e.getMessage());
            return "失败：" + e.getMessage();
        }
    }

    /** 把 jar 内的转换配置与配方解包到数据目录（可被管理员覆盖）。 */
    private void installContent() throws IOException {
        Path root = getDataFolder().toPath();
        int conversions = unpackIndex("conversions_index.json", "content/conversions/",
                root.resolve("conversions"));
        int recipes = unpackIndex("recipes_index.json", "content/recipes/",
                root.resolve("recipes"));
        getLogger().info("内容解包：转换 " + conversions + " 个 / 配方 " + recipes + " 个");
    }

    private int unpackIndex(String indexName, String resourcePrefix, Path targetRoot) throws IOException {
        try (InputStream in = provider.openRegistry(indexName.replace(".json", ""))) {
            if (in == null) {
                return 0;
            }
            JsonArray index = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            int written = 0;
            for (JsonElement element : index) {
                String rel = element.getAsString();
                Path target = targetRoot.resolve(rel.replace('/', File.separatorChar));
                byte[] data;
                if (resourcePrefix.contains("conversions")) {
                    try (InputStream stream = provider.openConversion(rel)) {
                        data = stream == null ? null : stream.readAllBytes();
                    }
                } else {
                    try (InputStream stream = provider.openRecipe(rel)) {
                        data = stream == null ? null : stream.readAllBytes();
                    }
                }
                if (data == null) {
                    continue;
                }
                if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), data)) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, data);
                    written++;
                }
            }
            return written;
        }
    }
}
