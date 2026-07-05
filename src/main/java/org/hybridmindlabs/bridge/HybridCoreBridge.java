package org.hybridmindlabs.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HybridCore — Game-Server Bridge (Minecraft / Spigot / Paper).
 *
 * Polls the HybridCore site for queued commands (vote/store/giveaway rewards,
 * bans, …) and runs them from the console, then acks the executed ones.
 *
 *   POST {base}/api/bridge/poll  -> { "commands": [ { "id": 12, "command": "..." } ] }
 *   POST {base}/api/bridge/ack   <- { "ids": [ 12 ] }
 *
 * Authenticated with a per-server bearer token ("hcb_...").
 */
public final class HybridCoreBridge extends JavaPlugin {

    private static final String PREFIX = "[HybridCore] ";

    private final Gson gson = new Gson();
    private HttpClient http;

    private String baseUrl;
    private String token;
    private boolean debug;
    private int taskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        if (!isConfigured()) {
            getLogger().warning("Set a valid bridge token (hcb_...) in config.yml, then restart.");
            return;
        }

        long intervalTicks = Math.max(40L, getConfig().getLong("poll-interval-seconds", 5) * 20L);
        taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(this, this::poll, 100L, intervalTicks)
                .getTaskId();

        getLogger().info("Polling " + baseUrl + " every " + (intervalTicks / 20) + "s.");
    }

    @Override
    public void onDisable() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    private void loadSettings() {
        baseUrl = getConfig().getString("base-url", "").replaceAll("/+$", "");
        token = getConfig().getString("token", "none");
        debug = getConfig().getBoolean("debug", false);
    }

    private boolean isConfigured() {
        return token != null && token.startsWith("hcb_");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hybridcore.bridge.admin")) {
            sender.sendMessage(PREFIX + "You don't have permission.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("poll")) {
            if (!isConfigured()) {
                sender.sendMessage(PREFIX + "Not configured — set the bridge token first.");
                return true;
            }
            Bukkit.getScheduler().runTaskAsynchronously(this, this::poll);
            sender.sendMessage(PREFIX + "Poll requested.");
            return true;
        }
        sender.sendMessage(PREFIX + "Usage: /hcbridge poll");
        return true;
    }

    // ── Poll / ack ───────────────────────────────────────────────

    private void poll() {
        if (!isConfigured()) {
            return;
        }
        try {
            HttpRequest req = authRequest("/api/bridge/poll")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 401) {
                getLogger().severe("Bridge token rejected (401). Check config.yml.");
                return;
            }
            if (res.statusCode() != 200) {
                debug("Poll HTTP " + res.statusCode());
                return;
            }

            handle(res.body());
        } catch (Exception e) {
            debug("Poll error: " + e.getMessage());
        }
    }

    private void handle(String body) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            debug("Invalid JSON in poll response.");
            return;
        }

        if (!root.has("commands") || !root.get("commands").isJsonArray()) {
            return;
        }

        JsonArray arr = root.getAsJsonArray("commands");
        List<Integer> ids = new ArrayList<>();
        List<String> commands = new ArrayList<>();

        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            int id = obj.has("id") ? obj.get("id").getAsInt() : 0;
            String cmd = obj.has("command") ? obj.get("command").getAsString() : "";
            if (id > 0 && !cmd.isEmpty()) {
                ids.add(id);
                commands.add(cmd);
            }
        }

        if (ids.isEmpty()) {
            return;
        }

        // Commands must run on the main server thread.
        Bukkit.getScheduler().runTask(this, () -> {
            for (String cmd : commands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                debug("Exec: " + cmd);
            }
        });

        ack(ids);
        getLogger().info("Executed " + ids.size() + " command(s).");
    }

    private void ack(List<Integer> ids) {
        try {
            JsonObject payload = new JsonObject();
            JsonArray idsArray = new JsonArray();
            ids.forEach(idsArray::add);
            payload.add("ids", idsArray);

            HttpRequest req = authRequest("/api/bridge/ack")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            debug("Ack HTTP " + res.statusCode());
        } catch (Exception e) {
            debug("Ack error: " + e.getMessage());
        }
    }

    private HttpRequest.Builder authRequest(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest");
    }

    private void debug(String msg) {
        if (debug) {
            getLogger().info(msg);
        }
    }
}
