package com.paperize.emc;

import java.util.List;

/**
 * EMC 转换：一组原料（物品/标签 + 数量）产出 1 种结果。
 *
 * <p>{@code outputCount} 为本次转换产物数量（合成一次得到几个）；
 * 求解时按 {@code ceil(总原料价值 / outputCount)} 归一。
 */
public record EmcConversion(String output, long outputCount, List<Ingredient> ingredients) {

    /** 单个原料：{@code target} 为 {@code namespace:path} 物品或 {@code #namespace:path} 标签。 */
    public record Ingredient(String target, long amount) {

        public boolean isTag() {
            return target.startsWith("#");
        }

        public String key() {
            return isTag() ? target.substring(1) : target;
        }
    }

    public static Ingredient item(String id, long amount) {
        return new Ingredient(id, amount);
    }

    public static Ingredient tag(String id, long amount) {
        return new Ingredient("#" + id, amount);
    }

    /** 转换合并键：同一输出 + 同一原料集视为重复，保留先者。 */
    public String dedupeKey() {
        StringBuilder sb = new StringBuilder(output).append('x').append(outputCount).append('|');
        ingredients.stream()
                .sorted((a, b) -> a.target().compareTo(b.target()))
                .forEach(i -> sb.append(i.target()).append(':').append(i.amount()).append(','));
        return sb.toString();
    }
}
