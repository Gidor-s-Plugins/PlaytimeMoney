package gidor.playtimebonus;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class PlaytimeMoney extends JavaPlugin {

    private Database database;
    private BonusManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        database = new Database(getLogger());
        try {
            database.init(new File(getDataFolder(), "playbonus.db"));
        } catch (Exception e) {
            getLogger().severe("Could not open the SQLite database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        EconomyHandler economy = new EconomyHandler();
        manager = new BonusManager(this, database, economy);

        getServer().getPluginManager().registerEvents(new PlayerListener(manager), this);

        BonusCommand command = new BonusCommand(this, manager, economy);
        PluginCommand pc = getCommand("pmoney");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        manager.start();
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
        if (database != null) database.close();
    }

    public String msg(String path, String... replacements) {
        String raw = getConfig().getString("messages." + path, "&cMissing message: " + path);
        String prefix = getConfig().getString("messages.prefix", "");
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        return KeyColor.c(prefix + raw);
    }
}
