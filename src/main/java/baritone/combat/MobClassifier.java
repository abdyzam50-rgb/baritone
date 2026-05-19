package baritone.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Hoglin;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Piglin;
import net.minecraft.world.entity.monster.PiglinBrute;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Per-mob hostility and danger classification, ported from altoclef's EntityHelper.
 *
 * isHostileToPlayer  — used by EntitySensor to decide whether a Monster should be
 *                      classified as HOSTILE_MOB or PASSIVE_MOB this tick.
 *
 * isUniversallyDangerous — used by CombatEngine to flee rather than engage.
 */
public final class MobClassifier {

    private MobClassifier() {}

    /**
     * Returns true if this entity is currently hostile toward the given player.
     * Handles neutral mobs that only aggro under specific conditions.
     */
    public static boolean isHostileToPlayer(Entity entity, Player player) {
        if (!(entity instanceof Monster)) return false;

        // Enderman: only hostile when angry (triggered by the player making eye contact)
        if (entity instanceof EnderMan enderman) {
            return enderman.isAngry();
        }
        // Piglin: neutral when the player wears at least one piece of gold armor,
        // and always neutral while mid-trade.
        if (entity instanceof Piglin piglin) {
            if (piglin.isTrading()) return false;
            return !playerWearingGold(player);
        }
        // Zombified Piglin: passive until the group is provoked.  Only mark hostile
        // when this specific mob is angry AND has the player as its target.
        if (entity instanceof ZombifiedPiglin zombie) {
            return zombie.isAngry() && isTargetingPlayer(zombie, player);
        }

        return true; // all other Monster subclasses are unconditionally hostile
    }

    /**
     * Returns true when engaging this mob in melee is almost certainly fatal.
     * CombatEngine uses GoalRunAway(20) instead of closing to melee range.
     */
    public static boolean isUniversallyDangerous(Entity entity) {
        return entity instanceof Warden
            || entity instanceof WitherSkeleton
            || entity instanceof Hoglin
            || entity instanceof Zoglin
            || entity instanceof PiglinBrute
            || entity instanceof Vindicator
            || entity.getType() == net.minecraft.world.entity.EntityType.WITHER;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static boolean playerWearingGold(Player player) {
        return player.getItemBySlot(EquipmentSlot.HEAD).getItem()  == Items.GOLDEN_HELMET
            || player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.GOLDEN_CHESTPLATE
            || player.getItemBySlot(EquipmentSlot.LEGS).getItem()  == Items.GOLDEN_LEGGINGS
            || player.getItemBySlot(EquipmentSlot.FEET).getItem()  == Items.GOLDEN_BOOTS;
    }

    private static boolean isTargetingPlayer(Mob mob, Player player) {
        return mob.getTarget() != null
            && mob.getTarget().getUUID().equals(player.getUUID());
    }
}
