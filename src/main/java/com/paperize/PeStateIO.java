package com.paperize;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paperize.emc.PePlayerData;
import com.paperize.machine.MachineStore;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 状态持久化：玩家炼金数据（知识/余额）+ 机器状态（委托 {@link MachineStore}）。
 *
 * <p>落盘策略：周期落盘（主类调度）+ 关停落盘双保险；玩家数据按 UUID 索引。
 */
public final class PeStateIO {

    private final Path playerFile;
    private final MachineStore machines;
    private final Map<UUID, PePlayerData> players = new ConcurrentHashMap<>();
    private final Logger log;

    public PeStateIO(Path playerFile, MachineStore machines, Logger log) {
        this.playerFile = playerFile;
        this.machines = machines;
        this.log = log;
    }

    /** 取（或建立）玩家数据。 */
    public PePlayerData player(Player player) {
        return players.computeIfAbsent(player.getUniqueId(), PePlayerData::new);
    }

    public PePlayerData player(UUID id) {
        return players.computeIfAbsent(id, PePlayerData::new);
    }

    public Map<UUID, PePlayerData> allPlayers() {
        return players;
    }

    public MachineStore machines() {
        return machines;
    }

    public synchronized void save() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (PePlayerData data : players.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", data.playerId().toString());
            o.addProperty("emc", data.emc());
            JsonArray knowledge = new JsonArray();
            data.knowledge().forEach(knowledge::add);
            o.add("knowledge", knowledge);
            array.add(o);
        }
        root.add("players", array);
        try {
            Files.createDirectories(playerFile.getParent());
            Files.writeString(playerFile, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("玩家数据落盘失败: " + e.getMessage());
        }
        machines.save();
    }

    public synchronized void load() {
        players.clear();
        machines.load();
        if (!Files.isRegularFile(playerFile)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(playerFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!root.has("players")) {
                return;
            }
            for (JsonElement element : root.getAsJsonArray("players")) {
                JsonObject o = element.getAsJsonObject();
                UUID id;
                try {
                    id = UUID.fromString(o.get("uuid").getAsString());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                PePlayerData data = new PePlayerData(id);
                data.setEmc(o.has("emc") ? o.get("emc").getAsLong() : 0);
                if (o.has("knowledge")) {
                    for (JsonElement k : o.getAsJsonArray("knowledge")) {
                        data.learn(k.getAsString());
                    }
                }
                players.put(id, data);
            }
            log.info("玩家炼金数据已装载：" + players.size() + " 名");
        } catch (Exception e) {
            log.warning("玩家数据装载失败: " + e.getMessage());
        }
    }

    /** 摘要（命令用）。 */
    public String describe() {
        int learned = 0;
        long emcTotal = 0;
        for (PePlayerData data : players.values()) {
            learned += data.knowledgeCount();
            emcTotal += data.emc();
        }
        return String.format("玩家 %d 名 / 知识条目 %d / 余额合计 %d / 机器 %d 台",
                players.size(), learned, emcTotal, machines.size());
    }
}
