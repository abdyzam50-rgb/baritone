package baritone.combat;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;

/**
 * Smart potion usage — offensive splash throws and defensive self-drink buffs.
 *
 * ── Splash potions (thrown at enemy, within SPLASH_MAX_RANGE) ────────────────────
 *
 *   Priority and conditions:
 *
 *   1. Instant Damage (Harm)
 *      NEVER throw at undead — it HEALS them instead.
 *
 *   2. Slowness — always effective regardless of mob type.
 *
 *   3. Weakness — reduces melee damage by 4 points.
 *
 *   4. Poison — DoT; skip undead (immune) and Witches (immune).
 *
 *   A SPLASH_COOLDOWN of 80 ticks (~4 s) prevents back-to-back throws.
 *
 * ── Self-drink buffs (consumed while pathing toward target) ──────────────────────
 *
 *   Strength and Speed are each drunk once when the effect is absent.
 *   Drinking takes DRINK_HOLD_TICKS (32) consecutive ticks of held CLICK_RIGHT.
 */
public final class PotionController {

    private static final float SPLASH_MAX_RANGE = 4.0f;
    private static final int   SPLASH_COOLDOWN  = 80;
    private static final int   DRINK_HOLD_TICKS = 32;

    private final IPlayerContext ctx;

    private int splashCooldown = 0;
    private int drinkTimer     = 0;
    private int drinkSlot      = -1;

    public PotionController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    // ── self-buff drinking ───────────────────────────────────────────────────────────

    public boolean tickSelfBuff(InputOverrideHandler input, Player player) {
        if (drinkSlot >= 0) {
            player.getInventory().selected = drinkSlot;
            drinkTimer++;
            if (drinkTimer < DRINK_HOLD_TICKS) {
                input.setInputForceState(Input.CLICK_RIGHT, true);
                return true;
            }
            drinkTimer = 0;
            drinkSlot  = -1;
            return false;
        }

        if (!player.hasEffect(MobEffects.STRENGTH)) {
            int slot = findDrinkable(player, MobEffects.STRENGTH);
            if (slot >= 0) { drinkSlot = slot; drinkTimer = 0; return tickSelfBuff(input, player); }
        }
        if (!player.hasEffect(MobEffects.SPEED)) {
            int slot = findDrinkable(player, MobEffects.SPEED);
            if (slot >= 0) { drinkSlot = slot; drinkTimer = 0; return tickSelfBuff(input, player); }
        }
        return false;
    }

    public void cancelDrink() { drinkTimer = 0; drinkSlot = -1; }
    public boolean isDrinking() { return drinkSlot >= 0; }

    // ── offensive splash throwing ────────────────────────────────────────────────────

    public boolean tickSplash(InputOverrideHandler input, ThreatEntry target, Player player) {
        if (splashCooldown > 0) { splashCooldown--; return false; }
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

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private static int chooseSplash(Player player, Entity target, boolean undead) {
        if (!undead) { int s = findSplash(player, MobEffects.HARM);      if (s >= 0) return s; }
        {             int s = findSplash(player, MobEffects.SLOWNESS);   if (s >= 0) return s; }
        {             int s = findSplash(player, MobEffects.WEAKNESS);   if (s >= 0) return s; }
        if (!undead && !(target instanceof Witch)) {
                      int s = findSplash(player, MobEffects.POISON);    if (s >= 0) return s; }
        return -1;
    }

    private static int findSplash(Player player, Holder<MobEffect> effect) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() == Items.SPLASH_POTION && hasEffect(s, effect)) return i;
        }
        return -1;
    }

    private static int findDrinkable(Player player, Holder<MobEffect> effect) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() == Items.POTION && hasEffect(s, effect)) return i;
        }
        return -1;
    }

    private static boolean hasEffect(ItemStack stack, Holder<MobEffect> target) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        for (MobEffectInstance e : contents.getAllEffects()) {
            if (e.getEffect().is(target)) return true;
        }
        return false;
    }

    private static boolean isUndead(Entity entity) {
        return entity instanceof LivingEntity living
            && living.getType().is(EntityTypeTags.UNDEAD);
    }

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
