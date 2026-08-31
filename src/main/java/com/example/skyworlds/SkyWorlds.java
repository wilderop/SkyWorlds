package com.example.skyworlds;

import org.bukkit.Axis;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.WorldBorder;

import java.util.*;
import java.util.logging.Level;

/**
 * SkyWorlds Plugin - Mirror Dimension System
 * 
 * Creates mirror dimensions that players can access by flying above a threshold
 * with Dragon's Breath. Includes optimized portal linking between dimensions.
 * 
 * v2.1.1: CRITICAL FIX - Plugin now only handles mirror world portals
 *         Main world uses vanilla Minecraft portal behavior
 * 
 * @author SkyWorlds Team
 * @version 2.1.1 (Main World Isolation Fix)
 */
public class SkyWorlds extends JavaPlugin implements Listener {

    // World references
    private World mainOverworld;
    private World mirrorOverworld;
    private World mainNether;
    private World mirrorNether;
    private World theEnd;

    // Configuration values
    private int thresholdY;
    private int pivotY;
    private int transitionTicks;
    private int cooldownTicks;
    private int fadeTicks;
    private int portalSearchRadius;
    private double netherScale;

    // Player tracking for dimension transitions
    private final Map<UUID, Integer> ticksAboveThreshold = new HashMap<>();
    private final Map<UUID, Integer> switchCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            loadConfigurationValues();
            
            if (!initializeWorlds()) {
                getLogger().severe("Failed to initialize worlds. Disabling plugin.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            getServer().getPluginManager().registerEvents(this, this);

            startWorldSyncTask();
            startDimensionTransitionTask();

            getLogger().info("========================================");
            getLogger().info("SkyWorlds v2.1.1 enabled successfully!");
            getLogger().info("Main world: Uses vanilla portal behavior");
            getLogger().info("Mirror world: Uses plugin portal behavior");
            getLogger().info("Threshold Y: " + thresholdY);
            getLogger().info("Portal Search Radius: " + portalSearchRadius);
            getLogger().info("Mirror Overworld: " + (mirrorOverworld != null ? "✓" : "✗"));
            getLogger().info("Mirror Nether: " + (mirrorNether != null ? "✓" : "✗"));
            getLogger().info("========================================");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Critical error during plugin initialization", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void loadConfigurationValues() {
        thresholdY = getConfig().getInt("threshold_y", 1320);
        pivotY = getConfig().getInt("pivot_y", 1321);
        transitionTicks = getConfig().getInt("transition_ticks", 60);
        cooldownTicks = getConfig().getInt("cooldown_ticks", 100);
        fadeTicks = getConfig().getInt("fade_ticks", 50);
        portalSearchRadius = getConfig().getInt("portal_search_radius", 32);
        netherScale = getConfig().getDouble("nether_scale", 8.0);

        if (portalSearchRadius > 64) {
            getLogger().warning("portal_search_radius (" + portalSearchRadius + ") is very high! Recommend 32-48 for best balance.");
        }
        if (portalSearchRadius < 24 && netherScale >= 8.0) {
            getLogger().warning("portal_search_radius (" + portalSearchRadius + ") may be too small for nether_scale " + netherScale + ". Recommend 32+ to prevent duplicate portals.");
        }
        if (thresholdY >= pivotY) {
            getLogger().warning("threshold_y should be less than pivot_y for proper mirror behavior.");
        }
    }

    private boolean initializeWorlds() {
        mainOverworld = getServer().getWorld("world");
        if (mainOverworld == null) {
            getLogger().severe("Main Overworld (world) not found!");
            return false;
        }

        mainNether = getServer().getWorld("world_nether");
        theEnd = getServer().getWorld("world_the_end");

        if (mainNether == null) {
            getLogger().warning("Main Nether not found. Nether features will be disabled.");
        }
        if (theEnd == null) {
            getLogger().warning("The End not found. End will use vanilla behavior.");
        }

        mirrorOverworld = createMirrorWorld(
            "mirror_overworld",
            "mirror_overworld_seed",
            World.Environment.NORMAL
        );
        
        if (mirrorOverworld == null) {
            getLogger().severe("Failed to create/load mirror_overworld!");
            return false;
        }

        if (mainNether != null) {
            mirrorNether = createMirrorWorld(
                "mirror_nether",
                "mirror_nether_seed",
                World.Environment.NETHER
            );
            
            if (mirrorNether == null) {
                getLogger().warning("Failed to create/load mirror_nether. Nether mirroring disabled.");
            }
        }

        syncWorldBorders();

        return true;
    }

    private World createMirrorWorld(String worldName, String seedConfigKey, World.Environment environment) {
        try {
            long seed = getConfig().getLong(seedConfigKey, 0);
            if (seed == 0) {
                seed = new Random().nextLong();
                getConfig().set(seedConfigKey, seed);
                saveConfig();
                getLogger().info("Generated new seed for " + worldName + ": " + seed);
            }

            WorldCreator creator = new WorldCreator(worldName)
                    .environment(environment)
                    .type(WorldType.NORMAL)
                    .seed(seed);
            
            World world = creator.createWorld();
            
            if (world != null) {
                getLogger().info("Successfully loaded " + worldName + " (seed: " + seed + ")");
            }
            
            return world;
            
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error creating mirror world: " + worldName, e);
            return null;
        }
    }

    private void copyWorldBorder(World source, World target) {
        if (source == null || target == null) return;
        
        try {
            WorldBorder sourceBorder = source.getWorldBorder();
            WorldBorder targetBorder = target.getWorldBorder();
            
            targetBorder.setCenter(sourceBorder.getCenter());
            targetBorder.setSize(sourceBorder.getSize());
            targetBorder.setDamageAmount(sourceBorder.getDamageAmount());
            targetBorder.setDamageBuffer(sourceBorder.getDamageBuffer());
            targetBorder.setWarningDistance(sourceBorder.getWarningDistance());
            targetBorder.setWarningTime(sourceBorder.getWarningTime());
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error copying world border", e);
        }
    }

    private void syncWorldBorders() {
        copyWorldBorder(mainOverworld, mirrorOverworld);
        if (mainNether != null && mirrorNether != null) {
            copyWorldBorder(mainNether, mirrorNether);
        }
    }

    private void clampToBorder(Location loc, World world) {
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double size = border.getSize();
        double half = size / 2.0;
        
        double minX = center.getX() - half;
        double maxX = center.getX() + half;
        double minZ = center.getZ() - half;
        double maxZ = center.getZ() + half;

        double x = Math.max(minX, Math.min(loc.getX(), maxX));
        double z = Math.max(minZ, Math.min(loc.getZ(), maxZ));

        loc.setX(x);
        loc.setZ(z);
    }

    private void startWorldSyncTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (mainOverworld != null && mirrorOverworld != null) {
                        long mainFullTime = mainOverworld.getFullTime();
                        long mirrorFullTime = mirrorOverworld.getFullTime();
                        long syncedFullTime = Math.max(mainFullTime, mirrorFullTime);
                        mainOverworld.setFullTime(syncedFullTime);
                        mirrorOverworld.setFullTime(syncedFullTime);
                        
                        boolean syncedStorm = mainOverworld.hasStorm() && mirrorOverworld.hasStorm();
                        boolean syncedThunder = mainOverworld.isThundering() && mirrorOverworld.isThundering();
                        
                        mainOverworld.setStorm(syncedStorm);
                        mirrorOverworld.setStorm(syncedStorm);
                        mainOverworld.setThundering(syncedThunder);
                        mirrorOverworld.setThundering(syncedThunder);
                        
                        int syncedWeatherDur;
                        if (syncedStorm) {
                            syncedWeatherDur = Math.min(mainOverworld.getWeatherDuration(), mirrorOverworld.getWeatherDuration());
                        } else {
                            syncedWeatherDur = Math.max(mainOverworld.getWeatherDuration(), mirrorOverworld.getWeatherDuration());
                        }
                        
                        int syncedThunderDur;
                        if (syncedThunder) {
                            syncedThunderDur = Math.min(mainOverworld.getThunderDuration(), mirrorOverworld.getThunderDuration());
                        } else {
                            syncedThunderDur = Math.max(mainOverworld.getThunderDuration(), mirrorOverworld.getThunderDuration());
                        }
                        
                        mainOverworld.setWeatherDuration(syncedWeatherDur);
                        mirrorOverworld.setWeatherDuration(syncedWeatherDur);
                        mainOverworld.setThunderDuration(syncedThunderDur);
                        mirrorOverworld.setThunderDuration(syncedThunderDur);
                        
                        copyWorldBorder(mainOverworld, mirrorOverworld);
                    }
                    
                    if (mainNether != null && mirrorNether != null) {
                        copyWorldBorder(mainNether, mirrorNether);
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error in world sync task", e);
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void startDimensionTransitionTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    switchCooldowns.replaceAll((id, ticks) -> ticks - 1);
                    switchCooldowns.entrySet().removeIf(entry -> entry.getValue() <= 0);

                    List<World> worlds = new ArrayList<>();
                    if (mainOverworld != null) worlds.add(mainOverworld);
                    if (mirrorOverworld != null) worlds.add(mirrorOverworld);
                    if (mainNether != null) worlds.add(mainNether);
                    if (mirrorNether != null) worlds.add(mirrorNether);

                    for (World world : worlds) {
                        processEntitiesInWorld(world);
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error in dimension transition task", e);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void processEntitiesInWorld(World world) {
        try {
            for (Entity entity : world.getEntities()) {
                UUID id = entity.getUniqueId();
                
                if (switchCooldowns.containsKey(id)) {
                    continue;
                }
                
                Location loc = entity.getLocation();
                if (loc == null) continue;
                
                if (loc.getY() > thresholdY) {
                    handleEntityAboveThreshold(entity, id);
                } else {
                    ticksAboveThreshold.remove(id);
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error processing entities in " + world.getName(), e);
        }
    }

    private void handleEntityAboveThreshold(Entity entity, UUID id) {
        int ticks = ticksAboveThreshold.getOrDefault(id, 0);
        
        if (ticks < 0) {
            return;
        }
        
        ticks++;
        ticksAboveThreshold.put(id, ticks);
        
        if (ticks >= transitionTicks) {
            if (entity instanceof Player) {
                Player player = (Player) entity;
                
                if (player.getInventory().getItemInMainHand().getType() == Material.DRAGON_BREATH ||
                    player.getInventory().getItemInOffHand().getType() == Material.DRAGON_BREATH) {
                    
                    switchWorld(entity);
                    ticksAboveThreshold.remove(id);
                    switchCooldowns.put(id, cooldownTicks);
                    
                } else {
                    if (ticks == transitionTicks) {
                        player.sendMessage(ChatColor.RED + "You need Dragon's Breath in your hand to enter the mirror dimension!");
                    }
                    ticksAboveThreshold.put(id, -1);
                }
            } else {
                switchWorld(entity);
                ticksAboveThreshold.remove(id);
                switchCooldowns.put(id, cooldownTicks);
            }
        }
    }

    private void switchWorld(Entity entity) {
        Location fromLoc = entity.getLocation();
        World fromWorld = fromLoc.getWorld();
        if (fromWorld == null) return;

        World toWorld;
        if (fromWorld.equals(mainOverworld)) {
            toWorld = mirrorOverworld;
        } else if (fromWorld.equals(mirrorOverworld)) {
            toWorld = mainOverworld;
        } else if (fromWorld.equals(mainNether)) {
            toWorld = mirrorNether;
        } else if (fromWorld.equals(mirrorNether)) {
            toWorld = mainNether;
        } else {
            return;
        }

        if (toWorld == null) return;

        double mirroredY = (2 * pivotY) - fromLoc.getY();
        Location toLoc = new Location(
            toWorld,
            fromLoc.getX(),
            mirroredY,
            fromLoc.getZ(),
            fromLoc.getYaw(),
            fromLoc.getPitch()
        );

        clampToBorder(toLoc, toWorld);

        if (entity instanceof Player) {
            Player player = (Player) entity;
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, fadeTicks, 1, false, false));
            player.playSound(toLoc, "minecraft:block.portal.travel", 1.0f, 1.0f);
        }

        entity.teleport(toLoc);
        getLogger().fine("Switched " + entity.getType() + " from " + fromWorld.getName() + " to " + toWorld.getName());
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        try {
            if (event.getCause() != TeleportCause.NETHER_PORTAL) {
                return;
            }
            
            if (mainNether == null || mirrorNether == null) {
                return;
            }

            Location fromLoc = event.getFrom();
            World fromWorld = fromLoc.getWorld();
            if (fromWorld == null) return;

            boolean isMirrorWorld = fromWorld.equals(mirrorOverworld) || 
                                   fromWorld.equals(mirrorNether);
            
            if (!isMirrorWorld) {
                return;
            }

            World toWorld;
            double scale;

            if (fromWorld.getEnvironment() == World.Environment.NORMAL) {
                toWorld = mirrorNether;
                scale = 1.0 / netherScale;
            } else if (fromWorld.getEnvironment() == World.Environment.NETHER) {
                toWorld = mirrorOverworld;
                scale = netherScale;
            } else {
                return;
            }

            if (toWorld == null) return;

            Location calcLoc = new Location(
                toWorld,
                fromLoc.getX() * scale,
                fromLoc.getY(),
                fromLoc.getZ() * scale
            );
            clampToBorder(calcLoc, toWorld);

            Location targetLoc = findPortal(calcLoc, portalSearchRadius);
            if (targetLoc == null) {
                targetLoc = createNetherPortal(calcLoc);
            }

            if (targetLoc != null) {
                event.setCancelled(true);
                event.getPlayer().teleport(targetLoc);
                getLogger().fine("Mirror world portal: " + fromWorld.getName() + " -> " + toWorld.getName());
            } else {
                getLogger().warning("Failed to create mirror portal for " + event.getPlayer().getName());
            }

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error in PlayerPortalEvent", e);
        }
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent event) {
        try {
            if (event.getEntity() instanceof Player) return;

            Location fromLoc = event.getFrom();
            if (fromLoc == null) return;

            Block portalBlock = fromLoc.getBlock();
            if (portalBlock.getType() != Material.NETHER_PORTAL) return;
            
            if (mainNether == null || mirrorNether == null) return;

            World fromWorld = fromLoc.getWorld();
            if (fromWorld == null) return;

            boolean isMirrorWorld = fromWorld.equals(mirrorOverworld) || 
                                   fromWorld.equals(mirrorNether);
            
            if (!isMirrorWorld) {
                return;
            }

            World toWorld;
            double scale;

            if (fromWorld.getEnvironment() == World.Environment.NORMAL) {
                toWorld = mirrorNether;
                scale = 1.0 / netherScale;
            } else if (fromWorld.getEnvironment() == World.Environment.NETHER) {
                toWorld = mirrorOverworld;
                scale = netherScale;
            } else {
                return;
            }

            if (toWorld == null) return;

            Location calcLoc = new Location(
                toWorld,
                fromLoc.getX() * scale,
                fromLoc.getY(),
                fromLoc.getZ() * scale
            );
            clampToBorder(calcLoc, toWorld);

            Location targetLoc = findPortal(calcLoc, portalSearchRadius);
            if (targetLoc == null) {
                targetLoc = createNetherPortal(calcLoc);
            }

            if (targetLoc != null) {
                event.setCancelled(true);
                event.getEntity().teleport(targetLoc);
                getLogger().fine("Mirror world entity portal: " + fromWorld.getName() + " -> " + toWorld.getName());
            }

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error in EntityPortalEvent", e);
        }
    }

    private Location findPortal(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) return null;
        
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        Location closest = null;
        double minDist = Double.MAX_VALUE;

        int centerChunkX = cx >> 4;
        int centerChunkZ = cz >> 4;
        int chunkRadius = (radius / 16) + 1;
        
        for (int chunkX = centerChunkX - 1; chunkX <= centerChunkX + 1; chunkX++) {
            for (int chunkZ = centerChunkZ - 1; chunkZ <= centerChunkZ + 1; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    try {
                        world.getChunkAt(chunkX, chunkZ);
                    } catch (Exception e) {
                        continue;
                    }
                }
            }
        }

        Set<Chunk> chunksToSearch = new HashSet<>();
        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    chunksToSearch.add(world.getChunkAt(chunkX, chunkZ));
                }
            }
        }

        List<int[]> searchOffsets = generateSpiralSearch(radius);
        
        for (int[] offset : searchOffsets) {
            int bx = cx + offset[0];
            int by = cy + offset[1];
            int bz = cz + offset[2];
            
            Chunk blockChunk = world.getChunkAt(bx >> 4, bz >> 4);
            if (!chunksToSearch.contains(blockChunk)) {
                continue;
            }
            
            try {
                if (world.getBlockAt(bx, by, bz).getType() == Material.NETHER_PORTAL) {
                    Location candidate = new Location(world, bx, by, bz);
                    double dist = center.distanceSquared(candidate);
                    
                    if (dist < minDist) {
                        minDist = dist;
                        closest = candidate;
                        
                        if (dist < 25) break;
                    }
                }
            } catch (Exception e) {
                continue;
            }
        }

        if (closest != null) {
            int by = closest.getBlockY();
            while (by > world.getMinHeight() && 
                   world.isChunkLoaded(closest.getBlockX() >> 4, closest.getBlockZ() >> 4) &&
                   world.getBlockAt(closest.getBlockX(), by - 1, closest.getBlockZ()).getType() == Material.NETHER_PORTAL) {
                by--;
            }
            
            closest.setY(by + 1);
            closest.setX(closest.getBlockX() + 0.5);
            closest.setZ(closest.getBlockZ() + 0.5);
            
            getLogger().fine("Found existing portal at " + closest.getBlockX() + "," + by + "," + closest.getBlockZ());
            return closest;
        }
        
        return null;
    }

    private List<int[]> generateSpiralSearch(int radius) {
        List<int[]> offsets = new ArrayList<>();
        
        offsets.add(new int[]{0, 0, 0});
        
        for (int r = 1; r <= radius; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) == r) {
                            offsets.add(new int[]{dx, dy, dz});
                        }
                    }
                }
            }
        }
        
        return offsets;
    }

    private Location createNetherPortal(Location center) {
        World world = center.getWorld();
        if (world == null) return null;
        
        int x = center.getBlockX() - 1;
        int y = findSafePortalY(center);
        int z = center.getBlockZ();

        if (y == -1) {
            getLogger().warning("Could not find safe Y for portal at " + center);
            return null;
        }

        try {
            for (int dx = 0; dx < 4; dx++) {
                for (int dy = 0; dy < 5; dy++) {
                    world.getBlockAt(x + dx, y + dy, z).setType(Material.AIR);
                }
            }

            for (int dx = 0; dx < 4; dx++) {
                world.getBlockAt(x + dx, y, z).setType(Material.OBSIDIAN);
                world.getBlockAt(x + dx, y + 4, z).setType(Material.OBSIDIAN);
            }
            for (int dy = 1; dy < 4; dy++) {
                world.getBlockAt(x, y + dy, z).setType(Material.OBSIDIAN);
                world.getBlockAt(x + 3, y + dy, z).setType(Material.OBSIDIAN);
            }

            for (int dx = 1; dx < 3; dx++) {
                for (int dy = 1; dy < 4; dy++) {
                    Block block = world.getBlockAt(x + dx, y + dy, z);
                    block.setType(Material.NETHER_PORTAL);
                    
                    Orientable portalData = (Orientable) block.getBlockData();
                    portalData.setAxis(Axis.X);
                    block.setBlockData(portalData);
                }
            }

            getLogger().info("Created new portal at " + x + "," + y + "," + z + " in " + world.getName());
            
            return new Location(world, x + 1.5, y + 1, z + 0.5, 0, 0);
            
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error creating portal", e);
            return null;
        }
    }

    private int findSafePortalY(Location center) {
        World world = center.getWorld();
        if (world == null) return -1;
        
        int maxY = world.getMaxHeight() - 5;
        int minY = world.getMinHeight() + 1;
        int originalY = (int) center.getY();

        int searchStartY = Math.min(originalY, maxY);
        int searchEndY = Math.max(minY, originalY - 64);

        for (int testY = searchStartY; testY >= searchEndY; testY--) {
            Location testLoc = new Location(world, center.getX(), testY - 1, center.getZ());
            
            if (!world.isChunkLoaded(testLoc.getBlockX() >> 4, testLoc.getBlockZ() >> 4)) {
                continue;
            }
            
            Location portalLoc = center.clone();
            portalLoc.setY(testY);
            
            if (testLoc.getBlock().getType().isSolid() && isAreaClearForPortal(portalLoc)) {
                return testY;
            }
        }
        
        return Math.max(minY, Math.min(originalY, maxY));
    }

    private boolean isAreaClearForPortal(Location bottomLeft) {
        World world = bottomLeft.getWorld();
        if (world == null) return false;
        
        int x = bottomLeft.getBlockX() - 1;
        int y = bottomLeft.getBlockY();
        int z = bottomLeft.getBlockZ();

        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }

        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                if (!world.getBlockAt(x + dx, y + dy, z).getType().isAir()) {
                    return false;
                }
            }
        }
        
        return true;
    }

    @Override
    public void onDisable() {
        getLogger().info("SkyWorlds v2.1.1 disabled.");
        
        ticksAboveThreshold.clear();
        switchCooldowns.clear();
    }
}
