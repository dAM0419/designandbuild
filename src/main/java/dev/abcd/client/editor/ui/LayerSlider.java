package dev.abcd.client.editor.ui;

import dev.abcd.client.editor.EditorScreen;
import dev.abcd.client.editor.EditorSession;
import dev.abcd.client.ui.Icons;
import dev.abcd.client.ui.Ui;
import dev.abcd.core.Box;
import dev.abcd.core.StructureInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 뷰포트 오른쪽 가장자리의 단면 슬라이더. 평소에는 버튼 하나(L)만, 켜면 슬라이더와 축 좌표.
 * 위 토글: 커서 버블(X), 이 면만 / 이 면까지, 축(Y·X·Z). 슬라이더 위쪽 = 더 드러남(숨긴 쪽 끝).
 * Y 단면에서 구조물이 선택돼 있으면 상대 층(2층 / 5층)으로 표시한다.
 */
public final class LayerSlider extends UiPart {
    private boolean dragging;

    public LayerSlider(EditorScreen screen) {
        super(screen);
    }

    private int[] range() {
        return s().cutRange();
    }

    private int trackTop() {
        return y + 76;
    }

    private int trackBottom() {
        return y + h - 6;
    }

    @Override
    public boolean contains(double mx, double my) {
        if (!s().cutActive) return Ui.inside(mx, my, x + 8, y, 18, 18);
        return super.contains(mx, my);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        EditorSession s = s();
        boolean h0 = Ui.inside(mx, my, x + 8, y, 18, 18);
        g.fill(x + 8, y, x + 26, y + 18, Ui.PANEL);
        Ui.iconButton(g, x + 8, y, 18, 18, Icons.LAYER, s.cutActive, h0, true);
        if (h0) screen.tooltip = List.of(Component.translatable("abcd.layer.toggle"), Component.translatable("abcd.layer.toggle.tip"));
        if (!s.cutActive) return;

        boolean h1 = Ui.inside(mx, my, x + 8, y + 20, 18, 18);
        g.fill(x + 8, y + 20, x + 26, y + 38, Ui.PANEL);
        Ui.iconButton(g, x + 8, y + 20, 18, 18, Icons.BUBBLE, s.bubble, h1, true);
        if (h1) screen.tooltip = List.of(Component.translatable("abcd.layer.bubble"), Component.translatable("abcd.layer.bubble.tip"));
        boolean h2 = Ui.inside(mx, my, x + 8, y + 40, 18, 14);
        g.fill(x + 8, y + 40, x + 26, y + 54, h2 ? 0xF0262E38 : Ui.PANEL);
        String mode = Component.translatable(s.cutOnlyLayer ? "abcd.layer.only.short" : "abcd.layer.below.short").getString();
        Ui.text(g, mode, x + 17 - Ui.font().width(mode) / 2, y + 43, Ui.TEXT);
        if (h2) screen.tooltip = List.of(Component.translatable(s.cutOnlyLayer ? "abcd.layer.only" : "abcd.layer.below"),
                Component.translatable("abcd.layer.mode.tip"));
        boolean h3 = Ui.inside(mx, my, x + 8, y + 56, 18, 14);
        g.fill(x + 8, y + 56, x + 26, y + 70, h3 ? 0xF0262E38 : Ui.PANEL);
        String axis = s.cutAxis.getName().toUpperCase(java.util.Locale.ROOT);
        Ui.text(g, axis, x + 17 - Ui.font().width(axis) / 2, y + 59, Ui.ACCENT);
        if (h3) screen.tooltip = List.of(Component.translatable("abcd.layer.axis", axis),
                Component.translatable("abcd.layer.axis.tip"));

        int[] r = range();
        int top = trackTop(), bottom = trackBottom();
        int tx = x + 16;
        g.fill(tx, top, tx + 2, bottom, 0xA0000000);
        g.fill(tx, top, tx + 1, bottom, Ui.EDGE);
        int hy = handleY(s.cutPos, r);
        boolean hh = Ui.inside(mx, my, x + 6, hy - 4, 22, 9);
        g.fill(x + 8, hy - 3, x + 26, hy + 4, hh || dragging ? Ui.ACCENT : 0xFFB8C2CC);
        String label;
        StructureInstance st = s.selected != 0 ? s.model.structure(s.selected) : null;
        if (st != null && st.bounds() != null && s.cutAxis == net.minecraft.core.Direction.Axis.Y) {
            int floors = st.bounds().sizeY();
            int floor = Mth.clamp(s.cutPos - st.bounds().minY() + 1, 0, floors);
            label = Component.translatable("abcd.layer.floor", floor, floors).getString();
        } else {
            label = s.cutAxis.getName().toUpperCase(java.util.Locale.ROOT) + " " + s.cutPos;
        }
        int lw = Ui.font().width(label) + 6;
        g.fill(x + 6 - lw, hy - 5, x + 6, hy + 6, 0xE0101418);
        Ui.text(g, label, x + 9 - lw, hy - 3, Ui.TEXT);
        if (hh) screen.tooltip = List.of(Component.translatable("abcd.layer.drag"));
    }

    /** 위쪽 = 숨긴 쪽 끝 (다 드러남). 숨기는 쪽이 - 방향이면 값 방향을 뒤집는다. */
    private int handleY(int value, int[] r) {
        int top = trackTop(), bottom = trackBottom();
        float k = (value - r[0]) / (float) Math.max(1, r[1] - r[0]);
        if (s().cutSign() < 0) k = 1 - k;
        return Mth.clamp(Math.round(bottom - k * (bottom - top)), top, bottom);
    }

    private int valueAt(double my, int[] r) {
        int top = trackTop(), bottom = trackBottom();
        float k = (float) ((bottom - my) / Math.max(1, bottom - top));
        if (s().cutSign() < 0) k = 1 - k;
        return Mth.clamp(Math.round(r[0] + k * (r[1] - r[0])), r[0] - 1, r[1] + 1);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        EditorSession s = s();
        if (Ui.inside(mx, my, x + 8, y, 18, 18)) {
            s.toggleCut();
            return true;
        }
        if (!s.cutActive) return false;
        if (Ui.inside(mx, my, x + 8, y + 20, 18, 18)) {
            s.bubble = !s.bubble;
            return true;
        }
        if (Ui.inside(mx, my, x + 8, y + 40, 18, 14)) {
            s.cutOnlyLayer = !s.cutOnlyLayer;
            return true;
        }
        if (Ui.inside(mx, my, x + 8, y + 56, 18, 14)) {
            s.cycleCutAxis();
            return true;
        }
        if (my >= trackTop() - 4 && my <= trackBottom() + 4) {
            dragging = true;
            s.cutPos = valueAt(my, range());
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!dragging) return false;
        s().cutPos = valueAt(my, range());
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!dragging) return false;
        dragging = false;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        s().moveCut(delta > 0 ? 1 : -1);
        return true;
    }
}
