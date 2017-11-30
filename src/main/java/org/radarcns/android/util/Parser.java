package org.radarcns.android.util;

import android.support.annotation.NonNull;
import okhttp3.Response;
import org.radarcns.producer.rest.RestClient;

import java.io.IOException;

public interface Parser<S, T> {
    T parse(@NonNull S type) throws IOException;
}
