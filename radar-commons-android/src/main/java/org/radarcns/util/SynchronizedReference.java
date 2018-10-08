package org.radarcns.util;

import java.io.IOException;
import java.lang.ref.WeakReference;

public abstract class SynchronizedReference<T> {
    private WeakReference<T> ref;

    public SynchronizedReference() {
        ref = null;
    }

    public synchronized T get() throws IOException {
        T localValue = null;
        if (ref != null) {
            localValue = ref.get();
        }
        if (localValue == null) {
            localValue = compute();
            ref = new WeakReference<>(localValue);
        }
        return localValue;
    }

    protected abstract T compute() throws IOException;
}
