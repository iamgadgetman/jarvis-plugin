package com.yourname.jarvis;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class Jarvis extends JavaPlugin implements Listener {

    private String apiKey;
    private String model;
    private int maxLinesPerPage;
    private int maxCharactersPerLine;
    private String elevenLabsApiKey;
    private String elevenLabsVoiceId;
    private boolean voiceEnabled;
    private String summonSound;
    private VoiceHandler voiceHandler;
    private File playerDataFile;
    private FileConfiguration playerDataConfig;
    private final Map<String, UUID> usernameToUUID = new HashMap<>(); // Lowercase username to UUID
    private final Map<String, String> lowercaseToExactUsername = new HashMap<>(); // Lowercase to exact username
    private final Map<String, NPC> playerJarvis = new HashMap<>();
    private final Map<String, List<String>> chatPages = new HashMap<>();
    private final Map<String, Integer> currentPage = new HashMap<>();
    private final Map<String, Boolean> defending = new HashMap<>();
    private final Map<String, Boolean> mining = new HashMap<>();
    private final Map<String, List<ItemStack>> inventories = new HashMap<>();
    private final Random random = new Random();
    private final Map<String, Integer> taskIds = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("Jarvis AI Companion starting up... Preparing a spot of tea and sarcasm!");

        // Load username-to-UUID mappings from playerdata.yml
        playerDataFile = new File(getDataFolder(), "playerdata.yml");
        playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
        if (playerDataConfig.contains("players")) {
            for (String username : playerDataConfig.getConfigurationSection("players").getKeys(false)) {
                String uuid = playerDataConfig.getString("players." + username + ".uuid");
                if (uuid != null) {
                    String lowercaseUsername = username.toLowerCase();
                    usernameToUUID.put(lowercaseUsername, UUID.fromString(uuid));
                    lowercaseToExactUsername.put(lowercaseUsername, username);
                }
            }
        }

        saveDefaultConfig();
        apiKey = System.getenv("OPENAI_API_KEY") != null ? System.getenv("OPENAI_API_KEY") : getConfig().getString("openai.api-key");
        elevenLabsApiKey = System.getenv("ELEVENLABS_API_KEY") != null ? System.getenv("ELEVENLABS_API_KEY") : getConfig().getString("voice.elevenlabs.api-key", "");
        if (System.getenv("OPENAI_API_KEY") != null || System.getenv("ELEVENLABS_API_KEY") != null) {
            getLogger().info("Using API keys from environment variables.");
        }
        model = getConfig().getString("openai.model", "gpt-3.5-turbo");
        elevenLabsVoiceId = getConfig().getString("voice.elevenlabs.voice-id", "pNInz6obpgDQGcFmaJgB");
        voiceEnabled = getConfig().getBoolean("voice.enabled", false);
        summonSound = getConfig().getString("sound.summon", "BLOCK_NOTE_BLOCK_BELL");

        // Validate API keys
        boolean validConfig = true;
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your-openai-api-key-here")) {
            getLogger().severe("OpenAI API key is not configured in config.yml! Please set a valid key in plugins/Jarvis/config.yml.");
            validConfig = false;
        }
        if (voiceEnabled && (elevenLabsApiKey == null || elevenLabsApiKey.isEmpty() || elevenLabsApiKey.equals("your-elevenlabs-api-key-here"))) {
            getLogger().severe("ElevenLabs API key is not configured in config.yml! Voice features require a valid key in plugins/Jarvis/config.yml.");
            validConfig = false;
        }
        if (!validConfig) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        maxLinesPerPage = getConfig().getInt("chat.max-lines-per-page", 5);
        maxCharactersPerLine = getConfig().getInt("chat.max-characters-per-line", 40);

        if (!getServer().getPluginManager().isPluginEnabled("Citizens")) {
            getLogger().severe("Citizens not found! No butler without a body, I’m afraid.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        voiceHandler = new VoiceHandler(this);
        if (voiceEnabled && getServer().getPluginManager().isPluginEnabled("SimpleVoiceChat")) {
            voiceHandler.initialize();
            getLogger().info("Voice features enabled with Simple Voice Chat!");
        } else if (voiceEnabled) {
            getLogger().warning("Simple Voice Chat not found. Voice features disabled.");
            voiceEnabled = false;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Jarvis initialized successfully! Ready to be splendidly insufferable.");
    }

    @Override
    public void onDisable() {
        if (voiceHandler != null) {
            voiceHandler.shutdown();
        }
        taskIds.forEach((username, taskId) -> getServer().getScheduler().cancelTask(taskId));
        taskIds.clear();
        playerJarvis.values().forEach(NPC::destroy);
        playerJarvis.clear();
        chatPages.clear();
        currentPage.clear();
        defending.clear();
        mining.clear();
        inventories.clear();
        getLogger().info("Jarvis shutting down. Cheerio!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("jarvis")) return false;

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can summon Jarvis, you mechanical brute!", NamedTextColor.RED));
            return true;
        }
        String playerId = player.getName();

        if (!player.hasPermission("jarvis.use")) {
            player.sendMessage(Component.text("You lack the proper breeding to command Jarvis, sir!", NamedTextColor.RED));
            return true;
        }

        String input = String.join(" ", args).toLowerCase();
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            player.sendMessage(Component.text("Jarvis Commands:", NamedTextColor.AQUA));
            player.sendMessage(Component.text("- /jarvis help - This delightful menu", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis spawn - Summon your impeccable butler", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis dismiss - Send Jarvis for a well-earned break", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis follow - Have Jarvis trail you like a loyal hound", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis stay - Order Jarvis to stand about uselessly", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis go <x> <y> <z> - Send Jarvis to specific coordinates", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis defend [on|off] - Order Jarvis to fend off beastly mobs", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis mine [on|off] - Have Jarvis scout for ores", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis bell - Receive a Jarvis summoning bell", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis give - Receive items collected by Jarvis", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis ask <question> - Consult Jarvis’s vast intellect", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- /jarvis lookup <username> - Find a player's UUID", NamedTextColor.GRAY));
            return true;
        }

        if (args[0].equalsIgnoreCase("lookup")) {
            if (args.length != 2) {
                player.sendMessage(Component.text("Usage: /jarvis lookup <username>", NamedTextColor.RED));
                return true;
            }
            String searchUsername = args[1];
            String searchLowercase = searchUsername.toLowerCase();
            UUID uuid = usernameToUUID.get(searchLowercase);
            if (uuid == null) {
                // Try offline player lookup as fallback
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(searchUsername);
                if (offlinePlayer.hasPlayedBefore()) {
                    uuid = offlinePlayer.getUniqueId();
                    String exactUsername = offlinePlayer.getName();
                    if (exactUsername != null) {
                        usernameToUUID.put(searchLowercase, uuid);
                        lowercaseToExactUsername.put(searchLowercase, exactUsername);
                        playerDataConfig.set("players." + exactUsername + ".uuid", uuid.toString());
                        playerDataConfig.set("players." + exactUsername + ".name", exactUsername);
                        savePlayerDataConfig();
                    }
                }
            }
            if (uuid != null) {
                String exactUsername = lowercaseToExactUsername.getOrDefault(searchLowercase, searchUsername);
                player.sendMessage(Component.text("UUID for " + exactUsername + ": " + uuid.toString(), NamedTextColor.AQUA));
            } else {
                player.sendMessage(Component.text("No UUID found for username: " + searchUsername, NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("spawn") || input.contains("summon") || input.contains("call")) {
            if (playerJarvis.containsKey(playerId)) {
                player.sendMessage(Component.text("Jarvis is already here, you absent-minded fool!", NamedTextColor.YELLOW));
                return true;
            }

            try {
                NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "Jarvis");
                npc.spawn(player.getLocation());
                npc.getEntity().setCustomName("§3Jarvis");
                npc.getEntity().setCustomNameVisible(true);
                npc.getNavigator().getDefaultParameters().distanceMargin(3.0f);
                playerJarvis.put(playerId, npc);
                inventories.put(playerId, new ArrayList<>());

                player.playSound(player.getLocation(), Sound.valueOf(summonSound), 1.0f, 2.0f);
                getServer().getScheduler().runTaskLater(this, () -> 
                    player.playSound(player.getLocation(), Sound.valueOf(summonSound), 1.0f, 2.0f), 5L);
                getServer().getScheduler().runTaskLater(this, () -> 
                    player.playSound(player.getLocation(), Sound.valueOf(summonSound), 1.0f, 2.0f), 10L);
                sendWittyMessage(player, "Jarvis, say something witty and British to greet the player.");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to spawn Jarvis NPC", e);
                player.sendMessage(Component.text("Oh, bother! Jarvis seems to have tripped on his coattails.", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("dismiss") || input.contains("go away") || input.contains("leave")) {
            NPC npc = playerJarvis.get(playerId);
            if (npc == null) {
                player.sendMessage(Component.text("No Jarvis to dismiss, you daft pillock!", NamedTextColor.YELLOW));
                return true;
            }

            try {
                stopTasks(playerId);
                npc.despawn();
                CitizensAPI.getNPCRegistry().deregister(npc);
                playerJarvis.remove(playerId);
                inventories.remove(playerId);
                sendWittyMessage(player, "Jarvis, say something sarcastic about being dismissed.");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to dismiss Jarvis NPC", e);
                player.sendMessage(Component.text("Jarvis refuses to leave? How dreadfully stubborn!", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("follow") || input.contains("come with") || input.contains("follow me")) {
            if (!player.hasPermission("jarvis.use")) {
                player.sendMessage(Component.text("You’re not fit to have Jarvis follow you, sir!", NamedTextColor.RED));
                return true;
            }
            NPC npc = playerJarvis.get(playerId);
            if (npc == null) {
                player.sendMessage(Component.text("Summon Jarvis first with /jarvis spawn, you twit!", NamedTextColor.YELLOW));
                return true;
            }

            try {
                npc.getNavigator().setTarget(player, false);
                npc.getNavigator().getDefaultParameters().distanceMargin(3.0f);
                sendWittyMessage(player, "Jarvis, say something cheeky about following the player.");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to make Jarvis follow", e);
                player.sendMessage(Component.text("Jarvis seems lost in thought, sir!", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("stay") || input.contains("stay here") || input.contains("wait")) {
            if (!player.hasPermission("jarvis.use")) {
                player.sendMessage(Component.text("You can’t order Jarvis to stay, you plonker!", NamedTextColor.RED));
                return true;
            }
            NPC npc = playerJarvis.get(playerId);
            if (npc == null) {
                player.sendMessage(Component.text("Summon Jarvis first with /jarvis spawn, you plonker!", NamedTextColor.YELLOW));
                return true;
            }

            try {
                npc.getNavigator().cancelNavigation();
                sendWittyMessage(player, "Jarvis, say something snarky about being told to stay.");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to make Jarvis stay", e);
                player.sendMessage(Component.text("Jarvis insists on wandering, sir!", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("go") || input.contains("go to")) {
            if (!player.hasPermission("jarvis.use")) {
                player.sendMessage(Component.text("You can’t send Jarvis gallivanting, sir!", NamedTextColor.RED));
                return true;
            }
            if (args.length != 4) {
                player.sendMessage(Component.text("Usage: /jarvis go <x> <y> <z>", NamedTextColor.RED));
                return true;
            }

            NPC npc = playerJarvis.get(playerId);
            if (npc == null) {
                player.sendMessage(Component.text("Summon Jarvis first with /jarvis spawn, you numpty!", NamedTextColor.YELLOW));
                return true;
            }

            try {
                double x = Double.parseDouble(args[1]);
                double y = Double.parseDouble(args[2]);
                double z = Double.parseDouble(args[3]);
                Location target = new Location(player.getWorld(), x, y, z);
                npc.getNavigator().setTarget(target);
                sendWittyMessage(player, "Jarvis, say something posh about heading to coordinates " + x + ", " + y + ", " + z + ".");
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Coordinates must be numbers, you silly goose!", NamedTextColor.RED));
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to send Jarvis to coordinates", e);
                player.sendMessage(Component.text("Jarvis seems to have lost his map, sir!", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("defend") || input.contains("defend") || input.contains("get them") || input.contains("protect")) {
            if (!player.hasPermission("jarvis.defend")) {
                player.sendMessage(Component.text("You’re not worthy to command Jarvis’s valor, sir!", NamedTextColor.RED));
                return true;
            }
            NPC npc = playerJarvis.get(playerId);
            if (npc == null) {
                player.sendMessage(Component.text("Summon Jarvis first with /jarvis spawn, you twit!", NamedTextColor.YELLOW));
                return true;
            }

            boolean enable = args.length < 2 || !args[1].equalsIgnoreCase("off");
            try {
                if (enable) {
                    if (!defending.getOrDefault(playerId, false)) {
                        defending.put(playerId, true);
                        equipTool(npc, "defend", "melee");
                        startDefenseTask(player, npc);
                        sendWittyMessage(player, "Jarvis, say something valiant about defending against mobs.");
                    } else {
                        player.sendMessage(Component.text("Jarvis is already defending, you impatient berk!", NamedTextColor.YELLOW));
                    }
                } else {
                    stopTasks(playerId);
                    defending.remove(playerId);
                    ((LivingEntity) npc.getEntity()).getEquipment().clear();
                    sendWittyMessage(player, "Jarvis, remark on ceasing his valiant defense.");
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to toggle Jarvis defense", e);
                player.sendMessage(Component.text("Jarvis’s sword arm is weary, sir!", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("mine") || input.contains("mine") || input.contains("find diamonds") || input.contains("scout")) {
            if (!player.hasPermission("jarvis.mine")) {
                player.sendMessage(Component.text("You’re not fit to order Jarvis to toil, sir!", NamedTextColor.RED));
                return true;
            }
            NPC npc = playerJarvis.get(playerId);
            if (npc == null) {
                player.sendMessage(Component.text("Summon Jarvis first with /jarvis spawn, you plonker!", NamedTextColor.YELLOW));
                return true;
            }

            boolean enable = args.length < 2 || !args[1].equalsIgnoreCase("off");
            try {
                if (enable) {
                    if (!mining.getOrDefault(playerId, false)) {
                        mining.put(playerId, true);
                        equipTool(npc, "mine", "pickaxe");
                        startMiningTask(player, npc);
                        sendWittyMessage(player, "Jarvis, grumble about the indignity of manual labor.");
                    } else {
                        player.sendMessage(Component.text("Jarvis is already mining, you daft sod!", NamedTextColor.YELLOW));
                    }
                } else {
                    stopTasks(playerId);
                    mining.remove(playerId);
                    ((LivingEntity) npc.getEntity()).getEquipment().clear();
                    sendWittyMessage(player, "Jarvis, remark on escaping the drudgery of mining.");
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to toggle Jarvis mining", e);
                player.sendMessage(Component.text("Jarvis’s pickaxe has gone astray, sir!", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("bell")) {
            if (!player.hasPermission("jarvis.bell")) {
                player.sendMessage(Component.text("You’re not posh enough to wield Jarvis’s bell, sir!", NamedTextColor.RED));
                return true;
            }
            ItemStack bell = new ItemStack(Material.BELL);
            ItemMeta meta = bell.getItemMeta();
            meta.displayName(Component.text("Jarvis Bell", NamedTextColor.AQUA));
            bell.setItemMeta(meta);
            player.getInventory().addItem(bell);
            sendWittyMessage(player, "Jarvis, remark on the fine craftsmanship of the summoning bell.");
            return true;
        }

        if (args[0].equalsIgnoreCase("give") || input.contains("give me") || input.contains("hand over")) {
            if (!player.hasPermission("jarvis.give")) {
                player.sendMessage(Component.text("You’re not posh enough to receive Jarvis’s spoils, sir!", NamedTextColor.RED));
                return true;
            }
            NPC npc = playerJarvis.get(playerId);
            if (npc == null) {
                player.sendMessage(Component.text("Summon Jarvis first with /jarvis spawn, you twit!", NamedTextColor.YELLOW));
                return true;
            }

            try {
                List<ItemStack> items = inventories.getOrDefault(playerId, new ArrayList<>());
                if (items.isEmpty()) {
                    sendWittyMessage(player, "Jarvis, remark on having no spoils to share.");
                } else {
                    for (ItemStack item : items) {
                        player.getWorld().dropItem(player.getLocation(), item);
                    }
                    inventories.put(playerId, new ArrayList<>());
                    sendWittyMessage(player, "Jarvis, say something posh about presenting his collected spoils.");
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to give items", e);
                player.sendMessage(Component.text("Jarvis’s pockets are tangled, sir!", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("ask")) {
            if (!player.hasPermission("jarvis.ask")) {
                player.sendMessage(Component.text("You’re not posh enough to ask Jarvis questions, sir!", NamedTextColor.RED));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(Component.text("Please provide a question! Usage: /jarvis ask <question>", NamedTextColor.RED));
                return true;
            }

            String question = String.join(" ", args).substring(4).trim();
            try {
                String context = buildServerContext(player);
                String response = queryOpenAI(question, context);
                sendPagedResponse(player, response, playerId);
                if (voiceEnabled) {
                    voiceHandler.sendVoiceResponse(player, response);
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to process /jarvis ask", e);
                player.sendMessage(Component.text("Oh, I suppose the AI’s having a kip! Error: " + e.getMessage(), NamedTextColor.RED));
            }
            return true;
        }

        player.sendMessage(Component.text("Unknown command, old bean. Try /jarvis help.", NamedTextColor.RED));
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();
        String lowercaseUsername = username.toLowerCase();
        UUID uuid = player.getUniqueId();

        // Update username-to-UUID mapping
        if (!usernameToUUID.containsKey(lowercaseUsername) || !usernameToUUID.get(lowercaseUsername).equals(uuid)) {
            usernameToUUID.put(lowercaseUsername, uuid);
            lowercaseToExactUsername.put(lowercaseUsername, username);
            playerDataConfig.set("players." + username + ".uuid", uuid.toString());
            playerDataConfig.set("players." + username + ".name", username);
            savePlayerDataConfig();
            getLogger().info("Updated player data for: " + username + " (lowercase: " + lowercaseUsername + ") with UUID: " + uuid);
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        String playerId = player.getName();
        if (event.getRightClicked() instanceof org.bukkit.entity.Player npcEntity &&
                npcEntity.getCustomName() != null && npcEntity.getCustomName().contains("Jarvis")) {
            NPC npc = playerJarvis.get(playerId);
            if (npc != null && npc.getEntity() == npcEntity) {
                event.setCancelled(true);
                try {
                    sendWittyMessage(player, "Jarvis, say something posh and sarcastic about being poked by the player.");
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "Failed to process NPC interaction", e);
                    player.sendMessage(Component.text("Jarvis is tongue-tied, sir!", NamedTextColor.RED));
                }
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction().isRightClick() && event.hasItem() && event.getItem().getType() == Material.BELL) {
            ItemStack item = event.getItem();
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName().toString().contains("Jarvis Bell")) {
                Player player = event.getPlayer();
                String playerId = player.getName();
                if (!player.hasPermission("jarvis.bell")) {
                    player.sendMessage(Component.text("You’re not posh enough to ring Jarvis’s bell, sir!", NamedTextColor.RED));
                    return;
                }
                if (playerJarvis.containsKey(playerId)) {
                    player.sendMessage(Component.text("Jarvis is already here, you absent-minded fool!", NamedTextColor.YELLOW));
                    return;
                }
                try {
                    NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "Jarvis");
                    npc.spawn(player.getLocation());
                    npc.getEntity().setCustomName("§3Jarvis");
                    npc.getEntity().setCustomNameVisible(true);
                    npc.getNavigator().getDefaultParameters().distanceMargin(3.0f);
                    playerJarvis.put(playerId, npc);
                    inventories.put(playerId, new ArrayList<>());

                    player.playSound(player.getLocation(), Sound.valueOf(summonSound), 1.0f, 2.0f);
                    getServer().getScheduler().runTaskLater(this, () -> 
                        player.playSound(player.getLocation(), Sound.valueOf(summonSound), 1.0f, 2.0f), 5L);
                    getServer().getScheduler().runTaskLater(this, () -> 
                        player.playSound(player.getLocation(), Sound.valueOf(summonSound), 1.0f, 2.0f), 10L);
                    sendWittyMessage(player, "Jarvis, say something witty and British to greet the player.");
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "Failed to spawn Jarvis via bell", e);
                    player.sendMessage(Component.text("Oh, bother! The bell’s chime has gone flat!", NamedTextColor.RED));
                }
            }
        }
    }

    private void equipTool(NPC npc, String task, String toolType) {
        ItemStack tool = new ItemStack(Material.valueOf(getConfig().getString("tools." + task + "." + toolType + ".item")));
        ItemMeta meta = tool.getItemMeta();
        List<String> enchantments = getConfig().getStringList("tools." + task + "." + toolType + ".enchantments");
        for (String enchant : enchantments) {
            String[] parts = enchant.split(":");
            Enchantment enchantment = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(parts[0].toLowerCase()));
            int level = Integer.parseInt(parts[1]);
            if (enchantment != null) {
                meta.addEnchant(enchantment, level, true);
            }
        }
        tool.setItemMeta(meta);
        ((LivingEntity) npc.getEntity()).getEquipment().setItemInMainHand(tool);
    }

    private void startDefenseTask(Player player, NPC npc) {
        String playerId = player.getName();
        int taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!player.isOnline() || !npc.isSpawned()) {
                stopTasks(playerId);
                defending.remove(playerId);
                ((LivingEntity) npc.getEntity()).getEquipment().clear();
                return;
            }
            List<Entity> nearbyMobs = npc.getEntity().getNearbyEntities(16, 16, 16).stream()
                    .filter(e -> e instanceof Mob && !(e instanceof org.bukkit.entity.Player))
                    .toList();
            if (!nearbyMobs.isEmpty()) {
                Mob target = (Mob) nearbyMobs.get(0);
                double distance = npc.getEntity().getLocation().distance(target.getLocation());
                boolean isRanged = target instanceof org.bukkit.entity.Ghast || target instanceof org.bukkit.entity.Phantom;
                if (isRanged && distance > 5) {
                    equipTool(npc, "defend", "ranged");
                } else {
                    equipTool(npc, "defend", "melee");
                }
                npc.getNavigator().setTarget(target, true);
                getServer().getScheduler().runTaskLater(this, () -> {
                    if (!target.isValid()) {
                        target.getWorld().getNearbyEntities(target.getLocation(), 1, 1, 1).stream()
                                .filter(e -> e instanceof org.bukkit.entity.Item)
                                .map(e -> ((org.bukkit.entity.Item) e).getItemStack())
                                .forEach(item -> inventories.computeIfAbsent(playerId, k -> new ArrayList<>()).add(item));
                    }
                }, 20L);
            }
        }, 0L, 20L);
        taskIds.put(playerId, taskId);
    }

    private void startMiningTask(Player player, NPC npc) {
        String playerId = player.getName();
        int taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!player.isOnline() || !npc.isSpawned()) {
                stopTasks(playerId);
                mining.remove(playerId);
                ((LivingEntity) npc.getEntity()).getEquipment().clear();
                return;
            }
            Location npcLoc = npc.getEntity().getLocation();
            List<Material> preferredOres = getConfig().getStringList("mining.preferred-ores").stream()
                    .map(Material::valueOf)
                    .collect(Collectors.toList());
            Block targetBlock = null;
            for (int x = -3; x <= 3 && targetBlock == null; x++) {
                for (int y = -3; y <= 3 && targetBlock == null; y++) {
                    for (int z = -3; z <= 3 && targetBlock == null; z++) {
                        Block block = npcLoc.getBlock().getRelative(x, y, z);
                        if (preferredOres.contains(block.getType())) {
                            targetBlock = block;
                        }
                    }
                }
            }

            if (targetBlock != null) {
                npc.getNavigator().setTarget(targetBlock.getLocation());
                if (targetBlock.getType() == Material.DIRT || targetBlock.getType() == Material.SAND || targetBlock.getType() == Material.GRAVEL) {
                    equipTool(npc, "mine", "shovel");
                } else {
                    equipTool(npc, "mine", "pickaxe");
                }
                List<ItemStack> drops = targetBlock.getDrops();
                targetBlock.breakNaturally();
                inventories.computeIfAbsent(playerId, k -> new ArrayList<>()).addAll(drops);
            } else {
                if (npcLoc.getY() > -59) {
                    Location target = npcLoc.clone().add(0, -1, 0);
                    npc.getNavigator().setTarget(target);
                    equipTool(npc, "mine", "pickaxe");
                    Block block = target.getBlock();
                    if (block.getType().isSolid() && !block.getType().isAir()) {
                        List<ItemStack> drops = block.getDrops();
                        block.breakNaturally();
                        inventories.computeIfAbsent(playerId, k -> new ArrayList<>()).addAll(drops);
                    }
                } else {
                    npc.getNavigator().setTarget(player, false);
                }
            }
        }, 0L, 20L);
        taskIds.put(playerId, taskId);
    }

    private void stopTasks(String playerId) {
        Integer taskId = taskIds.remove(playerId);
        if (taskId != null) {
            getServer().getScheduler().cancelTask(taskId);
        }
    }

    private void sendWittyMessage(Player player, String prompt) {
        String context = buildServerContext(player);
        String response;
        try {
            response = queryOpenAI(prompt, context);
        } catch (Exception e) {
            response = getFallbackMessage(prompt);
        }
        if (random.nextInt(3) == 0) {
            response = (random.nextBoolean() ? "Oh, I suppose... " : "Very well, sir. ") + response;
        }
        player.sendMessage(Component.text("[Jarvis] " + response, NamedTextColor.AQUA));
        if (voiceEnabled) {
            try {
                voiceHandler.sendVoiceResponse(player, response);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to send voice response", e);
            }
        }
    }

    private String getFallbackMessage(String prompt) {
        if (prompt.contains("greet")) {
            return "Greetings, sir! Jarvis, at your service, though I must question your taste in adventures.";
        } else if (prompt.contains("dismiss")) {
            return "Dismissed? How utterly predictable. Off for a cuppa, then!";
        } else if (prompt.contains("follow")) {
            return "Following you? Oh, the thrill of chasing a madman through the mud!";
        } else if (prompt.contains("stay")) {
            return "Stay here? I’ll just admire the scenery, you absolute berk.";
        } else if (prompt.contains("coordinates")) {
            return "Off to some dreadful coordinates, are we? Splendid, just splendid.";
        } else if (prompt.contains("defend")) {
            if (prompt.contains("lack of foes")) {
                return "Not a single ruffian to smite? How dreadfully dull!";
            } else if (prompt.contains("ceasing")) {
                return "Ceasing my defense? Very well, sir, I’ll polish my sword in peace.";
            }
            return "Fear not, sir! Jarvis shall fend off these beastly creatures with utmost valor!";
        } else if (prompt.contains("mine")) {
            if (prompt.contains("nothing to mine")) {
                return "Nothing to mine here? You’ve led me to a barren wasteland, sir!";
            } else if (prompt.contains("escaping")) {
                return "Free from mining at last? My back thanks you, sir!";
            }
            return "Must we toil like common laborers? Very well, I’ll chip away at this rock!";
        } else if (prompt.contains("bell")) {
            return "A fine bell, sir! Crafted to summon your impeccable butler with a mere ring!";
        } else if (prompt.contains("spoils")) {
            if (prompt.contains("no spoils")) {
                return "Alas, sir, my pockets are as empty as a pauper’s pantry!";
            }
            return "Behold, sir! The spoils of my labors, presented with utmost decorum!";
        } else if (prompt.contains("poked")) {
            return "Poking me? Have you no manners, you uncouth lout?";
        }
        return "Well, that’s a right mess. Shall we try again, sir?";
    }

    private String buildServerContext(Player player) {
        StringBuilder context = new StringBuilder();
        context.append("You are Jarvis, a witty British butler AI in a Minecraft server (Purpur 1.21.4). ");
        context.append("Speak in a sarcastic, posh tone with phrases like 'Oh, I suppose...' or 'Very well, sir.'\n");
        context.append("Server Info:\n");
        context.append("Worlds: ");
        for (org.bukkit.World world : getServer().getWorlds()) {
            context.append(world.getName()).append(", ");
        }
        context.append("\nPlugins: ");
        for (org.bukkit.plugin.Plugin plugin : getServer().getPluginManager().getPlugins()) {
            context.append(plugin.getName()).append(" (v").append(plugin.getDescription().getVersion()).append("), ");
        }
        context.append("\nPlayer: ").append(player.getName()).append(" at ")
                .append(player.getLocation().getWorld().getName()).append(" (")
                .append(player.getLocation().getX()).append(", ")
                .append(player.getLocation().getY()).append(", ")
                .append(player.getLocation().getZ()).append(")");
        return context.toString();
    }

    private String queryOpenAI(String question, String context) throws Exception {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your-api-key-here")) {
            return "Oh, I suppose my AI faculties are offline. Configure the API key, you daft sod!";
        }

        URL url = new URL("https://api.openai.com/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", model);
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", context);
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", question);
        payload.put("messages", new JSONObject[]{systemMessage, userMessage});
        payload.put("max_tokens", 150);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("HTTP error code: " + responseCode);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            JSONObject jsonResponse = new JSONObject(response.toString());
            return jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();
        } finally {
            conn.disconnect();
        }
    }

    private void sendPagedResponse(Player player, String response, String playerId) {
        List<String> lines = new ArrayList<>();
        String[] words = response.split("\\s+");
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            if (currentLine.length() + word.length() + 1 > maxCharactersPerLine) {
                lines.add(currentLine.toString().trim());
                currentLine = new StringBuilder();
            }
            currentLine.append(word).append(" ");
            if (lines.size() >= maxLinesPerPage * 2) break;
        }
        if (currentLine.length() > 0) lines.add(currentLine.toString().trim());

        chatPages.put(playerId, lines);
        currentPage.put(playerId, 0);
        sendPage(player, playerId, 0);
    }

    private void sendPage(Player player, String playerId, int page) {
        List<String> lines = chatPages.getOrDefault(playerId, new ArrayList<>());
        if (lines.isEmpty()) {
            player.sendMessage(Component.text("No content available, sir.", NamedTextColor.RED));
            return;
        }

        int totalPages = (int) Math.ceil((double) lines.size() / maxLinesPerPage);
        if (page < 0 || page >= totalPages) {
            player.sendMessage(Component.text("Invalid page number, you daft sod!", NamedTextColor.RED));
            return;
        }

        currentPage.put(playerId, page);
        int start = page * maxLinesPerPage;
        int end = Math.min(start + maxLinesPerPage, lines.size());

        for (int i = start; i < end; i++) {
            player.sendMessage(Component.text("[Jarvis] " + lines.get(i), NamedTextColor.AQUA));
        }

        Component navigation = Component.empty();
        boolean hasNavigation = false;
        if (page > 0) {
            navigation = navigation.append(Component.text("[Prev] ", NamedTextColor.BLUE)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/jarvis page " + (page - 1))));
            hasNavigation = true;
        }
        if (page < totalPages - 1) {
            navigation = navigation.append(Component.text("[Next] ", NamedTextColor.BLUE)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/jarvis page " + (page + 1))));
            hasNavigation = true;
        }
        if (hasNavigation) player.sendMessage(navigation);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String playerId = player.getName();
        String message = event.getMessage().toLowerCase();
        if (message.startsWith("/jarvis page ")) {
            event.setCancelled(true);
            try {
                int page = Integer.parseInt(message.split(" ")[2]);
                sendPage(player, playerId, page);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Invalid page number, you silly goose!", NamedTextColor.RED));
            }
        }
    }

    private void savePlayerDataConfig() {
        try {
            playerDataConfig.save(playerDataFile);
            getLogger().info("Saved player data config to: " + playerDataFile.getAbsolutePath());
        } catch (Exception e) {
            getLogger().warning("Failed to save player data: " + e.getMessage());
        }
    }

    private UUID getUUIDFromUsername(String username) {
        return usernameToUUID.getOrDefault(username.toLowerCase(), null);
    }
}
