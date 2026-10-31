package betterrunlogs;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.controller.CInputActionSet;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

/**
 * A modal two-button box. While it is up, everything updated after {@link #update()} sees no pointer and no
 * clicks, so the screen under it cannot react; {@link #restorePointer()} gives the cursor back before render.
 */
final class Dialog {
    enum Choice { NONE, YES, NO, CANCEL }

    private static final Color SHADE = new Color(0f, 0f, 0f, 0.75f);
    private static final Color PANEL = new Color(0.08f, 0.1f, 0.12f, 0.97f);
    private static final Color EDGE = new Color(0.85f, 0.7f, 0.35f, 1f);
    private static final Color BUTTON = new Color(0.2f, 0.24f, 0.28f, 1f);
    private static final Color BUTTON_HOVER = new Color(0.32f, 0.38f, 0.44f, 1f);
    private static final int OFF_SCREEN = -100_000;

    private final String title;
    private final String body;
    private final String yes;
    private final String no;
    private final float width;
    private final float height;
    private final Hitbox yesHb = new Hitbox(1, 1);
    private final Hitbox noHb = new Hitbox(1, 1);
    private int realX;
    private int realY;

    Dialog(String title, String body, String yes, String no, float width, float height) {
        this.title = title;
        this.body = body;
        this.yes = yes;
        this.no = no;
        this.width = width;
        this.height = height;
    }

    /** Reads this frame's input, then hides it from everything updated after. */
    Choice update() {
        layout();
        yesHb.update();
        noHb.update();
        boolean click = InputHelper.justClickedLeft || CInputActionSet.select.isJustPressed();
        Choice c = Choice.NONE;
        if (click && yesHb.hovered) c = Choice.YES;
        else if (click && noHb.hovered) c = Choice.NO;
        else if (InputHelper.pressedEscape || CInputActionSet.cancel.isJustPressed()) c = Choice.CANCEL;
        swallowInput();
        return c;
    }

    private void layout() {
        float s = Settings.scale;
        float cx = Settings.WIDTH / 2f;
        float by = Settings.HEIGHT / 2f - height * s / 2f + 80f * s;
        yesHb.resize(400f * s, 80f * s);
        noHb.resize(300f * s, 80f * s);
        yesHb.move(cx - 230f * s, by);
        noHb.move(cx + 260f * s, by);
    }

    private void swallowInput() {
        realX = InputHelper.mX;
        realY = InputHelper.mY;
        InputHelper.mX = OFF_SCREEN;
        InputHelper.mY = OFF_SCREEN;
        InputHelper.justClickedLeft = false;
        InputHelper.justReleasedClickLeft = false;
        InputHelper.justClickedRight = false;
        InputHelper.justReleasedClickRight = false;
        InputHelper.isMouseDown = false;
        InputHelper.isMouseDown_R = false;
        InputHelper.touchDown = false;
        InputHelper.touchUp = false;
        InputHelper.scrolledUp = false;
        InputHelper.scrolledDown = false;
        InputHelper.pressedEscape = false;
        CInputActionSet.select.unpress();
        CInputActionSet.cancel.unpress();
    }

    /** The cursor draws at mX/mY: call after the hidden screen's update, before render. */
    void restorePointer() {
        if (InputHelper.mX == OFF_SCREEN) InputHelper.mX = realX;
        if (InputHelper.mY == OFF_SCREEN) InputHelper.mY = realY;
    }

    void render(SpriteBatch sb) {
        layout();
        float s = Settings.scale;
        float w = width * s;
        float h = height * s;
        float cx = Settings.WIDTH / 2f;
        float cy = Settings.HEIGHT / 2f;
        float edge = 3f * s;
        rect(sb, SHADE, 0, 0, Settings.WIDTH, Settings.HEIGHT);
        rect(sb, EDGE, cx - w / 2f - edge, cy - h / 2f - edge, w + 2 * edge, h + 2 * edge);
        rect(sb, PANEL, cx - w / 2f, cy - h / 2f, w, h);
        FontHelper.renderFontCentered(sb, FontHelper.charTitleFont, title, cx, cy + h / 2f - 70f * s, Settings.GOLD_COLOR);
        FontHelper.renderWrappedText(sb, FontHelper.charDescFont, drawable(FontHelper.charDescFont, body), cx,
                cy + 30f * s, w - 140f * s, Settings.CREAM_COLOR, 1f);
        button(sb, yesHb, yes);
        button(sb, noHb, no);
        sb.setColor(Color.WHITE);
    }

    /** The game's Kreon font has no U+2192, which would draw as nothing; show ">" only then. */
    static String drawable(BitmapFont font, String text) {
        BitmapFont.Glyph g = font.getData().getGlyph('→');
        return g == null || g == font.getData().missingGlyph ? text.replace("→", ">") : text;
    }

    private static void button(SpriteBatch sb, Hitbox hb, String label) {
        rect(sb, hb.hovered ? EDGE : BUTTON, hb.x - 2f, hb.y - 2f, hb.width + 4f, hb.height + 4f);
        rect(sb, hb.hovered ? BUTTON_HOVER : BUTTON, hb.x, hb.y, hb.width, hb.height);
        FontHelper.renderFontCentered(sb, FontHelper.buttonLabelFont, label, hb.cX, hb.cY, Settings.CREAM_COLOR);
    }

    private static void rect(SpriteBatch sb, Color c, float x, float y, float w, float h) {
        sb.setColor(c);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, w, h);
    }
}
