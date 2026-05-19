package baritone.combat;

import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra + mace dive-bomb combos and wind-charge utility.
 *
 * All 1.21+ item references (Mace, Wind Charge) are resolved via reflection so
 * this class compiles and runs on 1.19.4 — features stay inactive when absent.
 *
 * Dive-bomb sequence (elytra equipped + mace in hotbar):
 *   LAUNCHING     — throw Wind Charge at own feet to rocket upward (if available).
 *                   Skipped if no wind charges.
 *   ELYTRA_ACTIVE — once airborne and descending, JUMP to activate glide.
 *                   Fire one Firework Rocket boost if target > 6 m away.
 *                   Steer toward target.
 *   DIVE_ATTACK   — falling faster than DIVE_THRESHOLD m/tick → select mace and
 *                   return null, letting CombatEngine's normal attack flow land
 *                   the smash (AttackValidator + SpacingController still run).
 *   RECOVERING    — 20-tick cooldown after landing (handles Wind Burst bounce).
 *
 * Wind-charge enemy knock-up (wind charges + mace, target ≤ KNOCKUP_RANGE):
 *   Throw Wind Charge at enemy → disrupts / launches them → follow up with mace.
 *   If elytra is equipped the controller immediately enters the dive sequence after
 *   the throw so we're airborne for the smash follow-up.
 */
public final class ElytraComboController {

    // ── 1.21+ items via reflection (null on earlier versions) ────────────────────────
    static final Item MACE_ITEM;
    static final Item WIND_CHARGE_ITEM;
    static {
        Item mace = null, wc = null;
        try { mace = (Item) Items.class.getField("MACE").get(null);         } catch (Exception ignored) {}
        try { wc   = (Item) Items.class.getField("WIND_CHARGE").get(null);  } catch (Exception ignored) {}
        MACE_ITEM        = mace;
        WIND_CHARGE_ITEM = wc;
    }

    private enum Phase { IDLE, LAUNCHING, ELYTRA_ACTIVE, DIVE_ATTACK, RECOVERING }

    private static final double DIVE_THRESHOLD = -0.5;   // m/tick falling speed for mace switch
    private static final float  KNOCKUP_RANGE  = 5.0f;
    private static final float  DIVE_MIN_DIST  = 6.0f;   // don't initiate dive if already in melee
    private static final int    RECOVER_TICKS  = 20;
    private static final int    WC_COOLDOWN    = 20;      // ticks between wind-charge throws

    private final IPlayerContext ctx;

    private Phase   phase        = Phase.IDLE;
    private int     recoverTimer = 0;
    private int     wcCooldown   = 0;
    private boolean boosted      = false;  // firework rocket fired this dive

    public ElytraComboController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns a PathingCommand to override normal navigation, or null when the
     * controller is idle or in DIVE_ATTACK phase (the latter falls through to
     * CombatEngine's normal attack flow so AttackValidator can fire the smash).
     *
     * Callers must check isActive() when selecting weapons so the mace slot is
     * preserved during the dive and recovery window.
     */
    public PathingCommand tick(InputOverrideHandler input, ThreatEntry target) {
        Player player = ctx.player();
        if (player == null || target == null || !target.tracked.entity.isAlive()) {
            reset();
            return null;
        }
        if (wcCooldown > 0) wcCooldown--;

        // ── RECOVERING ───────────────────────────────────────────────────────────────
        if (phase == Phase.RECOVERING) {
            if (--recoverTimer <= 0) phase = Phase.IDLE;
            return pause();
        }

        // ── LAUNCHING: throw wind charge at own feet to rocket upward ────────────────
        if (phase == Phase.LAUNCHING) {
            int wcSlot = findWindChargeSlot(player);
            if (wcSlot >= 0 && wcCooldown == 0) {
                player.setXRot(89f); player.xRotO = 89f;  // aim steeply downward
                player.getInventory().selected = wcSlot;
                input.setInputForceState(Input.CLICK_RIGHT, true);
                wcCooldown = WC_COOLDOWN;
            }
            phase = Phase.ELYTRA_ACTIVE;
            return pause();
        }

        // ── ELYTRA_ACTIVE: glide toward target; transition to dive when fast enough ──
        if (phase == Phase.ELYTRA_ACTIVE) {
            if (!hasElytraEquipped(player)) { reset(); return null; }

            if (player.getDeltaMovement().y < DIVE_THRESHOLD) {
                phase = Phase.DIVE_ATTACK;
                return null;  // fall through to normal attack flow
            }

            // Press JUMP to activate glide once airborne and starting to descend
            if (!player.isOnGround() && !player.isFallFlying()
                    && player.getDeltaMovement().y <= 0.0) {
                input.setInputForceState(Input.JUMP, true);
            }

            // One-shot firework rocket boost when we first start gliding
            if (player.isFallFlying() && !boosted) {
                float dist = (float) target.tracked.distance;
                int fwSlot = findFireworkSlot(player);
                if (dist > DIVE_MIN_DIST && fwSlot >= 0) {
                    player.getInventory().selected = fwSlot;
                    input.setInputForceState(Input.CLICK_RIGHT, true);
                    boosted = true;
                    return pause();
                }
                boosted = true;  // no rocket available — mark done
            }

            if (player.isFallFlying()) aimAtTarget(target, player);
            return pause();
        }

        // ── DIVE_ATTACK: mace selected; normal flow handles the actual hit ────────────
        if (phase == Phase.DIVE_ATTACK) {
            int maceSlot = findMaceSlot(player);
            if (maceSlot >= 0) player.getInventory().selected = maceSlot;
            if (player.isOnGround() || player.getDeltaMovement().y > -0.1) {
                phase        = Phase.RECOVERING;
                recoverTimer = RECOVER_TICKS;
            }
            return null;  // let AttackValidator + SpacingController handle the smash
        }

        // ── IDLE: decide whether and how to initiate ──────────────────────────────────
        if (MACE_ITEM == null) return null;  // no mace available on this MC version

        float dist = (float) target.tracked.distance;

        // Wind-charge enemy knock-up: throw at enemy to disrupt + launch them.
        // If elytra is equipped, immediately enter dive sequence for a smash follow-up.
        if (dist <= KNOCKUP_RANGE && WIND_CHARGE_ITEM != null && wcCooldown == 0
                && target.tracked.hasLineOfSight) {
            int wcSlot   = findWindChargeSlot(player);
            int maceSlot = findMaceSlot(player);
            if (wcSlot >= 0 && maceSlot >= 0) {
                aimAtTarget(target, player);
                player.getInventory().selected = wcSlot;
                input.setInputForceState(Input.CLICK_RIGHT, true);
                wcCooldown = WC_COOLDOWN;
                // With elytra, immediately begin the dive to follow up from the air
                if (hasElytraEquipped(player)) {
                    boosted = false;
                    phase   = Phase.LAUNCHING;
                }
                return pause();
            }
        }

        // Dive-bomb: elytra equipped, mace in hotbar, target far enough to gain altitude
        if (hasElytraEquipped(player) && findMaceSlot(player) >= 0 && dist > DIVE_MIN_DIST) {
            boosted = false;
            boolean hasWC = WIND_CHARGE_ITEM != null && findWindChargeSlot(player) >= 0;
            phase = hasWC ? Phase.LAUNCHING : Phase.ELYTRA_ACTIVE;
            return tick(input, target);  // re-enter to execute the new phase immediately
        }

        // Already gliding with mace — take over guidance
        if (hasElytraEquipped(player) && player.isFallFlying() && findMaceSlot(player) >= 0) {
            boosted = true;  // already in the air, skip rocket
            phase   = Phase.ELYTRA_ACTIVE;
            return tick(input, target);
        }

        return null;
    }

    public void reset() {
        phase        = Phase.IDLE;
        recoverTimer = 0;
        boosted      = false;
    }

    /**
     * True when the mace slot must be preserved (DIVE_ATTACK or RECOVERING).
     * CombatEngine checks this before letting WeaponSelector change the hotbar slot.
     */
    public boolean isActive() {
        return phase == Phase.DIVE_ATTACK || phase == Phase.RECOVERING;
    }

    // ── static helpers ────────────────────────────────────────────────────────────────

    public static int findMaceSlot(Player player) {
        if (MACE_ITEM == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() == MACE_ITEM) return i;
        }
        return -1;
    }

    public static int findWindChargeSlot(Player player) {
        if (WIND_CHARGE_ITEM == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() == WIND_CHARGE_ITEM) return i;
        }
        return -1;
    }

    public static int findFireworkSlot(Player player) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() == Items.FIREWORK_ROCKET) return i;
        }
        return -1;
    }

    public static boolean hasElytraEquipped(Player player) {
        return player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    private void aimAtTarget(ThreatEntry target, Player player) {
        Vec3  eye  = player.getEyePosition(1f);
        Vec3  tEye = target.tracked.entity.getEyePosition(1f);
        Vec3  dir  = tEye.subtract(eye).normalize();
        float yaw   = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dir.y))));
        player.setYRot(yaw);   player.yRotO = yaw;
        player.setXRot(pitch); player.xRotO = pitch;
    }

    private static PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
}
