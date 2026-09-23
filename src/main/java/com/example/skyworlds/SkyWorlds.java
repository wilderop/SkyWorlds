package com.example.skyworlds;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SkyWorlds 3.0 — Paper ground overworld. Fly-up (dragon's breath) sends the
 * player to the Fabric sky server via Velocity. Local mirror worlds are not used.
 */
public class SkyWorlds extends JavaPlugin implements Listener {
    public static final String CHANNEL = "skygate:connect";

    private World mainOverworld;
    private World mainNether;
    private World theEnd;

    private int thresholdY;
    private int pivotY;
    private int transitionTicks;
    private int cooldownTicks;
    private int fadeTicks;

    private final Map<UUID, Integer> ticksAboveThreshold = new HashMap<>();
    private final Map<UUID, Integer> switchCooldowns = new HashMap<>();

    private HandoffStore store;
    private RedisBus redis;
    private NamespacedKey pearlTag;
    private CombinedSleep combinedSleep;
    private StasisKeepalive stasisKeepalive;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigurationValues();
        pearlTag = new NamespacedKey(this, "transferred");

        Path handoff = Path.of(getConfig().getString("handoff-dir", "/mnt/pool/skygate"));
        store = new HandoffStore(handoff, getLogger());

        String host = getConfig().getString("redis.host", "127.0.0.1");
        int port = getConfig().getInt("redis.port", 6379);
        Path passFile = Path.of(getConfig().getString("redis.password-file", "redis.pass"));
        String sentinelMaster = getConfig().getString("redis.sentinel-master", "azpbmd");
        List<String> sentinels = getConfig().getStringList("redis.sentinels");
        if (sentinels == null || sentinels.isEmpty()) {
            sentinels = List.of("127.0.0.1:26379", "127.0.0.1:26379", "127.0.0.1:26379");
        }
        redis = new RedisBus(getLogger(), host, port, passFile, sentinelMaster, sentinels);
        if (!redis.connect()) {
            getLogger().severe("SkyGate Redis is required for Velocity. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        mainOverworld = getServer().getWorld("world");
        mainNether = getServer().getWorld("world_nether");
        theEnd = getServer().getWorld("world_the_end");
        if (mainOverworld == null) {
            getLogger().severe("Overworld (world) not found.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getPluginManager().registerEvents(this, this);
        combinedSleep = new CombinedSleep(this, store);
        getServer().getPluginManager().registerEvents(combinedSleep, this);
        disableVanillaSleepSkip();
        startDimensionTransitionTask();
        startIncomingTask();
        startClockPublishTask();
        int leftoverSky = LegacyMirrorResume.scanAndQueue(this, store, redis, mainOverworld);
        if (leftoverSky > 0) {
            getLogger().info("Queued " + leftoverSky + " leftover sky-world logouts to Fabric");
        }
        getServer().getScheduler().runTask(this, () -> {
            getServer().dispatchCommand(getServer().getConsoleSender(),
                    "time of minecraft:overworld resume");
            clearPersonalClocks();
            unloadLegacyMirrors();
        });

        stasisKeepalive = new StasisKeepalive(this, store, redis, pearlTag);
        stasisKeepalive.start();

        getLogger().info("SkyWorlds 3.0.18 enabled — fly-up uses Redis plus BungeeCord Connect.");
    }

    /**
     * Keep playersSleepingPercentage at 100. 101 makes vanilla show
     * "No amount of rest can pass this night" even though CombinedSleep
     * still skips. Vanilla night skip is cancelled in CombinedSleep.
     */
    private void disableVanillaSleepSkip() {
        getServer().dispatchCommand(getServer().getConsoleSender(),
                "execute in minecraft:overworld run gamerule minecraft:players_sleeping_percentage 100");
        getServer().dispatchCommand(getServer().getConsoleSender(),
                "gamerule minecraft:locator_bar false");
    }

    @Override
    public void onDisable() {
        ticksAboveThreshold.clear();
        switchCooldowns.clear();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");
        if (redis != null) {
            redis.close();
        }
        getLogger().info("SkyWorlds 3.0 disabled.");
    }

    private void loadConfigurationValues() {
        thresholdY = getConfig().getInt("threshold_y", 1320);
        pivotY = getConfig().getInt("pivot_y", 1321);
        transitionTicks = getConfig().getInt("transition_ticks", 60);
        cooldownTicks = getConfig().getInt("cooldown_ticks", 100);
        fadeTicks = getConfig().getInt("fade_ticks", 50);
    }

    private void startClockPublishTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (mainOverworld == null) return;
                applyFabricClockSkip();
                var border = mainOverworld.getWorldBorder();
                store.writeClock(
                        mainOverworld.getFullTime(),
                        mainOverworld.hasStorm(),
                        mainOverworld.isThundering(),
                        mainOverworld.getWeatherDuration(),
                        mainOverworld.getThunderDuration(),
                        border.getSize(),
                        border.getCenter().getX(),
                        border.getCenter().getZ());
                clearPersonalClocks();
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    /** Fabric sleep skip jumps sky time; Paper is the published clock, so catch up. */
    private void applyFabricClockSkip() {
        JsonObject skip = store.takeClockSkip();
        if (skip == null || !skip.has("fullTime")) {
            return;
        }
        if (skip.has("created")) {
            long age = System.currentTimeMillis() - skip.get("created").getAsLong();
            if (age > 15_000L) {
                return;
            }
        }
        long fullTime = skip.get("fullTime").getAsLong();
        if (combinedSleep != null) {
            combinedSleep.runAsOurSkip(() -> mainOverworld.setFullTime(fullTime));
        } else {
            mainOverworld.setFullTime(fullTime);
        }
        if (!skip.has("clearWeather") || skip.get("clearWeather").getAsBoolean()) {
            mainOverworld.setStorm(false);
            mainOverworld.setThundering(false);
        }
        if (combinedSleep != null) {
            combinedSleep.wakeSleepers();
        }
        getLogger().info("Applied Fabric sleep skip, fullTime=" + fullTime);
    }

    private void startIncomingTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                store.consumeIncoming("in-survival", SkyWorlds.this::handleIncoming);
            }
        }.runTaskTimer(this, 5L, 2L);
    }

    private void handleIncoming(JsonObject o) {
        String kind = o.has("kind") ? o.get("kind").getAsString() : "player";
        try {
            switch (kind) {
                case "pearl" -> spawnIncomingPearl(o);
                case "entity" -> spawnIncomingEntity(o);
                default -> {
                    // player payload is applied on join
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "incoming handoff", e);
        }
    }

    private void spawnIncomingPearl(JsonObject o) {
        World w = worldForDim(o.has("dim") ? o.get("dim").getAsString() : "overworld");
        if (w == null) return;
        double requestedY = o.get("y").getAsDouble();
        double y = findPearlFallY(w, o.get("x").getAsDouble(), requestedY, o.get("z").getAsDouble());
        Location loc = new Location(w, o.get("x").getAsDouble(), y, o.get("z").getAsDouble());
        w.getChunkAt(loc);
        Vector vel = new Vector(o.get("vx").getAsDouble(), o.get("vy").getAsDouble(), o.get("vz").getAsDouble());
        UUID owner = UUID.fromString(o.get("owner").getAsString());
        EnderPearl pearl = w.spawn(loc, EnderPearl.class, p -> {
            p.getPersistentDataContainer().set(pearlTag, PersistentDataType.BYTE, (byte) 1);
            p.setVelocity(vel);
            Player shooter = getServer().getPlayer(owner);
            if (shooter != null) {
                p.setShooter(shooter);
            }
        });
        pearl.getPersistentDataContainer().set(new NamespacedKey(this, "owner"),
                PersistentDataType.STRING, owner.toString());
        getLogger().info("Incoming pearl owner=" + owner
                + " at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                + " (from y=" + (int) requestedY + ") vy=" + vel.getY());
    }

    /** Drop through seam-height solids so a throw from ~1310 falls instead of dying on the gate floor. */
    private double findPearlFallY(World w, double x, double startY, double z) {
        double y = Math.min(startY, thresholdY - 0.25);
        double maxAccept = thresholdY - 20.0;
        int minY = w.getMinHeight() + 2;
        Double firstOpen = null;
        for (int i = 0; i < 2000 && y > minY; i++) {
            Location loc = new Location(w, x, y, z);
            w.getChunkAt(loc);
            if (isPearlAir(loc) && isPearlAir(loc.clone().add(0, -1, 0)) && isPearlAir(loc.clone().add(0, -2, 0))) {
                if (firstOpen == null) {
                    firstOpen = y;
                }
                if (y <= maxAccept) {
                    return y;
                }
            }
            y -= 0.5;
        }
        return firstOpen != null ? Math.min(firstOpen, maxAccept) : y;
    }

    private static boolean isPearlAir(Location loc) {
        return loc.getBlock().isEmpty();
    }

    private void spawnIncomingEntity(JsonObject o) {
        if (!o.has("snbt")) return;
        World w = worldForDim(o.has("dim") ? o.get("dim").getAsString() : "overworld");
        if (w == null) return;
        Location loc = new Location(w, o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
        spawnFromSnbt(o.get("snbt").getAsString(), loc);
    }

    private Entity spawnFromSnbt(String snbt, Location loc) {
        try {
            Class<?> tagParser = Class.forName("net.minecraft.nbt.TagParser");
            Object tag = tagParser.getMethod("parseCompoundFully", String.class).invoke(null, snbt);
            Class<?> snapCl = Class.forName("org.bukkit.craftbukkit.entity.CraftEntitySnapshot");
            for (Class<?> param : new Class<?>[] {
                    Class.forName("net.minecraft.nbt.CompoundTag")
            }) {
                try {
                    Object snap = snapCl.getMethod("create", param).invoke(null, tag);
                    if (snap instanceof org.bukkit.entity.EntitySnapshot es) {
                        return es.createEntity(loc);
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Exception e) {
            getLogger().fine("entity spawn: " + e.getMessage());
        }
        return null;
    }

    private World worldForDim(String dim) {
        if (dim == null) return mainOverworld;
        return switch (dim) {
            case "nether" -> mainNether != null ? mainNether : mainOverworld;
            case "end" -> theEnd != null ? theEnd : mainOverworld;
            default -> mainOverworld;
        };
    }

    private String dimOf(World world) {
        if (world == null) return "overworld";
        if (world.getEnvironment() == World.Environment.NETHER) return "nether";
        if (world.getEnvironment() == World.Environment.THE_END) return "end";
        return "overworld";
    }

    private void startDimensionTransitionTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                switchCooldowns.replaceAll((id, ticks) -> ticks - 1);
                switchCooldowns.entrySet().removeIf(e -> e.getValue() <= 0);
                if (mainOverworld != null) processEntitiesInWorld(mainOverworld);
                if (mainNether != null) processEntitiesInWorld(mainNether);
                if (combinedSleep != null && mainOverworld != null) {
                    combinedSleep.tick(mainOverworld);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void unloadLegacyMirrors() {
        for (String name : List.of("mirror_overworld", "mirror_nether")) {
            World w = getServer().getWorld(name);
            if (w != null) {
                boolean ok = getServer().unloadWorld(w, true);
                getLogger().info("Unloaded leftover " + name + ": " + ok);
            }
        }
    }

    private void processEntitiesInWorld(World world) {
        try {
            for (Entity entity : world.getEntities()) {
                UUID id = entity.getUniqueId();
                if (switchCooldowns.containsKey(id)) continue;
                if (travelsWithPlayer(entity)) continue;
                Location loc = entity.getLocation();
                if (loc.getY() > thresholdY) {
                    handleEntityAboveThreshold(entity, id);
                } else {
                    ticksAboveThreshold.remove(id);
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "scan " + world.getName(), e);
        }
    }

    private static boolean travelsWithPlayer(Entity entity) {
        if (entity instanceof Player) return false;
        if (entity instanceof EnderPearl) return false;
        for (Entity p : entity.getPassengers()) {
            if (p instanceof Player) return true;
        }
        Entity vehicle = entity.getVehicle();
        if (vehicle instanceof Player) return true;
        if (entity instanceof org.bukkit.entity.LivingEntity living && living.isLeashed()) {
            try {
                return living.getLeashHolder() instanceof Player;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void handleEntityAboveThreshold(Entity entity, UUID id) {
        if (entity instanceof EnderPearl pearl) {
            if (isTransferred(pearl)) return;
            // Parked stasis must stay on this side. Only flying pearls cross Y 1320.
            if (pearl.getVelocity().lengthSquared() < 0.01) {
                return;
            }
            gatePearl(pearl);
            return;
        }
        int ticks = ticksAboveThreshold.getOrDefault(id, 0);
        if (ticks < 0) return;
        ticks++;
        ticksAboveThreshold.put(id, ticks);
        if (ticks < transitionTicks) return;

        if (entity instanceof Player player) {
            if (hasBreath(player)) {
                gatePlayer(player);
                ticksAboveThreshold.remove(id);
                switchCooldowns.put(id, cooldownTicks);
            } else if (ticks == transitionTicks) {
                player.sendMessage(ChatColor.RED + "You need Dragon's Breath in your hand to enter the sky world!");
                ticksAboveThreshold.put(id, -1);
            }
        } else {
            gateLooseEntity(entity);
            ticksAboveThreshold.remove(id);
            switchCooldowns.put(id, cooldownTicks);
        }
    }

    private boolean hasBreath(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.DRAGON_BREATH
                || player.getInventory().getItemInOffHand().getType() == Material.DRAGON_BREATH;
    }

    private boolean isTransferred(Entity entity) {
        Byte v = entity.getPersistentDataContainer().get(pearlTag, PersistentDataType.BYTE);
        return v != null && v == 1;
    }

    private Location mirrored(Location from) {
        double y = (2.0 * pivotY) - from.getY();
        Location to = from.clone();
        to.setY(y);
        return to;
    }

    private void gatePlayer(Player player) {
        Location dest = mirrored(player.getLocation());
        List<String> extras = HandoffStore.takeMountsAndLeashes(player);
        String dim = dimOf(player.getWorld());
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, fadeTicks, 1, false, false));
        player.playSound(player.getLocation(), "minecraft:block.portal.travel", 1.0f, 1.0f);
        store.writePlayerToFabric(player, dest, dim, extras);
        requestProxyConnect(player, "fabric");
        getLogger().info("Gated " + player.getName() + " to Fabric");
    }

    private void gatePearl(EnderPearl pearl) {
        ProjectileSource src = pearl.getShooter();
        UUID owner = src instanceof Player p ? p.getUniqueId() : pearl.getUniqueId();
        Location dest = mirrored(pearl.getLocation());
        dest.setY(Math.min(dest.getY(), thresholdY - 2.0));
        Vector vel = pearl.getVelocity().clone();
        vel.setY(-vel.getY());
        store.writePearl("in-fabric", owner, dest, vel, dimOf(pearl.getWorld()));
        pearl.remove();
        if (src instanceof Player shooter && shooter.isOnline()) {
            shooter.playSound(shooter.getLocation(), "minecraft:block.portal.travel", 0.4f, 1.2f);
        }
        getLogger().info("Pearl -> fabric owner=" + owner
                + " at " + dest.getBlockX() + "," + dest.getBlockY() + "," + dest.getBlockZ()
                + " vy=" + vel.getY());
    }

    private void gateLooseEntity(Entity entity) {
        if (entity instanceof Player) return;
        String snbt = HandoffStore.snapshot(entity);
        if (snbt == null) return;
        Location dest = mirrored(entity.getLocation());
        store.writeEntity("in-fabric", snbt, dest, dimOf(entity.getWorld()));
        entity.remove();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        JsonObject pending = store.takePlayer(player.getUniqueId(), "in-survival");
        if (pending != null) {
            getServer().getScheduler().runTask(this, () -> {
                applyPlayerHandoff(player, pending);
                unstickPlayer(player);
            });
            getServer().getScheduler().runTaskLater(this, () -> unstickPlayer(player), 40L);
            if (stasisKeepalive != null) {
                stasisKeepalive.onJoin(player);
            }
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> unstickPlayer(player), 40L);
        if (stasisKeepalive != null) {
            stasisKeepalive.onJoin(player);
        }
        if (LegacyMirrorResume.isLegacyMirrorWorld(player.getWorld())) {
            getLogger().info("Join in leftover " + player.getWorld().getName()
                    + " — sending " + player.getName() + " to Fabric");
            getServer().getScheduler().runTask(this, () -> {
                LegacyMirrorResume.resumeOnline(player, store, redis);
                sendBungeeConnect(player, "fabric");
            });
            return;
        }
        if (store.peekPlayer(player.getUniqueId(), "in-fabric") != null || redis.hasMigrate(player.getUniqueId())) {
            getServer().getScheduler().runTask(this, () -> requestProxyConnect(player, "fabric"));
        }
    }

    private void applyPlayerHandoff(Player player, JsonObject o) {
        World w = worldForDim(o.has("dim") ? o.get("dim").getAsString() : "overworld");
        double y = Math.min(o.get("y").getAsDouble(), thresholdY - 2.0);
        Location loc = new Location(w,
                o.get("x").getAsDouble(), y, o.get("z").getAsDouble(),
                o.has("yaw") ? o.get("yaw").getAsFloat() : player.getLocation().getYaw(),
                o.has("pitch") ? o.get("pitch").getAsFloat() : player.getLocation().getPitch());
        player.teleport(loc);
        if (o.has("stasis") && o.get("stasis").getAsBoolean() && stasisKeepalive != null) {
            stasisKeepalive.consumeNear(player, loc);
        }
        if (o.has("vx")) {
            player.setVelocity(new Vector(o.get("vx").getAsDouble(), o.get("vy").getAsDouble(), o.get("vz").getAsDouble()));
        }
        if (o.has("gliding") && o.get("gliding").getAsBoolean()) {
            player.setGliding(true);
        }
        spawnExtras(player, o);
        switchCooldowns.put(player.getUniqueId(), cooldownTicks);
        unstickPlayer(player);
    }

    private void unstickPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            if (player.isSleeping()) {
                player.wakeup(true);
            }
        } catch (Exception ignored) {
        }
        try {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }
            player.setAllowFlight(false);
            player.setFlying(false);
            if (player.getWalkSpeed() <= 0.0f) {
                player.setWalkSpeed(0.2f);
            }
        } catch (Exception ignored) {
        }
    }

    private void spawnExtras(Player player, JsonObject o) {
        if (!o.has("entities") || !o.get("entities").isJsonArray()) return;
        JsonArray arr = o.getAsJsonArray("entities");
        Location loc = player.getLocation();
        for (var el : arr) {
            Entity spawned = spawnFromSnbt(el.getAsString(), loc);
            if (spawned == null) continue;
            if (spawned instanceof org.bukkit.entity.LivingEntity living) {
                try {
                    living.setLeashHolder(player);
                } catch (Exception ignored) {
                }
            } else {
                spawned.addPassenger(player);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ticksAboveThreshold.remove(event.getPlayer().getUniqueId());
        if (stasisKeepalive != null) {
            stasisKeepalive.onQuit(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) {
            return;
        }
        Location bed = event.getBed().getLocation();
        store.writeBed(event.getPlayer().getUniqueId(), "survival", dimOf(event.getPlayer().getWorld()), bed);
        JsonObject o = new JsonObject();
        o.addProperty("server", "survival");
        o.addProperty("world", dimOf(event.getPlayer().getWorld()));
        o.addProperty("x", bed.getX());
        o.addProperty("y", bed.getY());
        o.addProperty("z", bed.getZ());
        redis.setBed(event.getPlayer().getUniqueId(), o.toString(), 60 * 60 * 24 * 30);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        JsonObject bed = resolveBed(event.getPlayer().getUniqueId());
        if (bed == null) {
            return;
        }
        if (isFabricBed(bed)) {
            sendToFabricBed(event.getPlayer(), bed);
            return;
        }
        Location dest = survivalBedLocation(bed);
        if (dest != null) {
            event.setRespawnLocation(dest);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getCause() != TeleportCause.END_PORTAL) {
            return;
        }
        World from = event.getFrom().getWorld();
        if (from == null || from.getEnvironment() != World.Environment.THE_END) {
            // Entering the End from ground overworld/nether stays vanilla.
            return;
        }
        JsonObject bed = resolveBed(event.getPlayer().getUniqueId());
        if (bed == null) {
            return;
        }
        if (isFabricBed(bed)) {
            event.setCancelled(true);
            sendToFabricBed(event.getPlayer(), bed);
            return;
        }
        Location dest = survivalBedLocation(bed);
        if (dest != null) {
            event.setTo(dest);
        }
    }

    private JsonObject resolveBed(UUID uuid) {
        JsonObject bed = store.readBed(uuid);
        if (bed != null) {
            return bed;
        }
        String raw = redis.getBed(uuid);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isFabricBed(JsonObject bed) {
        String server = bed.has("server") ? bed.get("server").getAsString() : "survival";
        return "fabric".equalsIgnoreCase(server);
    }

    private Location survivalBedLocation(JsonObject bed) {
        if (!bed.has("x") || !bed.has("y") || !bed.has("z")) {
            return null;
        }
        World w = worldForDim(bed.has("world") ? bed.get("world").getAsString() : "overworld");
        if (w == null) {
            return null;
        }
        float yaw = bed.has("yaw") ? bed.get("yaw").getAsFloat() : 0f;
        float pitch = bed.has("pitch") ? bed.get("pitch").getAsFloat() : 0f;
        return new Location(w,
                bed.get("x").getAsDouble() + 0.5,
                bed.get("y").getAsDouble() + 0.5,
                bed.get("z").getAsDouble() + 0.5,
                yaw, pitch);
    }

    private void sendToFabricBed(Player player, JsonObject bed) {
        UUID uuid = player.getUniqueId();
        String dim = bed.has("world") ? bed.get("world").getAsString() : "overworld";
        Location dest = new Location(mainOverworld,
                bed.get("x").getAsDouble() + 0.5,
                bed.get("y").getAsDouble() + 0.5,
                bed.get("z").getAsDouble() + 0.5,
                bed.has("yaw") ? bed.get("yaw").getAsFloat() : player.getLocation().getYaw(),
                bed.has("pitch") ? bed.get("pitch").getAsFloat() : player.getLocation().getPitch());
        store.writePlayerToFabric(player, dest, dim, List.of());
        requestProxyConnect(player, "fabric");
        getLogger().info("Shared bed: " + player.getName() + " -> fabric");
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (player.getWorld() != null && player.getWorld().getEnvironment() == World.Environment.THE_END) {
                getLogger().warning("Shared bed transfer stalled for " + player.getName() + "; leaving End locally");
                Location fallback = survivalBedLocation(resolveBed(uuid));
                if (fallback == null && mainOverworld != null) {
                    fallback = mainOverworld.getSpawnLocation();
                }
                if (fallback != null) {
                    player.teleport(fallback);
                }
            }
        }, 60L);
    }

    /** Redis pub/sub plus BungeeCord Connect so a dead Velocity subscriber cannot eat the gate. */
    private void requestProxyConnect(Player player, String dest) {
        if (player == null || dest == null || dest.isBlank()) {
            return;
        }
        redis.allow(player.getUniqueId(), dest, 120);
        if ("fabric".equalsIgnoreCase(dest)) {
            redis.markMigrate(player.getUniqueId());
        }
        redis.publishConnect(player.getUniqueId(), dest);
        sendBungeeConnect(player, dest);
        getServer().getScheduler().runTask(this, () -> sendBungeeConnect(player, dest));
    }

    private void sendBungeeConnect(Player player, String dest) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(dest);
            player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
        } catch (Exception e) {
            getLogger().warning("Bungee Connect failed: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl)) return;
        // Gating happens on tick when Y exceeds threshold.
    }

    @EventHandler
    public void onPearlHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!isTransferred(pearl)) {
            if (stasisKeepalive != null) {
                stasisKeepalive.onHit(pearl);
            }
            return;
        }
        String ownerStr = pearl.getPersistentDataContainer().get(new NamespacedKey(this, "owner"), PersistentDataType.STRING);
        if (ownerStr == null) return;
        UUID owner = UUID.fromString(ownerStr);
        Player p = getServer().getPlayer(owner);
        Location hit = pearl.getLocation().clone();
        if (hit.getY() > thresholdY - 2.0) {
            hit.setY(thresholdY - 2.0);
        }
        if (p != null && p.isOnline()) {
            getLogger().info("Incoming pearl hit owner=" + owner
                    + " at " + hit.getBlockX() + "," + hit.getBlockY() + "," + hit.getBlockZ()
                    + " pull=local");
            p.teleport(hit);
            return;
        }
        // Pearls still cross the gate as entities. Do not Velocity-switch the
        // owner: that kidnapped players whose pearl crossed Y 1320 far away.
        getLogger().info("Incoming pearl hit owner=" + owner
                + " at " + hit.getBlockX() + "," + hit.getBlockY() + "," + hit.getBlockZ()
                + " pull=none (owner not on survival)");
    }

    private void clearPersonalClocks() {
        for (Player player : getServer().getOnlinePlayers()) {
            try {
                if (!player.isPlayerTimeRelative()) player.resetPlayerTime();
                player.resetPlayerWeather();
                if (player.getWalkSpeed() <= 0.0f) player.setWalkSpeed(0.2f);
            } catch (Exception ignored) {
            }
        }
    }
}
