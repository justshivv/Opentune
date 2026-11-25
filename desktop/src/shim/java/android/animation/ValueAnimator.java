package android.animation;

/**
 * The one call the shared interface files make: whether the system has animations removed.
 * The desktop has no such switch, so animations are always on.
 */
public final class ValueAnimator {
    private ValueAnimator() {}

    public static boolean areAnimatorsEnabled() {
        return true;
    }
}
