package ru.doksi.hungergames.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.doksi.hungergames.HungerGamesPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ArenaManager {
    private final HungerGamesPlugin plugin;
    private final Map<String, Arena> arenas = new ConcurrentHashMap<>();
    private File file;
    private FileConfiguration config;

    public ArenaManager(HungerGamesPlugin plugin) { this.plugin = plugin; }

    public void load() {
        arenas.clear();
        file = new File(plugin.getDataFolder(), "arenas.yml");
        config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("arenas");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) continue;
            UUID worldUid = parseUuid(sec.getString("world-uid"));
            String worldName = sec.getString("world", "world");
            Arena arena = new Arena(id, worldName, worldUid);
            World world = worldUid == null ? Bukkit.getWorld(worldName) : Bukkit.getWorld(worldUid);
            if (world != null) arena.setWorld(world);
            arena.setMinPlayers(sec.getInt("min-players", arena.minPlayers()));
            arena.setMaxPlayers(sec.getInt("max-players", arena.maxPlayers()));
            arena.settings().maxPlayersBySpawns = sec.getBoolean("settings.max-players-by-spawns", arena.settings().maxPlayersBySpawns);
            arena.settings().protectionSeconds = sec.getLong("settings.protection-seconds", arena.settings().protectionSeconds);
            arena.settings().chestRefillSeconds = sec.getLong("settings.chest-refill-seconds", arena.settings().chestRefillSeconds);
            arena.settings().shrinkStartDelaySeconds = sec.getLong("settings.shrink-start-delay-seconds", arena.settings().shrinkStartDelaySeconds);
            arena.settings().shrinkStageDurationSeconds = sec.getLong("settings.shrink-stage-duration-seconds", arena.settings().shrinkStageDurationSeconds);
            arena.settings().shrinkStages = sec.getInt("settings.shrink-stages", arena.settings().shrinkStages);
            arena.settings().shrinkFinalRatio = sec.getDouble("settings.shrink-final-ratio", arena.settings().shrinkFinalRatio);
            arena.settings().borderDamageHeartMultiplier = sec.getDouble("settings.border-damage-heart-multiplier", arena.settings().borderDamageHeartMultiplier);
            String borderMode = sec.getString("settings.border-mode", "DAMAGE");
            try { arena.setBorderMode(BorderMode.valueOf(borderMode.toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException ignored) { arena.setBorderMode(BorderMode.DAMAGE); }
            try { arena.settings().changingStartMode = BorderMode.valueOf(sec.getString("settings.border-changing-start-mode", "SOFT_TELEPORT").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { arena.settings().changingStartMode = BorderMode.SOFT_TELEPORT; }
            try { arena.settings().changingTargetMode = BorderMode.valueOf(sec.getString("settings.border-changing-target-mode", "DAMAGE").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { arena.settings().changingTargetMode = BorderMode.DAMAGE; }
            if (arena.settings().changingStartMode == BorderMode.CHANGING) arena.settings().changingStartMode = BorderMode.SOFT_TELEPORT;
            if (arena.settings().changingTargetMode == BorderMode.CHANGING) arena.settings().changingTargetMode = BorderMode.DAMAGE;
            arena.settings().borderChangeAfterSeconds = sec.getLong("settings.border-change-after-seconds", arena.settings().borderChangeAfterSeconds);
            arena.settings().borderChangeTransitionSeconds = sec.getLong("settings.border-change-transition-seconds", arena.settings().borderChangeTransitionSeconds);
            arena.settings().countdownSeconds = sec.getInt("settings.countdown-seconds", arena.settings().countdownSeconds);
            arena.settings().lobbyTeleportOnJoin = sec.getBoolean("settings.lobby-teleport-on-join", arena.settings().lobbyTeleportOnJoin);
            arena.setCollecting(false);
            arena.setState(ArenaState.WAITING);
            for (String rawUuid : sec.getStringList("participants")) {
                try { arena.participants().add(UUID.fromString(rawUuid)); } catch (IllegalArgumentException ignored) { }
            }
            arena.mutableSpawns().clear();
            if (world != null) {
                Location p1 = readLocation(sec.getConfigurationSection("point1"), world);
                Location p2 = readLocation(sec.getConfigurationSection("point2"), world);
                Location lobby = readLocation(sec.getConfigurationSection("lobby"), world);
                if (p1 != null) arena.setPos1(p1);
                if (p2 != null) arena.setPos2(p2);
                if (lobby != null) arena.setLobby(lobby);
                List<Map<?, ?>> spawnMaps = sec.getMapList("spawns");
                for (Map<?, ?> map : spawnMaps) arena.mutableSpawns().add(readLocationMap(map, world));
                arena.prepareShrink();
            }
            ConfigurationSection slots = sec.getConfigurationSection("spawn-slots");
            if (slots != null) {
                for (String rawUuid : slots.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(rawUuid);
                        arena.assignSpawnSlot(uuid, slots.getInt(rawUuid, -1));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            arena.clearMissingSpawnSlots();
            arena.spawnSlots().keySet().removeIf(uuid -> !arena.participants().contains(uuid));
            arenas.put(id.toLowerCase(Locale.ROOT), arena);
        }
    }

    public void save() {
        if (config == null) config = new YamlConfiguration();
        config.set("arenas", null);
        for (Arena arena : arenas.values()) {
            String base = "arenas." + arena.id();
            config.set(base + ".world", arena.worldName());
            if (arena.worldUid() != null) config.set(base + ".world-uid", arena.worldUid().toString());
            saveLocation(base + ".point1", arena.pos1());
            saveLocation(base + ".point2", arena.pos2());
            saveLocation(base + ".lobby", arena.lobby());
            List<Map<String, Object>> spawns = new ArrayList<>();
            for (Location loc : arena.spawns()) spawns.add(locationMap(loc));
            config.set(base + ".spawns", spawns);
            // Save configured values, not effective spawn-based values, so switching
            // between manual and spawn-based capacity does not destroy the manual setup.
            config.set(base + ".min-players", arena.settings().minPlayers);
            config.set(base + ".max-players", arena.settings().maxPlayers);
            List<String> participants = arena.participants().stream().map(UUID::toString).toList();
            config.set(base + ".participants", participants);
            config.set(base + ".spawn-slots", null);
            for (Map.Entry<UUID, Integer> slot : arena.spawnSlots().entrySet()) {
                config.set(base + ".spawn-slots." + slot.getKey(), slot.getValue());
            }
            config.set(base + ".settings.protection-seconds", arena.settings().protectionSeconds);
            config.set(base + ".settings.chest-refill-seconds", arena.settings().chestRefillSeconds);
            config.set(base + ".settings.shrink-start-delay-seconds", arena.settings().shrinkStartDelaySeconds);
            config.set(base + ".settings.shrink-stage-duration-seconds", arena.settings().shrinkStageDurationSeconds);
            config.set(base + ".settings.shrink-stages", arena.settings().shrinkStages);
            config.set(base + ".settings.shrink-final-ratio", arena.settings().shrinkFinalRatio);
            config.set(base + ".settings.border-damage-heart-multiplier", arena.settings().borderDamageHeartMultiplier);
            config.set(base + ".settings.border-mode", arena.settings().borderMode.name());
            config.set(base + ".settings.border-changing-start-mode", arena.settings().changingStartMode.name());
            config.set(base + ".settings.border-changing-target-mode", arena.settings().changingTargetMode.name());
            config.set(base + ".settings.border-change-after-seconds", arena.settings().borderChangeAfterSeconds);
            config.set(base + ".settings.border-change-transition-seconds", arena.settings().borderChangeTransitionSeconds);
            config.set(base + ".settings.countdown-seconds", arena.settings().countdownSeconds);
            config.set(base + ".settings.lobby-teleport-on-join", arena.settings().lobbyTeleportOnJoin);
            config.set(base + ".settings.max-players-by-spawns", arena.settings().maxPlayersBySpawns);
        }
        try { config.save(file); } catch (IOException e) { plugin.getLogger().severe("Could not save arenas.yml: " + e.getMessage()); }
    }

    public Arena create(String id, World world) {
        String key = id.toLowerCase(Locale.ROOT);
        if (arenas.containsKey(key)) return null;
        Arena arena = new Arena(id, world.getName(), world.getUID());
        arenas.put(key, arena);
        save();
        return arena;
    }

    public boolean delete(String id) {
        Arena arena = get(id);
        if (arena == null) return false;
        arenas.remove(arena.id().toLowerCase(Locale.ROOT));
        save();
        return true;
    }

    public Arena get(String id) { return id == null ? null : arenas.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<Arena> all() { return List.copyOf(arenas.values()); }
    public Arena first() { return arenas.values().stream().findFirst().orElse(null); }

    private UUID parseUuid(String text) { try { return text == null ? null : UUID.fromString(text); } catch (IllegalArgumentException e) { return null; } }

    private Location readLocation(ConfigurationSection sec, World world) {
        if (sec == null) return null;
        return new Location(world, sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"), (float) sec.getDouble("yaw", 0), (float) sec.getDouble("pitch", 0));
    }
    private Location readLocationMap(Map<?, ?> map, World world) {
        double x = number(map.get("x"), 0).doubleValue();
        double y = number(map.get("y"), 0).doubleValue();
        double z = number(map.get("z"), 0).doubleValue();
        float yaw = number(map.get("yaw"), 0).floatValue();
        float pitch = number(map.get("pitch"), 0).floatValue();
        return new Location(world, x, y, z, yaw, pitch);
    }
    private Number number(Object value, Number fallback) { return value instanceof Number n ? n : fallback; }
    private void saveLocation(String path, Location loc) {
        config.set(path, null);
        if (loc == null) return;
        config.set(path + ".x", loc.getX()); config.set(path + ".y", loc.getY()); config.set(path + ".z", loc.getZ());
        config.set(path + ".yaw", loc.getYaw()); config.set(path + ".pitch", loc.getPitch());
    }
    private Map<String, Object> locationMap(Location loc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", loc.getX()); map.put("y", loc.getY()); map.put("z", loc.getZ());
        map.put("yaw", loc.getYaw()); map.put("pitch", loc.getPitch());
        return map;
    }
}
