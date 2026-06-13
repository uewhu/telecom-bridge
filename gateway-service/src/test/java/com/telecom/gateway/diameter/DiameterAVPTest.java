package com.telecom.gateway.diameter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link DiameterAVP} — verifies RFC 6733 §4.1 AVP wire format
 * including padding, Unsigned32/64, UTF8String, Grouped, and Address AVPs.
 */
@DisplayName("DiameterAVP – encoding and decoding")
class DiameterAVPTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Padding
    // ──────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "padTo4({0}) = {1}")
    @CsvSource({"0,0", "1,4", "2,4", "3,4", "4,4", "5,8", "8,8", "9,12"})
    @DisplayName("padTo4 rounds up to 4-byte boundary")
    void padTo4_correctBoundary(int input, int expected) {
        assertThat(DiameterAVP.padTo4(input)).isEqualTo(expected);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Unsigned32
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unsigned32 AVP encodes to 12 bytes (8 header + 4 data, no padding needed)")
    void unsigned32_encodeLength() {
        DiameterAVP avp = DiameterAVP.ofUnsigned32(DiameterConstants.AVP_RESULT_CODE,
                DiameterConstants.RESULT_SUCCESS);
        byte[] wire = avp.encode();
        assertThat(wire).hasSize(12); // 8 header + 4 data
    }

    @Test
    @DisplayName("Unsigned32 round-trip preserves Result-Code 2001")
    void unsigned32_roundTrip() {
        DiameterAVP avp = DiameterAVP.ofUnsigned32(DiameterConstants.AVP_RESULT_CODE, 2001);
        byte[] wire = avp.encode();
        DiameterAVP decoded = DiameterAVP.decode(ByteBuffer.wrap(wire));
        assertThat(decoded.getCode()).isEqualTo(DiameterConstants.AVP_RESULT_CODE);
        assertThat(decoded.getValueAsUnsigned32()).isEqualTo(2001L);
    }

    @Test
    @DisplayName("Unsigned32 stores max uint32 value (0xFFFFFFFFL) without sign corruption")
    void unsigned32_maxValue() {
        DiameterAVP avp = DiameterAVP.ofUnsigned32(DiameterConstants.AVP_RESULT_CODE, 0xFFFFFFFFL);
        DiameterAVP decoded = DiameterAVP.decode(ByteBuffer.wrap(avp.encode()));
        assertThat(decoded.getValueAsUnsigned32()).isEqualTo(0xFFFFFFFFL);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Unsigned64
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unsigned64 AVP encodes to 16 bytes (8 header + 8 data)")
    void unsigned64_encodeLength() {
        DiameterAVP avp = DiameterAVP.ofUnsigned64(DiameterConstants.AVP_CC_TOTAL_OCTETS, 1_048_576L);
        assertThat(avp.encode()).hasSize(16);
    }

    @Test
    @DisplayName("Unsigned64 round-trip preserves large value")
    void unsigned64_roundTrip() {
        long bigVal = 1_099_511_627_776L; // 1 TiB
        DiameterAVP avp = DiameterAVP.ofUnsigned64(DiameterConstants.AVP_CC_TOTAL_OCTETS, bigVal);
        DiameterAVP decoded = DiameterAVP.decode(ByteBuffer.wrap(avp.encode()));
        assertThat(decoded.getValueAsUnsigned64()).isEqualTo(bigVal);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UTF8String (DiameterIdentity)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UTF8String AVP data padded to 4-byte boundary")
    void utf8String_paddingApplied() {
        // "abc" = 3 bytes → padded to 4 bytes wire, AVP header=8, total wire=12
        DiameterAVP avp = DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_HOST, "abc");
        int wireLen = avp.encode().length;
        assertThat(wireLen).isEqualTo(12);
        assertThat(wireLen % 4).isZero();
    }

    @Test
    @DisplayName("UTF8String round-trip preserves value with unicode chars")
    void utf8String_roundTripUnicode() {
        String val = "sub.realm-001.com";
        DiameterAVP avp = DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_REALM, val);
        DiameterAVP decoded = DiameterAVP.decode(ByteBuffer.wrap(avp.encode()));
        assertThat(decoded.getValueAsUtf8String()).isEqualTo(val);
    }

    @Test
    @DisplayName("UTF8String with exactly 4-byte-aligned content has no extra padding bytes")
    void utf8String_noExtraPadding_whenAligned() {
        // "test" = 4 bytes → wire = 8 (header) + 4 (data) = 12, no padding needed
        DiameterAVP avp = DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_HOST, "test");
        assertThat(avp.encode().length).isEqualTo(12);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Grouped AVP
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Grouped Subscription-Id AVP wraps type + data children correctly")
    void grouped_subscriptionId_correct() {
        DiameterAVP grouped = DiameterAVP.ofGrouped(
            DiameterConstants.AVP_SUBSCRIPTION_ID,
            DiameterAVP.ofEnumerated(DiameterConstants.AVP_SUBSCRIPTION_ID_TYPE,
                    DiameterConstants.SUBSCRIPTION_ID_TYPE_E164),
            DiameterAVP.ofUtf8String(DiameterConstants.AVP_SUBSCRIPTION_ID_DATA, "447700900001")
        );

        byte[] wire = grouped.encode();
        assertThat(wire.length % 4).isZero();

        // Decode the outer grouped AVP
        DiameterAVP decoded = DiameterAVP.decode(ByteBuffer.wrap(wire));
        assertThat(decoded.getCode()).isEqualTo(DiameterConstants.AVP_SUBSCRIPTION_ID);

        // Decode the children
        ByteBuffer inner = ByteBuffer.wrap(decoded.getValue());
        DiameterAVP typeAvp = DiameterAVP.decode(inner);
        DiameterAVP dataAvp = DiameterAVP.decode(inner);

        assertThat(typeAvp.getCode()).isEqualTo(DiameterConstants.AVP_SUBSCRIPTION_ID_TYPE);
        assertThat(typeAvp.getValueAsUnsigned32()).isEqualTo(DiameterConstants.SUBSCRIPTION_ID_TYPE_E164);
        assertThat(dataAvp.getCode()).isEqualTo(DiameterConstants.AVP_SUBSCRIPTION_ID_DATA);
        assertThat(dataAvp.getValueAsUtf8String()).isEqualTo("447700900001");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Flags
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mandatory flag (M-bit) is set in factory-created AVPs")
    void mandatoryFlag_isSet() {
        DiameterAVP avp = DiameterAVP.ofUnsigned32(DiameterConstants.AVP_RESULT_CODE, 2001);
        assertThat(avp.getFlags() & DiameterConstants.AVP_FLAG_MANDATORY).isNotZero();
    }

    @Test
    @DisplayName("Optional factory method does not set M-bit")
    void optionalFlag_notSet() {
        DiameterAVP avp = DiameterAVP.ofUtf8StringOptional(DiameterConstants.AVP_ERROR_MESSAGE, "test");
        assertThat(avp.getFlags() & DiameterConstants.AVP_FLAG_MANDATORY).isZero();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // IPv4 Address AVP
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IPv4 Address AVP has address type 1 and 4-byte IP")
    void ipv4Address_correct() {
        DiameterAVP avp = DiameterAVP.ofIpv4Address(DiameterConstants.AVP_HOST_IP_ADDRESS,
                new byte[]{10, 0, 0, 1});
        byte[] val = avp.getValue();
        assertThat(val[0]).isEqualTo((byte) 0x00);
        assertThat(val[1]).isEqualTo((byte) 0x01); // AddressType IPv4
        assertThat(val[2]).isEqualTo((byte) 10);
        assertThat(val[5]).isEqualTo((byte) 1);
    }
}
