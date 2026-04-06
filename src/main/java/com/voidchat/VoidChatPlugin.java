package com.voidchat;

import org.bstats.bukkit.Metrics;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class VoidChatPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final Set<UUID> mutedGlobalChat = ConcurrentHashMap.newKeySet();
    private final Set<UUID> adminSpyEnabled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> playerGlobalMessageCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerBlocksRemaining = new ConcurrentHashMap<>();

    private long globalMessageCooldown;
    private double localChatRange;
    private int blocksRequired;
    private String prefix;
    private boolean chatRequirementEnabled;
    private String globalFormat;
    private String localFormat;
    
    private boolean chatEnabled = true;
    private List<String> bannedWords = new ArrayList<>();

    private String latestVersion = null;
    private String downloadUrl = "";
    private boolean isImportant = false;

    private File dataFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.json");
        loadPlayerData();

        if (getCommand("voidchat") != null) {
            getCommand("voidchat").setExecutor(this);
            getCommand("voidchat").setTabCompleter(new CommandTabCompleter());
        }

        int pluginId = 30607;
        new Metrics(this, pluginId);

        if (getConfig().getBoolean("update-check", true)) {
            checkUpdate();
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("VoidChat has been enabled!");
    }

    private void loadConfiguration() {
        reloadConfig();
        globalMessageCooldown = getConfig().getLong("global-message-cooldown", 30) * 1000;
        localChatRange = getConfig().getDouble("local-chat-range", 100.0);
        blocksRequired = getConfig().getInt("blocks-required", 10);
        chatRequirementEnabled = getConfig().getBoolean("chat-requirement-enabled", true);
        globalFormat = getConfig().getString("global-format", "&a&lGLOBAL &r&8»&7 {player}: {message}");
        localFormat = getConfig().getString("local-format", "&e&lLOCAL &r&8»&7 {player}: {message}");
        prefix = colorize(getConfig().getString("messages.prefix", "&b&lVoidChat &r&8»&7 "), null);
        bannedWords = getConfig().getStringList("anti-swear.banned-words");
    }

    public void checkUpdate() {
        String url = "https://raw.githubusercontent.com/kuba-121/VoidChat/refs/heads/main/version.json";

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (InputStreamReader reader = new InputStreamReader(new URL(url).openStream())) {
                JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
                
                this.latestVersion = json.get("version").getAsString();
                this.downloadUrl = json.get("url").getAsString();
                this.isImportant = json.get("important").getAsBoolean();
                
                String currentVersion = getDescription().getVersion();

                if (!currentVersion.equals(latestVersion)) {
                    getLogger().warning("A new version of VoidChat (v" + latestVersion + ") is available!");
                    getLogger().warning("Download here: " + downloadUrl);
                }
            } catch (Exception ignored) {} 
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playerBlocksRemaining.putIfAbsent(player.getUniqueId(), blocksRequired);

        if (latestVersion != null && isImportant && !getDescription().getVersion().equals(latestVersion)) {
            if (player.hasPermission("voidchat.admin")) {
                player.sendMessage(prefix + " §c§lNew important update! §f(v" + latestVersion + ")\n§7Download: §n" + downloadUrl);
            }
        }
    }

    private String filterSwearWords(String message) {
        String filtered = message;
        for (String word : bannedWords) {
            filtered = filtered.replaceAll("(?i)" + Pattern.quote(word), "****");
        }
        return filtered;
    }

    private String colorize(String message, Player player) {
        if (message == null) return "";
        if (player != null && !player.hasPermission("voidchat.color")) return message;

        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(sb, ChatColor.COLOR_CHAR + "x" +
                    ChatColor.COLOR_CHAR + hex.charAt(0) + ChatColor.COLOR_CHAR + hex.charAt(1) +
                    ChatColor.COLOR_CHAR + hex.charAt(2) + ChatColor.COLOR_CHAR + hex.charAt(3) +
                    ChatColor.COLOR_CHAR + hex.charAt(4) + ChatColor.COLOR_CHAR + hex.charAt(5));
        }
        matcher.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private String getMessage(String key) {
        String raw = getConfig().getString("messages." + key.replace("_", "-"));
        if (raw == null) return ChatColor.RED + "Missing key: " + key;
        return colorize(raw.replace("%prefix%", getConfig().getString("messages.prefix", "&b&lVoidChat &r&8»&7 ")), null);
    }

    private String getFormattedMessage(String format, Player player, String message) {
        String processedMessage = filterSwearWords(colorize(message, player));
        String result = format.replace("{player}", player != null ? player.getName() : "Console")
                              .replace("{message}", processedMessage);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && player != null) {
            result = PlaceholderAPI.setPlaceholders(player, result);
        }
        return colorize(result, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!chatEnabled && !player.hasPermission("voidchat.admin")) {
            event.setCancelled(true);
            player.sendMessage(getMessage("chat-disabled-error"));
            return;
        }

        UUID uuid = player.getUniqueId();
        int blocksRemaining = playerBlocksRemaining.getOrDefault(uuid, blocksRequired);
        if (chatRequirementEnabled && blocksRemaining > 0 && !player.hasPermission("voidchat.bypass")) {
            event.setCancelled(true);
            player.sendMessage(getMessage("requirement-not-met").replace("{blocks_required}", String.valueOf(blocksRemaining)));
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();
        if (message.startsWith("!")) {
            handleGlobalChat(player, message.substring(1));
        } else {
            handleLocalChat(player, message);
        }
    }

    private void handleGlobalChat(Player player, String message) {
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            player.sendMessage(getMessage("usage-voidchat"));
            return;
        }

        if (mutedGlobalChat.contains(player.getUniqueId())) {
            player.sendMessage(getMessage("global-muted-error"));
            return;
        }

        if (!player.hasPermission("voidchat.globalbypass")) {
            long last = playerGlobalMessageCooldown.getOrDefault(player.getUniqueId(), 0L);
            long now = System.currentTimeMillis();
            if (now - last < globalMessageCooldown) {
                player.sendMessage(getMessage("global-cooldown").replace("{time_remaining}", String.valueOf((globalMessageCooldown - (now - last)) / 1000)));
                return;
            }
            playerGlobalMessageCooldown.put(player.getUniqueId(), now);
        }

        String formatted = getFormattedMessage(globalFormat, player, trimmed);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!mutedGlobalChat.contains(p.getUniqueId())) {
                p.sendMessage(formatted);
            }
        }
        Bukkit.getConsoleSender().sendMessage(formatted);
    }

    private void handleLocalChat(Player player, String message) {
        String formatted = getFormattedMessage(localFormat, player, message);
        boolean foundRecipient = false;
        boolean isInfinite = localChatRange == -1;
        double rangeSq = localChatRange * localChatRange;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getWorld().getName().equals(player.getWorld().getName())) {
                if (adminSpyEnabled.contains(online.getUniqueId())) online.sendMessage(formatted);
                continue;
            }
            
            try {
                if (isInfinite) {
                    online.sendMessage(formatted);
                    if (!online.equals(player)) foundRecipient = true;
                } else {
                    double distSq = online.getLocation().distanceSquared(player.getLocation());
                    if (distSq <= rangeSq || adminSpyEnabled.contains(online.getUniqueId())) {
                        online.sendMessage(formatted);
                        if (distSq <= rangeSq && !online.equals(player)) foundRecipient = true;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!isInfinite && !foundRecipient && !adminSpyEnabled.contains(player.getUniqueId())) {
            player.sendMessage(getMessage("no-one-in-range"));
        }
        Bukkit.getConsoleSender().sendMessage(formatted);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        playerBlocksRemaining.computeIfPresent(event.getPlayer().getUniqueId(), (k, v) -> Math.max(0, v - 1));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerGlobalMessageCooldown.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (label.equalsIgnoreCase("global") || label.equalsIgnoreCase("g")) {
            if (!(sender instanceof Player)) return onlyPlayers(sender);
            if (args.length == 0) {
                sender.sendMessage(getMessage("usage-voidchat"));
                return true;
            }
            handleGlobalChat((Player) sender, String.join(" ", args));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            for (String line : getConfig().getStringList("messages.help-menu")) {
                sender.sendMessage(colorize(line.replace("%prefix%", getConfig().getString("messages.prefix", "&b&lVoidChat &r&8»&7 ")), null));
            }
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("voidchat.reload")) return noPerm(sender);
                loadConfiguration();
                sender.sendMessage(getMessage("reload-success"));
                break;
            case "admin":
                if (!(sender instanceof Player)) return onlyPlayers(sender);
                if (!sender.hasPermission("voidchat.admin")) return noPerm(sender);
                UUID adminUuid = ((Player) sender).getUniqueId();
                if (adminSpyEnabled.remove(adminUuid)) {
                    sender.sendMessage(getMessage("admin-disabled"));
                } else {
                    adminSpyEnabled.add(adminUuid);
                    sender.sendMessage(getMessage("admin-enabled"));
                }
                break;
            case "global":
                if (!(sender instanceof Player)) return onlyPlayers(sender);
                if (args.length < 2) {
                    sender.sendMessage(getMessage("usage-voidchat"));
                    return true;
                }
                String msg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                handleGlobalChat((Player) sender, msg);
                break;
            case "clear":
                if (!sender.hasPermission("voidchat.admin")) return noPerm(sender);
                for (int i = 0; i < 100; i++) Bukkit.broadcastMessage("");
                Bukkit.broadcastMessage(getMessage("chat-cleared").replace("{player}", sender.getName()));
                break;
            case "toggle":
                if (!sender.hasPermission("voidchat.admin")) return noPerm(sender);
                chatEnabled = !chatEnabled;
                Bukkit.broadcastMessage(chatEnabled ? getMessage("chat-toggled-on") : getMessage("chat-toggled-off"));
                break;
            default:
                sender.sendMessage(getMessage("usage-voidchat"));
                break;
        }
        return true;
    }

    private boolean noPerm(CommandSender s) { s.sendMessage(getMessage("no-permission")); return true; }
    private boolean onlyPlayers(CommandSender s) { s.sendMessage(getMessage("only-players")); return true; }

    private void loadPlayerData() {
        if (dataFile == null || !dataFile.exists()) return;
        try (FileReader reader = new FileReader(dataFile)) {
            Type type = new TypeToken<Map<UUID, Integer>>() {}.getType();
            Map<UUID, Integer> data = gson.fromJson(reader, type);
            if (data != null) playerBlocksRemaining.putAll(data);
        } catch (IOException ignored) {}
    }

    private void savePlayerData() {
        if (dataFile == null) return;
        try (FileWriter writer = new FileWriter(dataFile)) {
            gson.toJson(playerBlocksRemaining, writer);
        } catch (IOException ignored) {}
    }

    @Override
    public void onDisable() {
        savePlayerData();
    }

    private class CommandTabCompleter implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
            if (label.equalsIgnoreCase("global") || label.equalsIgnoreCase("g")) return Collections.emptyList();
            if (args.length == 1) {
                return Arrays.asList("reload", "admin", "help", "global", "clear", "toggle").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }
}