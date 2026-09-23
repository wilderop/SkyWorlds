package com.example.skyworlds;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.JedisSentinelPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RedisBus {
    public static final String CHANNEL = "skygate:connect";
    public static final String ALLOW_PREFIX = "skygate:allow:";
    public static final String BED_PREFIX = "skygate:bed:";
    public static final String MIGRATE_PREFIX = "skygate:migrate:";

    private final Logger log;
    private final String host;
    private final int port;
    private final String password;
    private final String sentinelMaster;
    private final Set<String> sentinels;
    private Pool<Jedis> pool;
    private Thread subThread;

    public RedisBus(Logger log, String host, int port, Path passwordFile) {
        this(log, host, port, passwordFile, "", List.of());
    }

    public RedisBus(Logger log, String host, int port, Path passwordFile,
                    String sentinelMaster, List<String> sentinelAddrs) {
        this.log = log;
        this.host = host;
        this.port = port;
        this.password = readPassword(passwordFile);
        this.sentinelMaster = sentinelMaster == null ? "" : sentinelMaster.trim();
        this.sentinels = new LinkedHashSet<>();
        if (sentinelAddrs != null) {
            for (String s : sentinelAddrs) {
                if (s != null && !s.isBlank()) {
                    this.sentinels.add(s.trim());
                }
            }
        }
    }

    public boolean connect() {
        try {
            JedisPoolConfig cfg = new JedisPoolConfig();
            cfg.setMaxTotal(8);
            cfg.setMaxIdle(2);
            boolean auth = password != null && !password.isBlank();
            if (!sentinelMaster.isEmpty() && !sentinels.isEmpty()) {
                try {
                    pool = auth
                            ? new JedisSentinelPool(sentinelMaster, sentinels, cfg, 2000, password)
                            : new JedisSentinelPool(sentinelMaster, sentinels, cfg, 2000);
                    try (Jedis j = pool.getResource()) {
                        j.ping();
                    }
                    log.info("SkyGate Redis via Sentinel master=" + sentinelMaster + " nodes=" + sentinels);
                    return true;
                } catch (Exception e) {
                    log.warning("SkyGate Sentinel failed (" + e.getMessage() + "), falling back to " + host + ":" + port);
                    if (pool != null) {
                        try { pool.close(); } catch (Exception ignored) {}
                        pool = null;
                    }
                }
            }
            pool = auth
                    ? new JedisPool(cfg, host, port, 2000, password)
                    : new JedisPool(cfg, host, port, 2000);
            try (Jedis j = pool.getResource()) {
                j.ping();
            }
            log.info("SkyGate Redis connected at " + host + ":" + port);
            return true;
        } catch (Exception e) {
            log.log(Level.SEVERE, "SkyGate Redis failed: " + e.getMessage(), e);
            return false;
        }
    }

    public void close() {
        if (subThread != null) {
            subThread.interrupt();
        }
        if (pool != null) {
            pool.close();
        }
    }

    public void allow(UUID uuid, String dest, int ttlSeconds) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            j.setex(ALLOW_PREFIX + uuid, ttlSeconds, dest);
        } catch (Exception e) {
            log.warning("redis allow: " + e.getMessage());
        }
    }

    public String takeAllow(UUID uuid) {
        if (pool == null) return null;
        try (Jedis j = pool.getResource()) {
            String key = ALLOW_PREFIX + uuid;
            String dest = j.get(key);
            if (dest != null) {
                j.del(key);
            }
            return dest;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasAllow(UUID uuid, String dest) {
        if (pool == null) return false;
        try (Jedis j = pool.getResource()) {
            String got = j.get(ALLOW_PREFIX + uuid);
            return dest.equalsIgnoreCase(got);
        } catch (Exception e) {
            return false;
        }
    }

    public void publishConnect(UUID uuid, String dest) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            j.publish(CHANNEL, uuid + " " + dest);
        } catch (Exception e) {
            log.warning("redis publish: " + e.getMessage());
        }
    }

    public void setBed(UUID uuid, String json, int ttlSeconds) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            j.setex(BED_PREFIX + uuid, ttlSeconds, json);
        } catch (Exception e) {
            log.warning("redis bed: " + e.getMessage());
        }
    }

    public String getBed(UUID uuid) {
        if (pool == null) return null;
        try (Jedis j = pool.getResource()) {
            return j.get(BED_PREFIX + uuid);
        } catch (Exception e) {
            return null;
        }
    }

    /** Remember this player logged out in Fabric sky. No TTL — resume forever until they connect to survival. */
    public void markMigrate(UUID uuid) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            j.set(MIGRATE_PREFIX + uuid, "fabric");
        } catch (Exception e) {
            log.warning("redis migrate: " + e.getMessage());
        }
    }

    /** @deprecated TTL is ignored; migrate keys persist until survival connect. */
    public void markMigrate(UUID uuid, int ttlSeconds) {
        markMigrate(uuid);
    }

    public boolean hasMigrate(UUID uuid) {
        if (pool == null) return false;
        try (Jedis j = pool.getResource()) {
            return Boolean.TRUE.equals(j.exists(MIGRATE_PREFIX + uuid));
        } catch (Exception e) {
            return false;
        }
    }

    public void clearMigrate(UUID uuid) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            j.del(MIGRATE_PREFIX + uuid);
        } catch (Exception ignored) {
        }
    }

    public void subscribeConnect(BiConsumer<UUID, String> onConnect) {
        if (pool == null) return;
        subThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis j = pool.getResource()) {
                    j.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            String[] parts = message.trim().split("\\s+", 2);
                            if (parts.length != 2) return;
                            try {
                                onConnect.accept(UUID.fromString(parts[0]), parts[1]);
                            } catch (Exception ignored) {
                            }
                        }
                    }, CHANNEL);
                    if (!Thread.currentThread().isInterrupted()) {
                        log.warning("redis subscribe returned, reconnecting");
                    }
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    log.warning("redis subscribe ended: " + e.getMessage());
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "skygate-redis-sub");
        subThread.setDaemon(true);
        subThread.start();
    }

    private static String readPassword(Path file) {
        if (file == null) return "";
        try {
            if (Files.isRegularFile(file)) {
                return Files.readString(file).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
