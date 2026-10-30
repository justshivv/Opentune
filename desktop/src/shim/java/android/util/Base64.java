package android.util;

/** Android's Base64, as the shared remote-configuration code decodes with it, on java.util.Base64. */
public final class Base64 {
    private Base64() {}

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    public static byte[] decode(String text, int flags) {
        // Android's decoder takes either alphabet's padding and ignores line breaks; so does this.
        String clean = text.replaceAll("\\s", "");
        return (flags & URL_SAFE) != 0
            ? java.util.Base64.getUrlDecoder().decode(clean)
            : java.util.Base64.getMimeDecoder().decode(clean);
    }

    public static String encodeToString(byte[] bytes, int flags) {
        java.util.Base64.Encoder encoder = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) encoder = encoder.withoutPadding();
        return encoder.encodeToString(bytes);
    }
}
