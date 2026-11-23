package android.os;

/** Android's build information, for settings shared with the phone. A computer has no SDK level. */
public final class Build {
    private Build() {}

    public static final class VERSION {
        private VERSION() {}

        public static final int SDK_INT = 0;
    }
}
