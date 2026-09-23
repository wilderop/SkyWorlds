package com.example.skyworlds.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Night skip at 10% of (Paper survival + Fabric) online, not 10% of Fabric alone.
 */
final class CombinedSleep {
    private static final Logger LOG = LoggerFactory.getLogger("SkyWorlds");
    private static final Path DIR = Path.of("/mnt/pool/skygate/sleep");
    static final int PERCENT = 10;
    private static final long OTHER_MAX_AGE_MS = 3_000L;

    private long lastSkipMs;
    private final Map<UUID, Integer> ticksInBed = new HashMap<>();

    void noteSleep(UUID uuid) {
        ticksInBed.putIfAbsent(uuid, 0);
    }

    void noteWake(UUID uuid) {
        ticksInBed.remove(uuid);
    }

    void tick(MinecraftServer srv, SkyWorldsFabric host) {
        int online = 0;
        int sleeping = 0;
        ticksInBed.entrySet().removeIf(e -> srv.getPlayerList().getPlayer(e.getKey()) == null);
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                continue;
            }
            online++;
            boolean bed = p.isSleeping() || ticksInBed.containsKey(p.getUUID());
            if (bed) {
                int ticks = ticksInBed.merge(p.getUUID(), 1, Integer::sum);
                if (ticks >= 100) {
                    sleeping++;
                }
            } else {
                ticksInBed.remove(p.getUUID());
            }
        }
        writeCensus("fabric", online, sleeping);
        if (sleeping == 0) {
            return;
        }
        ServerLevel overworld = srv.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return;
        }
        if (!canSkipTime(srv, overworld)) {
            return;
        }
        int[] paper = readCensus("survival");
        if (!enough(online, sleeping, paper[0], paper[1])) {
            return;
        }
        skipNight(srv, overworld, host, online + paper[0], sleeping + paper[1]);
    }

    private void skipNight(MinecraftServer srv, ServerLevel overworld, SkyWorldsFabric host,
                           int combinedOnline, int combinedSleeping) {
        long now = System.currentTimeMillis();
        if (now - lastSkipMs < 8_000L) {
            return;
        }
        lastSkipMs = now;
        try {
            ServerClockManager clocks = srv.clockManager();
            var holder = srv.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).get(WorldClocks.OVERWORLD);
            if (holder.isPresent()) {
                long t = clocks.getTotalTicks(holder.get());
                long next = t - Math.floorMod(t, 24000L) + 24000L;
                clocks.setTotalTicks(holder.get(), next);
                clocks.setPaused(holder.get(), false);
                host.requestPaperClockCatchUp(next);
            }
        } catch (Exception e) {
            LOG.warn("combined sleep skip failed: {}", e.toString());
            return;
        }
        overworld.resetWeatherCycle();
        wakeLocalSleepers(srv);
        LOG.info("Combined sleep skip {}/{} (10% of paper+fabric)", combinedSleeping, combinedOnline);
    }

    void wakeLocalSleepers(MinecraftServer srv) {
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (p.isSleeping() || ticksInBed.containsKey(p.getUUID())) {
                try {
                    p.stopSleeping();
                } catch (Throwable ignored) {
                }
            }
        }
        ticksInBed.clear();
    }

    static boolean enough(int localOnline, int localSleeping, int otherOnline, int otherSleeping) {
        int online = localOnline + otherOnline;
        int sleeping = localSleeping + otherSleeping;
        if (online <= 0 || sleeping <= 0) {
            return false;
        }
        int need = Math.max(1, (int) Math.ceil(online * (PERCENT / 100.0)));
        return sleeping >= need;
    }

    private boolean canSkipTime(MinecraftServer srv, ServerLevel overworld) {
        try {
            ServerClockManager clocks = srv.clockManager();
            var holder = srv.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).get(WorldClocks.OVERWORLD);
            if (holder.isPresent()) {
                long tod = Math.floorMod(clocks.getTotalTicks(holder.get()), 24000L);
                if (tod >= 12542L && tod < 23460L) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return overworld.isThundering() || overworld.isRaining();
    }

    private static boolean sleepingLongEnough(ServerPlayer player) {
        if (!player.isSleeping()) {
            return false;
        }
        try {
            return (boolean) Player.class.getMethod("isSleepingLongEnough").invoke(player);
        } catch (Throwable ignored) {
        }
        try {
            var m = player.getClass().getMethod("getSleepTimer");
            return ((Number) m.invoke(player)).intValue() >= 100;
        } catch (Throwable ignored) {
        }
        try {
            var f = player.getClass().getDeclaredField("sleepCounter");
            f.setAccessible(true);
            return ((Number) f.get(player)).intValue() >= 100;
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static void writeCensus(String side, int online, int sleeping) {
        try {
            Files.createDirectories(DIR);
            JsonObject o = new JsonObject();
            o.addProperty("online", online);
            o.addProperty("sleeping", sleeping);
            o.addProperty("t", System.currentTimeMillis());
            Path dest = DIR.resolve(side + ".json");
            Path tmp = dest.resolveSibling("." + dest.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
        }
    }

    private static int[] readCensus(String side) {
        try {
            Path file = DIR.resolve(side + ".json");
            if (!Files.isRegularFile(file)) {
                return new int[]{0, 0};
            }
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (System.currentTimeMillis() - o.get("t").getAsLong() > OTHER_MAX_AGE_MS) {
                return new int[]{0, 0};
            }
            return new int[]{
                    Math.max(0, o.get("online").getAsInt()),
                    Math.max(0, o.get("sleeping").getAsInt())
            };
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }
}
