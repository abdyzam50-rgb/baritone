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
 *   CLOSING    > 4.0 m  — sprint forward only
 *
 * Strafe direction flips every 20-40 ticks (randomised) to avoid predictable patterns.
 * Flip frequency adapts based on target approach speed.
 *
 * W-tap: sprint/forward dropped for exactly 1 tick after each attack to maximise
 * knockback and immediately re-enable sprint (called by AttackValidator).
 *
 * S-tap: MOVE_BACK pressed for exactly 1 tick before an attack when a ceiling
 * blocks the jump-crit; breaks sprint momentum so knockback direction is clean.
 */
public final class SpacingController {

    private static final float TOO_CLOSE   = 2.0f;
    private static final float OPTIMAL_MAX = 4.0f;

    // Strafe state
    private int   strafeDir   = 1;  // 1 = left, -1 = right
    private int   strafeTimer = 0;
    private int   nextFlip    = 30;

    // Approach tracking for adaptive strafing
    private float lastDist = -1;

    // Single-tick flags set by AttackValidator
    boolean wTapThisTick = false;
    boolean sTapThisTick = false;

    public void tick(InputOverrideHandler input, ThreatEntry target, AwarenessContext ctx) {
        float dist = (float) target.tracked.distance;

        if (lastDist > 0) {
            float delta = lastDist - dist;
            if (delta > 0.15f) {
                nextFlip = Math.max(10, nextFlip - 1);
            } else if (delta < -0.1f) {
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

        // S-tap takes priority — drop forward/sprint and press back for one tick
        if (sTapThisTick) {
            input.setInputForceState(Input.MOVE_BACK,    true);
            input.setInputForceState(Input.MOVE_FORWARD, false);
            input.setInputForceState(Input.SPRINT,       false);
            sTapThisTick = false;
            strafe(input);
            return;
        }

        if (dist < TOO_CLOSE) {
            input.setInputForceState(Input.MOVE_BACK, true);
            input.setInputForceState(Input.SPRINT, false);
            strafe(input);
        } else if (dist <= OPTIMAL_MAX) {
            if (wTapThisTick) {
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

    /** Called by AttackValidator the tick it fires an attack to reset sprint. */
    void scheduleWTap() { wTapThisTick = true; }

    /** Called by AttackValidator when a ceiling blocks jump-crit; breaks sprint before the hit. */
    void scheduleSTap() { sTapThisTick = true; }

    private void strafe(InputOverrideHandler input) {
        if (strafeDir > 0) {
            input.setInputForceState(Input.MOVE_LEFT, true);
        } else {
            input.setInputForceState(Input.MOVE_RIGHT, true);
        }
    }
}
