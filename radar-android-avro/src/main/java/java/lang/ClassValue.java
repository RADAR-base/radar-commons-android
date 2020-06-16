package java.lang;

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.WeakHashMap;

public abstract class ClassValue<T> {
    private final Map<Class<?>, SoftReference<T>> cache = new WeakHashMap<>();

    protected ClassValue() {
    }

    protected abstract T computeValue(Class<?> type);

    public T get(Class<?> type) {
        synchronized (this) {
            SoftReference<T> cachedRef = cache.get(type);
            if (cachedRef != null) {
                T cachedValue = cachedRef.get();
                if (cachedValue != null) {
                    return cachedValue;
                }
            }
        }

        T newValue = computeValue(type);

        synchronized (this) {
            SoftReference<T> previousRef = cache.put(type, new SoftReference<>(newValue));
            if (previousRef != null) {
                T previousValue = previousRef.get();
                if (previousValue != null) {
                    cache.put(type, previousRef);
                    return previousValue;
                }
            }
            return newValue;
        }
    }

    public synchronized void remove(Class<?> type) {
        cache.remove(type);
    }
}
