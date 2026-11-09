package org.hybridmindlabs.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HybridCore — Game-Server Bridge (Minecraft / Spigot / Paper).
 *
 * Compatible with Minecraft 1.8.9 through latest (1.21+) and Java 8+.
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

    private String baseUrl;
    private String token;
    private boolean debug;
    private int taskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

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
            HttpResponseData res = sendPost("/api/bridge/poll", null);

            if (res.statusCode == 401) {
                getLogger().severe("Bridge token rejected (401). Check config.yml.");
                return;
            }
            if (res.statusCode != 200) {
                debug("Poll HTTP " + res.statusCode);
                return;
            }

            handle(res.body);
        } catch (Exception e) {
            debug("Poll error: " + e.getMessage());
        }
    }

    private void handle(String body) {
        JsonObject root;
        try {
            // Using gson.fromJson for backwards compatibility with older Gson versions in 1.8.8 (2.2.x)
            root = gson.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            debug("Invalid JSON in poll response: " + e.getMessage());
            return;
        }

        if (root == null || !root.has("commands") || !root.get("commands").isJsonArray()) {
            return;
        }

        JsonArray arr = root.getAsJsonArray("commands");
        List<Integer> ids = new ArrayList<Integer>();
        List<String> commands = new ArrayList<String>();

        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            int id = (obj.has("id") && obj.get("id").isJsonPrimitive()) ? obj.get("id").getAsInt() : 0;
            String cmd = (obj.has("command") && obj.get("command").isJsonPrimitive()) ? obj.get("command").getAsString() : "";
            if (id > 0 && !cmd.trim().isEmpty()) {
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
            // Explicitly wrapping in JsonPrimitive ensures compatibility with Gson 2.2.x (MC 1.8.x)
            for (Integer id : ids) {
                idsArray.add(new JsonPrimitive(id));
            }
            payload.add("ids", idsArray);

            HttpResponseData res = sendPost("/api/bridge/ack", gson.toJson(payload));
            debug("Ack HTTP " + res.statusCode);
        } catch (Exception e) {
            debug("Ack error: " + e.getMessage());
        }
    }

    // ── HTTP transport (Java 8+ HttpURLConnection) ───────────────

    private HttpResponseData sendPost(String path, String jsonPayload) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("User-Agent", "HybridCoreBridge/1.0");

            if (jsonPayload != null && !jsonPayload.isEmpty()) {
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
            } else {
                conn.setFixedLengthStreamingMode(0);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.flush();
                }
            }

            int statusCode = conn.getResponseCode();
            InputStream is = (statusCode >= 200 && statusCode < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String body = "";
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    body = sb.toString();
                }
            }
            return new HttpResponseData(statusCode, body);
        } finally {
            conn.disconnect();
        }
    }

    private static class HttpResponseData {
        final int statusCode;
        final String body;

        HttpResponseData(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private void debug(String msg) {
        if (debug) {
            getLogger().info(msg);
        }
    }
}
