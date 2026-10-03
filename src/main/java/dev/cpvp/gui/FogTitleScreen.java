package dev.cpvp.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;

import java.util.Random;

import static dev.cpvp.gui.RenderUtil.*;

/** Launcher-style home screen: black side panel with animated entrance, drifting smoke on the right. */
public final class FogTitleScreen extends Screen {
    private static final String[] LABELS = { "Singleplayer", "Multiplayer", "Options", "Quit Game" };
    private static final int PANEL_W = 250;

    private static final class Puff { float x, y, vx, vy, r, a, phase; }
    private final Puff[] puffs = new Puff[44];
    private final float[] hover = new float[LABELS.length];
    private final long start = System.nanoTime();
    private long last = System.nanoTime();
    private final Random rnd = new Random();

    public FogTitleScreen() {
        super(Text.literal("Fog V1"));
        for (int i = 0; i < puffs.length; i++) { puffs[i] = new Puff(); reset(puffs[i], true, 800, 500); }
    }

    @Override public boolean shouldCloseOnEsc() { return false; }

    private void reset(Puff p, boolean anywhere, int w, int h) {
        p.x = PANEL_W + rnd.nextFloat() * Math.max(100, w - PANEL_W);
        p.y = anywhere ? rnd.nextFloat() * h : h + 80;
        p.r = 50 + rnd.nextFloat() * 90;
        p.vx = (rnd.nextFloat() - 0.3f) * 10f;
        p.vy = -(6f + rnd.nextFloat() * 14f);
        p.a = 0.035f + rnd.nextFloat() * 0.04f;
        p.phase = rnd.nextFloat() * 6.28f;
    }

    private static void disc(DrawContext c, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy += 2) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            c.fill(cx - half, cy + dy, cx + half, cy + dy + 2, color);
        }
    }

    private static float ease(float p) { p = Math.max(0, Math.min(1, p)); return 1f - (1f - p) * (1f - p) * (1f - p); }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        float t = (now - start) / 1e9f;

        c.fillGradient(0, 0, width, height, 0xFF000000, 0xFF030907);

        // smoke
        c.enableScissor(PANEL_W, 0, width, height);
        for (Puff p : puffs) {
            p.x += (p.vx + (float) Math.sin(t * 0.4 + p.phase) * 6f) * dt;
            p.y += p.vy * dt;
            if (p.y < -p.r * 1.5f) reset(p, false, width, height);
            int cx = Math.round(p.x), cy = Math.round(p.y), r = Math.round(p.r);
            int tint = 0xFFB4D2C2;
            disc(c, cx, cy, r, alpha(tint, p.a));
            disc(c, cx, cy, Math.round(r * 0.72f), alpha(tint, p.a));
            disc(c, cx, cy, Math.round(r * 0.45f), alpha(ACCENT, p.a * 0.8f));
        }
        c.disableScissor();

        // side panel
        float pa = ease(t / 0.5f);
        int pw = Math.round(PANEL_W * pa);
        c.fill(0, 0, pw, height, 0xF0000000);
        c.fill(pw - 1, 0, pw, height, alpha(ACCENT, 0.5f * pa));

        float la = ease((t - 0.15f) / 0.6f);
        logo(c, 28 - Math.round((1 - la) * 40), 34, 1.5f);
        text(c, "Minecraft 1.21.1", 30 - Math.round((1 - la) * 40), 34 + logoHeight(1.5f) + 6, alpha(DIM, la), 0.8f);

        int bx = 24, bw = PANEL_W - 48, bh = 30, y0 = height / 2 - 10;
        for (int i = 0; i < LABELS.length; i++) {
            float p = ease((t - 0.35f - i * 0.09f) / 0.45f);
            int by = y0 + i * (bh + 6);
            int off = Math.round((1 - p) * -60);
            boolean hov = p > 0.9f && mx >= bx && mx < bx + bw && my >= by && my < by + bh;
            hover[i] += ((hov ? 1f : 0f) - hover[i]) * Math.min(1f, dt * 14f);
            int fill = alpha(0xFF1A1A1A, p);
            c.fill(bx + off, by, bx + bw + off, by + bh, fill);
            int hb = Math.round(hover[i] * bw);
            c.fill(bx + off, by, bx + off + Math.round(3 + hover[i] * 3), by + bh, alpha(ACCENT, p * (0.35f + hover[i] * 0.65f)));
            if (hb > 0) c.fill(bx + off, by + bh - 1, bx + off + hb, by + bh, alpha(ACCENT, p));
            text(c, LABELS[i], bx + 16 + off + Math.round(hover[i] * 4), by + 10, alpha(hov ? 0xFFFFFFFF : TEXT, p), 1.0f);
        }
        text(c, "Fog V1", 28, height - 18, DIM, 0.75f);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int bx = 24, bw = PANEL_W - 48, bh = 30, y0 = height / 2 - 10;
        for (int i = 0; i < LABELS.length; i++) {
            int by = y0 + i * (bh + 6);
            if (mx >= bx && mx < bx + bw && my >= by && my < by + bh) {
                switch (i) {
                    case 0 -> client.setScreen(new SelectWorldScreen(this));
                    case 1 -> client.setScreen(new MultiplayerScreen(this));
                    case 2 -> client.setScreen(new OptionsScreen(this, client.options));
                    default -> client.scheduleStop();
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}
