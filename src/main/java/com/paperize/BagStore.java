package com.paperize;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 炼金袋存储：{@code 玩家UUID:袋子名} → 54 格物品（Base64 序列化落盘）。
 *
 * <p>对齐 ProjectE 的 {@code IAlchBagProvider}：袋子内容随玩家独立保存。
 */
public final class BagStore {

    private static final int SIZE = 54;

    private final Map<String, ItemStack[]> bags = new ConcurrentHashMap<>();
    private final Path file;
    private final Logger log;

    public BagStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public ItemStack[] get(String key) {
        return bags.get(key);
    }

    public void put(String key, ItemStack[] slots) {
        bags.put(key, slots);
    }

    public int count() {
        return bags.size();
    }

    public synchronized void save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, ItemStack[]> entry : bags.entrySet()) {
            JsonArray array = new JsonArray();
            for (ItemStack stack : entry.getValue()) {
                array.add(encode(stack));
            }
            root.add(entry.getKey(), array);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("炼金袋落盘失败: " + e.getMessage());
        }
    }

    public synchronized void load() {
        bags.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonArray array = entry.getValue().getAsJsonArray();
                ItemStack[] slots = new ItemStack[SIZE];
                for (int i = 0; i < Math.min(array.size(), SIZE); i++) {
                    slots[i] = decode(array.get(i).getAsString());
                }
                bags.put(entry.getKey(), slots);
            }
            log.info("炼金袋装载：" + bags.size() + " 个");
        } catch (Exception e) {
            log.warning("炼金袋装载失败: " + e.getMessage());
        }
    }

    private static String encode(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "";
        }
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    private static ItemStack decode(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 摘要（命令用）。 */
    public Map<String, Integer> summary() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, ItemStack[]> entry : bags.entrySet()) {
            int used = 0;
            for (ItemStack stack : entry.getValue()) {
                if (stack != null && !stack.getType().isAir()) {
                    used++;
                }
            }
            out.put(entry.getKey(), used);
        }
        return out;
    }
}
