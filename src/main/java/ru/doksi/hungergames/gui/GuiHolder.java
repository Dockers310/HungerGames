package ru.doksi.hungergames.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class GuiHolder implements InventoryHolder {
    private Inventory inventory;
    private boolean saveOnClose = true;
    public final String screen;
    public final String arena;
    public final String arg;
    public GuiHolder(String screen, String arena, String arg) { this.screen = screen; this.arena = arena; this.arg = arg; }
    public void bind(Inventory inventory) { this.inventory = inventory; }
    public void disableSaveOnClose() { this.saveOnClose = false; }
    public boolean saveOnClose() { return saveOnClose; }
    @Override public Inventory getInventory() { return inventory; }
}
