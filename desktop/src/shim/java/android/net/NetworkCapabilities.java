package android.net;

/** See [ConnectivityManager]. */
public final class NetworkCapabilities {
    public static final int NET_CAPABILITY_NOT_METERED = 11;
    public static final int NET_CAPABILITY_INTERNET = 12;
    public static final int NET_CAPABILITY_VALIDATED = 16;

    private NetworkCapabilities() {}

    public boolean hasCapability(int capability) { return true; }
}
