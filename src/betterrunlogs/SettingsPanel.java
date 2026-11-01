package betterrunlogs;

import basemod.IUIElement;
import basemod.ModLabel;
import basemod.ModLabeledButton;
import basemod.ModLabeledToggleButton;
import basemod.ModPanel;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;

/** Mods -> Better Run Logs -> Config: the sharing toggle, the stats link, and removing your runs from the site. */
final class SettingsPanel {
    private SettingsPanel() {}

    static final String STATS_URL = "https://spirepilot.com/player.html?name=";
    private static final long STOP_WAIT_MS = 40_000L;

    private static final Dialog CONFIRM_REMOVE = new Dialog("Remove your runs from spirepilot.com?",
            "This hides every run you shared from the site and turns sharing off.", "Yes, remove them", "Cancel",
            1000f, 480f);
    private static ModLabel status;
    private static boolean confirming;
    private static volatile boolean removing;

    static ModPanel build() {
        ModPanel panel = new ModPanel();
        ModLabeledToggleButton share = new ModLabeledToggleButton("Share runs with spirepilot.com", 380f, 700f,
                Settings.CREAM_COLOR, FontHelper.charDescFont, Consent.enabled(), panel, l -> {}, b -> toggled(b.enabled));
        Consent.toggle = share.toggle;
        panel.addUIElement(share);
        panel.addUIElement(new ModLabel("Your logs are saved on your PC in better-run-logs/.",
                380f, 640f, Settings.CREAM_COLOR, FontHelper.tipBodyFont, panel, l -> {}));
        panel.addUIElement(new ModLabeledButton("View my stats", 380f, 520f, panel, b -> viewStats()));
        panel.addUIElement(new ModLabeledButton("Remove my runs from the site", 380f, 400f, panel, b -> confirming = true));
        status = new ModLabel("", 380f, 330f, Settings.CREAM_COLOR, FontHelper.tipBodyFont, panel, l -> {});
        panel.addUIElement(status);
        panel.addUIElement(new ConfirmRemove());
        return panel;
    }

    /** Updates before every other element and hides their input while open; draws over the panel. */
    private static final class ConfirmRemove implements IUIElement {
        @Override
        public void update() {
            if (!confirming) return;
            Dialog.Choice c = CONFIRM_REMOVE.update();
            if (c == Dialog.Choice.NONE) return;
            confirming = false;
            if (c == Dialog.Choice.YES) removeUploads();
        }

        @Override
        public void render(SpriteBatch sb) {
            if (!confirming) return;
            CONFIRM_REMOVE.restorePointer();
            CONFIRM_REMOVE.render(sb);
        }

        @Override
        public int renderLayer() {
            return ModPanel.TEXT_LAYER + 1;
        }

        @Override
        public int updateOrder() {
            return ModPanel.PRIORITY_UPDATE;
        }
    }

    private static void say(String text) {
        if (status != null) status.text = text;
    }

    private static void toggled(boolean on) {
        if (!Consent.setEnabled(on)) {
            Consent.toggle.enabled = false;
            say("Settings could not be loaded (see the log); uploads stay off.");
            return;
        }
        say(on ? "Sharing on: your runs upload in the background." : "Sharing off: nothing more is uploaded.");
    }

    private static void viewStats() {
        String name = SteamName.get();
        if (name == null) {
            say("No Steam name found; start the game through Steam.");
            return;
        }
        try {
            Gdx.net.openURI(STATS_URL + URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
        } catch (UnsupportedEncodingException | RuntimeException e) { // LOUD-OK: shown to the player; the game continues
            say("Could not open the browser: " + e.getMessage());
        }
    }

    /** Stops sharing first so nothing uploads after the removal, then asks the site off the game thread. */
    private static void removeUploads() {
        if (removing) return;
        if (!Consent.ready()) {
            say("Settings could not be loaded (see the log).");
            return;
        }
        if (!Consent.asked()) {
            say("Nothing to remove: you have never shared runs.");
            return;
        }
        Thread running = Uploader.stop();
        Consent.setEnabled(false);
        String id = Consent.installId();
        removing = true;
        say("Removing your runs from spirepilot.com...");
        Thread t = new Thread(() -> {
            try {
                if (running != null) running.join(STOP_WAIT_MS);
                say(remove(id));
            } catch (Throwable e) { // LOUD-OK: shown to the player; the game continues
                say("Removal failed: " + e + ". Try again.");
            } finally {
                removing = false;
            }
        }, "BetterRunLogs-remove");
        t.setDaemon(true);
        t.start();
    }

    private static String remove(String installId) throws IOException {
        HttpURLConnection c = Uploader.open("DELETE", Uploader.API + "/installs/" + installId, installId);
        try {
            int status = c.getResponseCode();
            String reply = Uploader.body(c, status);
            if (status != 200) return "Removal failed (HTTP " + status + "). Try again later.";
            JsonElement n = new JsonParser().parse(reply).getAsJsonObject().get("hidden");
            if (n == null) return "Removal gave an unexpected reply; try again later.";
            return "Removed " + n.getAsInt() + " runs from spirepilot.com. Sharing is off.";
        } finally {
            c.disconnect();
        }
    }
}
