package baritone.combat;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.awareness.model.SelfState;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shield and off-hand inventory management.
 *
 * Detection:
 *   isOwnShieldBroken()  — true when our shield has the 5-second disable cooldown
 *                            from being hit by an axe.  CombatEngine uses this to
 *                            trigger pearl escapes and suppress shield-raise reflexes.
 *
 * Off-hand management (singleplayer / host only — uses direct inventory access):
 *   Swap off-hand to Totem when HP < 4 hearts so it auto-activates on near-death.
 *   Swap back to Shield when HP recovers above 8 hearts.
 *
 * Reactive shield raising (CLICK_RIGHT) is handled by ReactionSystem, not here,
 * except for the mace-fall defense which overrides it for ~20 ticks.
 */
public final class ShieldController {

    private static final float TOTEM_SWAP_HP     = 4f;
    private static final float SHIELD_RESTORE_HP = 8f;
    private static final int   MACE_DEFENSE_TICKS = 20;

    // Resolve the Mace item at runtime so this compiles on 1.19.x where it doesn't exist yet.
    // On 1.21.2+ where the Mace was added, this will be non-null and the defense triggers.
    private static final Item MACE_ITEM;
    static {
        Item mace = null;
        try { mace = (Item) Items.class.getField("MACE").get(null); }
        catch (Exception ignored) {}
        MACE_ITEM = mace;
    }

    private final IPlayerContext ctx;
    private int maceDefenseCooldown = 0;

    public ShieldController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** True when our shield was disabled by an enemy axe (5-second cooldown). */
    public boolean isOwnShieldBroken() {
        Player player = ctx.player();
        return player != null && player.getCooldowns().isOnCooldown(Items.SHIELD);
    }

    /**
     * Manages what lives in the off-hand slot.
     * Direct inventory mutation — works in singleplayer / as world host.
     * Multiplayer requires ServerboundContainerClickPacket (future work).
     */
    public void manageOffHand(SelfState self) {
        Player player = ctx.player();
        if (player == null) return;

        ItemStack offhand = player.getInventory().offhand.get(0);
        boolean offhandIsTotem  = offhand.getItem() == Items.TOTEM_OF_UNDYING;
        boolean offhandIsShield = offhand.getItem() == Items.SHIELD;

        if (self.health < TOTEM_SWAP_HP && self.hasTotem && !offhandIsTotem) {
            swapToOffhand(player, Items.TOTEM_OF_UNDYING);
        } else if (self.health >= SHIELD_RESTORE_HP && !offhandIsShield) {
            // Restore shield whether offhand has totem or became empty after totem was consumed
            swapToOffhand(player, Items.SHIELD);
        }
    }

    /**
     * Raises the shield automatically when the target is in free-fall with a Mace
     * (wind charge or jump-slam attack). Holds for MACE_DEFENSE_TICKS after the
     * dive is detected so the shield is up when the hit lands.
     */
    public void tickDefense(ThreatEntry target, InputOverrideHandler input) {
        if (isOwnShieldBroken()) {
            maceDefenseCooldown = 0;
            return;
        }
        if (target != null && MACE_ITEM != null) {
            Entity entity = target.tracked.entity;
            if (entity instanceof LivingEntity living) {
                boolean holdingMace = living.getMainHandItem().getItem() == MACE_ITEM;
                boolean divingFast  = !entity.isOnGround()
                                   && entity.getDeltaMovement().y < -0.08;
                if (holdingMace && divingFast) {
                    maceDefenseCooldown = MACE_DEFENSE_TICKS;
                }
            }
        }
        if (maceDefenseCooldown > 0) {
            input.setInputForceState(Input.CLICK_RIGHT, true);
            maceDefenseCooldown--;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private void swapToOffhand(Player player, net.minecraft.world.item.Item targetType) {
        // Find the item in the main inventory (slots 0-35)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || stack.getItem() != targetType) continue;
            ItemStack current = player.getInventory().offhand.get(0);
            player.getInventory().offhand.set(0, stack.copy());
            player.getInventory().setItem(i, current);
            return;
        }
    }
}
