package gidor.playtimebonus;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class EconomyHandler {

    private Economy economy;

    public boolean isAvailable() {
        if (economy != null) return true;
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return true;
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (!isAvailable()) return false;
        EconomyResponse r = economy.depositPlayer(player, amount);
        return r != null && r.transactionSuccess();
    }

    public String format(double amount) {
        return isAvailable() ? economy.format(amount) : String.format("%.2f", amount);
    }
}
