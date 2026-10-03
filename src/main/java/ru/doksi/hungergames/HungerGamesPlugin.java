package ru.doksi.hungergames;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import ru.doksi.hungergames.access.AccessManager;
import ru.doksi.hungergames.arena.ArenaManager;
import ru.doksi.hungergames.chest.ChestManager;
import ru.doksi.hungergames.command.HungerGamesCommand;
import ru.doksi.hungergames.game.GameManager;
import ru.doksi.hungergames.game.MatchHistoryManager;
import ru.doksi.hungergames.gui.GuiManager;
import ru.doksi.hungergames.listener.HungerGamesListener;
import ru.doksi.hungergames.util.InputManager;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public final class HungerGamesPlugin extends JavaPlugin {
    private static HungerGamesPlugin instance;

    private YamlConfiguration messages;
    private ArenaManager arenaManager;
    private ChestManager chestManager;
    private AccessManager accessManager;
    private InputManager inputManager;
    private GameManager gameManager;
    private MatchHistoryManager matchHistoryManager;
    private GuiManager guiManager;
    private final Map<UUID, String> selectedArena = new ConcurrentHashMap<>();

    public static HungerGamesPlugin getInstance() { return instance; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("arenas.yml");
        saveResourceIfMissing("chests.yml");
        saveResourceIfMissing("access.yml");
        saveResourceIfMissing("matches.yml");

        reloadMessages();

        accessManager = new AccessManager(this);
        arenaManager = new ArenaManager(this);
        chestManager = new ChestManager(this);
        inputManager = new InputManager(this);
        matchHistoryManager = new MatchHistoryManager(this);
        gameManager = new GameManager(this);
        guiManager = new GuiManager(this);

        accessManager.load();
        arenaManager.load();
        chestManager.load();
        matchHistoryManager.load();

        HungerGamesListener listener = new HungerGamesListener(this);
        Bukkit.getPluginManager().registerEvents(listener, this);

        HungerGamesCommand command = new HungerGamesCommand(this);
        PluginCommand hg = getCommand("hg");
        if (hg != null) {
            hg.setExecutor(command);
            hg.setTabCompleter(command);
        }

        gameManager.startTicker();
        for (Player player : Bukkit.getOnlinePlayers()) gameManager.trackOnlinePlayer(player);
        getLogger().info("HungerGames 1.6.4 enabled. Paper/Folia compatible mode is active.");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.shutdown();
        if (arenaManager != null) arenaManager.save();
        if (chestManager != null) chestManager.save();
        if (accessManager != null) accessManager.save();
        if (matchHistoryManager != null) matchHistoryManager.save();
        instance = null;
    }

    public void reloadPluginData() {
        for (ru.doksi.hungergames.arena.Arena arena : arenaManager.all()) {
            if (arena.state() != ru.doksi.hungergames.arena.ArenaState.WAITING) gameManager.stop(arena, false);
        }
        reloadConfig();
        reloadMessages();
        arenaManager.load();
        chestManager.load();
        accessManager.load();
    }

    private void saveResourceIfMissing(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) saveResource(name, false);
    }

    private void reloadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public String msg(String key, Object... replacements) {
        String text = messages == null ? null : messages.getString(key, null);
        if (text == null) {
            try (java.io.InputStream in = getResource("messages.yml")) {
                if (in != null) {
                    YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                    text = defaults.getString(key, "");
                } else {
                    text = "";
                }
            } catch (java.io.IOException ignored) {
                text = "";
            }
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        }
        // Never expose a legacy plugin prefix from an older messages.yml.
        // Existing server files are intentionally not overwritten by saveResourceIfMissing().
        text = text.replaceAll("(?i)\\bHungerGames\\b", "").replaceAll("[ \t]{2,}", " ").trim();
        return color(text);
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public void send(org.bukkit.entity.Player player, String key, Object... replacements) {
        if (player == null) return;
        String message = msg(key, replacements);
        if (!message.isBlank()) player.sendMessage(message);
    }

    /**
     * Kept for binary/source compatibility. HungerGames never prefixes chat messages.
     */
    public String getMessagePrefix() {
        return "";
    }

    public ArenaManager getArenaManager() { return arenaManager; }
    public ChestManager getChestManager() { return chestManager; }
    public AccessManager getAccessManager() { return accessManager; }
    public InputManager getInputManager() { return inputManager; }
    public GameManager getGameManager() { return gameManager; }
    public MatchHistoryManager getMatchHistoryManager() { return matchHistoryManager; }
    public GuiManager getGuiManager() { return guiManager; }

    public String getSelectedArena(UUID uuid) {
        return selectedArena.get(uuid);
    }

    public void selectArena(UUID uuid, String arenaId) {
        selectedArena.put(uuid, arenaId);
    }

    public String requireSelectedArena(org.bukkit.entity.Player player) {
        return getSelectedArena(player.getUniqueId());
    }


    public ItemStack getSelectionWand() {
        Material material = Material.matchMaterial(getConfig().getString("selection-wand.material", "IRON_PICKAXE"));
        if (material == null || !material.name().endsWith("_PICKAXE")) material = Material.IRON_PICKAXE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("selection-wand.name", "&dКирка границ арены")));
            meta.setLore(getConfig().getStringList("selection-wand.lore").stream().map(HungerGamesPlugin::color).toList());
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "hg_selection_wand"), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }


    public ItemStack getSpawnWand() {
        Material material = Material.matchMaterial(getConfig().getString("spawn-wand.material", "BLAZE_ROD"));
        if (material == null) material = Material.BLAZE_ROD;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("spawn-wand.name", "&eМаркер точки спавна")));
            meta.setLore(getConfig().getStringList("spawn-wand.lore").stream()
                    .map(HungerGamesPlugin::color).toList());
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "hg_spawn_wand"), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack getContainerMarker(ru.doksi.hungergames.chest.ChestTemplate template) {
        String base = getConfig().getConfigurationSection("container-marker") != null ? "container-marker" : "chest-marker";
        Material material = Material.matchMaterial(getConfig().getString(base + ".material", "PAPER"));
        if (material == null) material = Material.PAPER;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString(base + ".name", "&6Маркер контейнера — &f{type}").replace("{type}", template.displayName())));
            java.util.List<String> lore = new java.util.ArrayList<>();
            for (String line : getConfig().getStringList(base + ".lore")) lore.add(color(line.replace("{type}", template.displayName())));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "hg_marker"), PersistentDataType.BYTE, (byte)1);
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "hg_marker_type"), PersistentDataType.STRING, template.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Backwards-compatible alias used by older integrations. */
    public ItemStack getChestMarker(ru.doksi.hungergames.chest.ChestTemplate template) { return getContainerMarker(template); }
}
