package org.radarbase.android.util;

import androidx.annotation.NonNull;

import java.io.IOException;

public interface Parser<S, T> {
    T parse(@NonNull S type) throws IOException;
}
