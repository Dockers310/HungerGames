package ru.doksi.hungergames.command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import ru.doksi.hungergames.HungerGamesPlugin;
import ru.doksi.hungergames.arena.Arena;
import ru.doksi.hungergames.arena.BorderMode;

import java.util.*;

public final class HungerGamesCommand implements CommandExecutor, TabCompleter {
    private final HungerGamesPlugin plugin;
    public HungerGamesCommand(HungerGamesPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Команда доступна только игроку."); return true; }

        if (args.length > 0 && (args[0].equalsIgnoreCase("join"))) {
            if (plugin.getGameManager().findPlayerArena(player.getUniqueId()).isPresent()) {
                plugin.send(player, "already-in-other-arena");
                return true;
            }
            if (args.length < 2) {
                Arena arena = plugin.getGameManager().findJoinableArena();
                if (arena == null) {
                    plugin.send(player, "no-open-arenas");
                    return true;
                }
                plugin.getGameManager().join(player, arena);
                return true;
            }
            Arena arena = plugin.getArenaManager().get(args[1]);
            if (arena == null) { plugin.send(player, "unknown-arena", "arena", args[1]); return true; }
            plugin.getGameManager().join(player, arena);
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("leave"))) {
            plugin.getGameManager().leave(player);
            return true;
        }

        if (!plugin.getAccessManager().hasAccess(player)) { return true; }
        if (args.length == 0) { plugin.getGuiManager().openMain(player); return true; }
        return handleAdmin(player, args);
    }

    private boolean isJoinCommand(String value) {
        return value.equalsIgnoreCase("join");
    }

    private boolean isLeaveCommand(String value) {
        return value.equalsIgnoreCase("leave");
    }

    private boolean handleAdmin(Player player, String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui", "меню" -> plugin.getGuiManager().openMain(player);
            case "арена", "arena" -> handleArena(player, args);
            case "точка1", "point1" -> setPoint(player, 1, args);
            case "точка2", "point2" -> setPoint(player, 2, args);
            case "лобби", "lobby" -> setLobby(player);
            case "спавн", "spawn" -> handleSpawn(player, args);
            case "сбор", "collect" -> handleCollect(player, args);
            case "участник", "participant", "участники", "participants" -> handleParticipant(player, args);
            case "наблюдать", "observe", "watch" -> observe(player);
            case "результаты", "results" -> openResults(player);
            case "сундук", "chest" -> handleChest(player, args);
            case "старт", "start" -> start(player);
            case "стоп", "stop" -> stop(player);
            case "время", "time" -> handleTime(player, args);
            case "граница", "border" -> handleBorder(player, args);
            case "доступ", "access" -> handleAccess(player, args);
            case "перезагрузить", "reload" -> { plugin.reloadPluginData(); plugin.send(player, "config-reloaded"); }
            default -> { }
        }
        return true;
    }

    private Arena selected(Player player) {
        Arena arena = plugin.getArenaManager().get(plugin.getSelectedArena(player.getUniqueId()));
        if (arena == null) plugin.send(player, "not-selected");
        return arena;
    }

    private void handleArena(Player player, String[] args) {
        if (args.length < 2) { plugin.getGuiManager().openArenaList(player); return; }
        String op = args[1].toLowerCase(Locale.ROOT);
        if (op.equals("создать") || op.equals("create")) {
            if (args.length < 3) return;
            Arena arena = plugin.getArenaManager().create(args[2], player.getWorld());
            if (arena == null) { plugin.send(player, "arena-exists"); return; }
            plugin.selectArena(player.getUniqueId(), arena.id()); plugin.send(player, "arena-created", "arena", arena.id());
        } else if (op.equals("удалить") || op.equals("delete")) {
            if (args.length < 3) return;
            if (plugin.getArenaManager().delete(args[2])) { plugin.getChestManager().removeArena(args[2]); plugin.send(player, "arena-deleted", "arena", args[2]); }
        } else if (op.equals("выбрать") || op.equals("select")) {
            if (args.length < 3) return;
            Arena arena = plugin.getArenaManager().get(args[2]); if (arena == null) { plugin.send(player, "unknown-arena", "arena", args[2]); return; }
            plugin.selectArena(player.getUniqueId(), arena.id()); plugin.send(player, "arena-selected", "arena", arena.id());
        } else plugin.getGuiManager().openArenaList(player);
    }

    private void setPoint(Player p, int point, String[] args) {
        Arena arena = selected(p); if (arena == null) return;
        if (args.length >= 2 && (args[1].equalsIgnoreCase("убрать") || args[1].equalsIgnoreCase("remove"))) {
            if (point == 1) arena.setPos1(null); else arena.setPos2(null);
            arena.prepareShrink();
            plugin.getArenaManager().save();
            plugin.send(p, point == 1 ? "point1-removed" : "point2-removed");
            return;
        }
        org.bukkit.Location loc = parseLocation(p, args, 1); if (loc == null) return;
        if (point == 1) { arena.setPos1(loc); plugin.send(p, "point1-set", "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ()); }
        else { arena.setPos2(loc); plugin.send(p, "point2-set", "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ()); }
        arena.prepareShrink(); plugin.getArenaManager().save();
    }

    private org.bukkit.Location parseLocation(Player p, String[] args, int index) {
        if (args.length < index + 3) return p.getLocation().clone();
        try { return new org.bukkit.Location(p.getWorld(), Double.parseDouble(args[index]), Double.parseDouble(args[index+1]), Double.parseDouble(args[index+2]), p.getLocation().getYaw(), p.getLocation().getPitch()); }
        catch (NumberFormatException e) { plugin.send(p, "invalid-coordinate"); return null; }
    }

    private void setLobby(Player p) { Arena arena = selected(p); if (arena == null) return; arena.setLobby(p.getLocation()); plugin.getArenaManager().save(); plugin.send(p, "lobby-set"); }

    private void handleSpawn(Player p, String[] args) {
        Arena arena = selected(p); if (arena == null) return;
        if (args.length < 2 || args[1].equalsIgnoreCase("добавить") || args[1].equalsIgnoreCase("add")) {
            arena.addSpawn(p.getLocation()); plugin.getArenaManager().save(); plugin.send(p, "spawn-added", "count", arena.spawns().size());
        } else if (args[1].equalsIgnoreCase("убрать") || args[1].equalsIgnoreCase("remove")) {
            if (args.length < 3) { plugin.send(p, "usage-spawn-remove"); return; }
            try {
                int index = Integer.parseInt(args[2]) - 1;
                if (!arena.removeSpawn(index)) { plugin.send(p, "spawn-index-invalid"); return; }
                plugin.getArenaManager().save();
                plugin.send(p, "spawn-removed-index", "index", index + 1);
            } catch (NumberFormatException ex) { plugin.send(p, "invalid-number"); }
        } else if (args[1].equalsIgnoreCase("инструмент") || args[1].equalsIgnoreCase("tool")) {
            p.getInventory().addItem(plugin.getSpawnWand());
            plugin.send(p, "spawn-wand-given");
        }
    }

    private void handleCollect(Player p, String[] args) {
        Arena arena = selected(p); if (arena == null) return;
        if (args.length < 2 || args[1].equalsIgnoreCase("открыть") || args[1].equalsIgnoreCase("open")) { arena.setCollecting(true); plugin.send(p, "collecting-opened"); }
        else if (args[1].equalsIgnoreCase("закрыть") || args[1].equalsIgnoreCase("close")) { arena.setCollecting(false); plugin.send(p, "collecting-closed"); }
        plugin.getArenaManager().save();
    }

    private void handleParticipant(Player p, String[] args) {
        Arena arena = selected(p);
        if (arena == null) return;
        if (args.length < 2) { plugin.getGuiManager().openParticipants(p, arena); return; }
        String op = args[1].toLowerCase(Locale.ROOT);
        if (op.equals("добавить") || op.equals("add")) {
            if (args.length < 3) { plugin.send(p, "usage-participant-add"); return; }
            if (arena.state() != ru.doksi.hungergames.arena.ArenaState.WAITING) { plugin.send(p, "participants-locked"); return; }
            org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            if (arena.participants().contains(target.getUniqueId())) { plugin.send(p, "already-participant-target", "player", args[2]); return; }
            int freeSlot = plugin.getGameManager().assignParticipantSpawn(arena, target.getUniqueId());
            if (arena.participants().size() >= arena.maxPlayers() || freeSlot < 0) { plugin.send(p, "join-full"); return; }
            arena.participants().add(target.getUniqueId());
            Player targetPlayer = plugin.getGameManager().getOnlinePlayer(target.getUniqueId());
            if (targetPlayer != null) {
                targetPlayer.getScheduler().run(plugin, ignored -> {
                    plugin.getGameManager().rememberParticipantState(targetPlayer);
                    plugin.getGameManager().teleportParticipantToAssignedSpawn(targetPlayer, arena);
                    plugin.send(targetPlayer, "participant-added-you", "arena", arena.id());
                }, null);
            }
            plugin.getArenaManager().save();
            plugin.send(p, "participant-added", "player", target.getName() == null ? args[2] : target.getName());
        } else if (op.equals("убрать") || op.equals("remove")) {
            if (args.length < 3) { plugin.send(p, "usage-participant-remove"); return; }
            if (arena.state() != ru.doksi.hungergames.arena.ArenaState.WAITING) { plugin.send(p, "participants-locked"); return; }
            org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            if (arena.participants().remove(target.getUniqueId())) {
                arena.clearSpawnSlot(target.getUniqueId());
                plugin.getArenaManager().save();
                plugin.send(p, "participant-removed", "player", target.getName() == null ? args[2] : target.getName());
            } else plugin.send(p, "not-participant-target", "player", args[2]);
        } else {
            plugin.getGuiManager().openParticipants(p, arena);
        }
    }

    private void openParticipants(Player p) { Arena arena = selected(p); if (arena != null) plugin.getGuiManager().openParticipants(p, arena); }
    private void openResults(Player p) { Arena arena = selected(p); if (arena != null) plugin.getGuiManager().openResults(p, arena); }
    private void observe(Player p) { Arena arena = selected(p); if (arena != null) plugin.getGuiManager().openObserveMenu(p, arena); }

    private void handleChest(Player p, String[] args) {
        Arena arena = selected(p); if (arena == null) return;
        if (args.length >= 3 && args[1].equalsIgnoreCase("тип") || args.length >= 3 && args[1].equalsIgnoreCase("type")) {
            Block block = p.getTargetBlockExact(6); if (block == null || !plugin.getChestManager().isLootContainer(block)) { plugin.send(p, "chest-not-found"); return; }
            if (plugin.getChestManager().template(args[2]) == null) { plugin.send(p, "template-not-found", "type", args[2]); return; }
            plugin.getChestManager().setContainer(arena, block, args[2]); plugin.send(p, "chest-assigned", "type", args[2]);
        } else if (args.length >= 2 && (args[1].equalsIgnoreCase("снять") || args[1].equalsIgnoreCase("remove"))) {
            Block block = p.getTargetBlockExact(6); if (block == null) { plugin.send(p, "chest-not-found"); return; }
            plugin.getChestManager().removeChest(arena, block); plugin.send(p, "chest-unassigned");
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("шаблон")) {
            if (plugin.getChestManager().template(args[2]) == null) { plugin.send(p, "template-not-found", "type", args[2]); return; }
            plugin.getGuiManager().openTemplate(p, plugin.getChestManager().template(args[2]));
        } else plugin.getGuiManager().openChestTypes(p);
    }

    private void start(Player p) {
        Arena arena = selected(p); if (arena == null) return;
        if (arena.collecting()) { plugin.send(p, "close-collect-first"); return; }
        if (arena.participants().size() < arena.minPlayers()) { plugin.send(p, "minimum-not-reached", "min", arena.minPlayers()); return; }
        if (arena.spawns().isEmpty()) { plugin.send(p, "no-spawns"); return; }
        if (!plugin.getGameManager().start(arena)) plugin.send(p, "game-already-running");
    }
    private void stop(Player p) { Arena arena = selected(p); if (arena != null) plugin.getGameManager().stop(arena, true); }

    private void handleTime(Player p, String[] args) {
        Arena arena = selected(p); if (arena == null || args.length < 3) { if (arena != null) plugin.getGuiManager().openTimeSettings(p, arena); return; }
        String key = args[1].toLowerCase(Locale.ROOT);
        try { long value = Long.parseLong(args[2]);
            if (key.equals("защита") || key.equals("protection")) arena.settings().protectionSeconds = Math.max(0, value);
            else if (key.equals("сундуки") || key.equals("chests")) arena.settings().chestRefillSeconds = Math.max(0, value);
            else if (key.equals("сужение") || key.equals("shrink")) arena.settings().shrinkStageDurationSeconds = Math.max(1, value);
            else if (key.equals("начало-сужения") || key.equals("shrink-start")) arena.settings().shrinkStartDelaySeconds = Math.max(0, value);
            else if (key.equals("отсчёт") || key.equals("отсчет") || key.equals("countdown")) arena.settings().countdownSeconds = (int) Math.max(1L, value);
            else if (key.equals("смена-границы") || key.equals("граница-смена") || key.equals("border-change")) arena.settings().borderChangeAfterSeconds = Math.max(0L, value);
            else if (key.equals("переход-границы") || key.equals("граница-переход") || key.equals("border-transition")) arena.settings().borderChangeTransitionSeconds = Math.max(0L, value);
            else { plugin.getGuiManager().openTimeSettings(p, arena); return; }
            plugin.getArenaManager().save();
        } catch (NumberFormatException e) { plugin.send(p, "invalid-number"); }
    }

    private void handleBorder(Player p, String[] args) {
        Arena arena = selected(p);
        if (arena == null) return;
        if (args.length < 2) {
            arena.setBorderMode(arena.settings().borderMode.next());
        } else {
            String mode = args[1].toLowerCase(Locale.ROOT);
            if (mode.equals("урон") || mode.equals("damage") || mode.equals("жёсткая") || mode.equals("жесткая") || mode.equals("hard")) {
                arena.setBorderMode(BorderMode.DAMAGE);
            } else if (mode.equals("мягкая") || mode.equals("soft") || mode.equals("teleport") || mode.equals("телепорт")) {
                arena.setBorderMode(BorderMode.SOFT_TELEPORT);
            } else if (mode.equals("меняющаяся") || mode.equals("changing") || mode.equals("change")) {
                arena.setBorderMode(BorderMode.CHANGING);
            } else {
                return;
            }
        }
        plugin.getArenaManager().save();
        plugin.getGuiManager().openTimeSettings(p, arena);
    }

    private void handleAccess(Player p, String[] args) {
        if (args.length < 2) { plugin.getGuiManager().openAccess(p); return; }
        String op = args[1].toLowerCase(Locale.ROOT);
        if (op.equals("добавить") || op.equals("add")) {
            if (args.length < 3) return;
            if (!plugin.getAccessManager().add(args[2])) { plugin.send(p, "access-creator-protected"); return; }
            plugin.send(p, "access-added", "player", args[2]);
        } else if (op.equals("убрать") || op.equals("remove")) {
            if (args.length < 3) return;
            if (!plugin.getAccessManager().remove(args[2])) { plugin.send(p, "access-remove-failed", "player", args[2]); return; }
            plugin.send(p, "access-removed", "player", args[2]);
        } else if (op.equals("список") || op.equals("list")) plugin.getGuiManager().openAccess(p);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();
        boolean access = plugin.getAccessManager().hasAccess(player);

        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            // Player commands are always visible in suggestions, including for OP/staff.
            Collections.addAll(result, "join", "leave");
            if (access) {
                Collections.addAll(result,
                        "gui",
                        "арена", "arena",
                        "точка1", "point1",
                        "точка2", "point2",
                        "лобби", "lobby",
                        "спавн", "spawn",
                        "сбор", "collect",
                        "участник", "participant", "участники", "participants",
                        "наблюдать", "observe", "watch",
                        "результаты", "results",
                        "сундук", "chest",
                        "старт", "start",
                        "стоп", "stop",
                        "время", "time",
                        "граница", "border",
                        "доступ", "access",
                        "перезагрузить", "reload");
            }
            return filter(result, args[0]);
        }

        if (args.length >= 2 && isJoinCommand(args[0])) {
            List<String> arenas = new ArrayList<>();
            for (Arena arena : plugin.getArenaManager().all()) {
                if (arena.state() == ru.doksi.hungergames.arena.ArenaState.WAITING && arena.collecting()
                        && arena.spawns().size() > arena.participants().size()
                        && arena.participants().size() < arena.maxPlayers()) {
                    arenas.add(arena.id());
                }
            }
            return filter(arenas, args[args.length - 1]);
        }

        if (!access) return Collections.emptyList();

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length >= 2 && (sub.equals("арена") || sub.equals("arena"))) {
            return filter(Arrays.asList("создать", "create", "удалить", "delete", "выбрать", "select", "список", "list"), args[args.length-1]);
        }
        if (args.length >= 2 && (sub.equals("сбор") || sub.equals("collect"))) {
            return filter(Arrays.asList("открыть", "open", "закрыть", "close"), args[args.length-1]);
        }
        if (args.length >= 2 && (sub.equals("доступ") || sub.equals("access"))) {
            return filter(Arrays.asList("добавить", "add", "убрать", "remove", "список", "list"), args[args.length-1]);
        }
        if (args.length >= 2 && (sub.equals("спавн") || sub.equals("spawn"))) {
            return filter(Arrays.asList("добавить", "add", "убрать", "remove", "инструмент", "tool"), args[args.length-1]);
        }
        if (args.length >= 2 && (sub.equals("сундук") || sub.equals("chest"))) {
            return filter(Arrays.asList("тип", "type", "снять", "remove", "шаблон", "template"), args[args.length-1]);
        }
        if (args.length >= 2 && (sub.equals("время") || sub.equals("time"))) {
            return filter(Arrays.asList("защита", "protection", "сундуки", "chests", "сужение", "shrink", "начало-сужения", "shrink-start", "отсчёт", "countdown", "смена-границы", "border-change", "переход-границы", "border-transition"), args[args.length-1]);
        }
        if (args.length >= 2 && (sub.equals("граница") || sub.equals("border"))) {
            return filter(Arrays.asList("урон", "damage", "мягкая", "soft", "меняющаяся", "changing"), args[args.length-1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(Collection<String> values, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
