package baritone.combat;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Smart potion usage — offensive splash throws and defensive self-drink buffs.
 *
 * ── Splash potions (thrown at enemy, within SPLASH_MAX_RANGE) ────────────────────
 *
 *   Priority and conditions:
 *
 *   1. Instant Damage (Harm)
 *      Best offensive throw — maximum burst damage per item.
 *      NEVER throw at undead (Zombie, Skeleton, etc.) — it HEALS them instead.
 *
 *   2. Slowness
 *      Always effective regardless of mob type.  Slows the target's attack frequency
 *      and makes it easier to maintain optimal spacing.
 *
 *   3. Weakness
 *      Reduces target's melee damage by 4 points.  Especially valuable against
 *      strong attackers (Vindicator, PiglinBrute) but useful universally.
 *
 *   4. Poison
 *      Damage-over-time.  Skip against undead (immune) and Witches (immune).
 *
 *   A SPLASH_COOLDOWN of 80 ticks (~4 s) prevents throwing a new potion until the
 *   previous one's primary effect has mostly elapsed.
 *
 * ── Self-drink buffs (consumed while pathing toward target, > ENGAGE_DISTANCE) ───
 *
 *   Strength — drink once when not already buffed.  Persists for 3+ minutes.
 *   Speed    — drink once when not already buffed.  Helps close the gap faster.
 *
 *   Drinking takes DRINK_HOLD_TICKS (32) consecutive ticks of held CLICK_RIGHT.
 *   During a drink the bot continues pathing so no time is wasted.  The drink is
 *   automatically abandoned when the target enters melee range (cancelDrink()).
 */
public final class PotionController {

    private static final float SPLASH_MAX_RANGE = 4.0f;
    private static final int   SPLASH_COOLDOWN  = 80;
    private static final int   DRINK_HOLD_TICKS = 32;

    private final IPlayerContext ctx;

    private int splashCooldown = 0;
    private int drinkTimer     = 0;
    private int drinkSlot      = -1;   // -1 = not drinking

    public PotionController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    // ── self-buff drinking ───────────────────────────────────────────────────────────────────

    /**
     * Manages self-buff potions while the bot is still pathing toward the target.
     * Continues an in-progress drink or starts a new one if the player lacks a buff.
     * Returns true while the CLICK_RIGHT input is being held for the drink.
     */
    public boolean tickSelfBuff(InputOverrideHandler input, Player player) {
        // Continue an in-progress drink
        if (drinkSlot >= 0) {
            player.getInventory().selected = drinkSlot;
            drinkTimer++;
            if (drinkTimer < DRINK_HOLD_TICKS) {
                input.setInputForceState(Input.CLICK_RIGHT, true);
                return true;
            }
            // Drink complete — potion consumed
            drinkTimer = 0;
            drinkSlot  = -1;
            return false;
        }

        // Strength — most impactful buff; try first
        if (!player.hasEffect(MobEffects.DAMAGE_BOOST)) {
            int slot = findDrinkable(player, MobEffects.DAMAGE_BOOST);
            if (slot >= 0) {
                drinkSlot  = slot;
                drinkTimer = 0;
                return tickSelfBuff(input, player);
            }
        }
        // Speed — helps close the gap and chase retreating targets
        if (!player.hasEffect(MobEffects.MOVEMENT_SPEED)) {
            int slot = findDrinkable(player, MobEffects.MOVEMENT_SPEED);
            if (slot >= 0) {
                drinkSlot  = slot;
                drinkTimer = 0;
                return tickSelfBuff(input, player);
            }
        }

        return false;
    }

    /** Abandon any in-progress drink (called when entering melee range). */
    public void cancelDrink() {
        drinkTimer = 0;
        drinkSlot  = -1;
    }

    public boolean isDrinking() {
        return drinkSlot >= 0;
    }

    // ── offensive splash throwing ────────────────────────────────────────────────────────────

    /**
     * Attempts to throw an offensive splash potion at the target.
     * Returns true the tick a throw is initiated; the caller should skip its normal
     * melee attack that tick since the slot and aim are overridden.
     */
    public boolean tickSplash(InputOverrideHandler input, ThreatEntry target, Player player) {
        if (splashCooldown > 0) {
            splashCooldown--;
            return false;
        }
        if (target == null) return false;

        float dist = (float) target.tracked.distance;
        if (dist > SPLASH_MAX_RANGE || !target.tracked.hasLineOfSight) return false;

        boolean undead = isUndead(target.tracked.entity);
        int slot = chooseSplash(player, target.tracked.entity, undead);
        if (slot < 0) return false;

        player.getInventory().selected = slot;
        aimAtFeet(target, player);
        input.setInputForceState(Input.CLICK_RIGHT, true);
        splashCooldown = SPLASH_COOLDOWN;
        return true;
    }

    // ── splash selection logic ───────────────────────────────────────────────────────────

    /**
     * Returns the hotbar slot of the best splash potion to throw given the target's
     * type.  Applies all "do not throw X at Y" rules before returning a slot.
     */
    private static int chooseSplash(Player player, Entity target, boolean undead) {
        // 1. Instant Damage — highest burst; skip against undead (heals them)
        if (!undead) {
            int s = findSplash(player, MobEffects.HARM);
            if (s >= 0) return s;
        }
        // 2. Slowness — universal; makes the target easier to kite and reduces DPS
        {
            int s = findSplash(player, MobEffects.MOVEMENT_SLOWDOWN);
            if (s >= 0) return s;
        }
        // 3. Weakness — reduces melee damage by 4; best vs. strong attackers, useful universally
        {
            int s = findSplash(player, MobEffects.WEAKNESS);
            if (s >= 0) return s;
        }
        // 4. Poison — DoT; skip undead (immune) and Witches (immune to their own brews)
        if (!undead && !(target instanceof Witch)) {
            int s = findSplash(player, MobEffects.POISON);
            if (s >= 0) return s;
        }
        return -1;
    }

    // ── hotbar scan helpers ────────────────────────────────────────────────────────────────

    private static int findSplash(Player player, MobEffect effect) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() == Items.SPLASH_POTION && hasEffect(s, effect)) return i;
        }
        return -1;
    }

    private static int findDrinkable(Player player, MobEffect effect) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() == Items.POTION && hasEffect(s, effect)) return i;
        }
        return -1;
    }

    private static boolean hasEffect(ItemStack stack, MobEffect target) {
        List<MobEffectInstance> effects = PotionUtils.getMobEffects(stack);
        for (MobEffectInstance e : effects) {
            if (e.getEffect() == target) return true;
        }
        return false;
    }

    private static boolean isUndead(Entity entity) {
        return entity instanceof LivingEntity living
            && living.getMobType() == MobType.UNDEAD;
    }

    // Aim at the target's feet — optimal landing point for maximum splash coverage
    private void aimAtFeet(ThreatEntry target, Player player) {
        Vec3  eye  = player.getEyePosition(1f);
        Vec3  feet = target.tracked.entity.position();
        Vec3  dir  = feet.subtract(eye).normalize();
        float yaw   = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));
        player.setYRot(yaw);   player.yRotO = yaw;
        player.setXRot(pitch); player.xRotO = pitch;
    }
}
