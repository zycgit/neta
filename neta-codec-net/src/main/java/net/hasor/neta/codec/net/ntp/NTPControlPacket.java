/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.net.ntp;
/**
 * Represents an NTP Control Message (Mode 6).
 * <p>
 * Control messages are used to monitor and control the NTP server.
 * They are defined in RFC 1305, Appendix B.
 * </p>
 */
public class NTPControlPacket extends NTPMessage {
    /** Response Bit (1 bit): 0 for command, 1 for response. */
    private byte   responseBit;
    /** Error Bit (1 bit): 0 for normal, 1 for error. */
    private byte   errorBit;
    /** More Bit (1 bit): 0 for last fragment, 1 for more fragments. */
    private byte   moreBit;
    /** Operation Code (5 bits): Specifies the function of the message. */
    private byte   operationCode;
    /** Sequence Number (16 bits): Used to match requests and responses. */
    private int    sequence;
    /** Status Word (16 bits): Contains system status information. */
    private int    status;
    /** Association ID (16 bits): Identifies a specific association. */
    private int    associationID;
    /** Offset (16 bits): Data offset, used for fragment reassembly. */
    private int    offset;
    /** Count (16 bits): Length of the data field in bytes. */
    private int    count;
    /** Data (variable length): Contains specific control information. */
    private byte[] data;

    /**
     * Constructs a new NTPControlPacket.
     */
    public NTPControlPacket() {
        setNtpMode(NTPMode.CONTROL_MESSAGE);
    }

    /**
     * Returns the Response Bit.
     * @return 0 for command, 1 for response
     */
    public byte getResponseBit() {
        return responseBit;
    }

    /**
     * Sets the Response Bit.
     * @param responseBit the response bit to set
     */
    public void setResponseBit(byte responseBit) {
        this.responseBit = responseBit;
    }

    /**
     * Returns the Error Bit.
     * @return 0 for normal, 1 for error
     */
    public byte getErrorBit() {
        return errorBit;
    }

    /**
     * Sets the Error Bit.
     * @param errorBit the error bit to set
     */
    public void setErrorBit(byte errorBit) {
        this.errorBit = errorBit;
    }

    /**
     * Returns the More Bit.
     * @return 0 for last fragment, 1 for more fragments
     */
    public byte getMoreBit() {
        return moreBit;
    }

    /**
     * Sets the More Bit.
     * @param moreBit the more bit to set
     */
    public void setMoreBit(byte moreBit) {
        this.moreBit = moreBit;
    }

    /**
     * Returns the Operation Code.
     * @return the operation code
     */
    public byte getOperationCode() {
        return operationCode;
    }

    /**
     * Sets the Operation Code.
     * @param operationCode the operation code to set
     */
    public void setOperationCode(byte operationCode) {
        this.operationCode = operationCode;
    }

    /**
     * Returns the Sequence Number.
     * @return the sequence number
     */
    public int getSequence() {
        return sequence;
    }

    /**
     * Sets the Sequence Number.
     * @param sequence the sequence number to set
     */
    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    /**
     * Returns the Status Word.
     * @return the status word
     */
    public int getStatus() {
        return status;
    }

    /**
     * Sets the Status Word.
     * @param status the status word to set
     */
    public void setStatus(int status) {
        this.status = status;
    }

    /**
     * Returns the Association ID.
     * @return the association ID
     */
    public int getAssociationID() {
        return associationID;
    }

    /**
     * Sets the Association ID.
     * @param associationID the association ID to set
     */
    public void setAssociationID(int associationID) {
        this.associationID = associationID;
    }

    /**
     * Returns the Offset.
     * @return the offset
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Sets the Offset.
     * @param offset the offset to set
     */
    public void setOffset(int offset) {
        this.offset = offset;
    }

    /**
     * Returns the Count (data length).
     * @return the data length
     */
    public int getCount() {
        return count;
    }

    /**
     * Sets the Count (data length).
     * @param count the data length to set
     */
    public void setCount(int count) {
        this.count = count;
    }

    /**
     * Returns the Data field.
     * @return the data
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Sets the Data field.
     * @param data the data to set
     */
    public void setData(byte[] data) {
        this.data = data;
    }
}
