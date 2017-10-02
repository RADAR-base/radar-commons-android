/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.android.kafka;

import android.os.Handler;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.producer.KafkaSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks the connection of a sender.
 *
 * It does so using two mechanisms: a regular heartbeatInterval signal when the connection is assumed to be
 * present, and a exponential back-off mechanism if the connection is severed. If the connection is
 * assessed to be present through another mechanism, {@link #didConnect()} should be called,
 * conversely, if it is assessed to be severed, {@link #didDisconnect(IOException)} should be
 * called.
 */
class KafkaConnectionChecker implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(KafkaConnectionChecker.class);

    private static final int INCREMENTAL_BACKOFF_MILLISECONDS = 60_000;
    private static final int MAX_BACKOFF_MILLISECONDS = 14_400_000; // 4 hours
    private final KafkaSender sender;
    private final ServerStatusListener listener;
    private final AtomicBoolean isConnected;
    private final Random random;
    private final Handler mHandler;
    private final long heartbeatInterval;
    private long lastConnection;
    private int retries;
    private boolean isPosted;

    KafkaConnectionChecker(KafkaSender sender, Handler handler, ServerStatusListener listener,
                           long heartbeatSecondsInterval) {
        this.sender = sender;
        this.mHandler = handler;
        isConnected = new AtomicBoolean(true);
        lastConnection = -1L;
        this.retries = 0;
        this.listener = listener;
        this.random = new Random();
        this.heartbeatInterval = heartbeatSecondsInterval * 1000L;
        this.isPosted = false;
    }

    /**
     * Check whether the connection was closed and try to reconnect.
     */
    @Override
    public void run() {
        synchronized (this) {
            isPosted = false;
        }
        if (!isConnected.get()) {
            if (sender.isConnected() || sender.resetConnection()) {
                didConnect();
                listener.updateServerStatus(ServerStatusListener.Status.CONNECTED);
                logger.info("Sender reconnected");
            } else {
                retry();
            }
        } else if (System.currentTimeMillis() - lastConnection > 15_000L) {
            if (sender.isConnected()) {
                didConnect();
            } else {
                didDisconnect(null);
            }
        } else {
            post(heartbeatInterval);
        }
    }

    private synchronized void post(long delay) {
        if (isPosted) {
            mHandler.removeCallbacks(this);
        } else {
            isPosted = true;
        }
        mHandler.postDelayed(this, delay);
    }

    /** Check the connection as soon as possible. */
    public synchronized void check() {
        post(0);
    }

    /** Retry the connection with an incremental backoff. */
    private void retry() {
        double sample = random.nextDouble();
        synchronized (this) {
            retries++;
            int range = Math.min(INCREMENTAL_BACKOFF_MILLISECONDS * (1 << retries - 1),
                    MAX_BACKOFF_MILLISECONDS);
            long nextWait = Math.round(sample * range * 1000d);
            post(nextWait);
        }
    }

    /** Signal that the sender successfully connected. */
    public synchronized void didConnect() {
        lastConnection = System.currentTimeMillis();
        isConnected.set(true);
        post(heartbeatInterval);
        retries = 0;
    }

    /**
     * Signal that the Kafka REST sender has disconnected.
     * @param ex exception the sender disconnected with, may be null
     */
    public void didDisconnect(IOException ex) {
        logger.warn("Sender is disconnected", ex);

        if (isConnected.compareAndSet(true, false)) {
            if (ex instanceof AuthenticationException) {
                logger.warn("Failed to authenticate to server: {}", ex.getMessage());
                listener.updateServerStatus(ServerStatusListener.Status.UNAUTHORIZED);
            } else {
                listener.updateServerStatus(ServerStatusListener.Status.DISCONNECTED);
                // try to reconnect immediately
                check();
            }
        }
    }

    /** Whether the connection is currently assumed to be present. */
    public boolean isConnected() {
        return isConnected.get();
    }
}
