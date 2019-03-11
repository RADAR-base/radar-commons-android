package org.radarbase.util;

import java.io.IOException;

public interface ThrowingSupplier<T> {
    T get() throws IOException;
}
