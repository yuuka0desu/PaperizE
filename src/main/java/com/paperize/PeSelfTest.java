package com.paperize;

import com.paperize.content.PeRegistry;
import com.paperize.emc.EmcEngine;
import com.paperize.machine.MachineStore;
import com.paperize.world.PeWorldTransmutation;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 启动自检：在服务器启动完成后向日志输出验证报告。
 *
 * <p>检查项：
 * <ol>
 *   <li>EMC 表规模与关键物品值对照 ProjectE 官方基准（公开 EMC 表）</li>
 *   <li>内容物品可构造性（CraftEngine 物品工厂 → Bukkit 物品）</li>
 *   <li>方块注册表与机器类型映射</li>
 *   <li>世界转换表装载</li>
 * </ol>
 */
public final class PeSelfTest {

    /** ProjectE 官方公开 EMC 基准值（用于一致性校验）。 */
    private static final String[][] BENCHMARKS = {
            {"minecraft:cobblestone", "1"},
            {"minecraft:coal", "128"},
            {"minecraft:iron_ingot", "256"},
            {"minecraft:gold_ingot", "2048"},
            {"minecraft:diamond", "8192"},
            {"minecraft:obsidian", "64"},
            {"minecraft:ender_pearl", "1024"},
            {"minecraft:nether_star", "139264"},
            {"projecte:philosophers_stone", "9984"},
            {"projecte:alchemical_coal", "512"},
            {"projecte:mobius_fuel", "2048"},
            {"projecte:aeternalis_fuel", "8192"},
            {"projecte:dark_matter", "139264"},
            {"projecte:red_matter", "466944"},
            {"projecte:klein_star_ein", "24576"},
            {"projecte:klein_star_zwei", "98304"},
            {"projecte:klein_star_drei", "393216"},
            {"projecte:klein_star_omega", "25165824"},
    };

    private PeSelfTest() {
    }

    /** 执行自检并把报告写入日志。 */
    public static void run(PaperizEPlugin plugin) {
        Logger log = plugin.getLogger();
        EmcEngine emc = plugin.emc();
        PeRegistry registry = plugin.registry();
        MachineStore machines = plugin.machines();
        PeWorldTransmutation world = plugin.worldTransmutation();

        log.info("===== PaperizE 启动自检 =====");

        // 1) EMC 基准对照
        int pass = 0;
        List<String> failures = new ArrayList<>();
        for (String[] pair : BENCHMARKS) {
            long actual = emc.emcOf(pair[0]);
            long expected = Long.parseLong(pair[1]);
            if (actual == expected) {
                pass++;
            } else {
                failures.add(String.format("%s 期望 %d 实际 %d", pair[0], expected, actual));
            }
        }
        log.info(String.format("[自检] EMC 基准：%d/%d 项符合 ProjectE 官方值；%s",
                pass, BENCHMARKS.length,
                failures.isEmpty() ? "全部通过" : "偏差 " + String.join(" | ", failures)));

        // 2) EMC 表规模
        long projecteValues = emc.knownIds().stream().filter(id -> id.startsWith("projecte:")).count();
        log.info(String.format("[自检] EMC 表：总 %d 项（projecte 内容 %d 项 / 原版 %d 项）",
                emc.valueCount(), projecteValues, emc.valueCount() - projecteValues));

        // 3) 内容物品可构造性（抽样）
        List<String> samples = List.of(
                "projecte:dark_matter", "projecte:red_matter", "projecte:philosophers_stone",
                "projecte:transmutation_table", "projecte:collector_mk1", "projecte:condenser_mk1",
                "projecte:alchemical_chest", "projecte:klein_star_omega", "projecte:dm_pick",
                "projecte:rm_chestplate", "projecte:tome", "projecte:transmutation_tablet");
        int constructible = 0;
        List<String> failed = new ArrayList<>();
        for (String id : samples) {
            try {
                BukkitItemDefinition definition = CraftEngineItems.byId(Key.of(id));
                if (definition == null) {
                    failed.add(id + "(未注册)");
                    continue;
                }
                ItemStack stack = definition.buildBukkitItem();
                if (stack != null && !stack.getType().isAir()) {
                    constructible++;
                } else {
                    failed.add(id + "(构造为空)");
                }
            } catch (Throwable t) {
                failed.add(id + "(" + t.getClass().getSimpleName() + ")");
            }
        }
        log.info(String.format("[自检] 物品工厂：%d/%d 可构造%s",
                constructible, samples.size(),
                failed.isEmpty() ? "" : "；异常 " + String.join(", ", failed)));

        // 4) 注册表与机器
        log.info(String.format("[自检] 注册表：物品 %d / 方块 %d；机器状态 %d 台；世界转换 %d 条",
                registry.items().size(), registry.blocks().size(), machines.size(), world.size()));

        // 5) 机器类型映射抽查
        List<String> unmapped = new ArrayList<>();
        for (String simple : List.of("collector_mk1", "relay_mk1", "condenser_mk1", "alchemical_chest", "transmutation_table")) {
            if (registry.block(simple) == null) {
                unmapped.add(simple);
            }
        }
        log.info("[自检] 机器方块映射：" + (unmapped.isEmpty() ? "齐全" : "缺失 " + String.join(", ", unmapped)));

        log.info("===== 自检结束 =====");
    }
}
