package com.paperize.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paperize.PeContentProvider;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Slab;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 世界转换表：哲学者之石右键方块 → 目标方块（Shift 变体走 alt_result）。
 *
 * <p>数据源 {@code content/transmutations/*.json}（pe_world_transmutations）：
 * {@code {"transmutations":[{"origin":"minecraft:stone","result":"minecraft:cobblestone",
 * "alt_result":"minecraft:grass_block"}]}}。
 * origin/result 支持字符串 id 或带 {@code Properties} 的复合形态（属性在应用时尽力保留）。
 */
public final class PeWorldTransmutation {

    /** 单条转换：origin → result（alt 为 Shift 变体，可为 null）。 */
    public record Entry(String origin, String result, String altResult) {
    }

    private final Map<String, Entry> byOrigin = new LinkedHashMap<>();

    public Entry get(String originId) {
        return byOrigin.get(originId);
    }

    public int size() {
        return byOrigin.size();
    }

    public Map<String, Entry> entries() {
        return byOrigin;
    }

    /** 从内容提供者装载全部世界转换文件。 */
    public static PeWorldTransmutation load(PeContentProvider provider, Logger log) {
        PeWorldTransmutation table = new PeWorldTransmutation();
        for (String file : List.of("defaults.json", "colors.json", "oxidization.json", "wood.json")) {
            try (InputStream in = provider.openTransmutation(file)) {
                if (in == null) {
                    continue;
                }
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (!root.has("transmutations")) {
                    continue;
                }
                JsonArray array = root.getAsJsonArray("transmutations");
                for (JsonElement element : array) {
                    JsonObject o = element.getAsJsonObject();
                    String origin = readId(o.get("origin"));
                    String result = readId(o.get("result"));
                    String alt = o.has("alt_result") ? readId(o.get("alt_result")) : null;
                    if (origin == null || result == null) {
                        continue;
                    }
                    table.byOrigin.put(origin, new Entry(origin, result, alt));
                }
            } catch (IOException e) {
                log.warning("世界转换装载失败 " + file + ": " + e.getMessage());
            }
        }
        return table;
    }

    /** 读取条目 id：字符串形态直接取；复合形态取 {@code Name} 字段。 */
    private static String readId(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        JsonObject o = element.getAsJsonObject();
        if (o.has("Name")) {
            return o.get("Name").getAsString();
        }
        if (o.has("id")) {
            return o.get("id").getAsString();
        }
        return null;
    }

    /** 解析方块 → 内容 id（{@code namespace:path}，原版命名空间为 minecraft）。 */
    public static String blockId(Block block) {
        Material type = block.getType();
        return "minecraft:" + type.getKey().getKey();
    }

    public boolean canTransmute(String originId) {
        return byOrigin.containsKey(originId);
    }

    /**
     * 应用转换：把 origin 方块替换为 result/alt（尽量保留可迁移的方块数据，如水/分层）。
     * 返回是否发生替换。
     */
    public boolean apply(Block block, boolean alt) {
        Entry entry = byOrigin.get(blockId(block));
        if (entry == null) {
            return false;
        }
        String targetId = alt && entry.altResult() != null ? entry.altResult() : entry.result();
        Material target = Material.matchMaterial(targetId);
        if (target == null || target.isAir()) {
            return false;
        }
        BlockData previous = block.getBlockData();
        BlockData data = target.createBlockData();
        // 可迁移属性：分层（level/type）与轴向
        if (data instanceof Levelled levelled && previous instanceof Levelled prevLevelled) {
            levelled.setLevel(Math.min(prevLevelled.getLevel(), levelled.getMaximumLevel()));
        }
        if (data instanceof Slab slab && previous instanceof Slab prevSlab) {
            slab.setType(prevSlab.getType());
        }
        if (data instanceof Orientable orientable && previous instanceof Orientable prevOrientable) {
            orientable.setAxis(prevOrientable.getAxis());
        }
        block.setBlockData(data, true);
        return true;
    }

    /** 说明字符串（启动日志）。 */
    public String describe() {
        List<String> samples = new ArrayList<>();
        int i = 0;
        for (Entry entry : byOrigin.values()) {
            if (i++ >= 3) {
                break;
            }
            samples.add(entry.origin() + "→" + entry.result());
        }
        return String.format("世界转换 %d 条（%s…）", byOrigin.size(), String.join(", ", samples));
    }
}
