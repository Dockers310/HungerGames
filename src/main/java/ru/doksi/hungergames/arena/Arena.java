package ru.doksi.hungergames.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import ru.doksi.hungergames.HungerGamesPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Arena {
    public static final class Settings {
        public int minPlayers;
        public int maxPlayers;
        public long protectionSeconds;
        public long chestRefillSeconds;
        public long shrinkStartDelaySeconds;
        public long shrinkStageDurationSeconds;
        public int shrinkStages;
        public double shrinkFinalRatio;
        public double borderDamageHeartMultiplier;
        public BorderMode borderMode;
        public BorderMode changingStartMode;
        public BorderMode changingTargetMode;
        public long borderChangeAfterSeconds;
        public long borderChangeTransitionSeconds;
        public int countdownSeconds;
        public boolean lobbyTeleportOnJoin;
        public boolean maxPlayersBySpawns;
    }

    private final String id;
    private String worldName;
    private UUID worldUid;
    private Location pos1;
    private Location pos2;
    private Location lobby;
    private final List<Location> spawns = new ArrayList<>();
    private final Map<UUID, Integer> spawnSlots = new LinkedHashMap<>();
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> activePlayers = new LinkedHashSet<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private int minPlayers;
    private int maxPlayers;
    private final Settings settings = new Settings();
    private ArenaState state = ArenaState.WAITING;
    private boolean collecting;
    private long phaseStartedAt;
    private long lastRefillAt;
    private double shrinkStartHalfX;
    private double shrinkStartHalfZ;
    private double shrinkFinalHalfX;
    private double shrinkFinalHalfZ;
    private int currentShrinkStage;
    private long borderChangeStartedAtMillis;

    public Arena(String id, String worldName, UUID worldUid) {
        this.id = id;
        this.worldName = worldName;
        this.worldUid = worldUid;
        applyDefaults(HungerGamesPlugin.getInstance().getConfig());
    }

    private void applyDefaults(org.bukkit.configuration.file.FileConfiguration config) {
        String p = "default-arena.";
        minPlayers = config.getInt(p + "min-players", 2);
        maxPlayers = config.getInt(p + "max-players", 16);
        settings.minPlayers = minPlayers;
        settings.maxPlayers = maxPlayers;
        settings.protectionSeconds = config.getLong(p + "protection-seconds", 120);
        settings.chestRefillSeconds = config.getLong(p + "chest-refill-seconds", 300);
        settings.shrinkStartDelaySeconds = config.getLong(p + "shrink-start-delay-seconds", 180);
        settings.shrinkStageDurationSeconds = config.getLong(p + "shrink-stage-duration-seconds", 60);
        settings.shrinkStages = config.getInt(p + "shrink-stages", 3);
        settings.shrinkFinalRatio = config.getDouble(p + "shrink-final-ratio", 0.25);
        settings.borderDamageHeartMultiplier = config.getDouble(p + "border-damage-heart-multiplier", 1.0);
        settings.borderMode = parseBorderMode(config.getString(p + "border-mode", "DAMAGE"));
        settings.changingStartMode = parsePermanentBorderMode(config.getString(p + "border-changing-start-mode", "SOFT_TELEPORT"), BorderMode.SOFT_TELEPORT);
        settings.changingTargetMode = parsePermanentBorderMode(config.getString(p + "border-changing-target-mode", "DAMAGE"), BorderMode.DAMAGE);
        settings.borderChangeAfterSeconds = config.getLong(p + "border-change-after-seconds", 180);
        settings.borderChangeTransitionSeconds = config.getLong(p + "border-change-transition-seconds", 8);
        settings.countdownSeconds = config.getInt(p + "countdown-seconds", 5);
        settings.lobbyTeleportOnJoin = config.getBoolean(p + "lobby-teleport-on-join", true);
        settings.maxPlayersBySpawns = config.getBoolean(p + "max-players-by-spawns", false);
    }

    public String id() { return id; }
    public String worldName() { return worldName; }
    public UUID worldUid() { return worldUid; }
    public void setWorld(World world) { this.worldName = world.getName(); this.worldUid = world.getUID(); }
    public Location pos1() { return pos1 == null ? null : pos1.clone(); }
    public Location pos2() { return pos2 == null ? null : pos2.clone(); }
    public Location lobby() { return lobby == null ? null : lobby.clone(); }
    public List<Location> spawns() { return spawns.stream().map(Location::clone).toList(); }
    public List<Location> mutableSpawns() { return spawns; }
    public Map<UUID, Integer> spawnSlots() { return spawnSlots; }
    public int firstFreeSpawnSlot() {
        for (int i = 0; i < spawns.size(); i++) {
            if (!spawnSlots.containsValue(i)) return i;
        }
        return -1;
    }
    public Integer spawnSlot(UUID uuid) { return spawnSlots.get(uuid); }
    public void assignSpawnSlot(UUID uuid, int index) { if (uuid != null && index >= 0) spawnSlots.put(uuid, index); }
    public void clearSpawnSlot(UUID uuid) { if (uuid != null) spawnSlots.remove(uuid); }
    public void clearMissingSpawnSlots() { spawnSlots.entrySet().removeIf(e -> e.getValue() < 0 || e.getValue() >= spawns.size()); }
    public Set<UUID> participants() { return participants; }
    public Set<UUID> activePlayers() { return activePlayers; }
    public Set<UUID> spectators() { return spectators; }
    public Settings settings() { return settings; }

    public void setBorderMode(BorderMode mode) {
        settings.borderMode = mode == null ? BorderMode.DAMAGE : mode;
        if (settings.borderMode == BorderMode.CHANGING) borderChangeStartedAtMillis = System.currentTimeMillis();
    }

    public void startBorderChangeClock() {
        borderChangeStartedAtMillis = System.currentTimeMillis();
    }

    public long borderChangeElapsedSeconds() {
        if (borderChangeStartedAtMillis <= 0) return 0;
        return Math.max(0L, (System.currentTimeMillis() - borderChangeStartedAtMillis) / 1000L);
    }

    public BorderMode effectiveBorderMode() {
        if (settings.borderMode != BorderMode.CHANGING) return settings.borderMode;
        return borderChangeElapsedSeconds() >= Math.max(0L, settings.borderChangeAfterSeconds)
                ? settings.changingTargetMode : settings.changingStartMode;
    }

    public double borderChangeColorProgress() {
        if (settings.borderMode != BorderMode.CHANGING) return 0.0;
        long delay = Math.max(0L, settings.borderChangeAfterSeconds);
        long transition = Math.max(0L, settings.borderChangeTransitionSeconds);
        if (transition <= 0) return borderChangeElapsedSeconds() >= delay ? 1.0 : 0.0;
        double start = delay - transition / 2.0;
        double progress = (borderChangeElapsedSeconds() - start) / transition;
        return Math.max(0.0, Math.min(1.0, progress));
    }
    private static BorderMode parseBorderMode(String value) {
        if (value == null) return BorderMode.DAMAGE;
        try { return BorderMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return BorderMode.DAMAGE; }
    }

    private static BorderMode parsePermanentBorderMode(String value, BorderMode fallback) {
        BorderMode mode = parseBorderMode(value);
        return mode == BorderMode.CHANGING ? fallback : mode;
    }
    public int minPlayers() {
        // When the maximum is tied to spawn count, a configured minimum
        // larger than the available spawn slots must not make the arena
        // impossible to start. The effective minimum is capped by capacity.
        return settings.maxPlayersBySpawns
                ? Math.max(1, Math.min(minPlayers, spawns.size()))
                : minPlayers;
    }
    public int maxPlayers() { return settings.maxPlayersBySpawns ? spawns.size() : maxPlayers; }
    public boolean maxPlayersBySpawns() { return settings.maxPlayersBySpawns; }
    public void setMaxPlayersBySpawns(boolean value) { settings.maxPlayersBySpawns = value; }
    public void setMinPlayers(int value) { minPlayers = Math.max(1, value); settings.minPlayers = minPlayers; if (maxPlayers < minPlayers) maxPlayers = minPlayers; settings.maxPlayers = maxPlayers; }
    public void setMaxPlayers(int value) { maxPlayers = Math.max(minPlayers, value); settings.maxPlayers = maxPlayers; }
    public void setPos1(Location loc) { pos1 = loc == null ? null : loc.clone(); if (loc != null) setWorld(loc.getWorld()); }
    public void setPos2(Location loc) { pos2 = loc == null ? null : loc.clone(); if (loc != null) setWorld(loc.getWorld()); }
    public void setLobby(Location loc) { lobby = loc.clone(); setWorld(loc.getWorld()); }
    public void addSpawn(Location loc) {
        spawns.add(loc.clone());
        // Изменение списка точек делает старые привязки ненадёжными.
        // Участники будут назначены заново при следующем входе/матче.
        spawnSlots.clear();
        setWorld(loc.getWorld());
    }
    public void removeLastSpawn() {
        if (!spawns.isEmpty()) {
            spawns.remove(spawns.size() - 1);
            spawnSlots.clear();
        }
    }
    public boolean removeSpawn(int index) {
        if (index < 0 || index >= spawns.size()) return false;
        spawns.remove(index);
        // После удаления индексы точек меняются, поэтому старые назначения
        // больше нельзя считать действительными.
        spawnSlots.clear();
        return true;
    }
    public int participantCapacity() {
        return Math.min(maxPlayers, spawns.size());
    }
    public ArenaState state() { return state; }
    public void setState(ArenaState state) {
        this.state = state;
        this.phaseStartedAt = System.currentTimeMillis();
        if (state == ArenaState.GAME && settings.borderMode == BorderMode.CHANGING) startBorderChangeClock();
    }
    public boolean collecting() { return collecting; }
    public void setCollecting(boolean collecting) { this.collecting = collecting; }
    public long phaseElapsedSeconds() { return Math.max(0L, (System.currentTimeMillis() - phaseStartedAt) / 1000L); }
    public void markRefillNow() { lastRefillAt = System.currentTimeMillis(); }
    public long secondsSinceRefill() { return lastRefillAt == 0 ? Long.MAX_VALUE : Math.max(0L, (System.currentTimeMillis() - lastRefillAt) / 1000L); }
    public void resetSessionData() {
        participants.clear();
        activePlayers.clear();
        spectators.clear();
        // Сессионные привязки точек также относятся к завершённому матчу.
        spawnSlots.clear();
        currentShrinkStage = 0;
        state = ArenaState.WAITING;
        collecting = false;
        lastRefillAt = 0;
        borderChangeStartedAtMillis = 0;
    }
    public void prepareShrink() {
        if (pos1 == null || pos2 == null) return;
        shrinkStartHalfX = Math.max(1.0, Math.abs(pos2.getX() - pos1.getX()) / 2.0);
        shrinkStartHalfZ = Math.max(1.0, Math.abs(pos2.getZ() - pos1.getZ()) / 2.0);
        shrinkFinalHalfX = Math.max(1.0, shrinkStartHalfX * settings.shrinkFinalRatio);
        shrinkFinalHalfZ = Math.max(1.0, shrinkStartHalfZ * settings.shrinkFinalRatio);
    }
    public int currentShrinkStage() { return currentShrinkStage; }
    public void setCurrentShrinkStage(int stage) { currentShrinkStage = stage; }
    public double centerX() { return (pos1.getX() + pos2.getX()) / 2.0; }
    public double centerZ() { return (pos1.getZ() + pos2.getZ()) / 2.0; }
    public double shrinkCenterX() { return lobby == null ? centerX() : lobby.getX(); }
    public double shrinkCenterZ() { return lobby == null ? centerZ() : lobby.getZ(); }
    public double currentHalfX() {
        if (state != ArenaState.GAME || settings.shrinkStages <= 0) return shrinkStartHalfX;
        return currentHalfXAtProgress(Math.min(1.0, progressOfShrink()));
    }
    public double currentHalfZ() {
        if (state != ArenaState.GAME || settings.shrinkStages <= 0) return shrinkStartHalfZ;
        return currentHalfZAtProgress(Math.min(1.0, progressOfShrink()));
    }
    private double currentHalfXAtProgress(double p) { return shrinkStartHalfX + (shrinkFinalHalfX - shrinkStartHalfX) * p; }
    private double currentHalfZAtProgress(double p) { return shrinkStartHalfZ + (shrinkFinalHalfZ - shrinkStartHalfZ) * p; }
    public double progressOfShrink() {
        long afterDelay = Math.max(0L, phaseElapsedSeconds() - settings.shrinkStartDelaySeconds);
        long total = Math.max(1L, settings.shrinkStageDurationSeconds * Math.max(1, settings.shrinkStages));
        return Math.min(1.0, afterDelay / (double) total);
    }
    public boolean shrinkActive() {
        // Once shrinking starts, the final boundary remains active for the entire GAME state.
        // Reaching the last N/N stage never disables or removes the current border.
        return state == ArenaState.GAME && phaseElapsedSeconds() >= settings.shrinkStartDelaySeconds;
    }
    public Location clampToShrink(Location input) {
        if (input == null || pos1 == null || pos2 == null) return input == null ? null : input.clone();
        double inset = 0.20;
        double minX = shrinkCenterX() - Math.max(0.25, currentHalfX() - inset);
        double maxX = shrinkCenterX() + Math.max(0.25, currentHalfX() - inset);
        double minZ = shrinkCenterZ() - Math.max(0.25, currentHalfZ() - inset);
        double maxZ = shrinkCenterZ() + Math.max(0.25, currentHalfZ() - inset);
        Location out = input.clone();
        out.setX(Math.max(minX, Math.min(maxX, out.getX())));
        out.setZ(Math.max(minZ, Math.min(maxZ, out.getZ())));
        return out;
    }
    public double outsideDistance(Location loc) {
        if (pos1 == null || pos2 == null) return 0.0;
        double dx = Math.max(0.0, Math.abs(loc.getX() - shrinkCenterX()) - currentHalfX());
        double dz = Math.max(0.0, Math.abs(loc.getZ() - shrinkCenterZ()) - currentHalfZ());
        return Math.max(dx, dz);
    }
    public boolean insideOuter(Location loc) {
        if (pos1 == null || pos2 == null || loc.getWorld() == null) return false;
        if (worldUid != null && !worldUid.equals(loc.getWorld().getUID())) return false;
        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX());
        double minY = Math.min(pos1.getY(), pos2.getY());
        double maxY = Math.max(pos1.getY(), pos2.getY());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ());
        return loc.getX() >= minX && loc.getX() <= maxX && loc.getY() >= minY && loc.getY() <= maxY && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }
    public Location clampToOuter(Location input) {
        if (pos1 == null || pos2 == null) return input.clone();
        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX());
        double minY = Math.min(pos1.getY(), pos2.getY());
        double maxY = Math.max(pos1.getY(), pos2.getY());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ());
        Location out = input.clone();
        out.setX(Math.max(minX + 0.2, Math.min(maxX - 0.2, out.getX())));
        out.setY(Math.max(minY + 0.1, Math.min(maxY - 0.1, out.getY())));
        out.setZ(Math.max(minZ + 0.2, Math.min(maxZ - 0.2, out.getZ())));
        return out;
    }
    public Location shrinkCenterLocation() {
        World world = Bukkit.getWorld(worldUid);
        if (world == null) return null;
        double y = lobby != null ? lobby.getY() : (pos1 == null ? world.getMinHeight() + 1.0 : pos1.getY());
        return new Location(world, shrinkCenterX(), y, shrinkCenterZ());
    }
}
