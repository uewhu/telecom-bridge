package com.telecom.simulator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diameter server-side handler.
 *
 * <p>Handles:</p>
 * <ul>
 *   <li>CER → CEA (immediate, Result-Code: 2001)</li>
 *   <li>DWR → DWA (immediate, Result-Code: 2001)</li>
 *   <li>CCR → CCA (after 50-100ms simulated processing delay, Result-Code: 2001)</li>
 * </ul>
 *
 * <p>This handler is NOT sharable — a new instance per connection.</p>
 */
@io.netty.channel.ChannelHandler.Sharable
public class DiameterServerHandler extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(DiameterServerHandler.class);

    // ── Diameter header constants (duplicated to keep simulator self-contained) ──
    private static final int HEADER_LEN              = 20;
    private static final int VERSION                 = 1;
    private static final int FLAG_REQUEST            = 0x80;
    private static final int FLAG_PROXIABLE          = 0x40;
    private static final int CMD_CAPABILITIES_EXCHANGE = 257;
    private static final int CMD_DEVICE_WATCHDOG     = 280;
    private static final int CMD_CREDIT_CONTROL      = 272;
    private static final int APP_ID_COMMON           = 0;
    private static final int APP_ID_CREDIT_CONTROL   = 4;
    private static final int RESULT_CODE             = 268;
    private static final int ORIGIN_HOST             = 264;
    private static final int ORIGIN_REALM            = 296;
    private static final int AUTH_APP_ID             = 258;
    private static final int VENDOR_ID               = 266;
    private static final int HOST_IP_ADDRESS         = 257;
    private static final int AVP_FLAG_MANDATORY      = 0x40;
    private static final int GRANTED_SERVICE_UNIT    = 431;
    private static final int CC_TOTAL_OCTETS         = 421;
    private static final int CC_REQUEST_TYPE         = 416;
    private static final int CC_REQUEST_NUMBER       = 415;
    private static final int RESULT_SUCCESS          = 2001;

    private static final AtomicLong totalCcr = new AtomicLong(0);

    // ──────────────────────────────────────────────────────────────────────────
    // Decoding
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) return;
        int msgLen = in.getInt(in.readerIndex()) & 0x00FFFFFF;
        if (in.readableBytes() < msgLen) return;

        byte[] raw = new byte[msgLen];
        in.readBytes(raw);
        out.add(raw);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Message handling
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof byte[] raw)) return;

        ByteBuffer bb = ByteBuffer.wrap(raw);

        // Parse header
        int versionLen = bb.getInt();
        int msgLen     = versionLen & 0x00FFFFFF;
        int flagsCmd   = bb.getInt();
        int flags      = (flagsCmd >>> 24) & 0xFF;
        int cmdCode    = flagsCmd & 0x00FFFFFF;
        long appId     = bb.getInt() & 0xFFFFFFFFL;
        long hopByHop  = bb.getInt() & 0xFFFFFFFFL;
        long endToEnd  = bb.getInt() & 0xFFFFFFFFL;

        log.debug("Server received: cmd={} flags=0x{} appId={} hbh={}",
                cmdCode, Integer.toHexString(flags), appId, hopByHop);

        switch (cmdCode) {
            case CMD_CAPABILITIES_EXCHANGE -> handleCer(ctx, hopByHop, endToEnd);
            case CMD_DEVICE_WATCHDOG       -> handleDwr(ctx, hopByHop, endToEnd);
            case CMD_CREDIT_CONTROL        -> handleCcr(ctx, bb, hopByHop, endToEnd);
            default -> log.warn("Unknown command code: {}", cmdCode);
        }
    }

    // ── CER → CEA ─────────────────────────────────────────────────────────────
    private void handleCer(ChannelHandlerContext ctx, long hopByHop, long endToEnd) {
        log.info("CER received from {}. Sending CEA.", ctx.channel().remoteAddress());
        ByteBuf cea = buildCea(hopByHop, endToEnd);
        ctx.writeAndFlush(cea);
    }

    private ByteBuf buildCea(long hopByHop, long endToEnd) {
        List<byte[]> avps = new ArrayList<>();
        avps.add(encodeUnsigned32(RESULT_CODE,   RESULT_SUCCESS));
        avps.add(encodeUtf8String(ORIGIN_HOST,   "simulator.telecom.com"));
        avps.add(encodeUtf8String(ORIGIN_REALM,  "telecom.com"));
        avps.add(encodeIpv4Address(HOST_IP_ADDRESS, new byte[]{127, 0, 0, 1}));
        avps.add(encodeUnsigned32(VENDOR_ID,      0));
        avps.add(encodeUnsigned32(AUTH_APP_ID,    APP_ID_CREDIT_CONTROL));
        return buildMessage(0, CMD_CAPABILITIES_EXCHANGE, APP_ID_COMMON, hopByHop, endToEnd, avps);
    }

    // ── DWR → DWA ─────────────────────────────────────────────────────────────
    private void handleDwr(ChannelHandlerContext ctx, long hopByHop, long endToEnd) {
        log.debug("DWR received. Sending DWA.");
        List<byte[]> avps = new ArrayList<>();
        avps.add(encodeUnsigned32(RESULT_CODE,  RESULT_SUCCESS));
        avps.add(encodeUtf8String(ORIGIN_HOST,  "simulator.telecom.com"));
        avps.add(encodeUtf8String(ORIGIN_REALM, "telecom.com"));
        ByteBuf dwa = buildMessage(0, CMD_DEVICE_WATCHDOG, APP_ID_COMMON, hopByHop, endToEnd, avps);
        ctx.writeAndFlush(dwa);
    }

    // ── CCR → CCA (with 50-100ms simulated delay) ────────────────────────────
    private void handleCcr(ChannelHandlerContext ctx, ByteBuffer avpArea,
                            long hopByHop, long endToEnd) {
        long count = totalCcr.incrementAndGet();
        if (count % 1000 == 0) {
            log.info("Processed {} CCR requests total.", count);
        }
        log.debug("CCR received: hbh={}. Scheduling CCA after simulated delay.", hopByHop);

        // Simulated processing delay: 50-100ms
        long delayMs = ThreadLocalRandom.current().nextLong(50, 101);

        ctx.executor().schedule(() -> {
            ByteBuf cca = buildCca(hopByHop, endToEnd);
            ctx.writeAndFlush(cca).addListener(f -> {
                if (f.isSuccess()) {
                    log.debug("CCA sent: hbh={}", hopByHop);
                } else {
                    log.warn("CCA write failed for hbh={}: {}", hopByHop, f.cause().getMessage());
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private ByteBuf buildCca(long hopByHop, long endToEnd) {
        // Granted-Service-Unit: CC-Total-Octets = 10 MB
        byte[] ccTotalOctets  = encodeUnsigned64(CC_TOTAL_OCTETS, 10_485_760L);
        byte[] grantedSvcUnit = encodeGrouped(GRANTED_SERVICE_UNIT, ccTotalOctets);

        List<byte[]> avps = new ArrayList<>();
        avps.add(encodeUtf8String(ORIGIN_HOST,   "simulator.telecom.com"));
        avps.add(encodeUtf8String(ORIGIN_REALM,  "telecom.com"));
        avps.add(encodeUnsigned32(RESULT_CODE,   RESULT_SUCCESS));
        avps.add(encodeUnsigned32(CC_REQUEST_TYPE,   1));  // INITIAL_REQUEST
        avps.add(encodeUnsigned32(CC_REQUEST_NUMBER, 0));
        avps.add(grantedSvcUnit);

        return buildMessage(FLAG_PROXIABLE, CMD_CREDIT_CONTROL,
                APP_ID_CREDIT_CONTROL, hopByHop, endToEnd, avps);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Wire format helpers (self-contained, no shared library dependency)
    // ──────────────────────────────────────────────────────────────────────────

    private ByteBuf buildMessage(int flags, int cmdCode, long appId,
                                  long hopByHop, long endToEnd, List<byte[]> avps) {
        int avpLen = avps.stream().mapToInt(a -> a.length).sum();
        int msgLen = HEADER_LEN + avpLen;
        ByteBuf buf = Unpooled.buffer(msgLen);
        buf.writeInt((VERSION << 24) | msgLen);
        buf.writeInt((flags  << 24) | cmdCode);
        buf.writeInt((int)(appId   & 0xFFFFFFFFL));
        buf.writeInt((int)(hopByHop & 0xFFFFFFFFL));
        buf.writeInt((int)(endToEnd & 0xFFFFFFFFL));
        avps.forEach(buf::writeBytes);
        return buf;
    }

    /** Encode a Unsigned32 AVP (4-byte value, total wire = 12 bytes). */
    private byte[] encodeUnsigned32(int code, long value) {
        byte[] avp = new byte[12];
        ByteBuffer b = ByteBuffer.wrap(avp);
        b.putInt(code);
        b.putInt((AVP_FLAG_MANDATORY << 24) | 12);
        b.putInt((int)(value & 0xFFFFFFFFL));
        return avp;
    }

    /** Encode a Unsigned64 AVP (8-byte value, total wire = 16 bytes). */
    private byte[] encodeUnsigned64(int code, long value) {
        byte[] avp = new byte[16];
        ByteBuffer b = ByteBuffer.wrap(avp);
        b.putInt(code);
        b.putInt((AVP_FLAG_MANDATORY << 24) | 16);
        b.putLong(value);
        return avp;
    }

    /** Encode a UTF8String AVP with 4-byte padding. */
    private byte[] encodeUtf8String(int code, String value) {
        byte[] data    = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int    avpLen  = 8 + data.length;
        int    wireLen = padTo4(avpLen);
        byte[] avp     = new byte[wireLen];
        ByteBuffer b   = ByteBuffer.wrap(avp);
        b.putInt(code);
        b.putInt((AVP_FLAG_MANDATORY << 24) | avpLen);
        b.put(data);
        return avp;
    }

    /** Encode an IPv4 Address AVP. */
    private byte[] encodeIpv4Address(int code, byte[] ipv4) {
        // AddressType(2) + IP(4) = 6 data bytes → wire=16 (8+6 padded to 16)
        byte[] avp = new byte[16];
        ByteBuffer b = ByteBuffer.wrap(avp);
        b.putInt(code);
        b.putInt((AVP_FLAG_MANDATORY << 24) | 14);
        b.put((byte) 0x00).put((byte) 0x01); // AddressType=IPv4
        b.put(ipv4);
        return avp;
    }

    /** Encode a Grouped AVP from pre-encoded child bytes. */
    private byte[] encodeGrouped(int code, byte[]... children) {
        int dataLen = 0;
        for (byte[] c : children) dataLen += c.length;
        int avpLen  = 8 + dataLen;
        int wireLen = padTo4(avpLen);
        byte[] avp  = new byte[wireLen];
        ByteBuffer b = ByteBuffer.wrap(avp);
        b.putInt(code);
        b.putInt((AVP_FLAG_MANDATORY << 24) | avpLen);
        for (byte[] c : children) b.put(c);
        return avp;
    }

    private static int padTo4(int n) {
        return (n + 3) & ~3;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Channel lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("New Diameter client connected: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Diameter client disconnected: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception on channel {}: {}", ctx.channel().remoteAddress(), cause.getMessage(), cause);
        ctx.close();
    }
}
