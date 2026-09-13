package com.paperize.emc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * EMC 装载器：把声明式数据编译进 {@link EmcCollector}。
 *
 * <p>数据源：
 * <ol>
 *   <li>{@code content/conversions/**}（pe_custom_conversions）：固定值（before/after）与显式转换</li>
 *   <li>{@code content/recipes/**}（ProjectE 配方）：合成配方 → 转换关系</li>
 *   <li>服务器配方（{@code Bukkit.recipeIterator}）：原版配方 → 转换关系
 *       （跳过参与物为 CraftEngine 自定义物品的条目，避免与内容包重复）</li>
 * </ol>
 * 标签（{@code #c:...}、{@code #minecraft:...}）在装载时展开为具体成员：
 * 固定值设给全部成员；转换则为每个成员生成一条，语义与 ProjectE 的 TagMapper 对齐。
 */
public final class EmcLoader {

    private final EmcTagResolver tags;
    private final Logger log;

    public EmcLoader(EmcTagResolver tags, Logger log) {
        this.tags = tags;
        this.log = log;
    }

    public record LoadResult(int fixedValues, int conversions, int tagExpansions, int skipped) {
    }

    /** 全量装载（转换配置 + 配方 + 服务器配方）。 */
    public LoadResult load(Path conversionsDir, Path recipesDir, boolean includeServerRecipes) {
        EmcCollector collector = new EmcCollector();
        int[] counters = new int[4];
        loadConversionFiles(conversionsDir, collector, counters);
        loadRecipeFiles(recipesDir, collector, counters);
        if (includeServerRecipes) {
            loadServerRecipes(collector, counters);
        }
        this.collector = collector;
        return new LoadResult(counters[0], counters[1], counters[2], counters[3]);
    }

    private EmcCollector collector;

    public EmcCollector collector() {
        return collector;
    }

    // ---- 转换配置文件（pe_custom_conversions） ----

    private void loadConversionFiles(Path dir, EmcCollector collector, int[] counters) {
        if (!Files.isDirectory(dir)) {
            log.warning("转换配置目录缺失: " + dir);
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    parseConversionFile(root, collector, counters);
                } catch (Exception e) {
                    log.warning("转换配置解析失败 " + file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warning("转换配置目录扫描失败: " + e.getMessage());
        }
    }

    private void parseConversionFile(JsonObject root, EmcCollector collector, int[] counters) {
        if (root.has("values") && root.get("values").isJsonObject()) {
            JsonObject values = root.getAsJsonObject("values");
            if (values.has("before")) {
                for (JsonElement element : values.getAsJsonArray("before")) {
                    applyFixedValue(element.getAsJsonObject(), collector, counters, true);
                }
            }
            if (values.has("after")) {
                for (JsonElement element : values.getAsJsonArray("after")) {
                    applyFixedValue(element.getAsJsonObject(), collector, counters, false);
                }
            }
            if (values.has("conversion")) {
                for (JsonElement element : values.getAsJsonArray("conversion")) {
                    applyExplicitConversion(element.getAsJsonObject(), collector, counters);
                }
            }
        }
        if (root.has("groups") && root.get("groups").isJsonObject()) {
            for (Map.Entry<String, JsonElement> group : root.getAsJsonObject("groups").entrySet()) {
                JsonObject g = group.getValue().getAsJsonObject();
                if (!g.has("conversions")) {
                    continue;
                }
                for (JsonElement element : g.getAsJsonArray("conversions")) {
                    parseGroupConversion(element.getAsJsonObject(), collector, counters);
                }
            }
        }
        if (root.has("transmutations")) {
            // 世界转换由 PeWorldTransmutation 单独装载，此处忽略
        }
    }

    /** values.before / values.after 条目：{type, emc_value, id|tag}。 */
    private void applyFixedValue(JsonObject entry, EmcCollector collector, int[] counters, boolean before) {
        String type = optString(entry, "type");
        if (type == null || !type.endsWith(":item")) {
            counters[3]++;
            return; // 流体/伪值：无物品对应
        }
        if (!entry.has("emc_value")) {
            counters[3]++;
            return;
        }
        JsonElement valueElement = entry.get("emc_value");
        if (!valueElement.isJsonPrimitive() || !valueElement.getAsJsonPrimitive().isNumber()) {
            counters[3]++;
            return; // "free" 等非数值语义
        }
        long value = valueElement.getAsLong();
        List<String> targets = new ArrayList<>();
        if (entry.has("id")) {
            targets.add(entry.get("id").getAsString());
        } else if (entry.has("tag")) {
            Set<String> members = tags.expand(entry.get("tag").getAsString());
            counters[2]++;
            if (members.isEmpty()) {
                counters[3]++;
                return;
            }
            targets.addAll(members);
        } else {
            counters[3]++;
            return;
        }
        for (String id : targets) {
            if (before) {
                collector.setValueBefore(id, value);
            } else {
                collector.setValueAfter(id, value);
            }
            counters[0]++;
        }
    }

    /** values.conversion 条目：{ingredients, output, propagateTags}。 */
    private void applyExplicitConversion(JsonObject entry, EmcCollector collector, int[] counters) {
        if (!entry.has("ingredients") || !entry.has("output")) {
            counters[3]++;
            return;
        }
        List<EmcConversion.Ingredient> ingredients = parseIngredients(entry.getAsJsonArray("ingredients"), counters);
        if (ingredients.isEmpty()) {
            counters[3]++;
            return;
        }
        JsonObject output = entry.getAsJsonObject("output");
        List<String> outputs = new ArrayList<>();
        if (output.has("id")) {
            outputs.add(output.get("id").getAsString());
        }
        if (output.has("tag")) {
            Set<String> members = tags.expand(output.get("tag").getAsString());
            counters[2]++;
            if (members.isEmpty() && !output.has("id")) {
                counters[3]++;
                return;
            }
            outputs.addAll(members);
        }
        if (outputs.isEmpty()) {
            counters[3]++;
            return;
        }
        long outputCount = output.has("count") ? output.get("count").getAsLong() : 1;
        for (String id : outputs) {
            collector.addConversion(new EmcConversion(id, outputCount, List.copyOf(ingredients)));
            counters[1]++;
        }
    }

    /** groups.<name>.conversions 条目：{ingredients, output}。 */
    private void parseGroupConversion(JsonObject entry, EmcCollector collector, int[] counters) {
        applyExplicitConversion(entry, collector, counters);
    }

    /** 原料数组 → 原料列表（支持 tag/id 与多选一数组）。 */
    private List<EmcConversion.Ingredient> parseIngredients(JsonArray array, int[] counters) {
        List<EmcConversion.Ingredient> out = new ArrayList<>();
        for (JsonElement element : array) {
            parseIngredient(element, out, counters);
        }
        return out;
    }

    /** 单条目：{item|id|tag, amount} 或 [选项, 选项]（多选一 → 由调用方展开为多条转换）。 */
    private void parseIngredient(JsonElement element, List<EmcConversion.Ingredient> out, int[] counters) {
        if (element.isJsonArray()) {
            // 多选一：取第一个可用选项（其余选项由多转换展开覆盖）
            JsonArray options = element.getAsJsonArray();
            if (!options.isEmpty()) {
                parseIngredient(options.get(0), out, counters);
            }
            return;
        }
        if (!element.isJsonObject()) {
            counters[3]++;
            return;
        }
        JsonObject o = element.getAsJsonObject();
        long amount = o.has("amount") ? o.get("amount").getAsLong() : 1;
        if (o.has("tag")) {
            String tag = o.get("tag").getAsString();
            out.add(EmcConversion.tag(tag, amount));
            counters[2]++;
            return;
        }
        String id = optString(o, "item");
        if (id == null) {
            id = optString(o, "id");
        }
        if (id == null) {
            counters[3]++;
            return;
        }
        if (isCatalyst(id)) {
            return; // 转化触媒不计价
        }
        out.add(EmcConversion.item(id, amount));
    }

    /**
     * 催化剂判定：哲学者之石在转换配方中作为触媒出现，不消耗计量。
     *
     * <p>事实依据：ProjectE 公开 EMC 表（炼金煤炭 512 = 4×煤炭 128、金锭 2048 = 8×铁锭 256、
     * 暗物质 139264）仅在触媒不计价时成立；若计入哲学者之石 9984，上述值会分别放大到
     * 10496 / 4544 / 数百万级。
     */
    private static boolean isCatalyst(String id) {
        return "projecte:philosophers_stone".equals(id);
    }

    // ---- ProjectE 配方（合成配方 → 转换） ----

    private void loadRecipeFiles(Path dir, EmcCollector collector, int[] counters) {
        if (!Files.isDirectory(dir)) {
            log.warning("配方目录缺失: " + dir);
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    parseRecipe(root, collector, counters);
                } catch (Exception e) {
                    log.warning("配方解析失败 " + file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warning("配方目录扫描失败: " + e.getMessage());
        }
    }

    private void parseRecipe(JsonObject recipe, EmcCollector collector, int[] counters) {
        String type = optString(recipe, "type");
        if (type == null) {
            counters[3]++;
            return;
        }
        switch (type) {
            case "minecraft:crafting_shaped" -> parseShaped(recipe, collector, counters);
            case "minecraft:crafting_shapeless" -> parseShapeless(recipe, collector, counters);
            default -> counters[3]++; // projecte:covalence_repair / philo_stone_smelting：非配方型转换
        }
    }

    private void parseShaped(JsonObject recipe, EmcCollector collector, int[] counters) {
        if (!recipe.has("pattern") || !recipe.has("key") || !recipe.has("result")) {
            counters[3]++;
            return;
        }
        JsonObject key = recipe.getAsJsonObject("key");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonElement line : recipe.getAsJsonArray("pattern")) {
            String row = line.getAsString();
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c == ' ') {
                    continue;
                }
                counts.merge(String.valueOf(c), 1, Integer::sum);
            }
        }
        List<EmcConversion.Ingredient> ingredients = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!key.has(entry.getKey())) {
                continue;
            }
            JsonElement keyValue = key.get(entry.getKey());
            List<EmcConversion.Ingredient> parsed = new ArrayList<>();
            parseIngredient(keyValue, parsed, counters);
            for (EmcConversion.Ingredient ingredient : parsed) {
                ingredients.add(new EmcConversion.Ingredient(ingredient.target(), ingredient.amount() * entry.getValue()));
            }
        }
        emitRecipeResult(recipe.getAsJsonObject("result"), ingredients, collector, counters);
    }

    private void parseShapeless(JsonObject recipe, EmcCollector collector, int[] counters) {
        if (!recipe.has("ingredients") || !recipe.has("result")) {
            counters[3]++;
            return;
        }
        List<EmcConversion.Ingredient> ingredients = new ArrayList<>();
        for (JsonElement element : recipe.getAsJsonArray("ingredients")) {
            parseIngredient(element, ingredients, counters);
        }
        emitRecipeResult(recipe.getAsJsonObject("result"), ingredients, collector, counters);
    }

    private void emitRecipeResult(JsonObject result, List<EmcConversion.Ingredient> ingredients,
                                  EmcCollector collector, int[] counters) {
        if (result == null || !result.has("id") || ingredients.isEmpty()) {
            counters[3]++;
            return;
        }
        String id = result.get("id").getAsString();
        long count = result.has("count") ? result.get("count").getAsLong() : 1;
        List<EmcConversion.Ingredient> expanded = expandTags(ingredients, counters);
        if (expanded.isEmpty()) {
            counters[3]++;
            return;
        }
        collector.addConversion(new EmcConversion(id, count, List.copyOf(expanded)));
        counters[1]++;
    }

    /** 标签原料展开：标签成员按顺序并入（求解时任一成员即可满足）。 */
    private List<EmcConversion.Ingredient> expandTags(List<EmcConversion.Ingredient> ingredients, int[] counters) {
        List<EmcConversion.Ingredient> out = new ArrayList<>();
        for (EmcConversion.Ingredient ingredient : ingredients) {
            if (!ingredient.isTag()) {
                out.add(ingredient);
                continue;
            }
            Set<String> members = tags.expand(ingredient.key());
            counters[2]++;
            if (members.isEmpty()) {
                continue; // 未识别标签：丢弃该原料（配方自动失效）
            }
            for (String member : members) {
                out.add(new EmcConversion.Ingredient(member, ingredient.amount()));
            }
        }
        return out;
    }

    // ---- 服务器配方（原版） ----

    private void loadServerRecipes(EmcCollector collector, int[] counters) {
        int scanned = 0;
        try {
            var iterator = Bukkit.recipeIterator();
            while (iterator.hasNext()) {
                Recipe recipe = iterator.next();
                scanned++;
                List<EmcConversion.Ingredient> ingredients = new ArrayList<>();
                String outputId = null;
                long outputCount = 1;

                if (recipe instanceof ShapedRecipe shaped) {
                    if (isCustom(shaped.getResult())) {
                        continue;
                    }
                    outputId = vanillaId(shaped.getResult());
                    outputCount = shaped.getResult().getAmount();
                    // 注意：Paper 26.1 的 getChoiceMap() 键为 Character
                    Map<Character, RecipeChoice> choiceMap = shaped.getChoiceMap();
                    for (String symbol : shaped.getShape()) {
                        for (char c : symbol.toCharArray()) {
                            if (c == ' ') {
                                continue;
                            }
                            addChoice(choiceMap.get(c), ingredients);
                        }
                    }
                } else if (recipe instanceof ShapelessRecipe shapeless) {
                    if (isCustom(shapeless.getResult())) {
                        continue;
                    }
                    outputId = vanillaId(shapeless.getResult());
                    outputCount = shapeless.getResult().getAmount();
                    for (RecipeChoice choice : shapeless.getChoiceList()) {
                        addChoice(choice, ingredients);
                    }
                } else if (recipe instanceof CookingRecipe<?> cooking) {
                    // 熔炼/烟熏/营火：输入 → 输出（1:1；燃料成本与 ProjectE 一致不计入）
                    if (isCustom(cooking.getResult())) {
                        continue;
                    }
                    outputId = vanillaId(cooking.getResult());
                    outputCount = cooking.getResult().getAmount();
                    addChoice(cooking.getInputChoice(), ingredients);
                } else {
                    continue; // 锻造等：EMC 通过配置固定值覆盖
                }

                if (outputId == null || ingredients.isEmpty()) {
                    counters[3]++;
                    continue;
                }
                collector.addConversion(new EmcConversion(outputId, outputCount, List.copyOf(ingredients)));
                counters[1]++;
            }
        } catch (Throwable t) {
            log.warning("服务器配方遍历异常（已跳过）: " + t);
        }
        log.info("服务器配方扫描：" + scanned + " 条（纳入 EMC 转换 " + counters[1] + " 条累计）");
    }

    private void addChoice(RecipeChoice choice, List<EmcConversion.Ingredient> ingredients) {
        if (choice == null) {
            return;
        }
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            // 多选一 = OR 语义：取首个候选作为代表（原版同类等价物 EMC 同值，
            // 如 11 种木板统一 8 点；若全部立案会退化为 AND 虚增成本）
            if (!materialChoice.getChoices().isEmpty()) {
                var material = materialChoice.getChoices().get(0);
                ingredients.add(EmcConversion.item("minecraft:" + material.getKey().getKey(), 1));
            }
        } else if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
            for (ItemStack stack : exactChoice.getChoices()) {
                if (!isCustom(stack)) {
                    ingredients.add(EmcConversion.item(vanillaId(stack), stack.getAmount()));
                    break; // 同上：多选一取首个
                }
            }
        }
    }

    private boolean isCustom(ItemStack stack) {
        try {
            return CraftEngineItems.isCustomItem(stack);
        } catch (Throwable t) {
            return false;
        }
    }

    private String vanillaId(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        return "minecraft:" + stack.getType().getKey().getKey();
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
