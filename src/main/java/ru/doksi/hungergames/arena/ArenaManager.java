package ru.doksi.hungergames.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.doksi.hungergames.HungerGamesPlugin;
import ru.doksi.hungergames.util.SafeFiles;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ArenaManager {
    private static final String[] LOCATION_KEYS = {"point1", "point2", "lobby"};

    private final HungerGamesPlugin plugin;
    private volatile Map<String, Arena> arenas = new ConcurrentHashMap<>();
    // Арены, мир которых не найден при загрузке. Их точки хранятся в том виде, в каком лежали
    // в arenas.yml, и записываются обратно без изменений, пока мир не появится.
    private final Map<String, ConfigurationSection> unresolved = new ConcurrentHashMap<>();
    private File file;

    public ArenaManager(HungerGamesPlugin plugin) { this.plugin = plugin; }

    public synchronized void load() {
        if (file == null) file = new File(plugin.getDataFolder(), "arenas.yml");
        YamlConfiguration config = read(file);
        Map<String, Arena> loaded = new ConcurrentHashMap<>();
        unresolved.clear();
        ConfigurationSection root = config.getConfigurationSection("arenas");
        if (root == null) { arenas = loaded; return; }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) continue;
            String key = id.toLowerCase(Locale.ROOT);
            UUID worldUid = parseUuid(sec.getString("world-uid"));
            String worldName = sec.getString("world", "world");
            Arena arena = new Arena(id, worldName, worldUid);
            World world = findWorld(worldUid, worldName);
            if (world != null) {
                if (worldUid != null && !worldUid.equals(world.getUID())) {
                    plugin.getLogger().info("Арена " + id + ": мир " + worldName + " найден по имени, его UID изменился. Разметка арены сохранена.");
                }
                arena.setWorld(world);
            }
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
            arena.setContainersDirty(sec.getBoolean("containers-dirty", false));
            // Пометка означает, что сервер остановился во время матча. Состав того матча
            // недействителен: вещи игроков вернутся из папки restore, а запись начнётся заново.
            boolean matchInterrupted = arena.containersDirty();
            for (String rawUuid : matchInterrupted ? List.<String>of() : sec.getStringList("participants")) {
                try { arena.participants().add(UUID.fromString(rawUuid)); } catch (IllegalArgumentException ignored) { }
            }
            arena.mutableSpawns().clear();
            if (world != null) {
                readLocations(arena, sec, world);
            } else {
                unresolved.put(key, sec);
                plugin.getLogger().warning("Арена " + id + ": мир " + worldName + " не загружен. Границы и точки появления"
                        + " сохранены в arenas.yml и подключатся, когда мир загрузится.");
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
            loaded.put(key, arena);
        }
        arenas = loaded;
    }

    public synchronized void save() {
        if (file == null) file = new File(plugin.getDataFolder(), "arenas.yml");
        YamlConfiguration config = new YamlConfiguration();
        for (Arena arena : arenas.values()) {
            String key = arena.id().toLowerCase(Locale.ROOT);
            String base = "arenas." + arena.id();
            config.set(base + ".world", arena.worldName());
            if (arena.worldUid() != null) config.set(base + ".world-uid", arena.worldUid().toString());
            ConfigurationSection raw = unresolved.get(key);
            if (raw != null && hasNoLocations(arena)) {
                // Мир арены не загружен: записываем точки ровно такими, какими они были в файле.
                for (String name : LOCATION_KEYS) {
                    ConfigurationSection location = raw.getConfigurationSection(name);
                    if (location != null) config.createSection(base + "." + name, location.getValues(false));
                }
                config.set(base + ".spawns", raw.getMapList("spawns"));
            } else {
                // Сотрудник заново разметил арену в другом мире: старая разметка больше не нужна.
                unresolved.remove(key);
                saveLocation(config, base + ".point1", arena.pos1());
                saveLocation(config, base + ".point2", arena.pos2());
                saveLocation(config, base + ".lobby", arena.lobby());
                List<Map<String, Object>> spawns = new ArrayList<>();
                for (Location loc : arena.spawns()) spawns.add(locationMap(loc));
                config.set(base + ".spawns", spawns);
            }
            // Save configured values, not effective spawn-based values, so switching
            // between manual and spawn-based capacity does not destroy the manual setup.
            config.set(base + ".min-players", arena.settings().minPlayers);
            config.set(base + ".max-players", arena.settings().maxPlayers);
            List<String> participants = arena.participants().stream().map(UUID::toString).toList();
            config.set(base + ".participants", participants);
            for (Map.Entry<UUID, Integer> slot : arena.spawnSlots().entrySet()) {
                config.set(base + ".spawn-slots." + slot.getKey(), slot.getValue());
            }
            if (arena.containersDirty()) config.set(base + ".containers-dirty", true);
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
        try { SafeFiles.saveYaml(config, file); } catch (IOException e) { plugin.getLogger().severe("Could not save arenas.yml: " + e.getMessage()); }
    }

    /** Мир загрузился позже плагина: подключаем арены, которые его ждали. */
    public synchronized void resolveWorld(World world) {
        if (world == null || unresolved.isEmpty()) return;
        boolean changed = false;
        for (Map.Entry<String, ConfigurationSection> entry : new ArrayList<>(unresolved.entrySet())) {
            Arena arena = arenas.get(entry.getKey());
            if (arena == null || !hasNoLocations(arena)) { unresolved.remove(entry.getKey()); continue; }
            if (!world.getUID().equals(arena.worldUid()) && !world.getName().equals(arena.worldName())) continue;
            arena.setWorld(world);
            readLocations(arena, entry.getValue(), world);
            unresolved.remove(entry.getKey());
            changed = true;
            plugin.getLogger().info("Арена " + arena.id() + ": мир " + world.getName() + " загружен, разметка подключена.");
        }
        if (changed) {
            plugin.getChestManager().remapWorlds();
            save();
        }
    }

    public synchronized Arena create(String id, World world) {
        String key = id.toLowerCase(Locale.ROOT);
        if (arenas.containsKey(key)) return null;
        Arena arena = new Arena(id, world.getName(), world.getUID());
        arenas.put(key, arena);
        save();
        return arena;
    }

    public synchronized boolean delete(String id) {
        Arena arena = get(id);
        if (arena == null) return false;
        String key = arena.id().toLowerCase(Locale.ROOT);
        arenas.remove(key);
        unresolved.remove(key);
        save();
        return true;
    }

    public Arena get(String id) { return id == null ? null : arenas.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<Arena> all() { return List.copyOf(arenas.values()); }
    public Arena first() { return arenas.values().stream().findFirst().orElse(null); }

    /** Нечитаемый arenas.yml сохраняется под другим именем, чтобы следующее сохранение его не затёрло. */
    private YamlConfiguration read(File source) {
        YamlConfiguration config = new YamlConfiguration();
        if (!source.isFile()) return config;
        try {
            config.loadFromString(Files.readString(source.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            File broken = new File(source.getParentFile(), source.getName() + ".broken-" + System.currentTimeMillis());
            plugin.getLogger().log(Level.SEVERE, "arenas.yml не читается. Арены не загружены, файл сохранён как " + broken.getName(), e);
            try { Files.copy(source.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException copyError) { plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить копию arenas.yml", copyError); }
            return new YamlConfiguration();
        }
        return config;
    }

    private World findWorld(UUID worldUid, String worldName) {
        World world = worldUid == null ? null : Bukkit.getWorld(worldUid);
        // После сброса карты (копия шаблона с другим uid.dat) мир остаётся под тем же именем.
        if (world == null && worldName != null) world = Bukkit.getWorld(worldName);
        return world;
    }

    private boolean hasNoLocations(Arena arena) {
        return arena.pos1() == null && arena.pos2() == null && arena.lobby() == null && arena.spawns().isEmpty();
    }

    private void readLocations(Arena arena, ConfigurationSection sec, World world) {
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
    private void saveLocation(YamlConfiguration config, String path, Location loc) {
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
