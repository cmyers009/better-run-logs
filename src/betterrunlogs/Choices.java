package betterrunlogs;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireRawPatch;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;
import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.NotFoundException;

/** Shop purchases and removals, reward claims and campfire choices, each as its own line. */
public final class Choices {
    private Choices() {}

    private static int goldBefore;
    private static AbstractCampfireOption lastCampfire;

    static void endFrame() {
        lastCampfire = null;
    }

    private static int gold() {
        return AbstractDungeon.player.gold;
    }

    /** A purchase attempt only counts when gold was actually spent (not enough gold, full belt, etc. spend none). */
    private static void bought(String kind, JsonObject item) {
        int paid = goldBefore - gold();
        if (paid <= 0) return;
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.add("item", item);
        o.addProperty("price", paid);
        o.addProperty("gold", gold());
        o.add("shop_after", BetterRunLogs.shop(AbstractDungeon.shopScreen));
        RunLog.get().emit("shop_buy", o);
    }

    @SpirePatch(clz = ShopScreen.class, method = "purchaseCard")
    public static class BuyCard {
        private static JsonObject card;

        /** purchaseCard nulls its argument before returning, so the card is captured here. */
        @SpirePrefixPatch
        public static void Prefix(ShopScreen __instance, AbstractCard hoveredCard) {
            goldBefore = gold();
            card = hoveredCard == null ? null : Snap.card(hoveredCard);
            if (card != null) card.addProperty("listed_price", hoveredCard.price);
        }

        @SpirePostfixPatch
        public static void Postfix(ShopScreen __instance) {
            RunLog.guard("shopBuyCard", () -> {
                if (card != null) bought("card", card);
                card = null;
            });
        }
    }

    private static JsonObject pending;

    private static JsonObject item(String id, int price) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("listed_price", price);
        return o;
    }

    /** The Courier restocks the same StoreRelic/StorePotion slot during the purchase, so capture first. */
    @SpirePatch(clz = StoreRelic.class, method = "purchaseRelic")
    public static class BuyRelic {
        @SpirePrefixPatch
        public static void Prefix(StoreRelic __instance) {
            goldBefore = gold();
            pending = item(__instance.relic.relicId, __instance.price);
        }

        @SpirePostfixPatch
        public static void Postfix(StoreRelic __instance) {
            RunLog.guard("shopBuyRelic", () -> bought("relic", pending));
        }
    }

    @SpirePatch(clz = StorePotion.class, method = "purchasePotion")
    public static class BuyPotion {
        @SpirePrefixPatch
        public static void Prefix(StorePotion __instance) {
            goldBefore = gold();
            pending = item(__instance.potion.ID, __instance.price);
        }

        @SpirePostfixPatch
        public static void Postfix(StorePotion __instance) {
            RunLog.guard("shopBuyPotion", () -> bought("potion", pending));
        }
    }

    /** purgeCard() charges the removal; the card itself is the grid selection being confirmed. */
    @SpirePatch(clz = ShopScreen.class, method = "purgeCard")
    public static class Purge {
        @SpirePrefixPatch
        public static void Prefix() {
            RunLog.guard("shopPurge", () -> {
                JsonObject o = new JsonObject();
                JsonArray cards = new JsonArray();
                for (AbstractCard c : AbstractDungeon.gridSelectScreen.selectedCards) cards.add(Snap.card(c));
                o.add("cards", cards);
                o.addProperty("price", ShopScreen.actualPurgeCost);
                o.addProperty("gold_before", gold());
                o.add("shop", BetterRunLogs.shop(AbstractDungeon.shopScreen));
                RunLog.get().emit("shop_purge", o);
            });
        }
    }

    @SpirePatch(clz = RewardItem.class, method = "claimReward")
    public static class ClaimReward {
        @SpirePrefixPatch
        public static void Prefix(RewardItem __instance) {
            goldBefore = gold();
        }

        /** false means not taken (full potion belt) or, for a card reward, the card screen opened. */
        @SpirePostfixPatch
        public static boolean Postfix(boolean claimed, RewardItem __instance) {
            RunLog.guard("rewardClaim", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("type", String.valueOf(__instance.type));
                o.addProperty("claimed", claimed);
                if (__instance.relic != null) o.addProperty("relic", __instance.relic.relicId);
                if (__instance.potion != null) o.addProperty("potion", __instance.potion.ID);
                if (__instance.relicLink != null) o.addProperty("linked", true);
                if (gold() != goldBefore) o.addProperty("gold_gained", gold() - goldBefore);
                o.addProperty("reward_index", AbstractDungeon.combatRewardScreen.rewards.indexOf(__instance));
                RunLog.get().emit("reward_claim", o);
            });
            return claimed;
        }
    }

    public static void campfire(AbstractCampfireOption option) {
        if (option == lastCampfire) return;
        lastCampfire = option;
        RunLog.guard("campfire", () -> {
            JsonObject o = new JsonObject();
            o.addProperty("option", option.getClass().getName());
            Object label = Snap.privateField(option, AbstractCampfireOption.class, "label");
            if (label != null) o.addProperty("label", label.toString());
            o.addProperty("usable", option.usable);
            o.addProperty("hp", AbstractDungeon.player.currentHealth);
            RunLog.get().emit("campfire_choice", o);
        });
    }

    @SpirePatch(clz = AbstractCampfireOption.class, method = "update")
    public static class EveryCampfireOption {
        @SpireRawPatch
        public static void Raw(CtBehavior host) throws NotFoundException, CannotCompileException {
            SubclassHooks.insertBefore(host.getDeclaringClass().getClassPool(), AbstractCampfireOption.class,
                    "useOption", new Class<?>[0], "betterrunlogs.Choices.campfire(this);");
        }
    }
}
