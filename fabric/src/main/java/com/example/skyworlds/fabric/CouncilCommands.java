package com.example.skyworlds.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.Pool;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric stubs for PlayerCouncil commands. Survival still owns the logic;
 * this publishes {@code pc:cmd} and shows MiniMessage replies from {@code pc:reply}.
 */
final class CouncilCommands {
    private static final Logger LOG = LoggerFactory.getLogger("SkyWorlds");
    private static final String CMD = "pc:cmd";
    private static final List<String> NAMES = List.of(
            "activity", "council", "proposals", "propose", "proposal",
            "councilvote", "cvote", "pcvote", "cancelproposal",
            "councilweb", "webcode", "councilcode", "counciladmin",
            "councilreview", "councilboard", "pcboard", "activityboard",
            "cape", "spawncape", "topkiller",
            "top", "topgui", "topplayers", "stats", "tpstats",
            "bug", "bugs"
    );

    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> JOIN_NOTIFY = new ConcurrentHashMap<>();

    private record Pending(UUID player, int ticksLeft, boolean quiet) {}

    private CouncilCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, SkyWorldsFabric mod) {
        for (String name : NAMES) {
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name)
                    .executes(ctx -> run(ctx, mod, name, new String[0]))
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .executes(ctx -> run(ctx, mod, name,
                                    split(StringArgumentType.getString(ctx, "args")))));
            dispatcher.register(node);
        }
    }

    static void onJoin(ServerPlayer player) {
        JOIN_NOTIFY.put(player.getUUID(), 80);
    }

    static void tick(MinecraftServer srv, SkyWorldsFabric mod) {
        if (!JOIN_NOTIFY.isEmpty()) {
            Iterator<Map.Entry<UUID, Integer>> joinIt = JOIN_NOTIFY.entrySet().iterator();
            while (joinIt.hasNext()) {
                Map.Entry<UUID, Integer> e = joinIt.next();
                int left = e.getValue() - 1;
                if (left > 0) {
                    e.setValue(left);
                    continue;
                }
                joinIt.remove();
                ServerPlayer player = srv.getPlayerList().getPlayer(e.getKey());
                if (player != null) {
                    publish(mod, player, "bugnotify", new String[0]);
                }
            }
        }
        if (PENDING.isEmpty()) return;
        Pool<Jedis> pool = mod.redisPool();
        if (pool == null) {
            PENDING.clear();
            return;
        }
        Iterator<Map.Entry<String, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Pending> e = it.next();
            String id = e.getKey();
            Pending p = e.getValue();
            String raw = null;
            try (Jedis j = pool.getResource()) {
                raw = SkyWorldsFabric.jedisGet(j, "pc:reply:" + id);
                if (raw != null) {
                    SkyWorldsFabric.redisDel(j, "pc:reply:" + id);
                }
            } catch (Exception ex) {
                LOG.warn("pc:reply poll: {}", ex.toString());
            }
            if (raw != null) {
                it.remove();
                deliver(srv, p.player(), raw);
                continue;
            }
            int left = p.ticksLeft() - 1;
            if (left <= 0) {
                it.remove();
                if (p.quiet()) continue;
                ServerPlayer player = srv.getPlayerList().getPlayer(p.player());
                if (player != null) {
                    player.sendSystemMessage(Component.literal(
                            "No response from survival council. Is PlayerCouncil loaded?")
                            .withStyle(ChatFormatting.RED));
                }
            } else {
                e.setValue(new Pending(p.player(), left, p.quiet()));
            }
        }
    }

    private static int run(CommandContext<CommandSourceStack> ctx, SkyWorldsFabric mod, String cmd, String[] args) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Players only."));
            return 0;
        }
        if (!publish(mod, player, cmd, args)) {
            player.sendSystemMessage(Component.literal("Council bridge is offline.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        return 1;
    }

    private static boolean publish(SkyWorldsFabric mod, ServerPlayer player, String cmd, String[] args) {
        Pool<Jedis> pool = mod.redisPool();
        if (pool == null) return false;
        String id = UUID.randomUUID().toString();
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("uuid", player.getUUID().toString());
        o.addProperty("name", player.getGameProfile().name());
        o.addProperty("cmd", cmd);
        o.addProperty("server", "fabric");
        JsonArray arr = new JsonArray();
        for (String a : args) arr.add(a);
        o.add("args", arr);
        try (Jedis j = pool.getResource()) {
            SkyWorldsFabric.jedisPublish(j, CMD, o.toString());
        } catch (Exception e) {
            LOG.warn("pc:cmd publish: {}", e.toString());
            return false;
        }
        PENDING.put(id, new Pending(player.getUUID(), 100, "bugnotify".equals(cmd)));
        return true;
    }

    private static String[] split(String raw) {
        if (raw == null || raw.isBlank()) return new String[0];
        return raw.trim().split("\\s+");
    }

    private static void deliver(MinecraftServer srv, UUID uuid, String raw) {
        ServerPlayer player = srv.getPlayerList().getPlayer(uuid);
        if (player == null) return;
        try {
            JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
            if (!o.has("lines") || !o.get("lines").isJsonArray()) return;
            JsonArray lines = o.getAsJsonArray("lines");
            lines.forEach(el -> player.sendSystemMessage(fromMini(el.getAsString())));
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal(raw));
        }
    }

    static Component fromMini(String mini) {
        if (mini == null || mini.isEmpty()) return Component.empty();
        MutableComponent out = Component.empty();
        Deque<Style> stack = new ArrayDeque<>();
        Style current = Style.EMPTY;
        int i = 0;
        while (i < mini.length()) {
            if (mini.charAt(i) == '<') {
                int end = mini.indexOf('>', i);
                if (end < 0) {
                    out.append(Component.literal(mini.substring(i)).withStyle(current));
                    break;
                }
                String tag = mini.substring(i + 1, end);
                i = end + 1;
                if (tag.startsWith("/")) {
                    current = stack.isEmpty() ? Style.EMPTY : stack.pop();
                } else {
                    stack.push(current);
                    current = applyTag(current, tag);
                }
            } else {
                int next = mini.indexOf('<', i);
                if (next < 0) next = mini.length();
                String text = mini.substring(i, next);
                if (!text.isEmpty()) {
                    out.append(Component.literal(text).withStyle(current));
                }
                i = next;
            }
        }
        return out;
    }

    private static Style applyTag(Style current, String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.equals("reset")) return Style.EMPTY;
        if (lower.equals("bold")) return current.withBold(true);
        if (lower.equals("italic")) return current.withItalic(true);
        if (lower.equals("underlined") || lower.equals("underline")) return current.withUnderlined(true);
        if (lower.equals("strikethrough")) return current.withStrikethrough(true);
        if (lower.equals("obfuscated")) return current.withObfuscated(true);
        if (lower.startsWith("click:open_url:")) {
            String rest = unquote(tag.substring("click:open_url:".length()));
            try {
                return current.withClickEvent(new ClickEvent.OpenUrl(URI.create(rest)));
            } catch (Exception ignored) {
                return current;
            }
        }
        if (lower.startsWith("click:run_command:")) {
            String rest = unquote(tag.substring("click:run_command:".length()));
            try {
                return current.withClickEvent(new ClickEvent.RunCommand(rest));
            } catch (Exception ignored) {
                return current;
            }
        }
        if (lower.startsWith("click:suggest_command:")) {
            String rest = unquote(tag.substring("click:suggest_command:".length()));
            try {
                return current.withClickEvent(new ClickEvent.SuggestCommand(rest));
            } catch (Exception ignored) {
                return current;
            }
        }
        if (lower.startsWith("#") && lower.length() == 7) {
            try {
                return current.withColor(Integer.parseInt(lower.substring(1), 16));
            } catch (Exception ignored) {
                return current;
            }
        }
        try {
            ChatFormatting fmt = ChatFormatting.valueOf(lower.toUpperCase(Locale.ROOT));
            return switch (fmt) {
                case RESET -> Style.EMPTY;
                case BOLD -> current.withBold(true);
                case ITALIC -> current.withItalic(true);
                case UNDERLINE -> current.withUnderlined(true);
                case STRIKETHROUGH -> current.withStrikethrough(true);
                case OBFUSCATED -> current.withObfuscated(true);
                default -> current.withColor(fmt);
            };
        } catch (IllegalArgumentException ignored) {
            return current;
        }
    }

    private static String unquote(String rest) {
        if (rest.length() >= 2 && ((rest.startsWith("'") && rest.endsWith("'"))
                || (rest.startsWith("\"") && rest.endsWith("\"")))) {
            return rest.substring(1, rest.length() - 1);
        }
        return rest;
    }
}
