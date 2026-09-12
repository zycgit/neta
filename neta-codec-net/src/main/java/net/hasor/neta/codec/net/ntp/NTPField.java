/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.net.ntp;
/**
 * Represents an NTP Extension Field (NTPv4).
 * <p>
 * Extension fields are used to add optional capabilities to NTP packets.
 * The format is defined in RFC 5905.
 * </p>
 */
public class NTPField {
    /** Field Type (16 bits): Identifies the function of the extension field. */
    private short  fieldType;
    /** Value (variable length): The actual data of the extension field. */
    private byte[] value;

    /**
     * Constructs a new NTPField.
     * @param fieldType the type of the extension field
     * @param value the value of the extension field
     */
    public NTPField(short fieldType, byte[] value) {
        this.fieldType = fieldType;
        this.value = value;
    }

    /**
     * Returns the Field Type.
     * @return the field type
     */
    public short getFieldType() {
        return fieldType;
    }

    /**
     * Sets the Field Type.
     * @param fieldType the field type to set
     */
    public void setFieldType(short fieldType) {
        this.fieldType = fieldType;
    }

    /**
     * Returns the Value.
     * @return the value
     */
    public byte[] getValue() {
        return value;
    }

    /**
     * Sets the Value.
     * @param value the value to set
     */
    public void setValue(byte[] value) {
        this.value = value;
    }

    /**
     * Returns the total length of the field including padding.
     * @return the total length
     */
    public int getLength() {
        // Type(2) + Length(2) + Value + Padding
        int dataLen = 4 + (value == null ? 0 : value.length);
        return (dataLen + 3) & ~3; // Align to 4 bytes
    }
}
