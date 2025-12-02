package net.hasor.neta.codec.net.ntp;

public class NTPField {
    private short  fieldType;
    private byte[] value;

    public NTPField(short fieldType, byte[] value) {
        this.fieldType = fieldType;
        this.value = value;
    }

    public short getFieldType() {
        return fieldType;
    }

    public void setFieldType(short fieldType) {
        this.fieldType = fieldType;
    }

    public byte[] getValue() {
        return value;
    }

    public void setValue(byte[] value) {
        this.value = value;
    }

    public int getLength() {
        // Type(2) + Length(2) + Value + Padding
        int dataLen = 4 + (value == null ? 0 : value.length);
        return (dataLen + 3) & ~3; // Align to 4 bytes
    }
}
