package baritone.combat;

import baritone.api.utils.input.Input;
import baritone.awareness.AwarenessContext;
import baritone.awareness.model.ThreatEntry;
import baritone.utils.InputOverrideHandler;

/**
 * Controls movement while CombatEngine is in direct-input mode (target within 4.5 m).
 *
 * Zones:
 *   TOO_CLOSE  < 2.0 m  — back off, no sprint, strafe
 *   OPTIMAL  2.5–4.0 m  — sprint forward, W-tap before each hit, strafe
 *   CLOSING    > 4.0 m  — sprint forward only (should be handed to Baritone, but
 *                         handled here as a safety net)
 *
 * Strafe direction flips every 20-40 ticks (randomised) to avoid predictable patterns.
 * Flip frequency adapts based on target approach speed.
 */
public final class SpacingController {

    private static final float TOO_CLOSE    = 2.0f;
    private static final float OPTIMAL_MAX  = 4.0f;

    // Strafe state
    private int   strafeDir   = 1;  // 1 = left, -1 = right
    private int   strafeTimer = 0;
    private int   nextFlip    = 30;

    // Approach tracking for adaptive strafing
    private float lastDist = -1;

    // W-tap: set true to drop sprint/forward for exactly 1 tick
    boolean wTapThisTick = false;

    public void tick(InputOverrideHandler input, ThreatEntry target, AwarenessContext ctx) {
        float dist = (float) target.tracked.distance;

        // Adapt strafe frequency based on whether target is closing or retreating.
        // When they charge (distance shrinking fast) circle-strafe more aggressively
        // to avoid their attack arc. When they retreat, strafe less and close gap.
        if (lastDist > 0) {
            float delta = lastDist - dist; // positive = we're closing / target approaching
            if (delta > 0.15f) {
                // Target approaching — tighten the strafe cycle
                nextFlip = Math.max(10, nextFlip - 1);
            } else if (delta < -0.1f) {
                // Target retreating — relax strafe, prioritise forward sprint
                nextFlip = Math.min(40, nextFlip + 1);
            }
        }
        lastDist = dist;

        strafeTimer++;
        if (strafeTimer >= nextFlip) {
            strafeDir   *= -1;
            strafeTimer  = 0;
            nextFlip     = 20 + (int) (Math.random() * 20);
        }

        if (dist < TOO_CLOSE) {
            input.setInputForceState(Input.MOVE_BACK, true);
            input.setInputForceState(Input.SPRINT, false);
            strafe(input);
        } else if (dist <= OPTIMAL_MAX) {
            if (wTapThisTick) {
                // Drop W + sprint for this tick (the W-tap itself)
                input.setInputForceState(Input.MOVE_FORWARD, false);
                input.setInputForceState(Input.SPRINT, false);
                wTapThisTick = false;
            } else {
                input.setInputForceState(Input.MOVE_FORWARD, true);
                input.setInputForceState(Input.SPRINT, true);
            }
            strafe(input);
        } else {
            input.setInputForceState(Input.MOVE_FORWARD, true);
            input.setInputForceState(Input.SPRINT, true);
        }
    }

    /** Called by AttackValidator the tick before a hit lands to schedule a W-tap. */
    void scheduleWTap() {
        wTapThisTick = true;
    }

    private void strafe(InputOverrideHandler input) {
        if (strafeDir > 0) {
            input.setInputForceState(Input.MOVE_LEFT, true);
        } else {
            input.setInputForceState(Input.MOVE_RIGHT, true);
        }
    }
}
