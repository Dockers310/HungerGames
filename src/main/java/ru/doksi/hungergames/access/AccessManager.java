package ru.doksi.hungergames.access;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.doksi.hungergames.HungerGamesPlugin;
import ru.doksi.hungergames.util.SafeFiles;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AccessManager {
    /**
     * Полный доступ к плагину без записи в access.yml. По умолчанию есть у OP;
     * остальным выдаётся плагином прав (LuckPerms). Кроме того, полный доступ дают ники
     * из access.creator-names в config.yml (по умолчанию Dok_Si — автор плагина).
     */
    public static final String ADMIN_PERMISSION = "hungergames.admin";

    private final HungerGamesPlugin plugin;
    private final Map<UUID, String> granted = new ConcurrentHashMap<>();
    private File file;

    public AccessManager(HungerGamesPlugin plugin) { this.plugin = plugin; }

    public boolean hasAccess(OfflinePlayer player) {
        if (player == null) return false;
        if (player.isOp()) return true;
        if (isCreator(player)) return true;
        if (player instanceof Player online && online.hasPermission(ADMIN_PERMISSION)) return true;
        return granted.containsKey(player.getUniqueId());
    }

    /**
     * Полный доступ по нику из access.creator-names (по умолчанию Dok_Si). Ник сравнивается
     * с учётом регистра: на сервере без лицензионной авторизации «dok_si» — другой аккаунт.
     */
    public boolean isCreator(OfflinePlayer player) {
        return player != null && isCreatorName(player.getName());
    }

    public boolean isCreatorName(String name) {
        if (name == null || name.isBlank()) return false;
        for (String creator : creatorNames()) if (name.equals(creator)) return true;
        return false;
    }

    /** Ники из access.creator-names. Если ключа в config.yml нет, берётся список по умолчанию из плагина. */
    public List<String> creatorNames() {
        List<String> names = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("access.creator-names")) {
            if (raw != null && !raw.isBlank()) names.add(raw.trim());
        }
        return names;
    }

    public boolean hasAccess(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        return hasAccess(online != null ? online : Bukkit.getOfflinePlayer(uuid));
    }

    /** OP, ник из access.creator-names или право {@link #ADMIN_PERMISSION}. Доступ из access.yml сюда не входит. */
    public boolean isAdmin(Player player) {
        return player != null && (player.isOp() || isCreator(player) || player.hasPermission(ADMIN_PERMISSION));
    }

    public Map<UUID, String> granted() { return Collections.unmodifiableMap(granted); }

    public boolean add(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        granted.put(player.getUniqueId(), player.getName() == null ? playerName : player.getName());
        save();
        return true;
    }

    public boolean remove(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
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

    public synchronized void load() {
        granted.clear();
        file = new File(plugin.getDataFolder(), "access.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try { granted.put(UUID.fromString(key), section.getString(key, key)); } catch (IllegalArgumentException ignored) { }
        }
    }

    public synchronized void save() {
        if (file == null) file = new File(plugin.getDataFolder(), "access.yml");
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : granted.entrySet()) config.set("players." + e.getKey(), e.getValue());
        try { SafeFiles.saveYaml(config, file); } catch (IOException e) { plugin.getLogger().severe("Could not save access.yml: " + e.getMessage()); }
    }
}
