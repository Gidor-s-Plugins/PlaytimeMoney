package gidor.playtimebonus;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class BonusCommand implements CommandExecutor, TabCompleter {

    private final PlaytimeMoney plugin;
    private final BonusManager manager;
    private final EconomyHandler economy;

    public BonusCommand(PlaytimeMoney plugin, BonusManager manager, EconomyHandler economy) {
        this.plugin = plugin;
        this.manager = manager;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("playtimemoney.admin")) {
                sender.sendMessage(plugin.msg("no-permission"));
                return true;
            }
            plugin.reloadConfig();
            manager.reloadSettings();
            sender.sendMessage(plugin.msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.msg("players-only"));
            return true;
        }
        if (!player.hasPermission("playtimemoney.use")) {
            player.sendMessage(plugin.msg("no-permission"));
            return true;
        }

        PlayerData data = manager.get(player.getUniqueId());
        if (data == null) {
            player.sendMessage(plugin.msg("not-loaded"));
            return true;
        }

        long interval = Math.max(1, plugin.getConfig().getLong("hourly.interval-minutes", 60));
        long remaining = interval - (data.playtimeMinutes % interval);
        double hourlyAmount = plugin.getConfig().getDouble("hourly.amount", 500.0);

        player.sendMessage(KeyColor.c(plugin.getConfig().getString("messages.info-header", "")));
        player.sendMessage(KeyColor.c(plugin.getConfig().getString("messages.info-playtime", ""))
                .replace("%playtime%", BonusManager.formatPlaytime(data.playtimeMinutes)));
        player.sendMessage(KeyColor.c(plugin.getConfig().getString("messages.info-next-hourly", ""))
                .replace("%minutes%", String.valueOf(remaining))
                .replace("%amount%", economy.format(hourlyAmount)));
        player.sendMessage(KeyColor.c(plugin.getConfig().getString(
                manager.dailyClaimedToday(data) ? "messages.info-daily-claimed" : "messages.info-daily-pending", "")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("playtimemoney.admin")) return List.of("reload");
        return List.of();
    }
}
