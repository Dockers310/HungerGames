package ru.doksi.hungergames.access;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.doksi.hungergames.HungerGamesPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AccessManager {
    private final HungerGamesPlugin plugin;
    private final Map<UUID, String> granted = new ConcurrentHashMap<>();
    private File file;
    private YamlConfiguration config;

    public AccessManager(HungerGamesPlugin plugin) { this.plugin = plugin; }

    public boolean hasAccess(OfflinePlayer player) {
        if (player == null) return false;
        if (isCreator(player)) return true;
        if (player.isOp()) return true;
        return granted.containsKey(player.getUniqueId());
    }

    public boolean hasAccess(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return hasAccess(player);
    }

    public boolean isCreator(OfflinePlayer player) {
        return player != null && player.getName() != null && player.getName().equalsIgnoreCase("Dok_Si");
    }

    public Map<UUID, String> granted() { return Collections.unmodifiableMap(granted); }

    public boolean add(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        if (isCreator(player)) return false;
        granted.put(player.getUniqueId(), player.getName() == null ? playerName : player.getName());
        save();
        return true;
    }

    public boolean remove(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        if (isCreator(player)) return false;
        boolean removed = granted.remove(player.getUniqueId()) != null;
        if (removed) save();
        return removed;
    }

    public String displayName(UUID uuid) {
        String name = granted.get(uuid);
        if (name != null) return name;
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() == null ? uuid.toString().substring(0, 8) : player.getName();
    }

    public void load() {
        granted.clear();
        file = new File(plugin.getDataFolder(), "access.yml");
        config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try { granted.put(UUID.fromString(key), section.getString(key, key)); } catch (IllegalArgumentException ignored) { }
        }
    }

    public void save() {
        if (config == null) config = new YamlConfiguration();
        config.set("players", null);
        for (Map.Entry<UUID, String> e : granted.entrySet()) config.set("players." + e.getKey(), e.getValue());
        try { config.save(file); } catch (IOException e) { plugin.getLogger().severe("Could not save access.yml: " + e.getMessage()); }
    }
}
