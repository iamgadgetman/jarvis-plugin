package com.yourname.jarvis;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.json.JSONObject;
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

    public VoiceHandler(Jarvis plugin) {
        this.plugin = plugin;
        this.elevenLabsApiKey = plugin.getConfig().getString("voice.elevenlabs.api-key", "");
        this.voiceId = plugin.getConfig().getString("voice.elevenlabs.voice-id", "pNInz6obpgDQGcFmaJgB");
        this.httpClient = HttpClient.newHttpClient();
        this.cacheDirectory = Paths.get(plugin.getDataFolder().getPath(), "voice-cache");
        try {
            Files.createDirectories(cacheDirectory);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not create voice cache directory", e);
        }
    }

    public void initialize() {
        plugin.getLogger().warning("Voice chat functionality is disabled due to Simple Voice Chat API issues. Using text-based responses only.");
    }

    public void processVoiceCommand(Player player, String input) {
        input = input.toLowerCase();
        plugin.getLogger().info("Received voice input from " + player.getName() + ": " + input);
        String command = null;
        if (input.contains("jarvis")) {
            if (input.contains("defend") || input.contains("get them") || input.contains("protect")) {
                command = "/jarvis defend on";
            } else if (input.contains("mine") || input.contains("find diamonds") || input.contains("scout")) {
                command = "/jarvis mine on";
            } else if (input.contains("give") || input.contains("hand over")) {
                command = "/jarvis give";
            } else if (input.contains("summon") || input.contains("call")) {
                command = "/jarvis spawn";
            } else if (input.contains("dismiss") || input.contains("go away") || input.contains("leave")) {
                command = "/jarvis dismiss";
            } else if (input.contains("follow") || input.contains("come with")) {
                command = "/jarvis follow";
            } else if (input.contains("stay") || input.contains("wait")) {
                command = "/jarvis stay";
            } else if (input.contains("ask")) {
                String question = input.replace("jarvis", "").replace("ask", "").trim();
                if (!question.isEmpty()) {
                    command = "/jarvis ask " + question;
                }
            }
        }

        if (command != null) {
            plugin.getLogger().info("Executing voice command: " + command);
            plugin.getServer().dispatchCommand(player, command);
        } else {
            plugin.getLogger().warning("Unrecognized voice command: " + input);
        }
    }

    public void sendVoiceResponse(Player player, String text) {
        if (text == null || text.trim().isEmpty()) {
            plugin.getLogger().warning("Empty voice response attempted for " + player.getName());
            return;
        }

        // Fallback to text-based response since voice chat is disabled
        player.sendMessage("[Jarvis Voice] " + text);
        plugin.getLogger().info("Sent text response to " + player.getName() + ": " + text);
    }

    private byte[] convertTextToSpeech(String text) {
        if (elevenLabsApiKey.isEmpty() || elevenLabsApiKey.equals("your-elevenlabs-api-key")) {
            plugin.getLogger().warning("ElevenLabs API key not set");
            return null;
        }

        try {
            String json = String.format("{\"text\":\"%s\",\"voice_settings\":{\"stability\":0.5,\"similarity_boost\":0.5}}",
                    text.replace("\"", "\\\""));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.elevenlabs.io/v1/text-to-speech/" + voiceId))
                    .header("Content-Type", "application/json")
                    .header("xi-api-key", elevenLabsApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                plugin.getLogger().info("Successfully generated voice response from ElevenLabs");
                return response.body();
            } else {
                plugin.getLogger().warning("ElevenLabs API error: HTTP " + response.statusCode());
                return null;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error converting text to speech", e);
            return null;
        }
    }

    private String transcribeAudio(byte[] audioData) throws Exception {
        String apiKey = plugin.getConfig().getString("openai.api-key");
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your-openai-api-key-here")) {
            plugin.getLogger().warning("OpenAI API key not set");
            return "";
        }

        Path tempFile = Files.createTempFile("voice", ".opus");
        Files.write(tempFile, audioData);

        String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/transcriptions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"voice.opus\"\r\n" +
                        "Content-Type: audio/opus\r\n\r\n" +
                        new String(Files.readAllBytes(tempFile), java.nio.charset.StandardCharsets.UTF_8) +
                        "\r\n--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                        "whisper-1\r\n" +
                        "--" + boundary + "--\r\n"
                ))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Files.delete(tempFile);

        if (response.statusCode() == 200) {
            JSONObject json = new JSONObject(response.body());
            return json.getString("text");
        } else {
            plugin.getLogger().warning("OpenAI Whisper API error: HTTP " + response.statusCode());
            return "";
        }
    }

    public void shutdown() {
        audioCache.clear();
        plugin.getLogger().info("Voice handler shut down");
    }
}
