package ru.doksi.hungergames.util;

import org.bukkit.entity.Player;
import ru.doksi.hungergames.HungerGamesPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class InputManager {
    private final HungerGamesPlugin plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public InputManager(HungerGamesPlugin plugin) { this.plugin = plugin; }

    public void request(Player player, Consumer<String> consumer) {
        pending.put(player.getUniqueId(), consumer);
        plugin.send(player, "input-start");
    }

    public boolean handle(Player player, String message) {
        Consumer<String> consumer = pending.remove(player.getUniqueId());
        if (consumer == null) return false;
        if (message.equalsIgnoreCase("отмена") || message.equalsIgnoreCase("cancel")) {
            plugin.send(player, "input-cancelled");
            return true;
        }
        player.getScheduler().run(plugin, ignored -> consumer.accept(message), null);
        return true;
    }

    public void cancel(Player player) { pending.remove(player.getUniqueId()); }
}
