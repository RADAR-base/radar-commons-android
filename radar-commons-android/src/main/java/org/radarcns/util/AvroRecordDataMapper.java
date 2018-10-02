package org.radarcns.util;

import org.apache.avro.generic.IndexedRecord;

public interface AvroRecordDataMapper {
    IndexedRecord convert(IndexedRecord record);
    boolean returnsSpecificRecord();
}
