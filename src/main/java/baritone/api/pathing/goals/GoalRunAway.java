package baritone.api.pathing.goals;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;

/**
 * Satisfied when the player is at least minDistance blocks horizontally from
 * a danger position.  Heuristic is 0 when in goal and positive otherwise so
 * the pathfinder drives movement away from the danger point while still
 * navigating around obstacles (unlike a raw sprint-away input override).
 */
public class GoalRunAway implements Goal {

    private final int    dangerX, dangerZ;
    private final double minDistance;

    public GoalRunAway(BlockPos danger, double minDistance) {
        this(danger.getX(), danger.getZ(), minDistance);
    }

    public GoalRunAway(int dangerX, int dangerZ, double minDistance) {
        this.dangerX     = dangerX;
        this.dangerZ     = dangerZ;
        this.minDistance = minDistance;
    }

    @Override
    public boolean isInGoal(BetterBlockPos pos) {
        double dx = pos.x - dangerX;
        double dz = pos.z - dangerZ;
        return dx * dx + dz * dz >= minDistance * minDistance;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx = x - dangerX;
        double dz = z - dangerZ;
        double actualDist = Math.sqrt(dx * dx + dz * dz);
        return Math.max(0.0, minDistance - actualDist);
    }

    @Override
    public String toString() {
        return "GoalRunAway{from=(" + dangerX + "," + dangerZ + "), min=" + minDistance + "}";
    }
}
