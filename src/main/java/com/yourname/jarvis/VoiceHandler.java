package com.yourname.jarvis;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.audiochannel.ClientAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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
    private VoicechatApi voicechatApi;
    private final Map<String, ClientAudioChannel> audioChannels = new HashMap<>();

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
        plugin.getLogger().info("Initializing voice handler for Simple Voice Chat");
        try {
            voicechatApi = VoicechatApi.getInstance();
            if (voicechatApi != null) {
                registerEvents();
                plugin.getLogger().info("Simple Voice Chat API initialized");
            } else {
                plugin.getLogger().warning("Simple Voice Chat API not found");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize Simple Voice Chat API: " + e.getMessage());
        }
    }

    private void registerEvents() {
        voicechatApi.registerEvents(new EventRegistration() {
            @Override
            public void registerEvent(Class<?> eventType, Object listener, int priority) {
                if (eventType == MicrophonePacketEvent.class) {
                    voicechatApi.registerEvent(MicrophonePacketEvent.class, (MicrophonePacketEvent event) -> {
                        String playerName = event.getSenderConnection().getPlayer().getName();
                        Player player = plugin.getServer().getPlayer(playerName);
                        if (player == null) return;

                        byte[] audioData = event.getPacket().getOpusEncodedData();
                        try {
                            String transcribedText = transcribeAudio(audioData);
                            plugin.getLogger().info("Transcribed voice from " + playerName + ": " + transcribedText);
                            processVoiceCommand(player, transcribedText);
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Failed to process voice input: " + e.getMessage());
                        }
                    }, priority);
                }
            }
        });
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

        String normalizedText = text.trim().toLowerCase();
        byte[] audioData = audioCache.get(normalizedText);

        if (audioData == null) {
            audioData = convertTextToSpeech(text);
            if (audioData != null) {
                audioCache.put(normalizedText, audioData);
                try {
                    Path cacheFile = cacheDirectory.resolve(Integer.toHexString(normalizedText.hashCode()) + ".mp3");
                    Files.write(cacheFile, audioData);
                    plugin.getLogger().info("Cached voice response for: " + text);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error caching audio", e);
                }
            }
        }

        if (audioData != null && voicechatApi != null) {
            try {
                String playerId = player.getName();
                ClientAudioChannel audioChannel = audioChannels.computeIfAbsent(playerId, id -> {
                    LocationalAudioChannel channel = voicechatApi.createLocationalAudioChannel(
                            UUID.randomUUID(),
                            voicechatApi.fromServerLevel(player.getWorld()),
                            voicechatApi.createPosition(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ())
                    );
                    channel.setDistance(16.0);
                    return channel;
                });
                audioChannel.send(audioData);
                plugin.getLogger().info("Sent voice response to " + player.getName() + ": " + text);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to send voice response: " + e.getMessage());
            }
        } else {
            plugin.getLogger().warning("Failed to generate voice response for: " + text);
        }
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
        audioChannels.values().forEach(ClientAudioChannel::close);
        audioChannels.clear();
        audioCache.clear();
        plugin.getLogger().info("Voice handler shut down");
    }
}
