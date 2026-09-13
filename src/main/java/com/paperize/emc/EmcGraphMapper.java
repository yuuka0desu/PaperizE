package com.paperize.emc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EMC 图求解器：从固定值出发，沿转换关系做全图迭代松弛，直到不动点。
 *
 * <p>对齐 ProjectE {@code SimpleGraphMapper} 语义：
 * <ol>
 *   <li>{@code values.before} 为初始固定值（配方不可覆盖：基础价值是体系锚点）；</li>
 *   <li>反复扫描全部转换：原料齐全时计算 {@code ceil(Σ 原料价值 / 产物数量)}，
 *       新值更小才更新（EMC = 最小可得成本，天然防循环套利）；</li>
 *   <li>一轮无更新即收敛；</li>
 *   <li>最后用 {@code values.after} 覆盖。</li>
 * </ol>
 * 全图松弛（而非一次定型）是必要的：某物品价值下降后，其下游产物可能继续下降，
 * 需要多轮传播才能达到最小不动点。
 */
public final class EmcGraphMapper {

    /** 迭代上限（本项目规模下数十轮内收敛；上限防御病态配方环）。 */
    private static final int MAX_ROUNDS = 400;

    private EmcGraphMapper() {
    }

    public record Result(Map<String, Long> values, int rounds, boolean converged) {
    }

    public static Result solve(EmcCollector collector) {
        Map<String, Long> values = new LinkedHashMap<>(collector.fixedBefore());
        Map<String, List<EmcConversion>> conversionsFor = collector.conversionsFor();

        int rounds = 0;
        boolean changed = true;
        while (changed && rounds < MAX_ROUNDS) {
            rounds++;
            changed = false;
            for (Map.Entry<String, List<EmcConversion>> entry : conversionsFor.entrySet()) {
                String output = entry.getKey();
                if (collector.hasFixedBefore(output)) {
                    continue; // 固定值优先，不被配方覆盖
                }
                long best = values.getOrDefault(output, Long.MAX_VALUE);
                for (EmcConversion conversion : entry.getValue()) {
                    long total;
                    try {
                        total = ingredientCost(conversion, values);
                    } catch (MissingValueException missing) {
                        continue; // 原料未知：本条暂不可用
                    }
                    long normalized = ceilDiv(total, conversion.outputCount());
                    if (normalized < best) {
                        best = normalized;
                    }
                }
                if (best != Long.MAX_VALUE && best < values.getOrDefault(output, Long.MAX_VALUE)) {
                    values.put(output, best);
                    changed = true;
                }
            }
        }
        boolean converged = !changed;

        for (Map.Entry<String, Long> entry : collector.fixedAfter().entrySet()) {
            values.put(entry.getKey(), entry.getValue());
        }
        return new Result(values, rounds, converged);
    }

    private static long ingredientCost(EmcConversion conversion, Map<String, Long> values)
            throws MissingValueException {
        long total = 0;
        for (EmcConversion.Ingredient ingredient : conversion.ingredients()) {
            Long value = values.get(ingredient.key());
            if (value == null) {
                throw new MissingValueException(ingredient.key());
            }
            long add = value * ingredient.amount();
            total = add < 0 || total > Long.MAX_VALUE - add ? Long.MAX_VALUE : total + add;
        }
        return total;
    }

    private static long ceilDiv(long total, long divisor) {
        return (total + divisor - 1) / divisor;
    }

    private static final class MissingValueException extends Exception {
        MissingValueException(String id) {
            super(id);
        }
    }
}
