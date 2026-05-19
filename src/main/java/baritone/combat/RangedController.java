package baritone.combat;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Handles bow and crossbow attacks in the 5–16 m band where melee isn't viable
 * but Baritone pathing alone wastes damage-dealing time.
 *
 * Bow:
 *   Hold CLICK_RIGHT for BOW_DRAW_TICKS (20) for a full-power shot, then release.
 *   Gravity compensation: aim above the target by the estimated arrow drop so the
 *   shot lands at eye level rather than at the feet.
 *
 * Crossbow:
 *   Hold CLICK_RIGHT while unloaded; CrossbowItem.isCharged() becomes true when
 *   the loading animation completes (~25 ticks). One more CLICK_RIGHT fires instantly.
 *   Prefers crossbow over bow when both are available (instant fire when charged).
 *
 * Returns true each tick a ranged action is active so CombatEngine can suppress
 * pathing (hold position while drawing/firing).
 */
public final class RangedController {

    private static final float MIN_RANGE      = 5.0f;
    private static final float MAX_RANGE      = 16.0f;
    private static final int   BOW_DRAW_TICKS = 20;

    private final IPlayerContext ctx;
    private int     drawTimer = 0;
    private boolean drawing   = false;

    public RangedController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Called every tick while in the ranged band.
     * Returns true if a ranged action is in progress (caller should pause pathing).
     */
    public boolean tick(InputOverrideHandler input, ThreatEntry target) {
        Player player = ctx.player();
        if (player == null || target == null || !target.tracked.hasLineOfSight) {
            reset();
            return false;
        }

        float dist = (float) target.tracked.distance;
        if (dist < MIN_RANGE || dist > MAX_RANGE) {
            reset();
            return false;
        }

        // Prefer crossbow when available — fires instantly once charged.
        int cbSlot = InventoryLayout.findCrossbowSlot(player);
        if (cbSlot >= 0) {
            ItemStack cb = player.getInventory().getItem(cbSlot);
            if (CrossbowItem.isCharged(cb)) {
                // Fire the loaded crossbow
                player.getInventory().selected = cbSlot;
                aimRanged(target, dist, player);
                input.setInputForceState(Input.CLICK_RIGHT, true);
                drawing   = false;
                drawTimer = 0;
                return true;
            } else {
                // Hold right-click to load; isCharged() will flip true when done
                player.getInventory().selected = cbSlot;
                input.setInputForceState(Input.CLICK_RIGHT, true);
                drawing = true;
                drawTimer++;
                return true;
            }
        }

        // Bow: draw for BOW_DRAW_TICKS then release to fire
        int bowSlot = InventoryLayout.findBowSlot(player);
        if (bowSlot >= 0) {
            player.getInventory().selected = bowSlot;
            aimRanged(target, dist, player);
            drawing = true;
            drawTimer++;

            if (drawTimer <= BOW_DRAW_TICKS) {
                input.setInputForceState(Input.CLICK_RIGHT, true);
            } else {
                // Release — arrow fires; resetInputs already set CLICK_RIGHT false
                drawTimer = 0;
                drawing   = false;
            }
            return true;
        }

        reset();
        return false;
    }

    public void reset() {
        drawTimer = 0;
        drawing   = false;
    }

    public boolean isDrawing() {
        return drawing;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    /**
     * Aims toward the target with upward pitch correction for arrow/bolt gravity.
     *
     * At full bow draw the arrow travels ~3.0 m/tick with gravity −0.05 m/tick².
     * Drop ≈ 0.025 × (dist/3)²  — we add this to the target's eye height before
     * computing the look direction so the shot arcs down onto the target.
     */
    private void aimRanged(ThreatEntry target, float dist, Player player) {
        Vec3 eye  = player.getEyePosition(1f);
        Vec3 tEye = target.tracked.entity.getEyePosition(1f);

        double tFlight  = dist / 3.0;
        double drop     = 0.025 * tFlight * tFlight;
        Vec3   adjusted = new Vec3(tEye.x, tEye.y + drop, tEye.z);

        Vec3  dir   = adjusted.subtract(eye).normalize();
        float yaw   = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));

        player.setYRot(yaw);   player.yRotO = yaw;
        player.setXRot(pitch); player.xRotO = pitch;
    }
}
