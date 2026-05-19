package baritone.combat;

import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.awareness.AwarenessContext;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import baritone.api.utils.input.Input;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Handles fusing Creepers independently of the main combat loop.
 *
 * Strategy:
 *   As soon as a creeper's swellDir turns positive (any fuse detected), sprint
 *   directly away and optionally place a block between the bot and the creeper.
 *   This is simpler and more reliable than the old hit-window approach whose
 *   timing depended on accurate fuse accumulation.
 *
 * Fuse tracking:
 *   Uses c.getSwell() / c.getMaxSwell() directly rather than a per-tick
 *   accumulator.  This is accurate regardless of when the creeper entered
 *   the bot's tracking range.
 *
 * Explosion detection:
 *   When a creeper that was at >= 60 % fuse progress disappears, check
 *   entity.isAlive() to distinguish a true explosion from a creeper that
 *   merely stopped fusing and wandered away.  On a real explosion, continue
 *   sprinting from the last known position for POST_EXPLOSION_TICKS.
 */
public final class CreepeTactics {

    private static final float EXPLOSION_THRESHOLD  = 0.60f;
    private static final int   POST_EXPLOSION_TICKS = 20;

    private final IPlayerContext ctx;

    private final Map<Integer, Float> fuseProgress = new HashMap<>();
    private final Map<Integer, Vec3>  lastKnownPos = new HashMap<>();

    private Vec3 escapeFromPos    = null;
    private int  escapeTicksLeft  = 0;
    private int  blockPlaceCooldown = 0;

    public CreepeTactics(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** True while the post-explosion escape sprint is running. */
    public boolean hasPendingEscape() {
        return escapeTicksLeft > 0;
    }

    public PathingCommand tick(InputOverrideHandler input, AwarenessContext awarenessCtx) {
        if (blockPlaceCooldown > 0) blockPlaceCooldown--;

        // ── Post-explosion escape buffer ─────────────────────────────────────────
        if (escapeTicksLeft > 0) {
            escapeTicksLeft--;
            if (escapeFromPos != null) sprintAwayFromPos(input, escapeFromPos);
            return pause();
        }

        // ── Build active-fusing set this tick ────────────────────────────────────
        Set<Integer> activeFusingIds = new HashSet<>();
        Creeper closest = null;
        float   maxProg = 0f;

        for (ThreatEntry t : awarenessCtx.getThreats()) {
            if (!(t.tracked.entity instanceof Creeper c)) continue;
            int id = c.getId();

            if (c.getSwellDir() > 0) {
                float progress = (float) c.getSwell() / (float) c.getMaxSwell();
                activeFusingIds.add(id);
                lastKnownPos.put(id, c.position());
                fuseProgress.put(id, progress);

                if (progress > maxProg) {
                    maxProg  = progress;
                    closest  = c;
                }
            }
        }

        // ── Detect true explosions: was fusing at high progress, entity now gone ──
        for (Map.Entry<Integer, Float> entry : new HashMap<>(fuseProgress).entrySet()) {
            int   id       = entry.getKey();
            float progress = entry.getValue();
            if (!activeFusingIds.contains(id) && progress >= EXPLOSION_THRESHOLD) {
                // Distinguish explosion (entity gone/dead) from a creeper that just
                // stopped fusing — the latter is still alive in the world.
                Entity ent = ctx.world().getEntity(id);
                if (ent == null || !ent.isAlive()) {
                    Vec3 pos = lastKnownPos.get(id);
                    if (pos != null) {
                        escapeFromPos   = pos;
                        escapeTicksLeft = POST_EXPLOSION_TICKS;
                    }
                }
            }
        }

        // Clean up creepers that are no longer fusing
        fuseProgress.keySet().retainAll(activeFusingIds);
        lastKnownPos.keySet().retainAll(activeFusingIds);

        // ── Sprint away immediately on any active fuse ────────────────────────────
        if (closest != null) {
            sprintAwayFrom(input, closest);
            tryPlaceShieldBlock(closest);
            return pause();
        }

        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void sprintAwayFrom(InputOverrideHandler input, Creeper creeper) {
        sprintAwayFromPos(input, creeper.position());
    }

    private void sprintAwayFromPos(InputOverrideHandler input, Vec3 dangerPos) {
        Player player = ctx.player();
        if (player == null) return;
        Vec3 away = player.position().subtract(dangerPos);
        if (away.lengthSqr() < 0.001) away = new Vec3(1, 0, 0);
        away = new Vec3(away.x, 0, away.z).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
        player.setYRot(yaw);  player.yRotO = yaw;
        player.setXRot(10f);  player.xRotO = 10f;
        input.setInputForceState(Input.SPRINT,       true);
        input.setInputForceState(Input.MOVE_FORWARD, true);
    }

    private void tryPlaceShieldBlock(Creeper creeper) {
        if (blockPlaceCooldown > 0) return;
        Player player = ctx.player();
        if (player == null) return;

        Vec3 toCreeper = creeper.position().subtract(player.position());
        if (toCreeper.lengthSqr() < 0.001) return;
        Vec3 dir = new Vec3(toCreeper.x, 0, toCreeper.z).normalize();

        BlockPos place = new BlockPos(
            (int) Math.floor(player.getX() + dir.x),
            (int) Math.floor(player.getY()),
            (int) Math.floor(player.getZ() + dir.z)
        );

        if (!ctx.world().getBlockState(place).isAir()) return;

        BlockPos support = place.below();
        if (ctx.world().getBlockState(support).isAir()) return;

        int blockSlot = findBlockSlot(player);
        if (blockSlot < 0) return;

        int prevSlot = player.getInventory().selected;
        player.getInventory().selected = blockSlot;

        Vec3 hitVec = Vec3.atCenterOf(support).add(0, 0.5, 0);
        ctx.playerController().processRightClickBlock(
            ctx.player(),
            ctx.world(),
            InteractionHand.MAIN_HAND,
            new BlockHitResult(hitVec, Direction.UP, support, false)
        );

        player.getInventory().selected = prevSlot;
        blockPlaceCooldown = 5;
    }

    private int findBlockSlot(Player player) {
        if (isPlaceable(player.getInventory().getItem(InventoryLayout.SLOT_BLOCKS))) {
            return InventoryLayout.SLOT_BLOCKS;
        }
        for (int i = 0; i < 9; i++) {
            if (isPlaceable(player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private boolean isPlaceable(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    private static PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
}
