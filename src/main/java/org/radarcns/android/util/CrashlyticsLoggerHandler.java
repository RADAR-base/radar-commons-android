package org.radarcns.android.util;

import com.crashlytics.android.Crashlytics;
import pl.brightinventions.slf4android.MessageValueSupplier;

import java.util.logging.Handler;
import pl.brightinventions.slf4android.LogRecord;

public class CrashlyticsLoggerHandler extends Handler {
    private final MessageValueSupplier messageValueSupplier = new MessageValueSupplier();

    @Override
    public void publish(java.util.logging.LogRecord record) {
        LogRecord logRecord = LogRecord.fromRecord(record);
        StringBuilder messageBuilder = new StringBuilder();
        messageValueSupplier.append(logRecord, messageBuilder);
        String tag = record.getLoggerName();
        int androidLogLevel = logRecord.getLogLevel().getAndroidLevel();
        Crashlytics.log(androidLogLevel, tag, messageBuilder.toString());
    }

    @Override
    public void close() {}

    @Override
    public void flush() {}
}