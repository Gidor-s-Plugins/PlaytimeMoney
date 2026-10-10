package gidor.playtimebonus;
import org.bukkit.ChatColor;

final class KeyColor {
    private KeyColor() {}

    static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
