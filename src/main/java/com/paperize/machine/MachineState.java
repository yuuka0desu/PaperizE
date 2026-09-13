package com.paperize.machine;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 机器状态：收集器 / 继电器 / 凝聚器 / 炼金箱的持久化数据。
 *
 * <p>槽位语义（对齐 ProjectE 方块实体）：
 * <ul>
 *   <li>收集器（collector_mk*）：{@code emc} 被动生成 + 燃料燃烧；{@code slots} 为产出/燃料槽</li>
 *   <li>继电器（relay_mk*）：接受 EMC 注入与抽取（网络特性 v1 简化为本地累积）</li>
 *   <li>凝聚器（condenser_mk*）：{@code work} 为当前累积点数；{@code target} 为幽灵槽目标</li>
 *   <li>炼金箱（alchemical_chest）：纯存储（104 格）</li>
 * </ul>
 */
public final class MachineState {

    public enum Type {
        COLLECTOR_MK1(10_000, 4),
        COLLECTOR_MK2(30_000, 12),
        COLLECTOR_MK3(60_000, 40),
        RELAY_MK1(100_000, 0),
        RELAY_MK2(1_000_000, 0),
        RELAY_MK3(10_000_000, 0),
        CONDENSER_MK1(0, 0),
        CONDENSER_MK2(0, 0),
        ALCHEMY_CHEST(0, 0),
        DM_FURNACE(100_000, 0),
        RM_FURNACE(1_000_000, 0);

        private final long capacity;
        private final int generation;

        Type(long capacity, int generation) {
            this.capacity = capacity;
            this.generation = generation;
        }

        public long capacity() {
            return capacity;
        }

        /** MK 档位基础生成速率（点/秒；实际受光照与档位影响）。 */
        public int generation() {
            return generation;
        }

        public boolean collector() {
            return name().startsWith("COLLECTOR");
        }

        public boolean relay() {
            return name().startsWith("RELAY");
        }

        public boolean condenser() {
            return name().startsWith("CONDENSER");
        }

        public boolean furnace() {
            return name().endsWith("_FURNACE");
        }

        /** 熔炉每拍烧炼数量（RM：单物品 <0.2s ≈ 5 个/秒）。 */
        public int smeltPerTick() {
            return this == RM_FURNACE ? 5 : 1;
        }

        /** 单次烧炼的 EMC 成本（RM 单位燃值效率约 2.5 倍）。 */
        public long smeltCost() {
            return this == RM_FURNACE ? 13 : 32;
        }

        /** 金属提炼双倍率：RM 100%、DM 50%。 */
        public int oreDoubleChance() {
            return this == RM_FURNACE ? 100 : 50;
        }

        /** 继电器：每个相邻收集器面的额外 EMC 产出/秒。 */
        public int relayFaceBonus() {
            return switch (this) {
                case RELAY_MK1 -> 1;
                case RELAY_MK2 -> 3;
                case RELAY_MK3 -> 10;
                default -> 0;
            };
        }

        /** 继电器：克莱因之星充能速率（EMC/s）。 */
        public int relayStarChargeRate() {
            return switch (this) {
                case RELAY_MK1 -> 320;
                case RELAY_MK2 -> 1280;
                case RELAY_MK3 -> 5120;
                default -> 0;
            };
        }

        public int slots() {
            return switch (this) {
                case COLLECTOR_MK1, COLLECTOR_MK2, COLLECTOR_MK3 -> 54; // 见 collector* 布局方法
                case RELAY_MK1, RELAY_MK2, RELAY_MK3 -> 54;            // 见 relay* 布局方法
                case CONDENSER_MK1, CONDENSER_MK2 -> 54;                // 见 layout 注释（MK1/MK2 布局不同）
                case ALCHEMY_CHEST, DM_FURNACE, RM_FURNACE -> 54;
            };
        }

        /** 输入区大小（凝聚器 45 格；其余 0）。 */
        public int inputSize() {
            return this.condenser() ? 45 : 0;
        }

        public static Type of(String simple) {
            return switch (simple) {
                case "collector_mk1" -> COLLECTOR_MK1;
                case "collector_mk2" -> COLLECTOR_MK2;
                case "collector_mk3" -> COLLECTOR_MK3;
                case "relay_mk1" -> RELAY_MK1;
                case "relay_mk2" -> RELAY_MK2;
                case "relay_mk3" -> RELAY_MK3;
                case "condenser_mk1" -> CONDENSER_MK1;
                case "condenser_mk2" -> CONDENSER_MK2;
                case "alchemical_chest" -> ALCHEMY_CHEST;
                case "dm_furnace" -> DM_FURNACE;
                case "rm_furnace" -> RM_FURNACE;
                default -> null;
            };
        }
    }

    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final Type type;
    private long emc;
    private ItemStack[] slots;
    /** 凝聚器进度（累积点数，达到目标值即产出）。 */
    private long work;
    /** 凝聚器目标物品 id（幽灵槽，物品本身存 slots 尾部）。 */
    private String targetId;

    public MachineState(String world, int x, int y, int z, Type type) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.slots = new ItemStack[type.slots()];
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public Type type() {
        return type;
    }

    public String key() {
        return world + "@" + x + "," + y + "," + z;
    }

    public long emc() {
        return emc;
    }

    public void setEmc(long value) {
        this.emc = Math.max(0, Math.min(type.capacity() > 0 ? type.capacity() : Long.MAX_VALUE, value));
    }

    /** 注入 EMC；返回实际接受量。 */
    public long insertEmc(long amount) {
        if (amount <= 0) {
            return 0;
        }
        long cap = type.capacity() > 0 ? type.capacity() : Long.MAX_VALUE;
        long accepted = Math.min(amount, cap - emc);
        if (accepted <= 0) {
            return 0;
        }
        emc += accepted;
        return accepted;
    }

    /** 抽取 EMC；返回实际抽出量。 */
    public long extractEmc(long amount) {
        long taken = Math.min(amount, emc);
        emc -= taken;
        return taken;
    }

    public boolean full() {
        return type.capacity() > 0 && emc >= type.capacity();
    }

    public ItemStack[] slots() {
        return slots;
    }

    public void setSlots(ItemStack[] slots) {
        this.slots = slots == null ? new ItemStack[type.slots()] : slots;
    }

    public long work() {
        return work;
    }

    public void setWork(long value) {
        this.work = Math.max(0, value);
    }

    public long addWork(long value) {
        if (value <= 0) {
            return 0;
        }
        work += value;
        return value;
    }

    public String targetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    /** 凝聚器目标槽位（第一行第一位）。 */
    public static final int CONDENSER_TARGET_SLOT = 0;

    /**
     * 燃料区槽位（凝聚器）。
     *
     * <ul>
     *   <li>MK1：第二行到倒数第二行整宽（行 1-4，slot 9-44）——产物也输出在此区</li>
     *   <li>MK2：左侧 4 列（行 1-5，列 0-3）</li>
     * </ul>
     */
    public int[] fuelSlots() {
        if (type == Type.CONDENSER_MK1) {
            return grid(1, 4, 0, 8);
        }
        if (type == Type.CONDENSER_MK2) {
            return grid(1, 5, 0, 3);
        }
        return new int[0];
    }

    /**
     * 产物输出槽位。
     *
     * <ul>
     *   <li>MK1：与燃料区同区（产物混入燃料区，燃烧逻辑忽略产物以避免循环）</li>
     *   <li>MK2：右侧 4 列（行 1-5，列 5-8）</li>
     * </ul>
     */
    public int[] outputSlots() {
        if (type == Type.CONDENSER_MK1) {
            return fuelSlots();
        }
        if (type == Type.CONDENSER_MK2) {
            return grid(1, 5, 5, 8);
        }
        return new int[0];
    }

    // ---- 能量收集器布局（54 格）----
    // 行 0 / 行 5 / 列 0 / 列 8 为占位；
    // 燃料槽自列 1 起向右扩展（MK1 2 列 / MK2 3 列 / MK3 4 列）；
    // 列 6：行 1 = 输出槽、行 4 = 输入槽；列 7 = 能量之星槽（4 格）；
    // 进度条位于燃料槽右侧至列 5（行 1 与行 4）。

    /** 输入槽（列 6，行 4）。 */
    public static final int COLLECTOR_INPUT_SLOT = 4 * 9 + 6;

    /** 输出槽（列 6，行 1）。 */
    public static final int COLLECTOR_OUTPUT_SLOT = 1 * 9 + 6;

    /** 收集器燃料槽列数：MK1=2、MK2=3、MK3=4。 */
    public int collectorFuelColumns() {
        return switch (type) {
            case COLLECTOR_MK1 -> 2;
            case COLLECTOR_MK2 -> 3;
            case COLLECTOR_MK3 -> 4;
            default -> 0;
        };
    }

    /** 收集器燃料槽（列 1..N，行 1-4）。 */
    public int[] collectorFuelSlots() {
        int cols = collectorFuelColumns();
        return cols == 0 ? new int[0] : grid(1, 4, 1, cols);
    }

    /** 能量之星槽（列 7，行 1-4）。 */
    public int[] collectorStarSlots() {
        return type.collector() ? grid(1, 4, 7, 7) : new int[0];
    }

    /** 收集器进度条槽（燃料槽右侧至列 5，行 1 与行 4）。 */
    public int[] collectorProgressSlots() {
        if (!type.collector()) {
            return new int[0];
        }
        int from = collectorFuelColumns() + 1;
        if (from > 5) {
            return new int[0];
        }
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int col = from; col <= 5; col++) {
            out.add(1 * 9 + col);
            out.add(4 * 9 + col);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---- 反物质继电器布局（54 格）----
    // 行 0 / 行 5 占位；燃料槽（=提取区）自列 1 起向右扩展（MK1 2 列 / MK2 3 列 / MK3 4 列）；
    // 输入槽 = 列 5 行 3；行 1 列 6-8 = 存储进度条；行 4 列 6-8 = 输入剩余进度条；
    // 列 7 行 2-3 = 能量之星槽；其余占位。

    /** 继电器输入槽（列 5，行 3）。 */
    public static final int RELAY_INPUT_SLOT = 3 * 9 + 5;

    /** 继电器燃料（提取）槽列数：MK1=2、MK2=3、MK3=4。 */
    public int relayFuelColumns() {
        return switch (type) {
            case RELAY_MK1 -> 2;
            case RELAY_MK2 -> 3;
            case RELAY_MK3 -> 4;
            default -> 0;
        };
    }

    /** 继电器燃料（提取）槽（列 1..N，行 1-4）。 */
    public int[] relayFuelSlots() {
        int cols = relayFuelColumns();
        return cols == 0 ? new int[0] : grid(1, 4, 1, cols);
    }

    /** 继电器存储进度条（行 1，列 6-8）。 */
    public int[] relayStorageProgressSlots() {
        return type.relay() ? grid(1, 1, 6, 8) : new int[0];
    }

    /** 继电器输入剩余进度条（行 4，列 6-8）。 */
    public int[] relayInputProgressSlots() {
        return type.relay() ? grid(4, 4, 6, 8) : new int[0];
    }

    /** 继电器星槽（列 7，行 2-3）。 */
    public int[] relayStarSlots() {
        return type.relay() ? new int[]{2 * 9 + 7, 3 * 9 + 7} : new int[0];
    }

    // ---- 暗物质 / 红物质熔炉布局（54 格）----
    // 行 0 / 行 5 / 列 0 / 列 8 占位；
    // 列 1-2（行 1-4）= 预备输入槽（8 格，按顺序补入原料槽）；
    // 列 6-7（行 1-4）= 备用输出槽（8 格，成品自动转移至此）；
    // 列 3 行 1 = 输入（原料）槽；列 3 行 3 = 燃料槽（可接相邻继电器能量）；
    // 列 5 行 1 = 输出槽。

    /** 熔炉输入（原料）槽：列 3，行 1。 */
    public static final int FURNACE_INPUT_SLOT = 1 * 9 + 3;

    /** 熔炉燃料槽：列 3，行 3。 */
    public static final int FURNACE_FUEL_SLOT = 3 * 9 + 3;

    /** 熔炉输出槽：列 5，行 1。 */
    public static final int FURNACE_OUTPUT_SLOT = 1 * 9 + 5;

    /** 预备输入槽：RM = 列 0-2（12 格），DM = 列 1-2（8 格），行 1-4。 */
    public int[] furnacePreInputSlots() {
        if (!type.furnace()) {
            return new int[0];
        }
        return type == Type.RM_FURNACE ? grid(1, 4, 0, 2) : grid(1, 4, 1, 2);
    }

    /** 备用输出槽：RM = 列 6-8（12 格），DM = 列 6-7（8 格），行 1-4。 */
    public int[] furnaceBackupOutputSlots() {
        if (!type.furnace()) {
            return new int[0];
        }
        return type == Type.RM_FURNACE ? grid(1, 4, 6, 8) : grid(1, 4, 6, 7);
    }

    /** 分隔列槽位（MK2 第五列，行 1-5）。 */
    public int[] separatorSlots() {
        if (type == Type.CONDENSER_MK2) {
            return grid(1, 5, 4, 4);
        }
        return new int[0];
    }

    /** 第一行进度/占位槽（slot 1-8）。 */
    public static int[] headerSlots() {
        return grid(1, 1, 1, 8);
    }

    /** 生成行区间 × 列区间的槽位数组（行、列均含端点；每行 9 列）。 */
    private static int[] grid(int rowFrom, int rowTo, int colFrom, int colTo) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int row = rowFrom; row <= rowTo; row++) {
            for (int col = colFrom; col <= colTo; col++) {
                out.add(row * 9 + col);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 功能槽位集合（用于界面回写与破坏掉落过滤）。
     *
     * <p>装饰/进度/信息位不在其中：界面关闭回写时若整表写回，
     * 装饰玻璃板会混入机器状态，方块被破坏时随之掉落。
     */
    public int[] functionalSlots() {
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        if (type.collector()) {
            out.add(COLLECTOR_INPUT_SLOT);
            out.add(COLLECTOR_OUTPUT_SLOT);
            for (int slot : collectorFuelSlots()) {
                out.add(slot);
            }
            for (int slot : collectorStarSlots()) {
                out.add(slot);
            }
        } else if (type.relay()) {
            out.add(RELAY_INPUT_SLOT);
            for (int slot : relayFuelSlots()) {
                out.add(slot);
            }
            for (int slot : relayStarSlots()) {
                out.add(slot);
            }
        } else if (type.condenser()) {
            out.add(CONDENSER_TARGET_SLOT);
            for (int slot : fuelSlots()) {
                out.add(slot);
            }
            for (int slot : outputSlots()) {
                out.add(slot);
            }
        } else if (type.furnace()) {
            out.add(FURNACE_INPUT_SLOT);
            out.add(FURNACE_FUEL_SLOT);
            out.add(FURNACE_OUTPUT_SLOT);
            for (int slot : furnacePreInputSlots()) {
                out.add(slot);
            }
            for (int slot : furnaceBackupOutputSlots()) {
                out.add(slot);
            }
        } else {
            // 炼金箱等：纯存储，全部槽位均可承载物品
            for (int i = 0; i < slots().length; i++) {
                out.add(i);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 摘要（命令/调试用）。 */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type.name());
        out.put("pos", x + "," + y + "," + z);
        out.put("emc", emc);
        if (type.condenser()) {
            out.put("work", work);
            out.put("target", targetId);
        }
        return out;
    }
}
