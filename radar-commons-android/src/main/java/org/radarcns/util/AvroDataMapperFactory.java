package org.radarcns.util;

import org.apache.avro.Schema;
import org.apache.avro.SchemaValidationException;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.generic.IndexedRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.avro.JsonProperties.NULL_VALUE;

public class AvroDataMapperFactory {
    private static final AvroDataMapper IDENTITY_MAPPER = obj -> obj;

    public static AvroDataMapper createMapper(Schema from, Schema to, Object defaultVal) throws SchemaValidationException {
        if (from.equals(to)) {
            return IDENTITY_MAPPER;
        }

        try {
            if (to.getType() == Schema.Type.UNION || from.getType() == Schema.Type.UNION) {
                return mapUnion(from, to, defaultVal);
            }
            if (to.getType() == Schema.Type.ENUM || to.getType() == Schema.Type.ENUM) {
                return mapEnum(from, to, defaultVal);
            }

            switch (to.getType()) {
                case INT:
                case LONG:
                case DOUBLE:
                case FLOAT:
                    return mapNumber(from, to, defaultVal);
                default:
                    break;
            }
            switch (from.getType()) {
                case RECORD:
                    return RecordMapper.mapRecord(from, to);
                case ARRAY:
                    return mapArray(from, to);
                case MAP:
                    return mapMap(from, to);
                case FIXED:
                    return mapFixed(from, to);
                case INT:
                case LONG:
                case DOUBLE:
                case FLOAT:
                    return mapNumber(from, to, defaultVal);
                default:
                    if (from.getType() != to.getType()) {
                        throw new SchemaValidationException(from, to,
                                new RuntimeException("Schema types of from and to don't match"));
                    }
                    return IDENTITY_MAPPER;
            }
        } catch (SchemaValidationException ex) {
            if (defaultVal != null) {
                if (defaultVal == NULL_VALUE) {
                    return obj -> null;
                } else {
                    return obj -> defaultVal;
                }
            } else {
                throw ex;
            }
        }
    }

    private static AvroDataMapper mapEnum(Schema from, Schema to, Object defaultVal) throws SchemaValidationException {
        if (from.getType() == Schema.Type.ENUM && to.getType() == Schema.Type.ENUM) {
            boolean containsAll = true;
            for (String s : from.getEnumSymbols()) {
                if (!to.hasEnumSymbol(s)) {
                    containsAll = false;
                    break;
                }
            }
            if (containsAll) {
                return obj -> new GenericData.EnumSymbol(to, obj.toString());
            } else {
                Object mappedDefaultVal = defaultVal != null ? defaultVal : (
                        to.hasEnumSymbol("UNKNOWN")
                                ? new GenericData.EnumSymbol(to, "UNKNOWN") : null);
                if (mappedDefaultVal == null) {
                    throw new SchemaValidationException(from, to,
                            new RuntimeException("Cannot map enum symbols without default value"));
                } else {
                    return obj -> {
                        String value = obj.toString();
                        if (to.hasEnumSymbol(value)) {
                            return new GenericData.EnumSymbol(to, value);
                        } else {
                            return mappedDefaultVal;
                        }
                    };
                }
            }
        } else if (from.getType() == Schema.Type.ENUM && to.getType() == Schema.Type.STRING) {
            return Object::toString;
        }
        return null;
    }

    private static AvroDataMapper mapNumber(Schema from, Schema to, Object defaultVal) throws SchemaValidationException {
        if (from.getType() == to.getType()) {
            return IDENTITY_MAPPER;
        }

        if (from.getType() == Schema.Type.STRING) {
            if (defaultVal == null) {
                throw new SchemaValidationException(from, to,
                        new RuntimeException("Cannot map string to number without default value."));
            } else {
                switch (to.getType()) {
                    case INT:
                        return obj -> {
                            try {
                                return Integer.valueOf(obj.toString());
                            } catch (NumberFormatException ex) {
                                return defaultVal;
                            }
                        };
                    case LONG:
                        return obj -> {
                            try {
                                return Long.valueOf(obj.toString());
                            } catch (NumberFormatException ex) {
                                return defaultVal;
                            }
                        };
                    case DOUBLE:
                        return obj -> {
                            try {
                                return Double.valueOf(obj.toString());
                            } catch (NumberFormatException ex) {
                                return defaultVal;
                            }
                        };
                    case FLOAT:
                        return obj -> {
                            try {
                                return Float.valueOf(obj.toString());
                            } catch (NumberFormatException ex) {
                                return defaultVal;
                            }
                        };
                    default:
                        throw new SchemaValidationException(from, to,
                                new RuntimeException("Cannot map numeric type with non-numeric type"));
                }
            }
        } else {
            switch (to.getType()) {
                case INT:
                    return obj -> ((Number) obj).intValue();
                case LONG:
                    return obj -> ((Number) obj).longValue();
                case DOUBLE:
                    return obj -> Double.valueOf(obj.toString());
                case FLOAT:
                    return obj -> ((Number) obj).floatValue();
                case STRING:
                    return Object::toString;
                default:
                    throw new SchemaValidationException(from, to,
                            new RuntimeException("Cannot map numeric type with non-numeric type"));
            }
        }
    }

    private static Schema nonNullUnionSchema(Schema schema) throws SchemaValidationException {
        List<Schema> types = schema.getTypes();

        if (types.size() != 2) {
            throw new SchemaValidationException(schema, schema,
                    new RuntimeException("Types must denote optionals"));
        }

        if (types.get(0).getType() == Schema.Type.NULL) {
            if (types.get(1).getType() != Schema.Type.NULL) {
                return types.get(1);
            } else {
                throw new SchemaValidationException(schema, schema,
                        new RuntimeException("Types must denote optionals"));
            }
        } else if (types.get(1).getType() == Schema.Type.NULL) {
            return types.get(0);
        } else {
            throw new SchemaValidationException(schema, schema,
                    new RuntimeException("Types must denote optionals."));
        }
    }

    private static AvroDataMapper mapUnion(Schema from, Schema to, Object defaultVal) throws SchemaValidationException {
        Schema resolvedFrom = from.getType() == Schema.Type.UNION ? nonNullUnionSchema(from) : from;

        if (from.getType() == Schema.Type.UNION && to.getType() != Schema.Type.UNION) {
            return createMapper(resolvedFrom, to, defaultVal);
        } else {
            Schema toNonNull = nonNullUnionSchema(to);
            AvroDataMapper unionMapper = createMapper(resolvedFrom, toNonNull, defaultVal);
            return obj -> obj == null ? null : unionMapper.convert(obj);
        }
    }

    private static AvroDataMapper mapArray(Schema from, Schema to) throws SchemaValidationException {
        if (to.getType() != Schema.Type.ARRAY) {
            throw new SchemaValidationException(from, to,
                    new RuntimeException("Cannot map array to non-array"));
        }
        AvroDataMapper entryMapper = createMapper(from.getElementType(), to.getElementType(), null);
        return obj -> {
            List array = (List) obj;
            List<Object> toArray = new ArrayList<>(array.size());
            for (Object val : array) {
                toArray.add(entryMapper.convert(val));
            }
            return toArray;
        };
    }


    private static AvroDataMapper mapMap(Schema from, Schema to) throws SchemaValidationException {
        if (to.getType() != Schema.Type.MAP) {
            throw new SchemaValidationException(from, to,
                    new RuntimeException("Cannot map array to non-array"));
        }
        AvroDataMapper entryMapper = createMapper(from.getValueType(), to.getValueType(), null);
        return obj -> {
            @SuppressWarnings("unchecked")
            Map<String, ?> map = (Map<String, ?>) obj;
            Map<String, Object> toMap = new HashMap<>(map.size() * 4 / 3 + 1);
            for (Map.Entry<String, ?> entry : map.entrySet()) {
                toMap.put(entry.getKey(), entryMapper.convert(entry.getValue()));
            }
            return toMap;
        };
    }

    private static AvroDataMapper mapFixed(Schema from, Schema to) throws SchemaValidationException {
        if (!(to.getType() == Schema.Type.BYTES || (to.getType() == Schema.Type.FIXED && to.getFixedSize() == from.getFixedSize()))) {
            throw new SchemaValidationException(from, to,
                    new RuntimeException("Fixed type must be mapped to comparable byte size"));
        }
        return IDENTITY_MAPPER;
    }

    private static class RecordMapper implements AvroDataMapper {
        private final AvroDataMapper[] fieldMappers;
        private final Schema.Field[] toFields;
        private final Schema toSchema;

        RecordMapper(Schema toSchema, Schema.Field[] toFields, AvroDataMapper[] fieldMappers) {
            this.toSchema = toSchema;
            this.fieldMappers = fieldMappers;
            this.toFields = toFields;
        }

        static AvroDataMapper mapRecord(Schema from, Schema to)
                throws SchemaValidationException {
            if (to.getType() != Schema.Type.RECORD) {
                throw new SchemaValidationException(from, to,
                        new RuntimeException("From and to schemas must be records."));
            }
            List<Schema.Field> fromFields = from.getFields();
            Schema.Field[] toFields = new Schema.Field[fromFields.size()];
            AvroDataMapper[] fieldMappers = new AvroDataMapper[fromFields.size()];

            for (int i = 0; i < fromFields.size(); i++) {
                Schema.Field fromField = fromFields.get(i);
                Schema.Field toField = to.getField(fromField.name());
                if (toField == null) {
                    continue;
                }

                Schema fromSchema = fromField.schema();
                Schema toSchema = toField.schema();

                if (fromSchema.equals(toSchema)) {
                    toFields[i] = toField;
                    fieldMappers[i] = createMapper(fromSchema, toSchema, toField.defaultVal());
                }
            }

            return new RecordMapper(to, toFields, fieldMappers);
        }

        @Override
        public GenericRecord convert(Object obj) {
            GenericRecordBuilder builder = new GenericRecordBuilder(toSchema);
            IndexedRecord record = (IndexedRecord) obj;
            for (int i = 0; i < toFields.length; i++) {
                Schema.Field field = toFields[i];
                if (field == null) {
                    continue;
                }
                builder.set(field, fieldMappers[i].convert(record.get(i)));
            }
            return builder.build();
        }
    }
}
