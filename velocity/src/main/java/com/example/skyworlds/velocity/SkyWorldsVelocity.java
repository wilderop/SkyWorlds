package com.example.skyworlds.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.JedisSentinelPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Plugin(id = "skyworlds", name = "SkyWorlds Velocity", version = "3.0.6",
        authors = {"SkyWorlds"})
public class SkyWorldsVelocity {
    private static final String CHANNEL = "skygate:connect";
    private static final String ALLOW = "skygate:allow:";
    private static final String MIGRATE = "skygate:migrate:";
    private static final String REDIS_HOST = "127.0.0.1";
    private static final int REDIS_PORT = 6379;
    private static final String SENTINEL_MASTER = "azpbmd";
    private static final Set<String> SENTINELS = Set.of(
            "127.0.0.1:26379", "127.0.0.1:26379", "127.0.0.1:26379");
    private static final Path[] PASS_FILES = {
            Path.of("redis.pass"),
            Path.of("redis.pass"),
    };

    private final ProxyServer proxy;
    private final Logger log;
    private Pool<Jedis> pool;
    private Thread sub;
    private volatile boolean running;

    @Inject
    public SkyWorldsVelocity(ProxyServer proxy, Logger log) {
        this.proxy = proxy;
        this.log = log;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        String password = readRedisPassword();
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(8);
        Set<String> sentinels = new LinkedHashSet<>(SENTINELS);
        try {
            if (password.isBlank()) {
                pool = new JedisSentinelPool(SENTINEL_MASTER, sentinels, cfg, 2000);
            } else {
                pool = new JedisSentinelPool(SENTINEL_MASTER, sentinels, cfg, 2000, password);
            }
            try (Jedis j = pool.getResource()) {
                j.ping();
            }
            log.info("SkyGate Redis via Sentinel master={}", SENTINEL_MASTER);
        } catch (Exception e) {
            log.warn("SkyGate Sentinel failed ({}), falling back to {}:{}", e.getMessage(), REDIS_HOST, REDIS_PORT);
            pool = password.isBlank()
                    ? new JedisPool(cfg, REDIS_HOST, REDIS_PORT, 2000)
                    : new JedisPool(cfg, REDIS_HOST, REDIS_PORT, 2000, password);
            try (Jedis j = pool.getResource()) {
                j.ping();
            } catch (Exception e2) {
                log.error("SkyGate Redis failed: {}", e2.getMessage());
            }
        }
        running = true;
        sub = new Thread(this::listen, "skygate-vel-sub");
        sub.setDaemon(true);
        sub.start();
        log.info("SkyWorlds Velocity 3.0.6: fabric sky is fly-up with Dragon's Breath.");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        running = false;
        if (sub != null) sub.interrupt();
        if (pool != null) pool.close();
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String dest = event.getOriginalServer().getServerInfo().getName();
        if ("survival".equalsIgnoreCase(dest) && hasMigrate(uuid)) {
            String current = event.getPlayer().getCurrentServer()
                    .map(s -> s.getServerInfo().getName())
                    .orElse("");
            if ("fabric".equalsIgnoreCase(current)) {
                // Already in the sky and gating back. Do not bounce to fabric.
                return;
            }
            Optional<RegisteredServer> fabric = proxy.getServer("fabric");
            if (fabric.isPresent()) {
                log.info("SkyGate resume {} -> fabric (logged out in sky)", event.getPlayer().getUsername());
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(fabric.get()));
            }
            return;
        }
        if (!"fabric".equalsIgnoreCase(dest)) {
            return;
        }
        if (hasAllow(uuid, "fabric") || hasMigrate(uuid)) {
            return;
        }
        log.info("Blocked {} /server fabric (fly-up only)", event.getPlayer().getUsername());
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        event.getPlayer().sendMessage(Component.text(
                "The sky is entered by flying up holding Dragon's Breath."));
    }

    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
        String name = event.getServer().getServerInfo().getName();
        UUID uuid = event.getPlayer().getUniqueId();
        if ("fabric".equalsIgnoreCase(name)) {
            persistMigrate(uuid);
            clearKey(ALLOW + uuid);
            return;
        }
        if ("survival".equalsIgnoreCase(name)) {
            clearKey(MIGRATE + uuid);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // tokens are TTL'd
    }

    private boolean hasAllow(UUID uuid, String dest) {
        if (pool == null) return false;
        try (Jedis j = pool.getResource()) {
            String got = j.get(ALLOW + uuid);
            return dest.equalsIgnoreCase(got);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasMigrate(UUID uuid) {
        if (pool == null) return false;
        try (Jedis j = pool.getResource()) {
            return Boolean.TRUE.equals(j.exists(MIGRATE + uuid));
        } catch (Exception e) {
            return false;
        }
    }

    private void persistMigrate(UUID uuid) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            j.set(MIGRATE + uuid, "fabric");
        } catch (Exception ignored) {
        }
    }

    private void clearKey(String key) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            j.del(key);
        } catch (Exception ignored) {
        }
    }

    private String readRedisPassword() {
        String envFile = System.getenv("SKYGATE_REDIS_PASS_FILE");
        if (envFile != null && !envFile.isBlank()) {
            String got = readPassFile(Path.of(envFile));
            if (got != null) {
                return got;
            }
        }
        for (Path p : PASS_FILES) {
            String got = readPassFile(p);
            if (got != null) {
                return got;
            }
        }
        return "";
    }

    private String readPassFile(Path passFile) {
        try {
            if (Files.isRegularFile(passFile)) {
                return Files.readString(passFile).trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void listen() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try (Jedis j = pool.getResource()) {
                log.info("SkyGate subscribe listening on {}", CHANNEL);
                j.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        String[] parts = message.trim().split("\\s+", 2);
                        if (parts.length != 2) return;
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(parts[0]);
                        } catch (Exception e) {
                            return;
                        }
                        String destName = parts[1];
                        proxy.getScheduler().buildTask(SkyWorldsVelocity.this, () -> {
                            Optional<Player> player = proxy.getPlayer(uuid);
                            Optional<RegisteredServer> dest = proxy.getServer(destName);
                            if (player.isEmpty()) {
                                log.debug("SkyGate connect: player {} not on this proxy", uuid);
                                return;
                            }
                            if (dest.isEmpty()) {
                                log.warn("SkyGate connect: server {} not registered", destName);
                                return;
                            }
                            log.info("SkyGate connecting {} -> {}", player.get().getUsername(), destName);
                            player.get().createConnectionRequest(dest.get()).fireAndForget();
                        }).schedule();
                    }
                }, CHANNEL);
                if (running) {
                    log.warn("SkyGate subscribe returned, reconnecting");
                }
            } catch (Exception e) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    return;
                }
                log.warn("SkyGate subscribe ended ({}), retry in 3s", e.getMessage());
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
