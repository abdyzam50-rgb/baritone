package baritone.combat;

import baritone.awareness.AwarenessContext;
import baritone.awareness.model.ThreatEntry;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Decides which hotbar slot to occupy each combat tick.
 *
 * Default: sword (slot 0 or first found).
 *
 * Switch to axe when primary target is actively blocking — an axe hit disables
 * the shield for 100 ticks (~5 seconds). We detect the break by observing the
 * target transition from blocking to not blocking immediately after we swung.
 *
 * AXE_HOLD_TICKS: stay on axe a few extra ticks after the block drops so the
 * break packet has time to register on the server before we switch back to sword.
 *
 * EXPLOIT_TICKS: count down the vulnerability window.  isInExploitWindow()
 * lets AttackValidator know to be aggressive (don't wait for crits).
 */
public final class WeaponSelector {

    private static final int AXE_HOLD_TICKS   = 3;
    public  static final int EXPLOIT_TICKS    = 100;

    private boolean prevTargetBlocking = false;
    private int     axeHoldTimer       = 0;
    private int     exploitTimer       = 0;

    public int select(Player player, AwarenessContext ctx) {
        ThreatEntry primary = ctx.getPrimaryThreat();

        boolean targetBlocking = false;
        if (primary != null && primary.tracked.entity.isAlive()
                && primary.tracked.entity instanceof LivingEntity) {
            targetBlocking = ((LivingEntity) primary.tracked.entity).isBlocking();
        }

        // Detect block-drop transition: was blocking → no longer blocking after axe swing
        if (prevTargetBlocking && !targetBlocking && axeHoldTimer > 0) {
            exploitTimer = EXPLOIT_TICKS;
        }
        prevTargetBlocking = targetBlocking;

        if (axeHoldTimer  > 0) axeHoldTimer--;
        if (exploitTimer  > 0) exploitTimer--;

        if (targetBlocking) {
            // Enemy has shield up — switch to axe
            int axeSlot = InventoryLayout.findAxeSlot(player);
            if (axeSlot >= 0) {
                axeHoldTimer = AXE_HOLD_TICKS;
                return axeSlot;
            }
        }

        if (axeHoldTimer > 0) {
            // Hold axe briefly after block drops so break registers
            int axeSlot = InventoryLayout.findAxeSlot(player);
            if (axeSlot >= 0) return axeSlot;
        }

        // DPS-based selection: pick the weapon with best damage-per-second.
        // Swords win DPS in equal-tier matchups, but a netherite axe beats a wooden sword.
        int bestSlot = -1;
        double bestDps = 0;
        for (int i = 0; i < 9; i++) {
            double dps = weaponDps(player.getInventory().getItem(i));
            if (dps > bestDps) { bestDps = dps; bestSlot = i; }
        }
        if (bestSlot >= 0) return bestSlot;

        return player.getInventory().selected;
    }

    /** True during the ~5-second window after we break an enemy's shield. */
    public boolean isInExploitWindow() {
        return exploitTimer > 0;
    }

    /**
     * Estimates damage-per-second for a weapon stack using known base stats.
     * DPS = (base item damage + 1 player base) × (attack speed in attacks/second).
     * Returns 0 for non-weapons.
     */
    private static double weaponDps(ItemStack s) {
        if (s.isEmpty()) return 0;
        Item it = s.getItem();
        // Swords — all attack speed 1.6/s
        if (it == Items.NETHERITE_SWORD) return 9.0 * 1.6;
        if (it == Items.DIAMOND_SWORD)   return 8.0 * 1.6;
        if (it == Items.IRON_SWORD)      return 7.0 * 1.6;
        if (it == Items.STONE_SWORD)     return 6.0 * 1.6;
        if (it == Items.GOLDEN_SWORD || it == Items.WOODEN_SWORD) return 5.0 * 1.6;
        // Axes — higher burst, lower speed
        if (it == Items.NETHERITE_AXE)   return 11.0 * 1.0;
        if (it == Items.DIAMOND_AXE)     return 10.0 * 1.0;
        if (it == Items.IRON_AXE)        return 10.0 * 0.9;
        if (it == Items.STONE_AXE)       return 10.0 * 0.8;
        if (it == Items.GOLDEN_AXE || it == Items.WOODEN_AXE) return 8.0 * 0.8;
        return 0;
    }
}
