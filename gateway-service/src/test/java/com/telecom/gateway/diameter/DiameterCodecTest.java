package com.telecom.gateway.diameter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link DiameterCodec} — verifies RFC 6733 compliant encoding/decoding.
 */
@DisplayName("DiameterCodec – encoding and decoding")
class DiameterCodecTest {

    private DiameterCodec codec;

    @BeforeEach
    void setUp() {
        codec = new DiameterCodec();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Header encoding
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Encoded CER has correct 20-byte header with request flag set")
    void encodeCer_headerIsCorrect() {
        DiameterMessage cer = DiameterMessage.builder()
                .requestFlag()
                .commandCode(DiameterConstants.CMD_CAPABILITIES_EXCHANGE)
                .applicationId(DiameterConstants.APP_ID_COMMON)
                .hopByHopId(0x0001L)
                .endToEndId(0x0002L)
                .build();

        ByteBuf buf = codec.encode(cer);
        try {
            // Byte 0 = version (1)
            assertThat(buf.getByte(0)).isEqualTo((byte) 1);

            // Bytes 1-3 = message length (20 = header only, no AVPs)
            int msgLen = ((buf.getByte(1) & 0xFF) << 16)
                       | ((buf.getByte(2) & 0xFF) << 8)
                       |  (buf.getByte(3) & 0xFF);
            assertThat(msgLen).isEqualTo(DiameterConstants.HEADER_LENGTH);

            // Byte 4 = flags (0x80 = request)
            assertThat(buf.getByte(4) & 0xFF).isEqualTo(DiameterConstants.FLAG_REQUEST);

            // Bytes 5-7 = command code (257 = CER)
            int cmd = ((buf.getByte(5) & 0xFF) << 16)
                    | ((buf.getByte(6) & 0xFF) << 8)
                    |  (buf.getByte(7) & 0xFF);
            assertThat(cmd).isEqualTo(DiameterConstants.CMD_CAPABILITIES_EXCHANGE);

            // Bytes 8-11 = Application-ID (0)
            assertThat(buf.getInt(8)).isEqualTo(0);

            // Bytes 12-15 = Hop-by-Hop ID
            assertThat(buf.getInt(12)).isEqualTo(1);

            // Bytes 16-19 = End-to-End ID
            assertThat(buf.getInt(16)).isEqualTo(2);
        } finally {
            buf.release();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Round-trip: encode → decode
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CCR round-trip: encode then decode preserves all fields")
    void encodeDecode_ccr_roundTrip() {
        DiameterAVP sessionAvp      = DiameterAVP.ofUtf8String(DiameterConstants.AVP_SESSION_ID, "test-session-42");
        DiameterAVP originHostAvp   = DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_HOST, "client.telecom.com");
        DiameterAVP originRealmAvp  = DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_REALM, "telecom.com");
        DiameterAVP ccReqTypeAvp    = DiameterAVP.ofEnumerated(DiameterConstants.AVP_CC_REQUEST_TYPE,
                DiameterConstants.CC_REQUEST_TYPE_INITIAL);

        DiameterMessage original = DiameterMessage.builder()
                .requestFlag()
                .proxiableFlag()
                .commandCode(DiameterConstants.CMD_CREDIT_CONTROL)
                .applicationId(DiameterConstants.APP_ID_CREDIT_CONTROL)
                .hopByHopId(0xDEADBEEFL)
                .endToEndId(0xCAFEBABEL)
                .addAvp(sessionAvp)
                .addAvp(originHostAvp)
                .addAvp(originRealmAvp)
                .addAvp(ccReqTypeAvp)
                .build();

        ByteBuf buf = codec.encode(original);
        try {
            DiameterMessage decoded = codec.decode(buf);

            assertThat(decoded).isNotNull();
            assertThat(decoded.getCommandCode()).isEqualTo(DiameterConstants.CMD_CREDIT_CONTROL);
            assertThat(decoded.getApplicationId()).isEqualTo(DiameterConstants.APP_ID_CREDIT_CONTROL);
            assertThat(decoded.getHopByHopId()).isEqualTo(0xDEADBEEFL);
            assertThat(decoded.getEndToEndId()).isEqualTo(0xCAFEBABEL);
            assertThat(decoded.isRequest()).isTrue();
            assertThat(decoded.getAvps()).hasSize(4);

            // Verify Session-Id
            Optional<DiameterAVP> session = decoded.findAvp(DiameterConstants.AVP_SESSION_ID);
            assertThat(session).isPresent();
            assertThat(session.get().getValueAsUtf8String()).isEqualTo("test-session-42");

            // Verify CC-Request-Type
            Optional<DiameterAVP> ccType = decoded.findAvp(DiameterConstants.AVP_CC_REQUEST_TYPE);
            assertThat(ccType).isPresent();
            assertThat(ccType.get().getValueAsUnsigned32()).isEqualTo(DiameterConstants.CC_REQUEST_TYPE_INITIAL);
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("decode returns null when buffer has insufficient bytes")
    void decode_insufficientBytes_returnsNull() {
        ByteBuf smallBuf = Unpooled.buffer(10);
        smallBuf.writeBytes(new byte[10]);
        assertThat(codec.decode(smallBuf)).isNull();
        smallBuf.release();
    }

    @Test
    @DisplayName("decode returns null when buffer contains a partial message")
    void decode_partialMessage_returnsNull() {
        // Only write header, claim message length = 100 but don't write AVPs
        ByteBuf buf = Unpooled.buffer(20);
        buf.writeInt((1 << 24) | 100);   // version=1, length=100
        buf.writeInt((0x80 << 24) | 272);
        buf.writeInt(4);
        buf.writeInt(1);
        buf.writeInt(2);
        // 80 bytes of AVP data missing — decode should reset and return null
        assertThat(codec.decode(buf)).isNull();
        assertThat(buf.readerIndex()).isEqualTo(0); // reader index must be reset
        buf.release();
    }

    @Test
    @DisplayName("peekMessageLength returns -1 when fewer than 4 bytes available")
    void peekMessageLength_tooFewBytes() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{0x01, 0x00});
        assertThat(codec.peekMessageLength(buf)).isEqualTo(-1);
        buf.release();
    }
}
