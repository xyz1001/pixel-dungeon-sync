package io.github.xyz1001.pdsync.android;

import java.util.List;

/** Pure JVM geometry checks, not Android device or screenshot tests. */
public final class HistoryArrowGeometryChecks {
    private static int checks;

    public static void main(String[] args) {
        sameColumnUsesCardCenter();
        unequalHeightsKeepHeadInVisibleGap();
        crossColumnStaysOnCenterLineInBothDirections();
        interveningCardCannotHideArrowhead();
        shortGapScalesArrowhead();
        overlappingCardsDoNotInventAnOutsideRoute();
        densityAndWidthChangesPreserveGeometry();
        observedPhoneBoundsPreserveRealParentDirection();
        System.out.println("History arrow geometry: " + checks + " checks passed");
    }

    private static void sameColumnUsesCardCenter() {
        for (float left : new float[]{0, 240}) {
            HistoryArrowGeometry.Box child = box(left, 6, left + 180, 160);
            HistoryArrowGeometry.Box parent = box(left, 174, left + 180, 328);
            HistoryArrowGeometry.Arrow arrow = arrow(parent, child, List.of(parent, child));
            check(arrow != null && arrow.hasHead, "adjacent cards have a visible arrow");
            close(left + 90, arrow.startX, "shaft starts at card center X");
            close(left + 90, arrow.endX, "shaft remains inside the same column");
            close(174, arrow.startY, "shaft starts at parent top border");
            close(160, arrow.endY, "shaft ends at child bottom border");
            check(arrow.tipY < (arrow.leftY + arrow.rightY) / 2f, "parent points upward to child");
            headClear(arrow, List.of(parent, child));
        }
    }

    private static void unequalHeightsKeepHeadInVisibleGap() {
        HistoryArrowGeometry.Box child = box(0, 6, 180, 246);
        HistoryArrowGeometry.Box parent = box(0, 260, 180, 386);
        HistoryArrowGeometry.Arrow arrow = arrow(parent, child, List.of(parent, child));
        // The midpoint of the card centers is inside the tall child; the visible gap is 246..260.
        check((parent.centerY() + child.centerY()) / 2f < child.bottom,
                "fixture reproduces the hidden-midpoint bug");
        close(260, arrow.startY, "uses actual parent bounds rather than row midpoint");
        close(246, arrow.endY, "uses actual child bounds rather than row midpoint");
        check(arrow.tipY > 246 && arrow.tipY < 260, "head is in the actual gap");
        headClear(arrow, List.of(parent, child));
    }

    private static void crossColumnStaysOnCenterLineInBothDirections() {
        HistoryArrowGeometry.Box cloud = box(0, 6, 180, 246);
        HistoryArrowGeometry.Box local = box(240, 260, 420, 386);
        for (boolean upload : new boolean[]{false, true}) {
            HistoryArrowGeometry.Box from = upload ? local : cloud, to = upload ? cloud : local;
            HistoryArrowGeometry.Arrow arrow = arrow(from, to, List.of(cloud, local));
            check(arrow != null && arrow.hasHead, "diagonal cross-column edge is visible");
            onCenterLine(from, to, arrow.startX, arrow.startY);
            onCenterLine(from, to, arrow.endX, arrow.endY);
            onCenterLine(from, to, arrow.tipX, arrow.tipY);
            float headX = arrow.tipX - (arrow.leftX + arrow.rightX) / 2f;
            float headY = arrow.tipY - (arrow.leftY + arrow.rightY) / 2f;
            check(headX * (to.centerX() - from.centerX()) + headY * (to.centerY() - from.centerY()) > 0,
                    "arrow preserves upload/restore direction");
            headClear(arrow, List.of(cloud, local));
        }
        HistoryArrowGeometry.Box sameRow = box(240, 6, 420, 246);
        HistoryArrowGeometry.Arrow horizontal = arrow(cloud, sameRow, List.of(cloud, sameRow));
        close(cloud.centerY(), horizontal.startY, "same-row cross-column edge is horizontal");
        close(180, horizontal.startX, "cross-column edge starts on cloud border");
        close(240, horizontal.endX, "cross-column edge ends on local border");
        headClear(horizontal, List.of(cloud, sameRow));
    }

    private static void interveningCardCannotHideArrowhead() {
        HistoryArrowGeometry.Box child = box(0, 6, 180, 160);
        HistoryArrowGeometry.Box middle = box(0, 174, 180, 328);
        HistoryArrowGeometry.Box parent = box(0, 342, 180, 496);
        List<HistoryArrowGeometry.Box> cards = List.of(parent, middle, child);
        HistoryArrowGeometry.Arrow arrow = arrow(parent, child, cards);
        check(arrow != null && arrow.hasHead, "non-adjacent DAG edge retains a visible head");
        close(90, arrow.startX, "long same-column edge never routes outside");
        close(90, arrow.endX, "long same-column edge stays straight");
        headClear(arrow, cards);
    }

    private static void shortGapScalesArrowhead() {
        HistoryArrowGeometry.Box child = box(0, 0, 180, 100);
        HistoryArrowGeometry.Box parent = box(0, 105, 180, 205);
        HistoryArrowGeometry.Arrow arrow = arrow(parent, child, List.of(parent, child));
        check(arrow != null && arrow.hasHead, "small but visible gap keeps a scaled head");
        check(Math.abs(arrow.tipY - arrow.leftY) < 7, "head scales to fit the small gap");
        headClear(arrow, List.of(parent, child));
    }

    private static void overlappingCardsDoNotInventAnOutsideRoute() {
        HistoryArrowGeometry.Box first = box(0, 0, 180, 200);
        check(arrow(first, first, List.of(first)) == null, "self edge has no invented direction");
        HistoryArrowGeometry.Box overlap = box(0, 100, 180, 300);
        check(arrow(first, overlap, List.of(first, overlap)) == null, "overlapping cards have no outside detour");
    }

    private static void densityAndWidthChangesPreserveGeometry() {
        for (float scale : new float[]{1, 1.5f, 3}) {
            for (float width : new float[]{144, 180, 320}) {
                HistoryArrowGeometry.Box top = box(0, 6 * scale, width * scale, 160 * scale);
                HistoryArrowGeometry.Box bottom = box(0, 174 * scale, width * scale, 328 * scale);
                HistoryArrowGeometry.Arrow arrow = HistoryArrowGeometry.between(bottom, top, List.of(top, bottom),
                        7 * scale, 4 * scale, scale);
                close(width * scale / 2, arrow.tipX, "width and density use measured center");
                headClear(arrow, List.of(top, bottom));
            }
        }
    }

    private static void observedPhoneBoundsPreserveRealParentDirection() {
        // Bounds observed in the phone's UI hierarchy (1240 x 2772), not a fabricated screenshot.
        // History is displayed B, A, C by save timestamp while its actual parent chain is A -> B -> C.
        HistoryArrowGeometry.Box b = box(56, 833, 541, 1298);
        HistoryArrowGeometry.Box a = box(56, 1393, 541, 1858);
        HistoryArrowGeometry.Box c = box(56, 1953, 541, 2418);
        HistoryArrowGeometry.Box local = box(699, 833, 1184, 1298);
        List<HistoryArrowGeometry.Box> cards = List.of(b, a, c, local);
        HistoryArrowGeometry.Arrow ab = HistoryArrowGeometry.between(a, b, cards, 21, 12, 3);
        HistoryArrowGeometry.Arrow bc = HistoryArrowGeometry.between(b, c, cards, 21, 12, 3);
        close(298.5f, ab.startX, "observed phone cloud column center");
        close(298.5f, bc.endX, "non-adjacent edge stays in the cloud column");
        check(ab.tipY < ab.leftY, "A to B follows the parent direction upward");
        check(bc.tipY > bc.leftY, "B to C follows the parent direction downward despite display order");
        check(bc.tipY > a.bottom, "B to C head uses the gap nearest C, avoiding the opposite A to B head");
        headClear(ab, cards);
        headClear(bc, cards);
        HistoryArrowGeometry.Arrow restore = HistoryArrowGeometry.between(c, local, cards, 21, 12, 3);
        onCenterLine(c, local, restore.startX, restore.startY);
        onCenterLine(c, local, restore.endX, restore.endY);
        headClear(restore, cards);
    }

    private static HistoryArrowGeometry.Arrow arrow(HistoryArrowGeometry.Box from,
            HistoryArrowGeometry.Box to, List<HistoryArrowGeometry.Box> cards) {
        return HistoryArrowGeometry.between(from, to, cards, 7, 4, 1);
    }

    private static HistoryArrowGeometry.Box box(float left, float top, float right, float bottom) {
        return new HistoryArrowGeometry.Box(left, top, right, bottom);
    }

    private static void onCenterLine(HistoryArrowGeometry.Box from, HistoryArrowGeometry.Box to,
            float x, float y) {
        float dx = to.centerX() - from.centerX(), dy = to.centerY() - from.centerY();
        check(Math.abs((x - from.centerX()) * dy - (y - from.centerY()) * dx) < 0.02f,
                "edge and tip follow the straight card-center line");
    }

    private static void headClear(HistoryArrowGeometry.Arrow arrow, List<HistoryArrowGeometry.Box> cards) {
        check(arrow.hasHead, "head is present");
        // Sample both complete strokes, not only the tip and outer endpoints.
        for (HistoryArrowGeometry.Box card : cards) {
            for (int i = 0; i <= 20; i++) {
                float t = i / 20f;
                outside(card, arrow.tipX * t + arrow.leftX * (1 - t), arrow.tipY * t + arrow.leftY * (1 - t));
                outside(card, arrow.tipX * t + arrow.rightX * (1 - t), arrow.tipY * t + arrow.rightY * (1 - t));
            }
        }
    }

    private static void outside(HistoryArrowGeometry.Box box, float x, float y) {
        check(x < box.left || x > box.right || y < box.top || y > box.bottom,
                "arrowhead stroke must not be obscured by a card");
    }

    private static void close(float expected, float actual, String message) {
        check(Math.abs(expected - actual) < 0.001f, message + ": " + expected + " != " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
