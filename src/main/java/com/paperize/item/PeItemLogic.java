package com.paperize.item;

import com.paperize.PaperizEPlugin;
import com.paperize.emc.EmcEngine;
import com.paperize.emc.PePlayerData;
import com.paperize.machine.MachineState;
import com.paperize.machine.MachineStore;
import com.paperize.world.PeWorldTransmutation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;

/**
 * 物品行为分发：哲学者之石（世界转换/物品转化）、克莱因之星（EMC 存储）、
 * 炼金袋（学习）、转换平板（随身转换台）、探测棒（价值探查）等。
 *
 * <p>对齐 ProjectE 的 {@code IExtraFunction / IItemEmcHolder / IModeChanger} 语义的最小集。
 */
public final class PeItemLogic {

    private final PaperizEPlugin plugin;
    private final EmcEngine emc;
    private final MachineStore machines;
    private final PeWorldTransmutation worldTransmutation;

    public PeItemLogic(PaperizEPlugin plugin, EmcEngine emc, MachineStore machines,
                       PeWorldTransmutation worldTransmutation) {
        this.plugin = plugin;
        this.emc = emc;
        this.machines = machines;
        this.worldTransmutation = worldTransmutation;
    }

    /** 物品简单名（去命名空间）。 */
    private static String simple(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /**
     * 右键方块：哲学者之石世界转换；禁止火把照明等。
     *
     * @return true 表示已处理（调用方取消默认交互）
     */
    public boolean onBlockRightClick(Player player, ItemStack hand, Block block) {
        String id = emc.idOf(hand);
        if (id == null) {
            return false;
        }
        String simple = simple(id);
        if ("philosophers_stone".equals(simple)) {
            return transmuteWorld(player, block, player.isSneaking());
        }
        if ("divining_rod_1".equals(simple) || "divining_rod_2".equals(simple) || "divining_rod_3".equals(simple)) {
            return probeBlock(player, block);
        }
        return false;
    }

    /** 哲学者之石：世界转换（普通 → result，Shift → alt_result）。 */
    private boolean transmuteWorld(Player player, Block block, boolean alt) {
        String originId = PeWorldTransmutation.blockId(block);
        if (!worldTransmutation.canTransmute(originId)) {
            return false;
        }
        if (!worldTransmutation.apply(block, alt)) {
            return false;
        }
        player.getWorld().playSound(block.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.4f);
        return true;
    }

    /** 探测棒：报告方块与手持物品价值。 */
    private boolean probeBlock(Player player, Block block) {
        String id = "minecraft:" + block.getType().getKey().getKey();
        long value = emc.emcOf(id);
        if (value > 0) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    "EMC " + value + "（" + id + "）"));
        } else {
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    "无 EMC 值（" + id + "）"));
        }
        return true;
    }

    /**
     * 右键空气/潜行：克莱因之星存取、炼金袋学习、平板打开转换台、戒指效果。
     *
     * @return true 表示已处理
     */
    public boolean onUse(Player player, ItemStack hand, PePlayerData data) {
        String id = emc.idOf(hand);
        if (id == null) {
            return false;
        }
        String simple = simple(id);

        // 克莱因之星：Shift 右键存入余额（把星里的 EMC 转入个人余额）
        if (simple.startsWith("klein_star_")) {
            if (player.isSneaking()) {
                long stored = emc.storedEmc(hand);
                if (stored > 0) {
                    data.addEmc(stored);
                    emc.setStoredEmc(hand, 0);
                    player.sendMessage("已从克莱因之星取出 " + stored + " EMC（当前余额 " + data.emc() + "）");
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.6f);
                    return true;
                }
                player.sendMessage("克莱因之星为空（Shift+右键从个人余额存入）");
                return true;
            }
            long capacity = kleinStarCapacity(simple);
            long stored = emc.storedEmc(hand);
            if (stored <= 0 && data.emc() > 0) {
                long moved = Math.min(data.emc(), capacity);
                data.spendEmc(moved);
                emc.setStoredEmc(hand, moved);
                player.sendMessage("已存入 " + moved + " EMC（星内 " + moved + "/" + capacity + "）");
                return true;
            }
            player.sendMessage("克莱因之星：" + stored + " / " + capacity + " EMC（Shift+右键取出）");
            return true;
        }

        // 炼金袋：打开自身存储（v1：以机器状态承载 54 格）
        if (simple.endsWith("_alchemical_bag")) {
            plugin.openBag(player, simple, hand);
            return true;
        }

        // 转换平板：打开随身转换台
        if ("transmutation_tablet".equals(simple)) {
            plugin.openTransmutation(player);
            return true;
        }

        // 戒指 / 护符（v1 最小效果集）
        switch (simple) {
            case "harvest_goddess_band" -> {
                return harvestGoddess(player);
            }
            case "body_stone" -> {
                return bodyStone(player);
            }
            case "life_stone" -> {
                return lifeStone(player);
            }
            case "soul_stone" -> {
                return soulStone(player);
            }
            case "mind_stone" -> {
                return mindStone(player);
            }
            case "gem_of_eternal_density" -> {
                return eternalDensity(player, hand);
            }
            case "destruction_catalyst" -> {
                return destructionCatalyst(player);
            }
            case "evertide_amulet" -> {
                return evertideAmulet(player, player.isSneaking());
            }
            case "volcanite_amulet" -> {
                return volcaniteAmulet(player, player.isSneaking());
            }
            case "ignition_ring" -> {
                return ignitionRing(player, player.isSneaking());
            }
            case "zero_ring" -> {
                return zeroRing(player, player.isSneaking());
            }
            case "swiftwolf_rending_gale" -> {
                return swiftwolf(player);
            }
            case "archangel_smite" -> {
                return archangelSmite(player);
            }
            case "repair_talisman" -> {
                return repairTalisman(player);
            }
            case "watch_of_flowing_time" -> {
                return watchOfFlowingTime(player);
            }
            case "mercurial_eye" -> {
                return mercurialEye(player, player.isSneaking());
            }
            case "void_ring" -> {
                return voidRing(player);
            }
            case "arcana_ring" -> {
                return arcanaRing(player, false);
            }
            case "hyperkinetic_lens" -> {
                return hyperkineticLens(player);
            }
            case "catalytic_lens" -> {
                return catalyticLens(player);
            }
            case "black_hole_band" -> {
                return blackHoleBand(player);
            }
            case "tome" -> {
                plugin.openTransmutation(player);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void apply(Player player, PotionEffectType type, int duration, int amplifier) {
        player.addPotionEffect(new PotionEffect(type, duration, amplifier, true, false, true));
    }

    // ---- 功能性物品：宝石 / 护符 / 戒指 / 工具 ----

    /** 身之宝石：抗性提升。 */
    private boolean bodyStone(Player player) {
        apply(player, PotionEffectType.RESISTANCE, 400, 1);
        player.sendActionBar(net.kyori.adventure.text.Component.text("身之宝石：获得抗性提升"));
        return true;
    }

    /** 生命宝石：治疗生命。 */
    private boolean lifeStone(Player player) {
        double max = player.getMaxHealth();
        if (player.getHealth() >= max) {
            player.sendActionBar(net.kyori.adventure.text.Component.text("生命宝石：生命已满"));
            return true;
        }
        player.setHealth(Math.min(max, player.getHealth() + 10));
        player.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                player.getLocation().add(0, 2, 0), 5, 0.3, 0.3, 0.3);
        player.sendActionBar(net.kyori.adventure.text.Component.text("生命宝石：已治疗"));
        return true;
    }

    /** 灵魂宝石：排斥周围敌对生物。 */
    private boolean soulStone(Player player) {
        int repelled = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(8, 5, 8)) {
            if (entity instanceof org.bukkit.entity.Monster monster) {
                org.bukkit.util.Vector away = monster.getLocation().toVector()
                        .subtract(player.getLocation().toVector());
                if (away.lengthSquared() > 0.01) {
                    monster.setVelocity(away.normalize().multiply(1.5));
                }
                repelled++;
            }
        }
        player.getWorld().spawnParticle(org.bukkit.Particle.SOUL,
                player.getLocation().add(0, 1, 0), 8, 0.5, 0.5, 0.5, 0.02);
        player.sendActionBar(net.kyori.adventure.text.Component.text("灵魂宝石：排斥 " + repelled + " 个敌对生物"));
        return true;
    }

    /** 念之宝石：吸引掉落物与经验球。 */
    private boolean mindStone(Player player) {
        int pulled = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(8, 5, 8)) {
            if (entity instanceof org.bukkit.entity.Item || entity instanceof org.bukkit.entity.ExperienceOrb) {
                org.bukkit.util.Vector to = player.getLocation().toVector()
                        .subtract(entity.getLocation().toVector());
                if (to.lengthSquared() > 0.01) {
                    entity.setVelocity(to.normalize().multiply(1.2));
                }
                pulled++;
            }
        }
        player.sendActionBar(net.kyori.adventure.text.Component.text("念之宝石：吸引 " + pulled + " 个目标"));
        return true;
    }

    /** 以太密度宝石：修复手持工具并为其充能。 */
    private boolean eternalDensity(Player player, ItemStack hand) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType().isAir() || !(tool.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable)) {
            player.sendActionBar(net.kyori.adventure.text.Component.text("以太密度宝石：请手持可修复的工具"));
            return true;
        }
        int damage = damageable.getDamage();
        if (damage <= 0) {
            player.sendActionBar(net.kyori.adventure.text.Component.text("以太密度宝石：工具完好"));
            return true;
        }
        damageable.setDamage(0);
        tool.setItemMeta((org.bukkit.inventory.meta.ItemMeta) damageable);
        player.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT,
                player.getLocation().add(0, 1, 0), 12, 0.4, 0.4, 0.4, 0.5);
        player.sendActionBar(net.kyori.adventure.text.Component.text("以太密度宝石：工具已修复"));
        return true;
    }

    /** 毁灭燧石：范围破坏（5×5×5）。 */
    private boolean destructionCatalyst(Player player) {
        var center = player.getTargetBlockExact(6);
        if (center == null) {
            player.sendActionBar(net.kyori.adventure.text.Component.text("毁灭燧石：未瞄准方块"));
            return true;
        }
        int destroyed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    org.bukkit.block.Block block = center.getRelative(dx, dy, dz);
                    if (block.getType().isAir() || block.getType().getHardness() < 0) {
                        continue;
                    }
                    block.breakNaturally();
                    destroyed++;
                }
            }
        }
        player.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION,
                center.getLocation().add(0.5, 0.5, 0.5), 3, 1, 1, 1);
        player.sendActionBar(net.kyori.adventure.text.Component.text("毁灭燧石：破坏 " + destroyed + " 个方块"));
        return true;
    }

    /** 潮汐护符：放水（Shift 灭火）。 */
    private boolean evertideAmulet(Player player, boolean shift) {
        var target = player.getTargetBlockExact(6);
        if (target == null) {
            return true;
        }
        if (shift) {
            int cleared = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        org.bukkit.block.Block b = target.getRelative(dx, dy, dz);
                        if (b.getType() == org.bukkit.Material.FIRE || b.getType() == org.bukkit.Material.SOUL_FIRE) {
                            b.setType(org.bukkit.Material.AIR);
                            cleared++;
                        }
                    }
                }
            }
            player.sendActionBar(net.kyori.adventure.text.Component.text("潮汐护符：扑灭 " + cleared + " 处火焰"));
            return true;
        }
        org.bukkit.block.Block above = target.getRelative(0, 1, 0);
        if (above.getType().isAir()) {
            above.setType(org.bukkit.Material.WATER);
        }
        player.sendActionBar(net.kyori.adventure.text.Component.text("潮汐护符：水体生成"));
        return true;
    }

    /** 熔焰护符：放岩浆（Shift 熄灭为圆石）。 */
    private boolean volcaniteAmulet(Player player, boolean shift) {
        var target = player.getTargetBlockExact(6);
        if (target == null) {
            return true;
        }
        if (shift) {
            if (target.getType() == org.bukkit.Material.LAVA) {
                target.setType(org.bukkit.Material.COBBLESTONE);
                player.sendActionBar(net.kyori.adventure.text.Component.text("熔焰护符：岩浆已凝固"));
            }
            return true;
        }
        org.bukkit.block.Block above = target.getRelative(0, 1, 0);
        if (above.getType().isAir()) {
            above.setType(org.bukkit.Material.LAVA);
        }
        player.sendActionBar(net.kyori.adventure.text.Component.text("熔焰护符：岩浆生成"));
        return true;
    }

    /** 烈焰指环：点燃目标（Shift 熄灭）。 */
    private boolean ignitionRing(Player player, boolean shift) {
        var target = player.getTargetBlockExact(6);
        if (target == null) {
            return true;
        }
        if (shift) {
            if (target.getType() == org.bukkit.Material.FIRE) {
                target.setType(org.bukkit.Material.AIR);
            }
            player.sendActionBar(net.kyori.adventure.text.Component.text("烈焰指环：熄灭火焰"));
            return true;
        }
        org.bukkit.block.Block above = target.getRelative(0, 1, 0);
        if (above.getType().isAir()) {
            above.setType(org.bukkit.Material.FIRE);
        }
        player.sendActionBar(net.kyori.adventure.text.Component.text("烈焰指环：点燃"));
        return true;
    }

    /** 零度指环：冻结水面（Shift 解冻）。 */
    private boolean zeroRing(Player player, boolean shift) {
        var target = player.getTargetBlockExact(6);
        if (target == null) {
            return true;
        }
        int changed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                org.bukkit.block.Block b = target.getRelative(dx, 0, dz);
                if (!shift && b.getType() == org.bukkit.Material.WATER) {
                    b.setType(org.bukkit.Material.ICE);
                    changed++;
                } else if (shift && b.getType() == org.bukkit.Material.ICE) {
                    b.setType(org.bukkit.Material.WATER);
                    changed++;
                }
            }
        }
        player.getWorld().spawnParticle(org.bukkit.Particle.SNOWFLAKE,
                target.getLocation().add(0.5, 1, 0.5), 15, 1.5, 0.5, 1.5, 0.01);
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                shift ? "零度指环：解冻 " + changed + " 格" : "零度指环：冻结 " + changed + " 格"));
        return true;
    }

    /** 疾风戒指：短距冲刺（Shift 高空滑翔助推）。 */
    private boolean swiftwolf(Player player) {
        org.bukkit.util.Vector dir = player.getLocation().getDirection().normalize().multiply(1.4);
        dir.setY(Math.max(dir.getY(), 0.45));
        player.setVelocity(dir);
        player.getWorld().spawnParticle(org.bukkit.Particle.CLOUD,
                player.getLocation(), 10, 0.3, 0.1, 0.3, 0.05);
        return true;
    }

    /** 大天使的惩戒：召唤闪电。 */
    private boolean archangelSmite(Player player) {
        var target = player.getTargetBlockExact(24);
        if (target == null) {
            player.sendActionBar(net.kyori.adventure.text.Component.text("大天使的惩戒：未瞄准目标"));
            return true;
        }
        player.getWorld().strikeLightning(target.getLocation());
        return true;
    }

    /** 丰收女神戒指：收获并补种周围成熟作物。 */
    private boolean harvestGoddess(Player player) {
        int harvested = 0;
        int radius = 4;
        org.bukkit.Location center = player.getLocation();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    org.bukkit.block.Block block = center.getWorld().getBlockAt(
                            center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                    org.bukkit.block.data.Ageable ageable =
                            block.getBlockData() instanceof org.bukkit.block.data.Ageable a ? a : null;
                    if (ageable == null || ageable.getAge() < ageable.getMaximumAge()) {
                        continue;
                    }
                    for (ItemStack drop : block.getDrops()) {
                        block.getWorld().dropItemNaturally(block.getLocation(), drop);
                    }
                    ageable.setAge(0);
                    block.setBlockData(ageable);
                    harvested++;
                }
            }
        }
        player.sendActionBar(net.kyori.adventure.text.Component.text("丰收女神戒指：收获 " + harvested + " 株作物"));
        return true;
    }

    /** 修复护符：修复背包内全部可修复物品。 */
    private boolean repairTalisman(Player player) {
        int repaired = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (stack.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable
                    && damageable.getDamage() > 0) {
                damageable.setDamage(0);
                stack.setItemMeta((org.bukkit.inventory.meta.ItemMeta) damageable);
                repaired++;
            }
        }
        player.sendActionBar(net.kyori.adventure.text.Component.text("修复护符：修复 " + repaired + " 件物品"));
        return true;
    }

    /** 时间洪流怀表：催熟周围作物（加速 11×11×11 内可成长方块）。 */
    private boolean watchOfFlowingTime(Player player) {
        int accelerated = 0;
        int radius = 5;
        org.bukkit.Location center = player.getLocation();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    org.bukkit.block.Block block = center.getWorld().getBlockAt(
                            center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                    if (block.getBlockData() instanceof org.bukkit.block.data.Ageable ageable
                            && ageable.getAge() < ageable.getMaximumAge()) {
                        ageable.setAge(Math.min(ageable.getMaximumAge(), ageable.getAge() + 1));
                        block.setBlockData(ageable);
                        accelerated++;
                    }
                }
            }
        }
        player.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT,
                player.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.5);
        player.sendActionBar(net.kyori.adventure.text.Component.text("时间洪流怀表：催熟 " + accelerated + " 株作物"));
        return true;
    }

    /** 墨丘利之眼：把瞄准的方块按世界转换表转换。 */
    private boolean mercurialEye(Player player, boolean shift) {
        var target = player.getTargetBlockExact(8);
        if (target == null) {
            return true;
        }
        if (worldTransmutation.apply(target, shift)) {
            player.getWorld().playSound(target.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.2f);
            player.sendActionBar(net.kyori.adventure.text.Component.text("墨丘利之眼：方块已转换"));
        } else {
            player.sendActionBar(net.kyori.adventure.text.Component.text("墨丘利之眼：该方块无转换"));
        }
        return true;
    }

    /** 虚空指环：传送到瞄准位置。 */
    private boolean voidRing(Player player) {
        var target = player.getTargetBlockExact(24);
        if (target == null) {
            player.sendActionBar(net.kyori.adventure.text.Component.text("虚空指环：未瞄准目标"));
            return true;
        }
        org.bukkit.Location destination = target.getLocation().add(0.5, 1, 0.5);
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        player.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, player.getLocation(), 20, 0.3, 0.5, 0.3);
        player.teleport(destination);
        player.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, destination, 20, 0.3, 0.5, 0.3);
        player.getWorld().playSound(destination, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
        return true;
    }

    /** 奥法指环：组合（疾风 / 零度 / 烈焰 / 治疗 循环）。 */
    private boolean arcanaRing(Player player, boolean shift) {
        if (shift) {
            return zeroRing(player, false);
        }
        int mode = player.isSneaking() ? 1 : 0;
        if (mode == 0) {
            swiftwolf(player);
            apply(player, PotionEffectType.SPEED, 200, 1);
            player.sendActionBar(net.kyori.adventure.text.Component.text("奥法指环：疾风形态"));
        } else {
            ignitionRing(player, false);
        }
        return true;
    }

    private static long kleinStarCapacity(String simple) {
        return switch (simple) {
            case "klein_star_ein" -> 50_000L;
            case "klein_star_zwei" -> 200_000L;
            case "klein_star_drei" -> 800_000L;
            case "klein_star_vier" -> 3_200_000L;
            case "klein_star_sphere" -> 12_800_000L;
            case "klein_star_omega" -> 51_200_000L;
            default -> 50_000L;
        };
    }

    /** 超动能透镜：发射爆炸火球。 */
    private boolean hyperkineticLens(Player player) {
        var fireball = player.launchProjectile(org.bukkit.entity.SmallFireball.class);
        fireball.setIsIncendiary(false);
        fireball.setYield(1.8f);
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ITEM_FIRECHARGE_USE, 0.6f, 1.3f);
        player.sendActionBar(net.kyori.adventure.text.Component.text("超动能透镜：发射爆裂弹"));
        return true;
    }

    /** 催化透镜：发射高速穿透箭。 */
    private boolean catalyticLens(Player player) {
        var arrow = player.launchProjectile(org.bukkit.entity.Arrow.class);
        arrow.setVelocity(player.getLocation().getDirection().multiply(3.2));
        arrow.setDamage(9.0);
        arrow.setPierceLevel(3);
        arrow.setCritical(true);
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ARROW_SHOOT, 0.7f, 1.4f);
        player.sendActionBar(net.kyori.adventure.text.Component.text("催化透镜：发射穿透弹"));
        return true;
    }

    /** 黑洞指环：吸引周围掉落物与经验（主动触发一帧强吸）。 */
    private boolean blackHoleBand(Player player) {
        int pulled = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(10, 6, 10)) {
            if (entity instanceof org.bukkit.entity.Item || entity instanceof org.bukkit.entity.ExperienceOrb) {
                org.bukkit.util.Vector to = player.getLocation().add(0, 1, 0).toVector()
                        .subtract(entity.getLocation().toVector());
                if (to.lengthSquared() > 0.01) {
                    entity.setVelocity(to.normalize().multiply(1.8));
                }
                pulled++;
            }
        }
        player.getWorld().spawnParticle(org.bukkit.Particle.PORTAL,
                player.getLocation().add(0, 1, 0), 12, 1.2, 0.6, 1.2, 0.2);
        player.sendActionBar(net.kyori.adventure.text.Component.text("黑洞指环：牵引 " + pulled + " 个目标"));
        return true;
    }

    /** 黑洞指环周期被动：持续磁力（由主类节拍调用）。 */
    public int blackHolePull(Player player) {
        int pulled = 0;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(9, 6, 9)) {
            if (entity instanceof org.bukkit.entity.Item || entity instanceof org.bukkit.entity.ExperienceOrb) {
                org.bukkit.util.Vector to = player.getLocation().add(0, 1, 0).toVector()
                        .subtract(entity.getLocation().toVector());
                if (to.lengthSquared() > 0.01) {
                    entity.setVelocity(to.normalize().multiply(1.1));
                }
                pulled++;
            }
        }
        return pulled;
    }

    /** 暗物质台座：放入/取出手持物品（空手右键取下）。 */
    public boolean pedestalInteract(Player player, org.bukkit.block.Block block, ItemStack hand) {
        var key = "pedestal:" + block.getWorld().getName() + "@"
                + block.getX() + "," + block.getY() + "," + block.getZ();
        if (hand.getType().isAir() || player.isSneaking()) {
            ItemStack stored = plugin.pedestalStore().remove(key);
            if (stored == null) {
                player.sendActionBar(net.kyori.adventure.text.Component.text("台座上没有物品"));
                return true;
            }
            player.getInventory().addItem(stored).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            player.sendActionBar(net.kyori.adventure.text.Component.text("已取回台座上的物品"));
            return true;
        }
        String id = emc.idOf(hand);
        if (id == null) {
            return true;
        }
        ItemStack stored = hand.clone();
        stored.setAmount(1);
        ItemStack previous = plugin.pedestalStore().put(key, stored);
        hand.setAmount(hand.getAmount() - 1);
        if (previous != null) {
            player.getInventory().addItem(previous).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        player.sendActionBar(net.kyori.adventure.text.Component.text("台座已放置：" + id));
        player.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
        return true;
    }

    /** 暗物质台座周期效果：按台座上的物品对 11×11×11 范围生效。 */
    public void pedestalTick(org.bukkit.block.Block block, ItemStack stored) {
        if (stored == null || stored.getType().isAir()) {
            return;
        }
        String id = emc.idOf(stored);
        if (id == null) {
            return;
        }
        String simple = simple(id);
        int radius = 5;
        org.bukkit.Location center = block.getLocation();
        switch (simple) {
            case "watch_of_flowing_time" -> {
                // 范围催熟
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            org.bukkit.block.Block b = center.getWorld().getBlockAt(
                                    center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                            if (b.getBlockData() instanceof org.bukkit.block.data.Ageable ageable
                                    && ageable.getAge() < ageable.getMaximumAge()) {
                                ageable.setAge(Math.min(ageable.getMaximumAge(), ageable.getAge() + 1));
                                b.setBlockData(ageable);
                            }
                        }
                    }
                }
            }
            case "evertide_amulet" -> {
                // 范围灭火
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            org.bukkit.block.Block b = center.getWorld().getBlockAt(
                                    center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                            if (b.getType() == org.bukkit.Material.FIRE) {
                                b.setType(org.bukkit.Material.AIR);
                            }
                        }
                    }
                }
            }
            case "harvest_goddess_band" -> {
                // 范围收获
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            org.bukkit.block.Block b = center.getWorld().getBlockAt(
                                    center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                            if (b.getBlockData() instanceof org.bukkit.block.data.Ageable ageable
                                    && ageable.getAge() >= ageable.getMaximumAge()) {
                                for (ItemStack drop : b.getDrops()) {
                                    b.getWorld().dropItemNaturally(b.getLocation(), drop);
                                }
                                ageable.setAge(0);
                                b.setBlockData(ageable);
                            }
                        }
                    }
                }
            }
            default -> {
                // 其他物品：台座不产生周期效果（可扩展）
            }
        }
    }

    /** 转换台/凝聚器：把物品学习进知识库（右键方块走 GUI，此处为命令/备用路径）。 */
    public boolean learn(PePlayerData data, ItemStack stack) {
        String id = emc.idOf(stack);
        if (id == null || emc.emcOf(id) <= 0) {
            return false;
        }
        return data.learn(id);
    }

    /** 机器摘要（调试）。 */
    public List<String> machineSummary() {
        return machines.all().stream()
                .map(state -> state.key() + " " + state.type().name())
                .toList();
    }

    /** 位置摘要。 */
    public static String format(Location location) {
        return String.format(Locale.ROOT, "%s @ %d,%d,%d",
                location.getWorld() == null ? "?" : location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** 生存模式辅助：给予物品（命令用，保留堆叠语义）。 */
    public static void give(Player player, ItemStack stack) {
        var leftover = player.getInventory().addItem(stack);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    /** 机器类型查询（供监听器判断机器方块）。 */
    public static MachineState.Type machineType(String simple) {
        return MachineState.Type.of(simple);
    }

    /** 服务器就绪检查（GUI 前置）。 */
    public boolean ready() {
        return Bukkit.isPrimaryThread();
    }
}
