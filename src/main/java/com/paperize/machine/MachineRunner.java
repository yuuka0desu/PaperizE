package com.paperize.machine;

import com.paperize.emc.EmcEngine;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 机器节拍器：收集器/继电器被动产 EMC、凝聚器燃烧产出。
 *
 * <p>模型（对齐 ProjectE 方块实体语义，按每秒一拍折算）：
 * <ul>
 *   <li><b>收集器</b>：光照 ≥ 12 全额生成、8~11 半速；容量封顶。
 *       燃料槽（MK 档燃料倍率 v1 简化为直接生成）。</li>
 *   <li><b>继电器</b>：不主动生成，作为 EMC 中转（容量更大）。</li>
 *   <li><b>凝聚器</b>：每拍从输入区取 1 个可估值物品 → 燃烧为点数；
 *       点数额满目标物品价值即产出 1 个到输出槽。</li>
 * </ul>
 */
public final class MachineRunner {

    private final MachineStore store;
    private final EmcEngine emc;
    private final Logger log;
    private int validationCounter;
    /** 上次校验移除的失联机器数（日志用）。 */
    private int lastRemoved;
    /** 机器接管判定（界面打开时跳过物品处理；由界面层注入）。 */
    private java.util.function.Predicate<MachineState> takenOver = state -> false;

    public MachineRunner(MachineStore store, EmcEngine emc, Logger log) {
        this.store = store;
        this.emc = emc;
        this.log = log;
    }

    /** 注入接管判定（机器界面打开期间跳过物品处理）。 */
    public void setTakenOverCheck(java.util.function.Predicate<MachineState> check) {
        this.takenOver = check == null ? state -> false : check;
    }

    /** 每秒调用一次（代表 20 tick 的产出）。 */
    public void tick() {
        for (MachineState state : new ArrayList<>(store.all())) {
            if (state.type().collector()) {
                tickCollector(state);
            } else if (state.type().relay()) {
                tickRelay(state);
            } else if (state.type().condenser()) {
                tickCondenser(state);
            } else if (state.type().furnace()) {
                tickFurnace(state);
            }
        }
        if (++validationCounter >= 15) {
            validationCounter = 0;
            validate();
        }
    }

    /** 烧炼配方表：输入物品 id → 产物 id（懒加载，首次烧炼时构建）。 */
    private final java.util.Map<String, String> smeltingMap = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean smeltingLoaded;

    /**
     * 熔炉节拍：高效烧炼 + 金属提炼 + 存储缓冲 + 自动转移。
     *
     * <ul>
     *   <li><b>烧炼</b>：预备输入槽 → 原料槽 → 极速产出（RM 4 个/拍，DM 1 个/拍），
     *       单次消耗 EMC（DM 32 / RM 16）</li>
     *   <li><b>金属提炼</b>：红物质熔炉烧炼金属矿石有 50% 概率双倍产出</li>
     *   <li><b>存储</b>：左侧 8 格预备输入、右侧 8 格备用输出</li>
     *   <li><b>转移</b>：备用输出槽物品自动送入六邻接的箱子</li>
     *   <li><b>能量</b>：由相邻反物质继电器注入 EMC；燃料槽物品亦可转为 EMC</li>
     * </ul>
     */
    private void tickFurnace(MachineState state) {
        ItemStack[] slots = state.slots();
        if (slots.length < 54) {
            return;
        }
        ensureSmeltingMap();

        // 0) RM 专属：以极快速度吸入上方容器内的物品（无论能否烧炼）
        absorbFromAbove(state);

        // 1) 备用输出 → 邻近容器（自动转移，界面接管时也执行）
        transferToContainers(state);
        if (takenOver.test(state)) {
            return;
        }

        // 2) 输出槽 → 备用输出槽
        ItemStack output = slots[MachineState.FURNACE_OUTPUT_SLOT];
        if (output != null && !output.getType().isAir() && mergeToBackup(state, output)) {
            slots[MachineState.FURNACE_OUTPUT_SLOT] = null;
        }

        // 3) 预备输入 → 原料槽（空时补 1 个）
        int inputSlot = MachineState.FURNACE_INPUT_SLOT;
        if (slots[inputSlot] == null || slots[inputSlot].getType().isAir()) {
            for (int slot : state.furnacePreInputSlots()) {
                ItemStack pre = slots[slot];
                if (pre == null || pre.getType().isAir()) {
                    continue;
                }
                ItemStack moved = pre.clone();
                moved.setAmount(1);
                slots[inputSlot] = moved;
                pre.setAmount(pre.getAmount() - 1);
                if (pre.getAmount() <= 0) {
                    slots[slot] = null;
                }
                break;
            }
        }

        // 4) 烧炼批处理
        int batch = state.type().smeltPerTick();
        long cost = state.type().smeltCost();
        for (int i = 0; i < batch; i++) {
            ItemStack input = slots[inputSlot];
            if (input == null || input.getType().isAir()) {
                break;
            }
            String inputId = emc.idOf(input);
            String resultId = inputId == null ? null : smeltingMap.get(inputId);
            if (resultId == null) {
                break; // 不可烧炼
            }
            ItemStack produced = buildItem(resultId);
            if (produced == null || !canAbsorb(state, MachineState.FURNACE_OUTPUT_SLOT, produced)) {
                break;
            }
            if (state.emc() < cost) {
                break; // 能量不足（等待继电器注入/燃料补充）
            }
            state.extractEmc(cost);
            input.setAmount(input.getAmount() - 1);
            if (input.getAmount() <= 0) {
                slots[inputSlot] = null;
            }
            // 金属提炼：RM 100% 双倍 / DM 50% 双倍
            int count = 1;
            if (isMetalOre(inputId)) {
                int chance = state.type().oreDoubleChance();
                if (chance >= 100
                        || java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < chance) {
                    count = 2;
                }
            }
            produced.setAmount(count);
            absorb(state, MachineState.FURNACE_OUTPUT_SLOT, produced);
        }

        // 5) 燃料槽 → EMC 缓冲（低位时补充）
        if (state.emc() < state.type().smeltCost() * 8) {
            ItemStack fuel = slots[MachineState.FURNACE_FUEL_SLOT];
            if (fuel != null && !fuel.getType().isAir()) {
                long value = emc.emcOf(fuel);
                if (value > 0) {
                    fuel.setAmount(fuel.getAmount() - 1);
                    if (fuel.getAmount() <= 0) {
                        slots[MachineState.FURNACE_FUEL_SLOT] = null;
                    }
                    state.insertEmc(value);
                }
            }
        }
    }

    /** 构建烧炼配方表（服务器配方的一次性快照）。 */
    private void ensureSmeltingMap() {
        if (smeltingLoaded) {
            return;
        }
        smeltingLoaded = true;
        try {
            var iterator = Bukkit.recipeIterator();
            while (iterator.hasNext()) {
                Recipe recipe = iterator.next();
                if (!(recipe instanceof CookingRecipe<?> cooking)) {
                    continue;
                }
                ItemStack result = cooking.getResult();
                if (result == null || result.getType().isAir()) {
                    continue;
                }
                String out = "minecraft:" + result.getType().getKey().getKey();
                RecipeChoice choice = cooking.getInputChoice();
                if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
                    for (Material material : materialChoice.getChoices()) {
                        smeltingMap.putIfAbsent("minecraft:" + material.getKey().getKey(), out);
                    }
                } else if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
                    for (ItemStack stack : exactChoice.getChoices()) {
                        smeltingMap.putIfAbsent("minecraft:" + stack.getType().getKey().getKey(), out);
                    }
                }
            }
            log.info("熔炉烧炼表已构建：" + smeltingMap.size() + " 条");
        } catch (Throwable t) {
            log.warning("烧炼表构建失败: " + t);
        }
    }

    /** 金属矿石判定（含矿石/原矿语义）。 */
    private static boolean isMetalOre(String id) {
        if (id == null) {
            return false;
        }
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return path.contains("ore") || path.startsWith("raw_");
    }

    /**
     * 红物质熔炉专属：以极快速度（12 组/拍上限）吸入其上方容器内的物品，
     * 无论这些物品能否被烧炼——先把待处理物囤进左侧预备输入槽。
     */
    private void absorbFromAbove(MachineState state) {
        if (state.type() != MachineState.Type.RM_FURNACE) {
            return;
        }
        World world = Bukkit.getWorld(state.world());
        if (world == null) {
            return;
        }
        Block above = world.getBlockAt(state.x(), state.y() + 1, state.z());
        if (!(above.getState() instanceof org.bukkit.block.Container container)) {
            return;
        }
        org.bukkit.inventory.Inventory source = container.getInventory();
        ItemStack[] slots = state.slots();
        int[] preSlots = state.furnacePreInputSlots();
        int movedCap = 12 * 64; // 12 组/拍
        int moved = 0;
        for (int i = 0; i < source.getSize() && moved < movedCap; i++) {
            ItemStack stack = source.getItem(i);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            boolean placed = false;
            // 先尝试堆叠到同类预备槽
            for (int slot : preSlots) {
                if (slot >= slots.length) {
                    continue;
                }
                ItemStack existing = slots[slot];
                if (existing != null && !existing.getType().isAir() && existing.isSimilar(stack)
                        && existing.getAmount() < existing.getMaxStackSize()) {
                    int space = existing.getMaxStackSize() - existing.getAmount();
                    int move = Math.min(space, stack.getAmount());
                    existing.setAmount(existing.getAmount() + move);
                    stack.setAmount(stack.getAmount() - move);
                    moved += move;
                    if (stack.getAmount() <= 0) {
                        source.setItem(i, null);
                        placed = true;
                    }
                    if (moved >= movedCap) {
                        break;
                    }
                }
            }
            if (placed || (stack.getAmount() > 0 && moved >= movedCap)) {
                continue;
            }
            // 再找空位整组移入
            for (int slot : preSlots) {
                if (slot >= slots.length) {
                    continue;
                }
                ItemStack existing = slots[slot];
                if (existing == null || existing.getType().isAir()) {
                    slots[slot] = stack.clone();
                    moved += stack.getAmount();
                    source.setItem(i, null);
                    break;
                }
            }
        }
        if (moved > 0) {
            state.setWork(state.work()); // 触达状态变更标记（保持落盘）
        }
    }

    /** 输出槽 → 备用输出槽（先堆叠后空位）。 */
    private boolean mergeToBackup(MachineState state, ItemStack output) {
        ItemStack[] slots = state.slots();
        for (int slot : state.furnaceBackupOutputSlots()) {
            ItemStack existing = slots[slot];
            if (existing != null && !existing.getType().isAir() && existing.isSimilar(output)
                    && existing.getAmount() < existing.getMaxStackSize()) {
                int space = existing.getMaxStackSize() - existing.getAmount();
                int move = Math.min(space, output.getAmount());
                existing.setAmount(existing.getAmount() + move);
                output.setAmount(output.getAmount() - move);
                if (output.getAmount() <= 0) {
                    return true;
                }
            }
        }
        for (int slot : state.furnaceBackupOutputSlots()) {
            ItemStack existing = slots[slot];
            if (existing == null || existing.getType().isAir()) {
                slots[slot] = output;
                return true;
            }
        }
        return output.getAmount() <= 0;
    }

    /** 备用输出槽 → 六邻接容器（自动转移）。 */
    private void transferToContainers(MachineState state) {
        ItemStack[] slots = state.slots();
        boolean hasItems = false;
        for (int slot : state.furnaceBackupOutputSlots()) {
            ItemStack stack = slot < slots.length ? slots[slot] : null;
            if (stack != null && !stack.getType().isAir()) {
                hasItems = true;
                break;
            }
        }
        if (!hasItems) {
            return;
        }
        World world = Bukkit.getWorld(state.world());
        if (world == null) {
            return;
        }
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] offset : offsets) {
            Block block = world.getBlockAt(state.x() + offset[0], state.y() + offset[1], state.z() + offset[2]);
            if (!(block.getState() instanceof org.bukkit.block.Container container)) {
                continue;
            }
            org.bukkit.inventory.Inventory target = container.getInventory();
            for (int slot : state.furnaceBackupOutputSlots()) {
                if (slot >= slots.length) {
                    continue;
                }
                ItemStack stack = slots[slot];
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                var leftover = target.addItem(stack.clone());
                if (leftover.isEmpty()) {
                    slots[slot] = null;
                } else {
                    stack.setAmount(leftover.values().iterator().next().getAmount());
                }
            }
        }
    }

    /**
     * 继电器节拍：提取 / 产生 / 转移 三大机能。
     *
     * <ul>
     *   <li><b>提取</b>：燃料区物品按顺序进入输入槽，逐拍抽出其中的 EMC 存入继电器
     *       （输入槽剩余量见界面倒数第二行进度条）</li>
     *   <li><b>产生</b>：每个相邻收集器面提供额外 EMC（等级 × 面数 / 秒），不消耗物品</li>
     *   <li><b>转移</b>：向"正在加工"的相邻收集器注入 EMC（收集器阵列管道），
     *       并给星槽中的克莱因之星充能；<b>不向其他继电器转移</b>（避免环流）</li>
     * </ul>
     */
    private void tickRelay(MachineState state) {
        ItemStack[] slots = state.slots();
        if (slots.length < 54) {
            return;
        }
        boolean taken = takenOver.test(state);

        // ---- 提取：燃料区 → 输入槽 → 逐拍抽出 EMC ----
        int inputSlot = MachineState.RELAY_INPUT_SLOT;
        ItemStack input = slots[inputSlot];
        boolean inputEmpty = input == null || input.getType().isAir();
        if (!taken && inputEmpty) {
            for (int slot : state.relayFuelSlots()) {
                ItemStack fuel = slots[slot];
                if (fuel == null || fuel.getType().isAir()) {
                    continue;
                }
                long value = emc.emcOf(fuel);
                if (value <= 0) {
                    continue;
                }
                ItemStack moved = fuel.clone();
                moved.setAmount(1);
                slots[inputSlot] = moved;
                fuel.setAmount(fuel.getAmount() - 1);
                if (fuel.getAmount() <= 0) {
                    slots[slot] = null;
                }
                state.setWork(value); // 输入槽待提取总量
                input = moved;
                inputEmpty = false;
                break;
            }
        }
        if (!taken && !inputEmpty && state.work() > 0) {
            long capacity = state.type().capacity() > 0 ? state.type().capacity() : Long.MAX_VALUE;
            long room = capacity - state.emc();
            long rate = Math.max(1, state.type().relayStarChargeRate());
            long take = Math.min(Math.min(rate, state.work()), Math.max(0, room));
            if (take > 0) {
                state.insertEmc(take);
                state.setWork(state.work() - take);
            }
            if (state.work() <= 0) {
                input.setAmount(input.getAmount() - 1);
                if (input.getAmount() <= 0) {
                    slots[inputSlot] = null;
                }
            }
        }

        // ---- 产生：相邻收集器面数 × 等级奖励 ----
        int collectorFaces = 0;
        for (MachineState neighbor : adjacent(state)) {
            if (neighbor.type().collector()) {
                collectorFaces++;
            }
        }
        if (collectorFaces > 0) {
            state.insertEmc((long) collectorFaces * state.type().relayFaceBonus());
        }

        // ---- 星槽充能（固定速率） ----
        for (int slot : state.relayStarSlots()) {
            ItemStack star = slots[slot];
            if (star == null || star.getType().isAir()) {
                continue;
            }
            String id = emc.idOf(star);
            if (id == null || !id.contains("klein_star")) {
                continue;
            }
            long capacity = kleinCapacity(id);
            long stored = emc.storedEmc(star);
            if (stored >= capacity) {
                continue;
            }
            long charge = Math.min(Math.min(capacity - stored, state.type().relayStarChargeRate()),
                    state.emc());
            long takenEmc = state.extractEmc(charge);
            if (takenEmc > 0) {
                emc.setStoredEmc(star, stored + takenEmc);
            }
        }

        // ---- 转移：向"正在加工"的相邻收集器注入（管道机能） ----
        if (state.emc() > 0) {
            long budget = Math.max(1, state.type().relayStarChargeRate());
            for (MachineState neighbor : adjacent(state)) {
                if (budget <= 0 || state.emc() <= 0) {
                    break;
                }
                boolean acceptable = neighbor.type().collector()
                        ? isProcessing(neighbor)
                        : neighbor.type().furnace();
                if (!acceptable) {
                    continue;
                }
                long cap = neighbor.type().capacity();
                long room = cap - neighbor.emc();
                if (room <= 0) {
                    continue;
                }
                long move = Math.min(Math.min(state.emc(), room), budget);
                long accepted = neighbor.insertEmc(move);
                if (accepted > 0) {
                    state.extractEmc(accepted);
                    budget -= accepted;
                }
            }
        }
    }

    /** 收集器是否在加工（输入槽或燃料区有物品）。 */
    private boolean isProcessing(MachineState collector) {
        ItemStack[] slots = collector.slots();
        if (slots.length < 54) {
            return false;
        }
        ItemStack input = slots[MachineState.COLLECTOR_INPUT_SLOT];
        if (input != null && !input.getType().isAir()) {
            return true;
        }
        for (int slot : collector.collectorFuelSlots()) {
            if (slot < slots.length) {
                ItemStack fuel = slots[slot];
                if (fuel != null && !fuel.getType().isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 六邻接机器列表。 */
    private java.util.List<MachineState> adjacent(MachineState state) {
        java.util.List<MachineState> out = new java.util.ArrayList<>();
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] offset : offsets) {
            MachineState neighbor = store.get(state.world(),
                    state.x() + offset[0], state.y() + offset[1], state.z() + offset[2]);
            if (neighbor != null) {
                out.add(neighbor);
            }
        }
        return out;
    }

    /**
     * 收集器节拍：EMC 生成 + 升级流水线。
     *
     * <p>流水线（每拍推进）：
     * <ol>
     *   <li>光照生成 EMC（≥12 全额、8~11 半速）</li>
     *   <li>燃料槽 → 输入槽（输入槽空时移入 1 个）</li>
     *   <li>输入槽物品 + 足够 EMC → 升级为下一级 → 输出槽</li>
     *   <li>输出槽 → 燃料槽（回传，继续逐级升级）</li>
     *   <li>能量之星槽：把 EMC 充入克莱因之星</li>
     * </ol>
     */
    private void tickCollector(MachineState state) {
        World world = Bukkit.getWorld(state.world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(state.x(), state.y(), state.z());
        int light = block.getRelative(0, 1, 0).getLightLevel();
        long amount = state.type().generation();
        if (light < 8) {
            amount = 0;
        } else if (light < 12) {
            amount /= 2;
        }
        if (amount > 0) {
            distributeGeneration(state, amount);
        }
        if (takenOver.test(state)) {
            return; // 界面接管：仅推进 EMC 生成
        }
        ItemStack[] slots = state.slots();
        if (slots.length < 54) {
            return;
        }

        // 1) 燃料槽 → 输入槽（输入槽空时）
        int inputSlot = MachineState.COLLECTOR_INPUT_SLOT;
        if (slots[inputSlot] == null || slots[inputSlot].getType().isAir()) {
            for (int slot : state.collectorFuelSlots()) {
                ItemStack fuel = slots[slot];
                if (fuel == null || fuel.getType().isAir()) {
                    continue;
                }
                ItemStack moved = fuel.clone();
                moved.setAmount(1);
                slots[inputSlot] = moved;
                fuel.setAmount(fuel.getAmount() - 1);
                if (fuel.getAmount() <= 0) {
                    slots[slot] = null;
                }
                break;
            }
        }

        // 2) 升级：输入槽物品 + 足够 EMC → 下一级 → 输出槽
        ItemStack input = slots[inputSlot];
        if (input != null && !input.getType().isAir()) {
            String id = emc.idOf(input);
            CollectorChain.Upgrade upgrade = CollectorChain.next(id);
            if (upgrade != null && availableEmc(state) >= upgrade.cost()) {
                ItemStack produced = buildItem(upgrade.targetId());
                if (produced != null && canAbsorb(state, MachineState.COLLECTOR_OUTPUT_SLOT, produced)) {
                    consumeEmc(state, upgrade.cost());
                    input.setAmount(input.getAmount() - 1);
                    if (input.getAmount() <= 0) {
                        slots[inputSlot] = null;
                    }
                    absorb(state, MachineState.COLLECTOR_OUTPUT_SLOT, produced);
                }
            }
        }

        // 3) 输出槽 → 燃料槽（回传，继续升级）
        int outputSlot = MachineState.COLLECTOR_OUTPUT_SLOT;
        ItemStack output = slots[outputSlot];
        if (output != null && !output.getType().isAir() && mergeToFuel(state, output)) {
            slots[outputSlot] = null;
        }

        // 4) 能量之星槽充能
        for (int slot : state.collectorStarSlots()) {
            ItemStack star = slots[slot];
            if (star == null || star.getType().isAir()) {
                continue;
            }
            String id = emc.idOf(star);
            if (id == null || !id.contains("klein_star")) {
                continue;
            }
            long capacity = kleinCapacity(id);
            long stored = emc.storedEmc(star);
            if (stored >= capacity) {
                continue;
            }
            long charge = Math.min(capacity - stored, Math.max(1, amount * 4));
            long taken = consumeEmc(state, charge);
            if (taken > 0) {
                emc.setStoredEmc(star, stored + taken);
            }
        }
    }

    /**
     * EMC 产出分配：相邻反物质继电器优先吸收，余量存回自身。
     *
     * <p>ProjectE 联动：收集器旁的继电器作为共享储能池，
     * 收集器升级与充星时可从池中支取（见 {@link #consumeEmc}）。
     */
    private void distributeGeneration(MachineState state, long amount) {
        long remaining = amount;
        for (MachineState relay : adjacentRelays(state)) {
            if (remaining <= 0) {
                break;
            }
            remaining -= relay.insertEmc(remaining);
        }
        if (remaining > 0) {
            state.insertEmc(remaining);
        }
    }

    /** 相邻反物质继电器（六邻接）。 */
    private java.util.List<MachineState> adjacentRelays(MachineState state) {
        java.util.List<MachineState> out = new java.util.ArrayList<>();
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] offset : offsets) {
            MachineState neighbor = store.get(state.world(),
                    state.x() + offset[0], state.y() + offset[1], state.z() + offset[2]);
            if (neighbor != null && neighbor.type().relay()) {
                out.add(neighbor);
            }
        }
        return out;
    }

    /** 可用 EMC：自身 + 相邻继电器。 */
    private long availableEmc(MachineState state) {
        long total = state.emc();
        for (MachineState relay : adjacentRelays(state)) {
            total += relay.emc();
        }
        return total;
    }

    /** 支取 EMC：优先自身，不足部分从相邻继电器扣；返回实际支取量。 */
    private long consumeEmc(MachineState state, long amount) {
        long taken = state.extractEmc(amount);
        long remaining = amount - taken;
        for (MachineState relay : adjacentRelays(state)) {
            if (remaining <= 0) {
                break;
            }
            long got = relay.extractEmc(remaining);
            taken += got;
            remaining -= got;
        }
        return taken;
    }

    /** 构造内容物品（CE 自定义物品优先，回退原版材质）。 */
    private ItemStack buildItem(String id) {
        try {
            var definition = CraftEngineItems.byId(Key.of(id));
            if (definition != null) {
                ItemStack stack = definition.buildBukkitItem();
                if (stack != null && !stack.getType().isAir()) {
                    return stack;
                }
            }
        } catch (Throwable ignored) {
            // 回退原版材质
        }
        Material material = Material.matchMaterial(id);
        return material == null ? null : new ItemStack(material);
    }

    /** 槽位能否吸收该物品（空位或同类可堆叠）。 */
    private boolean canAbsorb(MachineState state, int slot, ItemStack produced) {
        ItemStack existing = state.slots()[slot];
        if (existing == null || existing.getType().isAir()) {
            return true;
        }
        return existing.isSimilar(produced) && existing.getAmount() < existing.getMaxStackSize();
    }

    /** 把物品并入槽位；返回是否完全吸收。 */
    private boolean absorb(MachineState state, int slot, ItemStack produced) {
        ItemStack[] slots = state.slots();
        ItemStack existing = slots[slot];
        if (existing == null || existing.getType().isAir()) {
            slots[slot] = produced;
            return true;
        }
        if (existing.isSimilar(produced) && existing.getAmount() < existing.getMaxStackSize()) {
            int space = existing.getMaxStackSize() - existing.getAmount();
            int move = Math.min(space, produced.getAmount());
            existing.setAmount(existing.getAmount() + move);
            produced.setAmount(produced.getAmount() - move);
        }
        return produced.getAmount() <= 0;
    }

    /** 把输出槽物品回传到燃料槽（先堆叠后空位）。 */
    private boolean mergeToFuel(MachineState state, ItemStack output) {
        ItemStack[] slots = state.slots();
        for (int slot : state.collectorFuelSlots()) {
            ItemStack existing = slots[slot];
            if (existing != null && !existing.getType().isAir() && existing.isSimilar(output)
                    && existing.getAmount() < existing.getMaxStackSize()) {
                int space = existing.getMaxStackSize() - existing.getAmount();
                int move = Math.min(space, output.getAmount());
                existing.setAmount(existing.getAmount() + move);
                output.setAmount(output.getAmount() - move);
                if (output.getAmount() <= 0) {
                    return true;
                }
            }
        }
        for (int slot : state.collectorFuelSlots()) {
            ItemStack existing = slots[slot];
            if (existing == null || existing.getType().isAir()) {
                slots[slot] = output;
                return true;
            }
        }
        return output.getAmount() <= 0;
    }

    /** 克莱因之星容量（按级别）。 */
    private static long kleinCapacity(String id) {
        if (id.endsWith("klein_star_ein")) return 50_000L;
        if (id.endsWith("klein_star_zwei")) return 200_000L;
        if (id.endsWith("klein_star_drei")) return 800_000L;
        if (id.endsWith("klein_star_vier")) return 3_200_000L;
        if (id.endsWith("klein_star_sphere")) return 12_800_000L;
        if (id.endsWith("klein_star_omega")) return 51_200_000L;
        return 50_000L;
    }

    /**
     * 凝聚器节拍。
     *
     * <p>布局（按机型分区，见 {@link MachineState#fuelSlots()}）：
     * <ul>
     *   <li>目标槽 = 第一行第一位（slot 0）</li>
     *   <li>MK1：燃料区 = 行 1-4 整宽，**产物也输出到该区**；
     *       燃烧时忽略"与目标产物相同的物品"，避免产物被当成燃料烧掉（防循环）</li>
     *   <li>MK2：左 4 列为燃料区、第五列为分隔、右 4 列为成品区</li>
     * </ul>
     */
    private void tickCondenser(MachineState state) {
        if (takenOver.test(state)) {
            return; // 界面接管：暂停燃烧与产出
        }
        ItemStack[] slots = state.slots();
        ItemStack targetStack = slots.length > MachineState.CONDENSER_TARGET_SLOT
                ? slots[MachineState.CONDENSER_TARGET_SLOT] : null;
        long target = 0;
        if (targetStack != null && !targetStack.getType().isAir()) {
            target = emc.emcOf(targetStack);
            if (target > 0) {
                state.setTargetId(emc.idOf(targetStack));
            }
        }
        if (target <= 0) {
            String recorded = state.targetId();
            target = recorded == null ? 0 : emc.emcOf(recorded);
        }
        if (target <= 0) {
            return; // 未设定目标
        }
        String targetId = state.targetId();

        // 燃烧燃料：每拍 1 个；跳过产物本身（防循环燃烧）
        for (int slot : state.fuelSlots()) {
            if (slot >= slots.length) {
                continue;
            }
            ItemStack stack = slots[slot];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            String id = emc.idOf(stack);
            if (targetId != null && targetId.equals(id)) {
                continue; // 产物：忽略燃烧
            }
            long value = emc.emcOf(stack);
            if (value <= 0) {
                continue;
            }
            stack.setAmount(stack.getAmount() - 1);
            if (stack.getAmount() <= 0) {
                slots[slot] = null;
            }
            state.addWork(value);
            break;
        }

        // 产出：点数达到目标价值 → 目标物品复制到产物区（保留组件/贴皮）
        if (targetStack == null || targetStack.getType().isAir()) {
            return;
        }
        while (state.work() >= target) {
            ItemStack produced = targetStack.clone();
            produced.setAmount(1);
            if (!deliver(state, produced)) {
                break; // 产物区已满
            }
            state.setWork(state.work() - target);
        }
    }

    /** 把产物放入产物区（首个空位或可堆叠位）；返回是否成功。 */
    private boolean deliver(MachineState state, ItemStack produced) {
        ItemStack[] slots = state.slots();
        for (int slot : state.outputSlots()) {
            if (slot >= slots.length) {
                continue;
            }
            ItemStack existing = slots[slot];
            if (existing == null || existing.getType().isAir()) {
                slots[slot] = produced;
                return true;
            }
            if (existing.isSimilar(produced) && existing.getAmount() < existing.getMaxStackSize()) {
                existing.setAmount(existing.getAmount() + 1);
                return true;
            }
        }
        return false;
    }

    /** 方块存活校验：CE 方块已消失的机器状态回收。 */
    public int validate() {
        int removed = 0;
        for (MachineState state : new ArrayList<>(store.all())) {
            World world = Bukkit.getWorld(state.world());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(state.x(), state.y(), state.z());
            boolean alive;
            try {
                ImmutableBlockState custom = CraftEngineBlocks.getCustomBlockState(block);
                alive = custom != null && !custom.isEmpty()
                        && custom.owner().value().id().value().equals(customId(state.type()));
            } catch (Throwable t) {
                alive = true; // 引擎未就绪时不误删
            }
            if (!alive) {
                store.remove(state.world(), state.x(), state.y(), state.z());
                removed++;
            }
        }
        lastRemoved = removed;
        return removed;
    }

    private static String customId(MachineState.Type type) {
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

    /** 机器摘要（命令用）。 */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (MachineState state : store.all()) {
            out.add(state.key() + " " + state.type().name() + " emc=" + state.emc()
                    + (state.type().condenser() ? " work=" + state.work() + " target=" + state.targetId() : ""));
        }
        if (lastRemoved > 0) {
            out.add("（最近校验回收失联机器 " + lastRemoved + " 台）");
        }
        return out;
    }
}
