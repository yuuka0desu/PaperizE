package com.paperize.machine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能量收集器升级链：消耗 EMC 把物质逐级升级为更高阶形态。
 *
 * <p>链路（物质 / 升级消耗）：
 * <pre>
 *   木炭 32 →红石 +32
 *   红石 64 →煤炭 +64
 *   煤炭 128 →火药 +64
 *   火药 192 →荧石粉 +192
 *   荧石粉 384 →炼金煤炭 +128
 *   炼金煤炭 512 →红石块 +64
 *   红石块 576 →烈焰粉 +192
 *   烈焰粉 768 →煤炭块 +384
 *   煤炭块 1152 →荧石 +384
 *   荧石 1536 →莫比乌斯燃料 +512
 *   莫比乌斯燃料 2048 →炼金煤炭块 +2560
 *   炼金煤炭块 4608 →永恒燃料 +3584
 *   永恒燃料 8192 →莫比乌斯燃料块 +10240
 *   莫比乌斯燃料块 18432 →永恒燃料块 +55296
 *   永恒燃料块 73728（终点）
 * </pre>
 * 全链累计消耗 73,696 EMC。
 */
public final class CollectorChain {

    /** 升级目标与消耗。 */
    public record Upgrade(String targetId, long cost) {
    }

    private static final Map<String, Upgrade> CHAIN = new LinkedHashMap<>();

    static {
        chain("minecraft:charcoal", "minecraft:redstone", 32);
        chain("minecraft:redstone", "minecraft:coal", 64);
        chain("minecraft:coal", "minecraft:gunpowder", 64);
        chain("minecraft:gunpowder", "minecraft:glowstone_dust", 192);
        chain("minecraft:glowstone_dust", "projecte:alchemical_coal", 128);
        chain("projecte:alchemical_coal", "minecraft:redstone_block", 64);
        chain("minecraft:redstone_block", "minecraft:blaze_powder", 192);
        chain("minecraft:blaze_powder", "minecraft:coal_block", 384);
        chain("minecraft:coal_block", "minecraft:glowstone", 384);
        chain("minecraft:glowstone", "projecte:mobius_fuel", 512);
        chain("projecte:mobius_fuel", "projecte:alchemical_coal_block", 2560);
        chain("projecte:alchemical_coal_block", "projecte:aeternalis_fuel", 3584);
        chain("projecte:aeternalis_fuel", "projecte:mobius_fuel_block", 10240);
        chain("projecte:mobius_fuel_block", "projecte:aeternalis_fuel_block", 55296);
    }

    private CollectorChain() {
    }

    private static void chain(String from, String to, long cost) {
        CHAIN.put(from, new Upgrade(to, cost));
    }

    /** 查询下一级；无下一级（终点或未知物品）返回 null。 */
    public static Upgrade next(String id) {
        return id == null ? null : CHAIN.get(id);
    }

    /** 全链物品 id（含起点与各级目标，供展示/诊断）。 */
    public static List<String> allItems() {
        List<String> out = new java.util.ArrayList<>();
        out.add("minecraft:charcoal");
        for (Upgrade upgrade : CHAIN.values()) {
            out.add(upgrade.targetId());
        }
        return out;
    }

    /** 全链累计消耗。 */
    public static long totalCost() {
        long total = 0;
        for (Upgrade upgrade : CHAIN.values()) {
            total += upgrade.cost();
        }
        return total;
    }

    /** 链路长度。 */
    public static int length() {
        return CHAIN.size();
    }
}
