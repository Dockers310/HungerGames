package ru.doksi.hungergames.game;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.doksi.hungergames.HungerGamesPlugin;
import ru.doksi.hungergames.util.SafeFiles;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Журнал восстановления: вещи и состояние игрока, сохранённые перед матчем.
 *
 * Файл {@code restore/<uuid>.yml} записывается на диск ДО очистки инвентаря и удаляется только
 * после того, как вещи возвращены игроку. Поэтому остановка или сбой сервера во время матча
 * не приводят к потере вещей: они возвращаются при следующем входе игрока.
 *
 * Операции с файлом одного игрока выполняются только из потока этого игрока.
 */
public final class RestoreStore {
    private record Entry(String matchId, PlayerSnapshot snapshot) { }

    private final HungerGamesPlugin plugin;
    private final File directory;
    private final Map<UUID, Entry> pending = new ConcurrentHashMap<>();

    public RestoreStore(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "restore");
    }

    /** Находит файлы, оставшиеся после остановки сервера или выхода игроков во время матча. */
    public void load() {
        pending.clear();
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".tmp")) {
                // Недописанный файл: инвентарь игрока в этот момент ещё не был очищен.
                if (!file.delete()) plugin.getLogger().warning("Не удалось удалить " + file);
                continue;
            }
            if (!name.endsWith(".yml")) continue;
            UUID uuid;
            try { uuid = UUID.fromString(name.substring(0, name.length() - 4)); }
            catch (IllegalArgumentException e) { continue; }
            String matchId = "";
            try { matchId = read(file).getString("match", ""); }
            catch (IOException | InvalidConfigurationException e) {
                plugin.getLogger().log(Level.SEVERE, "Файл восстановления " + file + " не читается. Он оставлен на диске.", e);
            }
            pending.put(uuid, new Entry(matchId, null));
        }
        if (!pending.isEmpty()) {
            plugin.getLogger().info("Ожидают возврата вещей после матча: " + pending.size() + " игрок(ов). Вещи вернутся при входе.");
        }
    }

    public boolean has(UUID uuid) {
        return uuid != null && pending.containsKey(uuid);
    }

    /** Идентификатор матча, перед которым сохранены вещи, или null, если файла нет. */
    public String pendingMatch(UUID uuid) {
        Entry entry = uuid == null ? null : pending.get(uuid);
        return entry == null ? null : entry.matchId();
    }

    public int size() { return pending.size(); }

    /** Записывает снимок на диск. Пока метод не вернул управление, очищать инвентарь нельзя. */
    public void save(UUID uuid, String playerName, String arenaId, String matchId, PlayerSnapshot snapshot) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        yaml.set("uuid", uuid.toString());
        yaml.set("name", playerName);
        yaml.set("arena", arenaId);
        yaml.set("match", matchId);
        yaml.set("saved-at", System.currentTimeMillis());
        snapshot.writeTo(yaml);
        SafeFiles.saveYamlDurably(yaml, file(uuid));
        pending.put(uuid, new Entry(matchId, snapshot));
    }

    /** Читает снимок. Исключение означает, что файл повреждён; он остаётся на диске. */
    public PlayerSnapshot load(UUID uuid) throws IOException, InvalidConfigurationException {
        Entry entry = pending.get(uuid);
        if (entry == null) return null;
        if (entry.snapshot() != null) return entry.snapshot();
        File file = file(uuid);
        if (!file.isFile()) {
            pending.remove(uuid);
            return null;
        }
        PlayerSnapshot snapshot = PlayerSnapshot.readFrom(read(file));
        pending.put(uuid, new Entry(entry.matchId(), snapshot));
        return snapshot;
    }

    /** Удаляет файл после того, как вещи возвращены. */
    public void delete(UUID uuid) {
        pending.remove(uuid);
        File file = file(uuid);
        try { Files.deleteIfExists(file.toPath()); }
        catch (IOException e) {
            // Нельзя оставлять файл: при следующем входе вещи были бы выданы повторно.
            plugin.getLogger().log(Level.SEVERE, "Не удалось удалить " + file + ". Удалите его вручную, вещи игроку уже возвращены.", e);
            file.deleteOnExit();
        }
    }

    private File file(UUID uuid) {
        return new File(directory, uuid + ".yml");
    }

    private static YamlConfiguration read(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        return yaml;
    }
}
