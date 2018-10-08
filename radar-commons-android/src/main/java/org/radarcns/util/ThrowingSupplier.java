package org.radarcns.util;

import java.io.IOException;

public interface ThrowingSupplier<T> {
    T get() throws IOException;
}
