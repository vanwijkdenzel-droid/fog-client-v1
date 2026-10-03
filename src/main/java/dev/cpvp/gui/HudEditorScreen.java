package dev.cpvp.gui;

import dev.cpvp.module.Module;
import dev.cpvp.module.ModuleManager;
import dev.cpvp.module.impl.Modules.HudElement;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import static dev.cpvp.gui.RenderUtil.*;

/** Drag your HUD mods (armor, CPS, keystrokes...) where you want them. ESC goes back to the menu. */
public final class HudEditorScreen extends Screen {
    private final Screen parent;
    private HudElement drag;
    private int dx, dy;

    public HudEditorScreen(Screen parent) { super(Text.literal("Layout")); this.parent = parent; }

    @Override public boolean shouldPause() { return false; }
    @Override public void close() { dev.cpvp.ProfileManager.saveCurrent(); client.setScreen(parent); }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, 0x70000000);
        boolean any = false;
        for (Module m : ModuleManager.INSTANCE.all()) {
            if (!(m instanceof HudElement h) || !m.isEnabled()) continue;
            any = true;
            h.draw(c, h.x(), h.y());
            boolean hov = mx >= h.x() && mx < h.x() + h.width() && my >= h.y() && my < h.y() + h.height();
            outline(c, h.x() - 1, h.y() - 1, h.x() + h.width() + 1, h.y() + h.height() + 1, hov || h == drag ? ACCENT : alpha(ACCENT, 0.4f));
            text(c, m.getName(), h.x(), h.y() - 10, ASH, 0.7f);
        }
        centerText(c, "Layout editor  -  drag elements, ESC to go back", width / 2, 12, TEXT, 0.95f);
        if (!any) centerText(c, "No HUD mods enabled. Turn some on in the Mods window first.", width / 2, height / 2, ASH, 0.9f);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = ModuleManager.INSTANCE.all().size() - 1; i >= 0; i--) {
            Module m = ModuleManager.INSTANCE.all().get(i);
            if (!(m instanceof HudElement h) || !m.isEnabled()) continue;
            if (mx >= h.x() && mx < h.x() + h.width() && my >= h.y() && my < h.y() + h.height()) {
                drag = h; dx = (int) mx - h.x(); dy = (int) my - h.y();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double ddx, double ddy) {
        if (drag == null) return false;
        drag.setPos(MathHelper.clamp((int) mx - dx, 0, width - drag.width()), MathHelper.clamp((int) my - dy, 0, height - drag.height()));
        return true;
    }

    @Override public boolean mouseReleased(double mx, double my, int button) { drag = null; return true; }
}
