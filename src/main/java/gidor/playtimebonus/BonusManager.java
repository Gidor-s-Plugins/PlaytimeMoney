package gidor.playtimebonus;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core logic. Performance notes:
 *  - the cache and all counters live on the main thread, so a minute tick is just a few
 *    integer increments per online player (no I/O, no locks);
 *  - the database is only touched asynchronously: load on join, batch-save of CHANGED players
 *    every few minutes, and a save on quit.
 */
public final class BonusManager {

    private final PlaytimeMoney plugin;
    private final Database db;
    private final EconomyHandler economy;

    private final Map<UUID, PlayerData> cache = new HashMap<>();
    private BukkitTask minuteTask;
    private BukkitTask saveTask;
    private ZoneId zone = ZoneId.systemDefault();
    private boolean warnedNoEconomy;

    public BonusManager(PlaytimeMoney plugin, Database db, EconomyHandler economy) {
        this.plugin = plugin;
        this.db = db;
        this.economy = economy;
    }

    // ── lifecycle ────────────────────────────────────────────────────

    public void start() {
        reloadSettings();
        minuteTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMinute, 1200L, 1200L);

        long saveTicks = Math.max(1, plugin.getConfig().getLong("autosave-minutes", 5)) * 1200L;
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, saveTicks, saveTicks);

        // Handles /reload: players already online.
        for (Player p : Bukkit.getOnlinePlayers()) handleJoin(p);
    }

    public void shutdown() {
        if (minuteTask != null) minuteTask.cancel();
        if (saveTask != null) saveTask.cancel();
        saveDirty();
        cache.clear();
    }

    public void reloadSettings() {
        String tz = plugin.getConfig().getString("daily.timezone", "Europe/Budapest");
        try {
            zone = ZoneId.of(tz);
        } catch (DateTimeException e) {
            plugin.getLogger().warning("Invalid timezone '" + tz + "', using system default.");
            zone = ZoneId.systemDefault();
        }
    }

    // ── join / quit ──────────────────────────────────────────────────

    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();

        db.load(id, name).whenComplete((data, error) -> {
            if (error != null) {
                plugin.getLogger().severe("Could not load data for " + name + ": " + error.getMessage());
                return;
            }
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(id);
                if (online == null) return; // left while loading
                data.name = online.getName();
                cache.put(id, data);
                checkDaily(online, data);
            });
        });
    }

    public void handleQuit(Player player) {
        PlayerData data = cache.remove(player.getUniqueId());
        if (data != null && data.dirty) {
            data.dirty = false;
            db.save(List.of(data.snapshot()));
        }
    }

    // ── rewards ──────────────────────────────────────────────────────

    private void checkDaily(Player player, PlayerData data) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("daily.enabled", true)) return;

        long today = LocalDate.now(zone).toEpochDay();
        if (data.lastDailyDay == today) return;

        double amount = cfg.getDouble("daily.amount", 1500.0);
        if (!pay(player, amount)) return;

        data.lastDailyDay = today;
        data.dirty = false;
        // Saved immediately so a crash can't hand out the daily bonus twice.
        db.save(List.of(data.snapshot()));

        player.sendMessage(plugin.msg("daily", "%amount%", economy.format(amount)));
        playSound(player);
    }

    private void tickMinute() {
        FileConfiguration cfg = plugin.getConfig();
        boolean hourly = cfg.getBoolean("hourly.enabled", true);
        long interval = Math.max(1, cfg.getLong("hourly.interval-minutes", 60));
        double amount = cfg.getDouble("hourly.amount", 500.0);

        for (PlayerData data : cache.values()) {
            data.playtimeMinutes++;
            data.dirty = true;

            if (hourly && data.playtimeMinutes % interval == 0) {
                Player player = Bukkit.getPlayer(data.uuid);
                if (player != null && pay(player, amount)) {
                    player.sendMessage(plugin.msg("hourly",
                            "%amount%", economy.format(amount),
                            "%playtime%", formatPlaytime(data.playtimeMinutes)));
                    playSound(player);
                }
            }
        }
    }

    /** One batched, asynchronous write containing only players that changed. */
    private void saveDirty() {
        List<PlayerData.Snapshot> batch = new ArrayList<>();
        for (PlayerData data : cache.values()) {
            if (data.dirty) {
                batch.add(data.snapshot());
                data.dirty = false;
            }
        }
        db.save(batch);
    }

    private boolean pay(Player player, double amount) {
        if (economy.deposit(player, amount)) return true;
        if (!warnedNoEconomy) {
            warnedNoEconomy = true;
            plugin.getLogger().warning("Economy deposit failed (no provider?). Bonuses are skipped.");
        }
        player.sendMessage(plugin.msg("economy-unavailable"));
        return false;
    }

    private void playSound(Player player) {
        String name = plugin.getConfig().getString("sounds.reward", "");
        if (name == null || name.isBlank()) return;
        try {
            player.playSound(player.getLocation(), Sound.valueOf(name.toUpperCase()),
                    (float) plugin.getConfig().getDouble("sounds.volume", 1.0),
                    (float) plugin.getConfig().getDouble("sounds.pitch", 1.0));
        } catch (IllegalArgumentException ignored) {
            // invalid sound name in config gracefully ignore it because why the hell not
        }
    }

    // ── info ─────────────────────────────────────────────────────────

    public PlayerData get(UUID uuid) {
        return cache.get(uuid);
    }

    public boolean dailyClaimedToday(PlayerData data) {
        return data.lastDailyDay == LocalDate.now(zone).toEpochDay();
    }

    public static String formatPlaytime(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        return h + " óra " + m + " perc";
    }
}
