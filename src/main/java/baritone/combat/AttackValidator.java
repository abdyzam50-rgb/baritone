package baritone.combat;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Fires entity attacks when the correct conditions are met.
 *
 * Primary strategy — jump-crit every hit:
 *   When grounded and cooldown is nearly ready, jump. Wait for the DOWNWARD arc
 *   (delta.y < 0) before attacking for a 1.5× damage critical hit.
 *   Safety timer fires the attack after MAX_JUMP_WAIT ticks if the arc never comes.
 *
 * Ceiling fallback — S-tap:
 *   When a solid block within jump-clearance height is detected above the player,
 *   jumping would be blocked (or produce no crit arc). Instead:
 *     Tick 0 — press S for one tick to break sprint momentum (scheduleSTap).
 *     Tick 1 — cooldown still high enough → fire attack + W-tap as normal.
 *   This avoids the wasted jump and still maximises knockback by clearing sprint.
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
    private boolean sTapPending   = false;  // attack on next tick after S-tap

    public AttackValidator(IPlayerContext ctx, SpacingController spacing) {
        this.ctx     = ctx;
        this.spacing = spacing;
    }

    public void tick(InputOverrideHandler input, ThreatEntry target) {
        Player player = ctx.player();
        if (player == null) return;

        float cooldown = player.getAttackStrengthScale(0f);
        float distance = (float) target.tracked.distance;

        if (distance > MAX_RANGE) {
            jumpedForCrit = false;
            jumpTimer     = 0;
            sTapPending   = false;
            return;
        }
        if (cooldown < MIN_COOLDOWN) return;

        Vec3    delta    = player.getDeltaMovement();
        boolean onGround = player.isOnGround();
        boolean falling  = !onGround && !player.onClimbable()
                        && !player.isInWater() && delta.y < 0;

        // ── S-tap pending: the S-tap landed last tick; attack now ──────────────────
        if (sTapPending) {
            if (onGround && target.tracked.hasLineOfSight) {
                input.setInputForceState(Input.CLICK_RIGHT, false);
                spacing.scheduleWTap();
                Minecraft mc = ctx.minecraft();
                if (mc.gameMode != null) {
                    mc.gameMode.attack(ctx.player(), target.tracked.entity);
                }
            }
            sTapPending = false;
            return;
        }

        // ── Ceiling check — prefer S-tap when jump would be blocked ──────────────
        if (!jumpedForCrit && onGround && cooldown >= JUMP_COOLDOWN) {
            if (hasCeilingAbove(player)) {
                // Can't jump-crit: S-tap this tick, attack next tick
                spacing.scheduleSTap();
                sTapPending = true;
                return;
            }
            // Clear ceiling — initiate the normal jump-crit
            input.setInputForceState(Input.JUMP, true);
            jumpedForCrit = true;
            jumpTimer     = 0;
        }
        if (jumpedForCrit) jumpTimer++;

        // ── Attack on falling arc or safety timer ─────────────────────────────
        boolean shouldHit = falling || (jumpedForCrit && jumpTimer > MAX_JUMP_WAIT);

        if (shouldHit && target.tracked.hasLineOfSight) {
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

    public void reset() {
        jumpedForCrit = false;
        jumpTimer     = 0;
        sTapPending   = false;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Returns true if a solid block is close enough above the player to prevent a
     * jump-crit arc. A standard jump clears ~1.25 blocks; the player is 1.8 m tall,
     * so check the block 2 above feet (≈ head height mid-jump).
     */
    private boolean hasCeilingAbove(Player player) {
        Level world = ctx.world();
        if (world == null) return false;
        BlockPos feet  = player.blockPosition();
        BlockPos check = feet.above(2);
        return !world.getBlockState(check).isAir()
            || !world.getBlockState(check.above()).isAir();
    }
}
