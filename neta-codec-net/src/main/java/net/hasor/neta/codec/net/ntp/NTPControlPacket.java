package net.hasor.neta.codec.net.ntp;

public class NTPControlPacket extends NTPMessage {
    private byte   rem;                // 3bit, R(1) E(1) M(1)
    private byte   op;                 // 5bit，操作码，表明命令的类型。
    private int    sequence;           // 16bit, Sequence Number
    private int    status;             // 16bit, Status
    private int    associationID;      // 16bit, Association ID
    private int    offset;             // 16bit, Offset
    private int    count;              // 16bit, Count
    private byte[] data;               // Data

    public NTPControlPacket() {
        setNtpMode(NTPMode.CONTROL_MESSAGE);
    }

    public byte getRem() {
        return rem;
    }

    public void setRem(byte rem) {
        this.rem = rem;
    }

    public boolean isResponse() {
        return (rem & 0x4) != 0;
    }

    public void setResponse(boolean response) {
        if (response) {
            rem |= 0x4;
        } else {
            rem &= ~0x4;
        }
    }

    public boolean isError() {
        return (rem & 0x2) != 0;
    }

    public void setError(boolean error) {
        if (error) {
            rem |= 0x2;
        } else {
            rem &= ~0x2;
        }
    }

    public boolean isMore() {
        return (rem & 0x1) != 0;
    }

    public void setMore(boolean more) {
        if (more) {
            rem |= 0x1;
        } else {
            rem &= ~0x1;
        }
    }

    public byte getOp() {
        return op;
    }

    public void setOp(byte op) {
        this.op = op;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getAssociationID() {
        return associationID;
    }

    public void setAssociationID(int associationID) {
        this.associationID = associationID;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "NTPControlPacket{\n" +                                  //
                "    leapIndicator=" + this.getLeapIndicator() + ",\n" +//
                "    version=" + this.getVersion() + ",\n" +            //
                "    ntpMode=" + this.getNtpMode() + ",\n" +            //
                "    rem=" + this.rem + ",\n" +                         //
                "    op=" + this.op + ",\n" +                           //
                "    sequence=" + this.sequence + ",\n" +               //
                "    status=" + this.status + ",\n" +                   //
                "    associationID=" + this.associationID + ",\n" +     //
                "    offset=" + this.offset + ",\n" +                   //
                "    count=" + this.count + "\n" +                      //
                '}';
    }
}
