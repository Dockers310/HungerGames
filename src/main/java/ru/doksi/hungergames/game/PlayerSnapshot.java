package ru.doksi.hungergames.game;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

public final class PlayerSnapshot {
    private final ItemStack[] storage;
    private final ItemStack[] armor;
    private final ItemStack offHand;
    private final ItemStack cursor;
    private final Location location;
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

    private PlayerSnapshot(Player player) {
        storage = cloneItems(player.getInventory().getStorageContents());
        armor = cloneItems(player.getInventory().getArmorContents());
        offHand = cloneItem(player.getInventory().getItemInOffHand());
        cursor = cloneItem(player.getItemOnCursor());
        location = player.getLocation().clone();
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

    public static PlayerSnapshot capture(Player player) {
        return new PlayerSnapshot(player);
    }

    public Location location() { return location.clone(); }

    public void restoreInventory(Player player) {
        player.getInventory().setStorageContents(cloneItems(storage));
        player.getInventory().setArmorContents(cloneItems(armor));
        player.getInventory().setItemInOffHand(cloneItem(offHand));
        player.setItemOnCursor(cloneItem(cursor));
    }

    public void restoreState(Player player) {
        restoreInventory(player);
        player.setGameMode(gameMode);
        player.setTotalExperience(totalExperience);
        player.setLevel(level);
        player.setExp(exp);
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
