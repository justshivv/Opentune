package android.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Android's lint suppression, which means nothing on the desktop. */
@Retention(RetentionPolicy.SOURCE)
public @interface SuppressLint {
    String[] value();
}
