package betterrunlogs;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.DamageInfo;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.ui.panels.PotionPopUp;
import com.megacrit.cardcrawl.ui.panels.TopPanel;
import com.evacipated.cardcrawl.modthespire.lib.SpireRawPatch;
import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/**
 * Every concrete potion's use(target) is patched at load, so a use is logged whoever calls it (UI,
 * Fairy, other mods) with the target actually passed. A slot emptied after a use is not a discard.
 */
public final class PotionLog {
    private PotionLog() {}

    private static int consumedSlot = -1;
    private static AbstractPotion lastUsed;
    private static String how;

    static void endFrame() {
        consumedSlot = -1;
        lastUsed = null;
    }

    /** Called from the injected prologue of every potion subclass's use(). */
    public static void used(AbstractPotion p, AbstractCreature target) {
        if (p == lastUsed) return;
        lastUsed = p;
        String label = how == null ? "direct" : how;
        RunLog.guard("potionUse", () -> usedUnguarded(p, target, label));
    }

    public static void setHow(String label) {
        how = label;
    }

    private static void usedUnguarded(AbstractPotion p, AbstractCreature target, String how) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.ID);
        o.addProperty("slot", p.slot);
        o.addProperty("how", how);
        o.addProperty("potency", p.getPotency());
        if (p.targetRequired || target != null) o.addProperty("target", Patches.monsterIndex(target));
        if (target != null && target != AbstractDungeon.player) o.addProperty("targetId", target.id);
        o.addProperty("inCombat", Snap.inCombat());
        if (Snap.inCombat()) o.add("hp", Snap.hpVector());
        o.add("belt", Snap.potions());
        boolean inBelt = p.slot >= 0 && p.slot < AbstractDungeon.player.potions.size()
                && AbstractDungeon.player.potions.get(p.slot) == p;
        if (!inBelt) o.addProperty("notInBelt", true);
        RunLog.get().emit("potion_use", o);
        if (inBelt) consumedSlot = p.slot;
    }

    @SpirePatch(clz = AbstractPotion.class, method = "getPotency", paramtypez = {})
    public static class EveryPotionUse {
        @SpireRawPatch
        public static void Raw(CtBehavior host) throws NotFoundException, CannotCompileException {
            SubclassHooks.insertBefore(host.getDeclaringClass().getClassPool(), AbstractPotion.class, "use",
                    new Class<?>[] {AbstractCreature.class}, "betterrunlogs.PotionLog.used(this, $1);");
        }
    }

    private static ExprEditor hookUse(String how) {
        return new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                if (m.getClassName().equals(AbstractPotion.class.getName()) && m.getMethodName().equals("use")) {
                    m.replace("{ betterrunlogs.PotionLog.setHow(\"" + how + "\"); try { $_ = $proceed($$); }"
                            + " finally { betterrunlogs.PotionLog.setHow(null); } }");
                }
            }
        };
    }

    @SpirePatch(clz = PotionPopUp.class, method = "updateTargetMode")
    public static class TargetedUse {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return hookUse("targeted");
        }
    }

    @SpirePatch(clz = PotionPopUp.class, method = "updateInput")
    public static class UntargetedUse {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return hookUse("drink");
        }
    }

    @SpirePatch(clz = AbstractPlayer.class, method = "damage", paramtypez = {DamageInfo.class})
    public static class AutoUseOnDeath {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return hookUse("auto_on_death");
        }
    }

    @SpirePatch(clz = TopPanel.class, method = "destroyPotion")
    public static class SlotEmptied {
        @SpirePrefixPatch
        public static void Prefix(TopPanel __instance, int slot) {
            RunLog.guard("potionDiscard", () -> {
                AbstractPotion p = AbstractDungeon.player.potions.get(slot);
                if (slot == consumedSlot || p instanceof PotionSlot) return;
                JsonObject o = new JsonObject();
                o.addProperty("slot", slot);
                o.addProperty("id", p.ID);
                o.addProperty("inCombat", Snap.inCombat());
                RunLog.get().emit("potion_discard", o);
            });
        }
    }

    @SpirePatch(clz = AbstractPlayer.class, method = "removePotion")
    public static class Removed {
        @SpirePrefixPatch
        public static void Prefix(AbstractPlayer __instance, AbstractPotion p) {
            RunLog.guard("potionRemoved", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("slot", p.slot);
                o.addProperty("id", p.ID);
                AbstractRoom room = Snap.room();
                if (room != null && room.event != null) o.addProperty("event", room.event.getClass().getSimpleName());
                RunLog.get().emit("potion_removed", o);
            });
        }
    }
}
