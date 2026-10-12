package io.github.leadfreecandy.metalforge.opt;

import java.util.Arrays;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * The rectangles of one GUI layer node, for "does anything here intersect this rectangle" with the exact
 * ScreenRectangle.intersects semantics. Small nodes scan a flat array; once a node holds GRID_AT rectangles it also keeps a
 * uniform grid so a query only tests rectangles in the cells it covers. Each rectangle is filed under the cells of its closed
 * hull [min(left,right), max(left,right)] x [min(top,bottom), max(top,bottom)], clamped to the grid: if left_a < right_q and
 * right_a > left_q (and likewise vertically) the two hulls overlap, so the pair shares a cell even for zero or negative sizes.
 */
public final class RectIndex {
    private static final int GRID_AT = 24, SHIFT = 4, CELLS = 64; // 16 px cells over 1024 x 1024 GUI px; outside clamps to the edge
    private int[] rects = new int[64];
    private int count;
    private int[] head, next, elem;
    private int entries;
    /** Rectangles spanning more than BIG cells are scanned linearly instead of filed in every cell. */
    private static final int BIG = 64;
    private int[] big = new int[8];
    private int bigCount;

    public void add(ScreenRectangle r) {
        if (r == null) return;
        if (this.count * 4 == this.rects.length) this.rects = Arrays.copyOf(this.rects, this.rects.length * 2);
        int i = this.count++, o = i * 4;
        this.rects[o] = r.left();
        this.rects[o + 1] = r.top();
        this.rects[o + 2] = r.right();
        this.rects[o + 3] = r.bottom();
        if (this.head != null) this.insert(i);
        else if (this.count == GRID_AT) {
            this.head = new int[CELLS * CELLS];
            Arrays.fill(this.head, -1);
            this.next = new int[256];
            this.elem = new int[256];
            for (int k = 0; k < this.count; k++) this.insert(k);
        }
    }

    private static int cell(int v) {
        int c = v >> SHIFT;
        return c < 0 ? 0 : c >= CELLS ? CELLS - 1 : c;
    }

    private void insert(int i) {
        int o = i * 4, l = this.rects[o], t = this.rects[o + 1], r = this.rects[o + 2], b = this.rects[o + 3];
        int x0 = cell(Math.min(l, r)), x1 = cell(Math.max(l, r)), y0 = cell(Math.min(t, b)), y1 = cell(Math.max(t, b));
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > BIG) {
            if (this.bigCount == this.big.length) this.big = Arrays.copyOf(this.big, this.bigCount * 2);
            this.big[this.bigCount++] = i;
            return;
        }
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (this.entries == this.elem.length) {
                    this.elem = Arrays.copyOf(this.elem, this.entries * 2);
                    this.next = Arrays.copyOf(this.next, this.entries * 2);
                }
                int c = y * CELLS + x;
                this.elem[this.entries] = i;
                this.next[this.entries] = this.head[c];
                this.head[c] = this.entries++;
            }
        }
    }

    public boolean intersects(ScreenRectangle q) {
        int l = q.left(), t = q.top(), r = q.right(), b = q.bottom();
        int[] a = this.rects;
        int x0 = cell(Math.min(l, r)), x1 = cell(Math.max(l, r)), y0 = cell(Math.min(t, b)), y1 = cell(Math.max(t, b));
        if (this.head == null || (x1 - x0 + 1) * (y1 - y0 + 1) > this.count) {
            for (int o = 0, n = this.count * 4; o < n; o += 4) {
                if (a[o] < r && a[o + 2] > l && a[o + 1] < b && a[o + 3] > t) return true;
            }
            return false;
        }
        for (int k = 0; k < this.bigCount; k++) {
            int o = this.big[k] * 4;
            if (a[o] < r && a[o + 2] > l && a[o + 1] < b && a[o + 3] > t) return true;
        }
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int e = this.head[y * CELLS + x]; e >= 0; e = this.next[e]) {
                    int o = this.elem[e] * 4;
                    if (a[o] < r && a[o + 2] > l && a[o + 1] < b && a[o + 3] > t) return true;
                }
            }
        }
        return false;
    }
}
