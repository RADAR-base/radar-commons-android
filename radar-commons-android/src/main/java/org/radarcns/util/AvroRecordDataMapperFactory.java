package org.radarcns.util;

import android.util.SparseArray;
import android.util.SparseIntArray;

import org.apache.avro.Schema;
import org.apache.avro.SchemaValidationException;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.generic.IndexedRecord;

import java.util.ArrayList;
import java.util.List;

public class AvroRecordDataMapperFactory {
    public static AvroRecordDataMapper createMapper(Schema from, Schema to) throws SchemaValidationException {
        if (from.equals(to)) {
            return new IdentityMapper();
        }

        SparseIntArray fieldMap = new SparseIntArray();
        SparseIntArray unionMap = new SparseIntArray();
        SparseIntArray customMap = new SparseIntArray();
        List<Integer> unmapped = new ArrayList<>(1);
        SparseArray<AvroRecordDataMapper> subMappers = new SparseArray<>();

        for (Schema.Field fromField : from.getFields()) {
            Schema.Field toField = to.getField(fromField.name());

            if (toField == null) {
                unmapped.add(fromField.pos());
                continue;
            }

            int fromPos = fromField.pos();
            int toPos = toField.pos();

            Schema fromSchema = fromField.schema();
            Schema toSchema = toField.schema();

            if (fromSchema.equals(toSchema)) {
                fieldMap.append(fromPos, toPos);
            } else {
                Schema.Type fromType = fromSchema.getType();
                Schema.Type toType = toSchema.getType();

                if (fromType == Schema.Type.UNION) {
                    if (toType != Schema.Type.UNION) {
                        if (toField.defaultVal() == null) {
                            throw new SchemaValidationException(from, to,
                                    new RuntimeException("Cannot map non-union to field without " +
                                            "default value"));
                        } else {
                            unionMap.append(fromPos, toPos);
                        }
                    } else {
                        for (Schema fromUnionSchema : fromSchema.getTypes()) {
                            for (Schema)
                        }
                    }
                }
                if (fromType != Schema.Type.UNION && toType == Schema.Type.UNION) {
                    boolean didMap = false;
                    for (Schema unionSchema : toField.schema().getTypes()) {
                        if (fromField.schema().equals(unionSchema)) {
                            fieldMap.append(fromField.pos(), toField.pos());
                            didMap = true;
                            break;
                        }
                    }
                    if (!didMap && toField.defaultVal() == null) {
                        throw new SchemaValidationException(from, to,
                                new RuntimeException("Cannot map incompatible union fields"));
                    }
                } else if (fromType == Schema.Type.UNION) {

                }
                if (fromField.schema().getType())
            }
        }
    }

    public IndexedRecord mapRecordToSchema(IndexedRecord record, Schema schema) {
        if (schema.equals(record.getSchema())) {
            return record;
        }
    }

    private static class IdentityMapper implements AvroRecordDataMapper {
        @Override
        public IndexedRecord convert(IndexedRecord record) {
            return record;
        }

        @Override
        public boolean returnsSpecificRecord() {
            return true;
        }
    }
}
