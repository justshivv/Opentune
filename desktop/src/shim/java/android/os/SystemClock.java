package android.os;

/** Android's monotonic clock, for the shared code's "not more often than" checks. */
public final class SystemClock {
    private SystemClock() {}

    public static long elapsedRealtime() {
        return System.nanoTime() / 1_000_000L;
    }
}
