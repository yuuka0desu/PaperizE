package com.paperize.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 机器状态仓库：内存映射 + JSON 落盘（ItemStack 走字节序列化，Base64 承载）。
 *
 * <p>按位置索引（{@code world@x,y,z}），随方块放置/破坏增删；周期落盘与关停落盘双保险。
 */
public final class MachineStore {

    private final Map<String, MachineState> byKey = new LinkedHashMap<>();
    private final Path file;
    private final Logger log;

    public MachineStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public MachineState get(String world, int x, int y, int z) {
        return byKey.get(world + "@" + x + "," + y + "," + z);
    }

    public MachineState getOrCreate(String world, int x, int y, int z, MachineState.Type type) {
        String key = world + "@" + x + "," + y + "," + z;
        MachineState existing = byKey.get(key);
        if (existing != null) {
            return existing;
        }
        MachineState created = new MachineState(world, x, y, z, type);
        byKey.put(key, created);
        return created;
    }

    public MachineState remove(String world, int x, int y, int z) {
        return byKey.remove(world + "@" + x + "," + y + "," + z);
    }

    public Collection<MachineState> all() {
        return byKey.values();
    }

    public int size() {
        return byKey.size();
    }

    public List<MachineState> byType(MachineState.Type type) {
        List<MachineState> out = new ArrayList<>();
        for (MachineState state : byKey.values()) {
            if (state.type() == type) {
                out.add(state);
            }
        }
        return out;
    }

    // ---- 持久化 ----

    public synchronized void save() {
        JsonObject root = new JsonObject();
        JsonArray machines = new JsonArray();
        for (MachineState state : byKey.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("world", state.world());
            o.addProperty("x", state.x());
            o.addProperty("y", state.y());
            o.addProperty("z", state.z());
            o.addProperty("type", state.type().name());
            o.addProperty("emc", state.emc());
            if (state.type().condenser()) {
                o.addProperty("work", state.work());
                if (state.targetId() != null) {
                    o.addProperty("target", state.targetId());
                }
            }
            JsonArray slots = new JsonArray();
            ItemStack[] array = state.slots();
            for (ItemStack stack : array) {
                slots.add(encode(stack));
            }
            o.add("slots", slots);
            machines.add(o);
        }
        root.add("machines", machines);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("机器状态落盘失败: " + e.getMessage());
        }
    }

    public synchronized void load() {
        byKey.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("machines")) {
                return;
            }
            for (JsonElement element : root.getAsJsonArray("machines")) {
                JsonObject o = element.getAsJsonObject();
                MachineState.Type type;
                try {
                    type = MachineState.Type.valueOf(o.get("type").getAsString());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                MachineState state = new MachineState(
                        o.get("world").getAsString(),
                        o.get("x").getAsInt(),
                        o.get("y").getAsInt(),
                        o.get("z").getAsInt(),
                        type);
                state.setEmc(o.has("emc") ? o.get("emc").getAsLong() : 0);
                if (type.condenser()) {
                    state.setWork(o.has("work") ? o.get("work").getAsLong() : 0);
                    if (o.has("target")) {
                        state.setTargetId(o.get("target").getAsString());
                    }
                }
                ItemStack[] slots = new ItemStack[type.slots()];
                if (o.has("slots")) {
                    JsonArray array = o.getAsJsonArray("slots");
                    for (int i = 0; i < Math.min(array.size(), slots.length); i++) {
                        slots[i] = decode(array.get(i).getAsString());
                    }
                }
                state.setSlots(slots);
                byKey.put(state.key(), state);
            }
            log.info("机器状态已装载：" + byKey.size() + " 台");
        } catch (Exception e) {
            log.warning("机器状态装载失败: " + e.getMessage());
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
}
