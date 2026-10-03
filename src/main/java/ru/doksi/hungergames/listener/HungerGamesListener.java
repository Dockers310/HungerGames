package ru.doksi.hungergames.listener;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.bukkit.persistence.PersistentDataType;
import ru.doksi.hungergames.HungerGamesPlugin;
import ru.doksi.hungergames.arena.Arena;
import ru.doksi.hungergames.arena.ArenaState;
import ru.doksi.hungergames.arena.BorderMode;
import ru.doksi.hungergames.gui.GuiHolder;

import java.util.*;

public final class HungerGamesListener implements Listener {
    private final HungerGamesPlugin plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey markerTypeKey;
    private final NamespacedKey spawnWandKey;
    private final NamespacedKey selectionWandKey;
    private final Map<UUID, DeathInfo> pendingDeaths = new java.util.concurrent.ConcurrentHashMap<>();
    // Двойное подтверждение изменения граничной точки.
    // Первый клик только просит подтверждение, второй такой же клик в течение 5 секунд меняет точку.
    private final Map<UUID, PendingPointChange> pendingPointChanges = new java.util.concurrent.ConcurrentHashMap<>();

    private record DeathInfo(Arena arena, String reason, String killer, UUID killerUuid) { }
    private record PendingPointChange(int point, boolean remove, UUID worldUid, int x, int y, int z, long expiresAt) { }

    public HungerGamesListener(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        markerKey = new NamespacedKey(plugin, "hg_marker");
        markerTypeKey = new NamespacedKey(plugin, "hg_marker_type");
        spawnWandKey = new NamespacedKey(plugin, "hg_spawn_wand");
        selectionWandKey = new NamespacedKey(plugin, "hg_selection_wand");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof GuiHolder) plugin.getGuiManager().handleClick(e);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        plugin.getGuiManager().handleClose(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        // Plugin GUIs use CHEST inventory type too; never treat them as world loot containers.
        if (e.getInventory().getHolder() instanceof GuiHolder) return;
        Arena arena = arenaFor(p);
        if (arena == null) return;
        boolean preGameParticipant = (arena.state() == ArenaState.WAITING || arena.state() == ArenaState.COUNTDOWN)
                && arena.participants().contains(p.getUniqueId());
        if (!preGameParticipant) return;
        if (plugin.getChestManager().isLootContainerInventory(e.getInventory())
                && e.getInventory().getHolder() != p.getInventory().getHolder()) {
            e.setCancelled(true);
            plugin.send(p, "chests-locked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof GuiHolder holder) {
            plugin.getGuiManager().handleDrag(e);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (plugin.getInputManager().handle(e.getPlayer(), e.getMessage())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        // Не обрабатываем вторую руку: иначе один клик может сработать дважды.
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!plugin.getAccessManager().hasAccess(p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();

        // Selection/spawn tools are handled before vanilla interaction and regardless of another plugin cancelling it.
        if (isSelectionWand(hand)) {
            handleSelectionWandClick(e, p, hand);
            return;
        }

        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;

        if (isSpawnWand(hand)) {
            Arena selected = plugin.getArenaManager().get(plugin.getSelectedArena(p.getUniqueId()));
            if (selected == null) return;
            if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                Location spawn = block.getLocation().add(0.5, 1.0, 0.5);
                spawn.setYaw(p.getLocation().getYaw());
                spawn.setPitch(p.getLocation().getPitch());
                selected.addSpawn(spawn);
                plugin.getArenaManager().save();
                plugin.send(p, "spawn-added", "count", selected.spawns().size());
                e.setCancelled(true);
            } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                int nearest = -1;
                double best = 2.25;
                List<Location> spawns = selected.spawns();
                for (int i = 0; i < spawns.size(); i++) {
                    double d = spawns.get(i).distanceSquared(block.getLocation().add(0.5, 1.0, 0.5));
                    if (d <= best) { best = d; nearest = i; }
                }
                if (nearest >= 0) {
                    selected.removeSpawn(nearest);
                    plugin.getArenaManager().save();
                    plugin.send(p, "spawn-removed-index", "index", nearest + 1);
                } else {
                    plugin.send(p, "spawn-remove-nearby");
                }
                e.setCancelled(true);
            }
            return;
        }

        String type = markerType(hand);
        // Marker assignment is intentionally LEFT-CLICK only.
        // RIGHT-CLICK must stay vanilla so the player can open the container normally.
        if (type != null && e.getAction() == Action.LEFT_CLICK_BLOCK && plugin.getChestManager().isLootContainer(block)) {
            Arena arena = plugin.getArenaManager().get(plugin.getSelectedArena(p.getUniqueId()));
            if (arena != null && plugin.getChestManager().template(type) != null) {
                plugin.getChestManager().setContainer(arena, block, type);
                plugin.send(p, "chest-assigned", "type", type);
                e.setCancelled(true);
                return;
            }
        }

    }

    private void handleSelectionWandClick(PlayerInteractEvent e, Player p, ItemStack hand) {
        Arena arena = plugin.getArenaManager().get(plugin.getSelectedArena(p.getUniqueId()));
        if (arena == null) {
            e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK
                && e.getAction() != Action.LEFT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_AIR) return;

        Block clicked = e.getClickedBlock();
        if (clicked == null) {
            org.bukkit.util.RayTraceResult trace = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 64.0);
            if (trace != null) clicked = trace.getHitBlock();
        }
        if (clicked == null) {
            e.setCancelled(true);
            return;
        }

        boolean firstPoint = e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.LEFT_CLICK_AIR;
        int point = firstPoint ? 1 : 2;
        boolean remove = p.isSneaking();
        boolean alreadySet = point == 1 ? arena.pos1() != null : arena.pos2() != null;
        UUID worldUid = clicked.getWorld().getUID();
        int x = clicked.getX();
        int y = clicked.getY();
        int z = clicked.getZ();

        // Если точка уже существует, изменение/удаление требует второго подтверждающего клика.
        if (alreadySet) {
            long now = System.currentTimeMillis();
            PendingPointChange pending = pendingPointChanges.get(p.getUniqueId());
            boolean confirmed = pending != null
                    && pending.expiresAt() >= now
                    && pending.point() == point
                    && pending.remove() == remove
                    && pending.worldUid().equals(worldUid)
                    && pending.x() == x
                    && pending.y() == y
                    && pending.z() == z;

            if (!confirmed) {
                pendingPointChanges.put(p.getUniqueId(), new PendingPointChange(point, remove, worldUid, x, y, z, now + 5000L));
                plugin.send(p, remove ? "point-remove-confirm" : "point-change-confirm", "point", point);
                e.setCancelled(true);
                return;
            }
            pendingPointChanges.remove(p.getUniqueId());
        } else {
            // Для ещё не установленной точки подтверждение не требуется.
            pendingPointChanges.remove(p.getUniqueId());
        }

        if (point == 1) {
            if (remove) {
                arena.setPos1(null);
                plugin.send(p, "point1-removed");
            } else {
                arena.setPos1(clicked.getLocation());
                plugin.send(p, "point1-set", "x", x, "y", y, "z", z);
            }
        } else {
            if (remove) {
                arena.setPos2(null);
                plugin.send(p, "point2-removed");
            } else {
                arena.setPos2(clicked.getLocation());
                plugin.send(p, "point2-set", "x", x, "y", y, "z", z);
            }
        }
        arena.prepareShrink();
        plugin.getArenaManager().save();
        e.setCancelled(true);
    }

    private boolean isSelectionWand(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        // Инструментом выделения теперь является только кирка.
        // Старые топоры с PDC больше не считаются инструментом выделения.
        if (!stack.getType().name().endsWith("_PICKAXE")) return false;
        if (meta.getPersistentDataContainer().has(selectionWandKey, PersistentDataType.BYTE)) return true;
        Material configured = Material.matchMaterial(plugin.getConfig().getString("selection-wand.material", "IRON_PICKAXE"));
        if (configured == null || !configured.name().endsWith("_PICKAXE")) configured = Material.IRON_PICKAXE;
        String configuredName = HungerGamesPlugin.color(plugin.getConfig().getString("selection-wand.name", "&dКирка границ арены"));
        return stack.getType() == configured && configuredName.equals(meta.getDisplayName());
    }

    private boolean isSpawnWand(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(spawnWandKey, PersistentDataType.BYTE);
    }

    private String markerType(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        ItemMeta meta = stack.getItemMeta();
        if (!meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) return null;
        return meta.getPersistentDataContainer().get(markerTypeKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        Player p = e.getPlayer();
        Arena arena = arenaFor(p);
        if (arena == null || arena.state() == ArenaState.FINISH || arena.state() == ArenaState.STOPPED) return;

        boolean active = arena.activePlayers().contains(p.getUniqueId());
        boolean observer = plugin.getGameManager().isObserver(p.getUniqueId(), arena);
        boolean preGameParticipant = (arena.state() == ArenaState.WAITING || arena.state() == ArenaState.COUNTDOWN)
                && arena.participants().contains(p.getUniqueId());
        if (preGameParticipant) {
            Integer slot = arena.spawnSlot(p.getUniqueId());
            if (slot != null && slot >= 0 && slot < arena.spawns().size()) {
                Location spawn = arena.spawns().get(slot);
                boolean positionChanged = e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ();
                if (positionChanged) e.setTo(new Location(e.getFrom().getWorld(), spawn.getX(), spawn.getY(), spawn.getZ(), e.getTo().getYaw(), e.getTo().getPitch()));
            }
            return;
        }
        if (active || observer) {
            if (!arena.insideOuter(e.getTo())) {
                e.setTo(arena.clampToOuter(e.getTo()));
            } else if (active && arena.shrinkActive() && arena.outsideDistance(e.getTo()) > 0) {
                if (arena.effectiveBorderMode() == BorderMode.SOFT_TELEPORT) {
                    Location safe = plugin.getGameManager().softBorderDestination(arena, e.getTo(), p);
                    if (safe != null) {
                        e.setTo(safe);
                        Vector push = plugin.getGameManager().softBorderPush(arena, safe);
                        if (push != null) {
                            p.getScheduler().run(plugin, ignored -> {
                                if (p.isOnline()) p.setVelocity(push);
                            }, null);
                            p.getScheduler().runDelayed(plugin, ignored -> {
                                if (p.isOnline()) p.setVelocity(push);
                            }, null, 2L);
                        }
                    }
                } else if (arena.effectiveBorderMode() == BorderMode.DAMAGE) {
                    // The hard shrink border remains impassable even after the last stage.
                    // Keep the original damaging behavior by applying contact damage once the
                    // player tries to cross it, while the regular tick handles forced teleports.
                    double outside = arena.outsideDistance(e.getTo());
                    e.setTo(arena.clampToShrink(e.getTo()));
                    if (outside > 0.0 && p.getNoDamageTicks() <= 0) {
                        double blocks = Math.max(1.0, Math.ceil(outside));
                        double damage = blocks * 2.0 * arena.settings().borderDamageHeartMultiplier;
                        if (damage > 0.0) p.damage(damage);
                    }
                }
            }
            return;
        }

        if (arena.state() != ArenaState.WAITING && arena.insideOuter(e.getTo())) {
            plugin.getGameManager().enforceNonParticipant(p, arena);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        Arena arena = arenaFor(p);
        if (arena == null) return;
        boolean preGameParticipant = (arena.state() == ArenaState.WAITING || arena.state() == ArenaState.COUNTDOWN)
                && arena.participants().contains(p.getUniqueId());
        if (preGameParticipant) {
            e.setCancelled(true);
            plugin.send(p, "countdown-frozen");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (plugin.getGameManager().isPluginTeleport(p.getUniqueId())) return;
        Location destination = e.getTo();
        if (destination == null) return;

        for (Arena arena : plugin.getArenaManager().all()) {
            if (arena.state() == ArenaState.WAITING || arena.state() == ArenaState.FINISH || arena.state() == ArenaState.STOPPED) continue;
            boolean active = arena.activePlayers().contains(p.getUniqueId());
            boolean observer = plugin.getGameManager().isObserver(p.getUniqueId(), arena);
            boolean preGameParticipant = (arena.state() == ArenaState.WAITING || arena.state() == ArenaState.COUNTDOWN)
                    && arena.participants().contains(p.getUniqueId());
            if (preGameParticipant) {
                e.setCancelled(true);
                plugin.send(p, "countdown-frozen");
                return;
            }
            if (active || observer) {
                if (!arena.insideOuter(destination)) {
                    e.setTo(arena.clampToOuter(destination));
                    plugin.send(p, observer ? "observer-boundary" : "game-boundary");
                } else if (active && arena.shrinkActive() && arena.outsideDistance(destination) > 0) {
                    if (arena.effectiveBorderMode() == BorderMode.SOFT_TELEPORT) {
                        Location safe = plugin.getGameManager().softBorderDestination(arena, destination, p);
                        if (safe != null) {
                            e.setTo(safe);
                            Vector push = plugin.getGameManager().softBorderPush(arena, safe);
                            if (push != null) {
                                p.getScheduler().run(plugin, ignored -> {
                                    if (p.isOnline()) p.setVelocity(push);
                                }, null);
                                p.getScheduler().runDelayed(plugin, ignored -> {
                                    if (p.isOnline()) p.setVelocity(push);
                                }, null, 2L);
                            }
                        }
                    } else if (arena.effectiveBorderMode() == BorderMode.DAMAGE) {
                        double outside = arena.outsideDistance(destination);
                        e.setTo(arena.clampToShrink(destination));
                        if (outside > 0.0 && p.getNoDamageTicks() <= 0) {
                            double blocks = Math.max(1.0, Math.ceil(outside));
                            double damage = blocks * 2.0 * arena.settings().borderDamageHeartMultiplier;
                            if (damage > 0.0) p.damage(damage);
                        }
                        plugin.send(p, "game-boundary");
                    }
                }
                return;
            }
            if (arena.insideOuter(destination)) {
                e.setCancelled(true);
                plugin.getGameManager().rejectArenaEntry(p, arena);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) {
        Player p = e.getPlayer();
        Arena arena = arenaFor(p);
        if (arena != null && arena.activePlayers().contains(p.getUniqueId())) {
            e.setCancelled(true);
            plugin.send(p, "bed-not-allowed");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Arena observerArena = plugin.getGameManager().observerArena(p.getUniqueId());
        if (observerArena != null) { e.setCancelled(true); return; }
        Arena arena = arenaFor(p);
        if (arena == null) return;
        if (((arena.state() == ArenaState.WAITING || arena.state() == ArenaState.COUNTDOWN) && arena.participants().contains(p.getUniqueId()))
                || (arena.activePlayers().contains(p.getUniqueId()) && arena.state() == ArenaState.PROTECTION)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        Arena arena = arenaFor(p);
        if (arena == null || !arena.activePlayers().contains(p.getUniqueId())) return;

        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);

        EntityDamageEvent last = p.getLastDamageCause();
        String reason = last == null ? "Неизвестная причина" : humanizeCause(last.getCause());
        if (arena.shrinkActive() && arena.outsideDistance(p.getLocation()) > 0) reason = "Урон от границы";
        Player killer = p.getKiller();
        String killerName = killer == null ? "" : killer.getName();
        if (killer != null) reason = "Убит игроком";
        pendingDeaths.put(p.getUniqueId(), new DeathInfo(arena, reason, killerName, killer == null ? null : killer.getUniqueId()));

        p.getScheduler().run(plugin, ignored -> {
            if (p.isOnline()) {
                try { p.spigot().respawn(); }
                catch (Throwable ignoredRespawn) { }
            }
        }, null);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        DeathInfo death = pendingDeaths.get(p.getUniqueId());
        if (death != null) {
            var session = plugin.getGameManager().liveSession(death.arena());
            if (session != null && session.snapshots().containsKey(p.getUniqueId())) {
                e.setRespawnLocation(session.snapshots().get(p.getUniqueId()).location());
            }

            // PlayerPostRespawnEvent есть не во всех сборках/сценариях одинаково.
            // Поэтому оставляем надёжный резерв: если post-respawn не придёт,
            // через 2 тика игрок всё равно будет окончательно выведен из матча.
            p.getScheduler().runDelayed(plugin, ignored -> processPendingDeath(p), null, 2L);
            return;
        }
        Arena arena = arenaFor(p);
        if (arena != null && arena.spectators().contains(p.getUniqueId())) {
            e.setRespawnLocation(arena.lobby() == null ? p.getWorld().getSpawnLocation() : arena.lobby());
        }
    }

    @EventHandler
    public void onPostRespawn(PlayerPostRespawnEvent e) {
        processPendingDeath(e.getPlayer());
    }

    private void processPendingDeath(Player p) {
        if (p == null) return;
        DeathInfo death = pendingDeaths.remove(p.getUniqueId());
        if (death == null) return;
        plugin.getGameManager().eliminate(p, death.arena(), death.reason(), death.killer(), death.killerUuid(), false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getGameManager().handlePlayerJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pendingDeaths.remove(e.getPlayer().getUniqueId());
        pendingPointChanges.remove(e.getPlayer().getUniqueId());
        plugin.getGuiManager().clearPendingPointConfirmation(e.getPlayer().getUniqueId());
        plugin.getGameManager().handlePlayerQuit(e.getPlayer());
    }

    private Arena arenaFor(Player p) {
        return plugin.getArenaManager().all().stream()
                .filter(a -> a.participants().contains(p.getUniqueId()) ||
                        a.activePlayers().contains(p.getUniqueId()) ||
                        a.spectators().contains(p.getUniqueId()) ||
                        plugin.getGameManager().isObserver(p.getUniqueId(), a))
                .findFirst().orElseGet(() -> findArenaAtLocation(p));
    }

    private Arena findArenaAtLocation(Player p) {
        for (Arena arena : plugin.getArenaManager().all()) {
            if (arena.state() != ArenaState.WAITING && arena.insideOuter(p.getLocation())) return arena;
        }
        return null;
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
}
