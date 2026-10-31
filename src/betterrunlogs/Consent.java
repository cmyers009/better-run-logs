package betterrunlogs;

import basemod.ModToggleButton;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireConfig;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import java.io.IOException;
import java.util.UUID;

/**
 * The opt-in: config keys upload_enabled ("yes"/"no"; absent = never asked) and install_id.
 * Nothing starts the uploader except a stored or freshly given "yes".
 */
public final class Consent {
    private Consent() {}

    static final String KEY_ENABLED = "upload_enabled";
    static final String KEY_INSTALL = "install_id";

    static final String TITLE = "Share your runs with spirepilot.com?";
    static final String YES = "Yes, share my runs";
    static final String NO = "No thanks";

    private static SpireConfig config;
    private static String installId;
    /** Closed without an answer: ask again next launch. */
    private static boolean dismissed;
    static ModToggleButton toggle;

    static void init() {
        try {
            config = new SpireConfig("better-run-logs", "config");
            String id = config.has(KEY_INSTALL) ? config.getString(KEY_INSTALL) : null;
            if (!isUuid(id)) {
                id = UUID.randomUUID().toString();
                config.setString(KEY_INSTALL, id);
                config.save();
            }
            installId = id;
        } catch (IOException | RuntimeException e) { // LOUD-OK: no config means no prompt and no uploads
            System.err.println("[BetterRunLogs] could not load or save config; uploads stay off: " + e);
            config = null;
            installId = null;
            return;
        }
        if (enabled()) Uploader.start(installId);
    }

    private static boolean isUuid(String s) {
        if (s == null) return false;
        try {
            return UUID.fromString(s).toString().equals(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean ready() {
        return config != null && installId != null;
    }

    static boolean asked() {
        return config != null && config.has(KEY_ENABLED);
    }

    static boolean enabled() {
        return config != null && "yes".equals(config.getString(KEY_ENABLED));
    }

    static String installId() {
        return installId;
    }

    /** The player's answer, from the prompt or the Config toggle. False when there is no config to store it. */
    static boolean setEnabled(boolean on) {
        if (!ready()) return false;
        config.setString(KEY_ENABLED, on ? "yes" : "no");
        try {
            config.save();
        } catch (IOException e) { // LOUD-OK: the answer holds for this session; next launch asks again
            System.err.println("[BetterRunLogs] could not save upload choice: " + e);
        }
        if (toggle != null) toggle.enabled = on;
        if (on) Uploader.start(installId);
        else Uploader.stop();
        return true;
    }

    // ---- the main-menu prompt ----

    private static final Dialog PROMPT = new Dialog(TITLE,
            UploadPlan.consentBody(!Uploader.scanLogs().isEmpty()), YES, NO, 1100f, 620f);

    static boolean showing(MainMenuScreen m) {
        return ready() && !asked() && !dismissed && m.screen == MainMenuScreen.CurScreen.MAIN_MENU && !m.isFadingOut;
    }

    static void update() {
        switch (PROMPT.update()) {
            case YES: setEnabled(true); break;
            case NO: setEnabled(false); break;
            case CANCEL: dismissed = true; break;
            default: break;
        }
    }

    private static void fail(String where, Throwable t) {
        dismissed = true;
        System.err.println("[BetterRunLogs] consent prompt " + where + " failed; asking again next launch: " + t);
    }

    @SpirePatch(clz = MainMenuScreen.class, method = "update")
    public static class MenuUpdate {
        @SpirePrefixPatch
        public static void Prefix(MainMenuScreen __instance) {
            try {
                if (!showing(__instance)) return;
                hiding = true;
                update();
            } catch (Throwable t) { // LOUD-OK: a broken prompt closes; the menu keeps working
                fail("update", t);
            }
        }
    }

    /** Set while the prompt hides the menu's input this frame; the postfix gives the pointer back. */
    private static boolean hiding;

    @SpirePatch(clz = MainMenuScreen.class, method = "update")
    public static class MenuUpdateDone {
        @SpirePostfixPatch
        public static void Postfix(MainMenuScreen __instance) {
            if (!hiding) return;
            hiding = false;
            PROMPT.restorePointer();
        }
    }

    @SpirePatch(clz = MainMenuScreen.class, method = "render")
    public static class MenuRender {
        @SpirePostfixPatch
        public static void Postfix(MainMenuScreen __instance, SpriteBatch sb) {
            try {
                if (showing(__instance)) PROMPT.render(sb);
            } catch (Throwable t) { // LOUD-OK: a broken prompt closes; the menu keeps working
                sb.setColor(Color.WHITE);
                fail("render", t);
            }
        }
    }
}
