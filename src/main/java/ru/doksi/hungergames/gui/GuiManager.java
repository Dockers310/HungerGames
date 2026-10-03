package ru.doksi.hungergames.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import ru.doksi.hungergames.HungerGamesPlugin;
import ru.doksi.hungergames.arena.Arena;
import ru.doksi.hungergames.arena.ArenaState;
import ru.doksi.hungergames.arena.BorderMode;
import ru.doksi.hungergames.chest.ChestTemplate;

import java.util.*;

public final class GuiManager {
    private final HungerGamesPlugin plugin;
    private final Map<UUID, String> editorSessions = new HashMap<>();
    // Двойное подтверждение изменения/удаления уже существующих граничных точек из GUI.
    private final Map<UUID, PendingGuiPointChange> pendingGuiPointChanges = new java.util.concurrent.ConcurrentHashMap<>();
    private record PendingGuiPointChange(String arenaId, int point, boolean remove, long expiresAt) { }

    public GuiManager(HungerGamesPlugin plugin) { this.plugin = plugin; }

    public void clearPendingPointConfirmation(UUID uuid) {
        if (uuid != null) pendingGuiPointChanges.remove(uuid);
    }

    public void openMain(Player p) {
        Inventory inv = create("main", null, null, 54, "&5HungerGames &8— &dГлавное меню");
        put(inv, 20, item(Material.NETHER_STAR, "&dАрены", "&7Создание и настройка арен"));
        put(inv, 22, item(Material.CHEST, "&6Контейнеры с лутом", "&7Шаблоны, лут и маркеры контейнеров"));
        put(inv, 24, item(Material.BLAZE_ROD, "&eСпец. предметы", "&7Инструменты и маркеры для настройки"));
        put(inv, 26, item(Material.PLAYER_HEAD, "&bСотрудники", "&7Кто имеет доступ к плагину", "&7Выданных: &f" + plugin.getAccessManager().granted().size()));
        put(inv, 29, item(Material.CLOCK, "&eВремя и правила", "&7Настройки выбранной арены"));
        put(inv, 31, item(Material.BOOK, "&fСправочник", "&7Команды и примеры"));
        put(inv, 40, item(Material.BARRIER, "&cЗакрыть"));
        p.openInventory(inv);
    }

    public void openArenaList(Player p) {
        Inventory inv = create("arenas", null, null, 54, "&5HungerGames &8— &dАрены");
        int slot = 0;
        for (Arena a : plugin.getArenaManager().all()) {
            if (slot >= 45) break;
            String state = a.state().name();
            put(inv, slot++, item(Material.NETHER_STAR, "&d" + a.id(), "&7Мир: &f" + a.worldName(), "&7Состояние: &f" + state, "&7Участники: &f" + a.participants().size() + "&7/&f" + a.maxPlayers(), "&eНажмите для настройки"));
        }
        put(inv, 49, item(Material.EMERALD, "&aСоздать арену", "&7Имя введёте в чат"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    public void openArena(Player p, Arena a) {
        plugin.selectArena(p.getUniqueId(), a.id());
        Inventory inv = create("arena", a.id(), null, 54, "&5Арена &d" + a.id());
        put(inv, 10, item(Material.TARGET, "&dТочка 1", locLine(a.pos1()), "&7ЛКМ — установить здесь", "&cПКМ — убрать точку"));
        put(inv, 12, item(Material.ENDER_EYE, "&dТочка 2", locLine(a.pos2()), "&7ЛКМ — установить здесь", "&cПКМ — убрать точку"));
        put(inv, 14, item(Material.COMPASS, "&bЛобби", locLine(a.lobby())));
        put(inv, 16, item(Material.SPAWNER, "&eТочки появления", "&7Количество: &f" + a.spawns().size(), "&7ЛКМ — список и удаление", "&7ПКМ — получить маркер"));
        put(inv, 20, item(a.collecting() ? Material.GREEN_WOOL : Material.RED_WOOL, a.collecting() ? "&aСбор игроков ОТКРЫТ" : "&cСбор игроков ЗАКРЫТ", "&7Нажмите, чтобы переключить"));
        put(inv, 22, item(Material.PLAYER_HEAD, "&bУчастники", "&7Записано: &f" + a.participants().size()));
        put(inv, 24, item(Material.CHEST, "&6Контейнеры с лутом", "&7Назначенных: &f" + plugin.getChestManager().chests(a).size()));
        put(inv, 26, item(Material.GOLD_BLOCK, "&6Результаты матчей", "&7Места 1–3, убийства, выбывания", "&eНажмите для просмотра"));
        put(inv, 28, item(Material.ENDER_EYE, "&bНаблюдение", "&7Выбрать SPECTATOR или обычное", "&7Без регистрации как участника"));
        put(inv, 29, item(Material.CLOCK, "&eНастройки времени", "&7Защита, контейнеры, сужение"));
        put(inv, 31, item(Material.LIME_DYE, "&aЗапустить", "&7Только при закрытом сборе"));
        put(inv, 33, item(Material.REDSTONE, "&cОстановить", "&7Остановить игру"));
        put(inv, 36, item(Material.BLAZE_ROD, "&eМаркер точки появления", "&7ЛКМ по блоку — поставить точку", "&7ПКМ рядом — убрать ближайшую"));
        put(inv, 38, item(Material.IRON_PICKAXE, "&dПолучить кирку границ арены", "&7ЛКМ по блоку: точка 1", "&7ПКМ по блоку: точка 2", "&eИзменение существующей точки требует двойного подтверждения"));
        put(inv, 40, item(Material.OAK_SIGN, "&fРазмер и лимиты", "&7ЛКМ: ввести координаты точки 1", "&7ПКМ: min/max +1", "&eShift+ЛКМ: min/max +5", "&eShift+ПКМ: min/max -5"));
        put(inv, 41, item(a.maxPlayersBySpawns() ? Material.SPAWNER : Material.PLAYER_HEAD,
                a.maxPlayersBySpawns() ? "&aМаксимум игроков: по точкам" : "&eМаксимум игроков: вручную",
                a.maxPlayersBySpawns() ? "&7Сейчас: &f" + a.spawns().size() + " мест" : "&7Сейчас: &f" + a.maxPlayers() + " игроков",
                "&7ЛКМ — переключить режим",
                "&7По точкам: максимум равен количеству спавнов"));
        put(inv, 42, item(Material.BARRIER, "&cУдалить арену"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    public void openTools(Player p) {
        Inventory inv = create("tools", null, null, 54, "&eСпециальные предметы");
        put(inv, 10, plugin.getSelectionWand());
        put(inv, 12, plugin.getSpawnWand());
        put(inv, 16, item(Material.CHEST, "&6Макеты лута",
                "&7Открыть список всех шаблонов",
                "&7Выбрать шаблон и получить маркер"));
        put(inv, 49, item(Material.BOOK, "&fЧто делает предмет?",
                "&7Палочка выбора: точки 1/2 арены",
                "&7Маркер точки: добавление/удаление спавнов",
                "&7Макеты лута: выбрать шаблон для маркера"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    public void openChestTypes(Player p) { openChestTypes(p, 0); }

    public void openChestTypes(Player p, int page) {
        List<ChestTemplate> templates = new ArrayList<>(plugin.getChestManager().templates());
        int perPage = 45;
        int pages = Math.max(1, (templates.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = create("templates", null, String.valueOf(page), 54,
                "&6Шаблоны лута &8— &f" + (page + 1) + "&7/" + pages);
        int startIndex = page * perPage;
        int endIndex = Math.min(startIndex + perPage, templates.size());
        for (int index = startIndex; index < endIndex; index++) {
            ChestTemplate t = templates.get(index);
            put(inv, index - startIndex, item(t.icon(), "&6" + t.displayName(),
                    "&7ID: &f" + t.id(),
                    "&7Предметов: &f" + t.items().size(),
                    "&7Диапазон: &f" + t.minItems() + "&7–&f" + t.maxItems(),
                    "&eНажмите — настройка",
                    "&cShift+ПКМ — удалить"));
        }
        put(inv, 49, item(Material.CRAFTING_TABLE, "&aСоздать тип", "&7Имя введёте в чат"));
        if (page > 0) put(inv, 51, item(Material.ARROW, "&eНазад", "&7Предыдущая страница"));
        if (page + 1 < pages) put(inv, 52, item(Material.ARROW, "&aВперёд", "&7Следующая страница"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    public void openTemplate(Player p, ChestTemplate t) {
        Inventory inv = create("template", null, t.id(), 54, "&6Шаблон &f" + t.displayName());
        // Keep preview slots fixed at 0-44, matching the editable area of the content editor.
        int slot = 0;
        for (ItemStack content : t.items()) {
            if (slot >= 45) break;
            put(inv, slot++, content.clone());
        }
        put(inv, 46, item(Material.CHEST, "&eРедактировать содержимое", "&7Открыть редактор содержимого шаблона"));
        put(inv, 47, item(Material.BARRIER, "&cУдалить шаблон", "&7Удаляет этот макет лута", "&7Назначения этого шаблона у контейнеров будут сняты"));
        put(inv, 48, item(Material.PAPER, "&6Получить маркер лута", "&7ЛКМ по контейнеру — назначить этот шаблон", "&7ПКМ по контейнеру — открыть его инвентарь"));
        put(inv, 50, item(Material.IRON_NUGGET, "&fМин. предметов: &e" + t.minItems(), "&7ЛКМ +1, ПКМ -1", "&eShift+ЛКМ +5, Shift+ПКМ -5"));
        put(inv, 51, item(Material.GOLD_NUGGET, "&fМакс. предметов: &e" + t.maxItems(), "&7ЛКМ +1, ПКМ -1", "&eShift+ЛКМ +5, Shift+ПКМ -5"));
        put(inv, 52, item(Material.GOLD_INGOT, "&fШанс предмета: &e" + Math.round(t.itemChance()*100) + "%", "&7ЛКМ +1%, ПКМ -1%", "&eShift+ЛКМ +5%, Shift+ПКМ -5%"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    public void openTemplateEditor(Player p, ChestTemplate t) {
        editorSessions.put(p.getUniqueId(), t.id());
        GuiHolder holder = new GuiHolder("template-editor", null, t.id());
        Inventory inv = Bukkit.createInventory(holder, 54, HungerGamesPlugin.color("&6Редактор: &f" + t.displayName()));
        holder.bind(inv);
        for (int i=45;i<54;i++) inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(49, item(Material.GREEN_WOOL, "&aСохранить"));
        inv.setItem(53, item(Material.BARRIER, "&cОтмена"));
        int slot=0;
        for(ItemStack item:t.items()) { if(slot>=45) break; inv.setItem(slot++, item.clone()); }
        p.openInventory(inv);
    }

    public void openParticipants(Player p, Arena a) {
        Inventory inv = create("participants", a.id(), null, 54, "&bУчастники &8— &f" + a.id());
        int slot = 0;
        for (UUID uuid : a.participants()) {
            if (slot >= 45) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            put(inv, slot++, head(op, (op.getName() == null ? uuid.toString().substring(0,8) : op.getName()),
                    a.activePlayers().contains(uuid) ? "&aВ активном матче" : "&eВ списке участников",
                    "&cЛКМ — убрать из списка"));
        }
        put(inv, 49, item(Material.PLAYER_HEAD, "&aДобавить участника", "&7Введите ник в чат"));
        put(inv, 51, item(Material.PAPER, "&eПравило регистрации", "&7Только сотрудники могут записывать игроков", "&7Обычный игрок не имеет административных команд"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    public void openAccess(Player p) {
        Inventory inv = create("access", null, null, 54, "&bСотрудники &8— &dДоступ HungerGames");
        int slot = 0;
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(plugin.getAccessManager().granted().keySet());
        for (OfflinePlayer op : Bukkit.getOperators()) ids.add(op.getUniqueId());
        ids.removeIf(uuid -> plugin.getAccessManager().isCreator(Bukkit.getOfflinePlayer(uuid)));
        for (UUID uuid : ids) {
            if (slot >= 45) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            boolean opFlag = op.isOp();
            boolean granted = plugin.getAccessManager().granted().containsKey(uuid);
            String status = opFlag ? "&aOP" : "&eВыдан";
            put(inv, slot++, head(op, op.getName() == null ? uuid.toString().substring(0,8) : op.getName(),
                    "&7Статус: " + status,
                    "&7Доступ к GUI и командам: &aДА",
                    granted ? "&eЛКМ — убрать выданный доступ" : "&7OP управляется самим сервером"));
        }
        put(inv, 49, item(Material.PLAYER_HEAD, "&aДобавить сотрудника",
                "&7Любой сотрудник с доступом может",
                "&7добавлять и удалять выданный доступ."));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    public void openResults(Player p, Arena a) {
        Inventory inv = create("results", a.id(), null, 54, "&6Результаты — &f" + a.id());
        List<ru.doksi.hungergames.game.MatchHistoryManager.Result> results;
        boolean live = plugin.getGameManager().liveSession(a) != null;
        if (live) {
            results = new ArrayList<>(plugin.getGameManager().liveResults(a));
            put(inv, 4, item(Material.CLOCK, "&eТекущий матч", "&7Показаны уже выбывшие игроки"));
        } else {
            results = plugin.getMatchHistoryManager().latest(a.id()) == null ? List.of() : new ArrayList<>(plugin.getMatchHistoryManager().latest(a.id()).results());
            put(inv, 4, item(Material.BOOK, "&6Последний завершённый матч", results.isEmpty() ? "&7Нет сохранённых матчей" : "&7Нажмите на игрока для подробностей"));
        }
        results = new ArrayList<>(results);
        results.sort(Comparator.comparingInt(ru.doksi.hungergames.game.MatchHistoryManager.Result::kills).reversed()
                .thenComparingInt(ru.doksi.hungergames.game.MatchHistoryManager.Result::placement));
        List<ru.doksi.hungergames.game.MatchHistoryManager.Result> byPlacement = new ArrayList<>(results);
        byPlacement.sort(Comparator.comparingInt(ru.doksi.hungergames.game.MatchHistoryManager.Result::placement));
        int[] podium = {11, 13, 15};
        for (int i = 1; i <= 3; i++) {
            final int placement = i;
            ru.doksi.hungergames.game.MatchHistoryManager.Result result = byPlacement.stream()
                    .filter(r -> r.placement() == placement).findFirst().orElse(null);
            if (result == null) {
                put(inv, podium[i - 1], item(i == 1 ? Material.GOLD_BLOCK : i == 2 ? Material.IRON_BLOCK : Material.COPPER_BLOCK,
                        i == 1 ? "&61 место" : i == 2 ? "&f2 место" : "&c3 место", "&7—"));
            } else {
                Material material = i == 1 ? Material.GOLD_BLOCK : i == 2 ? Material.IRON_BLOCK : Material.COPPER_BLOCK;
                String color = i == 1 ? "&6" : i == 2 ? "&f" : "&c";
                OfflinePlayer op = Bukkit.getOfflinePlayer(result.uuid());
                put(inv, podium[i - 1], head(op, color + result.name(),
                        color + "Место: " + i,
                        "&7Убийств: &f" + result.kills(),
                        "&7Когда: &f" + formatTime(result.time()),
                        "&7Причина: &f" + result.reason(),
                        "&eНажмите для подробностей"));
            }
        }
        int slot = 27;
        for (ru.doksi.hungergames.game.MatchHistoryManager.Result result : results) {
            if (slot >= 45) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(result.uuid());
            put(inv, slot++, head(op, placementColor(result.placement()) + result.name(),
                    "&7Место: &f" + result.placement(),
                    "&7Убийств: &f" + result.kills(),
                    "&7Статус: &f" + result.reason(),
                    "&7Время: &f" + formatTime(result.time()),
                    result.killer().isBlank() ? "" : "&7Убийца: &f" + result.killer(),
                    "&eНажмите — подробности"));
        }
        put(inv, 49, item(Material.HOPPER, "&eТоп убийств", "&7Каждый игрок — отдельная голова", "&7Скин + ник + убийства", "&eНажмите для полного топа"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    private void resultsClick(Player p, String id, int slot) {
        Arena arena = plugin.getArenaManager().get(id);
        if (arena == null) return;

        if (slot == 49) {
            openKillTop(p, arena);
            return;
        }
        if (slot == 53) {
            openArena(p, arena);
            return;
        }

        List<ru.doksi.hungergames.game.MatchHistoryManager.Result> results;
        if (plugin.getGameManager().liveSession(arena) != null) {
            results = new ArrayList<>(plugin.getGameManager().liveResults(arena));
        } else {
            var latest = plugin.getMatchHistoryManager().latest(arena.id());
            results = latest == null ? new ArrayList<>() : new ArrayList<>(latest.results());
        }

        ru.doksi.hungergames.game.MatchHistoryManager.Result selected = null;
        if (slot == 11 || slot == 13 || slot == 15) {
            int placement = slot == 11 ? 1 : slot == 13 ? 2 : 3;
            selected = results.stream().filter(r -> r.placement() == placement).findFirst().orElse(null);
        } else if (slot >= 27 && slot < 45) {
            results.sort(Comparator.comparingInt(ru.doksi.hungergames.game.MatchHistoryManager.Result::kills).reversed()
                    .thenComparingInt(ru.doksi.hungergames.game.MatchHistoryManager.Result::placement));
            int index = slot - 27;
            if (index >= 0 && index < results.size()) selected = results.get(index);
        }

        if (selected == null) return;
        String killer = selected.killer().isBlank() ? "—" : selected.killer();
        String message = "&6Результат &8» &f" + selected.name() +
                " &7| место: &f" + selected.placement() +
                " &7| убийств: &f" + selected.kills() +
                " &7| причина: &f" + selected.reason() +
                " &7| убийца: &f" + killer +
                " &7| время: &f" + formatTime(selected.time());
        p.sendMessage(HungerGamesPlugin.color(message));
    }

    private String placementColor(int placement) {
        return placement == 1 ? "&6" : placement == 2 ? "&f" : placement == 3 ? "&c" : "&d";
    }

    private String formatTime(long millis) {
        if (millis <= 0) return "—";
        return java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
    }

    public void openKillTop(Player p, Arena a) {
        Inventory inv = create("kill-top", a.id(), null, 54, "&cТоп убийств &8— &f" + a.id());
        List<ru.doksi.hungergames.game.MatchHistoryManager.Result> results;
        if (plugin.getGameManager().liveSession(a) != null) results = new ArrayList<>(plugin.getGameManager().liveKillLeaderboard(a));
        else {
            var latest = plugin.getMatchHistoryManager().latest(a.id());
            results = latest == null ? new ArrayList<>() : new ArrayList<>(latest.results());
            results.sort(Comparator.comparingInt(ru.doksi.hungergames.game.MatchHistoryManager.Result::kills).reversed()
                    .thenComparing(r -> r.name(), String.CASE_INSENSITIVE_ORDER));
        }
        int slot = 0;
        for (var result : results) {
            if (slot >= 45) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(result.uuid());
            put(inv, slot++, head(op, "&c" + result.name(),
                    "&7Убийств за матч: &c" + result.kills(),
                    result.placement() > 0 ? "&7Место: &f" + result.placement() : "&7Сейчас в игре"));
        }
        if (results.isEmpty()) put(inv, 22, item(Material.BARRIER, "&7Нет данных", "&7В этом матче ещё нет статистики"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    private void killTopClick(Player p, String id, int s) {
        Arena a = plugin.getArenaManager().get(id);
        if (a == null) return;
        if (s == 53) openResults(p,a);
    }

    public void openTimeSettings(Player p, Arena a) {
        Inventory inv = create("time", a.id(), null, 54, "&eНастройки &8— &f" + a.id());
        put(inv, 10, valueButton(Material.SHIELD, "&bЗащита", a.settings().protectionSeconds, "сек", 10));
        put(inv, 12, valueButton(Material.CHEST, "&6Обновление сундуков", a.settings().chestRefillSeconds, "сек", 10));
        put(inv, 14, valueButton(Material.CLOCK, "&dНачало сужения", a.settings().shrinkStartDelaySeconds, "сек", 10));
        put(inv, 16, valueButton(Material.COMPASS, "&5Длительность стадии", a.settings().shrinkStageDurationSeconds, "сек", 10));
        put(inv, 20, valueButton(Material.END_CRYSTAL, "&cСтадий сужения", a.settings().shrinkStages, "шт", 1));
        put(inv, 22, valueButton(Material.IRON_SWORD, "&cУрон за границей", formatDecimal(a.settings().borderDamageHeartMultiplier), "x", 0.1));
        put(inv, 24, valueButton(Material.GOLDEN_APPLE, "&aКонечный размер", Math.round(a.settings().shrinkFinalRatio * 100), "%", 1));
        put(inv, 26, valueButton(Material.SUNFLOWER, "&eОбратный отсчёт", a.settings().countdownSeconds, "сек", 1));

        BorderMode mode = a.settings().borderMode;
        Material icon = switch (mode) {
            case DAMAGE -> Material.RED_WOOL;
            case SOFT_TELEPORT -> Material.LIME_WOOL;
            case CHANGING -> Material.ORANGE_WOOL;
        };
        String title = switch (mode) {
            case DAMAGE -> "&cПостоянная: граница с уроном";
            case SOFT_TELEPORT -> "&aПостоянная: мягкая граница";
            case CHANGING -> "&6Меняющаяся граница";
        };
        put(inv, 28, item(icon, title,
                "&7ЛКМ — переключить режим",
                mode == BorderMode.CHANGING
                        ? "&7Старт: &f" + a.settings().changingStartMode.displayName() + " &7→ &f" + a.settings().changingTargetMode.displayName()
                        : "&7Режим всегда остаётся выбранным"));

        if (mode == BorderMode.CHANGING) {
            put(inv, 30, item(a.settings().changingStartMode == BorderMode.SOFT_TELEPORT ? Material.LIME_WOOL : Material.RED_WOOL,
                    "&eНачальная граница: &f" + a.settings().changingStartMode.displayName(),
                    "&7ЛКМ — переключить стартовую границу"));
            put(inv, 32, item(a.settings().changingTargetMode == BorderMode.SOFT_TELEPORT ? Material.LIME_WOOL : Material.RED_WOOL,
                    "&eКонечная граница: &f" + a.settings().changingTargetMode.displayName(),
                    "&7ЛКМ — переключить конечную границу"));
            put(inv, 34, valueButton(Material.CLOCK, "&6Смена через", a.settings().borderChangeAfterSeconds, "сек", 10));
            put(inv, 36, valueButton(Material.POTION, "&dПлавный переход цвета", a.settings().borderChangeTransitionSeconds, "сек", 10));
        }

        put(inv, 40, item(Material.CHEST_MINECART, "&aИнвентарь участников", "&7До начала игры контейнеры участникам закрыты", "&7После начала игры их можно открывать"));
        put(inv, 42, item(Material.BARRIER, "&cПосторонним вход всегда запрещён", "&7В арену допускаются только участники", "&7и сотрудники-наблюдатели"));
        put(inv, 31, item(Material.WRITABLE_BOOK, "&fУправление значениями", "&7ЛКМ/ПКМ — обычный шаг", "&eShift+ЛКМ — +5", "&eShift+ПКМ — -5"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    public void openHelp(Player p) {
        Inventory inv = create("help", null, null, 54, "&fСправочник команд");
        String[][] entries = {
                {"/hg присоединиться <арена>","Обычный игрок присоединяется к открытому сбору","/hg присоединиться desert"},
                {"/hg выйти","Обычный игрок выходит из сбора или матча","/hg выйти"},
                {"/hg gui","Открывает главное меню","/hg gui"},
                {"/hg арена создать <имя>","Создаёт новую арену","/hg арена создать desert"},
                {"/hg точка1","Сохраняет первую точку","/hg точка1"},{"/hg точка1 убрать","Удаляет первую точку","/hg точка1 убрать"},
                {"/hg точка2","Сохраняет вторую точку","/hg точка2"},{"/hg точка2 убрать","Удаляет вторую точку","/hg точка2 убрать"},
                {"/hg спавн добавить","Добавляет точку появления","/hg спавн добавить"},{"/hg спавн убрать <номер>","Удаляет точку появления","/hg спавн убрать 2"},{"/hg сбор открыть","Открывает сбор","/hg сбор открыть"},
                {"/hg сбор закрыть","Закрывает сбор","/hg сбор закрыть"},{"/hg участник добавить <ник>","Добавляет игрока в матч","/hg участник добавить Steve"},
                {"/hg участник убрать <ник>","Удаляет игрока из списка","/hg участник убрать Steve"},{"/hg старт","Запускает игру","/hg старт"},
                {"/hg стоп","Останавливает игру","/hg стоп"},{"/hg наблюдать","Открывает режим наблюдения","/hg наблюдать"},
                {"/hg результаты","Открывает места и убийства","/hg результаты"},{"/hg сундук тип <тип>","Назначает тип лута контейнеру","/hg сундук тип rare"},
                {"/hg доступ добавить <ник>","Выдаёт доступ сотруднику","/hg доступ добавить Steve"}
        };
        for (int i=0;i<entries.length;i++) put(inv, i, item(Material.BOOK, "&e"+entries[i][0], "&7"+entries[i][1], "&fНажмите — показать пример в чат"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    public void handleClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof GuiHolder holder)) return;
        if (holder.screen.equals("template-editor")) {
            int rawSlot = e.getRawSlot();
            if (rawSlot >= 45 && rawSlot < 54) {
                e.setCancelled(true);
                if (rawSlot == 49) {
                    saveEditor(e.getInventory(), holder.arg);
                    editorSessions.remove(p.getUniqueId());
                    holder.disableSaveOnClose();
                    ChestTemplate t = plugin.getChestManager().template(holder.arg);
                    if (t != null) openTemplate(p, t);
                } else if (rawSlot == 53) {
                    editorSessions.remove(p.getUniqueId());
                    holder.disableSaveOnClose();
                    ChestTemplate t = plugin.getChestManager().template(holder.arg);
                    if (t != null) openTemplate(p, t);
                }
            } else if (rawSlot >= 0 && rawSlot < 45) {
                // Normal clicks inside the editable 45-slot area are allowed.
                p.getScheduler().run(plugin, ignored -> {
                    if (p.isOnline() && p.getOpenInventory().getTopInventory() == e.getInventory()) saveEditor(e.getInventory(), holder.arg);
                }, null);
            } else if (rawSlot >= 54 && e.isShiftClick()) {
                // Shift-click from the player's inventory: move the whole stack into the first free editor slot.
                ItemStack source = e.getCurrentItem();
                if (source != null && !source.getType().isAir()) {
                    int free = firstFreeEditorSlot(e.getInventory());
                    if (free >= 0) {
                        e.setCancelled(true);
                        e.getInventory().setItem(free, source.clone());
                        e.getWhoClicked().getInventory().setItem(e.getSlot(), null);
                        saveEditor(e.getInventory(), holder.arg);
                    } else {
                        e.setCancelled(true);
                    }
                } else {
                    e.setCancelled(true);
                }
            }
            return;
        }
        e.setCancelled(true);
        int slot=e.getRawSlot();
        switch(holder.screen) {
            case "main" -> mainClick(p, slot);
            case "arenas" -> arenaListClick(p, slot);
            case "arena" -> arenaClick(p, holder.arena, slot, e.isRightClick(), e.isShiftClick());
            case "templates" -> templatesClick(p, slot);
            case "template" -> templateClick(p, holder.arg, slot, e.isRightClick(), e.isShiftClick());
            case "participants" -> participantClick(p, holder.arena, slot);
            case "access" -> accessClick(p, slot);
            case "time" -> timeClick(p, holder.arena, slot, e.isRightClick(), e.isShiftClick());
            case "results" -> resultsClick(p, holder.arena, slot);
            case "observe" -> observeClick(p, holder.arena, slot);
            case "spawns" -> spawnListClick(p, holder.arena, slot);
            case "kill-top" -> killTopClick(p, holder.arena, slot);
            case "help" -> helpClick(p, slot);
            case "tools" -> toolsClick(p, slot);
            case "loot-markers" -> lootMarkersClick(p, holder.arg, slot);
        }
    }

    public void handleDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof GuiHolder holder)) return;
        if (!holder.screen.equals("template-editor")) {
            e.setCancelled(true);
            return;
        }
        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot >= 45 && rawSlot < 54) {
                e.setCancelled(true);
                return;
            }
        }
        if (!e.isCancelled() && e.getWhoClicked() instanceof Player p) {
            p.getScheduler().run(plugin, ignored -> {
                if (p.isOnline() && p.getOpenInventory().getTopInventory() == e.getInventory()) {
                    saveEditor(e.getInventory(), holder.arg);
                }
            }, null);
        }
    }

    public void handleClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof GuiHolder holder)) return;
        if (!holder.screen.equals("template-editor")) return;
        if (holder.saveOnClose()) saveEditor(e.getInventory(), holder.arg);
        if (e.getPlayer() instanceof Player p) {
            String session = editorSessions.get(p.getUniqueId());
            if (holder.arg.equals(session)) editorSessions.remove(p.getUniqueId());
        }
    }

    private int firstFreeEditorSlot(Inventory inv) {
        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) return i;
        }
        return -1;
    }

    private void saveEditor(Inventory inv, String type) {
        ChestTemplate t=plugin.getChestManager().template(type); if(t==null) return;
        t.items().clear();
        for(int i=0;i<45;i++) { ItemStack item=inv.getItem(i); if(item!=null && !item.getType().isAir()) t.items().add(item.clone()); }
        plugin.getChestManager().save();
    }

    private void mainClick(Player p,int s) {
        if(s==20) openArenaList(p);
        else if(s==22) openChestTypes(p);
        else if(s==24) openTools(p);
        else if(s==26) openAccess(p);
        else if(s==29) { Arena a=selected(p); if(a!=null) openTimeSettings(p,a); else openArenaList(p); }
        else if(s==31) openHelp(p);
        else if(s==40) p.closeInventory();
    }
    private Arena selected(Player p) { return plugin.getArenaManager().get(plugin.getSelectedArena(p.getUniqueId())); }
    private boolean confirmGuiPointChange(Player p, Arena a, int point, boolean remove) {
        // Если точки ещё нет, изменять нечего — применяем действие сразу.
        boolean exists = point == 1 ? a.pos1() != null : a.pos2() != null;
        if (!exists) {
            pendingGuiPointChanges.remove(p.getUniqueId());
            return false;
        }

        long now = System.currentTimeMillis();
        PendingGuiPointChange pending = pendingGuiPointChanges.get(p.getUniqueId());
        boolean confirmed = pending != null
                && pending.expiresAt() >= now
                && pending.arenaId().equals(a.id())
                && pending.point() == point
                && pending.remove() == remove;

        if (!confirmed) {
            pendingGuiPointChanges.put(p.getUniqueId(), new PendingGuiPointChange(a.id(), point, remove, now + 5000L));
            plugin.send(p, remove ? "point-remove-confirm" : "point-change-confirm", "point", point);
            return true;
        }

        pendingGuiPointChanges.remove(p.getUniqueId());
        return false;
    }

    private void arenaListClick(Player p,int s) {
        if(s<45) { int i=0; for(Arena a:plugin.getArenaManager().all()){ if(i++==s){openArena(p,a);return;} } }
        if(s==49) { plugin.getInputManager().request(p, text -> { Arena a=plugin.getArenaManager().create(text.trim(),p.getWorld()); if(a==null){plugin.send(p, "arena-exists");return;} plugin.selectArena(p.getUniqueId(),a.id()); plugin.send(p,"arena-created","arena",a.id()); openArena(p,a); }); }
        if(s==53) openMain(p);
    }
    private void arenaClick(Player p,String id,int s,boolean right,boolean shift){ Arena a=plugin.getArenaManager().get(id); if(a==null)return;
        switch(s){
            case 10 -> {
                if (confirmGuiPointChange(p, a, 1, right)) return;
                if (right) a.setPos1(null); else a.setPos1(p.getLocation());
                a.prepareShrink(); plugin.getArenaManager().save(); openArena(p,a);
            }
            case 12 -> {
                if (confirmGuiPointChange(p, a, 2, right)) return;
                if (right) a.setPos2(null); else a.setPos2(p.getLocation());
                a.prepareShrink(); plugin.getArenaManager().save(); openArena(p,a);
            }
            case 14 -> {a.setLobby(p.getLocation());plugin.getArenaManager().save();openArena(p,a);}
            case 16 -> { if (right) { p.getInventory().addItem(plugin.getSpawnWand()); plugin.send(p, "spawn-wand-given"); } else openSpawns(p,a); }
            case 36 -> p.getInventory().addItem(plugin.getSpawnWand());
            case 38 -> {p.getInventory().addItem(plugin.getSelectionWand());}
            case 20 -> {a.setCollecting(!a.collecting());plugin.getArenaManager().save();openArena(p,a);}
            case 22 -> openParticipants(p,a);
            case 24 -> openChestTypes(p);
            case 26 -> openResults(p,a);
            case 28 -> openObserveMenu(p,a);
            case 29 -> openTimeSettings(p,a);
            case 31 -> { if(!a.collecting()&&a.participants().size()>=a.minPlayers()&& !a.spawns().isEmpty()) plugin.getGameManager().start(a); openArena(p,a); }
            case 33 -> {plugin.getGameManager().stop(a,true);openArena(p,a);}
            case 40 -> { if(!right && !shift){ plugin.getInputManager().request(p, text->{String[] v=text.trim().split("\\s+");try{if(v.length>=3){double x=Double.parseDouble(v[0]),y=Double.parseDouble(v[1]),z=Double.parseDouble(v[2]);a.setPos1(new org.bukkit.Location(p.getWorld(),x,y,z));plugin.getArenaManager().save();openArena(p,a);}}catch(Exception ex){plugin.send(p,"invalid-coordinate");}});} else { int delta = shift ? (right ? -5 : 5) : 1; a.setMinPlayers(Math.max(1, a.minPlayers()+delta)); a.setMaxPlayers(Math.max(a.minPlayers(), a.maxPlayers()+delta)); plugin.getArenaManager().save();openArena(p,a);} }
            case 41 -> { a.setMaxPlayersBySpawns(!a.maxPlayersBySpawns()); plugin.getArenaManager().save(); openArena(p,a); }
            case 42 -> { if(plugin.getAccessManager().isCreator(p)){plugin.getArenaManager().delete(a.id());openArenaList(p);} }
            case 53 -> openArenaList(p);
        }
    }
    public void openSpawns(Player p, Arena a) {
        Inventory inv = create("spawns", a.id(), null, 54, "&eТочки появления &8— &f" + a.id());
        int slot = 0;
        int index = 1;
        for (org.bukkit.Location loc : a.spawns()) {
            if (slot >= 45) break;
            put(inv, slot++, item(Material.END_ROD, "&eТочка №" + index,
                    "&7Координаты: &f" + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ(),
                    "&7ЛКМ — удалить эту точку"));
            index++;
        }
        put(inv, 49, item(Material.BLAZE_ROD, "&aПолучить маркер точки", "&7ЛКМ по блоку — добавить", "&7ПКМ рядом — удалить ближайшую"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    private void spawnListClick(Player p, String id, int s) {
        Arena a = plugin.getArenaManager().get(id);
        if (a == null) return;
        if (s < 45 && s < a.spawns().size()) {
            a.removeSpawn(s);
            plugin.getArenaManager().save();
            plugin.send(p, "spawn-removed-index", "index", s + 1);
            openSpawns(p, a);
            return;
        }
        if (s == 49) { p.getInventory().addItem(plugin.getSpawnWand()); plugin.send(p, "spawn-wand-given"); return; }
        if (s == 53) openArena(p,a);
    }

    private void toolsClick(Player p, int s) {
        if (s == 10) { p.getInventory().addItem(plugin.getSelectionWand()); plugin.send(p, "selection-wand-given"); return; }
        if (s == 12) { p.getInventory().addItem(plugin.getSpawnWand()); plugin.send(p, "spawn-wand-given"); return; }
        if (s == 16) { openLootMarkers(p, 0); return; }
        if (s == 53) openMain(p);
    }

    public void openLootMarkers(Player p, int page) {
        List<ChestTemplate> templates = new ArrayList<>(plugin.getChestManager().templates());
        int perPage = 51;
        int pages = Math.max(1, (templates.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        Inventory inv = create("loot-markers", null, String.valueOf(page), 54,
                "&6Макеты лута &8— &f" + (page + 1) + "&7/" + pages);
        int start = page * perPage;
        int end = Math.min(start + perPage, templates.size());
        for (int index = start; index < end; index++) {
            ChestTemplate t = templates.get(index);
            put(inv, index - start, item(t.icon(), "&6" + t.displayName(),
                    "&7ID: &f" + t.id(),
                    "&7Предметов: &f" + t.items().size(),
                    "&eНажмите — получить маркер"));
        }
        if (page > 0) put(inv, 51, item(Material.ARROW, "&eНазад", "&7Предыдущая страница"));
        if (page + 1 < pages) put(inv, 52, item(Material.ARROW, "&aВперёд", "&7Следующая страница"));
        put(inv, 53, item(Material.BARRIER, "&cНазад"));
        p.openInventory(inv);
    }

    private void lootMarkersClick(Player p, String pageArg, int slot) {
        int page;
        try { page = Integer.parseInt(pageArg); } catch (NumberFormatException ex) { page = 0; }
        List<ChestTemplate> templates = new ArrayList<>(plugin.getChestManager().templates());
        int perPage = 51;
        int pages = Math.max(1, (templates.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        if (slot < 51) {
            int index = page * perPage + slot;
            if (index >= 0 && index < templates.size()) {
                ChestTemplate t = templates.get(index);
                p.getInventory().addItem(plugin.getContainerMarker(t));
                plugin.send(p, "marker-given", "type", t.displayName());
            }
            return;
        }
        if (slot == 51 && page > 0) { openLootMarkers(p, page - 1); return; }
        if (slot == 52 && page + 1 < pages) { openLootMarkers(p, page + 1); return; }
        if (slot == 53) openTools(p);
    }

    private void templatesClick(Player p, int s) {
        int page = 0;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof GuiHolder h && h.arg != null) {
            try { page = Integer.parseInt(h.arg); } catch (NumberFormatException ignored) { }
        }
        List<ChestTemplate> templates = new ArrayList<>(plugin.getChestManager().templates());
        int perPage = 45;
        int pages = Math.max(1, (templates.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        if (s < 45) {
            int index = page * perPage + s;
            if (index >= 0 && index < templates.size()) {
                openTemplate(p, templates.get(index));
                return;
            }
        }
        if (s == 49) {
            plugin.getInputManager().request(p, text -> {
                String id = text.trim();
                if (id.isEmpty()) { plugin.send(p, "template-exists"); return; }
                ChestTemplate t = plugin.getChestManager().createTemplate(id);
                if (t == null) { plugin.send(p, "template-exists"); return; }
                plugin.send(p, "template-created", "type", t.id());
                openTemplate(p, t);
            });
            return;
        }
        if (s == 51 && page > 0) { openChestTypes(p, page - 1); return; }
        if (s == 52 && page + 1 < pages) { openChestTypes(p, page + 1); return; }
        if (s == 53) openMain(p);
    }

    private void templateClick(Player p,String type,int s,boolean right,boolean shift){
        ChestTemplate t=plugin.getChestManager().template(type);if(t==null)return;
        switch(s){
            case 46 -> openTemplateEditor(p,t);
            case 47 -> {
                if (!plugin.getChestManager().deleteTemplate(t.id())) {
                    plugin.send(p, t.id().equalsIgnoreCase("common") ? "template-common-protected" : "template-delete-failed", "type", t.displayName());
                    return;
                }
                plugin.send(p, "template-deleted", "type", t.displayName());
                openChestTypes(p);
            }
            case 48 -> p.getInventory().addItem(plugin.getContainerMarker(t));
            case 50->{int delta=shift?(right?-5:5):(right?-1:1);t.setMinItems(Math.max(0,t.minItems()+delta));if(t.maxItems()<t.minItems())t.setMaxItems(t.minItems());plugin.getChestManager().save();openTemplate(p,t);}
            case 51->{int delta=shift?(right?-5:5):(right?-1:1);t.setMaxItems(Math.max(t.minItems(),t.maxItems()+delta));plugin.getChestManager().save();openTemplate(p,t);}
            case 52->{double delta=(shift?(right?-0.05:0.05):(right?-0.01:0.01));t.setItemChance(Math.max(0.0,Math.min(1.0,t.itemChance()+delta)));plugin.getChestManager().save();openTemplate(p,t);}
            case 53->openChestTypes(p);
        }
    }
    private void participantClick(Player p,String id,int s){
        Arena a=plugin.getArenaManager().get(id); if(a==null)return;
        if(s<45){int i=0;for(UUID u:new ArrayList<>(a.participants())){if(i++==s){
            if(a.state()==ArenaState.WAITING){a.participants().remove(u);a.clearSpawnSlot(u);plugin.getArenaManager().save();plugin.send(p,"participant-removed","player",Bukkit.getOfflinePlayer(u).getName());openParticipants(p,a);}return;
        }}}
        if(s==49){
            if(a.state()!=ArenaState.WAITING){plugin.send(p,"participants-locked");return;}
            plugin.getInputManager().request(p,text->{org.bukkit.OfflinePlayer target=Bukkit.getOfflinePlayer(text.trim());if(a.participants().contains(target.getUniqueId())){plugin.send(p,"already-participant-target","player",text.trim());return;}if(a.participants().size()>=a.maxPlayers()){plugin.send(p,"join-full");return;}int freeSlot=plugin.getGameManager().assignParticipantSpawn(a,target.getUniqueId());if(freeSlot<0){plugin.send(p,"no-spawn-slots");return;}a.participants().add(target.getUniqueId());
                Player targetPlayer = plugin.getGameManager().getOnlinePlayer(target.getUniqueId());
                if (targetPlayer != null) {
                    targetPlayer.getScheduler().run(plugin, ignored -> {
                        plugin.getGameManager().rememberParticipantState(targetPlayer);
                        plugin.getGameManager().teleportParticipantToAssignedSpawn(targetPlayer, a);
                        plugin.send(targetPlayer, "participant-added-you", "arena", a.id());
                    }, null);
                }
                plugin.getArenaManager().save();
                plugin.send(p,"participant-added","player",target.getName()==null?text.trim():target.getName());
                openParticipants(p,a);});
            return;
        }
        if(s==53)openArena(p,a);
    }

    public void openObserveMenu(Player p, Arena a) {
        Inventory inv=create("observe",a.id(),null,27,"&bНаблюдение &8— &f"+a.id());
        put(inv,11,item(Material.ENDER_EYE,"&5SPECTATOR","&7Режим SPECTATOR","&7BossBar наблюдателя включён","&7После выхода возвращается прежний режим и место"));
        put(inv,15,item(Material.COMPASS,"&bОбычное наблюдение","&7Плагин не меняет режим игры","&7Инвентарь и возможности сервера не трогаются","&7BossBar наблюдателя включён"));
        if(plugin.getGameManager().isObserver(p.getUniqueId(),a)) put(inv,22,item(Material.BARRIER,"&cВыйти из наблюдения","&7Вернуть исходное состояние и место"));
        put(inv,26,item(Material.BARRIER,"&cНазад"));
        p.openInventory(inv);
    }

    private void observeClick(Player p,String id,int s){
        Arena a=plugin.getArenaManager().get(id); if(a==null)return;
        if(s==11){plugin.getGameManager().observe(p,a,true);}
        else if(s==15){plugin.getGameManager().observe(p,a,false);}
        else if(s==22){plugin.getGameManager().leaveObserver(p);}
        else if(s==26){openArena(p,a);}
    }
    private void accessClick(Player p, int s) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(plugin.getAccessManager().granted().keySet());
        for (OfflinePlayer op : Bukkit.getOperators()) ids.add(op.getUniqueId());
        ids.removeIf(uuid -> plugin.getAccessManager().isCreator(Bukkit.getOfflinePlayer(uuid)));
        if (s < 45) {
            int i = 0;
            for (UUID u : ids) {
                if (i++ != s) continue;
                if (plugin.getAccessManager().granted().containsKey(u)) {
                    String name = plugin.getAccessManager().displayName(u);
                    if (plugin.getAccessManager().remove(name)) plugin.send(p, "access-removed", "player", name);
                    else plugin.send(p, "access-remove-failed", "player", name);
                }
                openAccess(p);
                return;
            }
        }
        if (s == 49) {
            plugin.getInputManager().request(p, text -> {
                if (!plugin.getAccessManager().add(text.trim())) plugin.send(p, "access-add-failed", "player", text.trim());
                else plugin.send(p, "access-added", "player", text.trim());
                openAccess(p);
            });
            return;
        }
        if (s == 53) openMain(p);
    }

    private void timeClick(Player p, String id, int s, boolean right, boolean shift) {
        Arena a = plugin.getArenaManager().get(id);
        if (a == null) return;
        if (s == 53) { plugin.getArenaManager().save(); openArena(p, a); return; }
        if (s == 28) {
            a.setBorderMode(a.settings().borderMode.next());
            plugin.getArenaManager().save();
            openTimeSettings(p, a);
            return;
        }
        if (s == 30 && a.settings().borderMode == BorderMode.CHANGING) {
            a.settings().changingStartMode = a.settings().changingStartMode == BorderMode.SOFT_TELEPORT ? BorderMode.DAMAGE : BorderMode.SOFT_TELEPORT;
            plugin.getArenaManager().save();
            openTimeSettings(p, a);
            return;
        }
        if (s == 32 && a.settings().borderMode == BorderMode.CHANGING) {
            a.settings().changingTargetMode = a.settings().changingTargetMode == BorderMode.SOFT_TELEPORT ? BorderMode.DAMAGE : BorderMode.SOFT_TELEPORT;
            plugin.getArenaManager().save();
            openTimeSettings(p, a);
            return;
        }
        double sign = right ? -1.0 : 1.0;
        switch (s) {
            case 10 -> a.settings().protectionSeconds = Math.max(0, a.settings().protectionSeconds + (long)(shift ? sign * 5 : sign * 10));
            case 12 -> a.settings().chestRefillSeconds = Math.max(0, a.settings().chestRefillSeconds + (long)(shift ? sign * 5 : sign * 10));
            case 14 -> a.settings().shrinkStartDelaySeconds = Math.max(0, a.settings().shrinkStartDelaySeconds + (long)(shift ? sign * 5 : sign * 10));
            case 16 -> a.settings().shrinkStageDurationSeconds = Math.max(1, a.settings().shrinkStageDurationSeconds + (long)(shift ? sign * 5 : sign * 10));
            case 20 -> a.settings().shrinkStages = Math.max(1, a.settings().shrinkStages + (int)(shift ? sign * 5 : sign));
            case 22 -> a.settings().borderDamageHeartMultiplier = round2(Math.max(0.1, a.settings().borderDamageHeartMultiplier + (shift ? sign * 0.5 : sign * 0.1)));
            case 24 -> a.settings().shrinkFinalRatio = Math.max(0.05, Math.min(1, a.settings().shrinkFinalRatio + (shift ? sign * 0.05 : sign * 0.01)));
            case 26 -> a.settings().countdownSeconds = Math.max(1, a.settings().countdownSeconds + (int)(shift ? sign * 5 : sign));
            case 34 -> { if (a.settings().borderMode == BorderMode.CHANGING) a.settings().borderChangeAfterSeconds = Math.max(0, a.settings().borderChangeAfterSeconds + (long)(shift ? sign * 5 : sign * 10)); }
            case 36 -> { if (a.settings().borderMode == BorderMode.CHANGING) a.settings().borderChangeTransitionSeconds = Math.max(0, a.settings().borderChangeTransitionSeconds + (long)(shift ? sign * 5 : sign * 10)); }
        }
        plugin.getArenaManager().save();
        openTimeSettings(p, a);
    }

    private void helpClick(Player p,int s){
        String[][] entries = {
                {"/hg присоединиться <арена>","Обычный игрок присоединяется к открытому сбору","/hg присоединиться desert"},
                {"/hg выйти","Обычный игрок выходит из сбора или матча","/hg выйти"},
                {"/hg gui","Открывает главное меню","/hg gui"},
                {"/hg арена создать <имя>","Создаёт новую арену","/hg арена создать desert"},
                {"/hg точка1","Сохраняет первую точку","/hg точка1"},
                {"/hg точка2","Сохраняет вторую точку","/hg точка2"},
                {"/hg спавн добавить","Добавляет спавн","/hg спавн добавить"},
                {"/hg сбор открыть","Открывает сбор","/hg сбор открыть"},
                {"/hg сбор закрыть","Закрывает сбор","/hg сбор закрыть"},
                {"/hg участник добавить <ник>","Добавляет игрока в матч","/hg участник добавить Steve"},
                {"/hg участник убрать <ник>","Удаляет игрока из списка","/hg участник убрать Steve"},
                {"/hg старт","Запускает игру","/hg старт"},
                {"/hg стоп","Останавливает игру","/hg стоп"},
                {"/hg наблюдать","Открывает режим наблюдения","/hg наблюдать"},
                {"/hg граница <урон|мягкая>","Выбирает режим границы","/hg граница мягкая"},
                {"/hg результаты","Открывает места и убийства","/hg результаты"},
                {"/hg сундук тип <тип>","Назначает тип лута контейнеру","/hg сундук тип rare"},
                {"/hg доступ добавить <ник>","Выдаёт доступ сотруднику","/hg доступ добавить Steve"}
        };
        if(s<entries.length){
            p.sendMessage(HungerGamesPlugin.color("&dПример команды: &f"+entries[s][2]));
        } else if(s==53) openMain(p);
    }

    private Inventory create(String screen,String arena,String arg,int size,String title){GuiHolder holder=new GuiHolder(screen,arena,arg);Inventory inv=Bukkit.createInventory(holder,size,HungerGamesPlugin.color(title));holder.bind(inv);return inv;}
    private void put(Inventory inv,int slot,ItemStack item){inv.setItem(slot,item);}
    private ItemStack item(Material mat,String name,String... lore){ItemStack s=new ItemStack(mat);ItemMeta m=s.getItemMeta();if(m!=null){m.setDisplayName(HungerGamesPlugin.color(name));m.setLore(Arrays.stream(lore).map(HungerGamesPlugin::color).toList());s.setItemMeta(m);}return s;}
    private ItemStack head(OfflinePlayer player,String name,String... lore){ItemStack s=item(Material.PLAYER_HEAD,"&f"+name,lore);SkullMeta m=(SkullMeta)s.getItemMeta();m.setOwningPlayer(player);s.setItemMeta(m);return s;}
    private ItemStack valueButton(Material mat,String title,Object value,String suffix,double normalStep){
        String stepText = normalStep == 10 ? "10" : normalStep == 1 ? "1" : String.valueOf(normalStep);
        return item(mat,title,"&7Значение: &f"+value+" "+suffix,
                "&eЛКМ +"+stepText+" / ПКМ -"+stepText,
                "&eShift+ЛКМ +5 / Shift+ПКМ -5");
    }
    private String formatDecimal(double value) {
        return new java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US)).format(value);
    }
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
    private String locLine(org.bukkit.Location l){return l==null?"&cне задана":"&f"+l.getBlockX()+" &f"+l.getBlockY()+" &f"+l.getBlockZ();}
}
