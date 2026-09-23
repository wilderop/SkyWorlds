package com.example.skyworlds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Same-host JSON drop for Paper ↔ Fabric. Velocity allow tokens live in Redis.
 */
public final class HandoffStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path dir;
    private final Logger log;

    public HandoffStore(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
        try {
            Files.createDirectories(dir.resolve("in-fabric"));
            Files.createDirectories(dir.resolve("in-survival"));
            Files.createDirectories(dir.resolve("beds"));
            Files.createDirectories(dir.resolve("clock"));
        } catch (IOException e) {
            log.warning("Could not create skygate dirs: " + e.getMessage());
        }
    }

    public Path dir() {
        return dir;
    }

    public void writePlayerToFabric(Player player, Location dest, String dim, List<String> extras) {
        writePlayer("in-fabric", player, dest, dim, extras);
    }

    public void writePlayerToSurvival(UUID uuid, String name, Location dest, String dim, List<String> extras) {
        JsonObject o = basePlayer(uuid, name, dest, dim);
        if (extras != null && !extras.isEmpty()) {
            JsonArray arr = new JsonArray();
            extras.forEach(arr::add);
            o.add("entities", arr);
        }
        atomicWrite(dir.resolve("in-survival").resolve(uuid + ".json"), o);
    }

    public void writePlayer(String folder, Player player, Location dest, String dim, List<String> extras) {
        JsonObject o = basePlayer(player.getUniqueId(), player.getName(), dest, dim);
        Vector v = player.getVelocity();
        o.addProperty("vx", v.getX());
        o.addProperty("vy", v.getY());
        o.addProperty("vz", v.getZ());
        o.addProperty("gliding", player.isGliding());
        o.addProperty("sneaking", player.isSneaking());
        o.addProperty("sprinting", player.isSprinting());
        if (extras != null && !extras.isEmpty()) {
            JsonArray arr = new JsonArray();
            extras.forEach(arr::add);
            o.add("entities", arr);
        }
        atomicWrite(dir.resolve(folder).resolve(player.getUniqueId() + ".json"), o);
    }

    public void writePearl(String folder, UUID owner, Location loc, Vector vel, String dim) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "pearl");
        o.addProperty("owner", owner.toString());
        o.addProperty("dim", dim);
        o.addProperty("x", loc.getX());
        o.addProperty("y", loc.getY());
        o.addProperty("z", loc.getZ());
        o.addProperty("vx", vel.getX());
        o.addProperty("vy", vel.getY());
        o.addProperty("vz", vel.getZ());
        o.addProperty("created", System.currentTimeMillis());
        atomicWrite(dir.resolve(folder).resolve("pearl-" + UUID.randomUUID() + ".json"), o);
    }

    public void writeEntity(String folder, String snbt, Location loc, String dim) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "entity");
        o.addProperty("snbt", snbt);
        o.addProperty("dim", dim);
        o.addProperty("x", loc.getX());
        o.addProperty("y", loc.getY());
        o.addProperty("z", loc.getZ());
        o.addProperty("created", System.currentTimeMillis());
        atomicWrite(dir.resolve(folder).resolve("ent-" + UUID.randomUUID() + ".json"), o);
    }

    public void writeBed(UUID uuid, String server, String world, Location loc) {
        JsonObject o = new JsonObject();
        o.addProperty("server", server);
        o.addProperty("world", world);
        o.addProperty("x", loc.getX());
        o.addProperty("y", loc.getY());
        o.addProperty("z", loc.getZ());
        o.addProperty("yaw", loc.getYaw());
        o.addProperty("pitch", loc.getPitch());
        atomicWrite(dir.resolve("beds").resolve(uuid + ".json"), o);
    }

    public JsonObject readBed(UUID uuid) {
        return read(dir.resolve("beds").resolve(uuid + ".json"));
    }

    public void writeClock(long fullTime, boolean storm, boolean thunder, int weatherDur, int thunderDur,
                           double borderSize, double borderX, double borderZ) {
        JsonObject o = new JsonObject();
        o.addProperty("fullTime", fullTime);
        o.addProperty("storm", storm);
        o.addProperty("thunder", thunder);
        o.addProperty("weatherDur", weatherDur);
        o.addProperty("thunderDur", thunderDur);
        o.addProperty("borderSize", borderSize);
        o.addProperty("borderX", borderX);
        o.addProperty("borderZ", borderZ);
        o.addProperty("updated", System.currentTimeMillis());
        atomicWrite(dir.resolve("clock").resolve("paper.json"), o);
    }

    public void writeSleepCensus(String side, int online, int sleeping) {
        JsonObject o = new JsonObject();
        o.addProperty("online", online);
        o.addProperty("sleeping", sleeping);
        o.addProperty("t", System.currentTimeMillis());
        atomicWrite(dir.resolve("sleep").resolve(side + ".json"), o);
    }

    public int[] readSleepCensus(String side, long maxAgeMs) {
        JsonObject o = read(dir.resolve("sleep").resolve(side + ".json"));
        if (o == null || !o.has("t")) {
            return new int[]{0, 0};
        }
        try {
            if (System.currentTimeMillis() - o.get("t").getAsLong() > maxAgeMs) {
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

    public JsonObject takeClockSkip() {
        Path file = dir.resolve("clock").resolve("fabric-skip.json");
        JsonObject o = read(file);
        if (o != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
        return o;
    }

    public void consumeIncoming(String folder, Consumer<JsonObject> consumer) {
        Path p = dir.resolve(folder);
        if (!Files.isDirectory(p)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.json")) {
            for (Path file : ds) {
                String name = file.getFileName().toString();
                // Player payloads are named <uuid>.json and must wait for join.
                if (!name.startsWith("pearl-") && !name.startsWith("ent-")) {
                    continue;
                }
                JsonObject o = read(file);
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
                if (o != null) {
                    consumer.accept(o);
                }
            }
        } catch (IOException e) {
            log.fine("handoff scan: " + e.getMessage());
        }
    }

    public JsonObject peekPlayer(UUID uuid, String folder) {
        return read(dir.resolve(folder).resolve(uuid + ".json"));
    }

    public JsonObject takePlayer(UUID uuid, String folder) {
        Path file = dir.resolve(folder).resolve(uuid + ".json");
        JsonObject o = read(file);
        if (o != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
        return o;
    }

    public void writePlayerCoords(String folder, UUID uuid, String name, String dim,
                                  double x, double y, double z, float yaw, float pitch) {
        writePlayerCoords(folder, uuid, name, dim, x, y, z, yaw, pitch, false);
    }

    public void writePlayerCoords(String folder, UUID uuid, String name, String dim,
                                  double x, double y, double z, float yaw, float pitch, boolean stasis) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "player");
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name == null ? "" : name);
        o.addProperty("dim", dim);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("yaw", yaw);
        o.addProperty("pitch", pitch);
        o.addProperty("created", System.currentTimeMillis());
        if (stasis) {
            o.addProperty("stasis", true);
        }
        atomicWrite(dir.resolve(folder).resolve(uuid + ".json"), o);
    }

    public static String snapshot(Entity entity) {
        try {
            return entity.createSnapshot().getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> takeMountsAndLeashes(Player player) {
        List<String> extras = new ArrayList<>();
        Entity vehicle = player.getVehicle();
        if (vehicle != null && !(vehicle instanceof Player)) {
            String snbt = snapshot(vehicle);
            if (snbt != null) {
                extras.add(snbt);
            }
            player.leaveVehicle();
            vehicle.remove();
        }
        for (Entity nearby : player.getNearbyEntities(16, 16, 16)) {
            if (nearby instanceof LivingEntity living && living.isLeashed()) {
                try {
                    if (player.equals(living.getLeashHolder())) {
                        String snbt = snapshot(living);
                        if (snbt != null) {
                            extras.add(snbt);
                        }
                        living.setLeashHolder(null);
                        living.remove();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return extras;
    }

    private JsonObject basePlayer(UUID uuid, String name, Location dest, String dim) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "player");
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name == null ? "" : name);
        o.addProperty("dim", dim);
        o.addProperty("x", dest.getX());
        o.addProperty("y", dest.getY());
        o.addProperty("z", dest.getZ());
        o.addProperty("yaw", dest.getYaw());
        o.addProperty("pitch", dest.getPitch());
        o.addProperty("created", System.currentTimeMillis());
        return o;
    }

    private JsonObject read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            return GSON.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void atomicWrite(Path dest, JsonObject o) {
        try {
            Files.createDirectories(dest.getParent());
            Path tmp = dest.resolveSibling("." + dest.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warning("handoff write failed " + dest.getFileName() + ": " + e.getMessage());
        }
    }
}
