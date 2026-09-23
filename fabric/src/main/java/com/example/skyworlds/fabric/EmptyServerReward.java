package com.example.skyworlds.fabric;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Empty-server petrified oak slabs on Fabric when paper+fabric are empty
 * and this player was not on survival-side in the last 10s.
 */
final class EmptyServerReward {
    private static final Logger LOG = LoggerFactory.getLogger("SkyWorlds");
    private static final Path ROOT = Path.of("/mnt/pool/skygate/empty-reward");
    private static final Path LAST_SEEN = ROOT.resolve("last-seen");
    private static final Path PAPER_CONFIG = Path.of("/mnt/pool/survival/plugins/FirstJoinReward/config.yml");
    private static final long GRACE_MS = 10_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Map<UUID, Integer> pendingTicks = new HashMap<>();

    private EmptyServerReward() {}

    static void onJoin(ServerPlayer player) {
        pendingTicks.put(player.getUUID(), 5);
        writeOnline(player.level().getServer());
    }

    static void onDisconnect(MinecraftServer server, ServerPlayer player) {
        if (player != null) {
            touchLastSeen(player.getUUID());
            pendingTicks.remove(player.getUUID());
        }
        int n = server == null ? 0 : Math.max(0, server.getPlayerCount() - 1);
        writeOnlineCount(n);
    }

    static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        writeOnline(server);
        Iterator<Map.Entry<UUID, Integer>> it = pendingTicks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player != null) {
                tryGrant(server, player);
            }
        }
    }

    private static void tryGrant(MinecraftServer server, ServerPlayer player) {
        if (server.getPlayerCount() != 1) {
            return;
        }
        if (seenRecently(player.getUUID())) {
            LOG.info("Skipped empty-server reward for {} (on survival/fabric in the last 10s).",
                    player.getGameProfile().name());
            return;
        }
        if (freshOnline("survival") > 0) {
            LOG.info("Skipped empty-server reward for {} (paper survival is not empty).",
                    player.getGameProfile().name());
            return;
        }
        ItemStack reward = new ItemStack(rewardItem(), rewardAmount());
        if (!player.getInventory().add(reward)) {
            player.drop(reward, false);
        }
        LOG.info("{} received the empty-server reward.", player.getGameProfile().name());
        String webhook = webhookUrl();
        if (webhook != null) {
            String name = player.getGameProfile().name();
            Thread.ofVirtual().name("empty-reward-discord").start(() -> sendDiscord(name, webhook));
        }
    }

    private static Item rewardItem() {
        try {
            String material = configValue("material");
            if (material != null) {
                var holder = BuiltInRegistries.ITEM.get(
                        Identifier.parse("minecraft:" + material.toLowerCase()));
                if (holder.isPresent()) {
                    Item item = holder.get().value();
                    if (item != null && item != Items.AIR) {
                        return item;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Items.PETRIFIED_OAK_SLAB;
    }

    private static int rewardAmount() {
        try {
            String amount = configValue("amount");
            if (amount != null) {
                return Math.max(1, Math.min(64, Integer.parseInt(amount)));
            }
        } catch (Exception ignored) {
        }
        return 10;
    }

    private static String webhookUrl() {
        try {
            if (!"true".equalsIgnoreCase(configValue("enabled"))) {
                return null;
            }
            String url = configValue("webhook-url");
            if (url == null || url.isBlank() || url.contains("YOUR_WEBHOOK")) {
                return null;
            }
            return url.replace("\"", "").trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String configValue(String key) {
        if (!Files.isRegularFile(PAPER_CONFIG)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(PAPER_CONFIG, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || !trimmed.startsWith(key + ":")) {
                    continue;
                }
                return trimmed.substring(key.length() + 1).trim().replace("\"", "");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void sendDiscord(String playerName, String webhookUrl) {
        try {
            String description = "**" + escapeJson(playerName) + "** was the first to join and received the reward!";
            String json = """
                    {
                      "embeds": [{
                        "title": "Empty Server Reward",
                        "description": "%s",
                        "color": 5763719
                      }]
                    }
                    """.formatted(description);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warn("Discord webhook returned HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            LOG.warn("Failed to send empty-server Discord webhook: {}", e.getMessage());
        }
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeOnline(MinecraftServer server) {
        writeOnlineCount(server.getPlayerCount());
    }

    private static void writeOnlineCount(int count) {
        try {
            Files.createDirectories(ROOT);
            Files.writeString(
                    ROOT.resolve("fabric.online"),
                    count + "\n" + System.currentTimeMillis(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception ignored) {
        }
    }

    static void touchLastSeen(UUID uuid) {
        if (uuid == null) {
            return;
        }
        try {
            Files.createDirectories(LAST_SEEN);
            Files.writeString(
                    LAST_SEEN.resolve(uuid.toString()),
                    Long.toString(System.currentTimeMillis()),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception ignored) {
        }
    }

    private static boolean seenRecently(UUID uuid) {
        try {
            Path file = LAST_SEEN.resolve(uuid.toString());
            if (!Files.isRegularFile(file)) {
                return false;
            }
            long then = Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
            return System.currentTimeMillis() - then < GRACE_MS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int freshOnline(String side) {
        try {
            Path file = ROOT.resolve(side + ".online");
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).trim().split("\\R");
            if (lines.length < 2) {
                return 0;
            }
            long then = Long.parseLong(lines[1].trim());
            if (System.currentTimeMillis() - then > 20_000L) {
                return 0;
            }
            return Math.max(0, Integer.parseInt(lines[0].trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
