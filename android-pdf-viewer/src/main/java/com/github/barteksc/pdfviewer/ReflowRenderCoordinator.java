package com.github.barteksc.pdfviewer;

import android.graphics.Bitmap;
import android.os.Handler;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.github.barteksc.pdfviewer.reflow.ReflowBitmapProcessor;

import java.util.List;

final class ReflowRenderCoordinator {
    private static final int PAGE_PREFETCH_RADIUS = 1;
    private static final int MAX_RENDERED_PAGES = 4;
    private static final int RENDER_DEBOUNCE_MS = 80;
    private static final int ANCHOR_USER_SCROLL_TOLERANCE_DP = 8;

    private final Handler mainHandler;
    private final ScrollView scrollView;
    private final LinearLayout pagesContainer;
    private final List<ReflowPageSlot> pageSlots;
    private final ReflowDocumentSession session;
    private final ReflowLoadConfig loadConfig;
    private final ReflowBitmapProcessor processor = new ReflowBitmapProcessor();
    private final Runnable renderVisibleRunnable = this::renderVisiblePages;

    private ReflowRenderOptions options;
    private int visibleStart;
    private int visibleEnd;
    private int renderGeneration;
    private boolean disposed;

    ReflowRenderCoordinator(
            Handler mainHandler,
            ScrollView scrollView,
            LinearLayout pagesContainer,
            List<ReflowPageSlot> pageSlots,
            ReflowDocumentSession session,
            ReflowLoadConfig loadConfig,
            ReflowRenderOptions options
    ) {
        this.mainHandler = mainHandler;
        this.scrollView = scrollView;
        this.pagesContainer = pagesContainer;
        this.pageSlots = pageSlots;
        this.session = session;
        this.loadConfig = loadConfig;
        this.options = options;
    }

    void scheduleVisibleRender() {
        if (disposed) {
            return;
        }
        mainHandler.removeCallbacks(renderVisibleRunnable);
        mainHandler.postDelayed(renderVisibleRunnable, RENDER_DEBOUNCE_MS);
    }

    void resetForViewport(ReflowRenderOptions newOptions) {
        if (disposed) {
            return;
        }
        ReflowScrollAnchor anchor = ReflowScrollAnchor.capture(pageSlots, scrollView.getScrollY());
        renderGeneration++;
        mainHandler.removeCallbacks(renderVisibleRunnable);
        options = newOptions;
        for (ReflowPageSlot slot : pageSlots) {
            slot.resetForViewport(newOptions);
        }
        restoreAnchorAfterLayout(anchor);
    }

    void dispose() {
        disposed = true;
        renderGeneration++;
        mainHandler.removeCallbacks(renderVisibleRunnable);
        for (ReflowPageSlot slot : pageSlots) {
            slot.releaseBitmap();
        }
    }

    private void renderVisiblePages() {
        if (disposed || pageSlots.isEmpty()) {
            return;
        }

        int viewportTop = scrollView.getScrollY();
        int viewportBottom = viewportTop + Math.max(1, scrollView.getHeight());
        int firstVisible = -1;
        int lastVisible = -1;

        for (int i = 0; i < pageSlots.size(); i++) {
            ReflowPageSlot slot = pageSlots.get(i);
            int top = slot.container.getTop();
            int bottom = slot.container.getBottom();
            if (bottom >= viewportTop && top <= viewportBottom) {
                if (firstVisible < 0) {
                    firstVisible = i;
                }
                lastVisible = i;
            }
        }

        if (firstVisible < 0) {
            firstVisible = 0;
            lastVisible = 0;
        }

        visibleStart = Math.max(0, firstVisible);
        visibleEnd = Math.max(visibleStart, lastVisible);

        for (int page = visibleStart; page <= visibleEnd; page++) {
            requestRenderPage(page);
        }

        int renderStart = Math.max(0, visibleStart - PAGE_PREFETCH_RADIUS);
        int renderEnd = Math.min(pageSlots.size() - 1, visibleEnd + PAGE_PREFETCH_RADIUS);
        for (int distance = 1; distance <= PAGE_PREFETCH_RADIUS; distance++) {
            requestRenderPage(visibleStart - distance);
            requestRenderPage(visibleEnd + distance);
        }

        recycleFarPages(renderStart, renderEnd);
    }

    private void requestRenderPage(int page) {
        if (page < 0 || page >= pageSlots.size()) {
            return;
        }

        ReflowPageSlot slot = pageSlots.get(page);
        if (slot.bitmap != null || slot.renderRequested) {
            return;
        }

        slot.markRenderRequested();
        int generation = renderGeneration;
        session.renderPage(
                page,
                options,
                loadConfig.annotationRendering,
                processor,
                new ReflowDocumentSession.PageRenderCallback() {
                    @Override
                    public boolean shouldRender(int callbackPage) {
                        return isCurrent(generation) && isNearVisibleRange(callbackPage);
                    }

                    @Override
                    public boolean isCancelled() {
                        return !isCurrent(generation) || Thread.currentThread().isInterrupted();
                    }

                    @Override
                    public void onRendered(int callbackPage, Bitmap bitmap) {
                        handleRenderedPage(generation, callbackPage, bitmap);
                    }

                    @Override
                    public void onSkipped(int callbackPage) {
                        handleSkippedPage(generation, callbackPage);
                    }

                    @Override
                    public void onFailed(int callbackPage) {
                        handleFailedPage(generation, callbackPage);
                    }
                }
        );
    }

    private void handleRenderedPage(int generation, int page, Bitmap bitmap) {
        if (!isCurrent(generation) || page < 0 || page >= pageSlots.size()) {
            bitmap.recycle();
            return;
        }

        ReflowPageSlot slot = pageSlots.get(page);
        ReflowScrollAnchor anchor = ReflowScrollAnchor.capture(pageSlots, scrollView.getScrollY());
        slot.bindBitmap(bitmap);
        restoreAnchorAfterLayout(anchor);
        recycleFarPages(
                Math.max(0, visibleStart - PAGE_PREFETCH_RADIUS),
                Math.min(pageSlots.size() - 1, visibleEnd + PAGE_PREFETCH_RADIUS)
        );
    }

    private void handleSkippedPage(int generation, int page) {
        if (isCurrent(generation) && page >= 0 && page < pageSlots.size()) {
            pageSlots.get(page).markRenderSkipped();
        }
    }

    private void handleFailedPage(int generation, int page) {
        if (isCurrent(generation) && page >= 0 && page < pageSlots.size()) {
            pageSlots.get(page).markRenderFailed();
        }
    }

    private void recycleFarPages(int keepStart, int keepEnd) {
        int renderedCount = 0;
        int visibleCenter = (visibleStart + visibleEnd) / 2;

        for (int i = 0; i < pageSlots.size(); i++) {
            ReflowPageSlot slot = pageSlots.get(i);
            if (slot.bitmap == null) {
                continue;
            }
            if (i < keepStart || i > keepEnd) {
                slot.recycleBitmap();
            } else {
                renderedCount++;
            }
        }

        while (renderedCount > MAX_RENDERED_PAGES) {
            ReflowPageSlot farthest = null;
            int farthestDistance = -1;
            for (ReflowPageSlot slot : pageSlots) {
                if (slot.bitmap == null || (slot.page >= visibleStart && slot.page <= visibleEnd)) {
                    continue;
                }
                int distance = Math.abs(slot.page - visibleCenter);
                if (distance > farthestDistance) {
                    farthest = slot;
                    farthestDistance = distance;
                }
            }
            if (farthest == null) {
                break;
            }
            farthest.recycleBitmap();
            renderedCount--;
        }
    }

    private void restoreAnchorAfterLayout(ReflowScrollAnchor anchor) {
        ReflowScrollAnchor.restoreAfterLayout(
                anchor,
                scrollView,
                pagesContainer,
                pageSlots,
                dp(ANCHOR_USER_SCROLL_TOLERANCE_DP),
                this::scheduleVisibleRender
        );
    }

    private boolean isNearVisibleRange(int page) {
        return page >= visibleStart - PAGE_PREFETCH_RADIUS && page <= visibleEnd + PAGE_PREFETCH_RADIUS;
    }

    private boolean isCurrent(int generation) {
        return !disposed && generation == renderGeneration;
    }

    private int dp(float value) {
        return Math.round(value * scrollView.getResources().getDisplayMetrics().density);
    }
}
