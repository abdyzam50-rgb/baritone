package baritone.combat;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Fires entity attacks when the correct conditions are met.
 *
 * Strategy — crit every hit:
 *   When grounded and cooldown is nearly ready, jump. Wait for the DOWNWARD arc
 *   (delta.y < 0, i.e. falling) before attacking. This lands a 1.5× damage critical
 *   hit every swing. If the jump somehow gets stuck (ceiling, lag) a fallback fires
 *   after MAX_JUMP_WAIT ticks.
 *
 *   Uses Minecraft.gameMode.attack() directly rather than the CLICK_LEFT input so
 *   the attack targets the correct entity rather than whatever the crosshair hitResult
 *   happens to contain this frame.
 */
public final class AttackValidator {

    private static final float MIN_COOLDOWN  = 0.9f;
    private static final float JUMP_COOLDOWN = 0.85f;
    private static final float MAX_RANGE     = 3.0f;
    private static final int   MAX_JUMP_WAIT = 14;

    private final IPlayerContext    ctx;
    private final SpacingController spacing;

    private boolean jumpedForCrit = false;
    private int     jumpTimer     = 0;

    public AttackValidator(IPlayerContext ctx, SpacingController spacing) {
        this.ctx     = ctx;
        this.spacing = spacing;
    }

    public void tick(InputOverrideHandler input, ThreatEntry target) {
        Player player = ctx.player();
        if (player == null) return;

        float cooldown = player.getAttackStrengthScale(0f);
        float distance = (float) target.tracked.distance;

        // Reset stale jump state only when the target leaves melee range.
        // LOS loss alone does not reset — a brief occlusion should not waste the jump.
        if (distance > MAX_RANGE) {
            jumpedForCrit = false;
            jumpTimer     = 0;
            return;
        }
        if (cooldown < MIN_COOLDOWN) return;

        Vec3    delta    = player.getDeltaMovement();
        boolean onGround = player.isOnGround();
        boolean falling  = !onGround && !player.onClimbable()
                        && !player.isInWater() && delta.y < 0;

        // Initiate a crit jump when grounded and cooldown is nearly ready
        if (!jumpedForCrit && onGround && cooldown >= JUMP_COOLDOWN) {
            input.setInputForceState(Input.JUMP, true);
            jumpedForCrit = true;
            jumpTimer     = 0;
        }
        if (jumpedForCrit) jumpTimer++;

        // Attack only on the falling arc (true 1.5× crit) or when the safety timer expires.
        // No ground-attack fallback — that was causing non-crit hits at full cooldown.
        boolean shouldHit = falling || (jumpedForCrit && jumpTimer > MAX_JUMP_WAIT);

        if (shouldHit && target.tracked.hasLineOfSight) {
            // Drop shield the same tick we swing — holding it absorbs the knockback
            // that opens spacing and briefly delays the next cooldown cycle.
            input.setInputForceState(Input.CLICK_RIGHT, false);
            spacing.scheduleWTap();
            Minecraft mc = ctx.minecraft();
            if (mc.gameMode != null) {
                mc.gameMode.attack(ctx.player(), target.tracked.entity);
            }
            jumpedForCrit = false;
            jumpTimer     = 0;
        }
    }

    /** Reset jump-for-crit state, e.g. when first entering melee range. */
    public void reset() {
        jumpedForCrit = false;
        jumpTimer     = 0;
    }
}
