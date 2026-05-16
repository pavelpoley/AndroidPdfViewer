package com.github.barteksc.pdfviewer;

import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import java.util.List;

final class ReflowScrollAnchor {
    final int page;
    final int offsetFromPageTop;
    final int scrollY;

    private ReflowScrollAnchor(int page, int offsetFromPageTop, int scrollY) {
        this.page = page;
        this.offsetFromPageTop = offsetFromPageTop;
        this.scrollY = scrollY;
    }

    @Nullable
    static ReflowScrollAnchor capture(List<ReflowPageSlot> pageSlots, int scrollY) {
        if (pageSlots.isEmpty()) {
            return null;
        }

        for (ReflowPageSlot slot : pageSlots) {
            int top = slot.container.getTop();
            int bottom = slot.container.getBottom();
            if (bottom > scrollY) {
                return new ReflowScrollAnchor(slot.page, Math.max(0, scrollY - top), scrollY);
            }
        }

        ReflowPageSlot lastSlot = pageSlots.get(pageSlots.size() - 1);
        return new ReflowScrollAnchor(
                lastSlot.page,
                Math.max(0, scrollY - lastSlot.container.getTop()),
                scrollY
        );
    }

    static void restoreAfterLayout(
            @Nullable ReflowScrollAnchor anchor,
            ScrollView scrollView,
            LinearLayout pagesContainer,
            List<ReflowPageSlot> pageSlots,
            int userScrollTolerancePx,
            Runnable afterRestore
    ) {
        if (anchor == null) {
            return;
        }

        pagesContainer.post(() -> {
            if (anchor.page < 0 || anchor.page >= pageSlots.size()) {
                return;
            }
            if (Math.abs(scrollView.getScrollY() - anchor.scrollY) > userScrollTolerancePx) {
                return;
            }

            ReflowPageSlot slot = pageSlots.get(anchor.page);
            int maxOffset = Math.max(0, slot.currentHeight - 1);
            int targetScrollY = slot.container.getTop() + Math.min(anchor.offsetFromPageTop, maxOffset);
            scrollView.scrollTo(0, Math.max(0, targetScrollY));
            afterRestore.run();
        });
    }
}
