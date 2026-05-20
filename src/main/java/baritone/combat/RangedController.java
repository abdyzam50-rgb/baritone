package baritone.combat;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;

/**
 * Handles bow and crossbow attacks in the 5–16 m band.
 *
 * Bow:   hold CLICK_RIGHT for BOW_DRAW_TICKS (20), then release to fire.
 *        Gravity compensation aims above the target by the estimated drop.
 *
 * Crossbow: hold CLICK_RIGHT to load (CHARGED_PROJECTILES component becomes
 *           non-empty), then one more CLICK_RIGHT fires instantly.
 *           Prefers crossbow over bow when both are available.
 *
 * Returns true each tick a ranged action is active so CombatEngine can hold
 * position while drawing or firing.
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

    public boolean tick(InputOverrideHandler input, ThreatEntry target) {
        Player player = ctx.player();
        if (player == null || target == null || !target.tracked.hasLineOfSight) {
            reset();
            return false;
        }

        float dist = (float) target.tracked.distance;
        if (dist < MIN_RANGE || dist > MAX_RANGE) { reset(); return false; }

        int cbSlot = InventoryLayout.findCrossbowSlot(player);
        if (cbSlot >= 0) {
            ItemStack cb = player.getInventory().getItem(cbSlot);
            if (isCrossbowCharged(cb)) {
                player.getInventory().selected = cbSlot;
                aimRanged(target, dist, player);
                input.setInputForceState(Input.CLICK_RIGHT, true);
                drawing = false; drawTimer = 0;
                return true;
            } else {
                player.getInventory().selected = cbSlot;
                input.setInputForceState(Input.CLICK_RIGHT, true);
                drawing = true; drawTimer++;
                return true;
            }
        }

        int bowSlot = InventoryLayout.findBowSlot(player);
        if (bowSlot >= 0) {
            player.getInventory().selected = bowSlot;
            aimRanged(target, dist, player);
            drawing = true;
            drawTimer++;
            if (drawTimer <= BOW_DRAW_TICKS) {
                input.setInputForceState(Input.CLICK_RIGHT, true);
            } else {
                drawTimer = 0;
                drawing   = false;
            }
            return true;
        }

        reset();
        return false;
    }

    public void reset()            { drawTimer = 0; drawing = false; }
    public boolean isDrawing()     { return drawing; }

    private static boolean isCrossbowCharged(ItemStack stack) {
        ChargedProjectiles cp = stack.get(DataComponents.CHARGED_PROJECTILES);
        return cp != null && !cp.isEmpty();
    }

    private void aimRanged(ThreatEntry target, float dist, Player player) {
        Vec3 eye  = player.getEyePosition(1f);
        Vec3 tEye = target.tracked.entity.getEyePosition(1f);
        double tFlight = dist / 3.0;
        double drop    = 0.025 * tFlight * tFlight;
        Vec3   adjusted = new Vec3(tEye.x, tEye.y + drop, tEye.z);
        Vec3   dir   = adjusted.subtract(eye).normalize();
        float  yaw   = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float  pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));
        player.setYRot(yaw);   player.yRotO = yaw;
        player.setXRot(pitch); player.xRotO = pitch;
    }
}
