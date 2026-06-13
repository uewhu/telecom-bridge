package com.telecom.gateway.diameter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Represents a single Diameter AVP (Attribute-Value Pair) as defined in RFC 6733 §4.1.
 *
 * <p>Wire format (without Vendor-ID):</p>
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           AVP Code                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V M P r r r r r|                  AVP Length                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        Vendor-ID (opt)                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Data ...
 * +-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>AVP data is padded to the next 4-byte boundary on the wire, but the Length
 * field only counts the header + unpadded data bytes.</p>
 */
public class DiameterAVP {

    private final int    code;
    private final int    flags;
    private final long   vendorId;  // 0 = no vendor-specific flag
    private final byte[] value;

    // ──────────────────────────────────────────────────────────────────────────
    // Constructors
    // ──────────────────────────────────────────────────────────────────────────

    public DiameterAVP(int code, int flags, byte[] value) {
        this(code, flags, 0L, value);
    }

    public DiameterAVP(int code, int flags, long vendorId, byte[] value) {
        this.code     = code;
        this.flags    = flags;
        this.vendorId = vendorId;
        this.value    = value != null ? value.clone() : new byte[0];
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Factory helpers for common AVP data types
    // ──────────────────────────────────────────────────────────────────────────

    /** Creates a mandatory Unsigned32 AVP. */
    public static DiameterAVP ofUnsigned32(int code, long value) {
        byte[] data = new byte[4];
        ByteBuffer.wrap(data).putInt((int) (value & 0xFFFFFFFFL));
        return new DiameterAVP(code, DiameterConstants.AVP_FLAG_MANDATORY, data);
    }

    /** Creates a mandatory Unsigned64 AVP. */
    public static DiameterAVP ofUnsigned64(int code, long value) {
        byte[] data = new byte[8];
        ByteBuffer.wrap(data).putLong(value);
        return new DiameterAVP(code, DiameterConstants.AVP_FLAG_MANDATORY, data);
    }

    /** Creates a mandatory UTF8String / DiameterIdentity AVP. */
    public static DiameterAVP ofUtf8String(int code, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        return new DiameterAVP(code, DiameterConstants.AVP_FLAG_MANDATORY, data);
    }

    /** Creates a non-mandatory UTF8String AVP. */
    public static DiameterAVP ofUtf8StringOptional(int code, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        return new DiameterAVP(code, 0, data);
    }

    /** Creates a mandatory Enumerated AVP (encoded as Unsigned32). */
    public static DiameterAVP ofEnumerated(int code, int value) {
        return ofUnsigned32(code, value);
    }

    /**
     * Creates a mandatory Grouped AVP by concatenating the encoded form of each
     * child AVP (including their padding).
     */
    public static DiameterAVP ofGrouped(int code, DiameterAVP... children) {
        int totalSize = 0;
        byte[][] encoded = new byte[children.length][];
        for (int i = 0; i < children.length; i++) {
            encoded[i] = children[i].encode();
            totalSize += encoded[i].length;
        }
        byte[] data = new byte[totalSize];
        int pos = 0;
        for (byte[] e : encoded) {
            System.arraycopy(e, 0, data, pos, e.length);
            pos += e.length;
        }
        return new DiameterAVP(code, DiameterConstants.AVP_FLAG_MANDATORY, data);
    }

    /** Creates an Address AVP with an IPv4 address (AddressType=1, 4 bytes). */
    public static DiameterAVP ofIpv4Address(int code, byte[] ipv4) {
        if (ipv4.length != 4) throw new IllegalArgumentException("IPv4 address must be 4 bytes");
        byte[] data = new byte[6]; // AddressType(2) + IPv4(4)
        data[0] = 0x00;
        data[1] = 0x01; // AddressType = 1 (IPv4)
        System.arraycopy(ipv4, 0, data, 2, 4);
        return new DiameterAVP(code, DiameterConstants.AVP_FLAG_MANDATORY, data);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Wire encoding
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Encodes this AVP to its wire format including any necessary padding.
     *
     * <p>The returned byte array length is always a multiple of 4 bytes.</p>
     */
    public byte[] encode() {
        boolean hasVendor = (flags & DiameterConstants.AVP_FLAG_VENDOR_SPECIFIC) != 0;
        int headerLen = hasVendor ? 12 : 8;
        int avpLength = headerLen + value.length;           // Length field (no padding)
        int wireLen   = padTo4(avpLength);                  // Wire size (with padding)

        byte[] buf = new byte[wireLen];
        ByteBuffer bb = ByteBuffer.wrap(buf);

        bb.putInt(code);
        // Pack flags(1 byte) + avpLength(3 bytes) into one int
        bb.putInt(((flags & 0xFF) << 24) | (avpLength & 0x00FFFFFF));
        if (hasVendor) {
            bb.putInt((int) (vendorId & 0xFFFFFFFFL));
        }
        bb.put(value);
        // Remaining bytes in buf are already 0 (padding)
        return buf;
    }

    /**
     * Decodes a single AVP from the given ByteBuffer at its current position.
     * The buffer's position is advanced past the padded AVP.
     */
    public static DiameterAVP decode(ByteBuffer bb) {
        int code     = bb.getInt();
        int flagsLen = bb.getInt();
        int flags    = (flagsLen >>> 24) & 0xFF;
        int length   = flagsLen & 0x00FFFFFF;

        boolean hasVendor = (flags & DiameterConstants.AVP_FLAG_VENDOR_SPECIFIC) != 0;
        long vendorId = 0;
        if (hasVendor) {
            vendorId = bb.getInt() & 0xFFFFFFFFL;
        }

        int headerLen = hasVendor ? 12 : 8;
        int dataLen   = length - headerLen;
        if (dataLen < 0) dataLen = 0;

        byte[] data = new byte[dataLen];
        bb.get(data);

        // Skip padding bytes
        int paddedDataLen = padTo4(length) - headerLen;
        int padBytes = paddedDataLen - dataLen;
        if (padBytes > 0) {
            bb.position(bb.position() + padBytes);
        }

        return new DiameterAVP(code, flags, vendorId, data);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Value accessors
    // ──────────────────────────────────────────────────────────────────────────

    public long getValueAsUnsigned32() {
        return ByteBuffer.wrap(value).getInt() & 0xFFFFFFFFL;
    }

    public long getValueAsUnsigned64() {
        return ByteBuffer.wrap(value).getLong();
    }

    public String getValueAsUtf8String() {
        return new String(value, StandardCharsets.UTF_8);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Getters
    // ──────────────────────────────────────────────────────────────────────────

    public int    getCode()     { return code;     }
    public int    getFlags()    { return flags;    }
    public long   getVendorId() { return vendorId; }
    public byte[] getValue()    { return value.clone(); }

    // ──────────────────────────────────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────────────────────────────────

    /** Rounds n up to the nearest multiple of 4. */
    static int padTo4(int n) {
        return (n + 3) & ~3;
    }

    @Override
    public String toString() {
        return String.format("AVP{code=%d, flags=0x%02X, vendorId=%d, valueLen=%d}",
                code, flags, vendorId, value.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiameterAVP other)) return false;
        return code == other.code
            && flags == other.flags
            && vendorId == other.vendorId
            && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(code);
        result = 31 * result + Integer.hashCode(flags);
        result = 31 * result + Long.hashCode(vendorId);
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }
}
