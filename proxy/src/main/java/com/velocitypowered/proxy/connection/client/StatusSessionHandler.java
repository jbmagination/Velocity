package com.velocitypowered.proxy.connection.client;

import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;
import com.velocitypowered.api.event.connection.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.connection.InboundConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.ModInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.PingPassthroughMode;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.packet.StatusPingPacket;
import com.velocitypowered.proxy.protocol.packet.StatusRequestPacket;
import com.velocitypowered.proxy.protocol.packet.StatusResponsePacket;
import com.velocitypowered.proxy.protocol.packet.legacy.LegacyDisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.legacy.LegacyPingPacket;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import com.velocitypowered.proxy.util.except.QuietRuntimeException;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class StatusSessionHandler implements MinecraftSessionHandler {

  private static final Logger logger = LogManager.getLogger(StatusSessionHandler.class);
  private static final QuietRuntimeException EXPECTED_AWAITING_REQUEST = new QuietRuntimeException(
      "Expected connection to be awaiting status request");

  private final VelocityServer server;
  private final MinecraftConnection connection;
  private final InboundConnection inbound;
  private boolean pingReceived = false;

  StatusSessionHandler(VelocityServer server, MinecraftConnection connection,
      InboundConnection inbound) {
    this.server = server;
    this.connection = connection;
    this.inbound = inbound;
  }

  @Override
  public void activated() {
    if (server.getConfiguration().isShowPingRequests()) {
      logger.info("{} is pinging the server with version {}", this.inbound,
          this.connection.getProtocolVersion());
    }
  }

  private ServerPing constructLocalPing(ProtocolVersion version) {
    VelocityConfiguration configuration = server.getConfiguration();
    return new ServerPing(
        new ServerPing.Version(version.getProtocol(),
            "Velocity " + ProtocolVersion.SUPPORTED_VERSION_STRING),
        new ServerPing.Players(server.getPlayerCount(), configuration.getShowMaxPlayers(),
            ImmutableList.of()),
        configuration.getMotd(),
        configuration.getFavicon().orElse(null),
        configuration.isAnnounceForge() ? ModInfo.DEFAULT : null
    );
  }

  private CompletableFuture<ServerPing> attemptPingPassthrough(PingPassthroughMode mode,
      List<String> servers, ProtocolVersion pingingVersion) {
    ServerPing fallback = constructLocalPing(pingingVersion);
    List<CompletableFuture<ServerPing>> pings = new ArrayList<>();
    for (String s : servers) {
      Optional<RegisteredServer> rs = server.getServer(s);
      if (!rs.isPresent()) {
        continue;
      }
      VelocityRegisteredServer vrs = (VelocityRegisteredServer) rs.get();
      pings.add(vrs.ping(connection.eventLoop(), pingingVersion));
    }
    if (pings.isEmpty()) {
      return CompletableFuture.completedFuture(fallback);
    }

    CompletableFuture<List<ServerPing>> pingResponses = CompletableFutures.successfulAsList(pings,
        (ex) -> fallback);
    switch (mode) {
      case ALL:
        return pingResponses.thenApply(responses -> {
          // Find the first non-fallback
          for (ServerPing response : responses) {
            if (response == fallback) {
              continue;
            }
            return response;
          }
          return fallback;
        });
      case MODS:
        return pingResponses.thenApply(responses -> {
          // Find the first non-fallback that contains a mod list
          for (ServerPing response : responses) {
            if (response == fallback) {
              continue;
            }
            Optional<ModInfo> modInfo = response.getModinfo();
            if (modInfo.isPresent()) {
              return fallback.asBuilder().mods(modInfo.get()).build();
            }
          }
          return fallback;
        });
      case DESCRIPTION:
        return pingResponses.thenApply(responses -> {
          // Find the first non-fallback. If it includes a modlist, add it too.
          for (ServerPing response : responses) {
            if (response == fallback) {
              continue;
            }

            if (response.getDescription() == null) {
              continue;
            }

            return new ServerPing(
                fallback.getVersion(),
                fallback.getPlayers().orElse(null),
                response.getDescription(),
                fallback.getFavicon().orElse(null),
                response.getModinfo().orElse(null)
            );
          }
          return fallback;
        });
      default:
        // Not possible, but covered for completeness.
        return CompletableFuture.completedFuture(fallback);
    }
  }

  private CompletableFuture<ServerPing> getInitialPing() {
    VelocityConfiguration configuration = server.getConfiguration();
    ProtocolVersion shownVersion = ProtocolVersion.isSupported(connection.getProtocolVersion())
        ? connection.getProtocolVersion() : ProtocolVersion.MAXIMUM_VERSION;
    PingPassthroughMode passthrough = configuration.getPingPassthrough();

    if (passthrough == PingPassthroughMode.DISABLED) {
      return CompletableFuture.completedFuture(constructLocalPing(shownVersion));
    } else {
      String virtualHostStr = inbound.getVirtualHost().map(InetSocketAddress::getHostString)
          .orElse("");
      List<String> serversToTry = server.getConfiguration().getForcedHosts().getOrDefault(
          virtualHostStr, server.getConfiguration().getAttemptConnectionOrder());
      return attemptPingPassthrough(configuration.getPingPassthrough(), serversToTry, shownVersion);
    }
  }

  @Override
  public boolean handle(LegacyPingPacket packet) {
    if (this.pingReceived) {
      throw EXPECTED_AWAITING_REQUEST;
    }
    this.pingReceived = true;
    getInitialPing()
        .thenCompose(ping -> server.getEventManager().fire(new ProxyPingEvent(inbound, ping)))
        .thenAcceptAsync(event -> connection.closeWith(
            LegacyDisconnectPacket.fromServerPing(event.getPing(), packet.getVersion())),
            connection.eventLoop())
        .exceptionally((ex) -> {
          logger.error("Exception while handling legacy ping {}", packet, ex);
          return null;
        });
    return true;
  }

  @Override
  public boolean handle(StatusPingPacket packet) {
    connection.closeWith(packet);
    return true;
  }

  @Override
  public boolean handle(StatusRequestPacket packet) {
    if (this.pingReceived) {
      throw EXPECTED_AWAITING_REQUEST;
    }
    this.pingReceived = true;

    getInitialPing()
        .thenCompose(ping -> server.getEventManager().fire(new ProxyPingEvent(inbound, ping)))
        .thenAcceptAsync(
            (event) -> {
              StringBuilder json = new StringBuilder();
              VelocityServer.getPingGsonInstance(connection.getProtocolVersion())
                  .toJson(event.getPing(), json);
              connection.write(new StatusResponsePacket(json));
            },
            connection.eventLoop())
        .exceptionally((ex) -> {
          logger.error("Exception while handling status request {}", packet, ex);
          return null;
        });
    return true;
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    // what even is going on?
    connection.close(true);
  }

  private enum State {
    AWAITING_REQUEST,
    RECEIVED_REQUEST
  }
}
