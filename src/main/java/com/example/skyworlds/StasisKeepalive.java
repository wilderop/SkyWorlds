package com.example.skyworlds;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1.21.2+ packs thrown pearls into player NBT on disconnect. Crossing Paper
 * ↔ Fabric looks like a logout, so stasis chambers on the other side appear
 * empty. Keep a world copy of parked pearls while the owner is away, and
 * Velocity-switch them back when one lands.
 */
final class StasisKeepalive {
    private final SkyWorlds plugin;
    private final HandoffStore store;
    private final RedisBus redis;
    private final NamespacedKey keepKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey transferredKey;
    private final Map<String, Integer> ticketRefs = new HashMap<>();

    StasisKeepalive(SkyWorlds plugin, HandoffStore store, RedisBus redis,
                    NamespacedKey transferredKey) {
        this.plugin = plugin;
        this.store = store;
        this.redis = redis;
        this.transferredKey = transferredKey;
        this.keepKey = new NamespacedKey(plugin, "stasis_keep");
        this.ownerKey = new NamespacedKey(plugin, "owner");
    }

    void start() {
        plugin.getServer().getScheduler().runTaskLater(plugin, this::adoptExisting, 40L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweepTickets, 20L, 20L);
    }

    void onQuit(Player player) {
        List<Snap> snaps = snapshot(player);
        if (snaps.isEmpty()) {
            return;
        }
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (plugin.getServer().getPlayer(id) != null) {
                return;
            }
            int spawned = 0;
            for (Snap snap : snaps) {
                if (spawnKeep(snap)) {
                    spawned++;
                }
            }
            if (spawned > 0) {
                plugin.getLogger().info("Stasis keepalive " + spawned + " pearl(s) for " + id);
            }
        }, 5L);
    }

    void onJoin(Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> reconcile(player), 2L);
    }

    void consumeNear(Player player, Location at) {
        if (player == null || at == null || at.getWorld() == null) {
            return;
        }
        UUID id = player.getUniqueId();
        for (var entity : at.getWorld().getNearbyEntities(at, 3.0, 3.0, 3.0)) {
            if (!(entity instanceof EnderPearl pearl)) {
                continue;
            }
            UUID owner = ownerOf(pearl);
            if (player.equals(pearl.getShooter()) || id.equals(owner)) {
                releaseTicket(pearl);
                pearl.remove();
            }
        }
    }

    void onHit(EnderPearl pearl) {
        if (isTransferred(pearl)) {
            return;
        }
        UUID owner = ownerOf(pearl);
        if (owner == null) {
            return;
        }
        Player online = plugin.getServer().getPlayer(owner);
        if (online != null && online.isOnline()) {
            return;
        }
        Location hit = pearl.getLocation().clone();
        pullToSurvival(owner, hit);
    }

    private void reconcile(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        List<EnderPearl> kept = new ArrayList<>();
        List<EnderPearl> vanilla = new ArrayList<>();
        for (EnderPearl pearl : allPearls()) {
            UUID owner = ownerOf(pearl);
            if (!id.equals(owner) && !player.equals(pearl.getShooter())) {
                continue;
            }
            if (isTransferred(pearl)) {
                continue;
            }
            if (isKeep(pearl)) {
                kept.add(pearl);
            } else {
                vanilla.add(pearl);
            }
        }
        if (!vanilla.isEmpty()) {
            for (EnderPearl pearl : kept) {
                releaseTicket(pearl);
                pearl.remove();
            }
            return;
        }
        for (EnderPearl pearl : kept) {
            pearl.setShooter(player);
            pearl.getPersistentDataContainer().remove(keepKey);
            releaseTicket(pearl);
        }
        if (!kept.isEmpty()) {
            plugin.getLogger().info("Stasis keepalive rebound " + kept.size() + " pearl(s) for " + player.getName());
        }
    }

    private void adoptExisting() {
        int n = 0;
        for (EnderPearl pearl : allPearls()) {
            if (!isKeep(pearl)) {
                continue;
            }
            holdTicket(pearl);
            n++;
        }
        if (n > 0) {
            plugin.getLogger().info("Stasis keepalive adopted " + n + " existing pearl(s)");
        }
    }

    private void sweepTickets() {
        ticketRefs.keySet().removeIf(key -> {
            String[] parts = key.split("\\|", 3);
            if (parts.length != 3) {
                return true;
            }
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                return true;
            }
            int cx;
            int cz;
            try {
                cx = Integer.parseInt(parts[1]);
                cz = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return true;
            }
            boolean still = false;
            if (world.isChunkLoaded(cx, cz)) {
                for (var entity : world.getChunkAt(cx, cz).getEntities()) {
                    if (entity instanceof EnderPearl pearl && isKeep(pearl)) {
                        still = true;
                        break;
                    }
                }
            }
            if (still) {
                return false;
            }
            world.removePluginChunkTicket(cx, cz, plugin);
            return true;
        });
    }

    private List<Snap> snapshot(Player player) {
        List<Snap> out = new ArrayList<>();
        UUID id = player.getUniqueId();
        for (EnderPearl pearl : allPearls()) {
            if (isTransferred(pearl) || isKeep(pearl)) {
                continue;
            }
            UUID owner = ownerOf(pearl);
            if (!player.equals(pearl.getShooter()) && !id.equals(owner)) {
                continue;
            }
            Location loc = pearl.getLocation();
            World world = loc.getWorld();
            if (world == null) {
                continue;
            }
            out.add(new Snap(world.getName(), loc.getX(), loc.getY(), loc.getZ(),
                    pearl.getVelocity().clone(), id));
        }
        return out;
    }

    private boolean spawnKeep(Snap snap) {
        World world = Bukkit.getWorld(snap.world);
        if (world == null) {
            return false;
        }
        Location loc = new Location(world, snap.x, snap.y, snap.z);
        world.getChunkAt(loc);
        EnderPearl pearl = world.spawn(loc, EnderPearl.class, spawned -> {
            spawned.setVelocity(snap.vel);
            spawned.setGravity(true);
            spawned.getPersistentDataContainer().set(keepKey, PersistentDataType.BYTE, (byte) 1);
            spawned.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, snap.owner.toString());
        });
        if (pearl == null) {
            return false;
        }
        holdTicket(pearl);
        return true;
    }

    private void pullToSurvival(UUID owner, Location hit) {
        World world = hit.getWorld();
        String dim = dimOf(world);
        String name = Bukkit.getOfflinePlayer(owner).getName();
        store.writePlayerCoords("in-survival", owner, name == null ? "" : name, dim,
                hit.getX(), hit.getY(), hit.getZ(), hit.getYaw(), hit.getPitch(), true);
        redis.clearMigrate(owner);
        redis.allow(owner, "survival", 120);
        redis.publishConnect(owner, "survival");
        plugin.getLogger().info("Stasis pull " + owner + " -> survival");
    }

    private void holdTicket(EnderPearl pearl) {
        Location loc = pearl.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        String key = world.getName() + "|" + cx + "|" + cz;
        int refs = ticketRefs.getOrDefault(key, 0);
        if (refs == 0) {
            world.addPluginChunkTicket(cx, cz, plugin);
        }
        ticketRefs.put(key, refs + 1);
    }

    private void releaseTicket(EnderPearl pearl) {
        Location loc = pearl.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        String key = world.getName() + "|" + cx + "|" + cz;
        int refs = ticketRefs.getOrDefault(key, 0) - 1;
        if (refs <= 0) {
            ticketRefs.remove(key);
            world.removePluginChunkTicket(cx, cz, plugin);
        } else {
            ticketRefs.put(key, refs);
        }
    }

    private List<EnderPearl> allPearls() {
        List<EnderPearl> out = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            out.addAll(world.getEntitiesByClass(EnderPearl.class));
        }
        return out;
    }

    private boolean isKeep(EnderPearl pearl) {
        Byte v = pearl.getPersistentDataContainer().get(keepKey, PersistentDataType.BYTE);
        return v != null && v == 1;
    }

    private boolean isTransferred(EnderPearl pearl) {
        Byte v = pearl.getPersistentDataContainer().get(transferredKey, PersistentDataType.BYTE);
        return v != null && v == 1;
    }

    private UUID ownerOf(EnderPearl pearl) {
        String raw = pearl.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw != null) {
            try {
                return UUID.fromString(raw);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (pearl.getShooter() instanceof Player player) {
            return player.getUniqueId();
        }
        return null;
    }

    private static String dimOf(World world) {
        if (world == null) {
            return "overworld";
        }
        return switch (world.getEnvironment()) {
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "overworld";
        };
    }

    private record Snap(String world, double x, double y, double z, Vector vel, UUID owner) {}
}
