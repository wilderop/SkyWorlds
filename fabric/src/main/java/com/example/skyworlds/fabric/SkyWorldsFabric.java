package com.example.skyworlds.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.stats.Stats;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.util.Pool;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class SkyWorldsFabric implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("SkyWorlds");
    private static SkyWorldsFabric INSTANCE;
    private static final Path DIR = Path.of("/mnt/pool/skygate");
    private static final Path PAPER_PLAYERS = Path.of("/mnt/pool/survival/world/players/data");
    private static final int THRESHOLD = 1320;
    private static final int PIVOT = 1321;
    private static final int TRANSITION = 60;

    private final Map<UUID, Integer> ticksAbove = new HashMap<>();
    private final Map<UUID, Integer> cooldown = new HashMap<>();
    private final Map<UUID, Vec3> pearlSpawnPos = new HashMap<>();
    private final Map<UUID, Vec3> pearlLastPos = new HashMap<>();
    private final Map<UUID, String> pearlLastDim = new HashMap<>();
    private final Map<UUID, UUID> pearlOwner = new HashMap<>();
    private final Set<UUID> trackedPearls = new HashSet<>();
    private final Map<UUID, ThrownEnderpearl> trackedPearlEntities = new HashMap<>();
    private final Map<UUID, ChunkPos> pearlTickets = new HashMap<>();
    private final Set<UUID> pearlSeen = new HashSet<>();
    private final Map<UUID, Integer> pearlSpawnTick = new HashMap<>();
    private final Set<UUID> stasisKeepPearls = new HashSet<>();
    private final Map<UUID, Integer> pendingHandoffTries = new HashMap<>();
    /** Unstick again after PDS apply; End spectator can land after the first pass. */
    private final Map<UUID, Integer> pendingUnstick = new HashMap<>();
    private final Map<UUID, Vec3> lastOverworldPos = new HashMap<>();
    private final Map<UUID, float[]> lastOverworldRot = new HashMap<>();
    /** Prevents End/nether teleport handlers from re-entering (spectator loop). */
    private final Set<UUID> dimChangeGuard = new HashSet<>();
    /** Velocity switch to survival already issued; do not gate or teleport again. */
    private final Set<UUID> leavingForSurvival = new HashSet<>();
    private final Map<UUID, Map<String, Long>> councilBaseline = new HashMap<>();
    private volatile List<String> councilStatNames = List.of(
            "PLAY_ONE_MINUTE", "WALK_ONE_CM", "AVIATE_ONE_CM", "MOB_KILLS");
    private int councilTicks;
    private Pool<Jedis> pool;
    private MinecraftServer server;
    private final CombinedSleep combinedSleep = new CombinedSleep();

    @Override
    public void onInitialize() {
        INSTANCE = this;
        connectRedis();
        ServerPlayConnectionEvents.INIT.register((handler, srv) -> {
            this.server = srv;
            if (handler.player != null) {
                stripGhostPlayers(srv, handler.player.getUUID(), handler.player);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            this.server = srv;
            ServerPlayer player = handler.player;
            pendingHandoffTries.put(player.getUUID(), 80);
            stripGhostPlayers(srv, player.getUUID(), player);
            srv.execute(() -> {
                discardDuplicatePlayers(player);
                applyIncomingPlayer(player);
                importPaperInventoryIfEmpty(player);
                unstickPlayer(player);
                importPaperAdvancements(player);
                cooldown.put(player.getUUID(), 200);
                markMigrate(player.getUUID());
                councilBaseline.put(player.getUUID(), captureCouncilStats(player));
                EmptyServerReward.onJoin(player);
                CouncilCommands.onJoin(player);
                pendingUnstick.put(player.getUUID(), 40);
                reconcileStasis(player);
            });
            srv.execute(() -> unstickPlayer(player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            this.server = srv;
            List<PearlSnap> snaps = snapshotPearls(handler.player);
            UUID id = handler.player.getUUID();
            lastOverworldPos.remove(id);
            lastOverworldRot.remove(id);
            dimChangeGuard.remove(id);
            leavingForSurvival.remove(id);
            pendingUnstick.remove(id);
            EmptyServerReward.onDisconnect(srv, handler.player);
            flushCouncilDelta(handler.player);
            markMigrate(id);
            srv.execute(() -> {
                stripGhostPlayers(srv, id, null);
                spawnStasisKeeps(snaps);
            });
        });
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            this.server = srv;
            EmptyServerReward.tick(srv);
            combinedSleep.tick(srv, this);
            tickCooldowns();
            tickPendingUnstick(srv);
            consumeIncoming();
            applyClock(srv);
            tickTrackedPearls();
            retryPendingHandoffs(srv);
            tickCouncilSnaps(srv);
            CouncilCommands.tick(srv, this);
            if (srv.getTickCount() % 20 == 0) {
                sweepGhostPlayers(srv);
            }
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                rememberOverworld(p);
                scanEntity(p);
            }
            for (ServerLevel level : srv.getAllLevels()) {
                if (isEnd(level)) {
                    continue;
                }
                for (Entity e : level.getAllEntities()) {
                    if (e instanceof ServerPlayer) continue;
                    scanEntity(e);
                }
            }
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
            UUID id = player.getUUID();
            if (leavingForSurvival.contains(id)) {
                return;
            }
            if (!dimChangeGuard.add(id)) {
                return;
            }
            try {
                if (isEnd(destination)) {
                    // Never stay in Fabric End. Do not teleportTo overworld here:
                    // that recurses through teleportSpectators and watchdog-kills the tick.
                    leaveFabricEnd(player);
                    return;
                }
                if (origin != null && origin.dimension() != destination.dimension()) {
                    MinecraftServer srv = destination.getServer();
                    if (srv != null) {
                        srv.execute(() -> {
                            ServerPlayer p = srv.getPlayerList().getPlayer(id);
                            if (p != null && !leavingForSurvival.contains(id)) {
                                resyncClientPosition(p);
                            }
                        });
                    }
                }
            } finally {
                dimChangeGuard.remove(id);
            }
        });
        // CombinedSleep owns night skip. Returning false here is enough;
        // pinning the gamerule to 101 shows "No amount of rest can pass this night".
        EntitySleepEvents.ALLOW_RESETTING_TIME.register(player -> false);
        EntitySleepEvents.START_SLEEPING.register((entity, pos) -> {
            if (!(entity instanceof ServerPlayer player)) return;
            combinedSleep.noteSleep(player.getUUID());
            JsonObject o = new JsonObject();
            o.addProperty("server", "fabric");
            o.addProperty("world", dimOf(player.level()));
            o.addProperty("x", pos.getX());
            o.addProperty("y", pos.getY());
            o.addProperty("z", pos.getZ());
            o.addProperty("yaw", player.getYRot());
            o.addProperty("pitch", player.getXRot());
            writeJson(DIR.resolve("beds").resolve(player.getUUID() + ".json"), o);
            if (pool != null) {
                try (Jedis j = pool.getResource()) {
                    jedisSetex(j, "skygate:bed:" + player.getUUID(), 60 * 60 * 24 * 30, o.toString());
                } catch (Exception ignored) {
                }
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            applySharedBed(newPlayer);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            try {
                srv.getCommands().performPrefixedCommand(
                        srv.createCommandSourceStack(),
                        "execute in minecraft:overworld run gamerule minecraft:players_sleeping_percentage 100");
                srv.getCommands().performPrefixedCommand(
                        srv.createCommandSourceStack(),
                        "gamerule minecraft:locator_bar false");
            } catch (Throwable t) {
                LOG.warn("Could not pin sleep gamerule to 100: {}", t.toString());
            }
        });
        reloadCouncilStatNames();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CouncilCommands.register(dispatcher, this));
        LOG.info("SkyWorlds Fabric 3.0.40: council cmds do not send coordinates.");
    }

    private void connectRedis() {
        String password = "";
        try {
            Path pf = Path.of("redis.pass");
            if (Files.isRegularFile(pf)) password = Files.readString(pf).trim();
        } catch (Exception ignored) {
        }
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(4);
        try {
            java.util.Set<String> sentinels = java.util.Set.of(
                    "127.0.0.1:26379", "127.0.0.1:26379", "127.0.0.1:26379");
            pool = password.isBlank()
                    ? new redis.clients.jedis.JedisSentinelPool("azpbmd", sentinels, cfg, 2000)
                    : new redis.clients.jedis.JedisSentinelPool("azpbmd", sentinels, cfg, 2000, password);
            LOG.info("SkyGate Redis via Sentinel master=azpbmd");
        } catch (Exception e) {
            LOG.warn("SkyGate Sentinel failed ({}), falling back to 127.0.0.1", e.getMessage());
            pool = password.isBlank()
                    ? new JedisPool(cfg, "127.0.0.1", 6379, 2000)
                    : new JedisPool(cfg, "127.0.0.1", 6379, 2000, password);
        }
        try (Jedis j = pool.getResource()) {
            jedisCall(j, "ping", new Class<?>[0]);
        } catch (Exception e) {
            LOG.error("Redis failed: {}", e.getMessage());
        }
    }

    private void tickCooldowns() {
        cooldown.replaceAll((id, t) -> t - 1);
        cooldown.entrySet().removeIf(e -> e.getValue() <= 0);
    }

    private void tickPendingUnstick(MinecraftServer srv) {
        if (pendingUnstick.isEmpty()) {
            return;
        }
        List<UUID> due = new ArrayList<>();
        pendingUnstick.replaceAll((id, t) -> t - 1);
        for (var e : pendingUnstick.entrySet()) {
            if (e.getValue() <= 0) {
                due.add(e.getKey());
            }
        }
        for (UUID id : due) {
            pendingUnstick.remove(id);
            ServerPlayer p = srv.getPlayerList().getPlayer(id);
            if (p != null) {
                unstickPlayer(p);
            }
        }
    }

    private void scanEntity(Entity entity) {
        UUID id = entity.getUUID();
        if (leavingForSurvival.contains(id)) return;
        if (cooldown.containsKey(id)) return;
        if (isEnd(entity.level())) return;
        if (travelsWithPlayer(entity)) return;
        if (entity.getY() <= THRESHOLD) {
            ticksAbove.remove(id);
            return;
        }
        if (entity instanceof ThrownEnderpearl pearl) {
            if (trackedPearls.contains(pearl.getUUID())) {
                return;
            }
            // Parked stasis (near-zero velocity) must stay. Only flying pearls cross the gate.
            Vec3 motion = pearl.getDeltaMovement();
            if (motion.lengthSqr() < 0.01) {
                return;
            }
            gatePearl(pearl);
            return;
        }
        int t = ticksAbove.getOrDefault(id, 0) + 1;
        ticksAbove.put(id, t);
        if (t < TRANSITION) return;
        if (entity instanceof ServerPlayer player) {
            if (hasBreath(player)) {
                sendToSurvival(player, dimOf(player.level()), player.getX(), mirroredY(player.getY()), player.getZ(),
                        player.getYRot(), player.getXRot(), player.getDeltaMovement());
                ticksAbove.remove(id);
                cooldown.put(id, 200);
            } else if (t == TRANSITION) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§cYou need Dragon's Breath in your hand to return to the ground world!"));
                ticksAbove.put(id, -9999);
            }
        } else {
            gateLooseEntity(entity);
            ticksAbove.remove(id);
            cooldown.put(id, 100);
        }
    }

    private static boolean travelsWithPlayer(Entity entity) {
        if (entity instanceof ServerPlayer || entity instanceof ThrownEnderpearl) return false;
        for (Entity p : entity.getPassengers()) {
            if (p instanceof ServerPlayer) return true;
        }
        if (entity.getVehicle() instanceof ServerPlayer) return true;
        if (entity instanceof Leashable leashable && leashable.isLeashed()) {
            Entity holder = leashable.getLeashHolder();
            return holder instanceof ServerPlayer;
        }
        return false;
    }

    private boolean hasBreath(ServerPlayer player) {
        return player.getMainHandItem().getItem() == Items.DRAGON_BREATH
                || player.getOffhandItem().getItem() == Items.DRAGON_BREATH;
    }

    private double mirroredY(double y) {
        return landingY((2.0 * PIVOT) - y);
    }

    /** Playable side of the gate is at or below THRESHOLD. Mirror around the pivot can land above it. */
    private double landingY(double y) {
        return Math.min(y, THRESHOLD - 2.0);
    }

    private String dimOf(Level level) {
        if (level.dimension() == Level.NETHER) return "nether";
        if (level.dimension() == Level.END) return "end";
        return "overworld";
    }

    private boolean isEnd(Level level) {
        return level.dimension() == Level.END;
    }

    private void rememberOverworld(ServerPlayer player) {
        if (player.level().dimension() != Level.OVERWORLD) {
            return;
        }
        lastOverworldPos.put(player.getUUID(), player.position());
        lastOverworldRot.put(player.getUUID(), new float[]{player.getYRot(), player.getXRot()});
    }

    /**
     * Send the player to Paper End and disconnect. Do not teleportTo overworld:
     * that recurses through teleportSpectators (nether/End) and blocks on Redis.
     */
    private void leaveFabricEnd(ServerPlayer player) {
        if (player.isRemoved()) {
            return;
        }
        discardDuplicatePlayers(player);
        if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR
                || player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
            player.setGameMode(GameType.SURVIVAL);
        }
        sendToSurvival(player, "end", 100.0, 49.0, 0.0,
                player.getYRot(), player.getXRot(), Vec3.ZERO);
        try {
            player.disconnect();
        } catch (Exception e) {
            LOG.warn("disconnect after End gate: {}", e.toString());
        }
    }

    /**
     * Client-only resync after nether &lt;-&gt; overworld. connection.teleport does
     * not call teleportSpectators, so it cannot loop the way teleportTo did.
     */
    private void resyncClientPosition(ServerPlayer player) {
        if (player.isRemoved() || leavingForSurvival.contains(player.getUUID())) {
            return;
        }
        if (player.level().dimension() == Level.END) {
            leaveFabricEnd(player);
            return;
        }
        discardDuplicatePlayers(player);
        try {
            player.connection.teleport(player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        } catch (Exception e) {
            LOG.warn("nether/overworld resync: {}", e.toString());
        }
    }

    private void unstickPlayer(ServerPlayer player) {
        if (player == null || player.isRemoved()) {
            return;
        }
        discardDuplicatePlayers(player);
        try {
            if (player.isSleeping()) {
                player.stopSleepInBed(true, true);
            }
        } catch (Exception ignored) {
            try {
                player.stopSleeping();
            } catch (Exception ignored2) {
            }
        }
        GameType mode = player.gameMode.getGameModeForPlayer();
        if (mode == GameType.SPECTATOR) {
            player.setGameMode(GameType.SURVIVAL);
            mode = GameType.SURVIVAL;
        }
        var ab = player.getAbilities();
        if (mode == GameType.SURVIVAL || mode == GameType.ADVENTURE) {
            ab.mayBuild = true;
            ab.flying = false;
            ab.mayfly = false;
            ab.instabuild = false;
            ab.invulnerable = false;
        }
        player.onUpdateAbilities();
        try {
            player.connection.teleport(player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        } catch (Exception ignored) {
        }
    }

    /** Sky-only players can join Fabric with an empty PDS row; copy Paper player.dat. */
    private void importPaperInventoryIfEmpty(ServerPlayer player) {
        if (!inventoryLooksEmpty(player)) {
            return;
        }
        Path dat = PAPER_PLAYERS.resolve(player.getUUID() + ".dat");
        if (!Files.isRegularFile(dat)) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(dat, NbtAccounter.unlimitedHeap());
            var ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
            Inventory inv = player.getInventory();
            int loaded = 0;
            ListTag list = tag.getListOrEmpty("Inventory");
            if (!list.isEmpty()) {
                inv.clearContent();
            }
            for (int i = 0; i < list.size(); i++) {
                CompoundTag it = list.getCompoundOrEmpty(i);
                byte slot = it.getByteOr("Slot", (byte) -1);
                it.remove("Slot");
                ItemStack stack = ItemStack.CODEC.parse(ops, it).result().orElse(ItemStack.EMPTY);
                if (stack.isEmpty()) {
                    continue;
                }
                if (slot >= 0 && slot < inv.getContainerSize()) {
                    inv.setItem(slot, stack);
                    loaded++;
                } else if (slot == 100) {
                    player.setItemSlot(EquipmentSlot.FEET, stack);
                    loaded++;
                } else if (slot == 101) {
                    player.setItemSlot(EquipmentSlot.LEGS, stack);
                    loaded++;
                } else if (slot == 102) {
                    player.setItemSlot(EquipmentSlot.CHEST, stack);
                    loaded++;
                } else if (slot == 103) {
                    player.setItemSlot(EquipmentSlot.HEAD, stack);
                    loaded++;
                } else if (slot == -106) {
                    player.setItemSlot(EquipmentSlot.OFFHAND, stack);
                    loaded++;
                }
            }
            CompoundTag eq = tag.getCompoundOrEmpty("equipment");
            loaded += applyEquip(player, ops, eq, "head", EquipmentSlot.HEAD);
            loaded += applyEquip(player, ops, eq, "chest", EquipmentSlot.CHEST);
            loaded += applyEquip(player, ops, eq, "legs", EquipmentSlot.LEGS);
            loaded += applyEquip(player, ops, eq, "feet", EquipmentSlot.FEET);
            loaded += applyEquip(player, ops, eq, "offhand", EquipmentSlot.OFFHAND);
            if (enderChestLooksEmpty(player)) {
                ListTag ender = tag.getListOrEmpty("EnderItems");
                var enderInv = player.getEnderChestInventory();
                for (int i = 0; i < ender.size(); i++) {
                    CompoundTag it = ender.getCompoundOrEmpty(i);
                    byte slot = it.getByteOr("Slot", (byte) -1);
                    it.remove("Slot");
                    ItemStack stack = ItemStack.CODEC.parse(ops, it).result().orElse(ItemStack.EMPTY);
                    if (!stack.isEmpty() && slot >= 0 && slot < enderInv.getContainerSize()) {
                        enderInv.setItem(slot, stack);
                        loaded++;
                    }
                }
            }
            if (tag.contains("XpLevel")) {
                player.experienceLevel = tag.getIntOr("XpLevel", player.experienceLevel);
            }
            if (tag.contains("XpTotal")) {
                player.totalExperience = tag.getIntOr("XpTotal", player.totalExperience);
            }
            if (loaded > 0) {
                player.inventoryMenu.broadcastFullState();
                LOG.info("Imported Paper inventory for {} ({} stacks)", player.getGameProfile().name(), loaded);
            }
        } catch (Exception e) {
            LOG.warn("Paper inventory import failed for {}: {}", player.getGameProfile().name(), e.toString());
        }
    }

    private static int applyEquip(ServerPlayer player, com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> ops,
                                  CompoundTag eq, String key, EquipmentSlot slot) {
        CompoundTag it = eq.getCompoundOrEmpty(key);
        if (it.isEmpty()) {
            return 0;
        }
        ItemStack stack = ItemStack.CODEC.parse(ops, it).result().orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) {
            return 0;
        }
        player.setItemSlot(slot, stack);
        return 1;
    }

    private static boolean inventoryLooksEmpty(ServerPlayer player) {
        int n = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                n++;
            }
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            if (!player.getItemBySlot(slot).isEmpty()) {
                n++;
            }
        }
        return n <= 2;
    }

    private static boolean enderChestLooksEmpty(ServerPlayer player) {
        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) {
            if (!ender.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void flushPlayerData(ServerPlayer player) {
        try {
            Class<?> c = Class.forName(
                    "de.craftingstudiopro.playerDataSyncReloaded.fabric.PlayerDataSyncFabric");
            c.getMethod("flushPlayerNow", ServerPlayer.class).invoke(null, player);
        } catch (Throwable t) {
            LOG.warn("Could not flush PlayerDataSync before survival gate for {}: {}",
                    player.getGameProfile().name(), t.toString());
        }
    }

    private void discardDuplicatePlayers(ServerPlayer keep) {
        MinecraftServer srv = keep.level().getServer();
        if (srv == null) {
            return;
        }
        stripGhostPlayers(srv, keep.getUUID(), keep);
    }

    private static void stripGhostPlayers(MinecraftServer srv, UUID id, ServerPlayer keep) {
        if (srv == null || id == null) {
            return;
        }
        List<ServerPlayer> extras = new ArrayList<>();
        for (ServerLevel level : srv.getAllLevels()) {
            Entity byId = level.getEntity(id);
            if (byId instanceof ServerPlayer other && other != keep) {
                extras.add(other);
            }
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ServerPlayer other && other != keep && id.equals(other.getUUID())) {
                    extras.add(other);
                }
            }
        }
        ServerPlayer listed = srv.getPlayerList().getPlayer(id);
        if (listed != null && listed != keep) {
            extras.add(listed);
        }
        int n = 0;
        for (ServerPlayer other : extras) {
            if (other == keep || other.isRemoved()) {
                continue;
            }
            try {
                if (other.level() instanceof ServerLevel level) {
                    level.removePlayerImmediately(other, Entity.RemovalReason.DISCARDED);
                }
            } catch (Exception ignored) {
            }
            try {
                other.discard();
            } catch (Exception ignored) {
            }
            n++;
        }
        if (n > 0) {
            String name = keep != null ? keep.getGameProfile().name() : id.toString();
            LOG.warn("Removed {} leftover player cop{} for {}", n, n == 1 ? "y" : "ies", name);
        }
    }

    private static void sweepGhostPlayers(MinecraftServer srv) {
        var listed = srv.getPlayerList().getPlayers();
        for (ServerLevel level : srv.getAllLevels()) {
            List<ServerPlayer> ghosts = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ServerPlayer p && !listed.contains(p)) {
                    ghosts.add(p);
                }
            }
            for (ServerPlayer ghost : ghosts) {
                stripGhostPlayers(srv, ghost.getUUID(), srv.getPlayerList().getPlayer(ghost.getUUID()));
            }
        }
    }

    private void applySharedBed(ServerPlayer player) {
        JsonObject bed = resolveBed(player.getUUID());
        if (bed == null || !bed.has("x") || !bed.has("y") || !bed.has("z")) {
            return;
        }
        String serverName = bed.has("server") ? bed.get("server").getAsString() : "fabric";
        String dim = bed.has("world") ? bed.get("world").getAsString() : "overworld";
        double x = bed.get("x").getAsDouble() + 0.5;
        double y = bed.get("y").getAsDouble();
        double z = bed.get("z").getAsDouble() + 0.5;
        float yaw = bed.has("yaw") ? bed.get("yaw").getAsFloat() : player.getYRot();
        float pitch = bed.has("pitch") ? bed.get("pitch").getAsFloat() : player.getXRot();
        if ("survival".equalsIgnoreCase(serverName)) {
            sendToSurvival(player, dim, x, y, z, yaw, pitch, Vec3.ZERO);
            return;
        }
        ServerLevel level = levelFor(dim);
        if (level == null) {
            return;
        }
        player.teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, true);
        LOG.info("Shared bed: {} -> fabric {},{},{}", player.getGameProfile().name(),
                (int) x, (int) y, (int) z);
    }

    private JsonObject resolveBed(UUID uuid) {
        JsonObject bed = readJson(DIR.resolve("beds").resolve(uuid + ".json"));
        if (bed != null) {
            return bed;
        }
        if (pool == null) {
            return null;
        }
        try (Jedis j = pool.getResource()) {
            String raw = jedisGet(j, "skygate:bed:" + uuid);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private void sendToSurvival(ServerPlayer player, String dim, double x, double y, double z,
                                float yaw, float pitch, Vec3 vel) {
        UUID id = player.getUUID();
        if (!leavingForSurvival.add(id) && cooldown.containsKey(id)) {
            return;
        }
        cooldown.put(id, 200);
        JsonArray extras = takeMountsAndLeashes(player);
        JsonObject o = new JsonObject();
        o.addProperty("kind", "player");
        o.addProperty("uuid", player.getUUID().toString());
        o.addProperty("name", player.getGameProfile().name());
        o.addProperty("dim", dim);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("yaw", yaw);
        o.addProperty("pitch", pitch);
        o.addProperty("vx", vel.x);
        o.addProperty("vy", vel.y);
        o.addProperty("vz", vel.z);
        o.addProperty("gliding", player.isFallFlying());
        o.addProperty("created", System.currentTimeMillis());
        if (!extras.isEmpty()) {
            o.add("entities", extras);
        }
        writeJson(DIR.resolve("in-survival").resolve(player.getUUID() + ".json"), o);
        flushPlayerData(player);
        LOG.info("Flushed PlayerDataSync for {} before survival gate", player.getGameProfile().name());
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 50, 1, false, false));
        allowAndPublish(player.getUUID(), "survival");
        cooldown.put(player.getUUID(), 200);
        LOG.info("Gated {} back to survival", player.getGameProfile().name());
    }

    private JsonArray takeMountsAndLeashes(ServerPlayer player) {
        JsonArray extras = new JsonArray();
        Entity vehicle = player.getVehicle();
        if (vehicle != null && !(vehicle instanceof ServerPlayer)) {
            String snbt = snapshot(vehicle);
            if (snbt != null) extras.add(snbt);
            player.stopRiding();
            vehicle.discard();
        }
        for (Leashable leashable : Leashable.leashableLeashedTo(player)) {
            if (leashable instanceof Entity living) {
                String snbt = snapshot(living);
                if (snbt != null) extras.add(snbt);
                leashable.removeLeash();
                living.discard();
            }
        }
        return extras;
    }

    private String snapshot(Entity entity) {
        try {
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.level().registryAccess());
            if (!entity.save(out)) return null;
            CompoundTag nbt = out.buildResult();
            nbt.remove("UUID");
            return nbt.toString();
        } catch (Exception e) {
            LOG.debug("snapshot: {}", e.toString());
            return null;
        }
    }

    private void gatePearl(ThrownEnderpearl pearl) {
        Entity owner = pearl.getOwner();
        UUID id = owner != null ? owner.getUUID() : pearl.getUUID();
        Vec3 v = pearl.getDeltaMovement();
        JsonObject o = new JsonObject();
        o.addProperty("kind", "pearl");
        o.addProperty("owner", id.toString());
        o.addProperty("dim", dimOf(pearl.level()));
        o.addProperty("x", pearl.getX());
        o.addProperty("y", mirroredY(pearl.getY()));
        o.addProperty("z", pearl.getZ());
        o.addProperty("vx", v.x);
        o.addProperty("vy", -v.y);
        o.addProperty("vz", v.z);
        o.addProperty("created", System.currentTimeMillis());
        writeJson(DIR.resolve("in-survival").resolve("pearl-" + UUID.randomUUID() + ".json"), o);
        pearl.discard();
    }

    private void gateLooseEntity(Entity entity) {
        String snbt = snapshot(entity);
        if (snbt == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("kind", "entity");
        o.addProperty("snbt", snbt);
        o.addProperty("dim", dimOf(entity.level()));
        o.addProperty("x", entity.getX());
        o.addProperty("y", mirroredY(entity.getY()));
        o.addProperty("z", entity.getZ());
        o.addProperty("created", System.currentTimeMillis());
        writeJson(DIR.resolve("in-survival").resolve("ent-" + UUID.randomUUID() + ".json"), o);
        entity.discard();
    }

    private void consumeIncoming() {
        Path in = DIR.resolve("in-fabric");
        if (!Files.isDirectory(in) || server == null) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(in, "*.json")) {
            for (Path file : ds) {
                String name = file.getFileName().toString();
                if (!name.startsWith("pearl-") && !name.startsWith("ent-")) {
                    continue;
                }
                JsonObject o = readJson(file);
                Files.deleteIfExists(file);
                if (o == null) continue;
                String kind = o.has("kind") ? o.get("kind").getAsString() : "";
                if ("pearl".equals(kind)) spawnPearl(o);
                else if ("entity".equals(kind)) spawnEntity(o);
            }
        } catch (Exception e) {
            LOG.debug("consume: {}", e.toString());
        }
    }

    private void applyIncomingPlayer(ServerPlayer player) {
        Path file = DIR.resolve("in-fabric").resolve(player.getUUID() + ".json");
        JsonObject o = readJson(file);
        if (o == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
        String dim = o.has("dim") ? o.get("dim").getAsString() : "overworld";
        if ("end".equalsIgnoreCase(dim)) {
            sendToSurvival(player, "end",
                    o.has("x") ? o.get("x").getAsDouble() : 100.0,
                    o.has("y") ? o.get("y").getAsDouble() : 49.0,
                    o.has("z") ? o.get("z").getAsDouble() : 0.0,
                    o.has("yaw") ? o.get("yaw").getAsFloat() : player.getYRot(),
                    o.has("pitch") ? o.get("pitch").getAsFloat() : player.getXRot(),
                    Vec3.ZERO);
            return;
        }
        ServerLevel level = levelFor(dim);
        if (level == null) return;
        double x = o.get("x").getAsDouble();
        double y = landingY(o.get("y").getAsDouble());
        double z = o.get("z").getAsDouble();
        float yaw = o.has("yaw") ? o.get("yaw").getAsFloat() : player.getYRot();
        float pitch = o.has("pitch") ? o.get("pitch").getAsFloat() : player.getXRot();
        player.teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, false);
        if (o.has("stasis") && o.get("stasis").getAsBoolean()) {
            consumeStasisNear(player, level, x, y, z);
        }
        LOG.info("Applied handoff {} to {},{},{}", player.getGameProfile().name(), (int) x, (int) y, (int) z);
        if (o.has("vx")) {
            double vy = o.get("vy").getAsDouble();
            if (vy > 0) vy = 0;
            player.setDeltaMovement(o.get("vx").getAsDouble(), vy, o.get("vz").getAsDouble());
            player.hurtMarked = true;
        }
        cooldown.put(player.getUUID(), 200);
        if (o.has("gliding") && o.get("gliding").getAsBoolean()) {
            player.startFallFlying();
        }
        if (o.has("entities") && o.get("entities").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("entities");
            for (JsonElement el : arr) {
                spawnSnbt(el.getAsString(), level, x, y, z);
            }
        }
        cooldown.put(player.getUUID(), 100);
        pendingHandoffTries.remove(player.getUUID());
    }

    @SuppressWarnings("unchecked")
    private void spawnPearl(JsonObject o) {
        ServerLevel level = levelFor(o.has("dim") ? o.get("dim").getAsString() : "overworld");
        if (level == null) return;
        var holder = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse("minecraft:ender_pearl"));
        if (holder.isEmpty()) return;
        EntityType<ThrownEnderpearl> type = (EntityType<ThrownEnderpearl>) (EntityType<?>) holder.get().value();
        double x = o.get("x").getAsDouble();
        double requestedY = o.get("y").getAsDouble();
        double z = o.get("z").getAsDouble();
        double vx = o.get("vx").getAsDouble();
        double vy = o.get("vy").getAsDouble();
        double vz = o.get("vz").getAsDouble();
        double y = findPearlFallY(level, x, requestedY, z);
        level.getChunk(BlockPos.containing(x, y, z));
        ThrownEnderpearl pearl = new ThrownEnderpearl(type, level);
        pearl.setItem(new ItemStack(Items.ENDER_PEARL));
        pearl.snapTo(x, y, z);
        for (int i = 0; i < 2000; i++) {
            if (level.noCollision(pearl)) {
                break;
            }
            y -= 0.5;
            pearl.snapTo(x, y, z);
        }
        pearl.setDeltaMovement(vx, vy, vz);
        UUID owner = null;
        try {
            owner = UUID.fromString(o.get("owner").getAsString());
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p != null) {
                pearl.setOwner(p);
            }
        } catch (Exception ignored) {
        }
        if (!level.addFreshEntity(pearl)) {
            LOG.warn("Incoming pearl failed to spawn at {},{},{}", (int) x, (int) y, (int) z);
            return;
        }
        UUID id = pearl.getUUID();
        Vec3 spawnAt = new Vec3(pearl.getX(), pearl.getY(), pearl.getZ());
        trackedPearls.add(id);
        trackedPearlEntities.put(id, pearl);
        pearlSpawnPos.put(id, spawnAt);
        pearlLastPos.put(id, spawnAt);
        pearlLastDim.put(id, dimOf(level));
        pearlSpawnTick.put(id, server.getTickCount());
        if (owner != null) {
            pearlOwner.put(id, owner);
        }
        keepPearlChunk(level, id, pearl.getX(), pearl.getZ());
        LOG.info("Incoming pearl owner={} at {},{},{} (from y={}) vy={}",
                owner, (int) pearl.getX(), (int) pearl.getY(), (int) pearl.getZ(), (int) requestedY, vy);
    }

    private void keepPearlChunk(ServerLevel level, UUID id, double x, double z) {
        ChunkPos pos = new ChunkPos((int) Math.floor(x / 16.0), (int) Math.floor(z / 16.0));
        ChunkPos old = pearlTickets.get(id);
        if (pos.equals(old)) {
            return;
        }
        ServerChunkCache chunks = level.getChunkSource();
        chunks.addTicketWithRadius(TicketType.FORCED, pos, 2);
        if (old != null) {
            chunks.removeTicketWithRadius(TicketType.FORCED, old, 2);
        }
        pearlTickets.put(id, pos);
    }

    private void dropPearlChunk(ServerLevel level, UUID id) {
        ChunkPos old = pearlTickets.remove(id);
        if (old != null && level != null) {
            level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, old, 2);
        }
    }

    /** Gate lip is ~1318. Throws from 1300–1310 must spawn below that band and fall, not die on the walkway. */
    private static final int PEARL_CLEAR_BELOW = 20;

    /** Drop through seam-height solids so a throw from ~1310 falls into the sky instead of dying on the gate floor. */
    private double findPearlFallY(ServerLevel level, double x, double startY, double z) {
        double y = Math.min(startY, THRESHOLD - 0.25);
        double maxAccept = THRESHOLD - PEARL_CLEAR_BELOW;
        AABB box = new AABB(x - 0.15, y, z - 0.15, x + 0.15, y + 0.25, z + 0.15);
        int minY = level.getMinY() + 2;
        Double firstOpen = null;
        for (int i = 0; i < 2000 && y > minY; i++) {
            BlockPos feet = BlockPos.containing(x, y, z);
            level.getChunk(feet);
            boolean open = level.getBlockState(feet).isAir()
                    && level.getBlockState(feet.below()).isAir()
                    && level.getBlockState(feet.below(2)).isAir()
                    && level.noCollision(box);
            if (open) {
                if (firstOpen == null) {
                    firstOpen = y;
                }
                if (y <= maxAccept) {
                    return y;
                }
            }
            y -= 0.5;
            box = box.move(0.0, -0.5, 0.0);
        }
        return firstOpen != null ? Math.min(firstOpen, maxAccept) : y;
    }

    private Entity findEntity(UUID id) {
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private void tickTrackedPearls() {
        if (server == null || trackedPearls.isEmpty()) return;
        int now = server.getTickCount();
        Set<UUID> done = new HashSet<>();
        for (UUID id : trackedPearls) {
            ThrownEnderpearl held = trackedPearlEntities.get(id);
            Entity e = held != null && !held.isRemoved() ? held : findEntity(id);
            if (e instanceof ThrownEnderpearl pearl && !pearl.isRemoved()) {
                pearlSeen.add(id);
                trackedPearlEntities.put(id, pearl);
                pearlLastPos.put(id, new Vec3(pearl.getX(), pearl.getY(), pearl.getZ()));
                pearlLastDim.put(id, dimOf(pearl.level()));
                if (pearl.level() instanceof ServerLevel sl) {
                    keepPearlChunk(sl, id, pearl.getX(), pearl.getZ());
                }
                continue;
            }
            int spawned = pearlSpawnTick.getOrDefault(id, now);
            int age = now - spawned;
            if (age < 5) {
                continue;
            }
            if (!pearlSeen.contains(id)) {
                if (age < 40) {
                    continue;
                }
                LOG.warn("Incoming pearl {} never appeared after {} ticks; dropping", id, age);
                done.add(id);
                continue;
            }
            if (stasisKeepPearls.contains(id)) {
                done.add(id);
                UUID keepOwner = pearlOwner.get(id);
                Vec3 keepPos = pearlLastPos.get(id);
                String keepDim = pearlLastDim.getOrDefault(id, "overworld");
                if (keepOwner != null && server.getPlayerList().getPlayer(keepOwner) == null && keepPos != null) {
                    pullOwnerToFabric(keepOwner, keepDim, keepPos);
                }
                continue;
            }
            Vec3 pos = pearlLastPos.get(id);
            Vec3 spawnAt = pearlSpawnPos.get(id);
            if (pos == null) {
                done.add(id);
                continue;
            }
            double fallen = spawnAt == null ? 0 : spawnAt.y - pos.y;
            double moved = spawnAt == null ? 0 : spawnAt.distanceTo(pos);
            String dim = pearlLastDim.getOrDefault(id, "overworld");
            ServerLevel level = levelFor(dim);
            // Ender-pearl tickets expire in ~2s; that used to look like a hit at ~Y 1253
            // with no blocks. Blocks only exist inside build height (~320). Don't pull
            // unless the pearl actually collided in the world.
            if (fallen < 1.5 && moved < 2.0) {
                LOG.warn("Pearl vanished near spawn at {},{},{} (fallen={}); not pulling",
                        (int) pos.x, (int) pos.y, (int) pos.z, (int) fallen);
                done.add(id);
                continue;
            }
            if (level != null && level.isOutsideBuildHeight((int) Math.floor(pos.y))) {
                LOG.warn("Pearl vanished above/below blocks at y={} (maxY={}); not a hit",
                        (int) pos.y, level.getMaxY());
                done.add(id);
                continue;
            }
            done.add(id);
            UUID owner = pearlOwner.get(id);
            if (owner == null) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p != null && level != null) {
                LOG.info("Pearl hit owner={} at {},{},{} fallen={} pull=local",
                        owner, (int) pos.x, (int) pos.y, (int) pos.z, (int) fallen);
                p.teleportTo(level, pos.x, landingY(pos.y), pos.z, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
                continue;
            }
            // Pearls still cross the gate as entities. Do not Velocity-switch
            // the owner: a pearl above Y 1320 used to yank them onto this
            // server even if they were 300k blocks away on survival.
            LOG.info("Pearl hit owner={} at {},{},{} fallen={} pull=none (owner not on fabric)",
                    owner, (int) pos.x, (int) pos.y, (int) pos.z, (int) fallen);
        }
        for (UUID id : done) {
            ServerLevel sl = levelFor(pearlLastDim.getOrDefault(id, "overworld"));
            dropPearlChunk(sl, id);
        }
        trackedPearls.removeAll(done);
        trackedPearlEntities.keySet().removeAll(done);
        pearlSpawnPos.keySet().removeAll(done);
        pearlLastPos.keySet().removeAll(done);
        pearlLastDim.keySet().removeAll(done);
        pearlOwner.keySet().removeAll(done);
        pearlSeen.removeAll(done);
        pearlSpawnTick.keySet().removeAll(done);
        stasisKeepPearls.removeAll(done);
    }

    private void spawnEntity(JsonObject o) {
        if (!o.has("snbt")) return;
        ServerLevel level = levelFor(o.has("dim") ? o.get("dim").getAsString() : "overworld");
        if (level == null) return;
        spawnSnbt(o.get("snbt").getAsString(), level, o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
    }

    private void spawnSnbt(String snbt, ServerLevel level, double x, double y, double z) {
        try {
            CompoundTag nbt = TagParser.parseCompoundFully(snbt);
            nbt.remove("UUID");
            String id = nbt.getStringOr("id", "");
            if (id.isEmpty()) return;
            var holder = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse(id));
            if (holder.isEmpty()) return;
            Entity e = EntityType.loadEntityRecursive(holder.get().value(), nbt, level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (e == null) return;
            e.snapTo(x, y, z);
            level.addFreshEntity(e);
        } catch (Exception ex) {
            LOG.debug("spawn snbt: {}", ex.toString());
        }
    }

    private ServerLevel levelFor(String dim) {
        if (server == null) return null;
        if ("nether".equals(dim)) return server.getLevel(Level.NETHER);
        if ("end".equals(dim)) return server.getLevel(Level.END);
        return server.getLevel(Level.OVERWORLD);
    }

    private long ignorePaperClockUntilMs;

    private void applyClock(MinecraftServer srv) {
        JsonObject o = readJson(DIR.resolve("clock").resolve("paper.json"));
        if (o == null) return;
        try {
            long paperTime = o.get("fullTime").getAsLong();
            ServerClockManager clocks = srv.clockManager();
            var holder = srv.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).get(WorldClocks.OVERWORLD);
            boolean anyoneSleeping = false;
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                if (p.isSleeping()) {
                    anyoneSleeping = true;
                    break;
                }
            }
            if (holder.isPresent()) {
                long fabricTime = clocks.getTotalTicks(holder.get());
                long paperAhead = paperTime - fabricTime;
                if (anyoneSleeping || System.currentTimeMillis() < ignorePaperClockUntilMs) {
                    // CombinedSleep owns night skip; do not snap back to Paper night.
                    clocks.setPaused(holder.get(), false);
                } else if (paperAhead > 40L) {
                    // Paper skipped or we lagged behind. Catch up. Never rewind a Fabric skip.
                    clocks.setTotalTicks(holder.get(), paperTime);
                    clocks.setPaused(holder.get(), false);
                    combinedSleep.wakeLocalSleepers(srv);
                } else {
                    clocks.setPaused(holder.get(), false);
                }
            }
            ServerLevel ow = srv.getLevel(Level.OVERWORLD);
            if (ow != null) {
                WorldBorder b = ow.getWorldBorder();
                b.setCenter(o.get("borderX").getAsDouble(), o.get("borderZ").getAsDouble());
                b.setSize(o.get("borderSize").getAsDouble());
            }
        } catch (Exception ignored) {
        }
    }

    void requestPaperClockCatchUp(long fullTime) {
        ignorePaperClockUntilMs = System.currentTimeMillis() + 15_000L;
        JsonObject o = new JsonObject();
        o.addProperty("kind", "skip-night");
        o.addProperty("fullTime", fullTime);
        o.addProperty("clearWeather", true);
        o.addProperty("created", System.currentTimeMillis());
        writeJson(DIR.resolve("clock").resolve("fabric-skip.json"), o);
        LOG.info("Fabric sleep skipped night, asking Paper to catch up fullTime={}", fullTime);
    }

    private void retryPendingHandoffs(MinecraftServer srv) {
        if (pendingHandoffTries.isEmpty()) return;
        Set<UUID> done = new HashSet<>();
        for (Map.Entry<UUID, Integer> e : pendingHandoffTries.entrySet()) {
            UUID id = e.getKey();
            int left = e.getValue() - 1;
            ServerPlayer p = srv.getPlayerList().getPlayer(id);
            if (p != null) {
                Path file = DIR.resolve("in-fabric").resolve(id + ".json");
                if (Files.isRegularFile(file)) {
                    applyIncomingPlayer(p);
                    done.add(id);
                    continue;
                }
            }
            if (left <= 0 || p == null) {
                done.add(id);
            } else {
                pendingHandoffTries.put(id, left);
            }
        }
        done.forEach(pendingHandoffTries::remove);
    }

    Pool<Jedis> redisPool() {
        return pool;
    }

    static Object jedisCall(Jedis j, String name, Class<?>[] types, Object... args) throws Exception {
        return j.getClass().getMethod(name, types).invoke(j, args);
    }

    static void jedisSet(Jedis j, String key, String value) throws Exception {
        jedisCall(j, "set", new Class<?>[]{String.class, String.class}, key, value);
    }

    static void jedisSetex(Jedis j, String key, int seconds, String value) throws Exception {
        try {
            jedisCall(j, "setex", new Class<?>[]{String.class, long.class, String.class}, key, (long) seconds, value);
        } catch (Throwable t) {
            jedisCall(j, "setex", new Class<?>[]{String.class, int.class, String.class}, key, seconds, value);
        }
    }

    static void jedisPublish(Jedis j, String channel, String message) throws Exception {
        try {
            jedisCall(j, "publish", new Class<?>[]{String.class, String.class}, channel, message);
        } catch (Throwable t) {
            // Jedis 3 returns Long; 4 returns long. The invoke above should work for both;
            // keep a second path in case the runtime method is boxed-only under a wrapper.
            j.getClass().getMethod("publish", String.class, String.class).invoke(j, channel, message);
        }
    }

    static String jedisGet(Jedis j, String key) throws Exception {
        Object r = jedisCall(j, "get", new Class<?>[]{String.class}, key);
        return r == null ? null : String.valueOf(r);
    }

    static void redisDel(Jedis j, String key) {
        try {
            jedisCall(j, "del", new Class<?>[]{String.class}, key);
        } catch (Throwable t) {
            try {
                jedisCall(j, "del", new Class<?>[]{String[].class}, (Object) new String[]{key});
            } catch (Throwable t2) {
                try {
                    jedisCall(j, "unlink", new Class<?>[]{String.class}, key);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void markMigrate(UUID uuid) {
        if (pool == null || uuid == null) return;
        try (Jedis j = pool.getResource()) {
            jedisSet(j, "skygate:migrate:" + uuid, "fabric");
        } catch (Throwable e) {
            LOG.warn("redis migrate: {}", e.toString());
        }
    }

    private void allowAndPublish(UUID uuid, String dest) {
        if (pool == null) return;
        try (Jedis j = pool.getResource()) {
            jedisSetex(j, "skygate:allow:" + uuid, 120, dest);
            if ("fabric".equals(dest)) {
                jedisSet(j, "skygate:migrate:" + uuid, "fabric");
            } else {
                redisDel(j, "skygate:migrate:" + uuid);
            }
            jedisPublish(j, "skygate:connect", uuid + " " + dest);
        } catch (Throwable e) {
            LOG.warn("redis: {}", e.toString());
        }
    }

    private void tickCouncilSnaps(MinecraftServer srv) {
        councilTicks++;
        if (councilTicks < 20 * 60 * 5) return;
        councilTicks = 0;
        reloadCouncilStatNames();
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            flushCouncilDelta(p);
            councilBaseline.put(p.getUUID(), captureCouncilStats(p));
        }
    }

    private void flushCouncilDelta(ServerPlayer player) {
        if (player == null) return;
        Map<String, Long> now = captureCouncilStats(player);
        Map<String, Long> base = councilBaseline.remove(player.getUUID());
        if (base == null) {
            return;
        }
        JsonObject deltas = new JsonObject();
        boolean any = false;
        for (String key : now.keySet()) {
            long d = Math.max(0L, now.getOrDefault(key, 0L) - base.getOrDefault(key, 0L));
            if (d > 0) {
                deltas.addProperty(key, d);
                any = true;
            }
        }
        if (!any) return;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", player.getUUID().toString());
        o.addProperty("name", player.getGameProfile().name());
        o.addProperty("timestamp", System.currentTimeMillis());
        o.add("deltas", deltas);
        writeJson(DIR.resolve("council-snaps").resolve(player.getUUID() + "-" + System.currentTimeMillis() + ".json"), o);
    }

    private void reloadCouncilStatNames() {
        Path file = DIR.resolve("council-tracked-stats.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.has("stats") || !o.get("stats").isJsonArray()) {
                return;
            }
            List<String> names = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("stats")) {
                String n = el.getAsString().toUpperCase(Locale.ROOT);
                if (!n.isBlank() && !names.contains(n)) {
                    names.add(n);
                }
            }
            if (!names.contains("PLAY_ONE_MINUTE")) {
                names.add(0, "PLAY_ONE_MINUTE");
            }
            if (!names.isEmpty()) {
                councilStatNames = List.copyOf(names);
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, Long> captureCouncilStats(ServerPlayer player) {
        Map<String, Long> out = new HashMap<>();
        for (String name : councilStatNames) {
            out.put(name, (long) customStat(player, vanillaCustomId(name)));
        }
        return out;
    }

    private static Identifier vanillaCustomId(String bukkitName) {
        if ("PLAY_ONE_MINUTE".equals(bukkitName)) {
            return Stats.PLAY_TIME;
        }
        return Identifier.parse("minecraft:" + bukkitName.toLowerCase(Locale.ROOT));
    }

    private static int customStat(ServerPlayer player, Identifier id) {
        try {
            Object handler = player.getClass().getMethod("getStatHandler").invoke(player);
            return invokeGetStat(handler, id);
        } catch (Throwable ignored) {
        }
        try {
            Object handler = player.getClass().getMethod("getStats").invoke(player);
            return invokeGetStat(handler, id);
        } catch (Throwable ignored) {
        }
        try {
            var f = player.getClass().getDeclaredField("statHandler");
            f.setAccessible(true);
            return invokeGetStat(f.get(player), id);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int invokeGetStat(Object handler, Identifier id) throws Exception {
        for (var m : handler.getClass().getMethods()) {
            if (!"getStat".equals(m.getName()) || m.getParameterCount() != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[1].isAssignableFrom(id.getClass()) || p[1] == Object.class) {
                Object v = m.invoke(handler, Stats.CUSTOM, id);
                if (v instanceof Number n) return n.intValue();
            }
        }
        return 0;
    }

    public static void onPearlHit(ThrownEnderpearl pearl) {
        SkyWorldsFabric inst = INSTANCE;
        if (inst == null || pearl == null) {
            return;
        }
        inst.handleStasisHit(pearl);
    }

    private void handleStasisHit(ThrownEnderpearl pearl) {
        if (!stasisKeepPearls.contains(pearl.getUUID())) {
            return;
        }
        UUID owner = pearlOwner.get(pearl.getUUID());
        if (owner == null) {
            return;
        }
        if (server != null && server.getPlayerList().getPlayer(owner) != null) {
            return;
        }
        pullOwnerToFabric(owner, dimOf(pearl.level()), pearl.position());
    }

    private List<PearlSnap> snapshotPearls(ServerPlayer player) {
        List<PearlSnap> out = new ArrayList<>();
        if (player == null) {
            return out;
        }
        UUID id = player.getUUID();
        for (ThrownEnderpearl pearl : pearlsOf(player)) {
            if (pearl.isRemoved() || stasisKeepPearls.contains(pearl.getUUID())) {
                continue;
            }
            Vec3 v = pearl.getDeltaMovement();
            out.add(new PearlSnap(dimOf(pearl.level()), pearl.getX(), pearl.getY(), pearl.getZ(),
                    v.x, v.y, v.z, id));
        }
        return out;
    }

    private List<ThrownEnderpearl> pearlsOf(ServerPlayer player) {
        List<ThrownEnderpearl> out = new ArrayList<>();
        if (player == null || server == null) {
            return out;
        }
        UUID id = player.getUUID();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ThrownEnderpearl pearl) {
                    Entity owner = pearl.getOwner();
                    if (owner != null && id.equals(owner.getUUID())) {
                        out.add(pearl);
                    }
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void spawnStasisKeeps(List<PearlSnap> snaps) {
        if (snaps == null || snaps.isEmpty() || server == null) {
            return;
        }
        var holder = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse("minecraft:ender_pearl"));
        if (holder.isEmpty()) {
            return;
        }
        EntityType<ThrownEnderpearl> type = (EntityType<ThrownEnderpearl>) (EntityType<?>) holder.get().value();
        int spawned = 0;
        for (PearlSnap snap : snaps) {
            ServerLevel level = levelFor(snap.dim);
            if (level == null) {
                continue;
            }
            ThrownEnderpearl pearl = new ThrownEnderpearl(type, level);
            pearl.setItem(new ItemStack(Items.ENDER_PEARL));
            pearl.snapTo(snap.x, snap.y, snap.z);
            pearl.setDeltaMovement(snap.vx, snap.vy, snap.vz);
            if (!level.addFreshEntity(pearl)) {
                continue;
            }
            UUID pid = pearl.getUUID();
            Vec3 at = new Vec3(pearl.getX(), pearl.getY(), pearl.getZ());
            trackedPearls.add(pid);
            trackedPearlEntities.put(pid, pearl);
            pearlSpawnPos.put(pid, at);
            pearlLastPos.put(pid, at);
            pearlLastDim.put(pid, snap.dim);
            pearlSpawnTick.put(pid, server.getTickCount());
            pearlOwner.put(pid, snap.owner);
            stasisKeepPearls.add(pid);
            keepPearlChunk(level, pid, pearl.getX(), pearl.getZ());
            spawned++;
        }
        if (spawned > 0) {
            LOG.info("Stasis keepalive {} pearl(s)", spawned);
        }
    }

    private void reconcileStasis(ServerPlayer player) {
        if (player == null || server == null) {
            return;
        }
        UUID id = player.getUUID();
        List<ThrownEnderpearl> kept = new ArrayList<>();
        List<ThrownEnderpearl> vanilla = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof ThrownEnderpearl pearl) || pearl.isRemoved()) {
                    continue;
                }
                UUID owner = pearlOwner.get(pearl.getUUID());
                Entity live = pearl.getOwner();
                boolean ours = id.equals(owner) || (live != null && id.equals(live.getUUID()));
                if (!ours) {
                    continue;
                }
                if (stasisKeepPearls.contains(pearl.getUUID())) {
                    kept.add(pearl);
                } else {
                    vanilla.add(pearl);
                }
            }
        }
        if (!vanilla.isEmpty()) {
            for (ThrownEnderpearl pearl : kept) {
                dropPearlChunk(levelOf(pearl), pearl.getUUID());
                stasisKeepPearls.remove(pearl.getUUID());
                pearl.discard();
            }
            return;
        }
        for (ThrownEnderpearl pearl : kept) {
            pearl.setOwner(player);
            stasisKeepPearls.remove(pearl.getUUID());
            dropPearlChunk(levelOf(pearl), pearl.getUUID());
        }
        if (!kept.isEmpty()) {
            LOG.info("Stasis keepalive rebound {} pearl(s) for {}", kept.size(), player.getGameProfile().name());
        }
    }

    private void consumeStasisNear(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        if (player == null || level == null) {
            return;
        }
        UUID id = player.getUUID();
        AABB box = new AABB(x - 3, y - 3, z - 3, x + 3, y + 3, z + 3);
        for (ThrownEnderpearl pearl : level.getEntitiesOfClass(ThrownEnderpearl.class, box)) {
            UUID owner = pearlOwner.get(pearl.getUUID());
            Entity live = pearl.getOwner();
            if (id.equals(owner) || (live != null && id.equals(live.getUUID()))) {
                dropPearlChunk(level, pearl.getUUID());
                stasisKeepPearls.remove(pearl.getUUID());
                pearl.discard();
            }
        }
    }

    private void pullOwnerToFabric(UUID owner, String dim, Vec3 pos) {
        if (owner == null || pos == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("kind", "player");
        o.addProperty("uuid", owner.toString());
        o.addProperty("dim", dim == null ? "overworld" : dim);
        o.addProperty("x", pos.x);
        o.addProperty("y", pos.y);
        o.addProperty("z", pos.z);
        o.addProperty("yaw", 0);
        o.addProperty("pitch", 0);
        o.addProperty("stasis", true);
        o.addProperty("created", System.currentTimeMillis());
        writeJson(DIR.resolve("in-fabric").resolve(owner + ".json"), o);
        allowAndPublish(owner, "fabric");
        LOG.info("Stasis pull {} -> fabric", owner);
    }

    private static ServerLevel levelOf(ThrownEnderpearl pearl) {
        return pearl.level() instanceof ServerLevel sl ? sl : null;
    }

    private record PearlSnap(String dim, double x, double y, double z,
                             double vx, double vy, double vz, UUID owner) {}

    private void writeJson(Path dest, JsonObject o) {
        try {
            Files.createDirectories(dest.getParent());
            Path tmp = dest.resolveSibling("." + dest.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warn("write {}: {}", dest.getFileName(), e.getMessage());
        }
    }

    private static final Path PAPER_ADV = Path.of("/mnt/pool/survival/world/players/advancements");
    private static final Path FABRIC_ADV = Path.of("/mnt/pool/fabric/world/players/advancements");

    /**
     * First Fabric join has a blank advancement tree. Copy completed Paper
     * advancements (recipes included) so sky-first logins keep survival progress.
     */
    private void importPaperAdvancements(ServerPlayer player) {
        if (player == null) return;
        Path paper = PAPER_ADV.resolve(player.getUUID() + ".json");
        Path fabric = FABRIC_ADV.resolve(player.getUUID() + ".json");
        JsonObject src = readJson(paper);
        if (src == null) return;
        JsonObject dst = readJson(fabric);
        if (dst == null) {
            dst = new JsonObject();
        }
        int added = 0;
        for (var e : src.entrySet()) {
            String key = e.getKey();
            if ("DataVersion".equals(key) || !e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject paperAdv = e.getValue().getAsJsonObject();
            if (!paperAdv.has("done") || !paperAdv.get("done").getAsBoolean()) {
                continue;
            }
            if (dst.has(key) && dst.get(key).isJsonObject()) {
                JsonObject have = dst.getAsJsonObject(key);
                if (have.has("done") && have.get("done").getAsBoolean()) {
                    continue;
                }
            }
            dst.add(key, paperAdv.deepCopy());
            added++;
        }
        if (added == 0) {
            return;
        }
        if (src.has("DataVersion") && !dst.has("DataVersion")) {
            dst.add("DataVersion", src.get("DataVersion"));
        }
        writeJson(fabric, dst);
        try {
            player.getAdvancements().reload(server.getAdvancements());
        } catch (Exception e) {
            LOG.warn("reload advancements {}: {}", player.getGameProfile().name(), e.toString());
        }
        LOG.info("Imported {} Paper advancements for {}", added, player.getGameProfile().name());
    }

    private JsonObject readJson(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }
}
