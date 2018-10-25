package org.radarcns.util;

import java.io.IOException;
import java.lang.ref.WeakReference;

public class SynchronizedReference<T> {
    private final ThrowingSupplier<T> supplier;
    private WeakReference<T> ref;

    public SynchronizedReference(ThrowingSupplier<T> supplier) {
        ref = null;
        this.supplier = supplier;
    }

    public synchronized T get() throws IOException {
        T localValue = null;
        if (ref != null) {
            localValue = ref.get();
        }
        if (localValue == null) {
            localValue = supplier.get();
            ref = new WeakReference<>(localValue);
        }
        return localValue;
    }
}
