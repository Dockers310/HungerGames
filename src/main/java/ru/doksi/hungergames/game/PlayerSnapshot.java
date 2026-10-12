package ru.doksi.hungergames.game;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class PlayerSnapshot {
    private final ItemStack[] storage;
    private final ItemStack[] armor;
    private final ItemStack offHand;
    private final ItemStack cursor;
    private final UUID worldUid;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final GameMode gameMode;
    private final int level;
    private final float exp;
    private final int totalExperience;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final float exhaustion;
    private final int fireTicks;
    private final int air;
    private final float fallDistance;
    private final boolean allowFlight;
    private final boolean flying;
    private final List<PotionEffect> potionEffects;

    private PlayerSnapshot(Player player, Location returnLocation) {
        storage = cloneItems(player.getInventory().getStorageContents());
        armor = cloneItems(player.getInventory().getArmorContents());
        offHand = cloneItem(player.getInventory().getItemInOffHand());
        cursor = cloneItem(player.getItemOnCursor());
        Location location = returnLocation != null && returnLocation.getWorld() != null ? returnLocation : player.getLocation();
        World world = location.getWorld();
        worldUid = world == null ? null : world.getUID();
        worldName = world == null ? null : world.getName();
        x = location.getX();
        y = location.getY();
        z = location.getZ();
        yaw = location.getYaw();
        pitch = location.getPitch();
        gameMode = player.getGameMode();
        level = player.getLevel();
        exp = player.getExp();
        totalExperience = player.getTotalExperience();
        health = player.getHealth();
        foodLevel = player.getFoodLevel();
        saturation = player.getSaturation();
        exhaustion = player.getExhaustion();
        fireTicks = player.getFireTicks();
        air = player.getRemainingAir();
        fallDistance = player.getFallDistance();
        allowFlight = player.getAllowFlight();
        flying = player.isFlying();
        potionEffects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) potionEffects.add(effect);
    }

    private PlayerSnapshot(YamlConfiguration yaml) {
        storage = decodeItems(yaml.getString("storage"));
        armor = decodeItems(yaml.getString("armor"));
        offHand = firstItem(decodeItems(yaml.getString("off-hand")));
        cursor = firstItem(decodeItems(yaml.getString("cursor")));
        worldUid = parseUuid(yaml.getString("return.world-uid"));
        worldName = yaml.getString("return.world");
        x = yaml.getDouble("return.x");
        y = yaml.getDouble("return.y");
        z = yaml.getDouble("return.z");
        yaw = (float) yaml.getDouble("return.yaw");
        pitch = (float) yaml.getDouble("return.pitch");
        GameMode mode;
        try { mode = GameMode.valueOf(yaml.getString("game-mode", "SURVIVAL")); }
        catch (IllegalArgumentException e) { mode = GameMode.SURVIVAL; }
        gameMode = mode;
        level = yaml.getInt("level");
        exp = (float) yaml.getDouble("exp");
        totalExperience = yaml.getInt("total-experience");
        health = yaml.getDouble("health", 20.0);
        foodLevel = yaml.getInt("food-level", 20);
        saturation = (float) yaml.getDouble("saturation", 5.0);
        exhaustion = (float) yaml.getDouble("exhaustion");
        fireTicks = yaml.getInt("fire-ticks");
        air = yaml.getInt("remaining-air", 300);
        fallDistance = (float) yaml.getDouble("fall-distance");
        allowFlight = yaml.getBoolean("allow-flight");
        flying = yaml.getBoolean("flying");
        potionEffects = new ArrayList<>();
        List<?> effects = yaml.getList("effects");
        if (effects != null) {
            for (Object effect : effects) if (effect instanceof PotionEffect potion) potionEffects.add(potion);
        }
    }

    public static PlayerSnapshot capture(Player player) {
        return new PlayerSnapshot(player, null);
    }

    /**
     * @param returnLocation место, куда игрок вернётся после матча; если null — текущее место игрока
     */
    public static PlayerSnapshot capture(Player player, Location returnLocation) {
        return new PlayerSnapshot(player, returnLocation);
    }

    /** Место возврата или null, если мир сейчас не загружен. */
    public Location location() {
        World world = worldUid == null ? null : Bukkit.getWorld(worldUid);
        if (world == null && worldName != null) world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }

    public void restoreInventory(Player player) {
        player.getInventory().setStorageContents(cloneItems(storage));
        player.getInventory().setArmorContents(cloneItems(armor));
        player.getInventory().setItemInOffHand(cloneItem(offHand));
        player.setItemOnCursor(cloneItem(cursor));
    }

    /**
     * Возвращает инвентарь и состояние. Инвентарь возвращается первым: ошибка во второстепенных
     * параметрах (эффекты, здоровье) не должна оставить игрока без вещей.
     */
    public void restoreState(Player player) {
        restoreInventory(player);
        try {
            player.setGameMode(gameMode);
            player.setTotalExperience(totalExperience);
            player.setLevel(level);
            player.setExp(Math.max(0.0f, Math.min(1.0f, exp)));
            double maxHealth = player.getMaxHealth();
            player.setHealth(Math.max(0.0, Math.min(maxHealth, health)));
            player.setFoodLevel(foodLevel);
            player.setSaturation(saturation);
            player.setExhaustion(exhaustion);
            player.setFireTicks(fireTicks);
            player.setRemainingAir(air);
            player.setFallDistance(fallDistance);
            player.setAllowFlight(allowFlight);
            player.setFlying(allowFlight && flying);
            player.clearActivePotionEffects();
            for (PotionEffect effect : potionEffects) player.addPotionEffect(effect);
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[HungerGames] Не удалось полностью вернуть состояние игрока " + player.getName() + ": " + e);
        }
    }

    public void prepareForMatch(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        player.clearActivePotionEffects();
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setExhaustion(0.0f);
        player.setFireTicks(0);
        player.setRemainingAir(player.getMaximumAir());
        player.setFallDistance(0.0f);
    }

    /** Записывает снимок в YAML. Предметы сохраняются в формате Paper (байты в Base64). */
    public void writeTo(YamlConfiguration yaml) {
        yaml.set("storage", encodeItems(storage));
        yaml.set("armor", encodeItems(armor));
        yaml.set("off-hand", encodeItems(new ItemStack[]{offHand}));
        yaml.set("cursor", encodeItems(new ItemStack[]{cursor}));
        if (worldUid != null) yaml.set("return.world-uid", worldUid.toString());
        yaml.set("return.world", worldName);
        yaml.set("return.x", x);
        yaml.set("return.y", y);
        yaml.set("return.z", z);
        yaml.set("return.yaw", (double) yaw);
        yaml.set("return.pitch", (double) pitch);
        yaml.set("game-mode", gameMode.name());
        yaml.set("level", level);
        yaml.set("exp", (double) exp);
        yaml.set("total-experience", totalExperience);
        yaml.set("health", health);
        yaml.set("food-level", foodLevel);
        yaml.set("saturation", (double) saturation);
        yaml.set("exhaustion", (double) exhaustion);
        yaml.set("fire-ticks", fireTicks);
        yaml.set("remaining-air", air);
        yaml.set("fall-distance", (double) fallDistance);
        yaml.set("allow-flight", allowFlight);
        yaml.set("flying", flying);
        yaml.set("effects", new ArrayList<>(potionEffects));
    }

    public static PlayerSnapshot readFrom(YamlConfiguration yaml) {
        return new PlayerSnapshot(yaml);
    }

    private static String encodeItems(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    private static ItemStack[] decodeItems(String encoded) {
        if (encoded == null || encoded.isEmpty()) throw new IllegalArgumentException("В файле восстановления нет предметов");
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
    }

    private static ItemStack firstItem(ItemStack[] items) {
        return items.length == 0 ? null : items[0];
    }

    private static UUID parseUuid(String text) {
        try { return text == null ? null : UUID.fromString(text); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static ItemStack[] cloneItems(ItemStack[] source) {
        if (source == null) return new ItemStack[0];
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = cloneItem(source[i]);
        return result;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
