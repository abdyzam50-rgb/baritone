package baritone.combat;

import baritone.awareness.AwarenessContext;
import baritone.awareness.model.ThreatEntry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Illusioner;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.monster.Witch;

import java.util.List;

/**
 * Picks the highest-priority living target from the current threat list,
 * with hard target-locking to prevent mid-fight target switching.
 *
 * Lock rules:
 *   - Stick to the current locked target as long as it is alive and within 24 blocks.
 *   - Only switch when the locked target dies, flees, or de-spawns.
 *
 * Priority on fresh selection:
 *   1. Ranged threats (Skeleton, Stray, Witch, Pillager, Illusioner)
 *      — killed first because they deal damage while kiting.
 *   2. Highest-scored alive non-creeper.
 *   3. Creeper (solo only — CombatEngine will just wait for CreepeTactics).
 */
public final class TargetSelector {

    private static final double LOCK_MAX_DISTANCE = 24.0;

    private Entity lockedTarget = null;

    public ThreatEntry select(AwarenessContext ctx) {
        List<ThreatEntry> threats = ctx.getThreats();
        if (threats.isEmpty()) {
            lockedTarget = null;
            return null;
        }

        // Maintain lock while target is alive and close
        if (lockedTarget != null && lockedTarget.isAlive()) {
            for (ThreatEntry t : threats) {
                if (t.tracked.entity == lockedTarget
                        && t.tracked.distance < LOCK_MAX_DISTANCE) {
                    return t;
                }
            }
        }

        // Lock expired — pick the best new target
        ThreatEntry selected = selectBest(threats);
        lockedTarget = selected != null ? selected.tracked.entity : null;
        return selected;
    }

    private ThreatEntry selectBest(List<ThreatEntry> threats) {
        // 1. Ranged threats — these deal damage from range, kill them first
        for (ThreatEntry t : threats) {
            if (isRangedThreat(t.tracked.entity) && t.tracked.entity.isAlive()) return t;
        }
        // 2. Best non-creeper
        for (ThreatEntry t : threats) {
            if (!(t.tracked.entity instanceof Creeper) && t.tracked.entity.isAlive()) return t;
        }
        // 3. Creeper as last resort (CombatEngine will pause; CreepeTactics handles fuse)
        for (ThreatEntry t : threats) {
            if (t.tracked.entity.isAlive()) return t;
        }
        return null;
    }

    /**
     * True for mobs that attack from range and should be prioritised over melee
     * threats.  WitherSkeleton is intentionally excluded: it is melee and is
     * handled by MobClassifier.isUniversallyDangerous() in CombatEngine.
     */
    private static boolean isRangedThreat(Entity entity) {
        return entity instanceof Skeleton    // bow
            || entity instanceof Stray       // slow-falling ice arrows
            || entity instanceof Witch       // splash potions
            || entity instanceof Pillager    // crossbow
            || entity instanceof Illusioner; // bow + blindness
    }
}
