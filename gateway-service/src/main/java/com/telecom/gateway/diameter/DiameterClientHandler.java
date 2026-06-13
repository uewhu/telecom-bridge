package com.telecom.gateway.diameter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty {@link io.netty.channel.ChannelHandler} that handles all inbound Diameter
 * messages, routing them to the pending-request map or dispatching CER/DWR responses.
 *
 * <p>This handler is NOT sharable — a new instance is created per channel.</p>
 */
public class DiameterClientHandler extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(DiameterClientHandler.class);

    /** Shared map: Hop-by-Hop ID → pending CompletableFuture waiting for the answer. */
    private final ConcurrentHashMap<Long, CompletableFuture<DiameterMessage>> pendingRequests;

    /** CompletableFuture that completes when CER/CEA handshake succeeds. */
    private final CompletableFuture<Void> cerFuture;

    private final DiameterCodec codec = new DiameterCodec();

    public DiameterClientHandler(
            ConcurrentHashMap<Long, CompletableFuture<DiameterMessage>> pendingRequests,
            CompletableFuture<Void> cerFuture) {
        this.pendingRequests = pendingRequests;
        this.cerFuture       = cerFuture;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Decoding
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Ensure we have enough bytes for a complete message
        int msgLen = codec.peekMessageLength(in);
        if (msgLen < 0 || in.readableBytes() < msgLen) {
            return; // wait for more bytes
        }

        DiameterMessage msg = codec.decode(in);
        if (msg != null) {
            out.add(msg);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Routing
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msgObj) {
        if (!(msgObj instanceof DiameterMessage msg)) {
            ctx.fireChannelRead(msgObj);
            return;
        }

        int  cmd   = msg.getCommandCode();
        long hbhId = msg.getHopByHopId();

        log.debug("Received Diameter message: cmd={} flags=0x{} hbh={}",
                cmd, Integer.toHexString(msg.getFlags()), hbhId);

        switch (cmd) {
            case DiameterConstants.CMD_CAPABILITIES_EXCHANGE -> handleCea(msg);
            case DiameterConstants.CMD_DEVICE_WATCHDOG       -> handleDwa(ctx, msg);
            case DiameterConstants.CMD_CREDIT_CONTROL        -> handleCca(msg);
            default -> log.warn("Unhandled Diameter command code: {}", cmd);
        }
    }

    private void handleCea(DiameterMessage cea) {
        long resultCode = cea.getResultCode();
        log.info("CEA received: Result-Code={}", resultCode);
        if (resultCode == DiameterConstants.RESULT_SUCCESS) {
            cerFuture.complete(null);
        } else {
            cerFuture.completeExceptionally(
                new DiameterException("CEA rejected with Result-Code: " + resultCode));
        }
    }

    private void handleDwa(ChannelHandlerContext ctx, DiameterMessage dwr) {
        // If this is actually a DWR (request), we shouldn't receive it as a client,
        // but handle gracefully. If it's a DWA (answer), complete the watchdog future.
        long hbhId = dwr.getHopByHopId();
        CompletableFuture<DiameterMessage> pending = pendingRequests.remove(hbhId);
        if (pending != null) {
            pending.complete(dwr);
        }
        log.debug("DWA received for hbh={}", hbhId);
    }

    private void handleCca(DiameterMessage cca) {
        long hbhId = cca.getHopByHopId();
        CompletableFuture<DiameterMessage> pending = pendingRequests.remove(hbhId);
        if (pending != null) {
            log.debug("CCA matched to pending request hbh={} Result-Code={}",
                    hbhId, cca.getResultCode());
            pending.complete(cca);
        } else {
            log.warn("Received CCA for unknown Hop-by-Hop ID: {}. Message discarded.", hbhId);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Connection lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("Diameter connection lost. Failing {} pending requests.", pendingRequests.size());
        pendingRequests.forEach((hbhId, future) ->
            future.completeExceptionally(new DiameterException(
                "Diameter connection lost while waiting for response to hbh=" + hbhId)));
        pendingRequests.clear();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Diameter channel exception: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
