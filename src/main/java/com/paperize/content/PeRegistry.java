package com.paperize.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paperize.PeContentProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容注册表：ProjectE 物品/方块清单（由 {@code content/generated/*.json} 提供）。
 *
 * <p>清单由 {@code tools/extract_content.py} 从模组 jar 生成：
 * 物品 = lang 键 ∩ 模型存在；方块 = lang 键 ∩ 方块状态存在。
 */
public final class PeRegistry {

    /** 物品条目（material 为底材：工具/护甲用原版底材以继承可用行为）。 */
    public record ItemEntry(String id, String nameKey, String model, String material, String zhName) {
        public String simple() {
            int colon = id.indexOf(':');
            return colon < 0 ? id : id.substring(colon + 1);
        }
    }

    /** 方块条目。 */
    public record BlockEntry(String id, String nameKey, String model, List<String> blockstates,
                             double hardness, double resistance, String orientation, int light,
                             String explosive, String zhName) {
        public String simple() {
            int colon = id.indexOf(':');
            return colon < 0 ? id : id.substring(colon + 1);
        }

        public boolean hasFacing() {
            return "facing".equals(orientation) || "any_facing".equals(orientation);
        }

        public boolean anyFacing() {
            return "any_facing".equals(orientation);
        }

        public boolean torch() {
            return "torch".equals(orientation);
        }
    }

    private final Map<String, ItemEntry> items;
    private final Map<String, BlockEntry> blocks;

    private PeRegistry(Map<String, ItemEntry> items, Map<String, BlockEntry> blocks) {
        this.items = items;
        this.blocks = blocks;
    }

    public Map<String, ItemEntry> items() {
        return items;
    }

    public Map<String, BlockEntry> blocks() {
        return blocks;
    }

    public ItemEntry item(String simple) {
        return items.get(simple);
    }

    public BlockEntry block(String simple) {
        return blocks.get(simple);
    }

    public static PeRegistry load(PeContentProvider provider) throws IOException {
        Map<String, ItemEntry> items = new LinkedHashMap<>();
        Map<String, BlockEntry> blocks = new LinkedHashMap<>();

        try (InputStream in = provider.openRegistry("items")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    JsonObject o = entry.getValue().getAsJsonObject();
                    items.put(entry.getKey(), new ItemEntry(
                            str(o, "id", "projecte:" + entry.getKey()),
                            str(o, "nameKey", "item.projecte." + entry.getKey()),
                            str(o, "model", "projecte:item/" + entry.getKey()),
                            str(o, "material", "paper"),
                            optStr(o, "zhName")));
                }
            }
        }

        try (InputStream in = provider.openRegistry("blocks")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    JsonObject o = entry.getValue().getAsJsonObject();
                    List<String> states = new ArrayList<>();
                    if (o.has("blockstates")) {
                        for (JsonElement e : o.getAsJsonArray("blockstates")) {
                            states.add(e.getAsString());
                        }
                    }
                    blocks.put(entry.getKey(), new BlockEntry(
                            str(o, "id", "projecte:" + entry.getKey()),
                            str(o, "nameKey", "block.projecte." + entry.getKey()),
                            str(o, "model", "projecte:block/" + entry.getKey()),
                            states,
                            o.has("hardness") ? o.get("hardness").getAsDouble() : 2.0,
                            o.has("resistance") ? o.get("resistance").getAsDouble() : 6.0,
                            optStr(o, "orientation"),
                            o.has("light") ? o.get("light").getAsInt() : 0,
                            optStr(o, "explosive"),
                            optStr(o, "zhName")));
                }
            }
        }

        return new PeRegistry(items, blocks);
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static String optStr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && !o.get(key).isJsonNull()
                ? o.get(key).getAsString() : null;
    }
}
