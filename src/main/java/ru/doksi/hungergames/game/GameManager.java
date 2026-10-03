package ru.doksi.hungergames.game;

import org.bukkit.Bukkit;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class GameManager {
    private final HungerGamesPlugin plugin;
    private final FoliaScheduler scheduler;
    private ScheduledTask taskId;
    private ScheduledTask particleTaskId;
    private final Set<UUID> pluginTeleports = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, BossBar> bossBars = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> bossBarViewers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, MatchSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Arena> observers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, ObserverState> observerStates = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, PlayerSnapshot> participantPreGameSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, PlayerSnapshot> pendingRestores = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Player> onlinePlayers = new java.util.concurrent.ConcurrentHashMap<>();

    private record ObserverState(Location returnLocation, org.bukkit.GameMode previousGameMode, boolean previousAllowFlight, boolean previousFlying) { }
    private final Map<UUID, ObserverState> pendingObserverRestores = new java.util.concurrent.ConcurrentHashMap<>();

    public GameManager(HungerGamesPlugin plugin) { this.plugin = plugin; this.scheduler = new FoliaScheduler(plugin); }

    public static final class MatchSession {
        private final String id;
        private final long startedAt;
        private final Map<UUID, PlayerSnapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<UUID, Integer> kills = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<UUID, MatchHistoryManager.Result> results = new java.util.concurrent.ConcurrentHashMap<>();

        public MatchSession(String id, long startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }
        public String id() { return id; }
        public long startedAt() { return startedAt; }
        public Map<UUID, PlayerSnapshot> snapshots() { return snapshots; }
        public int kills(UUID uuid) { return kills.getOrDefault(uuid, 0); }
        public void addKill(UUID uuid) { kills.merge(uuid, 1, Integer::sum); }
        public Collection<MatchHistoryManager.Result> results() { return Collections.unmodifiableCollection(results.values()); }
        public void addResult(MatchHistoryManager.Result result) { results.put(result.uuid(), result); }
        public boolean hasResult(UUID uuid) { return results.containsKey(uuid); }
    }

    public void startTicker() {
        taskId = scheduler.globalRepeating(20L, 20L, this::tick);
        particleTaskId = scheduler.globalRepeating(5L, 5L, () -> {
            for (Arena arena : plugin.getArenaManager().all()) renderBorderParticles(arena);
        });
    }

    public void shutdown() {
        if (taskId != null) taskId.cancel();
        if (particleTaskId != null) particleTaskId.cancel();
        for (Arena arena : plugin.getArenaManager().all()) stop(arena, false);
        for (Player player : new ArrayList<>(onlinePlayers.values())) {
            if (player != null) {
                scheduler.entity(player, () -> {
                    if (!player.isOnline()) return;
                    restorePending(player);
                    leaveObserver(player);
                });
            }
        }
        participantPreGameSnapshots.clear();
        onlinePlayers.clear();
    }

    private void tick() {
        for (Arena arena : plugin.getArenaManager().all()) {
            switch (arena.state()) {
                case COUNTDOWN -> tickCountdown(arena);
                case PROTECTION -> tickProtection(arena);
                case GAME -> tickGame(arena);
                default -> { }
            }
        }
        enforceBossBarsOnlyForActivePlayers();
        enforceAllNonParticipants();
    }

    private void tickCountdown(Arena arena) {
        long elapsed = arena.phaseElapsedSeconds();
        long remaining = Math.max(0, arena.settings().countdownSeconds - elapsed);
        createOrUpdateBossBar(arena);
        if (remaining > 0 && remaining <= 5) broadcast(arena, "countdown", "seconds", remaining);
        if (elapsed >= arena.settings().countdownSeconds) startMatch(arena);
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

    public boolean join(Player player, Arena arena) {
        if (player == null || arena == null) return false;
        if (arena.state() != ArenaState.WAITING || !arena.collecting()) { plugin.send(player, "join-open-only"); return false; }
        if (arena.spawns().isEmpty()) { plugin.send(player, "no-spawn-slots"); return false; }
        if (arena.participants().contains(player.getUniqueId())) { plugin.send(player, "already-participant"); return false; }
        if (arena.participants().size() >= arena.maxPlayers()) { plugin.send(player, "join-full"); return false; }
        if (findPlayerArena(player.getUniqueId()).isPresent()) { plugin.send(player, "already-in-other-arena"); return false; }
        int freeSpawnIndex = arena.firstFreeSpawnSlot();
        if (freeSpawnIndex < 0) { plugin.send(player, "no-spawn-slots"); return false; }
        rememberParticipantState(player);
        arena.participants().add(player.getUniqueId());
        arena.assignSpawnSlot(player.getUniqueId(), freeSpawnIndex);
        safeTeleport(player, arena.spawns().get(freeSpawnIndex));
        plugin.send(player, "joined", "arena", arena.id(), "slot", freeSpawnIndex + 1);
        plugin.getArenaManager().save();
        return true;
    }

    public int assignParticipantSpawn(Arena arena, UUID uuid) {
        if (arena == null || uuid == null) return -1;
        Integer existing = arena.spawnSlot(uuid);
        if (existing != null && existing >= 0 && existing < arena.spawns().size()) return existing;
        int slot = arena.firstFreeSpawnSlot();
        if (slot >= 0) arena.assignSpawnSlot(uuid, slot);
        return slot;
    }

    public void rememberParticipantState(Player player) {
        if (player == null || !player.isOnline()) return;
        participantPreGameSnapshots.putIfAbsent(player.getUniqueId(), PlayerSnapshot.capture(player));
    }

    public void teleportParticipantToAssignedSpawn(Player player, Arena arena) {
        if (player == null || arena == null || !player.isOnline()) return;
        if (arena.state() != ArenaState.WAITING && arena.state() != ArenaState.COUNTDOWN) return;
        int slot = assignParticipantSpawn(arena, player.getUniqueId());
        if (slot >= 0 && slot < arena.spawns().size()) safeTeleport(player, arena.spawns().get(slot));
    }

    public void leave(Player player) {
        Arena activeArena = findPlayerArena(player.getUniqueId()).orElse(null);
        if (activeArena != null && activeArena.activePlayers().contains(player.getUniqueId())) {
            MatchSession session = sessions.get(arenaKey(activeArena));
            PlayerSnapshot snapshot = session == null ? null : session.snapshots().get(player.getUniqueId());
            eliminate(player, activeArena, "Покинул игру", "", false);
            return;
        }
        Arena participantArena = plugin.getArenaManager().all().stream().filter(a -> a.participants().contains(player.getUniqueId())).findFirst().orElse(null);
        if (participantArena != null && (participantArena.state() == ArenaState.WAITING || participantArena.state() == ArenaState.COUNTDOWN)) {
            participantArena.participants().remove(player.getUniqueId());
            participantArena.clearSpawnSlot(player.getUniqueId());
            PlayerSnapshot snapshot = participantPreGameSnapshots.remove(player.getUniqueId());
            if (snapshot != null) {
                snapshot.restoreState(player);
                safeTeleport(player, snapshot.location());
            } else if (participantArena.lobby() != null) safeTeleport(player, participantArena.lobby());
            plugin.send(player, "left");
            plugin.getArenaManager().save();
            return;
        }
        if (observers.containsKey(player.getUniqueId())) {
            leaveObserver(player);
            return;
        }
        plugin.send(player, "not-in-game");
    }

    public boolean start(Arena arena) {
        if (arena == null || arena.state() != ArenaState.WAITING || arena.collecting()) return false;
        if (arena.participants().size() < arena.minPlayers() || arena.spawns().isEmpty() || arena.participants().size() > arena.spawns().size()) return false;
        // onlinePlayers — это потокобезопасный кэш, который обновляется при join/quit.
        // Не вызываем Player.isOnline() из глобального региона Folia.
        long online = arena.participants().stream().filter(uuid -> onlinePlayers.get(uuid) != null).count();
        if (online < arena.minPlayers()) return false;
        arena.setState(ArenaState.COUNTDOWN);
        return true;
    }

    private void startMatch(Arena arena) {
        String matchId = arena.id() + "-" + System.currentTimeMillis();
        MatchSession session = new MatchSession(matchId, System.currentTimeMillis());
        sessions.put(arenaKey(arena), session);
        arena.activePlayers().clear();
        arena.activePlayers().addAll(arena.participants());
        arena.spectators().clear();
        arena.prepareShrink();
        arena.setCurrentShrinkStage(0);
        arena.startBorderChangeClock();
        plugin.getChestManager().refill(arena);
        arena.markRefillNow();

        List<Location> spawns = arena.spawns();
        for (UUID uuid : new ArrayList<>(arena.activePlayers())) {
            Player player = onlinePlayers.get(uuid);
            // Не читаем состояние Player из глобального региона Folia.
            // Если игрок отсутствует в кэше, его quit уже обработан или игрок недоступен.
            if (player == null) {
                arena.activePlayers().remove(uuid);
                participantPreGameSnapshots.remove(uuid);
                arena.participants().remove(uuid);
                addResult(arena, uuid, null, "Не подключился к игре", "", arena.activePlayers().size() + 1);
                continue;
            }
            final Player currentPlayer = player;
            scheduler.entity(currentPlayer, () -> {
                if (!currentPlayer.isOnline() || !arena.activePlayers().contains(currentPlayer.getUniqueId())) return;
                PlayerSnapshot preGame = participantPreGameSnapshots.remove(currentPlayer.getUniqueId());
                PlayerSnapshot snapshot = preGame == null ? PlayerSnapshot.capture(currentPlayer) : preGame;
                session.snapshots().put(currentPlayer.getUniqueId(), snapshot);
                session.kills.put(currentPlayer.getUniqueId(), 0);
                snapshot.prepareForMatch(currentPlayer);
                // Игрок уже был телепортирован на назначенный спавн при /hg join.
                // Повторно телепортировать его в момент START не нужно: это вызывало
                // заметное двойное/тройное перемещение при переходе COUNTDOWN -> GAME.
            });
        }

        if (arena.activePlayers().size() < 1) { stop(arena, false); return; }
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

    public void eliminate(Player player, Arena arena, String reason, String killer, UUID killerUuid, boolean disconnecting) {
        if (arena == null || !arena.activePlayers().contains(player.getUniqueId())) return;
        MatchSession session = sessions.get(arenaKey(arena));
        if (session == null) return;

        if (killerUuid != null && arena.activePlayers().contains(killerUuid) && !killerUuid.equals(player.getUniqueId())) {
            session.addKill(killerUuid);
            if (reason == null || reason.isBlank() || reason.equals("Умер")) reason = "Убит игроком";
            if (killer == null || killer.isBlank()) {
                Player killerPlayer = onlinePlayers.get(killerUuid);
                killer = killerPlayer == null ? Optional.ofNullable(Bukkit.getOfflinePlayer(killerUuid).getName()).orElse("") : killerPlayer.getName();
            }
        }

        int placement = Math.max(2, arena.activePlayers().size());
        arena.activePlayers().remove(player.getUniqueId());
        arena.participants().remove(player.getUniqueId());
        MatchHistoryManager.Result result = new MatchHistoryManager.Result(
                player.getUniqueId(), player.getName(), placement,
                session.kills(player.getUniqueId()),
                reason == null ? "Выбыл" : reason,
                killer == null ? "" : killer,
                System.currentTimeMillis()
        );
        session.addResult(result);
        broadcast(arena, "player-eliminated", "player", player.getName(), "placement", placement);
        plugin.getArenaManager().save();

        PlayerSnapshot snapshot = session.snapshots().get(player.getUniqueId());
        if (disconnecting || !player.isOnline()) {
            if (snapshot != null) pendingRestores.put(player.getUniqueId(), snapshot);
        } else {
            restoreAfterElimination(player, snapshot);
        }

        if (arena.activePlayers().size() <= 1) finish(arena);
    }

    private void finish(Arena arena) {
        if (arena == null || arena.state() == ArenaState.FINISH || arena.state() == ArenaState.WAITING) return;

        MatchSession session = sessions.get(arenaKey(arena));
        // Сначала фиксируем FINISH, чтобы обработчики границы/перемещения
        // больше не воспринимали победителя как обычного участника игры.
        arena.setState(ArenaState.FINISH);

        UUID winnerUuid = arena.activePlayers().size() == 1
                ? arena.activePlayers().iterator().next()
                : null;

        if (winnerUuid != null) {
            Player winner = onlinePlayers.get(winnerUuid);
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

        plugin.getChestManager().clearContainers(arena);

        // Сразу завершаем runtime матча. Внутри stop() победитель будет
        // восстановлен, а состояние арены вернётся в WAITING без пятисекундного
        // окна, когда бывший победитель ещё считался активным игроком.
        stop(arena, false);
    }

    public void stop(Arena arena, boolean announce) {
        if (arena == null) return;
        MatchSession session = sessions.get(arenaKey(arena));
        if (session != null && arena.state() != ArenaState.WAITING) {
            if (!session.results().isEmpty() || !arena.activePlayers().isEmpty()) {
                for (UUID uuid : new HashSet<>(arena.activePlayers())) {
                    Player player = onlinePlayers.get(uuid);
                    if (player != null) {
                        PlayerSnapshot snapshot = session.snapshots().get(uuid);
                        scheduler.entity(player, () -> {
                            if (player.isOnline()) restoreAfterMatch(player, snapshot);
                            else if (snapshot != null) pendingRestores.put(uuid, snapshot);
                        });
                    } else if (session.snapshots().containsKey(uuid)) pendingRestores.put(uuid, session.snapshots().get(uuid));
                }
            }
            if (arena.state() != ArenaState.FINISH && !session.results().isEmpty()) {
                plugin.getMatchHistoryManager().record(arena.id(), session.id(), session.startedAt(), System.currentTimeMillis(), session.results());
            }
        }

        // Also clear containers on manual/forced stop so no generated loot remains.
        plugin.getChestManager().clearContainers(arena);

        for (UUID uuid : new HashSet<>(arena.spectators())) {
            arena.spectators().remove(uuid);
        }
        for (UUID uuid : observers.keySet().stream().filter(id -> observers.get(id) == arena).toList()) {
            Player player = onlinePlayers.get(uuid);
            if (player != null && player.isOnline()) leaveObserver(player);
            else {
                ObserverState state = observerStates.remove(uuid);
                if (state != null) pendingObserverRestores.put(uuid, state);
                observers.remove(uuid);
            }
        }

        BossBar bar = bossBars.remove(arenaKey(arena));
        Set<UUID> viewers = bossBarViewers.remove(arenaKey(arena));
        if (bar != null && viewers != null) {
            for (UUID uuid : viewers) {
                Player viewer = onlinePlayers.get(uuid);
                if (viewer != null) scheduler.entity(viewer, () -> { if (viewer.isOnline()) bar.removePlayer(viewer); });
            }
        }
        // Любые незавершённые pre-game snapshots относятся только к старому матчу.
        for (UUID uuid : new HashSet<>(arena.participants())) {
            participantPreGameSnapshots.remove(uuid);
        }
        arena.activePlayers().clear();
        arena.spectators().clear();
        arena.participants().clear();
        // После окончания матча все старые привязки игроков к точкам появления
        // должны быть удалены. Иначе firstFreeSpawnSlot() считает старые точки занятыми
        // и следующую игру на этой же арене начать невозможно.
        arena.spawnSlots().clear();
        arena.setState(ArenaState.WAITING);
        arena.setCollecting(false);
        sessions.remove(arenaKey(arena));
        if (announce) broadcast(arena, "game-stopped");
        plugin.getArenaManager().save();
    }

    public boolean observe(Player player, Arena arena, boolean spectatorMode) {
        if (arena == null || arena.state() == ArenaState.WAITING) { plugin.send(player, "observe-not-running"); return false; }
        if (arena.activePlayers().contains(player.getUniqueId())) {
            plugin.send(player, "already-in-game");
            return false;
        }
        leaveOtherArenaObservation(player.getUniqueId());
        ObserverState state = new ObserverState(player.getLocation().clone(), player.getGameMode(), player.getAllowFlight(), player.isFlying());
        observers.put(player.getUniqueId(), arena);
        observerStates.put(player.getUniqueId(), state);

        if (spectatorMode) player.setGameMode(org.bukkit.GameMode.SPECTATOR);

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

    public void leaveObserver(Player player) {
        Arena arena = observers.remove(player.getUniqueId());
        ObserverState state = observerStates.remove(player.getUniqueId());
        if (arena == null && state == null) return;
        if (player.isOnline() && state != null) {
            player.setGameMode(state.previousGameMode());
            player.setAllowFlight(state.previousAllowFlight());
            player.setFlying(state.previousAllowFlight() && state.previousFlying());
            safeTeleport(player, state.returnLocation());
        }
        BossBar bar = arena == null ? null : bossBars.get(arenaKey(arena));
        if (bar != null && bar.getPlayers().contains(player)) bar.removePlayer(player);
        plugin.send(player, "observer-left");
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

    public void handlePlayerJoin(Player player) {
        trackOnlinePlayer(player);
        PlayerSnapshot snapshot = pendingRestores.remove(player.getUniqueId());
        if (snapshot != null) {
            scheduler.entity(player, () -> {
                restorePendingSnapshot(player, snapshot);
                plugin.send(player, "restored-after-leave");
            });
        }
        ObserverState observerState = pendingObserverRestores.remove(player.getUniqueId());
        if (observerState != null) {
            scheduler.entity(player, () -> {
                player.setGameMode(observerState.previousGameMode());
                player.setAllowFlight(observerState.previousAllowFlight());
                player.setFlying(observerState.previousAllowFlight() && observerState.previousFlying());
                safeTeleport(player, observerState.returnLocation());
                plugin.send(player, "observer-restored-after-leave");
            });
        }
        for (Arena arena : plugin.getArenaManager().all()) {
            if ((arena.state() == ArenaState.WAITING || arena.state() == ArenaState.COUNTDOWN) && arena.participants().contains(player.getUniqueId())) {
                assignParticipantSpawn(arena, player.getUniqueId());
                scheduler.entity(player, () -> {
                    if (!player.isOnline()) return;
                    rememberParticipantState(player);
                    teleportParticipantToAssignedSpawn(player, arena);
                });
                break;
            }
        }
        scheduler.entityLater(player, 1L, () -> enforceArenaEntry(player));
    }

    public void handlePlayerQuit(Player player) {
        Arena arena = findPlayerArena(player.getUniqueId()).orElse(null);
        if (arena != null && arena.activePlayers().contains(player.getUniqueId())) {
            eliminate(player, arena, "Вышел из игры", "", true);
        } else if (observers.containsKey(player.getUniqueId())) {
            ObserverState state = observerStates.remove(player.getUniqueId());
            if (state != null) pendingObserverRestores.put(player.getUniqueId(), state);
            observers.remove(player.getUniqueId());
        }
        onlinePlayers.remove(player.getUniqueId());
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
            if (arena.activePlayers().contains(player.getUniqueId()) || isObserver(player.getUniqueId(), arena)) return;
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
        for (Arena arena : plugin.getArenaManager().all()) {
            if (arena.state() == ArenaState.WAITING) continue;
            for (Player player : new ArrayList<>(onlinePlayers.values())) {
                scheduler.entity(player, () -> enforceNonParticipant(player, arena));
            }
        }
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

    private void restoreAfterElimination(Player player, PlayerSnapshot snapshot) {
        if (snapshot == null) return;
        snapshot.restoreState(player);
        safeTeleport(player, snapshot.location());
        plugin.send(player, "returned-after-elimination");
    }


    private void restoreAfterMatch(Player player, PlayerSnapshot snapshot) {
        if (snapshot == null) return;
        snapshot.restoreState(player);
        safeTeleport(player, snapshot.location());
        plugin.send(player, "inventory-restored");
    }


    private void restorePending(Player player) {
        PlayerSnapshot snapshot = pendingRestores.remove(player.getUniqueId());
        if (snapshot != null) restorePendingSnapshot(player, snapshot);
    }

    private void restorePendingSnapshot(Player player, PlayerSnapshot snapshot) {
        snapshot.restoreState(player);
        safeTeleport(player, snapshot.location());
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

    private void leaveOtherArenaObservation(UUID uuid) {
        Arena previous = observers.remove(uuid);
        ObserverState state = observerStates.remove(uuid);
        if (state != null) {
            Player player = onlinePlayers.get(uuid);
            if (player != null && player.isOnline()) {
                player.setGameMode(state.previousGameMode());
                player.setAllowFlight(state.previousAllowFlight());
                player.setFlying(state.previousAllowFlight() && state.previousFlying());
                safeTeleport(player, state.returnLocation());
            }
            if (previous != null) {
                BossBar bar = bossBars.get(arenaKey(previous));
                if (bar != null && player != null && bar.getPlayers().contains(player)) bar.removePlayer(player);
            }
        }
    }

    private void createOrUpdateBossBar(Arena arena) {
        scheduler.global(() -> updateBossBarGlobal(arena));
    }

    private void updateBossBarGlobal(Arena arena) {
        String key = arenaKey(arena);
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
        Set<UUID> desired = java.util.concurrent.ConcurrentHashMap.newKeySet();
        desired.addAll(arena.activePlayers());
        desired.addAll(arena.spectators());
        desired.addAll(arena.participants());
        Set<UUID> current = bossBarViewers.computeIfAbsent(key, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        for (UUID uuid : desired) {
            Player player = onlinePlayers.get(uuid);
            if (player != null && player.isOnline() && current.add(uuid)) scheduler.entity(player, () -> bar.addPlayer(player));
        }
        for (UUID uuid : new HashSet<>(current)) {
            if (!desired.contains(uuid)) {
                current.remove(uuid);
                Player player = onlinePlayers.get(uuid);
                if (player != null && player.isOnline()) scheduler.entity(player, () -> bar.removePlayer(player));
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

    private void enforceBossBarsOnlyForActivePlayers() { }

    private void broadcast(Arena arena, String key, Object... replacements) {
        String message = plugin.msg(key, replacements);
        Set<UUID> receivers = new HashSet<>(arena.activePlayers());
        receivers.addAll(arena.spectators());
        for (UUID uuid : receivers) {
            Player player = onlinePlayers.get(uuid);
            if (player != null) scheduler.entity(player, () -> { if (player.isOnline()) player.sendMessage(message); });
        }
    }

    private void safeTeleport(Player player, Location location) {
        if (player == null || !player.isOnline() || location == null) return;
        pluginTeleports.add(player.getUniqueId());
        scheduler.entity(player, () -> {
            if (!player.isOnline()) return;
            player.teleportAsync(location);
            scheduler.entityLater(player, 2L, () -> pluginTeleports.remove(player.getUniqueId()));
        });
    }

    private void renderBorderParticles(Arena arena) {
        if (!plugin.getConfig().getBoolean("border-particles.enabled", true)) return;
        if (arena == null || arena.pos1() == null || arena.pos2() == null) return;
        if (arena.state() != ArenaState.COUNTDOWN && arena.state() != ArenaState.PROTECTION && arena.state() != ArenaState.GAME) return;

        World world = Bukkit.getWorld(arena.worldUid());
        if (world == null) return;
        double minX = Math.min(arena.pos1().getX(), arena.pos2().getX());
        double maxX = Math.max(arena.pos1().getX(), arena.pos2().getX());
        double minZ = Math.min(arena.pos1().getZ(), arena.pos2().getZ());
        double maxZ = Math.max(arena.pos1().getZ(), arena.pos2().getZ());
        if (minX >= maxX || minZ >= maxZ) return;
        double minY = Math.min(arena.pos1().getY(), arena.pos2().getY());
        double maxY = Math.max(arena.pos1().getY(), arena.pos2().getY());
        double step = Math.max(1.5, plugin.getConfig().getDouble("border-particles.step", 2.0));
        float size = (float) Math.max(0.35, Math.min(1.0, plugin.getConfig().getDouble("border-particles.size", 0.8)));
        Particle.DustOptions dust = new Particle.DustOptions(currentBorderColor(arena), size);

        for (Player viewer : new ArrayList<>(onlinePlayers.values())) {
            if (viewer == null) continue;
            scheduler.entity(viewer, () -> {
                if (!viewer.isOnline() || viewer.getWorld() != world) return;
                UUID uuid = viewer.getUniqueId();
                boolean relevant = arena.activePlayers().contains(uuid) || arena.participants().contains(uuid)
                        || arena.spectators().contains(uuid) || isObserver(uuid, arena) || plugin.getAccessManager().hasAccess(viewer);
                if (!relevant) return;
                double baseY = Math.max(minY + 1.0, Math.min(maxY - 1.0, viewer.getLocation().getY()));
                double topY = Math.min(maxY, baseY + 5.0);
                spawnBorderWall(viewer, minX, maxX, minZ, maxZ, baseY, topY, step, dust);
                if (arena.shrinkActive()) {
                    double halfX = arena.currentHalfX();
                    double halfZ = arena.currentHalfZ();
                    spawnBorderWall(viewer, arena.shrinkCenterX() - halfX, arena.shrinkCenterX() + halfX,
                            arena.shrinkCenterZ() - halfZ, arena.shrinkCenterZ() + halfZ, baseY, topY, step, dust);
                }
            });
        }
    }

    private void spawnBorderWall(Player viewer, double minX, double maxX, double minZ, double maxZ,
                                  double baseY, double topY, double step, Particle.DustOptions dust) {
        spawnBorderLine(viewer, minX, maxX, minZ, minZ, baseY, step, dust);
        spawnBorderLine(viewer, minX, maxX, maxZ, maxZ, baseY, step, dust);
        spawnBorderLine(viewer, minX, minX, minZ, maxZ, baseY, step, dust);
        spawnBorderLine(viewer, maxX, maxX, minZ, maxZ, baseY, step, dust);
        double yStep = 0.75;
        for (double y = baseY + yStep; y <= topY + 0.01; y += yStep) {
            spawnBorderLine(viewer, minX, maxX, minZ, minZ, y, step, dust);
            spawnBorderLine(viewer, minX, maxX, maxZ, maxZ, y, step, dust);
            spawnBorderLine(viewer, minX, minX, minZ, maxZ, y, step, dust);
            spawnBorderLine(viewer, maxX, maxX, minZ, maxZ, y, step, dust);
        }
    }

    private void spawnBorderLine(Player viewer, double x1, double x2, double z1, double z2,
                                 double y, double step, Particle.DustOptions dust) {
        double length = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        int points = Math.max(2, Math.min(80, (int) Math.ceil(length / step)));
        for (int i = 0; i <= points; i++) {
            double t = i / (double) points;
            double x = x1 + (x2 - x1) * t;
            double z = z1 + (z2 - z1) * t;
            viewer.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust, true);
        }
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
