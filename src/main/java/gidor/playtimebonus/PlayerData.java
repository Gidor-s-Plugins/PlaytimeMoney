package gidor.playtimebonus;

import java.util.UUID;

/** In-memory player state. Only ever touched from the main thread. */
public final class PlayerData {

    public record Snapshot(UUID uuid, String name, long playtimeMinutes, long lastDailyDay) {}

    public static final long NEVER = Long.MIN_VALUE;

    public final UUID uuid;
    public String name;
    public long playtimeMinutes;
    public long lastDailyDay;
    public boolean dirty;

    public PlayerData(UUID uuid, String name, long playtimeMinutes, long lastDailyDay) {
        this.uuid = uuid;
        this.name = name;
        this.playtimeMinutes = playtimeMinutes;
        this.lastDailyDay = lastDailyDay;
    }

    public Snapshot snapshot() {
        return new Snapshot(uuid, name, playtimeMinutes, lastDailyDay);
    }
}
