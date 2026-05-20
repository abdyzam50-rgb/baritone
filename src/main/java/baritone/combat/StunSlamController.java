package baritone.combat;

import baritone.api.utils.IPlayerContext;
import baritone.awareness.model.ThreatEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Stun-slam combo: when the target is blocking (shield raised), fire an axe hit to
 * break their shield, then follow up with a mace smash the next tick.
 *
 * Sequence (IDLE → AXE_HIT_FIRED → PENDING_SLAM):
 *   Tick 0 — holding sword: switch to axe → fire attack → queue slam (return true).
 *   Tick 0 — holding axe:   fire attack (already on axe) → queue slam (return true).
 *   Tick 1 — PENDING_SLAM:  switch to mace → fire smash → reset (return true).
 *
 * Trigger conditions (all must be true):
 *   • Target is actively blocking (LivingEntity.isBlocking()).
 *   • Attack-strength cooldown ≥ 0.9 — ensures the axe hit actually stuns.
 *   • Axe available in hotbar.
 *   • Mace available in hotbar.
 *   • Line of sight to target.
 *
 * Enchant preference for the mace:
 *   Breach (ignores 60–100% of armour) is preferred for grounded follow-ups.
 *   Falls back to Density or any plain mace if Breach is not in the hotbar.
 */
public final class StunSlamController {

    private static final float MIN_STRENGTH = 0.9f;

    private enum State { IDLE, PENDING_SLAM }

    private final IPlayerContext ctx;
    private State  state      = State.IDLE;
    private Entity slamTarget = null;
    private int    slamSlot   = -1;

    public StunSlamController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns true when this controller fires an attack this tick so CombatEngine
     * can skip AttackValidator and WeaponSelector for that tick.
     */
    public boolean tick(ThreatEntry target, Player player) {

        // ── PENDING_SLAM: fire the queued mace hit ────────────────────────────────────────────
        if (state == State.PENDING_SLAM) {
            if (slamTarget != null && slamTarget.isAlive() && slamSlot >= 0) {
                player.getInventory().selected = slamSlot;
                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null) {
                    mc.gameMode.attack(player, slamTarget);
                    player.swing(InteractionHand.MAIN_HAND);
                }
            }
            reset();
            return true;
        }

        // ── IDLE: check whether to initiate ──────────────────────────────────────────────────
        if (ElytraComboController.MACE_ITEM == null) return false;
        if (target == null || !target.tracked.hasLineOfSight) return false;
        if (!(target.tracked.entity instanceof LivingEntity living)) return false;
        if (!living.isBlocking()) return false;
        if (player.getAttackStrengthScale(0f) < MIN_STRENGTH) return false;

        // Breach mace preferred — best for grounded armoured targets
        int maceSlot = ElytraComboController.findMaceSlot(player, false);
        if (maceSlot < 0) return false;

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!InventoryLayout.isAxe(held)) {
            int axeSlot = InventoryLayout.findAxeSlot(player);
            if (axeSlot < 0) return false;
            player.getInventory().selected = axeSlot;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.attack(player, target.tracked.entity);
            player.swing(InteractionHand.MAIN_HAND);
        }

        slamTarget = target.tracked.entity;
        slamSlot   = maceSlot;
        state      = State.PENDING_SLAM;
        return true;
    }

    public void reset() {
        state      = State.IDLE;
        slamTarget = null;
        slamSlot   = -1;
    }
}
