package ru.doksi.hungergames.chest;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class ChestTemplate {
    private final String id;
    private String displayName;
    private org.bukkit.Material icon;
    private int minItems;
    private int maxItems;
    private double itemChance;
    private final List<ItemStack> items = new ArrayList<>();

    public ChestTemplate(String id, String displayName, org.bukkit.Material icon) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.minItems = 2;
        this.maxItems = 5;
        this.itemChance = 0.85;
    }
    public String id() { return id; }
    public String displayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public org.bukkit.Material icon() { return icon; }
    public void setIcon(org.bukkit.Material value) { icon = value; }
    public int minItems() { return minItems; }
    public void setMinItems(int value) { minItems = Math.max(0, value); if (maxItems < minItems) maxItems = minItems; }
    public int maxItems() { return maxItems; }
    public void setMaxItems(int value) { maxItems = Math.max(minItems, value); }
    public double itemChance() { return itemChance; }
    public void setItemChance(double value) { itemChance = Math.max(0.0, Math.min(1.0, value)); }
    public List<ItemStack> items() { return items; }

    public List<ItemStack> generateLoot() {
        List<ItemStack> source = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack != null && !stack.getType().isAir()) source.add(stack);
        }
        List<ItemStack> result = new ArrayList<>();
        if (source.isEmpty()) return result;
        int count = ThreadLocalRandom.current().nextInt(maxItems - minItems + 1) + minItems;
        for (int i = 0; i < count; i++) {
            if (ThreadLocalRandom.current().nextDouble() > itemChance) continue;
            ItemStack picked = source.get(ThreadLocalRandom.current().nextInt(source.size())).clone();
            int maxStack = Math.max(1, Math.min(picked.getMaxStackSize(), picked.getAmount()));
            picked.setAmount(ThreadLocalRandom.current().nextInt(1, maxStack + 1));
            result.add(picked);
        }
        return result;
    }
}
