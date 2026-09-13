package com.paperize;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paperize.content.PeRegistry;
import com.paperize.emc.EmcTagResolver;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ProjectE → CraftEngine 内容包转换器。
 *
 * <p>把注册表转为 CE 内容 YAML（写入 {@code plugins/CraftEngine/resources/paperize/}）：
 * <ul>
 *   <li>物品：86 个（工具/护甲走原版底材以继承可用行为，其余 paper 贴皮）</li>
 *   <li>方块：21 个（block_item 管线；机器带水平朝向属性，转换台带六向属性）</li>
 *   <li>配方：合成配方（shaped/shapeless），原料闭包校验后导出</li>
 * </ul>
 * 标签原料（{@code c:gems/diamond} 等）在导出时收窄为具体成员（取首个可用项）。
 */
public final class PeCeConverter {

    private static final String PACK_NAME = "paperize";

    /** 水平朝向机器（collector/relay/condenser/furnace）。 */
    private static final Set<String> HORIZONTAL_FACING = Set.of(
            "collector_mk1", "collector_mk2", "collector_mk3",
            "relay_mk1", "relay_mk2", "relay_mk3",
            "condenser_mk1", "condenser_mk2", "dm_furnace", "rm_furnace");

    /** 六向方块（转换台）。 */
    private static final Set<String> ANY_FACING = Set.of("transmutation_table");

    /** 火把形态（禁止火把）：无朝向状态。 */
    private static final Set<String> TORCH_BLOCKS = Set.of("interdiction_torch");

    /**
     * 承载状态：指定自定义方块底层的原版方块状态（决定碰撞箱与物理行为）。
     *
     * <p>转换桌使用**阳光传感器**承载：矮板碰撞箱（14×6×14，贴地台面），
     * 其模型不随 power 变化，{@code inverted=true} 为反相形态（玩家极少先放），
     * 取 {@code inverted=true,power=0} 作为承载状态以避开常规传感器的状态区间。
     */
    private static final Map<String, String> CARRIER_STATES = Map.of(
            "transmutation_table", "minecraft:daylight_detector[inverted=true,power=0]",
            // 暗物质台座：紫水晶簇承载（小型尖簇碰撞箱，贴合基座形态）
            "dm_pedestal", "minecraft:amethyst_cluster[facing=up,waterlogged=false]");

    private PeCeConverter() {
    }

    /** 生成/更新 CE 内容包；返回是否有变更。 */
    public static boolean emit(PaperizEPlugin plugin, PeContentProvider provider, PeRegistry registry,
                               EmcTagResolver tags) throws IOException {
        Plugin ce = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (ce == null) {
            plugin.getLogger().warning("CraftEngine 未安装，跳过 CE 内容包生成。");
            return false;
        }
        Path packDir = ce.getDataFolder().toPath().resolve("resources").resolve(PACK_NAME);
        String packYml = "author: PaperizE\nversion: 1.0\ndescription: PaperizE content (CEPlus engine)\nnamespace: projecte\n";

        String itemsYml = buildItemsYml(registry, provider);
        RecipesOutput recipes = buildRecipesYml(plugin, provider, registry, tags);

        boolean changed = writeIfChanged(packDir.resolve("pack.yml"), packYml);
        changed |= writeIfChanged(packDir.resolve("configuration/items.yml"), itemsYml);
        changed |= writeIfChanged(packDir.resolve("configuration/recipes.yml"), recipes.yml());
        // 机器可读计划（供插件端转换台/凝聚器消费）
        changed |= writeIfChanged(plugin.getDataFolder().toPath().resolve("recipes-plan.json"), recipes.planJson());
        if (changed) {
            plugin.getLogger().info("PaperizE CE 内容包已写入: " + packDir);
        }
        return changed;
    }

    // ---- items.yml ----

    private static String buildItemsYml(PeRegistry registry, PeContentProvider provider) {
        TreeMap<String, String> entries = new TreeMap<>();
        int items = 0;
        int blocks = 0;

        for (PeRegistry.ItemEntry entry : registry.items().values()) {
            entries.put(entry.id(), itemYml(entry));
            items++;
        }
        for (PeRegistry.BlockEntry entry : registry.blocks().values()) {
            entries.put(entry.id(), blockYml(entry));
            blocks++;
        }
        StringBuilder out = new StringBuilder();
        out.append("# 由 PaperizE 自动生成（CEPlus 引擎），请勿手工编辑\n");
        out.append("items:\n");
        entries.values().forEach(out::append);
        out.append("# 共 ").append(entries.size()).append(" 个物品（普通 ").append(items)
                .append(" / 方块 ").append(blocks).append("）\n");
        return out.toString();
    }

    private static String itemYml(PeRegistry.ItemEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("  \"").append(entry.id()).append("\":\n");
        sb.append("    material: ").append(entry.material()).append('\n');
        sb.append("    data:\n");
        sb.append("      item_name: \"<!i><lang:").append(entry.nameKey()).append(">\"\n");
        sb.append("    model: ").append(entry.model()).append('\n');
        return sb.toString();
    }

    /** GUI 瓦片物品定义（界面拼贴用：纸底材质 + 原模组瓦片模型）。 */
    private static String guiTileYml(String id, String simple) {
        // 物品 id 为 projecte:gui_<machine>_<pos>，模型文件名为 <machine>_<pos>
        String model = simple.startsWith("gui_") ? simple.substring("gui_".length()) : simple;
        String nl = String.valueOf((char) 10);
        StringBuilder sb = new StringBuilder();
        sb.append("  \"").append(id).append("\":").append(nl);
        sb.append("    material: paper").append(nl);
        sb.append("    data:").append(nl);
        sb.append("      item_name: \"<!i><dark_gray>\"").append(nl);
        sb.append("    model: projecte_gui:item/").append(model).append(nl);
        return sb.toString();
    }

    /** 方块：block_item 管线 + 状态属性（朝向）。 */
    private static String blockYml(PeRegistry.BlockEntry entry) {
        String simple = entry.simple();
        StringBuilder sb = new StringBuilder();
        sb.append("  \"").append(entry.id()).append("\":\n");
        sb.append("    material: paper\n");
        sb.append("    data:\n");
        sb.append("      item_name: \"<!i><lang:").append(entry.nameKey()).append(">\"\n");
        sb.append("    model: ").append(entry.model()).append('\n');
        sb.append("    behavior:\n");
        sb.append("      type: block_item\n");
        sb.append("      block:\n");
        sb.append("        loot:\n");
        sb.append("          template: default:loot_table/self\n");
        sb.append("        settings:\n");
        sb.append("          overrides:\n");
        sb.append("            hardness: ").append(trimNumber(entry.hardness())).append('\n');
        sb.append("            resistance: ").append(trimNumber(entry.resistance())).append('\n');
        if (entry.light() > 0) {
            sb.append("            luminance: ").append(entry.light()).append('\n');
        }
        if (entry.orientation() != null && !entry.torch()) {
            sb.append("            tags:\n");
            sb.append("              - minecraft:mineable/pickaxe\n");
        }
        sb.append("        states:\n");
        if (entry.hasFacing()) {
            String type = entry.anyFacing() ? "direction" : "horizontal_direction";
            sb.append("          properties:\n");
            sb.append("            facing:\n");
            sb.append("              type: ").append(type).append('\n');
            sb.append("              default: north\n");
            sb.append("          appearances:\n");
            sb.append("            base:\n");
            String carrier = CARRIER_STATES.get(simple);
            if (carrier != null) {
                sb.append("              state: ").append(carrier).append('\n');
            } else {
                sb.append("              auto_state: solid\n");
            }
            sb.append("              model:\n");
            sb.append("                path: ").append(entry.model()).append('\n');
            sb.append("          variants:\n");
            for (String value : facingValues(entry)) {
                sb.append("            facing=").append(value).append(":\n");
                sb.append("              appearance: base\n");
            }
        } else {
            String carrier = CARRIER_STATES.get(simple);
            if (carrier != null) {
                sb.append("          state: ").append(carrier).append('\n');
            } else {
                sb.append("          auto_state: solid\n");
            }
            sb.append("          model:\n");
            sb.append("            path: ").append(entry.model()).append('\n');
        }
        return sb.toString();
    }

    private static List<String> facingValues(PeRegistry.BlockEntry entry) {
        if (entry.anyFacing()) {
            return List.of("north", "south", "east", "west", "up", "down");
        }
        return List.of("north", "south", "east", "west");
    }

    private static String trimNumber(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    // ---- recipes.yml ----

    private record RecipesOutput(String yml, String planJson) {
    }

    private static RecipesOutput buildRecipesYml(PaperizEPlugin plugin, PeContentProvider provider, PeRegistry registry,
                                                 EmcTagResolver tags)
            throws IOException {
        Set<String> exported = new LinkedHashSet<>();
        for (PeRegistry.ItemEntry entry : registry.items().values()) {
            exported.add(entry.id());
        }
        for (PeRegistry.BlockEntry entry : registry.blocks().values()) {
            exported.add(entry.id());
        }

        JsonArray index;
        try (InputStream in = provider.openRecipesIndex()) {
            if (in == null) {
                return new RecipesOutput("recipes: {}\n", "{}");
            }
            index = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }

        TreeMap<String, String> entries = new TreeMap<>();
        Map<String, String> plan = new LinkedHashMap<>();
        int shaped = 0;
        int shapeless = 0;
        int skippedType = 0;
        int skippedClosure = 0;
        int narrowed = 0;

        for (JsonElement element : index) {
            String rel = element.getAsString();
            JsonObject recipe;
            try (InputStream in = provider.openRecipe(rel)) {
                if (in == null) {
                    continue;
                }
                recipe = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            String type = optString(recipe, "type");
            String key = uniqueKey(rel, entries);
            if ("minecraft:crafting_shaped".equals(type)) {
                String yml = convertShaped(key, recipe, exported, tags, new int[1]);
                if (yml == null) {
                    skippedClosure++;
                } else {
                    entries.put(key, yml);
                    shaped++;
                }
            } else if ("minecraft:crafting_shapeless".equals(type)) {
                String yml = convertShapeless(key, recipe, exported, tags, new int[1]);
                if (yml == null) {
                    skippedClosure++;
                } else {
                    entries.put(key, yml);
                    shapeless++;
                }
            } else {
                skippedType++;
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("# 由 PaperizE 自动生成（CEPlus 引擎）；仅导出合成配方且闭包校验通过\n");
        out.append("recipes:\n");
        entries.forEach((k, v) -> out.append(v));
        out.append("# 共 ").append(entries.size()).append(" 条（shaped ").append(shaped)
                .append(" / shapeless ").append(shapeless)
                .append("；跳过 类型 ").append(skippedType).append(" / 闭包 ").append(skippedClosure)
                .append("）\n");

        plugin.getLogger().info(String.format(
                "配方转换：导出 %d 条（shaped %d / shapeless %d；跳过 类型 %d / 闭包 %d）",
                entries.size(), shaped, shapeless, skippedType, skippedClosure));

        String planJson = renderPlan(entries);
        return new RecipesOutput(out.toString(), planJson);
    }

    /** 机器可读计划（id → 配方描述），供运行时合成/查询消费。 */
    private static String renderPlan(TreeMap<String, String> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("count", entries.size());
        JsonArray ids = new JsonArray();
        entries.keySet().forEach(ids::add);
        root.add("recipes", ids);
        return root.toString();
    }

    private static String uniqueKey(String rel, Map<String, String> existing) {
        String key = rel.replace('/', '_').replace(".json", "");
        if (!existing.containsKey(key)) {
            return key;
        }
        int i = 2;
        while (existing.containsKey(key + "_" + i)) {
            i++;
        }
        return key + "_" + i;
    }

    private static String convertShaped(String key, JsonObject recipe, Set<String> exported,
                                        EmcTagResolver tags, int[] narrowed) {
        if (!recipe.has("key") || !recipe.has("pattern") || !recipe.has("result")) {
            return null;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : recipe.getAsJsonObject("key").entrySet()) {
            String id = resolveIngredient(entry.getValue(), exported, tags, narrowed);
            if (id == null) {
                return null;
            }
            resolved.put(entry.getKey(), id);
        }
        String[] result = resolveResult(recipe.getAsJsonObject("result"), exported);
        if (result == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  \"").append(key).append("\":\n");
        sb.append("    type: shaped\n");
        sb.append("    pattern:\n");
        for (JsonElement row : recipe.getAsJsonArray("pattern")) {
            sb.append("      - '").append(row.getAsString()).append("'\n");
        }
        sb.append("    ingredients:\n");
        resolved.forEach((letter, id) -> sb.append("      \"").append(letter).append("\": ").append(id).append('\n'));
        sb.append("    result:\n      id: ").append(result[0]).append("\n      count: ").append(result[1]).append('\n');
        return sb.toString();
    }

    private static String convertShapeless(String key, JsonObject recipe, Set<String> exported,
                                           EmcTagResolver tags, int[] narrowed) {
        if (!recipe.has("ingredients") || !recipe.has("result")) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (JsonElement element : recipe.getAsJsonArray("ingredients")) {
            String id = resolveIngredient(element, exported, tags, narrowed);
            if (id == null) {
                return null;
            }
            ids.add(id);
        }
        String[] result = resolveResult(recipe.getAsJsonObject("result"), exported);
        if (result == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  \"").append(key).append("\":\n");
        sb.append("    type: shapeless\n");
        sb.append("    ingredients:\n");
        ids.forEach(id -> sb.append("      - ").append(id).append('\n'));
        sb.append("    result:\n      id: ").append(result[0]).append("\n      count: ").append(result[1]).append('\n');
        return sb.toString();
    }

    /** 原料解析：item/tag/多选一 → 具体 id；不可解析返回 null（配方整体跳过）。 */
    private static String resolveIngredient(JsonElement element, Set<String> exported,
                                            EmcTagResolver tags, int[] narrowed) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray options = element.getAsJsonArray();
            for (JsonElement option : options) {
                String id = resolveIngredient(option, exported, tags, narrowed);
                if (id != null) {
                    return id;
                }
            }
            return null;
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject o = element.getAsJsonObject();
        if (o.has("tag")) {
            String tag = o.get("tag").getAsString();
            String member = pickTagMember(tag, exported, tags);
            if (member != null) {
                narrowed[0]++;
            }
            return member;
        }
        String id = optString(o, "item");
        if (id == null) {
            id = optString(o, "id");
        }
        if (id == null) {
            return null;
        }
        if (id.startsWith("projecte:") && !exported.contains(id)) {
            return null; // 引用了未导出的模组内部物品
        }
        return id;
    }

    /**
     * 标签收窄：优先取已导出的 projecte 成员（标签含模组物品时），否则取首个成员。
     * 成员来自提取的标签数据（EmcTagResolver）。
     */
    private static String pickTagMember(String tag, Set<String> exported, EmcTagResolver tags) {
        Set<String> members = tags.expand(tag);
        if (members.isEmpty()) {
            return null;
        }
        for (String member : members) {
            if (exported.contains(member)) {
                return member;
            }
        }
        return members.iterator().next();
    }

    private static String[] resolveResult(JsonObject result, Set<String> exported) {
        if (result == null || !result.has("id")) {
            return null;
        }
        String id = result.get("id").getAsString();
        if (id.startsWith("projecte:") && !exported.contains(id)) {
            return null;
        }
        long count = result.has("count") ? result.get("count").getAsLong() : 1;
        return new String[]{id, String.valueOf(count)};
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static boolean writeIfChanged(Path path, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(path) && Arrays.equals(Files.readAllBytes(path), data)) {
            return false;
        }
        Files.createDirectories(path.getParent());
        Files.write(path, data);
        return true;
    }
}
