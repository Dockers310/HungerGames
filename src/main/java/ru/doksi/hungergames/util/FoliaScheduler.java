package ru.doksi.hungergames.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Общие планировщики Paper/Folia. Эти API работают и на обычном Paper,
 * а на Folia выполняют задачу в правильном регионе или на правильном EntityScheduler.
 *
 * Выключенный плагин не может ставить задачи (сервер отвечает IllegalPluginAccessException),
 * поэтому во время onDisable все методы ничего не делают и возвращают null.
 */
public final class FoliaScheduler {
    private final Plugin plugin;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Задача без привязки к миру/сущности. */
    public ScheduledTask global(Runnable task) {
        if (!plugin.isEnabled()) return null;
        return plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }

    public ScheduledTask globalLater(long delayTicks, Runnable task) {
        if (!plugin.isEnabled()) return null;
        return plugin.getServer().getGlobalRegionScheduler().runDelayed(
                plugin, ignored -> task.run(), Math.max(1L, delayTicks));
    }

    public ScheduledTask globalRepeating(long initialDelayTicks, long periodTicks, Runnable task) {
        if (!plugin.isEnabled()) return null;
        return plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin, ignored -> task.run(), Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
    }

    /** Действие над конкретным игроком/сущностью. */
    public ScheduledTask entity(Player player, Runnable task) {
        return entity(player, task, null);
    }

    /**
     * @param retired выполняется, если игрок покинул сервер раньше, чем задача успела запуститься
     */
    public ScheduledTask entity(Player player, Runnable task, Runnable retired) {
        if (player == null || !plugin.isEnabled()) {
            if (retired != null) retired.run();
            return null;
        }
        ScheduledTask scheduled = player.getScheduler().run(plugin, ignored -> task.run(), retired);
        if (scheduled == null && retired != null) retired.run();
        return scheduled;
    }

    public ScheduledTask entityLater(Player player, long delayTicks, Runnable task) {
        if (player == null || !plugin.isEnabled()) return null;
        return player.getScheduler().runDelayed(plugin, ignored -> task.run(), null, Math.max(1L, delayTicks));
    }

    /** Операции с блоками и другими объектами региона. */
    public ScheduledTask region(Location location, Runnable task) {
        if (location == null || location.getWorld() == null || !plugin.isEnabled()) return null;
        return plugin.getServer().getRegionScheduler().run(plugin, location, ignored -> task.run());
    }

    public ScheduledTask regionLater(Location location, long delayTicks, Runnable task) {
        if (location == null || location.getWorld() == null || !plugin.isEnabled()) return null;
        return plugin.getServer().getRegionScheduler().runDelayed(
                plugin, location, ignored -> task.run(), Math.max(1L, delayTicks));
    }
}
