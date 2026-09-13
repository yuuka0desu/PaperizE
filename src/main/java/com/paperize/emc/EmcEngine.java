package com.paperize.emc;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时 EMC 引擎：求解结果的查询面 + 物品 id 解析 + 物品级 EMC 存储。
 *
 * <p>物品 id 解析顺序：CraftEngine 自定义物品 → 原版材质。物品上的 EMC 存储
 * （克莱因之星等）通过持久化数据容器 {@code paperize:stored_emc} 承载。
 */
public final class EmcEngine {

    /** 物品持久化键（克莱因之星存储量 / 便携 EMC）。 */
    public static final String STORED_EMC_KEY = "stored_emc";

    private final NamespacedKey storedEmcKey;
    private final Map<String, Long> values = new ConcurrentHashMap<>();
    private volatile List<String> sortedIds = List.of();

    public EmcEngine(Plugin plugin) {
        this.storedEmcKey = NamespacedKey.fromString(plugin.getName().toLowerCase() + ":" + STORED_EMC_KEY);
    }

    /** 装载求解结果。 */
    public void install(Map<String, Long> solved) {
        values.clear();
        values.putAll(solved);
        List<String> ids = new ArrayList<>(solved.keySet());
        ids.sort(Comparator
                .comparingLong((String id) -> solved.getOrDefault(id, 0L))
                .thenComparing(id -> id));
        sortedIds = List.copyOf(ids);
    }

    public boolean hasValue(String id) {
        return id != null && values.containsKey(id);
    }

    public long emcOf(String id) {
        if (id == null) {
            return 0;
        }
        return values.getOrDefault(id, 0L);
    }

    public long emcOf(ItemStack stack) {
        return emcOf(idOf(stack));
    }

    /** 物品 → 内容 id（CraftEngine 自定义物品优先，否则原版材质）。 */
    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        try {
            Key key = CraftEngineItems.getCustomItemId(stack);
            if (key != null) {
                return key.namespace() + ":" + key.value();
            }
        } catch (Throwable ignored) {
            // CraftEngine 未就绪：回退原版 id
        }
        return "minecraft:" + stack.getType().getKey().getKey();
    }

    /** 已求值的物品 id 列表（按 EMC 升序）。 */
    public List<String> knownIds() {
        return sortedIds;
    }

    /** 已求值物品清单（含展示名）：转换台/命令消费。 */
    public List<Map.Entry<String, Long>> knownEntries() {
        List<Map.Entry<String, Long>> out = new ArrayList<>();
        for (String id : sortedIds) {
            out.add(Map.entry(id, values.getOrDefault(id, 0L)));
        }
        return out;
    }

    /** 带值物品数。 */
    public int valueCount() {
        return values.size();
    }

    /** 值区间摘要（启动日志）。 */
    public String describe() {
        if (values.isEmpty()) {
            return "EMC 表为空";
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long v : values.values()) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return String.format("EMC 表：%d 项（%d ~ %d）", values.size(), min, max);
    }

    // ---- 物品级 EMC 存储（克莱因之星 / 便携单元） ----

    public long storedEmc(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null) {
            return 0;
        }
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        Long value = pdc.get(storedEmcKey, PersistentDataType.LONG);
        return value == null ? 0 : value;
    }

    public boolean hasStoredEmc(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(storedEmcKey, PersistentDataType.LONG);
    }

    /** 写入物品存储量；返回是否成功（需要 ItemMeta 可写）。 */
    public boolean setStoredEmc(ItemStack stack, long value) {
        if (stack == null || stack.getItemMeta() == null) {
            return false;
        }
        var meta = stack.getItemMeta();
        if (value <= 0) {
            meta.getPersistentDataContainer().remove(storedEmcKey);
        } else {
            meta.getPersistentDataContainer().set(storedEmcKey, PersistentDataType.LONG, value);
        }
        stack.setItemMeta(meta);
        return true;
    }

    public NamespacedKey storedEmcKey() {
        return storedEmcKey;
    }

    /** 已知值快照（求解结果落盘用）。 */
    public Map<String, Long> snapshot() {
        return new LinkedHashMap<>(values);
    }
}
