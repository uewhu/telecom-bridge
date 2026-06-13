package com.telecom.gateway.diameter;

import com.telecom.gateway.config.DiameterProperties;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.net.InetAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking Diameter TCP client built on top of Netty.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Bootstrap a persistent TCP connection to the Diameter server.</li>
 *   <li>Perform CER/CEA capabilities exchange on startup.</li>
 *   <li>Expose {@link #sendCCR(DiameterMessage)} for async request dispatch.</li>
 *   <li>Match answers to requests via Hop-by-Hop ID using {@link ConcurrentHashMap}.</li>
 *   <li>Auto-reconnect on connection loss with exponential backoff.</li>
 * </ul>
 */
@Component
public class DiameterClient {

    private static final Logger log = LoggerFactory.getLogger(DiameterClient.class);

    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int RECONNECT_DELAY_BASE_MS = 500;

    private final DiameterProperties props;
    private final DiameterCodec codec = new DiameterCodec();

    /** Pending CCR requests waiting for their CCA. Key = Hop-by-Hop ID. */
    private final ConcurrentHashMap<Long, CompletableFuture<DiameterMessage>> pendingRequests
            = new ConcurrentHashMap<>(4096);

    /** Monotonically increasing Hop-by-Hop identifier (wraps at 2^32). */
    private final AtomicLong hopByHopCounter = new AtomicLong(
            ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE));

    /** End-to-End identifier (fixed per RFC 6733 §3 — should be unique per session). */
    private final AtomicInteger endToEndCounter = new AtomicInteger(
            ThreadLocalRandom.current().nextInt());

    private final AtomicReference<Channel> channelRef    = new AtomicReference<>();
    private final AtomicReference<State>   stateRef      = new AtomicReference<>(State.DISCONNECTED);

    private NioEventLoopGroup workerGroup;
    private Bootstrap          bootstrap;

    enum State { DISCONNECTED, CONNECTING, HANDSHAKING, READY, RECONNECTING }

    public DiameterClient(DiameterProperties props) {
        this.props = props;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        log.info("Initialising Diameter client → {}:{}", props.getServerHost(), props.getServerPort());
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);
        bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY,  true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
        connectWithRetry(0);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        log.info("Shutting down Diameter client. Draining {} pending requests.", pendingRequests.size());
        pendingRequests.forEach((id, cf) ->
            cf.completeExceptionally(new DiameterException("Client is shutting down")));
        pendingRequests.clear();
        Channel ch = channelRef.get();
        if (ch != null) {
            ch.close().sync();
        }
        workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        log.info("Diameter client shut down.");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Connect + CER/CEA handshake
    // ──────────────────────────────────────────────────────────────────────────

    private void connectWithRetry(int attempt) {
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            log.error("Diameter server unreachable after {} attempts. Giving up.", attempt);
            return;
        }
        stateRef.set(attempt == 0 ? State.CONNECTING : State.RECONNECTING);
        long delayMs = RECONNECT_DELAY_BASE_MS * (long) Math.pow(2, Math.min(attempt, 6));
        if (attempt > 0) {
            log.info("Reconnecting to Diameter server (attempt {}/{}) in {}ms...",
                    attempt, MAX_RECONNECT_ATTEMPTS, delayMs);
        }

        CompletableFuture<Void> cerFuture = new CompletableFuture<>();

        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline()
                  .addLast(new IdleStateHandler(0, 0, (int)(props.getWatchdogIntervalMs() / 1000)))
                  .addLast(new DiameterClientHandler(pendingRequests, cerFuture));
            }
        });

        workerGroup.schedule(() -> {
            bootstrap.connect(props.getServerHost(), props.getServerPort())
                .addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        Channel ch = future.channel();
                        channelRef.set(ch);
                        stateRef.set(State.HANDSHAKING);
                        log.info("TCP connection established to {}:{}",
                                props.getServerHost(), props.getServerPort());
                        sendCer(ch);

                        ch.closeFuture().addListener(ignored -> {
                            log.warn("Diameter TCP connection closed.");
                            channelRef.set(null);
                            stateRef.set(State.DISCONNECTED);
                            // Trigger reconnect
                            connectWithRetry(0);
                        });

                        cerFuture.whenComplete((v, ex) -> {
                            if (ex != null) {
                                log.error("CER/CEA handshake failed: {}", ex.getMessage());
                                ch.close();
                            } else {
                                stateRef.set(State.READY);
                                log.info("Diameter connection READY (CER/CEA successful).");
                            }
                        });
                    } else {
                        log.warn("Diameter TCP connect failed: {}", future.cause().getMessage());
                        connectWithRetry(attempt + 1);
                    }
                });
        }, attempt == 0 ? 0 : delayMs, TimeUnit.MILLISECONDS);
    }

    private void sendCer(Channel ch) {
        long hbhId = nextHopByHopId();
        long e2eId = nextEndToEndId();

        DiameterMessage cer = DiameterMessage.builder()
                .requestFlag()
                .proxiableFlag()
                .commandCode(DiameterConstants.CMD_CAPABILITIES_EXCHANGE)
                .applicationId(DiameterConstants.APP_ID_COMMON)
                .hopByHopId(hbhId)
                .endToEndId(e2eId)
                .addAvp(DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_HOST, props.getOriginHost()))
                .addAvp(DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_REALM, props.getOriginRealm()))
                .addAvp(buildHostIpAddressAvp())
                .addAvp(DiameterAVP.ofUnsigned32(DiameterConstants.AVP_VENDOR_ID, 0))
                .addAvp(DiameterAVP.ofUnsigned32(DiameterConstants.AVP_AUTH_APPLICATION_ID,
                        DiameterConstants.APP_ID_CREDIT_CONTROL))
                .build();

        ch.writeAndFlush(codec.encode(cer));
        log.debug("CER sent: hbh={}", hbhId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sends a Diameter CCR and returns a {@link CompletableFuture} that will
     * complete with the corresponding CCA when received.
     *
     * <p>The future will fail exceptionally if:
     * <ul>
     *   <li>The Diameter client is not in READY state.</li>
     *   <li>The channel write fails.</li>
     *   <li>The connection is lost before a response arrives.</li>
     * </ul>
     */
    public CompletableFuture<DiameterMessage> sendCCR(DiameterMessage ccr) {
        if (stateRef.get() != State.READY) {
            CompletableFuture<DiameterMessage> failed = new CompletableFuture<>();
            failed.completeExceptionally(new DiameterException(
                "Diameter client not ready. Current state: " + stateRef.get()));
            return failed;
        }

        Channel ch = channelRef.get();
        if (ch == null || !ch.isActive()) {
            CompletableFuture<DiameterMessage> failed = new CompletableFuture<>();
            failed.completeExceptionally(new DiameterException("Diameter channel is not active."));
            return failed;
        }

        long hbhId = ccr.getHopByHopId();
        CompletableFuture<DiameterMessage> responseFuture = new CompletableFuture<>();
        pendingRequests.put(hbhId, responseFuture);

        ch.writeAndFlush(codec.encode(ccr)).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                pendingRequests.remove(hbhId);
                responseFuture.completeExceptionally(new DiameterException(
                    "Failed to write CCR to channel: " + writeFuture.cause().getMessage(),
                    writeFuture.cause()));
                log.error("CCR write failed for hbh={}: {}", hbhId, writeFuture.cause().getMessage());
            } else {
                log.debug("CCR sent: hbh={}", hbhId);
            }
        });

        return responseFuture;
    }

    /**
     * Sends a Device-Watchdog-Request and returns a CompletableFuture for the DWA.
     */
    public CompletableFuture<DiameterMessage> sendDWR() {
        Channel ch = channelRef.get();
        if (ch == null || !ch.isActive()) {
            return CompletableFuture.failedFuture(
                new DiameterException("Diameter channel is not active for DWR."));
        }

        long hbhId = nextHopByHopId();
        long e2eId = nextEndToEndId();

        DiameterMessage dwr = DiameterMessage.builder()
                .requestFlag()
                .commandCode(DiameterConstants.CMD_DEVICE_WATCHDOG)
                .applicationId(DiameterConstants.APP_ID_COMMON)
                .hopByHopId(hbhId)
                .endToEndId(e2eId)
                .addAvp(DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_HOST, props.getOriginHost()))
                .addAvp(DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_REALM, props.getOriginRealm()))
                .addAvp(DiameterAVP.ofUnsigned32(DiameterConstants.AVP_ORIGIN_STATE_ID,
                        System.currentTimeMillis() / 1000))
                .build();

        CompletableFuture<DiameterMessage> dwaFuture = new CompletableFuture<>();
        pendingRequests.put(hbhId, dwaFuture);
        ch.writeAndFlush(codec.encode(dwr));
        log.debug("DWR sent: hbh={}", hbhId);
        return dwaFuture;
    }

    /**
     * Builds a new CCR message with next Hop-by-Hop / End-to-End IDs assigned.
     */
    public long nextHopByHopId() {
        // Wrap at 32-bit boundary per RFC 6733
        return hopByHopCounter.getAndIncrement() & 0xFFFFFFFFL;
    }

    public long nextEndToEndId() {
        return endToEndCounter.getAndIncrement() & 0xFFFFFFFFL;
    }

    public boolean isReady() {
        return stateRef.get() == State.READY;
    }

    public int getPendingRequestCount() {
        return pendingRequests.size();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private DiameterAVP buildHostIpAddressAvp() {
        try {
            byte[] ipv4 = InetAddress.getLocalHost().getAddress();
            if (ipv4.length == 4) {
                return DiameterAVP.ofIpv4Address(DiameterConstants.AVP_HOST_IP_ADDRESS, ipv4);
            }
        } catch (Exception e) {
            log.warn("Could not determine local host IP, using 127.0.0.1");
        }
        return DiameterAVP.ofIpv4Address(DiameterConstants.AVP_HOST_IP_ADDRESS,
                new byte[]{127, 0, 0, 1});
    }
}
