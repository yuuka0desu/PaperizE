package com.paperize.emc;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家炼金数据：转换知识（已学习物品）+ 个人 EMC 余额。
 *
 * <p>对齐 ProjectE 的 {@code IKnowledgeProvider / IAlchBagProvider}：
 * 学习过的物品可在转换台取出；余额为转换台内燃烧/存入的 EMC（点数为单位）。
 */
public final class PePlayerData {

    private final UUID playerId;
    /** 已学习物品 id（有序，便于稳定展示）。 */
    private final Set<String> knowledge = new LinkedHashSet<>();
    /** 个人 EMC 余额。 */
    private long emc;

    public PePlayerData(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    public Set<String> knowledge() {
        return knowledge;
    }

    public boolean knows(String id) {
        return knowledge.contains(id);
    }

    /** 学习物品；返回 true 表示新学会。 */
    public boolean learn(String id) {
        return knowledge.add(id);
    }

    public boolean forget(String id) {
        return knowledge.remove(id);
    }

    public void clearKnowledge() {
        knowledge.clear();
    }

    public long emc() {
        return emc;
    }

    public void setEmc(long value) {
        this.emc = Math.max(0, value);
    }

    /** 增加余额；返回实际增加量（溢出保护）。 */
    public long addEmc(long amount) {
        if (amount <= 0) {
            return 0;
        }
        long before = emc;
        emc = emc > Long.MAX_VALUE - amount ? Long.MAX_VALUE : emc + amount;
        return emc - before;
    }

    /** 支出余额；返回是否成功（余额不足不扣减）。 */
    public boolean spendEmc(long amount) {
        if (amount <= 0) {
            return true;
        }
        if (emc < amount) {
            return false;
        }
        emc -= amount;
        return true;
    }

    public int knowledgeCount() {
        return knowledge.size();
    }
}
