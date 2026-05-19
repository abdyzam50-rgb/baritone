package baritone.combat;

import baritone.api.utils.IPlayerContext;
import baritone.awareness.AwarenessContext;
import baritone.awareness.model.ThreatEntry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Illusioner;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;

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
 *   1. Revenge target (mob that last hurt us, within 5 seconds).
 *   2. Ranged threats (Skeleton, Stray, Witch, Pillager, Illusioner)
 *      — killed first because they deal damage while kiting.
 *   3. Highest-scored alive non-creeper.
 *   4. Creeper (solo only — CombatEngine will just wait for CreepeTactics).
 */
public final class TargetSelector {

    private static final double LOCK_MAX_DISTANCE = 24.0;
    /** Ticks after being hit during which the attacker is top-priority (~5 seconds). */
    private static final int REVENGE_TICKS = 100;

    private final IPlayerContext ctx;
    private Entity        lockedTarget     = null;
    // Revenge tracking — we detect the change ourselves to avoid relying on
    // LivingEntity.getLastHurtByMobTimestamp() which is package-private on some mappings.
    private LivingEntity  lastSeenAttacker = null;
    private int           attackerSeenAt   = 0;

    public TargetSelector(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public ThreatEntry select(AwarenessContext awarenessCtx) {
        List<ThreatEntry> threats = awarenessCtx.getThreats();
        if (threats.isEmpty()) {
            lockedTarget = null;
            return null;
        }

        // Revenge: the mob that last hurt us jumps to the front of the queue.
        Player player = ctx.player();
        if (player != null) {
            LivingEntity currentAttacker = player.getLastHurtByMob();
            if (currentAttacker != lastSeenAttacker) {
                lastSeenAttacker = currentAttacker;
                attackerSeenAt   = player.tickCount;
            }
            if (lastSeenAttacker != null && lastSeenAttacker.isAlive()
                    && (player.tickCount - attackerSeenAt) < REVENGE_TICKS) {
                for (ThreatEntry t : threats) {
                    if (t.tracked.entity == lastSeenAttacker) {
                        lockedTarget = lastSeenAttacker;
                        return t;
                    }
                }
            }
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
