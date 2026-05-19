package baritone.combat;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.awareness.AwarenessContext;
import baritone.awareness.model.SelfState;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Top-level combat orchestrator.  Called every tick by CombatProcess.
 *
 * Tick order:
 *   1. Reset all combat inputs.
 *   2. ShieldController.manageOffHand — totem/shield swap based on HP.
 *   3. PearlController — escape throw when surrounded or shield broken + low HP.
 *   4. HealthGate — disengage and heal when HP < 50%.
 *   5. CreepeTactics — fusing creeper override (sprints away immediately).
 *   6. TargetSelector — pick best living target.
 *   6a. Universally dangerous target (Warden, Wither, etc.) → GoalRunAway(20).
 *   6b. Creeper target → pause; CreepeTactics handles the fuse.
 *   7a. Target > 4.5 m → pathing zone:
 *       PotionController.tickSelfBuff — drink Strength/Speed if not already buffed.
 *       RangedController.tick — bow/crossbow if in 5–16 m band with line of sight.
 *       Stuck detection: cancel path after 40 ticks without closing 0.2 m/tick.
 *       Baritone GoalNear(3) to close gap.
 *   7b. Target ≤ 4.5 m → direct input control:
 *       PotionController.tickSplash — throw Harm/Slow/Weakness/Poison if available.
 *       WeaponSelector sets hotbar slot (DPS-based, or axe when enemy blocks).
 *       ShieldController.tickDefense raises shield on incoming mace dive.
 *       SpacingController drives W/A/D/sprint/W-tap (adaptive strafe).
 *       AttackValidator fires attack only on falling arc (crit).
 *       Player rotation set directly toward target each tick.
 */
public final class CombatEngine {

    private static final float  ENGAGE_DISTANCE  = 4.5f;
    private static final double FLEE_DISTANCE    = 20.0;
    /** Ticks without closing distance before we cancel the stale path and try direct inputs. */
    private static final int    STUCK_TICKS      = 40;
    /** Minimum distance reduction per tick to not count as stuck (blocks). */
    private static final float  STUCK_MIN_CLOSE  = 0.2f;

    private final Baritone       baritone;
    private final IPlayerContext ctx;
    private final AwarenessContext awarenessCtx;

    private final TargetSelector    targetSelector;
    private final SpacingController spacingController;
    private final AttackValidator   attackValidator;
    private final CreepeTactics     creepeTactics;
    private final HealthGate        healthGate;
    private final WeaponSelector    weaponSelector;
    private final ShieldController  shieldController;
    private final PearlController   pearlController;
    private final RangedController  rangedController;
    private final PotionController  potionController;

    // Stuck-detection: cancel stale GoalNear paths that aren't closing the gap
    private float lastKnownDist      = -1;
    private int   closingStuckTicks  = 0;
    // Range-transition: reset attack state when first entering melee range
    private boolean wasInDirectControl = false;

    public CombatEngine(Baritone baritone, AwarenessContext awarenessCtx) {
        this.baritone      = baritone;
        this.ctx           = baritone.getPlayerContext();
        this.awarenessCtx  = awarenessCtx;
        targetSelector    = new TargetSelector(ctx);
        spacingController = new SpacingController();
        attackValidator   = new AttackValidator(ctx, spacingController);
        creepeTactics     = new CreepeTactics(ctx);
        healthGate        = new HealthGate(ctx);
        weaponSelector    = new WeaponSelector();
        shieldController  = new ShieldController(ctx);
        pearlController   = new PearlController(ctx);
        rangedController  = new RangedController(ctx);
        potionController  = new PotionController(ctx);
    }

    public PathingCommand tick() {
        InputOverrideHandler input = baritone.getInputOverrideHandler();
        Player player = ctx.player();
        if (input == null || player == null) return pause();

        resetInputs(input);

        SelfState self = awarenessCtx.getSelf();

        // 1. Off-hand management (totem ↔ shield swap)
        shieldController.manageOffHand(self);

        // 2. Pearl escape (runs before health gate so we can flee even while trying to heal)
        boolean ownShieldBroken = shieldController.isOwnShieldBroken();
        pearlController.tick(input, awarenessCtx, ownShieldBroken);
        if (pearlController.justThrew()) return pause();

        // 3. Health gate
        if (healthGate.shouldHeal(awarenessCtx)) {
            return healthGate.tick(input, awarenessCtx);
        }

        // 4. Creeper override — sprints away immediately when any creeper is fusing
        PathingCommand creeperCmd = creepeTactics.tick(input, awarenessCtx);
        if (creeperCmd != null) return creeperCmd;

        // 5. Target selection
        ThreatEntry target = targetSelector.select(awarenessCtx);
        if (target == null || !target.tracked.entity.isAlive()) return pause();

        float distance = (float) target.tracked.distance;

        // 6a. Universally dangerous mobs: never engage, use Baritone to flee
        if (MobClassifier.isUniversallyDangerous(target.tracked.entity)) {
            return new PathingCommand(
                new GoalRunAway(target.tracked.entity.blockPosition(), FLEE_DISTANCE),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        // 6b. Creepers: do not approach. CreepeTactics handles the fuse when it starts;
        //     the creeper will chase the player naturally until then.
        if (target.tracked.entity instanceof Creeper) {
            return pause();
        }

        boolean inDirectControl = distance <= ENGAGE_DISTANCE;

        if (!inDirectControl) {
            // Stuck detection — only count ticks when NOT actively shooting
            if (!rangedController.isDrawing()) {
                if (lastKnownDist > 0 && distance > lastKnownDist - STUCK_MIN_CLOSE) {
                    closingStuckTicks++;
                    if (closingStuckTicks > STUCK_TICKS) {
                        baritone.getPathingBehavior().secretInternalSegmentCancel();
                        closingStuckTicks = 0;
                    }
                } else {
                    closingStuckTicks = 0;
                }
                lastKnownDist = distance;
            }
            wasInDirectControl = false;

            // Drink Strength/Speed while chasing (slot managed internally during the hold)
            if (potionController.tickSelfBuff(input, player)) {
                // Keep pathing while drinking — just don't try to shoot simultaneously
                return new PathingCommand(
                    new GoalNear(target.tracked.entity.blockPosition(), 3),
                    PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }

            // Ranged attack when in the bow/crossbow band with line of sight
            if (rangedController.tick(input, target)) {
                return pause(); // hold position while drawing / firing
            }

            return new PathingCommand(
                new GoalNear(target.tracked.entity.blockPosition(), 3),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        // Entering direct-input range — reset stale state from the chase phase
        if (!wasInDirectControl) {
            attackValidator.reset();
            rangedController.reset();
            potionController.cancelDrink();
            lastKnownDist     = -1;
            closingStuckTicks = 0;
        }
        wasInDirectControl = true;

        // 7. Direct input control at close range
        aimAt(target.tracked.entity);

        // Throw a splash potion if one is available and the cooldown allows.
        // This overrides the weapon slot and aim for one tick, then normal melee resumes.
        if (potionController.tickSplash(input, target, player)) {
            return pause();
        }

        int desiredSlot = weaponSelector.select(player, awarenessCtx);
        player.getInventory().selected = desiredSlot;

        shieldController.tickDefense(target, input);
        attackValidator.tick(input, target);
        spacingController.tick(input, target, awarenessCtx);

        return pause();
    }

    /** True if CreepeTactics is still executing a post-explosion escape sprint. */
    public boolean hasPendingEscape() {
        return creepeTactics.hasPendingEscape();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private void aimAt(Entity target) {
        Vec3  eye   = ctx.player().getEyePosition(1f);
        Vec3  tEye  = target.getEyePosition(1f);
        Vec3  dir   = tEye.subtract(eye).normalize();
        float yaw   = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));
        Player p = ctx.player();
        p.setYRot(yaw);   p.yRotO = yaw;
        p.setXRot(pitch); p.xRotO = pitch;
    }

    private void resetInputs(InputOverrideHandler input) {
        input.setInputForceState(Input.MOVE_FORWARD, false);
        input.setInputForceState(Input.MOVE_BACK,    false);
        input.setInputForceState(Input.MOVE_LEFT,    false);
        input.setInputForceState(Input.MOVE_RIGHT,   false);
        input.setInputForceState(Input.SPRINT,       false);
        input.setInputForceState(Input.JUMP,         false);
        input.setInputForceState(Input.CLICK_LEFT,   false);
        input.setInputForceState(Input.CLICK_RIGHT,  false);
    }

    private static PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
}
