package io.github.xyz1001.pdsync.android;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Straight, center-to-center history edges, independent of Android for regression tests. */
final class HistoryArrowGeometry {
    private HistoryArrowGeometry() {}

    static final class Box {
        final float left, top, right, bottom;

        Box(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float centerX() { return (left + right) / 2f; }
        float centerY() { return (top + bottom) / 2f; }
    }

    static final class Arrow {
        final float startX, startY, endX, endY;
        final float tipX, tipY, leftX, leftY, rightX, rightY;
        final boolean hasHead;

        Arrow(float startX, float startY, float endX, float endY,
                float tipX, float tipY, float leftX, float leftY, float rightX, float rightY,
                boolean hasHead) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.tipX = tipX;
            this.tipY = tipY;
            this.leftX = leftX;
            this.leftY = leftY;
            this.rightX = rightX;
            this.rightY = rightY;
            this.hasHead = hasHead;
        }
    }

    /**
     * The shaft follows the actual card centers and stops at their boundaries. Cards are
     * still drawn above it. Put the arrowhead in an exposed gap, since the center of a long
     * edge can be inside an intervening card and unequal card heights move the visible gap.
     */
    static Arrow between(Box from, Box to, List<Box> cards, float headLength, float headWidth,
            float strokeInset) {
        float x = from.centerX(), y = from.centerY();
        float dx = to.centerX() - x, dy = to.centerY() - y;
        float length = (float) Math.hypot(dx, dy);
        if (length < 1f) return null;
        float ux = dx / length, uy = dy / length;
        float start = boundaryDistance(from, ux, uy);
        float end = length - boundaryDistance(to, ux, uy);
        if (end <= start) return null;

        // Expanding only perpendicular to the line keeps the whole arrowhead outside cards
        // without needlessly shrinking the vertical gap between same-column cards.
        float padX = Math.abs(uy) * headWidth + strokeInset;
        float padY = Math.abs(ux) * headWidth + strokeInset;
        List<float[]> covered = new ArrayList<>();
        for (Box card : cards) {
            float[] interval = {start, end};
            if (clip(interval, x, ux, card.left - padX, card.right + padX)
                    && clip(interval, y, uy, card.top - padY, card.bottom + padY)) {
                covered.add(interval);
            }
        }
        covered.sort(Comparator.comparingDouble(interval -> interval[0]));
        List<float[]> gaps = new ArrayList<>();
        float cursor = start;
        for (float[] interval : covered) {
            if (interval[0] > cursor) gaps.add(new float[]{cursor, interval[0]});
            cursor = Math.max(cursor, interval[1]);
        }
        if (cursor < end) gaps.add(new float[]{cursor, end});

        float[] best = null;
        // Prefer a gap that fits the normal head, then the one nearest the destination.
        // Save-time order need not match ancestry: a skipped row must not hide the head or
        // make two opposite parent/child edges place their heads on top of each other.
        for (float[] gap : gaps) {
            if (best == null || fits(gap, headLength) || !fits(best, headLength)) best = gap;
        }
        float sx = x + ux * start, sy = y + uy * start;
        float ex = x + ux * end, ey = y + uy * end;
        if (best == null || best[1] - best[0] < 1f) {
            return new Arrow(sx, sy, ex, ey, 0, 0, 0, 0, 0, 0, false);
        }
        float size = Math.min(headLength, best[1] - best[0]);
        float width = headWidth * size / headLength;
        float tip = (best[0] + best[1] + size) / 2f;
        float tx = x + ux * tip, ty = y + uy * tip;
        float bx = tx - ux * size, by = ty - uy * size;
        return new Arrow(sx, sy, ex, ey, tx, ty,
                bx - uy * width, by + ux * width,
                bx + uy * width, by - ux * width, true);
    }

    private static boolean fits(float[] gap, float size) { return gap[1] - gap[0] >= size; }

    private static float boundaryDistance(Box box, float ux, float uy) {
        return Math.min(ux == 0f ? Float.POSITIVE_INFINITY : (box.right - box.left) / (2f * Math.abs(ux)),
                uy == 0f ? Float.POSITIVE_INFINITY : (box.bottom - box.top) / (2f * Math.abs(uy)));
    }

    private static boolean clip(float[] interval, float origin, float unit, float min, float max) {
        if (unit == 0f) return origin >= min && origin <= max;
        float a = (min - origin) / unit, b = (max - origin) / unit;
        interval[0] = Math.max(interval[0], Math.min(a, b));
        interval[1] = Math.min(interval[1], Math.max(a, b));
        return interval[0] <= interval[1];
    }
}
