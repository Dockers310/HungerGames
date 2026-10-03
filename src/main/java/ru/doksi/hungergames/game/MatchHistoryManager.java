package ru.doksi.hungergames.game;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.doksi.hungergames.HungerGamesPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class MatchHistoryManager {
    public record Result(UUID uuid, String name, int placement, int kills, String reason, String killer, long time) { }
    public record MatchRecord(String id, long startedAt, long endedAt, List<Result> results) { }

    private final HungerGamesPlugin plugin;
    private final Map<String, List<MatchRecord>> history = new LinkedHashMap<>();
    private File file;
    private YamlConfiguration config;

    public MatchHistoryManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void load() {
        file = new File(plugin.getDataFolder(), "matches.yml");
        config = YamlConfiguration.loadConfiguration(file);
        history.clear();
        ConfigurationSection root = config.getConfigurationSection("arenas");
        if (root == null) return;
        for (String arenaId : root.getKeys(false)) {
            ConfigurationSection arenaSec = root.getConfigurationSection(arenaId);
            if (arenaSec == null) continue;
            List<MatchRecord> records = new ArrayList<>();
            for (String matchId : arenaSec.getKeys(false)) {
                ConfigurationSection matchSec = arenaSec.getConfigurationSection(matchId);
                if (matchSec == null) continue;
                List<Result> results = new ArrayList<>();
                for (Map<?, ?> raw : matchSec.getMapList("results")) {
                    UUID uuid;
                    try { uuid = UUID.fromString(String.valueOf(raw.get("uuid"))); }
                    catch (Exception ignored) { continue; }
                    results.add(new Result(
                            uuid,
                            String.valueOf(raw.containsKey("name") ? raw.get("name") : uuid.toString().substring(0, 8)),
                            number(raw.get("placement"), 999).intValue(),
                            number(raw.get("kills"), 0).intValue(),
                            String.valueOf(raw.containsKey("reason") ? raw.get("reason") : ""),
                            String.valueOf(raw.containsKey("killer") ? raw.get("killer") : ""),
                            number(raw.get("time"), 0L).longValue()
                    ));
                }
                results.sort(Comparator.comparingInt(Result::placement));
                records.add(new MatchRecord(matchId, matchSec.getLong("started-at"), matchSec.getLong("ended-at"), results));
            }
            records.sort(Comparator.comparingLong(MatchRecord::endedAt).reversed());
            history.put(arenaId.toLowerCase(Locale.ROOT), records);
        }
    }

    public synchronized void record(String arenaId, String id, long startedAt, long endedAt, Collection<Result> results) {
        String key = arenaId.toLowerCase(Locale.ROOT);
        List<MatchRecord> records = history.computeIfAbsent(key, ignored -> new ArrayList<>());
        records.removeIf(r -> r.id().equals(id));
        List<Result> copy = new ArrayList<>(results);
        copy.sort(Comparator.comparingInt(Result::placement));
        records.add(0, new MatchRecord(id, startedAt, endedAt, copy));
        int max = Math.max(1, plugin.getConfig().getInt("history.max-matches-per-arena", 20));
        if (records.size() > max) records.subList(max, records.size()).clear();
        save();
    }

    public synchronized MatchRecord latest(String arenaId) {
        List<MatchRecord> records = history.getOrDefault(arenaId.toLowerCase(Locale.ROOT), List.of());
        return records.isEmpty() ? null : records.get(0);
    }

    public List<MatchRecord> get(String arenaId) {
        return List.copyOf(history.getOrDefault(arenaId.toLowerCase(Locale.ROOT), List.of()));
    }

    public synchronized void save() {
        if (config == null) config = new YamlConfiguration();
        config.set("arenas", null);
        for (Map.Entry<String, List<MatchRecord>> entry : history.entrySet()) {
            String base = "arenas." + entry.getKey();
            for (MatchRecord record : entry.getValue()) {
                String path = base + "." + record.id();
                config.set(path + ".started-at", record.startedAt());
                config.set(path + ".ended-at", record.endedAt());
                List<Map<String, Object>> results = new ArrayList<>();
                for (Result result : record.results()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("uuid", result.uuid().toString());
                    map.put("name", result.name());
                    map.put("placement", result.placement());
                    map.put("kills", result.kills());
                    map.put("reason", result.reason());
                    map.put("killer", result.killer());
                    map.put("time", result.time());
                    results.add(map);
                }
                config.set(path + ".results", results);
            }
        }
        try { config.save(file); }
        catch (IOException e) { plugin.getLogger().severe("Could not save matches.yml: " + e.getMessage()); }
    }

    private Number number(Object value, Number fallback) {
        return value instanceof Number n ? n : fallback;
    }
}
