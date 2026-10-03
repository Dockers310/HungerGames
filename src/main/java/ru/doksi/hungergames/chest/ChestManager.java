package ru.doksi.hungergames.chest;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import ru.doksi.hungergames.HungerGamesPlugin;
import ru.doksi.hungergames.arena.Arena;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class ChestManager {
    private final HungerGamesPlugin plugin;
    private final Map<String, ChestTemplate> templates = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> arenaChests = new java.util.concurrent.ConcurrentHashMap<>();
    private File file;
    private YamlConfiguration config;

    public ChestManager(HungerGamesPlugin plugin) { this.plugin = plugin; }

    public void load() {
        templates.clear(); arenaChests.clear();
        file = new File(plugin.getDataFolder(), "chests.yml");
        config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("templates");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(id);
                if (sec == null) continue;
                Material icon = Material.matchMaterial(sec.getString("icon", "CHEST"));
                if (icon == null) icon = Material.CHEST;
                ChestTemplate template = new ChestTemplate(id, sec.getString("display-name", id), icon);
                template.setMinItems(sec.getInt("min-items", 2));
                template.setMaxItems(sec.getInt("max-items", 5));
                template.setItemChance(sec.getDouble("item-chance", 0.85));
                for (Map<?, ?> raw : sec.getMapList("items")) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : raw.entrySet()) map.put(String.valueOf(e.getKey()), e.getValue());
                    try { template.items().add(ItemStack.deserialize(map)); } catch (Exception ignored) { }
                }
                templates.put(id.toLowerCase(Locale.ROOT), template);
            }
        }
        ConfigurationSection arenas = config.getConfigurationSection("arena-chests");
        if (arenas != null) {
            for (String arenaId : arenas.getKeys(false)) {
                ConfigurationSection section = arenas.getConfigurationSection(arenaId);
                if (section == null) continue;
                Map<String, String> map = new LinkedHashMap<>();
                for (String key : section.getKeys(false)) map.put(key, section.getString(key, "common"));
                arenaChests.put(arenaId.toLowerCase(Locale.ROOT), map);
            }
        }
        if (!templates.containsKey("common")) {
            ChestTemplate common = new ChestTemplate("common", "Обычный", Material.CHEST);
            templates.put("common", common);
            save();
        }
    }

    public void save() {
        if (config == null) config = new YamlConfiguration();
        config.set("templates", null);
        for (ChestTemplate t : templates.values()) {
            String base = "templates." + t.id();
            config.set(base + ".display-name", t.displayName());
            config.set(base + ".icon", t.icon().name());
            config.set(base + ".min-items", t.minItems());
            config.set(base + ".max-items", t.maxItems());
            config.set(base + ".item-chance", t.itemChance());
            List<Map<String, Object>> itemMaps = new ArrayList<>();
            for (ItemStack item : t.items()) if (item != null && !item.getType().isAir()) itemMaps.add(item.serialize());
            config.set(base + ".items", itemMaps);
        }
        config.set("arena-chests", null);
        for (Map.Entry<String, Map<String, String>> e : arenaChests.entrySet()) {
            for (Map.Entry<String, String> chest : e.getValue().entrySet()) config.set("arena-chests." + e.getKey() + "." + chest.getKey(), chest.getValue());
        }
        try { config.save(file); } catch (IOException e) { plugin.getLogger().severe("Could not save chests.yml: " + e.getMessage()); }
    }

    public Collection<ChestTemplate> templates() { return Collections.unmodifiableCollection(templates.values()); }
    public ChestTemplate template(String id) { return id == null ? null : templates.get(id.toLowerCase(Locale.ROOT)); }
    public ChestTemplate createTemplate(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (templates.containsKey(key)) return null;
        ChestTemplate t = new ChestTemplate(key, id, Material.CHEST); templates.put(key, t); save(); return t;
    }

    /** Deletes a custom loot template and removes it from all container assignments. */
    public boolean deleteTemplate(String id) {
        if (id == null) return false;
        String key = id.toLowerCase(Locale.ROOT);
        if (!templates.containsKey(key) || key.equals("common")) return false;
        templates.remove(key);
        for (Map<String, String> map : arenaChests.values()) {
            map.entrySet().removeIf(entry -> key.equalsIgnoreCase(entry.getValue()));
        }
        save();
        return true;
    }

    /** Assigns a loot template to any supported block inventory. */
    public void setContainer(Arena arena, Block block, String type) {
        if (arena == null || block == null || type == null || template(type) == null || !isLootContainer(block)) return;
        arenaChests.computeIfAbsent(arena.id().toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>()).put(chestKey(block), type.toLowerCase(Locale.ROOT));
        save();
    }

    /** Backwards-compatible alias. */
    public void setChest(Arena arena, Block block, String type) { setContainer(arena, block, type); }

    public void removeContainer(Arena arena, Block block) {
        Map<String,String> map = arenaChests.get(arena.id().toLowerCase(Locale.ROOT));
        if (map != null) { map.remove(chestKey(block)); save(); }
    }

    /** Backwards-compatible alias. */
    public void removeChest(Arena arena, Block block) { removeContainer(arena, block); }

    public String getType(Arena arena, Block block) {
        Map<String,String> map = arenaChests.get(arena.id().toLowerCase(Locale.ROOT));
        return map == null ? null : map.get(chestKey(block));
    }

    /** True for block entities that expose a real Bukkit inventory. */
    public boolean isLootContainer(Block block) { return getContainerInventory(block) != null; }

    /** True for the supported physical loot-container inventory types. */
    public boolean isLootContainerInventory(Inventory inventory) {
        if (inventory == null) return false;
        return switch (inventory.getType()) {
            case CHEST, BARREL, DISPENSER, DROPPER, HOPPER, SHULKER_BOX -> true;
            default -> false;
        };
    }

    public Inventory getContainerInventory(Block block) {
        if (block == null) return null;
        if (!(block.getState() instanceof InventoryHolder holder)) return null;
        Inventory inventory = holder.getInventory();
        return inventory == null || inventory.getSize() <= 0 ? null : inventory;
    }


    public boolean isRegisteredLootInventory(Arena arena, Inventory inventory) {
        if (arena == null || inventory == null) return false;
        Map<String, String> map = arenaChests.get(arena.id().toLowerCase(Locale.ROOT));
        if (map == null) return false;
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) return false;
        return map.containsKey(chestKey(location.getBlockX(), location.getBlockY(), location.getBlockZ(), location.getWorld().getUID()));
    }

    public String describeContainer(Block block) {
        if (block == null) return "Контейнер";
        return switch (block.getType()) {
            case CHEST -> "Сундук";
            case TRAPPED_CHEST -> "Сундук-ловушка";
            case BARREL -> "Бочка";
            case DISPENSER -> "Раздатчик";
            case DROPPER -> "Выбрасыватель";
            case HOPPER -> "Воронка";
            case SHULKER_BOX -> "Шалкер-бокс";
            default -> "Контейнер (" + block.getType().translationKey() + ")";
        };
    }

    public void removeArena(String arenaId) {
        if (arenaId == null) return;
        arenaChests.remove(arenaId.toLowerCase(Locale.ROOT));
        save();
    }

    public Map<String,String> chests(Arena arena) {
        return arenaChests.getOrDefault(arena.id().toLowerCase(Locale.ROOT), Collections.emptyMap());
    }

    public void refill(Arena arena) {
        Map<String,String> map = arenaChests.get(arena.id().toLowerCase(Locale.ROOT));
        if (map == null) return;
        for (Map.Entry<String,String> entry : new ArrayList<>(map.entrySet())) {
            ContainerLocation target = parseKey(entry.getKey());
            if (target == null) continue;
            ChestTemplate template = template(entry.getValue());
            if (template == null) continue;
            plugin.getServer().getRegionScheduler().run(plugin, target.world, target.chunkX(), target.chunkZ(), ignored -> {
                Block block = target.world.getBlockAt(target.x, target.y, target.z);
                Inventory inv = getContainerInventory(block);
                if (inv == null) return;
                inv.clear();
                List<ItemStack> loot = template.generateLoot();
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < inv.getSize(); i++) slots.add(i);
                Collections.shuffle(slots);
                int placed = 0;
                for (ItemStack item : loot) {
                    if (item == null || item.getType().isAir() || placed >= slots.size()) continue;
                    inv.setItem(slots.get(placed++), item.clone());
                }
            });
        }
    }

    /** Clears every registered loot container on the arena after a match ends/stops. */
    public void clearContainers(Arena arena) {
        if (arena == null) return;
        Map<String,String> map = arenaChests.get(arena.id().toLowerCase(Locale.ROOT));
        if (map == null) return;
        for (String key : new ArrayList<>(map.keySet())) {
            ContainerLocation target = parseKey(key);
            if (target == null) continue;
            plugin.getServer().getRegionScheduler().run(plugin, target.world, target.chunkX(), target.chunkZ(), ignored -> {
                Block block = target.world.getBlockAt(target.x, target.y, target.z);
                Inventory inv = getContainerInventory(block);
                if (inv != null) inv.clear();
            });
        }
    }

    private String chestKey(Block block) {
        return chestKey(block.getX(), block.getY(), block.getZ(), block.getWorld().getUID());
    }

    private String chestKey(int x, int y, int z, java.util.UUID worldUid) {
        return worldUid + ":" + x + "," + y + "," + z;
    }

    private ContainerLocation parseKey(String key) {
        try {
            String[] split = key.split(":", 2);
            if (split.length != 2) return null;
            java.util.UUID uid = java.util.UUID.fromString(split[0]);
            String[] xyz = split[1].split(",");
            if (xyz.length != 3) return null;
            org.bukkit.World world = Bukkit.getWorld(uid);
            if (world == null) return null;
            int x = Integer.parseInt(xyz[0]);
            int y = Integer.parseInt(xyz[1]);
            int z = Integer.parseInt(xyz[2]);
            return new ContainerLocation(world, x, y, z);
        } catch (Exception e) { return null; }
    }

    private static final class ContainerLocation {
        final org.bukkit.World world; final int x; final int y; final int z;
        ContainerLocation(org.bukkit.World world, int x, int y, int z) { this.world = world; this.x = x; this.y = y; this.z = z; }
        int chunkX() { return x >> 4; }
        int chunkZ() { return z >> 4; }
    }

}
