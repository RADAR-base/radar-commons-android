/**
 * Autogenerated by Avro
 *
 * DO NOT EDIT DIRECTLY
 */
package org.radarcns.monitor.application;

import org.apache.avro.specific.SpecificData;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.SchemaStore;

@SuppressWarnings("all")
/** Number of records sent per topic and their success status. */
@org.apache.avro.specific.AvroGenerated
public class ApplicationTopicRecordsSent extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  private static final long serialVersionUID = -5794816745103495696L;
  public static final org.apache.avro.Schema SCHEMA$ = new org.apache.avro.Schema.Parser().parse("{\"type\":\"record\",\"name\":\"ApplicationTopicRecordsSent\",\"namespace\":\"org.radarcns.monitor.application\",\"doc\":\"Number of records sent per topic and their success status.\",\"fields\":[{\"name\":\"time\",\"type\":\"double\",\"doc\":\"Device timestamp in UTC (s).\"},{\"name\":\"topic\",\"type\":{\"type\":\"string\",\"avro.java.string\":\"String\"},\"doc\":\"The name of topic sent.\"},{\"name\":\"success\",\"type\":\"boolean\",\"doc\":\"Weather record is succesfully sent or not.\"},{\"name\":\"recordsSent\",\"type\":[\"null\",\"int\"],\"doc\":\"Number of records sent for a particular topic.\",\"default\":null}]}");
  public static org.apache.avro.Schema getClassSchema() { return SCHEMA$; }

  private static SpecificData MODEL$ = new SpecificData();

  private static final BinaryMessageEncoder<ApplicationTopicRecordsSent> ENCODER =
      new BinaryMessageEncoder<ApplicationTopicRecordsSent>(MODEL$, SCHEMA$);

  private static final BinaryMessageDecoder<ApplicationTopicRecordsSent> DECODER =
      new BinaryMessageDecoder<ApplicationTopicRecordsSent>(MODEL$, SCHEMA$);

  /**
   * Return the BinaryMessageDecoder instance used by this class.
   */
  public static BinaryMessageDecoder<ApplicationTopicRecordsSent> getDecoder() {
    return DECODER;
  }

  /**
   * Create a new BinaryMessageDecoder instance for this class that uses the specified {@link SchemaStore}.
   * @param resolver a {@link SchemaStore} used to find schemas by fingerprint
   */
  public static BinaryMessageDecoder<ApplicationTopicRecordsSent> createDecoder(SchemaStore resolver) {
    return new BinaryMessageDecoder<ApplicationTopicRecordsSent>(MODEL$, SCHEMA$, resolver);
  }

  /** Serializes this ApplicationTopicRecordsSent to a ByteBuffer. */
  public java.nio.ByteBuffer toByteBuffer() throws java.io.IOException {
    return ENCODER.encode(this);
  }

  /** Deserializes a ApplicationTopicRecordsSent from a ByteBuffer. */
  public static ApplicationTopicRecordsSent fromByteBuffer(
      java.nio.ByteBuffer b) throws java.io.IOException {
    return DECODER.decode(b);
  }

  /** Device timestamp in UTC (s). */
  @Deprecated public double time;
  /** The name of topic sent. */
  @Deprecated public java.lang.String topic;
  /** Weather record is succesfully sent or not. */
  @Deprecated public boolean success;
  /** Number of records sent for a particular topic. */
  @Deprecated public java.lang.Integer recordsSent;

  /**
   * Default constructor.  Note that this does not initialize fields
   * to their default values from the schema.  If that is desired then
   * one should use <code>newBuilder()</code>.
   */
  public ApplicationTopicRecordsSent() {}

  /**
   * All-args constructor.
   * @param time Device timestamp in UTC (s).
   * @param topic The name of topic sent.
   * @param success Weather record is succesfully sent or not.
   * @param recordsSent Number of records sent for a particular topic.
   */
  public ApplicationTopicRecordsSent(java.lang.Double time, java.lang.String topic, java.lang.Boolean success, java.lang.Integer recordsSent) {
    this.time = time;
    this.topic = topic;
    this.success = success;
    this.recordsSent = recordsSent;
  }

  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call.
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return time;
    case 1: return topic;
    case 2: return success;
    case 3: return recordsSent;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }

  // Used by DatumReader.  Applications should not call.
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: time = (java.lang.Double)value$; break;
    case 1: topic = (java.lang.String)value$; break;
    case 2: success = (java.lang.Boolean)value$; break;
    case 3: recordsSent = (java.lang.Integer)value$; break;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }

  /**
   * Gets the value of the 'time' field.
   * @return Device timestamp in UTC (s).
   */
  public java.lang.Double getTime() {
    return time;
  }

  /**
   * Sets the value of the 'time' field.
   * Device timestamp in UTC (s).
   * @param value the value to set.
   */
  public void setTime(java.lang.Double value) {
    this.time = value;
  }

  /**
   * Gets the value of the 'topic' field.
   * @return The name of topic sent.
   */
  public java.lang.String getTopic() {
    return topic;
  }

  /**
   * Sets the value of the 'topic' field.
   * The name of topic sent.
   * @param value the value to set.
   */
  public void setTopic(java.lang.String value) {
    this.topic = value;
  }

  /**
   * Gets the value of the 'success' field.
   * @return Weather record is succesfully sent or not.
   */
  public java.lang.Boolean getSuccess() {
    return success;
  }

  /**
   * Sets the value of the 'success' field.
   * Weather record is succesfully sent or not.
   * @param value the value to set.
   */
  public void setSuccess(java.lang.Boolean value) {
    this.success = value;
  }

  /**
   * Gets the value of the 'recordsSent' field.
   * @return Number of records sent for a particular topic.
   */
  public java.lang.Integer getRecordsSent() {
    return recordsSent;
  }

  /**
   * Sets the value of the 'recordsSent' field.
   * Number of records sent for a particular topic.
   * @param value the value to set.
   */
  public void setRecordsSent(java.lang.Integer value) {
    this.recordsSent = value;
  }

  /**
   * Creates a new ApplicationTopicRecordsSent RecordBuilder.
   * @return A new ApplicationTopicRecordsSent RecordBuilder
   */
  public static org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder newBuilder() {
    return new org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder();
  }

  /**
   * Creates a new ApplicationTopicRecordsSent RecordBuilder by copying an existing Builder.
   * @param other The existing builder to copy.
   * @return A new ApplicationTopicRecordsSent RecordBuilder
   */
  public static org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder newBuilder(org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder other) {
    return new org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder(other);
  }

  /**
   * Creates a new ApplicationTopicRecordsSent RecordBuilder by copying an existing ApplicationTopicRecordsSent instance.
   * @param other The existing instance to copy.
   * @return A new ApplicationTopicRecordsSent RecordBuilder
   */
  public static org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder newBuilder(org.radarcns.monitor.application.ApplicationTopicRecordsSent other) {
    return new org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder(other);
  }

  /**
   * RecordBuilder for ApplicationTopicRecordsSent instances.
   */
  public static class Builder extends org.apache.avro.specific.SpecificRecordBuilderBase<ApplicationTopicRecordsSent>
    implements org.apache.avro.data.RecordBuilder<ApplicationTopicRecordsSent> {

    /** Device timestamp in UTC (s). */
    private double time;
    /** The name of topic sent. */
    private java.lang.String topic;
    /** Weather record is succesfully sent or not. */
    private boolean success;
    /** Number of records sent for a particular topic. */
    private java.lang.Integer recordsSent;

    /** Creates a new Builder */
    private Builder() {
      super(SCHEMA$);
    }

    /**
     * Creates a Builder by copying an existing Builder.
     * @param other The existing Builder to copy.
     */
    private Builder(org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder other) {
      super(other);
      if (isValidValue(fields()[0], other.time)) {
        this.time = data().deepCopy(fields()[0].schema(), other.time);
        fieldSetFlags()[0] = true;
      }
      if (isValidValue(fields()[1], other.topic)) {
        this.topic = data().deepCopy(fields()[1].schema(), other.topic);
        fieldSetFlags()[1] = true;
      }
      if (isValidValue(fields()[2], other.success)) {
        this.success = data().deepCopy(fields()[2].schema(), other.success);
        fieldSetFlags()[2] = true;
      }
      if (isValidValue(fields()[3], other.recordsSent)) {
        this.recordsSent = data().deepCopy(fields()[3].schema(), other.recordsSent);
        fieldSetFlags()[3] = true;
      }
    }

    /**
     * Creates a Builder by copying an existing ApplicationTopicRecordsSent instance
     * @param other The existing instance to copy.
     */
    private Builder(org.radarcns.monitor.application.ApplicationTopicRecordsSent other) {
            super(SCHEMA$);
      if (isValidValue(fields()[0], other.time)) {
        this.time = data().deepCopy(fields()[0].schema(), other.time);
        fieldSetFlags()[0] = true;
      }
      if (isValidValue(fields()[1], other.topic)) {
        this.topic = data().deepCopy(fields()[1].schema(), other.topic);
        fieldSetFlags()[1] = true;
      }
      if (isValidValue(fields()[2], other.success)) {
        this.success = data().deepCopy(fields()[2].schema(), other.success);
        fieldSetFlags()[2] = true;
      }
      if (isValidValue(fields()[3], other.recordsSent)) {
        this.recordsSent = data().deepCopy(fields()[3].schema(), other.recordsSent);
        fieldSetFlags()[3] = true;
      }
    }

    /**
      * Gets the value of the 'time' field.
      * Device timestamp in UTC (s).
      * @return The value.
      */
    public java.lang.Double getTime() {
      return time;
    }

    /**
      * Sets the value of the 'time' field.
      * Device timestamp in UTC (s).
      * @param value The value of 'time'.
      * @return This builder.
      */
    public org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder setTime(double value) {
      validate(fields()[0], value);
      this.time = value;
      fieldSetFlags()[0] = true;
      return this;
    }

    /**
      * Checks whether the 'time' field has been set.
      * Device timestamp in UTC (s).
      * @return True if the 'time' field has been set, false otherwise.
      */
    public boolean hasTime() {
      return fieldSetFlags()[0];
    }


    /**
      * Clears the value of the 'time' field.
      * Device timestamp in UTC (s).
      * @return This builder.
      */
    public org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder clearTime() {
      fieldSetFlags()[0] = false;
      return this;
    }

    /**
      * Gets the value of the 'topic' field.
      * The name of topic sent.
      * @return The value.
      */
    public java.lang.String getTopic() {
      return topic;
    }

    /**
      * Sets the value of the 'topic' field.
      * The name of topic sent.
      * @param value The value of 'topic'.
      * @return This builder.
      */
    public org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder setTopic(java.lang.String value) {
      validate(fields()[1], value);
      this.topic = value;
      fieldSetFlags()[1] = true;
      return this;
    }

    /**
      * Checks whether the 'topic' field has been set.
      * The name of topic sent.
      * @return True if the 'topic' field has been set, false otherwise.
      */
    public boolean hasTopic() {
      return fieldSetFlags()[1];
    }


    /**
      * Clears the value of the 'topic' field.
      * The name of topic sent.
      * @return This builder.
      */
    public org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder clearTopic() {
      topic = null;
      fieldSetFlags()[1] = false;
      return this;
    }

    /**
      * Gets the value of the 'success' field.
      * Weather record is succesfully sent or not.
      * @return The value.
      */
    public java.lang.Boolean getSuccess() {
      return success;
    }

    /**
      * Sets the value of the 'success' field.
      * Weather record is succesfully sent or not.
      * @param value The value of 'success'.
      * @return This builder.
      */
    public org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder setSuccess(boolean value) {
      validate(fields()[2], value);
      this.success = value;
      fieldSetFlags()[2] = true;
      return this;
    }

    /**
      * Checks whether the 'success' field has been set.
      * Weather record is succesfully sent or not.
      * @return True if the 'success' field has been set, false otherwise.
      */
    public boolean hasSuccess() {
      return fieldSetFlags()[2];
    }


    /**
      * Clears the value of the 'success' field.
      * Weather record is succesfully sent or not.
      * @return This builder.
      */
    public org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder clearSuccess() {
      fieldSetFlags()[2] = false;
      return this;
    }

    /**
      * Gets the value of the 'recordsSent' field.
      * Number of records sent for a particular topic.
      * @return The value.
      */
    public java.lang.Integer getRecordsSent() {
      return recordsSent;
    }

    /**
      * Sets the value of the 'recordsSent' field.
      * Number of records sent for a particular topic.
      * @param value The value of 'recordsSent'.
      * @return This builder.
      */
    public org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder setRecordsSent(java.lang.Integer value) {
      validate(fields()[3], value);
      this.recordsSent = value;
      fieldSetFlags()[3] = true;
      return this;
    }

    /**
      * Checks whether the 'recordsSent' field has been set.
      * Number of records sent for a particular topic.
      * @return True if the 'recordsSent' field has been set, false otherwise.
      */
    public boolean hasRecordsSent() {
      return fieldSetFlags()[3];
    }


    /**
      * Clears the value of the 'recordsSent' field.
      * Number of records sent for a particular topic.
      * @return This builder.
      */
    public org.radarcns.monitor.application.ApplicationTopicRecordsSent.Builder clearRecordsSent() {
      recordsSent = null;
      fieldSetFlags()[3] = false;
      return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ApplicationTopicRecordsSent build() {
      try {
        ApplicationTopicRecordsSent record = new ApplicationTopicRecordsSent();
        record.time = fieldSetFlags()[0] ? this.time : (java.lang.Double) defaultValue(fields()[0]);
        record.topic = fieldSetFlags()[1] ? this.topic : (java.lang.String) defaultValue(fields()[1]);
        record.success = fieldSetFlags()[2] ? this.success : (java.lang.Boolean) defaultValue(fields()[2]);
        record.recordsSent = fieldSetFlags()[3] ? this.recordsSent : (java.lang.Integer) defaultValue(fields()[3]);
        return record;
      } catch (java.lang.Exception e) {
        throw new org.apache.avro.AvroRuntimeException(e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static final org.apache.avro.io.DatumWriter<ApplicationTopicRecordsSent>
    WRITER$ = (org.apache.avro.io.DatumWriter<ApplicationTopicRecordsSent>)MODEL$.createDatumWriter(SCHEMA$);

  @Override public void writeExternal(java.io.ObjectOutput out)
    throws java.io.IOException {
    WRITER$.write(this, SpecificData.getEncoder(out));
  }

  @SuppressWarnings("unchecked")
  private static final org.apache.avro.io.DatumReader<ApplicationTopicRecordsSent>
    READER$ = (org.apache.avro.io.DatumReader<ApplicationTopicRecordsSent>)MODEL$.createDatumReader(SCHEMA$);

  @Override public void readExternal(java.io.ObjectInput in)
    throws java.io.IOException {
    READER$.read(this, SpecificData.getDecoder(in));
  }

}
