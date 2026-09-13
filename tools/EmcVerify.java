import com.paperize.emc.EmcGraphMapper;
import com.paperize.emc.EmcLoader;
import com.paperize.emc.EmcTagResolver;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 离线验证：用提取后的真实数据跑 EMC 求解管线（无服务器配方路径）。
 *
 * <p>运行：
 * <pre>
 * javac -cp target/classes:... tools/EmcVerify.java -d tools/out
 * java -cp target/classes:tools/out:... EmcVerify
 * </pre>
 */
public final class EmcVerify {

    public static void main(String[] args) {
        Logger log = Logger.getLogger("EmcVerify");
        Path content = Path.of("src/main/resources/content");

        EmcTagResolver tags = new EmcTagResolver(log);
        int loaded = tags.loadDirectory(content.resolve("tags"));
        System.out.println("[标签] 装载 " + loaded + " 个 → " + tags.describe());
        System.out.println("[标签] 样例 c:ingots/iron = " + tags.expand("c:ingots/iron"));
        System.out.println("[标签] 样例 minecraft:planks = " + sample(tags.expand("minecraft:planks")));

        EmcLoader loader = new EmcLoader(tags, log);
        long start = System.currentTimeMillis();
        EmcLoader.LoadResult result = loader.load(
                content.resolve("conversions"), content.resolve("recipes"), false);
        System.out.printf("[装载] 固定值 %d / 转换 %d / 标签展开 %d / 跳过 %d（%d ms）%n",
                result.fixedValues(), result.conversions(), result.tagExpansions(),
                result.skipped(), System.currentTimeMillis() - start);

        // 模拟服务器配方路径（离线环境下 Bukkit.recipeIterator 不可用）：
        // 与 EmcLoader.loadServerRecipes 等价的原版压缩/熔炼配方子集
        var collector = loader.collector();
        compress(collector, "minecraft:diamond_block", "minecraft:diamond");
        compress(collector, "minecraft:iron_block", "minecraft:iron_ingot");
        compress(collector, "minecraft:gold_block", "minecraft:gold_ingot");
        compress(collector, "minecraft:coal_block", "minecraft:coal");
        compress(collector, "minecraft:redstone_block", "minecraft:redstone");
        compress(collector, "minecraft:lapis_block", "minecraft:lapis_lazuli");
        compress(collector, "minecraft:glowstone", "minecraft:glowstone_dust");
        smelt(collector, "minecraft:stone", "minecraft:cobblestone");
        smelt(collector, "minecraft:glass", "minecraft:sand");
        smelt(collector, "minecraft:charcoal", "minecraft:oak_log");
        System.out.println("[模拟] 已注入原版压缩/熔炼配方（服务器路径等价物）");

        EmcGraphMapper.Result solved = EmcGraphMapper.solve(loader.collector());
        System.out.printf("[求解] %d 项 / %d 轮 / %s%n",
                solved.values().size(), solved.rounds(), solved.converged() ? "收敛" : "未完全收敛");

        Map<String, Long> values = solved.values();
        String[][] expectations = {
                {"minecraft:cobblestone", "1"},
                {"minecraft:coal", "128"},
                {"minecraft:iron_ingot", "256"},
                {"minecraft:gold_ingot", "2048"},
                {"minecraft:diamond", "8192"},
                {"minecraft:obsidian", "64"},
                {"minecraft:ender_pearl", "1024"},
                {"minecraft:nether_star", "139264"},
                {"projecte:alchemical_coal", "512"},
                {"projecte:mobius_fuel", "2048"},
                {"projecte:aeternalis_fuel", "8192"},
                {"projecte:dark_matter", "139264"},
                {"projecte:red_matter", "466944"},
                {"projecte:philosophers_stone", "?"},
                {"projecte:klein_star_ein", "?"},
                {"projecte:collector_mk1", "?"},
                {"projecte:transmutation_table", "?"},
                {"projecte:dark_matter_block", "?"},
        };
        System.out.println("[核对] 关键物品 EMC：");
        int matched = 0;
        int checked = 0;
        for (String[] pair : expectations) {
            Long value = values.get(pair[0]);
            String actual = value == null ? "（无值）" : String.valueOf(value);
            boolean expect = !"?".equals(pair[1]);
            String verdict = "";
            if (expect) {
                checked++;
                boolean ok = actual.equals(pair[1]);
                if (ok) {
                    matched++;
                }
                verdict = ok ? "  ✓" : "  ✗ 期望 " + pair[1];
            }
            System.out.printf("  %-32s %-12s%s%n", pair[0], actual, verdict);
        }
        System.out.printf("[核对] %d/%d 项符合 ProjectE 基准值%n", matched, checked);

        System.out.println("[统计] 高价值物品 Top 10：");
        values.entrySet().stream()
                .filter(e -> e.getKey().startsWith("projecte:"))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> System.out.printf("  %-36s %d%n", e.getKey(), e.getValue()));

        System.out.println("[统计] projecte 物品有值数：" + values.keySet().stream()
                .filter(k -> k.startsWith("projecte:")).count());
        System.out.println("[统计] minecraft 物品有值数：" + values.keySet().stream()
                .filter(k -> k.startsWith("minecraft:")).count());
    }

    /** 9 合 1 压缩配方。 */
    private static void compress(com.paperize.emc.EmcCollector collector, String block, String unit) {
        collector.addConversion(new com.paperize.emc.EmcConversion(block, 1,
                java.util.List.of(com.paperize.emc.EmcConversion.item(unit, 9))));
    }

    /** 1:1 熔炼配方。 */
    private static void smelt(com.paperize.emc.EmcCollector collector, String output, String input) {
        collector.addConversion(new com.paperize.emc.EmcConversion(output, 1,
                java.util.List.of(com.paperize.emc.EmcConversion.item(input, 1))));
    }

    private static String sample(java.util.Set<String> set) {
        int i = 0;
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (i++ >= 5) {
                sb.append("…");
                break;
            }
            sb.append(s).append(' ');
        }
        return sb.toString();
    }
}
