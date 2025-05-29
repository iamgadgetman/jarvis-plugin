package com.yourname.jarvis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class VoiceHandler {

    private final Jarvis plugin;
    private final String elevenLabsApiKey;
    private final String voiceId;
    private final HttpClient httpClient;
    private final Map<String, byte[]> audioCache = new HashMap<>();
    private final Path cacheDirectory;
    private WebSocketServer webSocketServer;
    private final boolean voiceEnabled;
    private final Map<WebSocket, UUID> connectionToPlayer = new HashMap<>();

    public VoiceHandler(Jarvis plugin) {
        this.plugin = plugin;
        this.elevenLabsApiKey = plugin.getConfig().getString("voice.elevenlabs.api-key", "");
        this.voiceId = plugin.getConfig().getString("voice.elevenlabs.voice-id", "pNInz6obpgDQGcFmaJgB");
        this.httpClient = HttpClient.newHttpClient();
        this.cacheDirectory = Paths.get(plugin.getDataFolder().getPath(), "voice-cache");
        this.voiceEnabled = plugin.getConfig().getBoolean("voice.enabled", false);
        try {
            Files.createDirectories(cacheDirectory);
            plugin.getLogger().info("Voice cache directory created at: " + cacheDirectory.toString());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not create voice cache directory", e);
        }
        plugin.getLogger().info("VoiceHandler initialized with voice.enabled=" + voiceEnabled + ", elevenLabsApiKey=" + (elevenLabsApiKey.isEmpty() ? "not set" : "set"));
    }

    public void initialize() {
        if (!voiceEnabled) {
            plugin.getLogger().warning("Voice functionality is disabled in config (voice.enabled=false). Skipping WebSocket server initialization.");
            return;
        }
        plugin.getLogger().info("Initializing voice handler with WebSocket server on port 9090");
        startWebSocketServer();
    }

    private void startWebSocketServer() {
        try {
            webSocketServer = new WebSocketServer(new InetSocketAddress(9090)) {
                @Override
                public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    plugin.getLogger().info("WebSocket connection opened from: " + conn.getRemoteSocketAddress());
                }

                @Override
                public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                    plugin.getLogger().info("WebSocket connection closed: code=" + code + ", reason=" + reason + ", remote=" + remote);
                    connectionToPlayer.remove(conn);
                }

                @Override
                public void onMessage(WebSocket conn, String message) {
                    plugin.getLogger().info("Received WebSocket input: \"" + message + "\" from " + conn.getRemoteSocketAddress());
                    try {
                        JSONObject json = new JSONObject(message);
                        String type = json.getString("type");
                        if (type.equals("auth")) {
                            String uuid = json.getString("uuid");
                            connectionToPlayer.put(conn, UUID.fromString(uuid));
                            plugin.getLogger().info("Authenticated connection for player UUID: " + uuid);
                        } else if (type.equals("command")) {
                            UUID playerUUID = connectionToPlayer.get(conn);
                            if (playerUUID == null) {
                                plugin.getLogger().warning("No player UUID associated with connection");
                                return;
                            }
                            Player player = plugin.getServer().getPlayer(playerUUID);
                            if (player == null) {
                                plugin.getLogger().warning("Player not found for UUID: " + playerUUID);
                                return;
                            }
                            String text = json.getString("text");
                            plugin.getLogger().info("Processing command for player " + player.getName() + ": " + text);
                            processVoiceCommand(player, text);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Invalid WebSocket message: " + message);
                    }
                }

                @Override
                public void onError(WebSocket conn, Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "WebSocket error: " + ex.getMessage(), ex);
                }

                @Override
                public void onStart() {
                    plugin.getLogger().info("WebSocket server successfully started on port 9090");
                }
            };
            webSocketServer.start();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start WebSocket server on port 9090: " + e.getMessage(), e);
        }
    }

    public void processVoiceCommand(Player player, String input) {
        plugin.getLogger().info("Processing voice input for player " + player.getName() + ": \"" + input + "\"");
        if (!input.toLowerCase().contains("jarvis")) {
            plugin.getLogger().info("Input does not contain 'jarvis'. Ignoring.");
            return;
        }
        input = input.replaceAll("(?i)jarvis", "").trim();
        plugin.getLogger().info("Cleaned input: \"" + input + "\"");
        try {
            String context = plugin.buildServerContext(player);
            plugin.getLogger().info("Built server context: " + context);
            String prompt = "You are Jarvis, a witty British butler in a Minecraft server. Parse this command: '" + input + "'. " +
                            "Return a JSON object with 'command' and 'args'. " +
                            "Recognize these valid commands: 'spawn', 'dismiss', 'follow', 'stay', 'go', 'defend', 'mine', 'bell', 'give', 'inventory', 'ask'. " +
                            "Map 'summon' or 'call' to 'spawn', 'inventory' or 'show inventory' to 'inventory', 'give me the goodies' or 'hand over' to 'give'. " +
                            "Set 'command' to the exact command name (e.g., 'spawn', 'inventory') and 'args' to any additional arguments. " +
                            "Only use 'ask' if the input is explicitly a question (e.g., starts with 'what', 'how', 'why') or begins with 'ask'. " +
                            "Examples: " +
                            "'summon' -> {'command': 'spawn', 'args': ''}, " +
                            "'go 10 20 30' -> {'command': 'go', 'args': '10 20 30'}, " +
                            "'show inventory' -> {'command': 'inventory', 'args': ''}, " +
                            "'give me the goodies' -> {'command': 'give', 'args': ''}, " +
                            "'ask where am i' -> {'command': 'ask', 'args': 'where am i'}.";
            plugin.getLogger().info("Sending OpenAI prompt: " + prompt);
            String openAIResponse = plugin.queryOpenAI(prompt, context);
            plugin.getLogger().info("Received OpenAI response: " + openAIResponse);
            JSONObject response = new JSONObject(openAIResponse);
            String command = response.getString("command");
            String args = response.has("args") ? response.getString("args") : "";
            String fullCommand = "jarvis " + command + (args.isEmpty() ? "" : " " + args);
            plugin.getLogger().info("Executing command: " + fullCommand);
            boolean[] success = new boolean[1];
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                success[0] = plugin.getServer().dispatchCommand(player, fullCommand);
            });
            plugin.getLogger().info("Command execution " + (success[0] ? "succeeded" : "failed"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse voice command: " + e.getMessage(), e);
            sendVoiceResponse(player, "I’m terribly sorry, sir, but I didn’t quite catch that.");
        }
    }

    public void sendVoiceResponse(Player player, String text) {
        plugin.getLogger().info("Attempting to send voice response to " + player.getName() + ": \"" + text + "\"");
        if (text == null || text.trim().isEmpty()) {
            plugin.getLogger().warning("Empty voice response attempted for " + player.getName());
            player.sendMessage(Component.text("[Jarvis Voice] Empty response detected.", NamedTextColor.RED));
            return;
        }

        String normalizedText = text.trim().toLowerCase();
        byte[] audioData = audioCache.get(normalizedText);

        if (audioData == null) {
            plugin.getLogger().info("No cached audio for: \"" + normalizedText + "\". Generating new audio.");
            audioData = convertTextToSpeech(text);
            if (audioData != null) {
                audioCache.put(normalizedText, audioData);
                try {
                    Path cacheFile = cacheDirectory.resolve(Integer.toHexString(normalizedText.hashCode()) + ".mp3");
                    Files.write(cacheFile, audioData);
                    plugin.getLogger().info("Cached audio to: " + cacheFile.toString());
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error caching audio: " + e.getMessage(), e);
                }
            } else {
                plugin.getLogger().warning("Failed to generate audio for: \"" + text + "\"");
                player.sendMessage(Component.text("[Jarvis Voice] Audio generation failed.", NamedTextColor.RED));
                return;
            }
        } else {
            plugin.getLogger().info("Using cached audio for: \"" + normalizedText + "\"");
        }

        // Placeholder for Simple Voice Chat integration
        player.sendMessage(Component.text("[Jarvis Voice] " + text, NamedTextColor.AQUA));
        plugin.getLogger().info("Sent text-based voice response to " + player.getName() + ": \"" + text + "\"");
    }

    private byte[] convertTextToSpeech(String text) {
        if (elevenLabsApiKey.isEmpty() || elevenLabsApiKey.equals("your-elevenlabs-api-key")) {
            plugin.getLogger().warning("ElevenLabs API key not set or invalid");
            return null;
        }

        plugin.getLogger().info("Converting text to speech via ElevenLabs: \"" + text + "\"");
        try {
            String json = String.format("{\"text\":\"%s\",\"voice_settings\":{\"stability\":0.5,\"similarity_boost\":0.5}}",
                    text.replace("\"", "\\\""));
            plugin.getLogger().info("Sending ElevenLabs request: " + json);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.elevenlabs.io/v1/text-to-speech/" + voiceId + "/stream"))
                    .header("Content-Type", "application/json")
                    .header("xi-api-key", elevenLabsApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            plugin.getLogger().info("ElevenLabs response status: " + response.statusCode());
            if (response.statusCode() == 200) {
                plugin.getLogger().info("Successfully generated voice response from ElevenLabs (size: " + response.body().length + " bytes)");
                return response.body();
            } else {
                plugin.getLogger().warning("ElevenLabs API error: HTTP " + response.statusCode());
                return null;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error converting text to speech: " + e.getMessage(), e);
            return null;
        }
    }

    public void shutdown() {
        plugin.getLogger().info("Shutting down VoiceHandler");
        audioCache.clear();
        if (webSocketServer != null) {
            try {
                webSocketServer.stop();
                plugin.getLogger().info("WebSocket server shut down");
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error shutting down WebSocket server: " + e.getMessage(), e);
            }
        }
        plugin.getLogger().info("Voice handler shut down");
    }
}
