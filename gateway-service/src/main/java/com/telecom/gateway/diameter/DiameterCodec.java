package com.telecom.gateway.diameter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes Diameter messages to/from raw bytes per RFC 6733.
 *
 * <p>This class is stateless and thread-safe.</p>
 */
public class DiameterCodec {

    private static final Logger log = LoggerFactory.getLogger(DiameterCodec.class);

    // ──────────────────────────────────────────────────────────────────────────
    // Encoding
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Encodes a {@link DiameterMessage} to a Netty {@link ByteBuf}.
     *
     * <p>The caller is responsible for releasing the returned buffer.</p>
     */
    public ByteBuf encode(DiameterMessage msg) {
        // First, encode all AVPs to determine total message length
        List<byte[]> encodedAvps = new ArrayList<>(msg.getAvps().size());
        int avpTotalLen = 0;
        for (DiameterAVP avp : msg.getAvps()) {
            byte[] encoded = avp.encode();
            encodedAvps.add(encoded);
            avpTotalLen += encoded.length;
        }

        int msgLength = DiameterConstants.HEADER_LENGTH + avpTotalLen;
        ByteBuf buf   = Unpooled.buffer(msgLength);

        // Version (1 byte) + Message Length (3 bytes) packed as 4-byte int
        buf.writeInt(((DiameterConstants.DIAMETER_VERSION & 0xFF) << 24) | (msgLength & 0x00FFFFFF));

        // Command Flags (1 byte) + Command Code (3 bytes)
        buf.writeInt(((msg.getFlags() & 0xFF) << 24) | (msg.getCommandCode() & 0x00FFFFFF));

        // Application-ID (4 bytes)
        buf.writeInt((int) (msg.getApplicationId() & 0xFFFFFFFFL));

        // Hop-by-Hop Identifier (4 bytes)
        buf.writeInt((int) (msg.getHopByHopId() & 0xFFFFFFFFL));

        // End-to-End Identifier (4 bytes)
        buf.writeInt((int) (msg.getEndToEndId() & 0xFFFFFFFFL));

        // AVPs
        for (byte[] avp : encodedAvps) {
            buf.writeBytes(avp);
        }

        log.trace("Encoded Diameter message: cmd={} flags=0x{} hbh={} len={}",
                msg.getCommandCode(),
                Integer.toHexString(msg.getFlags()),
                msg.getHopByHopId(),
                msgLength);
        return buf;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Decoding
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Decodes a Diameter message from a Netty {@link ByteBuf}.
     *
     * <p>The buffer's reader index is advanced past the decoded message.
     * Returns {@code null} if there are not enough bytes to form a complete message.</p>
     */
    public DiameterMessage decode(ByteBuf buf) {
        if (buf.readableBytes() < DiameterConstants.HEADER_LENGTH) {
            log.debug("Insufficient bytes for Diameter header: {} < {}",
                    buf.readableBytes(), DiameterConstants.HEADER_LENGTH);
            return null;
        }

        buf.markReaderIndex();

        // Version (1) + Length (3)
        int versionLen = buf.readInt();
        int version    = (versionLen >>> 24) & 0xFF;
        int msgLength  = versionLen & 0x00FFFFFF;

        if (version != DiameterConstants.DIAMETER_VERSION) {
            log.warn("Unsupported Diameter version: {}", version);
            buf.resetReaderIndex();
            return null;
        }

        if (buf.readableBytes() < msgLength - 4) { // already read 4 bytes
            log.debug("Incomplete Diameter message: need {} more bytes",
                    msgLength - 4 - buf.readableBytes());
            buf.resetReaderIndex();
            return null;
        }

        // Flags (1) + Command Code (3)
        int flagsCmd    = buf.readInt();
        int flags       = (flagsCmd >>> 24) & 0xFF;
        int commandCode = flagsCmd & 0x00FFFFFF;

        long applicationId = buf.readUnsignedInt();
        long hopByHopId    = buf.readUnsignedInt();
        long endToEndId    = buf.readUnsignedInt();

        // Parse AVPs
        int avpAreaLen = msgLength - DiameterConstants.HEADER_LENGTH;
        byte[] avpBytes = new byte[avpAreaLen];
        buf.readBytes(avpBytes);

        List<DiameterAVP> avps = new ArrayList<>();
        if (avpAreaLen > 0) {
            ByteBuffer avpBuf = ByteBuffer.wrap(avpBytes);
            while (avpBuf.hasRemaining() && avpBuf.remaining() >= 8) {
                try {
                    DiameterAVP avp = DiameterAVP.decode(avpBuf);
                    avps.add(avp);
                } catch (Exception e) {
                    log.warn("Failed to decode AVP at position {}: {}", avpBuf.position(), e.getMessage());
                    break;
                }
            }
        }

        DiameterMessage msg = DiameterMessage.builder()
                .version(version)
                .flags(flags)
                .commandCode(commandCode)
                .applicationId(applicationId)
                .hopByHopId(hopByHopId)
                .endToEndId(endToEndId)
                .addAvps(avps)
                .build();

        log.trace("Decoded Diameter message: cmd={} flags=0x{} hbh={} avps={}",
                commandCode, Integer.toHexString(flags), hopByHopId, avps.size());
        return msg;
    }

    /**
     * Peeks at the message length field without advancing the reader index.
     * Returns -1 if not enough bytes are available.
     */
    public int peekMessageLength(ByteBuf buf) {
        if (buf.readableBytes() < 4) return -1;
        int versionLen = buf.getInt(buf.readerIndex());
        return versionLen & 0x00FFFFFF;
    }
}
