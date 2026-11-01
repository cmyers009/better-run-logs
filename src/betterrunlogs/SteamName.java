package betterrunlogs;

import com.codedisaster.steamworks.SteamFriends;
import com.megacrit.cardcrawl.integrations.steam.SteamIntegration;

/** The Steam persona name; CardCrawlGame.playerName is the save-slot profile name, not this. */
final class SteamName {
    private SteamName() {}

    /** Null when the game was not started through Steam or the name is blank. */
    static String get() {
        Object friends = Snap.privateField(null, SteamIntegration.class, "steamFriends");
        if (!(friends instanceof SteamFriends)) return null;
        try {
            String name = ((SteamFriends) friends).getPersonaName();
            return name == null || name.trim().isEmpty() ? null : name;
        } catch (RuntimeException | LinkageError e) { // LOUD-OK: no Steam name means no upload, said to the player
            System.err.println("[BetterRunLogs] Steam name unavailable: " + e);
            return null;
        }
    }
}
