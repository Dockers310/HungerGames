package ru.doksi.hungergames.game;

import org.bukkit.Bukkit;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import ru.doksi.hungergames.HungerGamesPlugin;
import ru.doksi.hungergames.arena.Arena;
import ru.doksi.hungergames.arena.ArenaState;
import ru.doksi.hungergames.arena.BorderMode;
import ru.doksi.hungergames.util.FoliaScheduler;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Матчи и состав арен.
 *
 * Потоки (Folia): глобальный цикл {@link #tick()} и события игроков выполняются в разных потоках.
 * Поэтому любое составное изменение состава или состояния арены делается под {@link #stateLock},
 * а любое действие над игроком (инвентарь, режим, телепорт) — только в потоке этого игрока
 * через {@link FoliaScheduler#entity}.
 *
 * Вещи игроков: инвентарь не трогается, пока идёт сбор. Снимок делается один раз, перед самым
 * стартом матча, сразу записывается на диск ({@link RestoreStore}) и только после этого инвентарь
 * очищается. Файл удаляется после возврата вещей.
 */
public final class GameManager {
    /** Сколько секунд после конца отсчёта ждать участников, чьи вещи ещё не сохранены. */
    private static final long PREPARE_GRACE_SECONDS = 3L;

    public enum ParticipantChange { OK, LOCKED, ALREADY, IN_OTHER_ARENA, FULL, NO_SLOT, NOT_FOUND }

    private final HungerGamesPlugin plugin;
    private final FoliaScheduler scheduler;
    private final RestoreStore restoreStore;
    private final Object stateLock = new Object();
    private ScheduledTask taskId;
    private ScheduledTask particleTaskId;
    private final Set<UUID> pluginTeleports = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, BossBar> bossBars = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> bossBarViewers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, MatchSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Arena> observers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, ObserverState> observerStates = new java.util.concurrent.ConcurrentHashMap<>();
    // Место, откуда игрок пришёл на арену. Инвентарь во время сбора не сохраняется и не меняется.
    private final Map<UUID, Location> returnLocations = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Player> onlinePlayers = new java.util.concurrent.ConcurrentHashMap<>();
    // Сотрудники онлайн; права проверяются в потоке самого игрока, здесь только результат.
    private final Set<UUID> staffOnline = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record ObserverState(Location returnLocation, org.bukkit.GameMode previousGameMode, boolean previousAllowFlight, boolean previousFlying) { }
    private final Map<UUID, ObserverState> pendingObserverRestores = new java.util.concurrent.ConcurrentHashMap<>();

    public GameManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        this.scheduler = new FoliaScheduler(plugin);
        this.restoreStore = new RestoreStore(plugin);
    }

    public static final class MatchSession {
        private final String id;
        private final long startedAt;
        private volatile boolean started;
        private final Map<UUID, PlayerSnapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
        private final Set<UUID> unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Map<UUID, Integer> kills = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<UUID, MatchHistoryManager.Result> results = new java.util.concurrent.ConcurrentHashMap<>();

        public MatchSession(String id, long startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }
        public String id() { return id; }
        public long startedAt() { return startedAt; }
        /** false, пока идёт сохранение вещей участников перед стартом. */
        public boolean started() { return started; }
        public Map<UUID, PlayerSnapshot> snapshots() { return snapshots; }
        public int kills(UUID uuid) { return kills.getOrDefault(uuid, 0); }
        public void addKill(UUID uuid) { kills.merge(uuid, 1, Integer::sum); }
        public Collection<MatchHistoryManager.Result> results() { return Collections.unmodifiableCollection(results.values()); }
        public void addResult(MatchHistoryManager.Result result) { results.put(result.uuid(), result); }
        public boolean hasResult(UUID uuid) { return results.containsKey(uuid); }
    }

    public void load() {
        restoreStore.load();
    }

    public void startTicker() {
        taskId = scheduler.globalRepeating(20L, 20L, this::tick);
        long period = Math.max(2L, plugin.getConfig().getLong("border-particles.period-ticks", 5L));
        particleTaskId = scheduler.globalRepeating(period, period, () -> {
            for (Arena arena : plugin.getArenaManager().all()) renderBorderParticles(arena);
        });
    }

    /**
     * Вызывается из onDisable. Выключенный плагин не может ставить задачи, а трогать игроков
     * из чужого потока нельзя, поэтому здесь нет действий над игроками: вещи участников
     * незавершённого матча лежат в папке restore и возвращаются при следующем входе.
     */
    public void shutdown() {
        if (taskId != null) taskId.cancel();
        if (particleTaskId != null) particleTaskId.cancel();
        synchronized (stateLock) {
            for (Arena arena : plugin.getArenaManager().all()) {
                MatchSession session = sessions.remove(arenaKey(arena));
                if (session != null && session.started() && arena.state() != ArenaState.FINISH && !session.results().isEmpty()) {
                    plugin.getMatchHistoryManager().record(arena.id(), session.id(), session.startedAt(), System.currentTimeMillis(), session.results());
                }
                clearArenaRuntime(arena);
            }
        }
        for (BossBar bar : bossBars.values()) {
            try { bar.removeAll(); } catch (RuntimeException ignored) { }
        }
        bossBars.clear();
        bossBarViewers.clear();
        observers.clear();
        observerStates.clear();
        returnLocations.clear();
        staffOnline.clear();
        onlinePlayers.clear();
        if (restoreStore.size() > 0) {
            plugin.getLogger().info("Вещи " + restoreStore.size() + " игрок(ов) сохранены в папке restore и вернутся при входе.");
        }
    }

    private void clearArenaRuntime(Arena arena) {
        arena.activePlayers().clear();
        arena.spectators().clear();
        arena.participants().clear();
        // После окончания матча все старые привязки игроков к точкам появления
        // должны быть удалены. Иначе firstFreeSpawnSlot() считает старые точки занятыми
        // и следующую игру на этой же арене начать невозможно.
        arena.spawnSlots().clear();
        arena.setState(ArenaState.WAITING);
        arena.setCollecting(false);
    }

    private void tick() {
        for (Arena arena : plugin.getArenaManager().all()) {
            try {
                synchronized (stateLock) {
                    switch (arena.state()) {
                        case COUNTDOWN -> tickCountdown(arena);
                        case PROTECTION -> tickProtection(arena);
                        case GAME -> tickGame(arena);
                        default -> { }
                    }
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка в цикле матча на арене " + arena.id(), e);
            }
        }
        enforceAllNonParticipants();
    }

    private void tickCountdown(Arena arena) {
        long elapsed = arena.phaseElapsedSeconds();
        long total = arena.settings().countdownSeconds;
        long remaining = Math.max(0, total - elapsed);
        createOrUpdateBossBar(arena);
        if (remaining > 0 && remaining <= 5) broadcast(arena, "countdown", "seconds", remaining);

        MatchSession session = sessions.get(arenaKey(arena));
        if (session == null) {
            // За секунду до старта сохраняем вещи участников; матч начнётся следующим шагом.
            if (elapsed >= total - 1) beginMatchPreparation(arena);
            return;
        }
        if (elapsed < total) return;
        boolean everyoneReady = true;
        for (UUID uuid : arena.participants()) {
            if (onlinePlayers.get(uuid) != null && !session.snapshots().containsKey(uuid) && !session.unavailable.contains(uuid)) {
                everyoneReady = false;
                break;
            }
        }
        if (everyoneReady || elapsed >= total + PREPARE_GRACE_SECONDS) startMatch(arena, session);
    }

    private void tickProtection(Arena arena) {
        long remaining = Math.max(0, arena.settings().protectionSeconds - arena.phaseElapsedSeconds());
        if (remaining > 0 && remaining <= 10) broadcast(arena, "protection", "seconds", remaining);
        createOrUpdateBossBar(arena);
        if (arena.activePlayers().size() <= 1) { finish(arena); return; }
        if (arena.phaseElapsedSeconds() >= arena.settings().protectionSeconds) {
            arena.setState(ArenaState.GAME);
            broadcast(arena, "protection-ended");
            arena.prepareShrink();
        }
        refillIfNeeded(arena);
    }

    private void tickGame(Arena arena) {
        if (arena.activePlayers().size() <= 1) { finish(arena); return; }
        BorderMode modeBefore = arena.effectiveBorderMode();
        createOrUpdateBossBar(arena);
        if (arena.shrinkActive()) {
            int stage = (int) Math.min(arena.settings().shrinkStages,
                    ((arena.phaseElapsedSeconds() - arena.settings().shrinkStartDelaySeconds) /
                            Math.max(1L, arena.settings().shrinkStageDurationSeconds)) + 1);
            if (stage > arena.currentShrinkStage()) {
                arena.setCurrentShrinkStage(stage);
                broadcast(arena, "shrink-stage", "stage", stage, "total", arena.settings().shrinkStages);
                if (stage == 1) broadcast(arena, "shrink-started");
            }
        }
        applyBorderBehavior(arena);
        if (arena.settings().borderMode == BorderMode.CHANGING) {
            BorderMode modeAfter = arena.effectiveBorderMode();
            if (modeAfter != modeBefore) {
                broadcast(arena, "border-mode-changed", "mode", modeAfter.displayName());
            }
        }
        refillIfNeeded(arena);
    }

    private void refillIfNeeded(Arena arena) {
        if (arena.settings().chestRefillSeconds <= 0) return;
        if (arena.secondsSinceRefill() >= arena.settings().chestRefillSeconds) {
            plugin.getChestManager().refill(arena);
            arena.markRefillNow();
            broadcast(arena, "chests-refilled", "arena", arena.id());
        }
    }

    private void applyBorderBehavior(Arena arena) {
        if (!arena.shrinkActive()) return;
        if (arena.effectiveBorderMode() == BorderMode.SOFT_TELEPORT) {
            for (UUID uuid : new HashSet<>(arena.activePlayers())) {
                Player player = onlinePlayers.get(uuid);
                if (player == null) continue;
                scheduler.entity(player, () -> {
                    if (!arena.activePlayers().contains(player.getUniqueId())) return;
                    if (arena.outsideDistance(player.getLocation()) <= 0) return;
                    Location safe = softBorderDestination(arena, player.getLocation(), player);
                    if (safe == null) return;
                    Vector push = softBorderPush(arena, safe);
                    scheduleSoftBorderSurfaceCorrection(player, arena, safe, push);
                });
            }
            return;
        }
        applyBorderDamage(arena);
    }

    private void applyBorderDamage(Arena arena) {
        for (UUID uuid : new HashSet<>(arena.activePlayers())) {
            Player player = onlinePlayers.get(uuid);
            if (player == null) continue;
            scheduler.entity(player, () -> {
                if (!arena.activePlayers().contains(player.getUniqueId())) return;
                double outside = arena.outsideDistance(player.getLocation());
                if (outside <= 0) return;
                double blocks = Math.ceil(outside);
                double damage = blocks * 2.0 * arena.settings().borderDamageHeartMultiplier;
                if (damage > 0) player.damage(damage);
            });
        }
    }

    public Location softBorderDestination(Arena arena, Location from, Player player) {
        if (arena == null || from == null || arena.effectiveBorderMode() != BorderMode.SOFT_TELEPORT
                || !arena.shrinkActive() || arena.outsideDistance(from) <= 0) return null;
        return findSoftBorderLocation(arena, from, player);
    }

    public Vector softBorderPush(Arena arena, Location location) {
        if (arena == null || location == null) return null;
        double dx = arena.shrinkCenterX() - location.getX();
        double dz = arena.shrinkCenterZ() - location.getZ();
        Vector push = new Vector(dx, 0.0, dz);
        if (push.lengthSquared() < 0.0001) return new Vector(0, 0, 0);
        return push.normalize().multiply(0.25);
    }

    private Location findSoftBorderLocation(Arena arena, Location from, Player player) {
        if (from == null || from.getWorld() == null || arena.pos1() == null || arena.pos2() == null) return null;
        double centerX = arena.shrinkCenterX();
        double centerZ = arena.shrinkCenterZ();
        double halfX = Math.max(1.0, arena.currentHalfX());
        double halfZ = Math.max(1.0, arena.currentHalfZ());
        double inset = Math.min(1.25, Math.max(0.35, Math.min(halfX, halfZ) * 0.08));
        double minX = centerX - Math.max(0.5, halfX - inset);
        double maxX = centerX + Math.max(0.5, halfX - inset);
        double minZ = centerZ - Math.max(0.5, halfZ - inset);
        double maxZ = centerZ + Math.max(0.5, halfZ - inset);
        double targetX = clamp(from.getX(), minX, maxX);
        double targetZ = clamp(from.getZ(), minZ, maxZ);

        Location direct = new Location(from.getWorld(), targetX, from.getY(), targetZ, from.getYaw(), from.getPitch());
        Location directSafe = highestSafeLocation(from.getWorld(), targetX, targetZ, arena);
        if (directSafe != null) return directSafe;

        // Редкий случай: прямо у границы находится глубокая вода/пещера/препятствие.
        // Ищем ближайшую безопасную колонну вокруг проекции на границу.
        int baseX = (int) Math.floor(targetX);
        int baseZ = (int) Math.floor(targetZ);
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double columnX = baseX + 0.5 + dx;
                double columnZ = baseZ + 0.5 + dz;
                Location candidate = highestSafeLocation(from.getWorld(), columnX, columnZ, arena);
                if (candidate == null || !insideCurrentShrink(arena, candidate)) continue;
                double distance = candidate.distanceSquared(direct);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private boolean insideCurrentShrink(Arena arena, Location location) {
        if (arena == null || location == null) return false;
        return arena.outsideDistance(location) <= 0.0;
    }

    private void scheduleSoftBorderSurfaceCorrection(Player player, Arena arena, Location target, Vector push) {
        if (player == null || arena == null || target == null) return;
        scheduler.region(target, () -> {
            Location safe = highestSafeLocation(target.getWorld(), target.getX(), target.getZ(), arena);
            if (safe == null) safe = target.clone();
            final Location destination = safe;
            scheduler.entity(player, () -> {
                if (!player.isOnline() || !arena.activePlayers().contains(player.getUniqueId())) return;
                safeTeleport(player, destination);
                if (push != null) {
                    player.setVelocity(push);
                    scheduler.entityLater(player, 1L, () -> { if (player.isOnline()) player.setVelocity(push); });
                    scheduler.entityLater(player, 2L, () -> { if (player.isOnline()) player.setVelocity(push); });
                }
            });
        });
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Location highestSafeLocation(World world, double x, double z, Arena arena) {
        if (world == null) return null;
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int highest = world.getHighestBlockYAt(bx, bz);
        if (highest < world.getMinHeight() || highest >= world.getMaxHeight() - 2) return null;
        for (int y = highest; y >= world.getMinHeight() + 1; y--) {
            org.bukkit.block.Block ground = world.getBlockAt(bx, y, bz);
            org.bukkit.block.Block feet = world.getBlockAt(bx, y + 1, bz);
            org.bukkit.block.Block head = world.getBlockAt(bx, y + 2, bz);
            if (!ground.getType().isAir() && !ground.isLiquid() && feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()) {
                Location result = new Location(world, bx + 0.5, y + 1.0, bz + 0.5);
                if (arena.insideOuter(result)) return result;
            }
        }
        for (int y = highest; y <= Math.min(world.getMaxHeight() - 3, highest + 4); y++) {
            org.bukkit.block.Block current = world.getBlockAt(bx, y, bz);
            org.bukkit.block.Block above = world.getBlockAt(bx, y + 1, bz);
            org.bukkit.block.Block head = world.getBlockAt(bx, y + 2, bz);
            if (current.isLiquid() && above.isPassable() && head.isPassable()) {
                Location result = new Location(world, bx + 0.5, y + 1.0, bz + 0.5);
                if (arena.insideOuter(result)) return result;
            }
        }
        return null;
    }

    public Arena findJoinableArena() {
        for (Arena arena : plugin.getArenaManager().all()) {
            if (arena.state() != ArenaState.WAITING || !arena.collecting()) continue;
            if (arena.participants().size() >= arena.maxPlayers()) continue;
            if (arena.firstFreeSpawnSlot() < 0) continue;
            return arena;
        }
        return null;
    }

    /** Запись игрока на арену. Вызывается из потока игрока. Инвентарь не сохраняется и не меняется. */
    public boolean join(Player player, Arena arena) {
        if (player == null || arena == null) return false;
        UUID uuid = player.getUniqueId();
        int freeSpawnIndex;
        Location spawn;
        boolean restoreFirst = false;
        synchronized (stateLock) {
            if (arena.state() != ArenaState.WAITING || !arena.collecting()) { plugin.send(player, "join-open-only"); return false; }
            List<Location> spawns = arena.spawns();
            if (spawns.isEmpty()) { plugin.send(player, "no-spawn-slots"); return false; }
            if (arena.participants().contains(uuid)) { plugin.send(player, "already-participant"); return false; }
            if (arena.participants().size() >= arena.maxPlayers()) { plugin.send(player, "join-full"); return false; }
            if (findPlayerArena(uuid).isPresent()) { plugin.send(player, "already-in-other-arena"); return false; }
            freeSpawnIndex = arena.firstFreeSpawnSlot();
            if (freeSpawnIndex < 0 || freeSpawnIndex >= spawns.size()) { plugin.send(player, "no-spawn-slots"); return false; }
            if (restoreStore.has(uuid)) {
                // Вещи с прошлого матча ещё не возвращены. Сначала возврат, запись — следующей командой.
                restoreFirst = true;
                spawn = null;
            } else {
                spawn = spawns.get(freeSpawnIndex);
                arena.participants().add(uuid);
                arena.assignSpawnSlot(uuid, freeSpawnIndex);
            }
        }
        if (restoreFirst) {
            restoreIfPending(player, null, "restored-after-leave");
            if (!restoreStore.has(uuid)) plugin.send(player, "restore-pending");
            return false;
        }
        returnLocations.put(uuid, player.getLocation().clone());
        safeTeleport(player, spawn);
        plugin.send(player, "joined", "arena", arena.id(), "slot", freeSpawnIndex + 1);
        plugin.getArenaManager().save();
        return true;
    }

    /** Запись игрока сотрудником. Вызывается из потока сотрудника. */
    public ParticipantChange addParticipant(Arena arena, UUID uuid) {
        if (arena == null || uuid == null) return ParticipantChange.NOT_FOUND;
        synchronized (stateLock) {
            if (arena.state() != ArenaState.WAITING) return ParticipantChange.LOCKED;
            if (arena.participants().contains(uuid)) return ParticipantChange.ALREADY;
            if (findPlayerArena(uuid).isPresent()) return ParticipantChange.IN_OTHER_ARENA;
            if (arena.participants().size() >= arena.maxPlayers()) return ParticipantChange.FULL;
            int slot = arena.firstFreeSpawnSlot();
            if (slot < 0) return ParticipantChange.NO_SLOT;
            arena.participants().add(uuid);
            arena.assignSpawnSlot(uuid, slot);
        }
        Player target = onlinePlayers.get(uuid);
        if (target != null) {
            scheduler.entity(target, () -> {
                if (!target.isOnline() || !isWaitingParticipant(arena, uuid)) return;
                returnLocations.put(uuid, target.getLocation().clone());
                teleportParticipantToAssignedSpawn(target, arena);
                plugin.send(target, "participant-added-you", "arena", arena.id());
            });
        }
        plugin.getArenaManager().save();
        return ParticipantChange.OK;
    }

    /** Удаление участника сотрудником во время сбора: игрок возвращается туда, откуда пришёл. */
    public ParticipantChange removeParticipant(Arena arena, UUID uuid) {
        if (arena == null || uuid == null) return ParticipantChange.NOT_FOUND;
        synchronized (stateLock) {
            if (arena.state() != ArenaState.WAITING) return ParticipantChange.LOCKED;
            if (!arena.participants().remove(uuid)) return ParticipantChange.NOT_FOUND;
            arena.clearSpawnSlot(uuid);
        }
        Player target = onlinePlayers.get(uuid);
        if (target != null) {
            scheduler.entity(target, () -> {
                if (!target.isOnline()) return;
                returnToPreviousPlace(target);
                plugin.send(target, "participant-removed-you", "arena", arena.id());
            });
        }
        plugin.getArenaManager().save();
        return ParticipantChange.OK;
    }

    /** Удаление арены. Арену с идущей игрой удалить нельзя; записанные участники возвращаются. */
    public boolean deleteArena(Arena arena) {
        if (arena == null) return false;
        synchronized (stateLock) {
            if (arena.state() != ArenaState.WAITING) return false;
            stop(arena, false);
            if (!plugin.getArenaManager().delete(arena.id())) return false;
        }
        plugin.getChestManager().removeArena(arena.id());
        return true;
    }

    private boolean isWaitingParticipant(Arena arena, UUID uuid) {
        return (arena.state() == ArenaState.WAITING || arena.state() == ArenaState.COUNTDOWN) && arena.participants().contains(uuid);
    }

    private int assignParticipantSpawn(Arena arena, UUID uuid) {
        if (arena == null || uuid == null) return -1;
        synchronized (stateLock) {
            Integer existing = arena.spawnSlot(uuid);
            if (existing != null && existing >= 0 && existing < arena.spawns().size()) return existing;
            int slot = arena.firstFreeSpawnSlot();
            if (slot >= 0) arena.assignSpawnSlot(uuid, slot);
            return slot;
        }
    }

    private void teleportParticipantToAssignedSpawn(Player player, Arena arena) {
        if (player == null || arena == null || !player.isOnline()) return;
        if (!isWaitingParticipant(arena, player.getUniqueId())) return;
        int slot = assignParticipantSpawn(arena, player.getUniqueId());
        List<Location> spawns = arena.spawns();
        if (slot >= 0 && slot < spawns.size()) safeTeleport(player, spawns.get(slot));
    }

    /** Возвращает игрока туда, откуда он пришёл на арену. Вызывается из потока игрока. */
    private void returnToPreviousPlace(Player player) {
        Location back = returnLocations.remove(player.getUniqueId());
        if (back != null && back.getWorld() != null) safeTeleport(player, back);
    }

    public void leave(Player player) {
        UUID uuid = player.getUniqueId();
        Arena activeArena = null;
        Arena participantArena = null;
        synchronized (stateLock) {
            for (Arena arena : plugin.getArenaManager().all()) {
                if (arena.activePlayers().contains(uuid)) { activeArena = arena; break; }
            }
            if (activeArena == null) {
                for (Arena arena : plugin.getArenaManager().all()) {
                    if (isWaitingParticipant(arena, uuid)) { participantArena = arena; break; }
                }
                if (participantArena != null) {
                    participantArena.participants().remove(uuid);
                    participantArena.clearSpawnSlot(uuid);
                    MatchSession session = sessions.get(arenaKey(participantArena));
                    if (session != null) session.snapshots().remove(uuid);
                }
            }
        }
        if (activeArena != null) {
            eliminate(player, activeArena, "Покинул игру", "", false);
            return;
        }
        if (participantArena != null) {
            // Если вещи уже сохранены перед стартом (последняя секунда отсчёта), возвращаем их из журнала.
            if (!restoreIfPending(player, null, null)) {
                Location back = returnLocations.remove(uuid);
                if (back != null && back.getWorld() != null) safeTeleport(player, back);
                else if (participantArena.lobby() != null) safeTeleport(player, participantArena.lobby());
            }
            plugin.send(player, "left");
            plugin.getArenaManager().save();
            return;
        }
        if (observers.containsKey(uuid)) {
            leaveObserver(player);
            return;
        }
        plugin.send(player, "not-in-game");
    }

    public boolean start(Arena arena) {
        if (arena == null) return false;
        synchronized (stateLock) {
            if (arena.state() != ArenaState.WAITING || arena.collecting()) return false;
            if (arena.participants().size() < arena.minPlayers() || arena.spawns().isEmpty() || arena.participants().size() > arena.spawns().size()) return false;
            // onlinePlayers — это потокобезопасный кэш, который обновляется при join/quit.
            // Не вызываем Player.isOnline() из глобального региона Folia.
            long online = arena.participants().stream().filter(uuid -> onlinePlayers.get(uuid) != null).count();
            if (online < arena.minPlayers()) return false;
            arena.setState(ArenaState.COUNTDOWN);
            return true;
        }
    }

    /** Шаг 1 старта: в потоке каждого участника сохраняем его вещи на диск и очищаем инвентарь. */
    private void beginMatchPreparation(Arena arena) {
        String matchId = arena.id() + "-" + System.currentTimeMillis();
        MatchSession session = new MatchSession(matchId, System.currentTimeMillis());
        sessions.put(arenaKey(arena), session);
        for (UUID uuid : new ArrayList<>(arena.participants())) {
            Player player = onlinePlayers.get(uuid);
            // Не читаем состояние Player из глобального региона Folia.
            // Если игрок отсутствует в кэше, его quit уже обработан или игрок недоступен.
            if (player == null) continue;
            scheduler.entity(player, () -> prepareParticipant(player, arena, session), () -> session.unavailable.add(uuid));
        }
    }

    private boolean isPreparing(Arena arena, MatchSession session, UUID uuid) {
        return sessions.get(arenaKey(arena)) == session && !session.started()
                && arena.state() == ArenaState.COUNTDOWN && arena.participants().contains(uuid);
    }

    /** Выполняется в потоке игрока. Порядок важен: снимок → файл на диске → очистка инвентаря. */
    private void prepareParticipant(Player player, Arena arena, MatchSession session) {
        UUID uuid = player.getUniqueId();
        if (!isPreparing(arena, session, uuid) || session.snapshots().containsKey(uuid)) return;
        if (!player.isOnline() || player.isDead()) { session.unavailable.add(uuid); return; }
        if (restoreStore.has(uuid)) {
            // Остались невозвращённые вещи с прошлого матча: новый снимок их бы затёр.
            session.unavailable.add(uuid);
            restoreIfPending(player, null, "restored-after-leave");
            return;
        }
        // Предметы из сетки крафта и с курсора возвращаются в инвентарь и попадают в снимок.
        player.closeInventory();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player, returnLocations.get(uuid));
        try {
            restoreStore.save(uuid, player.getName(), arena.id(), session.id(), snapshot);
        } catch (IOException | RuntimeException e) {
            session.unavailable.add(uuid);
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить вещи игрока " + player.getName()
                    + " перед матчем. Инвентарь не очищен, игрок в матч не попадает.", e);
            plugin.send(player, "snapshot-failed");
            return;
        }
        synchronized (stateLock) {
            if (!isPreparing(arena, session, uuid)) {
                // Пока файл записывался, игру остановили. Инвентарь ещё не тронут.
                restoreStore.delete(uuid);
                return;
            }
            session.snapshots().put(uuid, snapshot);
        }
        snapshot.prepareForMatch(player);
    }

    /** Шаг 2 старта: в матч попадают только участники, чьи вещи уже лежат на диске. */
    private void startMatch(Arena arena, MatchSession session) {
        List<UUID> skipped = new ArrayList<>();
        arena.activePlayers().clear();
        for (UUID uuid : new ArrayList<>(arena.participants())) {
            if (onlinePlayers.get(uuid) != null && session.snapshots().containsKey(uuid)) {
                arena.activePlayers().add(uuid);
                session.kills.put(uuid, 0);
            } else {
                skipped.add(uuid);
            }
        }
        int placement = arena.activePlayers().size() + skipped.size();
        for (UUID uuid : skipped) {
            arena.participants().remove(uuid);
            arena.clearSpawnSlot(uuid);
            session.snapshots().remove(uuid);
            addResult(arena, uuid, null, "Не подключился к игре", "", placement--);
            Player player = onlinePlayers.get(uuid);
            if (player == null) continue;
            scheduler.entity(player, () -> {
                if (!player.isOnline()) return;
                if (!restoreIfPending(player, session.id(), null)) returnToPreviousPlace(player);
                plugin.send(player, "start-skipped");
            });
        }

        if (arena.activePlayers().size() < 1) { stop(arena, false); return; }
        arena.spectators().clear();
        arena.prepareShrink();
        arena.setCurrentShrinkStage(0);
        arena.startBorderChangeClock();
        plugin.getChestManager().refill(arena);
        arena.markRefillNow();
        arena.setContainersDirty(true);
        session.started = true;
        // Игрок уже был телепортирован на назначенный спавн при /hg join.
        // Повторно телепортировать его в момент START не нужно: это вызывало
        // заметное двойное/тройное перемещение при переходе COUNTDOWN -> GAME.
        arena.setState(ArenaState.PROTECTION);
        broadcast(arena, "game-started");
        createOrUpdateBossBar(arena);
        plugin.getArenaManager().save();
    }

    public void eliminate(Player player, Arena arena) {
        EntityDamageEvent cause = player.getLastDamageCause();
        String reason = cause == null ? "Неизвестная причина" : humanizeCause(cause.getCause());
        String killer = player.getKiller() != null ? player.getKiller().getName() : "";
        eliminate(player, arena, reason, killer, false);
    }

    public void eliminate(Player player, Arena arena, String reason, String killer, boolean disconnecting) {
        Player killerPlayer = player.getKiller();
        UUID killerUuid = killerPlayer == null ? null : killerPlayer.getUniqueId();
        eliminate(player, arena, reason, killer, killerUuid, disconnecting);
    }

    /** Вызывается из потока выбывающего игрока. */
    public void eliminate(Player player, Arena arena, String reason, String killer, UUID killerUuid, boolean disconnecting) {
        if (arena == null) return;
        UUID uuid = player.getUniqueId();
        String matchId;
        synchronized (stateLock) {
            if (!arena.activePlayers().contains(uuid)) return;
            MatchSession session = sessions.get(arenaKey(arena));
            if (session == null) return;
            matchId = session.id();

            if (killerUuid != null && arena.activePlayers().contains(killerUuid) && !killerUuid.equals(uuid)) {
                session.addKill(killerUuid);
                if (reason == null || reason.isBlank() || reason.equals("Умер")) reason = "Убит игроком";
                if (killer == null || killer.isBlank()) {
                    Player killerPlayer = onlinePlayers.get(killerUuid);
                    killer = killerPlayer == null ? Optional.ofNullable(Bukkit.getOfflinePlayer(killerUuid).getName()).orElse("") : killerPlayer.getName();
                }
            }

            int placement = Math.max(2, arena.activePlayers().size());
            arena.activePlayers().remove(uuid);
            arena.participants().remove(uuid);
            MatchHistoryManager.Result result = new MatchHistoryManager.Result(
                    uuid, player.getName(), placement,
                    session.kills(uuid),
                    reason == null ? "Выбыл" : reason,
                    killer == null ? "" : killer,
                    System.currentTimeMillis()
            );
            session.addResult(result);
            broadcast(arena, "player-eliminated", "player", player.getName(), "placement", placement);

            if (arena.activePlayers().size() <= 1) finish(arena);
        }
        plugin.getArenaManager().save();

        // Вышедшему с сервера вещи возвращаются при следующем входе: файл остаётся на диске.
        if (!disconnecting && player.isOnline()) restoreIfPending(player, matchId, "returned-after-elimination");
    }

    private void finish(Arena arena) {
        if (arena == null) return;
        synchronized (stateLock) {
            if (arena.state() == ArenaState.FINISH || arena.state() == ArenaState.WAITING) return;

            MatchSession session = sessions.get(arenaKey(arena));
            // Сначала фиксируем FINISH, чтобы обработчики границы/перемещения
            // больше не воспринимали победителя как обычного участника игры.
            arena.setState(ArenaState.FINISH);

            UUID winnerUuid = arena.activePlayers().size() == 1
                    ? arena.activePlayers().iterator().next()
                    : null;

            if (winnerUuid != null) {
                String winnerName = Optional.ofNullable(Bukkit.getOfflinePlayer(winnerUuid).getName())
                        .orElse(winnerUuid.toString().substring(0, 8));

                if (session != null && !session.hasResult(winnerUuid)) {
                    session.addResult(new MatchHistoryManager.Result(
                            winnerUuid, winnerName, 1, session.kills(winnerUuid),
                            "Победитель", "", System.currentTimeMillis()));
                }
                broadcast(arena, "winner", "player", winnerName);
            } else {
                broadcast(arena, "no-winner");
            }

            // Историю записываем до очистки сессии. Так победитель гарантированно
            // попадает в matches.yml как 1 место.
            if (session != null) {
                long ended = System.currentTimeMillis();
                plugin.getMatchHistoryManager().record(
                        arena.id(), session.id(), session.startedAt(), ended, session.results());
            }

            // Сразу завершаем runtime матча. Внутри stop() победитель будет
            // восстановлен, а состояние арены вернётся в WAITING без пятисекундного
            // окна, когда бывший победитель ещё считался активным игроком.
            stop(arena, false);
        }
    }

    public void stop(Arena arena, boolean announce) {
        if (arena == null) return;
        String matchId = null;
        Set<UUID> matchPlayers = new LinkedHashSet<>();
        Set<UUID> inArena = new LinkedHashSet<>();
        synchronized (stateLock) {
            String key = arenaKey(arena);
            MatchSession session = sessions.remove(key);
            if (session != null) {
                matchId = session.id();
                matchPlayers.addAll(session.snapshots().keySet());
                if (session.started() && arena.state() != ArenaState.FINISH && !session.results().isEmpty()) {
                    plugin.getMatchHistoryManager().record(arena.id(), session.id(), session.startedAt(), System.currentTimeMillis(), session.results());
                }
            }
            inArena.addAll(arena.participants());
            inArena.addAll(arena.activePlayers());

            for (UUID uuid : observers.keySet().stream().filter(id -> observers.get(id) == arena).toList()) {
                observers.remove(uuid);
                ObserverState state = observerStates.remove(uuid);
                Player player = onlinePlayers.get(uuid);
                // Режим и место наблюдателя меняются только в его собственном потоке.
                scheduler.entity(player, () -> {
                    if (player.isOnline()) restoreObserver(player, state, "observer-left");
                    else if (state != null) pendingObserverRestores.put(uuid, state);
                }, () -> { if (state != null) pendingObserverRestores.put(uuid, state); });
            }

            BossBar bar = bossBars.remove(key);
            bossBarViewers.remove(key);
            if (bar != null) scheduler.global(bar::removeAll);

            clearArenaRuntime(arena);
        }

        final String stoppedMatch = matchId;
        Set<UUID> release = new LinkedHashSet<>(matchPlayers);
        release.addAll(inArena);
        for (UUID uuid : release) {
            Player player = onlinePlayers.get(uuid);
            // Если игрок не в сети, его вещи вернутся из папки restore при следующем входе.
            if (player == null) continue;
            boolean wasInArena = inArena.contains(uuid);
            scheduler.entity(player, () -> {
                if (!player.isOnline()) return;
                boolean restored = stoppedMatch != null && restoreIfPending(player, stoppedMatch, "inventory-restored");
                // Участник сбора, чьи вещи не сохранялись: просто возвращаем на прежнее место.
                if (!restored && wasInArena) returnToPreviousPlace(player);
                if (announce && wasInArena) plugin.send(player, "game-stopped");
            });
        }

        // Also clear containers on manual/forced stop so no generated loot remains.
        plugin.getChestManager().clearContainers(arena);
        arena.setContainersDirty(false);
        plugin.getArenaManager().save();
    }

    public boolean observe(Player player, Arena arena, boolean spectatorMode) {
        if (arena == null || arena.state() == ArenaState.WAITING) { plugin.send(player, "observe-not-running"); return false; }
        UUID uuid = player.getUniqueId();
        for (Arena other : plugin.getArenaManager().all()) {
            if (other.activePlayers().contains(uuid) || other.participants().contains(uuid)) {
                plugin.send(player, other == arena ? "already-in-game" : "already-in-other-arena");
                return false;
            }
        }
        // При переходе с другой арены сохраняем исходные режим и место, а не промежуточные.
        ObserverState previous = observerStates.get(uuid);
        ObserverState state = previous != null ? previous
                : new ObserverState(player.getLocation().clone(), player.getGameMode(), player.getAllowFlight(), player.isFlying());
        synchronized (stateLock) {
            if (arena.state() == ArenaState.WAITING) { plugin.send(player, "observe-not-running"); return false; }
            Arena previousArena = observers.put(uuid, arena);
            observerStates.put(uuid, state);
            if (previousArena != null && previousArena != arena) removeFromBossBars(player);
        }

        if (spectatorMode) player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        else if (previous != null) {
            player.setGameMode(previous.previousGameMode());
            player.setAllowFlight(previous.previousAllowFlight());
            player.setFlying(previous.previousAllowFlight() && previous.previousFlying());
        }

        Location target = arena.shrinkCenterLocation();
        if (target == null) {
            List<Location> spawns = arena.spawns();
            target = spawns.isEmpty() ? arena.lobby() : spawns.get(0);
        }
        if (target != null) safeTeleport(player, arena.clampToOuter(target));
        createOrUpdateBossBar(arena);
        plugin.send(player, spectatorMode ? "observer-entered-spectator" : "observer-entered-normal", "arena", arena.id());
        return true;
    }

    /** Вызывается из потока наблюдателя. */
    public void leaveObserver(Player player) {
        Arena arena = observers.remove(player.getUniqueId());
        ObserverState state = observerStates.remove(player.getUniqueId());
        if (arena == null && state == null) return;
        restoreObserver(player, state, "observer-left");
        removeFromBossBars(player);
    }

    private void restoreObserver(Player player, ObserverState state, String messageKey) {
        if (player.isOnline() && state != null) {
            player.setGameMode(state.previousGameMode());
            player.setAllowFlight(state.previousAllowFlight());
            player.setFlying(state.previousAllowFlight() && state.previousFlying());
            safeTeleport(player, state.returnLocation());
        }
        plugin.send(player, messageKey);
    }

    public boolean isObserver(UUID uuid, Arena arena) { return observers.get(uuid) == arena; }
    public Arena observerArena(UUID uuid) { return observers.get(uuid); }
    public boolean isPluginTeleport(UUID uuid) { return pluginTeleports.contains(uuid); }

    public void trackOnlinePlayer(Player player) { if (player != null) onlinePlayers.put(player.getUniqueId(), player); }
    public Player getOnlinePlayer(UUID uuid) { return onlinePlayers.get(uuid); }

    public Optional<Arena> findPlayerArena(UUID uuid) {
        for (Arena arena : plugin.getArenaManager().all()) {
            if (arena.participants().contains(uuid) || arena.activePlayers().contains(uuid) ||
                    arena.spectators().contains(uuid) || observers.get(uuid) == arena) return Optional.of(arena);
        }
        return Optional.empty();
    }

    public boolean isInActiveGame(UUID uuid) {
        return findPlayerArena(uuid).map(a -> a.activePlayers().contains(uuid)).orElse(false);
    }

    /** Место, куда участник вернётся после матча, или null. Используется как точка возрождения. */
    public Location returnLocation(Arena arena, UUID uuid) {
        MatchSession session = arena == null ? null : sessions.get(arenaKey(arena));
        PlayerSnapshot snapshot = session == null ? null : session.snapshots().get(uuid);
        return snapshot == null ? null : snapshot.location();
    }

    /** Вызывается из потока игрока при входе на сервер (и при включении плагина для игроков онлайн). */
    public void handlePlayerJoin(Player player) {
        trackOnlinePlayer(player);
        UUID uuid = player.getUniqueId();
        if (restoreStore.has(uuid)) {
            scheduler.entity(player, () -> {
                if (player.isOnline()) restoreIfPending(player, null, "restored-after-leave");
            });
        }
        ObserverState observerState = pendingObserverRestores.remove(uuid);
        if (observerState != null) {
            scheduler.entity(player, () -> restoreObserver(player, observerState, "observer-restored-after-leave"),
                    () -> pendingObserverRestores.put(uuid, observerState));
        }
        boolean participant = false;
        for (Arena arena : plugin.getArenaManager().all()) {
            if (isWaitingParticipant(arena, uuid)) {
                participant = true;
                assignParticipantSpawn(arena, uuid);
                scheduler.entity(player, () -> {
                    if (!player.isOnline() || !isWaitingParticipant(arena, uuid)) return;
                    returnLocations.putIfAbsent(uuid, player.getLocation().clone());
                    teleportParticipantToAssignedSpawn(player, arena);
                });
                break;
            }
        }
        if (!participant && !restoreStore.has(uuid) && returnLocations.containsKey(uuid)) {
            // Пока игрок был не в сети, его убрали из участников: возвращаем с арены на прежнее место.
            scheduler.entity(player, () -> { if (player.isOnline()) returnToPreviousPlace(player); });
        }
        scheduler.entityLater(player, 1L, () -> {
            if (!player.isOnline()) return;
            refreshStaff(player);
            enforceArenaEntry(player);
        });
    }

    /** Вызывается из потока игрока при выходе с сервера. */
    public void handlePlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        Arena activeArena = null;
        synchronized (stateLock) {
            for (Arena arena : plugin.getArenaManager().all()) {
                if (arena.activePlayers().contains(uuid)) { activeArena = arena; break; }
                MatchSession session = sessions.get(arenaKey(arena));
                if (session != null && !session.started() && session.snapshots().remove(uuid) != null) {
                    // Вещи уже сохранены и инвентарь очищен, но матч ещё не начался:
                    // игрок выбывает из набора, вещи вернутся из файла при следующем входе.
                    arena.participants().remove(uuid);
                    arena.clearSpawnSlot(uuid);
                }
            }
        }
        if (activeArena != null) {
            eliminate(player, activeArena, "Вышел из игры", "", true);
        } else if (observers.containsKey(uuid)) {
            ObserverState state = observerStates.remove(uuid);
            if (state != null) pendingObserverRestores.put(uuid, state);
            observers.remove(uuid);
        }
        onlinePlayers.remove(uuid);
        staffOnline.remove(uuid);
        removeFromBossBars(player);
    }

    /** Игрок возродился: если его вещи ещё лежат в файле, а матч для него закончен — возвращаем. */
    public void handlePostRespawn(Player player) {
        if (restoreStore.has(player.getUniqueId())) restoreIfPending(player, null, "restored-after-leave");
    }

    /**
     * Возвращает игроку вещи и состояние из файла восстановления. Только из потока игрока.
     *
     * @param matchId если задан — вернуть только вещи, сохранённые перед этим матчем;
     *                null — любые, кроме вещей матча, в котором игрок участвует прямо сейчас
     * @return true, если возврат выполнен или отложен (игрок мёртв) либо не удался; вызывающий код
     *         в этих случаях не должен сам телепортировать игрока
     */
    private boolean restoreIfPending(Player player, String matchId, String messageKey) {
        return restoreIfPending(player, matchId, messageKey, 0);
    }

    private boolean restoreIfPending(Player player, String matchId, String messageKey, int attempt) {
        UUID uuid = player.getUniqueId();
        String pendingMatch = restoreStore.pendingMatch(uuid);
        if (pendingMatch == null) return false;
        if (matchId != null ? !matchId.equals(pendingMatch) : isInLiveMatch(uuid, pendingMatch)) return false;
        if (player.isDead()) {
            // Мёртвому игроку вещи не вернуть. Ждём возрождения; файл остаётся на диске.
            if (attempt < 60) {
                scheduler.entityLater(player, 10L, () -> {
                    if (player.isOnline()) restoreIfPending(player, matchId, messageKey, attempt + 1);
                });
            }
            return true;
        }
        PlayerSnapshot snapshot;
        try {
            snapshot = restoreStore.load(uuid);
            if (snapshot == null) return false;
            // Лут из сетки крафта и с курсора уходит в игровой инвентарь, который сейчас будет заменён.
            player.closeInventory();
            snapshot.restoreState(player);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось вернуть вещи игроку " + player.getName() + " (" + uuid
                    + "). Файл восстановления оставлен на диске.", e);
            plugin.send(player, "restore-failed");
            return true;
        }
        try {
            // Сохраняем данные игрока до удаления файла: иначе сбой сервера между возвратом вещей
            // и очередным автосохранением оставил бы игрока с игровым инвентарём и без файла.
            player.saveData();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось сразу сохранить данные игрока " + player.getName(), e);
        }
        restoreStore.delete(uuid);
        returnLocations.remove(uuid);
        Location back = snapshot.location();
        if (back == null && !Bukkit.getWorlds().isEmpty()) back = Bukkit.getWorlds().get(0).getSpawnLocation();
        safeTeleport(player, back);
        if (messageKey != null) plugin.send(player, messageKey);
        return true;
    }

    /** true, если вещи игрока сохранены перед матчем, в котором он участвует прямо сейчас. */
    private boolean isInLiveMatch(UUID uuid, String matchId) {
        for (Arena arena : plugin.getArenaManager().all()) {
            MatchSession session = sessions.get(arenaKey(arena));
            if (session != null && session.id().equals(matchId)
                    && (arena.activePlayers().contains(uuid) || arena.participants().contains(uuid))) return true;
        }
        return false;
    }

    public MatchSession liveSession(Arena arena) { return sessions.get(arenaKey(arena)); }

    public List<MatchHistoryManager.Result> liveResults(Arena arena) {
        MatchSession session = liveSession(arena);
        if (session == null) return List.of();
        List<MatchHistoryManager.Result> result = new ArrayList<>(session.results());
        result.sort(Comparator.comparingInt(MatchHistoryManager.Result::placement));
        return result;
    }

    public List<MatchHistoryManager.Result> liveKillLeaderboard(Arena arena) {
        MatchSession session = liveSession(arena);
        if (session == null) return List.of();
        List<MatchHistoryManager.Result> list = new ArrayList<>();
        Set<UUID> uuids = new LinkedHashSet<>(session.snapshots().keySet());
        for (MatchHistoryManager.Result result : session.results()) uuids.add(result.uuid());
        for (UUID uuid : uuids) {
            MatchHistoryManager.Result existing = session.results.values().stream().filter(r -> r.uuid().equals(uuid)).findFirst().orElse(null);
            String name = existing != null ? existing.name() : Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString().substring(0, 8));
            int placement = existing != null ? existing.placement() : 0;
            String reason = existing != null ? existing.reason() : "В игре";
            String killer = existing != null ? existing.killer() : "";
            long time = existing != null ? existing.time() : session.startedAt();
            list.add(new MatchHistoryManager.Result(uuid, name, placement, session.kills(uuid), reason, killer, time));
        }
        list.sort(Comparator.comparingInt(MatchHistoryManager.Result::kills).reversed().thenComparing(r -> r.name(), String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public List<MatchHistoryManager.Result> leaderboard(Arena arena) {
        MatchHistoryManager.MatchRecord latest = plugin.getMatchHistoryManager().latest(arena.id());
        if (latest == null) return List.of();
        List<MatchHistoryManager.Result> list = new ArrayList<>(latest.results());
        list.sort(Comparator.comparingInt(MatchHistoryManager.Result::kills).reversed().thenComparingInt(MatchHistoryManager.Result::placement));
        return list;
    }

    private void enforceArenaEntry(Player player) {
        for (Arena arena : plugin.getArenaManager().all()) {
            if (arena.state() == ArenaState.WAITING || arena.state() == ArenaState.FINISH || arena.state() == ArenaState.STOPPED) continue;
            if (!arena.insideOuter(player.getLocation())) continue;
            UUID uuid = player.getUniqueId();
            if (arena.activePlayers().contains(uuid) || arena.participants().contains(uuid) || isObserver(uuid, arena)) return;
            sendOutOfArena(player, arena);
            return;
        }
    }

    public void enforceNonParticipant(Player player, Arena arena) {
        if (player == null || arena == null) return;
        if (arena.state() == ArenaState.WAITING || arena.state() == ArenaState.FINISH || arena.state() == ArenaState.STOPPED) return;

        UUID uuid = player.getUniqueId();

        // Участник уже зарегистрирован на этой арене. Во время обратного отсчёта
        // он ещё не попадает в activePlayers(), но это всё равно НЕ посторонний игрок.
        // Раньше этот метод отправлял участника на /spawn каждый тик до START.
        if (arena.participants().contains(uuid)) return;

        if (arena.activePlayers().contains(uuid) || isObserver(uuid, arena)) return;
        if (arena.insideOuter(player.getLocation())) sendOutOfArena(player, arena);
    }

    private void enforceAllNonParticipants() {
        List<Arena> running = plugin.getArenaManager().all().stream()
                .filter(arena -> arena.state() != ArenaState.WAITING).toList();
        if (running.isEmpty()) return;
        for (Player player : new ArrayList<>(onlinePlayers.values())) {
            scheduler.entity(player, () -> {
                if (!player.isOnline()) return;
                refreshStaff(player);
                for (Arena arena : running) enforceNonParticipant(player, arena);
            });
        }
    }

    /** Права проверяются в потоке игрока; результат используется при отрисовке границы. */
    private void refreshStaff(Player player) {
        if (plugin.getAccessManager().hasAccess(player)) staffOnline.add(player.getUniqueId());
        else staffOnline.remove(player.getUniqueId());
    }

    public void rejectArenaEntry(Player player, Arena arena) {
        if (arena == null || player == null || !player.isOnline()) return;
        sendOutOfArena(player, arena);
    }

    private void sendOutOfArena(Player player, Arena arena) {
        if (player == null || !player.isOnline()) return;
        plugin.send(player, "not-in-game-area", "arena", arena.id());
        pluginTeleports.add(player.getUniqueId());
        try { player.performCommand("spawn"); } catch (Throwable ignored) { }
        scheduler.entityLater(player, 2L, () -> {
            pluginTeleports.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            for (Arena other : plugin.getArenaManager().all()) {
                if (other.state() != ArenaState.WAITING && other.insideOuter(player.getLocation()) &&
                        !other.activePlayers().contains(player.getUniqueId()) && !isObserver(player.getUniqueId(), other)) {
                    player.teleportAsync(player.getWorld().getSpawnLocation());
                    break;
                }
            }
        });
    }

    private void addResult(Arena arena, UUID uuid, Player player, String reason, String killer, int placement) {
        MatchSession session = sessions.get(arenaKey(arena));
        if (session == null || session.hasResult(uuid)) return;
        String name = player == null ? Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString().substring(0, 8)) : player.getName();
        session.addResult(new MatchHistoryManager.Result(uuid, name, placement, session.kills(uuid), reason, killer, System.currentTimeMillis()));
    }

    private String humanizeCause(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case FALL -> "Падение";
            case FIRE, FIRE_TICK, HOT_FLOOR -> "Огонь";
            case LAVA -> "Лава";
            case DROWNING -> "Утопление";
            case VOID -> "Пустота";
            case FALLING_BLOCK -> "Падающий блок";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "Взрыв";
            case CONTACT -> "Столкновение";
            case SUFFOCATION -> "Удушье";
            case MAGIC -> "Магический урон";
            case POISON -> "Отравление";
            case WITHER -> "Иссушение";
            case STARVATION -> "Голод";
            case FREEZE -> "Замерзание";
            default -> cause.name();
        };
    }

    // BossBar меняется только в глобальном потоке: добавление зрителей из потоков игроков
    // одновременно с обновлением заголовка портило бы общий список зрителей.
    private void createOrUpdateBossBar(Arena arena) {
        scheduler.global(() -> updateBossBarGlobal(arena));
    }

    private void removeFromBossBars(Player player) {
        UUID uuid = player.getUniqueId();
        scheduler.global(() -> {
            for (Map.Entry<String, BossBar> entry : bossBars.entrySet()) {
                Set<UUID> viewers = bossBarViewers.get(entry.getKey());
                if (viewers != null) viewers.remove(uuid);
                entry.getValue().removePlayer(player);
            }
        });
    }

    private void updateBossBarGlobal(Arena arena) {
        String key = arenaKey(arena);
        if (arena.state() == ArenaState.WAITING || plugin.getArenaManager().get(arena.id()) != arena) {
            // Игра уже остановлена: запоздавшее обновление не должно создавать полосу заново.
            BossBar stale = bossBars.remove(key);
            bossBarViewers.remove(key);
            if (stale != null) stale.removeAll();
            return;
        }
        BossBar bar = bossBars.computeIfAbsent(key, ignored -> Bukkit.createBossBar(HungerGamesPlugin.color("&5HungerGames"), BarColor.PURPLE, BarStyle.SEGMENTED_10));
        long elapsed = arena.phaseElapsedSeconds();
        String title; double progress;
        switch (arena.state()) {
            case COUNTDOWN -> {
                long remaining = Math.max(0L, arena.settings().countdownSeconds - elapsed);
                title = "&eСЕЙЧАС: &fПОДГОТОВКА &7| &cСТАРТ ЧЕРЕЗ: &f" + remaining + " сек. &7| Игроков: &f" + arena.participants().size() + "/" + arena.maxPlayers();
                progress = arena.settings().countdownSeconds <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, remaining / (double) arena.settings().countdownSeconds));
                bar.setColor(BarColor.YELLOW);
            }
            case PROTECTION -> {
                long remaining = Math.max(0L, arena.settings().protectionSeconds - elapsed);
                title = "&bСЕЙЧАС: &fЗАЩИТА &7| До конца: &f" + remaining + " сек. &7| Живых: &f" + arena.activePlayers().size();
                progress = arena.settings().protectionSeconds <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, remaining / (double) arena.settings().protectionSeconds));
                bar.setColor(BarColor.BLUE);
            }
            case GAME -> {
                long beforeShrink = arena.settings().shrinkStartDelaySeconds;
                if (elapsed < beforeShrink) {
                    long remaining = beforeShrink - elapsed;
                    title = "&bСЕЙЧАС: &fИГРА &7| До сужения: &f" + remaining + " сек. &7| Граница: &f" + arena.effectiveBorderMode().displayName() + " &7| Живых: &f" + arena.activePlayers().size();
                    progress = beforeShrink <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, remaining / (double) beforeShrink));
                    bar.setColor(BarColor.BLUE);
                } else {
                    long afterDelay = Math.max(0L, elapsed - beforeShrink);
                    long duration = Math.max(1L, arena.settings().shrinkStageDurationSeconds);
                    int totalStages = Math.max(1, arena.settings().shrinkStages);
                    int stage = (int) Math.min(totalStages, afterDelay / duration + 1);
                    long stageElapsed = afterDelay - (long)(stage - 1) * duration;
                    long stageRemaining = Math.max(0L, duration - stageElapsed);
                    title = "&dСЕЙЧАС: &fСУЖЕНИЕ &7| Стадия: &f" + stage + "/" + totalStages + " &7| Следующая: &f" + stageRemaining + " сек. &7| Граница: &f" + arena.effectiveBorderMode().displayName() + " &7| Живых: &f" + arena.activePlayers().size();
                    progress = Math.max(0.0, Math.min(1.0, stageRemaining / (double)duration));
                    bar.setColor(effectiveBarColor(arena));
                }
            }
            default -> { title = "&5HungerGames"; progress = 1.0; bar.setColor(BarColor.PURPLE); }
        }
        bar.setTitle(HungerGamesPlugin.color(title));
        bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        Set<UUID> desired = new HashSet<>(arena.activePlayers());
        desired.addAll(arena.spectators());
        desired.addAll(arena.participants());
        observers.forEach((uuid, observed) -> { if (observed == arena) desired.add(uuid); });
        Set<UUID> current = bossBarViewers.computeIfAbsent(key, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        for (UUID uuid : desired) {
            Player player = onlinePlayers.get(uuid);
            if (player != null && current.add(uuid)) bar.addPlayer(player);
        }
        for (UUID uuid : new HashSet<>(current)) {
            if (!desired.contains(uuid)) {
                current.remove(uuid);
                Player player = onlinePlayers.get(uuid);
                if (player != null) bar.removePlayer(player);
            }
        }
    }

    private BarColor effectiveBarColor(Arena arena) {
        BorderMode mode = arena.effectiveBorderMode();
        if (arena.settings().borderMode == BorderMode.CHANGING) {
            double p = arena.borderChangeColorProgress();
            boolean softFirst = arena.settings().changingStartMode == BorderMode.SOFT_TELEPORT;
            if (softFirst) return p >= 0.5 ? BarColor.RED : BarColor.GREEN;
            return p >= 0.5 ? BarColor.GREEN : BarColor.RED;
        }
        return mode == BorderMode.SOFT_TELEPORT ? BarColor.GREEN : BarColor.RED;
    }

    private void broadcast(Arena arena, String key, Object... replacements) {
        String message = plugin.msg(key, replacements);
        Set<UUID> receivers = new HashSet<>(arena.activePlayers());
        receivers.addAll(arena.spectators());
        // Во время обратного отсчёта игроки ещё числятся только участниками.
        receivers.addAll(arena.participants());
        for (UUID uuid : receivers) {
            Player player = onlinePlayers.get(uuid);
            if (player != null) scheduler.entity(player, () -> { if (player.isOnline()) player.sendMessage(message); });
        }
    }

    private void safeTeleport(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null) return;
        UUID uuid = player.getUniqueId();
        pluginTeleports.add(uuid);
        scheduler.entity(player, () -> {
            if (!player.isOnline()) { pluginTeleports.remove(uuid); return; }
            player.teleportAsync(location);
            scheduler.entityLater(player, 2L, () -> pluginTeleports.remove(uuid));
        }, () -> pluginTeleports.remove(uuid));
    }

    private void renderBorderParticles(Arena arena) {
        if (!plugin.getConfig().getBoolean("border-particles.enabled", true)) return;
        if (arena == null) return;
        Location pos1 = arena.pos1(), pos2 = arena.pos2();
        UUID worldUid = arena.worldUid();
        if (pos1 == null || pos2 == null || worldUid == null) return;
        if (arena.state() != ArenaState.COUNTDOWN && arena.state() != ArenaState.PROTECTION && arena.state() != ArenaState.GAME) return;

        World world = Bukkit.getWorld(worldUid);
        if (world == null) return;
        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ());
        if (minX >= maxX || minZ >= maxZ) return;
        double minY = Math.min(pos1.getY(), pos2.getY());
        double maxY = Math.max(pos1.getY(), pos2.getY());
        double step = Math.max(1.5, plugin.getConfig().getDouble("border-particles.step", 2.0));
        float size = (float) Math.max(0.35, Math.min(1.0, plugin.getConfig().getDouble("border-particles.size", 0.8)));
        // Граница рисуется только рядом с игроком: вся стена большой арены — это тысячи пакетов в секунду.
        double radius = Math.max(4.0, plugin.getConfig().getDouble("border-particles.view-radius", 24.0));
        double height = Math.max(0.0, plugin.getConfig().getDouble("border-particles.height", 5.0));
        double rowStep = Math.max(0.5, plugin.getConfig().getDouble("border-particles.row-step", 1.25));
        Particle.DustOptions dust = new Particle.DustOptions(currentBorderColor(arena), size);
        boolean shrink = arena.shrinkActive();
        double shrinkMinX = arena.shrinkCenterX() - arena.currentHalfX();
        double shrinkMaxX = arena.shrinkCenterX() + arena.currentHalfX();
        double shrinkMinZ = arena.shrinkCenterZ() - arena.currentHalfZ();
        double shrinkMaxZ = arena.shrinkCenterZ() + arena.currentHalfZ();

        Set<UUID> viewers = new HashSet<>(arena.activePlayers());
        viewers.addAll(arena.participants());
        viewers.addAll(arena.spectators());
        observers.forEach((uuid, observed) -> { if (observed == arena) viewers.add(uuid); });
        viewers.addAll(staffOnline);
        for (UUID uuid : viewers) {
            Player viewer = onlinePlayers.get(uuid);
            if (viewer == null) continue;
            scheduler.entity(viewer, () -> {
                if (!viewer.isOnline() || viewer.getWorld() != world) return;
                Location at = viewer.getLocation();
                double baseY = Math.max(minY + 1.0, Math.min(maxY - 1.0, at.getY()));
                double topY = Math.min(maxY, baseY + height);
                spawnBorderWall(viewer, at, radius, minX, maxX, minZ, maxZ, baseY, topY, step, rowStep, dust);
                if (shrink) {
                    spawnBorderWall(viewer, at, radius, shrinkMinX, shrinkMaxX, shrinkMinZ, shrinkMaxZ, baseY, topY, step, rowStep, dust);
                }
            });
        }
    }

    private void spawnBorderWall(Player viewer, Location at, double radius, double minX, double maxX, double minZ, double maxZ,
                                  double baseY, double topY, double step, double rowStep, Particle.DustOptions dust) {
        for (double y = baseY; y <= topY + 0.01; y += rowStep) {
            spawnBorderLine(viewer, true, minX, maxX, minZ, at.getX(), at.getZ(), radius, y, step, dust);
            spawnBorderLine(viewer, true, minX, maxX, maxZ, at.getX(), at.getZ(), radius, y, step, dust);
            spawnBorderLine(viewer, false, minZ, maxZ, minX, at.getZ(), at.getX(), radius, y, step, dust);
            spawnBorderLine(viewer, false, minZ, maxZ, maxX, at.getZ(), at.getX(), radius, y, step, dust);
        }
    }

    /**
     * Отрезок стены от from до to вдоль оси X (alongX) или Z; fixed — координата стены по другой оси.
     * Рисуется только часть в пределах radius от игрока.
     */
    private void spawnBorderLine(Player viewer, boolean alongX, double from, double to, double fixed,
                                 double viewerAlong, double viewerAcross, double radius,
                                 double y, double step, Particle.DustOptions dust) {
        if (Math.abs(viewerAcross - fixed) > radius) return;
        double start = Math.max(from, viewerAlong - radius);
        double end = Math.min(to, viewerAlong + radius);
        if (start > end) return;
        // Точки привязаны к сетке от начала стены, чтобы частицы не смещались при движении игрока.
        double first = from + Math.ceil((start - from) / step - 1.0e-9) * step;
        double last = first;
        for (double along = first; along <= end + 1.0e-9; along += step) {
            spawnBorderParticle(viewer, alongX, along, fixed, y, dust);
            last = along;
        }
        // Угол стены не всегда попадает в сетку.
        if (end >= to - 1.0e-9 && to - last > step * 0.25) spawnBorderParticle(viewer, alongX, to, fixed, y, dust);
    }

    private void spawnBorderParticle(Player viewer, boolean alongX, double along, double fixed, double y, Particle.DustOptions dust) {
        double x = alongX ? along : fixed;
        double z = alongX ? fixed : along;
        viewer.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust, true);
    }

    private Color currentBorderColor(Arena arena) {
        if (arena.settings().borderMode != BorderMode.CHANGING) {
            return arena.effectiveBorderMode() == BorderMode.SOFT_TELEPORT ? Color.LIME : Color.RED;
        }
        Color from = arena.settings().changingStartMode == BorderMode.SOFT_TELEPORT ? Color.LIME : Color.RED;
        Color to = arena.settings().changingTargetMode == BorderMode.SOFT_TELEPORT ? Color.LIME : Color.RED;
        double p = arena.borderChangeColorProgress();
        int r = (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * p);
        int g = (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * p);
        int b = (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * p);
        return Color.fromRGB(r, g, b);
    }

    private String arenaKey(Arena arena) { return arena.id().toLowerCase(Locale.ROOT); }
}
