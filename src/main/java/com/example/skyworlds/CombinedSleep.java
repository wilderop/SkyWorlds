package com.example.skyworlds;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.TimeSkipEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Night skip at 10% of (Paper survival + Fabric) online, not 10% of one backend.
 * Vanilla gamerule stays at 100 so beds do not show "no amount of rest".
 */
final class CombinedSleep implements Listener {
    static final int PERCENT = 10;
    /** Vanilla is 100 ticks (~5s). Must tick once per server tick, not once per second. */
    private static final int DEEP_SLEEP_TICKS = 100;
    private static final long OTHER_MAX_AGE_MS = 3_000L;

    private final SkyWorlds plugin;
    private final HandoffStore store;
    private final Map<UUID, Integer> ticksInBed = new ConcurrentHashMap<>();
    private long lastSkipMs;
    private boolean ourSkip;

    CombinedSleep(SkyWorlds plugin, HandoffStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            ticksInBed.put(event.getPlayer().getUniqueId(), 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        ticksInBed.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ticksInBed.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTimeSkip(TimeSkipEvent event) {
        if (!ourSkip) {
            event.setCancelled(true);
        }
    }

    void tick(World overworld) {
        int online = 0;
        int sleeping = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            online++;
            if (inBed(p)) {
                int ticks = ticksInBed.merge(p.getUniqueId(), 1, Integer::sum);
                if (ticks >= DEEP_SLEEP_TICKS) {
                    sleeping++;
                }
            }
        }
        store.writeSleepCensus("survival", online, sleeping);
        if (overworld == null || sleeping == 0) {
            return;
        }
        if (!canSkipTime(overworld)) {
            return;
        }
        int[] fabric = store.readSleepCensus("fabric", OTHER_MAX_AGE_MS);
        if (!enough(online, sleeping, fabric[0], fabric[1])) {
            return;
        }
        skipNight(overworld, online + fabric[0], sleeping + fabric[1]);
    }

    private static boolean inBed(Player p) {
        try {
            if (p.isSleeping()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return p.isSleeping();
    }

    private void skipNight(World overworld, int combinedOnline, int combinedSleeping) {
        long now = System.currentTimeMillis();
        if (now - lastSkipMs < 8_000L) {
            return;
        }
        lastSkipMs = now;
        long t = overworld.getFullTime();
        long next = t - Math.floorMod(t, 24000L) + 24000L;
        ourSkip = true;
        try {
            overworld.setFullTime(next);
        } finally {
            ourSkip = false;
        }
        overworld.setStorm(false);
        overworld.setThundering(false);
        wakeSleepers();
        plugin.getLogger().info("Combined sleep skip " + combinedSleeping + "/" + combinedOnline
                + " (10% of paper+fabric)");
    }

    void wakeSleepers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (inBed(p)) {
                try {
                    p.wakeup(true);
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

    void runAsOurSkip(Runnable action) {
        ourSkip = true;
        try {
            action.run();
        } finally {
            ourSkip = false;
        }
    }

    static boolean canSkipTime(World world) {
        long tod = Math.floorMod(world.getFullTime(), 24000L);
        return (tod >= 12542L && tod < 23460L) || world.hasStorm() || world.isThundering();
    }
}
