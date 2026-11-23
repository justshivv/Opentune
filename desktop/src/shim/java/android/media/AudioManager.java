package android.media;

/** Android's AudioManager, as far as the shared settings read it: the desktop lists no outputs. */
public class AudioManager {
    public static final int GET_DEVICES_OUTPUTS = 2;

    public AudioDeviceInfo[] getDevices(int flags) {
        return new AudioDeviceInfo[0];
    }
}
