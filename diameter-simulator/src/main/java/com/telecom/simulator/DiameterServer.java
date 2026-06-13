package com.telecom.simulator;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.TimeUnit;

/**
 * Netty TCP server that accepts Diameter client connections on port 3868.
 *
 * <p>Uses a boss/worker event loop model:
 * <ul>
 *   <li>Boss group: 1 thread — accepts new connections.</li>
 *   <li>Worker group: N threads — handles I/O for established connections.</li>
 * </ul>
 */
@Component
public class DiameterServer {

    private static final Logger log = LoggerFactory.getLogger(DiameterServer.class);

    @Value("${diameter.simulator.port:3868}")
    private int port;

    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private Channel           serverChannel;

    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup   = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());

        ServerBootstrap b = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 128)
            .option(ChannelOption.SO_REUSEADDR, true)
            .handler(new LoggingHandler(LogLevel.INFO))
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new DiameterServerHandler());
                }
            });

        ChannelFuture future = b.bind(port).sync();
        serverChannel = future.channel();
        log.info("Diameter Simulator listening on port {}", port);
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        log.info("Stopping Diameter Simulator...");
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        workerGroup.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS).sync();
        bossGroup.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS).sync();
        log.info("Diameter Simulator stopped.");
    }
}
