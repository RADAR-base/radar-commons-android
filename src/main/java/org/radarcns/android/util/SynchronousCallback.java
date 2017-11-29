package org.radarcns.android.util;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import org.radarcns.producer.rest.RestClient;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class SynchronousCallback<V> implements Callback {
    private final Parser<String, V> parser;
    private V result;
    private boolean isDone;
    private IOException exception;

    public SynchronousCallback(Parser<String, V> parser) {
        this.parser = parser;
        this.isDone = false;
    }

    @Override
    public synchronized void onFailure(Call call, IOException ex) {
        exception = ex == null ? new IOException() : ex;
        isDone = true;
        notifyAll();
    }

    @Override
    public void onResponse(Call call, Response response) {
        try {
            String body = RestClient.responseBody(response);

            if (!response.isSuccessful()) {
                onFailure(call, new IOException("Failed to make request; response " + body));
            } else if (body == null || body.isEmpty()) {
                onFailure(call, new IOException("Response body expected but not found"));
            } else {
                V localResult = parser.parse(body);
                synchronized (this) {
                    result = localResult;
                    isDone = true;
                    notifyAll();
                }
            }
        } catch (IOException ex) {
            onFailure(call, ex);
        }
    }

    public synchronized V get() throws IOException, InterruptedException {
        while (!isDone) {
            wait();
        }
        if (exception != null) {
            throw exception;
        } else {
            return result;
        }
    }
}
