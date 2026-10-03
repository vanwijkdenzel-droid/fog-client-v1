package dev.cpvp.gui;

import dev.cpvp.ProfileManager;
import dev.cpvp.module.Category;
import dev.cpvp.module.Module;
import dev.cpvp.module.ModuleManager;
import dev.cpvp.module.impl.Modules.InterfaceModule;
import dev.cpvp.setting.BooleanSetting;
import dev.cpvp.setting.NumberSetting;
import dev.cpvp.setting.Setting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.IntConsumer;

import static dev.cpvp.gui.RenderUtil.*;

/**
 * Fog V1 menu, laid out like Vape V4: navigator + module panel. Profiles / Mods / Settings open as draggable
 * popup windows on top (X to close) so the module panel stays visible underneath.
 */
public final class ClickGuiScreen extends Screen {
    private static final int NAV_W = 150, PAN_W = 208, GAP = 8, CAT_H = 28, ROW_H = 24, HEADER_H = 28,
            LOGO_H = 54, TITLE_H = 24, RAD = 4;
    private static final int NO_MIN = Integer.MIN_VALUE, NO_MAX = Integer.MAX_VALUE;
    private static final List<Category> COLS = List.of(Category.COMBAT, Category.RENDER, Category.UTILITY,
            Category.WORLD, Category.INVENTORY, Category.NETWORK);

    private static Category selected = Category.COMBAT;

    private static final class Win {
        final String title; int x, y, w; boolean open; final float[] sc = new float[2];
        Win(String t, int x, int y, int w) { title = t; this.x = x; this.y = y; this.w = w; }
    }
    private static final Win PROFILES = new Win("Profiles", 40, 70, 180);
    private static final Win MODS     = new Win("Mods", 80, 80, 232);
    private static final Win SETTINGS = new Win("Settings", 120, 90, 252);
    private static final List<Win> ORDER = new ArrayList<>(List.of(PROFILES, MODS, SETTINGS));

    private static final Map<Object, float[]> SCROLL = new HashMap<>();
    private static final Map<Module, Float> EXPAND = new HashMap<>();
    private static final Set<Module> OPEN = new HashSet<>();

    private record Hit(int x, int y, int w, int h, int clipTop, int clipBottom, IntConsumer click) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private record Region(int x, int y, int w, int h, float[] sc) {}

    private final List<Hit> hits = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private Module listening;
    private NumberSetting dragging;
    private Win dragWin;
    private int dragX, dragW, dragDX, dragDY;
    private double clickX, clickY;
    private float kAnim, scale = 0.85f, modsContent;
    private int lw, lh, savedBlur = -1;
    private boolean toEditor;
    private long lastFrame = System.nanoTime();

    public ClickGuiScreen() { super(Text.literal("Fog V1")); }

    @Override public boolean shouldPause() { return false; }

    private InterfaceModule ui() { return ModuleManager.INSTANCE.get(InterfaceModule.class); }

    @Override protected void init() {
        toEditor = false;
        if (client != null && ui().blur.get() && savedBlur < 0) {
            savedBlur = client.options.getMenuBackgroundBlurriness().getValue();
            client.options.getMenuBackgroundBlurriness().setValue(10);   // strong blur behind the menu
        }
    }

    @Override public void removed() {
        if (client != null && savedBlur >= 0) client.options.getMenuBackgroundBlurriness().setValue(savedBlur);
        savedBlur = -1;
        ProfileManager.saveCurrent();
        if (toEditor) return;
        OPEN.clear(); EXPAND.clear();          // every settings panel + popup closes with the menu
        for (Win w : ORDER) w.open = false;
        listening = null;
    }

    private void scissor(DrawContext c, int x1, int y1, int x2, int y2) {
        c.enableScissor(Math.round(x1 * scale), Math.round(y1 * scale), Math.round(x2 * scale), Math.round(y2 * scale));
    }

    // =============================================================== render
    @Override
    public void render(DrawContext c, int mx, int my, float tickDelta) {
        ACCENT = ui().accent();
        scale = (float) ui().guiScale.get();
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        kAnim = 1f - (float) Math.exp(-dt * ui().animSpeed.get());
        float kScroll = 1f - (float) Math.exp(-dt * 16f);
        hits.clear();
        regions.clear();

        if (ui().blur.get() && client != null && client.world != null) applyBlur(tickDelta);
        c.fill(0, 0, width, height, 0x30000000);

        lw = Math.round(width / scale); lh = Math.round(height / scale);
        int smx = Math.round(mx / scale), smy = Math.round(my / scale);
        var ms = c.getMatrices();
        ms.push();
        ms.scale(scale, scale, 1f);
        drawMain(c, smx, smy, kScroll);
        for (Win w : ORDER) if (w.open) drawWindow(c, w, smx, smy, kScroll);
        ms.pop();
    }

    private void drawMain(DrawContext c, int mx, int my, float kScroll) {
        int navH = LOGO_H + COLS.size() * CAT_H + 20 + 3 * CAT_H + 26;
        int x0 = (lw - (NAV_W + GAP + PAN_W)) / 2, y0 = Math.max(6, (lh - navH) / 2);

        // ---- navigator ----
        roundRect(c, x0, y0, x0 + NAV_W, y0 + navH, RAD, NAV);
        roundRect(c, x0, y0, x0 + NAV_W, y0 + LOGO_H, RAD, BLACK);
        c.fill(x0, y0 + LOGO_H - RAD, x0 + NAV_W, y0 + LOGO_H, BLACK);
        logo(c, x0 + 12, y0 + 8, 0.72f);

        int ry = y0 + LOGO_H;
        for (Category cat : COLS) {
            final Category k = cat;
            ry = navRow(c, x0, ry, cat.ordinal(), cat.label, selected == cat, null, mx, my, b -> selected = k);
        }
        c.fill(x0, ry, x0 + NAV_W, ry + 20, STRIP);
        text(c, "MISC", x0 + 14, ry + 6, DIM, 0.7f);
        ry += 20;
        ry = navRow(c, x0, ry, ICON_PROFILES, "Profiles", PROFILES.open, ProfileManager.current, mx, my, b -> toggleWin(PROFILES));
        ry = navRow(c, x0, ry, Category.MODS.ordinal(), "Mods", MODS.open, null, mx, my, b -> toggleWin(MODS));
        ry = navRow(c, x0, ry, Category.SETTINGS.ordinal(), "Settings", SETTINGS.open, null, mx, my, b -> toggleWin(SETTINGS));
        roundRect(c, x0, ry, x0 + NAV_W, y0 + navH, RAD, BLACK);
        c.fill(x0, ry, x0 + NAV_W, ry + RAD, BLACK);
        outline(c, x0, y0, x0 + NAV_W, y0 + navH, BORDER);

        // ---- module panel ----
        int px = x0 + NAV_W + GAP;
        List<Module> mods = ModuleManager.INSTANCE.byCategory(selected);
        float content = listContent(mods);
        int bodyH = (int) Math.min(content, navH - HEADER_H);
        roundRect(c, px, y0, px + PAN_W, y0 + HEADER_H + bodyH, RAD, PANEL);
        icon(c, selected.ordinal(), px + 10, y0 + 7, ASH);
        text(c, selected.label, px + 34, y0 + 8, TEXT, 0.95f);
        chevronUp(c, px + PAN_W - 18, y0 + 12, DIM);
        drawListBody(c, mods, selected, px, y0 + HEADER_H, PAN_W, bodyH, content, mx, my, kScroll);
        outline(c, px, y0, px + PAN_W, y0 + HEADER_H + bodyH, BORDER);
        regions.add(new Region(px, y0, PAN_W, HEADER_H + bodyH, SCROLL.computeIfAbsent(selected, k -> new float[2])));
    }

    private int navRow(DrawContext c, int x, int y, int icon, String label, boolean sel, String chip,
                       int mx, int my, IntConsumer click) {
        boolean hov = mx >= x && mx < x + NAV_W && my >= y && my < y + CAT_H;
        if (sel) roundRect(c, x + 5, y + 1, x + NAV_W - 5, y + CAT_H - 1, 2, ROW_SEL);
        else if (hov) roundRect(c, x + 5, y + 1, x + NAV_W - 5, y + CAT_H - 1, 2, ROW_HOV);
        icon(c, icon, x + 14, y + 7, sel ? ACCENT : ASH);
        text(c, label, x + 40, y + 8, sel ? ACCENT : TEXT, 0.95f);
        if (chip != null) {
            int cw = width(chip, 0.7f) + 12, cx = x + NAV_W - 26 - cw;
            roundRect(c, cx, y + 7, cx + cw, y + CAT_H - 7, 2, TRACK);
            text(c, chip, cx + 6, y + 10, ASH, 0.7f);
        }
        chevronRight(c, x + NAV_W - 18, y + 11, sel ? ACCENT : DIM);
        hits.add(new Hit(x, y, NAV_W, CAT_H, NO_MIN, NO_MAX, click));
        return y + CAT_H;
    }

    private void toggleWin(Win w) {
        w.open = !w.open;
        if (w.open) front(w);
    }

    private void front(Win w) { ORDER.remove(w); ORDER.add(w); }

    // =============================================================== module list
    private float listContent(List<Module> mods) {
        float content = 2;
        for (Module m : mods) {
            float e = EXPAND.getOrDefault(m, 0f), g = OPEN.contains(m) ? 1f : 0f;
            e += (g - e) * kAnim;
            if (Math.abs(g - e) < 0.01f) e = g;
            EXPAND.put(m, e);
            content += ROW_H + Math.round(settingsHeight(m, true) * e) + 1;
        }
        return content + 3;
    }

    private void drawListBody(DrawContext c, List<Module> mods, Object key, int x, int y, int w, int bodyH,
                              float content, int mx, int my, float kScroll) {
        if (bodyH <= 0) return;
        float[] sc = SCROLL.computeIfAbsent(key, k -> new float[2]);
        float max = Math.max(0, content - bodyH);
        sc[0] = MathHelper.clamp(sc[0], 0, max);
        sc[1] += (sc[0] - sc[1]) * kScroll;
        if (Math.abs(sc[0] - sc[1]) < 0.05f) sc[1] = sc[0];

        final int clipTop = y, clipBottom = y + bodyH;
        scissor(c, x + 1, clipTop, x + w - 1, clipBottom);
        float yy = clipTop + 2 - sc[1];
        for (Module m : mods) {
            float e = EXPAND.getOrDefault(m, 0f);
            final int rx = x + 4, rw = w - 8, top = Math.round(yy);
            int setH = Math.round(settingsHeight(m, true) * e);
            if (top + ROW_H + setH > clipTop && top < clipBottom) {
                boolean hv = mx >= rx && mx < rx + rw && my >= top && my < top + ROW_H && my >= clipTop && my <= clipBottom;
                if (m.isEnabled()) roundRect(c, rx, top, rx + rw, top + ROW_H, 2, alpha(ACCENT, 0.14f));
                else if (hv) roundRect(c, rx, top, rx + rw, top + ROW_H, 2, ROW_HOV);
                if (m.isEnabled()) c.fill(rx, top + 5, rx + 2, top + ROW_H - 5, ACCENT);
                text(c, m.getName(), rx + 10, top + 7, m.isEnabled() ? ACCENT : (hv ? TEXT : ASH), 0.92f);
                if (m.getKey() >= 0) {
                    String kn = keyName(m.getKey());
                    text(c, kn, rx + rw - 20 - width(kn, 0.7f), top + 9, DIM, 0.7f);
                }
                dots(c, rx + rw - 12, top + 6, hv || OPEN.contains(m) ? TEXT : DIM);
                hits.add(new Hit(rx, top, rw - 20, ROW_H, clipTop, clipBottom, b -> {
                    if (b == 0) m.toggle(); else if (b == 1) toggleOpen(m);
                }));
                hits.add(new Hit(rx + rw - 20, top, 20, ROW_H, clipTop, clipBottom, b -> toggleOpen(m)));
                if (setH > 1) drawSettings(c, m, rx, rw, top + ROW_H, setH, e > 0.95f, clipTop, clipBottom, true);
            }
            yy += ROW_H + setH + 1;
        }
        c.disableScissor();
        if (max > 0) {
            int barH = Math.max(14, (int) (bodyH * (bodyH / content)));
            int barY = clipTop + (int) ((bodyH - barH) * (sc[1] / max));
            c.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF444444);
        }
    }

    private void toggleOpen(Module m) { if (!OPEN.remove(m)) OPEN.add(m); }

    // =============================================================== windows
    private int windowBodyH(Win w) {
        if (w == PROFILES) return 6 + 22 + 6 + Math.min(ProfileManager.names().size(), 12) * 22 + 6;
        if (w == MODS) {
            modsContent = listContent(ModuleManager.INSTANCE.byCategory(Category.MODS));
            return 34 + (int) Math.min(modsContent, 230);
        }
        return Math.min(settingsContent(), 290);
    }

    private int settingsContent() {
        return 8 + 16 + settingsHeight(ui(), false) + 8 + 16 + ModuleManager.INSTANCE.byCategory(Category.RENDER).size() * 22 + 6;
    }

    private void drawWindow(DrawContext c, Win win, int mx, int my, float kScroll) {
        int bodyH = windowBodyH(win);
        int x = win.x, y = win.y, w = win.w, h = TITLE_H + bodyH;

        roundRect(c, x, y, x + w, y + h, RAD, PANEL);
        roundRect(c, x, y, x + w, y + TITLE_H, RAD, BLACK);
        c.fill(x, y + TITLE_H - RAD, x + w, y + TITLE_H, BLACK);
        text(c, win.title, x + 10, y + 7, TEXT, 0.95f);
        boolean xh = mx >= x + w - 24 && mx < x + w && my >= y && my < y + TITLE_H;
        if (xh) roundRect(c, x + w - 22, y + 4, x + w - 4, y + TITLE_H - 4, 2, ROW_HOV);
        cross(c, x + w - 17, y + 9, xh ? TEXT : ASH);
        outline(c, x, y, x + w, y + h, BORDER);

        // catch-all (also brings the window to the front), then drag bar, then X
        hits.add(new Hit(x, y, w, h, NO_MIN, NO_MAX, b -> front(win)));
        hits.add(new Hit(x, y, w - 24, TITLE_H, NO_MIN, NO_MAX, b -> {
            front(win); dragWin = win; dragDX = (int) clickX - win.x; dragDY = (int) clickY - win.y;
        }));
        hits.add(new Hit(x + w - 24, y, 24, TITLE_H, NO_MIN, NO_MAX, b -> win.open = false));

        int by = y + TITLE_H;
        if (win == PROFILES) drawProfiles(c, x, by, w);
        else if (win == MODS) {
            roundRect(c, x + 8, by + 6, x + w - 8, by + 28, 2, TRACK);
            centerText(c, "Edit Layout", x + w / 2, by + 12, ACCENT, 0.85f);
            hits.add(new Hit(x + 8, by + 6, w - 16, 22, NO_MIN, NO_MAX, b -> {
                toEditor = true; client.setScreen(new HudEditorScreen(this));
            }));
            drawListBody(c, ModuleManager.INSTANCE.byCategory(Category.MODS), Category.MODS, x, by + 34, w,
                    bodyH - 34, modsContent, mx, my, kScroll);
            regions.add(new Region(x, y, w, h, SCROLL.computeIfAbsent(Category.MODS, k -> new float[2])));
        } else {
            drawSettingsWindow(c, win, x, by, w, bodyH);
            regions.add(new Region(x, y, w, h, win.sc));
        }
    }

    private void drawProfiles(DrawContext c, int x, int by, int w) {
        List<String> names = ProfileManager.names();
        int shown = Math.min(names.size(), 12);
        roundRect(c, x + 8, by + 6, x + w - 8, by + 28, 2, TRACK);
        centerText(c, "+  CREATE NEW", x + w / 2, by + 12, TEXT, 0.8f);
        hits.add(new Hit(x + 8, by + 6, w - 16, 22, NO_MIN, NO_MAX, b -> ProfileManager.create()));
        int ry = by + 34;
        for (int i = 0; i < shown; i++) {
            final String name = names.get(i);
            boolean sel = name.equals(ProfileManager.current);
            if (sel) roundRect(c, x + 8, ry, x + w - 8, ry + 20, 2, 0xFFEDEDED);
            text(c, name, x + 16, ry + 5, sel ? BLACK : TEXT, 0.88f);
            hits.add(new Hit(x + 8, ry, w - 16, 20, NO_MIN, NO_MAX, b -> {
                if (b == 0) ProfileManager.load(name); else if (b == 1) ProfileManager.delete(name);
            }));
            ry += 22;
        }
    }

    private void drawSettingsWindow(DrawContext c, Win win, int x, int by, int w, int viewH) {
        int content = settingsContent();
        float max = Math.max(0, content - viewH);
        win.sc[0] = MathHelper.clamp(win.sc[0], 0, max);
        win.sc[1] += (win.sc[0] - win.sc[1]) * 0.35f;
        final int clipTop = by, clipBottom = by + viewH;
        scissor(c, x + 1, clipTop, x + w - 1, clipBottom);

        int y = Math.round(by + 8 - win.sc[1]);
        text(c, "INTERFACE", x + 12, y, DIM, 0.7f);
        y += 16;
        int ifcH = settingsHeight(ui(), false);
        drawSettings(c, ui(), x + 4, w - 8, y, ifcH, true, clipTop, clipBottom, false);
        y += ifcH + 8;
        text(c, "RENDER MODULES", x + 12, y, DIM, 0.7f);
        y += 16;
        for (Module m : ModuleManager.INSTANCE.byCategory(Category.RENDER)) {
            text(c, m.getName(), x + 14, y + 6, m.isEnabled() ? ACCENT : ASH, 0.88f);
            checkbox(c, x + w - 28, y + 4, 14, m.isEnabled());
            hits.add(new Hit(x + 8, y, w - 16, 22, clipTop, clipBottom, b -> m.toggle()));
            y += 22;
        }
        c.disableScissor();
    }

    // =============================================================== module settings
    private void drawSettings(DrawContext c, Module m, int rx, int rw, int startY, int setH, boolean interact,
                              int clipTop, int clipBottom, boolean keybind) {
        scissor(c, rx, startY, rx + rw, startY + setH);
        c.fill(rx + 1, startY, rx + rw - 1, startY + setH - 1, INNER);
        int sy = startY + 2;
        final int ix = rx + 9, iw = rw - 18;

        if (keybind) {
            text(c, "Keybind", ix, sy + 5, DIM, 0.8f);
            String kt = listening == m ? "press a key..." : (m.getKey() < 0 ? "NONE" : keyName(m.getKey()));
            int kw = width(kt, 0.75f) + 10, kx = ix + iw - kw;
            roundRect(c, kx, sy + 2, kx + kw, sy + 16, 2, TRACK);
            text(c, kt, kx + 5, sy + 5, listening == m ? ACCENT : TEXT, 0.75f);
            if (interact) hits.add(new Hit(kx, sy + 2, kw, 14, clipTop, clipBottom, b -> listening = (listening == m) ? null : m));
            sy += 20;
        }
        for (Setting s : m.getSettings()) {
            if (s instanceof BooleanSetting bs) {
                text(c, s.getName(), ix, sy + 5, TEXT, 0.8f);
                checkbox(c, ix + iw - 13, sy + 3, 13, bs.get());
                if (interact) hits.add(new Hit(ix, sy, iw, 20, clipTop, clipBottom, b -> bs.toggle()));
                sy += 20;
            } else if (s instanceof NumberSetting ns) {
                text(c, s.getName(), ix, sy + 3, TEXT, 0.8f);
                String v = ns.display();
                text(c, v, ix + iw - width(v, 0.8f), sy + 3, ACCENT, 0.8f);
                final int tx = ix, tw = iw, ty = sy + 17;
                double f = (ns.get() - ns.min()) / (ns.max() - ns.min());
                int fw = (int) (tw * f);
                roundRect(c, tx, ty, tx + tw, ty + 4, 1, TRACK);
                if (fw > 0) roundRect(c, tx, ty, tx + Math.max(fw, 3), ty + 4, 1, ACCENT);
                roundRect(c, tx + fw - 3, ty - 2, tx + fw + 3, ty + 6, 1, 0xFFFFFFFF);
                if (interact) hits.add(new Hit(tx - 3, ty - 6, tw + 6, 16, clipTop, clipBottom, b -> {
                    dragging = ns; dragX = tx; dragW = tw; updateSlider(clickX);
                }));
                sy += 27;
            }
        }
        c.disableScissor();
    }

    private int settingsHeight(Module m, boolean keybind) {
        int h = keybind ? 24 : 4;
        for (Setting s : m.getSettings()) h += (s instanceof NumberSetting) ? 27 : 20;
        return h + 4;
    }

    private void updateSlider(double mouseX) {
        if (dragging == null) return;
        double f = MathHelper.clamp((mouseX - dragX) / (double) dragW, 0, 1);
        dragging.set(dragging.min() + f * (dragging.max() - dragging.min()));
    }

    private static String keyName(int key) {
        return InputUtil.fromKeyCode(key, -1).getLocalizedText().getString().toUpperCase(Locale.ROOT);
    }

    // =============================================================== input
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        mx /= scale; my /= scale;
        clickX = mx; clickY = my;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (my < h.clipTop() || my > h.clipBottom()) continue;
            if (h.contains(mx, my)) { h.click().accept(button); return true; }
        }
        listening = null;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        mx /= scale; my /= scale;
        if (dragging != null) { updateSlider(mx); return true; }
        if (dragWin != null) {
            dragWin.x = MathHelper.clamp((int) mx - dragDX, -dragWin.w + 40, lw - 40);
            dragWin.y = MathHelper.clamp((int) my - dragDY, 0, lh - TITLE_H);
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging != null) ProfileManager.markDirty();
        dragging = null;
        dragWin = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        mx /= scale; my /= scale;
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (mx < r.x() || mx >= r.x() + r.w() || my < r.y() || my >= r.y() + r.h()) continue;
            r.sc()[0] -= (float) (vertical * ui().scrollSpeed.get());
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        if (listening != null) {
            boolean clear = key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE;
            listening.setKey(clear ? -1 : key);
            listening = null;
            ProfileManager.markDirty();
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) { close(); return true; }   // closes everything, popups included
        return super.keyPressed(key, scancode, mods);
    }
}
