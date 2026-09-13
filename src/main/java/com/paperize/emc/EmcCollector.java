package com.paperize.emc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMC 图收集器：聚合固定值与转换关系，供 {@link EmcGraphMapper} 求解。
 *
 * <p>对齐 ProjectE 的 {@code MappingCollector} 语义：
 * <ul>
 *   <li>{@code values.before}：在配方传播前设置的固定值（优先级最高，不被配方覆盖）</li>
 *   <li>{@code values.after}：在传播完成后覆盖（用于强制指定终值）</li>
 *   <li>转换：结果 ← 原料集合（含数量）</li>
 * </ul>
 */
public final class EmcCollector {

    private final Map<String, Long> fixedBefore = new LinkedHashMap<>();
    private final Map<String, Long> fixedAfter = new LinkedHashMap<>();
    private final Map<String, List<EmcConversion>> conversionsFor = new LinkedHashMap<>();
    private final Set<String> dedupe = new LinkedHashSet<>();
    private final Set<String> touched = new LinkedHashSet<>();

    public void setValueBefore(String id, long value) {
        fixedBefore.put(id, value);
        touched.add(id);
    }

    public void setValueAfter(String id, long value) {
        fixedAfter.put(id, value);
        touched.add(id);
    }

    public boolean hasFixedBefore(String id) {
        return fixedBefore.containsKey(id);
    }

    public void addConversion(EmcConversion conversion) {
        if (conversion.outputCount() <= 0 || conversion.ingredients().isEmpty()) {
            return;
        }
        if (!dedupe.add(conversion.dedupeKey())) {
            return;
        }
        conversionsFor.computeIfAbsent(conversion.output(), k -> new ArrayList<>()).add(conversion);
        touched.add(conversion.output());
        for (EmcConversion.Ingredient ingredient : conversion.ingredients()) {
            if (!ingredient.isTag()) {
                touched.add(ingredient.key());
            }
        }
    }

    public Map<String, Long> fixedBefore() {
        return fixedBefore;
    }

    public Map<String, Long> fixedAfter() {
        return fixedAfter;
    }

    public Map<String, List<EmcConversion>> conversionsFor() {
        return conversionsFor;
    }

    /** 已触碰的 id 集合（含仅作为原料出现的物品）。 */
    public Set<String> touched() {
        return touched;
    }

    public int conversionCount() {
        int total = 0;
        for (List<EmcConversion> list : conversionsFor.values()) {
            total += list.size();
        }
        return total;
    }

    /** 反向索引：原料 → 使用它的转换（松弛求解用）。 */
    public Map<String, List<EmcConversion>> usedIn() {
        Map<String, List<EmcConversion>> usedIn = new LinkedHashMap<>();
        for (List<EmcConversion> list : conversionsFor.values()) {
            for (EmcConversion conversion : list) {
                for (EmcConversion.Ingredient ingredient : conversion.ingredients()) {
                    usedIn.computeIfAbsent(ingredient.target(), k -> new ArrayList<>()).add(conversion);
                }
            }
        }
        return usedIn;
    }
}
