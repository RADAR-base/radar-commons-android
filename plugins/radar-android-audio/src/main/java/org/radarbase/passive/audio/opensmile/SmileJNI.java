/*
 Copyright (c) 2015 audEERING UG. All rights reserved.

 Date: 17.08.2015
 Author(s): Florian Eyben
 E-mail:  fe@audeering.com

 This is the interface between the Android app and the openSMILE binary.
 openSMILE is called via SMILExtractJNI()
 Messages from openSMILE are received by implementing the SmileJNI.Listener interface.
*/

package org.radarbase.passive.audio.opensmile;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE;

public class SmileJNI implements Runnable {
    private static final String ASSET_PATH_BASE = "org/radarbase/passive/audio/opensmile";
    private static final String[] ASSET_PATHS = {".", "shared"};
    private static File assetDir;
    private static final AtomicBoolean isRecording = new AtomicBoolean(false);
    private static MessageListener messageListener;
    private static final Logger logger = LoggerFactory.getLogger(SmileJNI.class);

    public static File init(Context c) throws IOException {
        synchronized (SmileJNI.class) {
            if (assetDir == null) {
                File assetDirectory = new File(c.getCacheDir(), ASSET_PATH_BASE);

                AssetManager assetManager = c.getAssets();
                for (String assetPath : ASSET_PATHS) {
                    copyDirectory(assetManager, ASSET_PATH_BASE + '/' + assetPath, new File(assetDirectory, assetPath));
                }
                assetDir = assetDirectory;
            }
            return assetDir;
        }
    }

    private static void copyDirectory(AssetManager assetManager, String fromDirectory, File toDirectory) throws IOException {
        if (!toDirectory.exists() && !toDirectory.mkdirs()) {
            throw new IOException("Cannot initialize sample directory");
        }

        String[] fileList = assetManager.list(fromDirectory);
        if (fileList == null) {
            throw new IllegalStateException("Asset directory does not exist.");
        }
        for (String filename : fileList) {
            File outFile = new File(toDirectory, filename);

            byte[] buffer = new byte[8096];

            try (InputStream in = assetManager.open(fromDirectory + '/' + filename);
                 FileOutputStream out = new FileOutputStream(outFile)) {

                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }

            } catch (IOException e) {
                Log.e("tag", "Failed to copy asset file: " + filename, e);
            }
        }
    }

    /**
     * Method to execute openSMILE binary from the Android app activity, see smile_jni.cpp.
     *
     * @param configFile configuration file name
     * @param updateProfile whether to update the audio profile
     * @param outputfile outputfile that openSMILE should write to
     * @return
     */
    public static native String SMILExtractJNI(String cwd, String configFile, int updateProfile, String outputfile);

    public static native String SMILEndJNI();

    /**
     * process the messages from openSMILE (redirect to app activity etc.)
     */
    public interface MessageListener {
        void onSmileMessageReceived(String text);
    }

    public static void registerListener(MessageListener listener) {
        messageListener = listener;
    }

    /**
     * this is the first method called by openSMILE binary. it redirects the call to the Android
     * app activity.
     *
     * @param text JSON encoded string
     */
    static void receiveText(String text) {
        if (messageListener != null) {
            messageListener.onSmileMessageReceived(text);
        }
    }

    private final String conf;
    private final String recordingPath;
    private final long duration;

    /*
     * load the JNI interface
     */
    static {
        System.loadLibrary("smile_jni");
    }

    public SmileJNI(String conf, String recordingPath, long duration) {
        File confFile;
        synchronized (SmileJNI.class) {
            if (assetDir == null) {
                throw new IllegalStateException("Cannot start SmileJNI without calling SmileJNI.init");
            }
            confFile = new File(assetDir, conf);
        }
        if (!confFile.exists()) {
            throw new IllegalArgumentException("Config file " + confFile + " does not exist.");
        }
        this.conf = confFile.getAbsolutePath();
        this.recordingPath = recordingPath;
        this.duration = duration;
    }

    @Override
    public void run() {
        File output = new File(recordingPath);
        if (output.exists()) {
            throw new IllegalStateException("Output path exists");
        }

        if (isRecording.compareAndSet(false, true)) {
            Thread jniThread = new Thread("SMILEExtract") {
                @Override
                public void run() {
                    android.os.Process.setThreadPriority(THREAD_PRIORITY_MORE_FAVORABLE);
                    try {
                        SmileJNI.SMILExtractJNI(assetDir.getAbsolutePath(), conf, 1, recordingPath);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to run opensmile", e);
                    }
                }
            };
            jniThread.start();

            synchronized (this) {
                try {
                    wait(duration);
                } catch (InterruptedException ex) {
                    logger.warn("Interrupted, cancelling earlier", ex);
                    Thread.currentThread().interrupt();
                }
            }

            SmileJNI.SMILEndJNI();
            try {
                jniThread.join();
            } catch (InterruptedException ex) {
                logger.warn("Interrupted, not waiting for writing to finish", ex);
                Thread.currentThread().interrupt();
            }
            isRecording.set(false);
        } else {
            throw new IllegalStateException("Another instance is already recording");
        }
    }
}
