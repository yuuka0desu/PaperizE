package com.paperize.emc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 标签解析器：把 {@code #c:ingots/iron} 之类的标签展开成具体物品 id 集合。
 *
 * <p>来源：
 * <ul>
 *   <li>插件数据目录 {@code tags/**}（提取的 ProjectE / c: / curios 约定标签）</li>
 *   <li>服务器原版标签（{@code Bukkit.getTag}，作为补充）</li>
 * </ul>
 * 未识别的标签展开为空集（对应转换自动失效，不产生悬空引用）。
 */
public final class EmcTagResolver {

    /** 标签 id（不含 #）→ 物品 id 集合。 */
    private final Map<String, Set<String>> resolved = new LinkedHashMap<>();
    /** 原始条目：标签 id → （物品条目，嵌套标签条目）。 */
    private final Map<String, RawTag> raw = new LinkedHashMap<>();
    private final Logger log;

    private record RawTag(Set<String> entries, Set<String> nested) {
    }

    public EmcTagResolver(Logger log) {
        this.log = log;
    }

    /** 从数据目录装载标签（{@code <ns>/<path>.json}，对应 {@code #ns:path}）。 */
    public int loadDirectory(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                String rel = root.relativize(file).toString().replace('\\', '/');
                String id = normalize(rel);
                try (InputStream in = Files.newInputStream(file)) {
                    raw.put(id, parseTag(in));
                }
            }
        } catch (IOException e) {
            log.warning("标签目录扫描失败: " + e.getMessage());
        }
        return resolveAll();
    }

    /** 合并原版标签（minecraft 命名空间，来自服务器内置数据）。 */
    public int loadVanillaTags(List<String> tagIds) {
        int loaded = 0;
        for (String id : tagIds) {
            Set<String> members = resolveVanilla(id);
            if (!members.isEmpty()) {
                raw.put(id, new RawTag(members, Set.of()));
                loaded++;
            }
        }
        return loaded == 0 ? 0 : resolveAll();
    }

    /** 展开全部标签（一层嵌套展开 + 反向传递闭包一次，避免自引用死循环）。 */
    private int resolveAll() {
        resolved.clear();
        for (Map.Entry<String, RawTag> entry : raw.entrySet()) {
            resolved.put(entry.getKey(), new LinkedHashSet<>(entry.getValue().entries()));
        }
        // 嵌套展开：把嵌套标签的成员并入引用方（一轮即可覆盖 datapack 常态层级）
        for (Map.Entry<String, RawTag> entry : raw.entrySet()) {
            Set<String> target = resolved.get(entry.getKey());
            for (String nestedId : entry.getValue().nested()) {
                Set<String> members = resolved.get(nestedId);
                if (members != null && members != target) {
                    target.addAll(members);
                }
            }
        }
        return resolved.size();
    }

    /**
     * 数据包路径 → 标签 id：
     * {@code c/tags/item/ingots/iron.json → c:ingots/iron}；
     * 方块标签保留 {@code block/} 前缀避免与物品标签冲突。
     */
    public static String normalize(String rel) {
        String noExt = rel.endsWith(".json") ? rel.substring(0, rel.length() - 5) : rel;
        int tagsIdx = noExt.indexOf("/tags/");
        if (tagsIdx < 0) {
            return noExt.replace('/', ':');
        }
        String namespace = noExt.substring(0, tagsIdx);
        String rest = noExt.substring(tagsIdx + "/tags/".length());
        if (rest.startsWith("item/")) {
            rest = rest.substring("item/".length());
        } else if (rest.startsWith("items/")) {
            rest = rest.substring("items/".length());
        } else if (rest.startsWith("block/")) {
            rest = "block/" + rest.substring("block/".length());
        }
        return namespace + ":" + rest;
    }

    private Set<String> resolveVanilla(String id) {
        Set<String> out = new LinkedHashSet<>();
        int colon = id.indexOf(':');
        if (colon < 0) {
            return out;
        }
        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.fromString(namespace + ":" + path), Material.class);
        if (tag == null) {
            return out;
        }
        for (Material material : tag.getValues()) {
            out.add(namespace + ":" + material.getKey().getKey());
        }
        return out;
    }

    private static RawTag parseTag(InputStream in) {
        Set<String> entries = new LinkedHashSet<>();
        Set<String> nested = new LinkedHashSet<>();
        JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray values = root.has("values") ? root.getAsJsonArray("values") : new JsonArray();
        for (JsonElement element : values) {
            String id = null;
            if (element.isJsonPrimitive()) {
                id = element.getAsString();
            } else if (element.isJsonObject()) {
                JsonObject o = element.getAsJsonObject();
                if (o.has("id")) {
                    id = o.get("id").getAsString();
                }
            }
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (id.startsWith("#")) {
                nested.add(id.substring(1));
            } else {
                entries.add(id);
            }
        }
        return new RawTag(entries, nested);
    }

    /** 展开标签成员；未知标签回退到内置原版映射，仍未知返回空集。 */
    public Set<String> expand(String tagId) {
        Set<String> direct = resolved.get(tagId);
        if (direct != null) {
            return direct;
        }
        List<String> fallback = BUILTIN_TAGS.get(tagId);
        return fallback == null ? Set.of() : new LinkedHashSet<>(fallback);
    }

    public boolean knows(String tagId) {
        return resolved.containsKey(tagId) || BUILTIN_TAGS.containsKey(tagId);
    }

    public int tagCount() {
        return resolved.size();
    }

    /**
     * 内置标签回退（原版成员映射）。
     *
     * <p>NeoForge 生态中 {@code c:} 公共约定由模组数据包共同贡献；本工程运行于纯 Paper 环境，
     * 在此固化原版可对应的成员（跨模组成员如 {@code c:ingots/uranium} 无原版对应，保持缺失）。
     */
    private static final Map<String, List<String>> BUILTIN_TAGS = Map.ofEntries(
            // ---- c: 矿石与材料 ----
            Map.entry("c:gems/diamond", List.of("minecraft:diamond")),
            Map.entry("c:gems/emerald", List.of("minecraft:emerald")),
            Map.entry("c:gems/lapis", List.of("minecraft:lapis_lazuli")),
            Map.entry("c:gems/quartz", List.of("minecraft:quartz")),
            Map.entry("c:gems/amethyst", List.of("minecraft:amethyst_shard")),
            Map.entry("c:ingots/iron", List.of("minecraft:iron_ingot")),
            Map.entry("c:ingots/gold", List.of("minecraft:gold_ingot")),
            Map.entry("c:ingots/copper", List.of("minecraft:copper_ingot")),
            Map.entry("c:dusts/glowstone", List.of("minecraft:glowstone_dust")),
            Map.entry("c:dusts/redstone", List.of("minecraft:redstone")),
            Map.entry("c:rods/wooden", List.of("minecraft:stick")),
            Map.entry("c:rods/blaze", List.of("minecraft:blaze_rod")),
            Map.entry("c:rods/breeze", List.of("minecraft:breeze_rod")),
            Map.entry("c:strings", List.of("minecraft:string")),
            Map.entry("c:feathers", List.of("minecraft:feather")),
            Map.entry("c:nether_stars", List.of("minecraft:nether_star")),
            Map.entry("c:storage_blocks/diamond", List.of("minecraft:diamond_block")),
            Map.entry("c:storage_blocks/iron", List.of("minecraft:iron_block")),
            Map.entry("c:storage_blocks/gold", List.of("minecraft:gold_block")),
            Map.entry("c:chests/wooden", List.of("minecraft:chest")),
            Map.entry("c:stones", List.of("minecraft:stone")),
            Map.entry("c:cobblestones/normal", List.of("minecraft:cobblestone")),
            Map.entry("c:obsidians/normal", List.of("minecraft:obsidian")),
            Map.entry("c:seeds/wheat", List.of("minecraft:wheat_seeds")),
            Map.entry("c:seeds/beetroot", List.of("minecraft:beetroot_seeds")),
            Map.entry("c:crops/wheat", List.of("minecraft:wheat")),
            Map.entry("c:crops/carrot", List.of("minecraft:carrot")),
            Map.entry("c:crops/potato", List.of("minecraft:potato")),
            Map.entry("c:crops/beetroot", List.of("minecraft:beetroot")),
            Map.entry("c:crops/nether_wart", List.of("minecraft:nether_wart")),
            Map.entry("c:pumpkins/normal", List.of("minecraft:pumpkin")),
            // ---- c: 染料 ----
            Map.entry("c:dyes/black", List.of("minecraft:black_dye")),
            Map.entry("c:dyes/blue", List.of("minecraft:blue_dye")),
            Map.entry("c:dyes/brown", List.of("minecraft:brown_dye")),
            Map.entry("c:dyes/cyan", List.of("minecraft:cyan_dye")),
            Map.entry("c:dyes/gray", List.of("minecraft:gray_dye")),
            Map.entry("c:dyes/green", List.of("minecraft:green_dye")),
            Map.entry("c:dyes/light_blue", List.of("minecraft:light_blue_dye")),
            Map.entry("c:dyes/light_gray", List.of("minecraft:light_gray_dye")),
            Map.entry("c:dyes/lime", List.of("minecraft:lime_dye")),
            Map.entry("c:dyes/magenta", List.of("minecraft:magenta_dye")),
            Map.entry("c:dyes/orange", List.of("minecraft:orange_dye")),
            Map.entry("c:dyes/pink", List.of("minecraft:pink_dye")),
            Map.entry("c:dyes/purple", List.of("minecraft:purple_dye")),
            Map.entry("c:dyes/red", List.of("minecraft:red_dye")),
            Map.entry("c:dyes/white", List.of("minecraft:white_dye")),
            Map.entry("c:dyes/yellow", List.of("minecraft:yellow_dye")),
            // ---- minecraft: 原版标签（服务器未提供 TagRegistry 数据时的回退） ----
            Map.entry("minecraft:planks", List.of(
                    "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
                    "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks",
                    "minecraft:mangrove_planks", "minecraft:cherry_planks", "minecraft:bamboo_planks",
                    "minecraft:crimson_planks", "minecraft:warped_planks")),
            Map.entry("minecraft:logs", List.of(
                    "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
                    "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
                    "minecraft:mangrove_log", "minecraft:cherry_log")),
            Map.entry("minecraft:saplings", List.of(
                    "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling",
                    "minecraft:jungle_sapling", "minecraft:acacia_sapling", "minecraft:dark_oak_sapling",
                    "minecraft:cherry_sapling", "minecraft:mangrove_propagule")),
            Map.entry("minecraft:leaves", List.of(
                    "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
                    "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves",
                    "minecraft:cherry_leaves", "minecraft:mangrove_leaves")),
            Map.entry("minecraft:wool", List.of(
                    "minecraft:white_wool", "minecraft:orange_wool", "minecraft:magenta_wool",
                    "minecraft:light_blue_wool", "minecraft:yellow_wool", "minecraft:lime_wool",
                    "minecraft:pink_wool", "minecraft:gray_wool", "minecraft:light_gray_wool",
                    "minecraft:cyan_wool", "minecraft:purple_wool", "minecraft:blue_wool",
                    "minecraft:brown_wool", "minecraft:green_wool", "minecraft:red_wool",
                    "minecraft:black_wool")),
            Map.entry("minecraft:small_flowers", List.of(
                    "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
                    "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip",
                    "minecraft:orange_tulip", "minecraft:white_tulip", "minecraft:pink_tulip",
                    "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley")),
            Map.entry("minecraft:tall_flowers", List.of(
                    "minecraft:sunflower", "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony")),
            Map.entry("minecraft:flowers", List.of(
                    "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
                    "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip",
                    "minecraft:orange_tulip", "minecraft:white_tulip", "minecraft:pink_tulip",
                    "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
                    "minecraft:sunflower", "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony")),
            Map.entry("minecraft:decorated_pot_sherds", List.of(
                    "minecraft:angler_pottery_sherd", "minecraft:archer_pottery_sherd",
                    "minecraft:arms_up_pottery_sherd", "minecraft:blade_pottery_sherd",
                    "minecraft:brewer_pottery_sherd", "minecraft:burn_pottery_sherd",
                    "minecraft:danger_pottery_sherd", "minecraft:explorer_pottery_sherd",
                    "minecraft:friend_pottery_sherd", "minecraft:heart_pottery_sherd",
                    "minecraft:heartbreak_pottery_sherd", "minecraft:howl_pottery_sherd",
                    "minecraft:miner_pottery_sherd", "minecraft:mourner_pottery_sherd",
                    "minecraft:plenty_pottery_sherd", "minecraft:prize_pottery_sherd",
                    "minecraft:sheaf_pottery_sherd", "minecraft:shelter_pottery_sherd",
                    "minecraft:skull_pottery_sherd", "minecraft:snort_pottery_sherd")),
            Map.entry("minecraft:creeper_drop_music_discs", List.of(
                    "minecraft:music_disc_13", "minecraft:music_disc_cat", "minecraft:music_disc_blocks",
                    "minecraft:music_disc_chirp", "minecraft:music_disc_far", "minecraft:music_disc_mall",
                    "minecraft:music_disc_mellohi", "minecraft:music_disc_stal", "minecraft:music_disc_strad",
                    "minecraft:music_disc_ward", "minecraft:music_disc_11", "minecraft:music_disc_wait")),
            Map.entry("minecraft:base_stone_overworld", List.of("minecraft:stone")),
            Map.entry("minecraft:dirt", List.of("minecraft:dirt")),
            Map.entry("minecraft:sand", List.of("minecraft:sand")));

    public List<String> tagIds() {
        return List.copyOf(resolved.keySet());
    }

    /** 汇总说明（启动日志用）。 */
    public String describe() {
        int members = 0;
        for (Set<String> set : resolved.values()) {
            members += set.size();
        }
        return String.format("标签 %d 个 / 成员引用 %d 条", resolved.size(), members);
    }
}
