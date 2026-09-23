package com.example.skyworlds;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Players who logged out in Paper's old {@code mirror_*} sky worlds never flew
 * the 3.0 gate, so they have no migrate key. On join Paper remaps the vanished
 * dimension onto the ground overworld at the same XZ (inside the mountain).
 * Queue every leftover sky logout to Fabric at the saved island coords.
 */
final class LegacyMirrorResume {
    static final int MIGRATE_TTL = 0; // unused; migrate keys persist with no TTL
    private static final String MARKER_DIR = "legacy-sky";

    private LegacyMirrorResume() {}

    static int scanAndQueue(JavaPlugin plugin, HandoffStore store, RedisBus redis, World overworld) {
        if (overworld == null) {
            return 0;
        }
        Path dir = overworld.getWorldFolder().toPath().resolve("players").resolve("data");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        Path markers = store.dir().resolve(MARKER_DIR);
        try {
            Files.createDirectories(markers);
        } catch (IOException e) {
            plugin.getLogger().warning("legacy sky marker dir: " + e.getMessage());
        }
        int queued = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.dat")) {
            for (Path file : ds) {
                if (resumeOne(plugin, store, redis, markers, file)) {
                    queued++;
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("legacy sky scan: " + e.getMessage());
        }
        return queued;
    }

    static boolean isLegacyMirrorWorld(World world) {
        if (world == null) {
            return false;
        }
        String name = world.getName().toLowerCase();
        return name.contains("mirror_overworld") || name.contains("mirror_nether");
    }

    static void resumeOnline(Player player, HandoffStore store, RedisBus redis) {
        UUID uuid = player.getUniqueId();
        String dim = player.getWorld() != null
                && player.getWorld().getName().toLowerCase().contains("nether")
                ? "nether" : "overworld";
        if (store.peekPlayer(uuid, "in-fabric") == null) {
            store.writePlayerCoords("in-fabric", uuid, player.getName(), dim,
                    player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ(),
                    player.getLocation().getYaw(), player.getLocation().getPitch());
        }
        redis.allow(uuid, "fabric", 120);
        redis.markMigrate(uuid, MIGRATE_TTL);
        redis.publishConnect(uuid, "fabric");
    }

    private static boolean resumeOne(JavaPlugin plugin, HandoffStore store, RedisBus redis,
                                     Path markers, Path file) {
        String stem = file.getFileName().toString();
        if (!stem.endsWith(".dat")) {
            return false;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(stem.substring(0, stem.length() - 4));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (plugin.getServer().getPlayer(uuid) != null) {
            return false;
        }
        byte[] raw;
        try {
            raw = readPossiblyGzip(file);
        } catch (IOException e) {
            return false;
        }
        if (!containsAscii(raw, "mirror_overworld") && !containsAscii(raw, "mirror_nether")) {
            return false;
        }
        Snapshot snap;
        try {
            snap = parse(raw);
        } catch (Exception e) {
            plugin.getLogger().warning("legacy sky parse " + uuid + ": " + e.getMessage());
            return false;
        }
        if (snap == null || !isMirrorDimension(snap.dimension)) {
            return false;
        }
        Path marker = markers.resolve(uuid + ".done");
        boolean already = Files.isRegularFile(marker);
        if (!already && store.peekPlayer(uuid, "in-fabric") == null) {
            store.writePlayerCoords("in-fabric", uuid, snap.name, snap.fabricDim(),
                    snap.x, snap.y, snap.z, snap.yaw, snap.pitch);
        }
        redis.markMigrate(uuid, MIGRATE_TTL);
        if (!already) {
            try {
                Files.writeString(marker, snap.dimension + " " + snap.fabricDim()
                        + " " + (int) snap.x + "," + (int) snap.y + "," + (int) snap.z + "\n");
            } catch (IOException ignored) {
            }
            plugin.getLogger().info("Queued leftover sky logout " + (snap.name.isEmpty() ? uuid : snap.name)
                    + " to Fabric at " + (int) snap.x + "," + (int) snap.y + "," + (int) snap.z);
            return true;
        }
        return false;
    }

    private static boolean isMirrorDimension(String dimension) {
        if (dimension == null) {
            return false;
        }
        String d = dimension.toLowerCase();
        return d.contains("mirror_overworld") || d.contains("mirror_nether");
    }

    private static byte[] readPossiblyGzip(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length >= 2 && (all[0] & 0xff) == 0x1f && (all[1] & 0xff) == 0x8b) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(all))) {
                return in.readAllBytes();
            }
        }
        return all;
    }

    private static boolean containsAscii(byte[] raw, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + n.length <= raw.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (raw[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static Snapshot parse(byte[] raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte type = in.readByte();
            if (type != 10) {
                throw new IOException("root is not a compound");
            }
            in.readUTF();
            return readSnapshotCompound(in);
        }
    }

    private static Snapshot readSnapshotCompound(DataInputStream in) throws IOException {
        String dimension = "";
        String name = "";
        double x = 0, y = 0, z = 0;
        float yaw = 0, pitch = 0;
        boolean havePos = false;
        while (true) {
            byte type = in.readByte();
            if (type == 0) {
                break;
            }
            String key = in.readUTF();
            if (type == 8 && "Dimension".equals(key)) {
                dimension = in.readUTF();
            } else if (type == 9 && "Pos".equals(key)) {
                double[] pos = readDoubleList(in);
                if (pos.length >= 3) {
                    x = pos[0];
                    y = pos[1];
                    z = pos[2];
                    havePos = true;
                }
            } else if (type == 9 && "Rotation".equals(key)) {
                float[] rot = readFloatList(in);
                if (rot.length >= 2) {
                    yaw = rot[0];
                    pitch = rot[1];
                }
            } else if (type == 10 && "bukkit".equals(key)) {
                name = readLastKnownName(in);
            } else {
                skip(in, type);
            }
        }
        if (!havePos) {
            throw new IOException("no Pos tag");
        }
        return new Snapshot(dimension, x, y, z, yaw, pitch, name == null ? "" : name);
    }

    private static String readLastKnownName(DataInputStream in) throws IOException {
        String name = "";
        while (true) {
            byte type = in.readByte();
            if (type == 0) {
                break;
            }
            String key = in.readUTF();
            if (type == 8 && "lastKnownName".equals(key)) {
                name = in.readUTF();
            } else {
                skip(in, type);
            }
        }
        return name;
    }

    private static double[] readDoubleList(DataInputStream in) throws IOException {
        byte elem = in.readByte();
        int len = in.readInt();
        if (len < 0 || len > 16) {
            skipListBody(in, elem, len);
            return new double[0];
        }
        if (elem != 6) {
            skipListBody(in, elem, len);
            return new double[0];
        }
        double[] out = new double[len];
        for (int i = 0; i < len; i++) {
            out[i] = in.readDouble();
        }
        return out;
    }

    private static float[] readFloatList(DataInputStream in) throws IOException {
        byte elem = in.readByte();
        int len = in.readInt();
        if (len < 0 || len > 16) {
            skipListBody(in, elem, len);
            return new float[0];
        }
        if (elem != 5) {
            skipListBody(in, elem, len);
            return new float[0];
        }
        float[] out = new float[len];
        for (int i = 0; i < len; i++) {
            out[i] = in.readFloat();
        }
        return out;
    }

    private static void skip(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int n = in.readInt();
                in.skipBytes(n);
            }
            case 8 -> in.readUTF();
            case 9 -> {
                byte elem = in.readByte();
                int len = in.readInt();
                skipListBody(in, elem, len);
            }
            case 10 -> skipCompound(in);
            case 11 -> {
                int n = in.readInt();
                in.skipBytes(n * 4);
            }
            case 12 -> {
                int n = in.readInt();
                in.skipBytes(n * 8);
            }
            default -> throw new IOException("unknown nbt type " + type);
        }
    }

    private static void skipCompound(DataInputStream in) throws IOException {
        while (true) {
            byte type = in.readByte();
            if (type == 0) {
                return;
            }
            in.readUTF();
            skip(in, type);
        }
    }

    private static void skipListBody(DataInputStream in, byte elem, int len) throws IOException {
        if (len <= 0) {
            return;
        }
        for (int i = 0; i < len; i++) {
            skip(in, elem);
        }
    }

    private record Snapshot(String dimension, double x, double y, double z,
                            float yaw, float pitch, String name) {
        String fabricDim() {
            return dimension != null && dimension.toLowerCase().contains("nether")
                    ? "nether" : "overworld";
        }
    }
}
