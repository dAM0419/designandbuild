package dev.abcd.client.editor.ui;

import dev.abcd.client.ClientConfig;
import dev.abcd.client.editor.EditorScreen;
import dev.abcd.client.library.ClientLibrary;
import dev.abcd.client.library.LibraryEntry;
import dev.abcd.client.ui.Icons;
import dev.abcd.client.ui.Thumbnails;
import dev.abcd.client.ui.Ui;
import dev.abcd.core.TemplateData;
import dev.abcd.core.TemplateParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 라이브러리 탭: 검색창 하나, 태그 칩 3줄(출처·종류·지형, 고르면 교집합), 최근·즐겨찾기 줄, 썸네일 격자.
 * 썸네일을 누르면 배치 도구로 바뀌고 고스트가 커서에 붙는다. 뷰포트로 끌어다 놓아도 된다. 우클릭 = 즐겨찾기.
 */
final class LibraryTab {
    private static final int CELL = 46, CHIP_ROW = 12;

    private final EditorScreen screen;
    private final EditBox search;
    private final Set<String> active = new LinkedHashSet<>();
    private final int[] chipScroll = new int[3];
    private int scroll;
    private List<LibraryEntry> filtered = List.of();
    private String filterKey = "";
    private final Map<String, Integer> chipsX = new LinkedHashMap<>();
    @Nullable
    private LibraryEntry press;
    private double pressX, pressY;
    private int px, py, pw, ph;

    LibraryTab(EditorScreen screen) {
        this.screen = screen;
        this.search = new EditBox(Ui.font(), 0, 0, 100, 12, Component.empty());
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(Ui.TEXT);
        search.setHint(Component.translatable("abcd.library.search"));
    }

    // ------------------------------------------------------------------

    static Component tagName(String tag) {
        int c = tag.indexOf(':');
        String axis = c < 0 ? "" : tag.substring(0, c);
        String value = c < 0 ? tag : tag.substring(c + 1);
        String key = "abcd.tagname." + value;
        if (I18n.exists(key)) return Component.translatable(key);
        if ("source".equals(axis)) {
            var mod = ModList.get().getModContainerById(value);
            if (mod.isPresent()) return Component.literal(mod.get().getModInfo().getDisplayName());
        }
        String pretty = value.replace('_', ' ');
        return Component.literal(pretty.isEmpty() ? value : Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1));
    }

    private void refilter() {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        String key = q + "|" + active + "|" + ClientLibrary.revision();
        if (key.equals(filterKey)) return;
        filterKey = key;
        List<LibraryEntry> out = new ArrayList<>();
        for (LibraryEntry e : ClientLibrary.all()) {
            if (!e.tags().containsAll(active)) continue;
            if (!q.isEmpty() && !matches(e, q)) continue;
            out.add(e);
        }
        filtered = out;
        int rows = (filtered.size() + 2) / 3;
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows * CELL - 40));
    }

    private static boolean matches(LibraryEntry e, String q) {
        if (e.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (String t : e.tags()) {
            if (t.toLowerCase(Locale.ROOT).contains(q)) return true;
            if (tagName(t).getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private List<String> chips(int axis) {
        String prefix = TemplateParser.TAG_AXES[axis] + ":";
        Set<String> set = new LinkedHashSet<>();
        for (String a : active) if (a.startsWith(prefix)) set.add(a);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LibraryEntry e : filtered) {
            for (String t : e.tags()) if (t.startsWith(prefix)) counts.merge(t, 1, Integer::sum);
        }
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(en -> set.add(en.getKey()));
        return new ArrayList<>(set);
    }

    // ------------------------------------------------------------------

    void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        px = x;
        py = y;
        pw = w;
        ph = h;
        refilter();
        // 검색 + 새로고침 + 폴더
        int sw = w - 44;
        g.fill(x + 4, y + 4, x + 4 + sw, y + 16, search.isFocused() ? 0xFF0B0E12 : 0xFF161B22);
        Ui.border(g, x + 4, y + 4, sw, 12, search.isFocused() ? Ui.ACCENT : Ui.EDGE);
        search.setX(x + 7);
        search.setY(y + 6);
        search.setWidth(sw - 6);
        search.render(g, mx, my, 0);
        int rx = x + w - 38;
        boolean hr = Ui.inside(mx, my, rx, y + 2, 16, 16);
        Ui.iconButton(g, rx, y + 2, 16, 16, Icons.REFRESH, ClientLibrary.isScanning(), hr, true);
        if (hr) screen.tooltip = List.of(Component.translatable("abcd.library.refresh"));
        boolean hg = Ui.inside(mx, my, rx + 18, y + 2, 16, 16);
        Ui.iconButton(g, rx + 18, y + 2, 16, 16, Icons.FOLDER, false, hg, true);
        if (hg) screen.tooltip = List.of(Component.translatable("abcd.library.folders"));

        // 태그 칩 3줄
        chipsX.clear();
        int cy = y + 20;
        for (int axis = 0; axis < 3; axis++) {
            int ry = cy + axis * (CHIP_ROW + 2);
            Ui.text(g, Component.translatable("abcd.tagaxis." + TemplateParser.TAG_AXES[axis]).getString().substring(0, 1),
                    x + 4, ry + 2, Ui.DIM);
            int cx = x + 14 - chipScroll[axis];
            g.enableScissor(x + 13, ry, x + w - 2, ry + CHIP_ROW);
            for (String tag : chips(axis)) {
                Component name = tagName(tag);
                int cw = Ui.font().width(name) + 6;
                boolean on = active.contains(tag);
                boolean hover = Ui.inside(mx, my, cx, ry, cw, CHIP_ROW) && mx >= x + 13;
                g.fill(cx, ry, cx + cw, ry + CHIP_ROW, on ? 0x602EC4B6 : hover ? 0x40FFFFFF : 0x20FFFFFF);
                if (on) Ui.border(g, cx, ry, cw, CHIP_ROW, Ui.ACCENT);
                Ui.text(g, name, cx + 3, ry + 2, on ? Ui.TEXT : Ui.DIM);
                chipsX.put(axis + "|" + tag, cx);
                cx += cw + 2;
            }
            g.disableScissor();
        }

        // 최근·즐겨찾기 줄
        int fy = cy + 3 * (CHIP_ROW + 2) + 2;
        List<LibraryEntry> quick = quickRow();
        Ui.icon(g, Icons.STAR, x + 2, fy + 1, Ui.DIM);
        for (int i = 0; i < quick.size(); i++) {
            int qx = x + 20 + i * 22;
            LibraryEntry e = quick.get(i);
            boolean hover = Ui.inside(mx, my, qx, fy, 20, 18);
            g.fill(qx, fy, qx + 20, fy + 18, hover ? 0x40FFFFFF : 0x20FFFFFF);
            TemplateData t = ClientLibrary.template(e);
            if (t != null) Thumbnails.draw(g, t, qx, fy, 20, 18, 45, 100);
            if (hover) screen.tooltip = entryTip(e, t);
        }

        // 격자
        int gy = fy + 22;
        g.fill(x + 2, gy - 2, x + w - 2, gy - 1, Ui.EDGE);
        int gh = y + h - gy;
        g.enableScissor(x, gy, x + w, gy + gh);
        int cols = 3;
        int cellW = (w - 8) / cols;
        for (int i = 0; i < filtered.size(); i++) {
            int col = i % cols, row = i / cols;
            int ex = x + 4 + col * cellW, ey = gy + row * CELL - scroll;
            if (ey + CELL < gy || ey > gy + gh) continue;
            LibraryEntry e = filtered.get(i);
            boolean hover = Ui.inside(mx, my, ex, ey, cellW - 2, CELL - 2) && my >= gy;
            boolean chosen = screen.session().placing != null && screen.session().placing.id().equals(e.id());
            g.fill(ex, ey, ex + cellW - 2, ey + CELL - 2, chosen ? 0x402EC4B6 : hover ? 0x30FFFFFF : 0x18FFFFFF);
            if (chosen) Ui.border(g, ex, ey, cellW - 2, CELL - 2, Ui.ACCENT);
            TemplateData t = ClientLibrary.template(e);
            if (t != null) {
                if (!Thumbnails.draw(g, t, ex + 2, ey + 2, cellW - 6, CELL - 14, 45, 100)) {
                    Ui.text(g, "…", ex + cellW / 2 - 3, ey + 14, Ui.DIM);
                }
            } else {
                Ui.text(g, ClientLibrary.isMissing(e) ? "×" : "…", ex + cellW / 2 - 3, ey + 14, Ui.DIM);
            }
            if (ClientConfig.get().favorites.contains(e.id())) Ui.icon(g, Icons.STAR, ex + cellW - 18, ey, Ui.WARN);
            String label = Ui.clip(e.name(), cellW - 6);
            Ui.text(g, label, ex + 2, ey + CELL - 12, Ui.DIM);
            if (hover) screen.tooltip = entryTip(e, t);
        }
        if (filtered.isEmpty()) {
            Ui.text(g, Component.translatable(ClientLibrary.all().isEmpty() ? "abcd.library.loading" : "abcd.library.none"),
                    x + 6, gy + 6, Ui.DIM);
        }
        g.disableScissor();
    }

    void renderTooltip() {
    }

    private List<LibraryEntry> quickRow() {
        List<LibraryEntry> out = new ArrayList<>();
        List<LibraryEntry> all = ClientLibrary.all();
        for (String id : ClientConfig.get().favorites) {
            for (LibraryEntry e : all) if (e.id().equals(id) && !out.contains(e)) out.add(e);
            if (out.size() >= 6) return out;
        }
        for (String id : ClientConfig.get().recent) {
            for (LibraryEntry e : all) if (e.id().equals(id) && !out.contains(e)) out.add(e);
            if (out.size() >= 6) return out;
        }
        return out;
    }

    private List<Component> entryTip(LibraryEntry e, @Nullable TemplateData t) {
        List<Component> l = new ArrayList<>();
        l.add(Component.literal(e.name()));
        if (t != null) {
            l.add(Component.translatable("abcd.library.size", t.sizeX, t.sizeY, t.sizeZ).withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
            l.add(Component.translatable("abcd.library.blocks", t.realBlockCount()).withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
            l.add(Component.translatable("abcd.place.mount", Ui.dirName(t.mount)).withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
        } else if (e.sizeX() > 0) {
            l.add(Component.translatable("abcd.library.size", e.sizeX(), e.sizeY(), e.sizeZ()).withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
        }
        StringBuilder tags = new StringBuilder();
        for (String tag : e.tags()) {
            if (tags.length() > 0) tags.append(", ");
            tags.append(tagName(tag).getString());
        }
        l.add(Component.literal(tags.toString()).withStyle(s -> s.withColor(Ui.PATH & 0xFFFFFF)));
        l.add(Component.translatable("abcd.library.tip").withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
        return l;
    }

    // ------------------------------------------------------------------

    private int gridTop() {
        return py + 20 + 3 * (CHIP_ROW + 2) + 2 + 22;
    }

    @Nullable
    private LibraryEntry entryAt(double mx, double my) {
        int gy = gridTop();
        if (my < gy) return null;
        int cellW = (pw - 8) / 3;
        int col = (int) ((mx - px - 4) / cellW);
        int row = (int) ((my - gy + scroll) / CELL);
        if (col < 0 || col > 2) return null;
        int i = row * 3 + col;
        return i >= 0 && i < filtered.size() ? filtered.get(i) : null;
    }

    boolean mouseClicked(double mx, double my, int button) {
        // 검색창
        if (Ui.inside(mx, my, px + 4, py + 4, pw - 44, 12)) {
            screen.focus(search, null);
            search.mouseClicked(mx, my, button);
            return true;
        }
        int rx = px + pw - 38;
        if (Ui.inside(mx, my, rx, py + 2, 16, 16)) {
            ClientLibrary.rescanLocal();
            return true;
        }
        if (Ui.inside(mx, my, rx + 18, py + 2, 16, 16)) {
            screen.openModal(new FolderDialog(screen));
            return true;
        }
        // 칩
        int cy = py + 20;
        for (int axis = 0; axis < 3; axis++) {
            int ry = cy + axis * (CHIP_ROW + 2);
            if (my < ry || my >= ry + CHIP_ROW) continue;
            for (String tag : chips(axis)) {
                Integer cx = chipsX.get(axis + "|" + tag);
                if (cx == null) continue;
                int cw = Ui.font().width(tagName(tag)) + 6;
                if (mx >= cx && mx < cx + cw) {
                    if (!active.remove(tag)) active.add(tag);
                    scroll = 0;
                    return true;
                }
            }
            return true;
        }
        // 최근·즐겨찾기
        int fy = cy + 3 * (CHIP_ROW + 2) + 2;
        List<LibraryEntry> quick = quickRow();
        for (int i = 0; i < quick.size(); i++) {
            if (Ui.inside(mx, my, px + 20 + i * 22, fy, 20, 18)) {
                screen.selectForPlacement(quick.get(i));
                return true;
            }
        }
        LibraryEntry e = entryAt(mx, my);
        if (e == null) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            List<String> fav = ClientConfig.get().favorites;
            if (!fav.remove(e.id())) fav.add(0, e.id());
            ClientConfig.save();
            return true;
        }
        press = e;
        pressX = mx;
        pressY = my;
        return true;
    }

    boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (press != null && Math.abs(mx - pressX) + Math.abs(my - pressY) > 4) {
            screen.libraryDrag = press;
            press = null;
            return true;
        }
        return press != null;
    }

    boolean mouseReleased(double mx, double my, int button) {
        if (press == null) return false;
        LibraryEntry e = press;
        press = null;
        screen.selectForPlacement(e);
        return true;
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        int cy = py + 20;
        for (int axis = 0; axis < 3; axis++) {
            int ry = cy + axis * (CHIP_ROW + 2);
            if (my >= ry && my < ry + CHIP_ROW) {
                chipScroll[axis] = Math.max(0, chipScroll[axis] - (int) (delta * 24));
                return true;
            }
        }
        int rows = (filtered.size() + 2) / 3;
        int max = Math.max(0, rows * CELL - (py + ph - gridTop()));
        scroll = Mth.clamp(scroll - (int) (delta * 24), 0, max);
        return true;
    }
}
