package com.github.barteksc.pdfviewer;

import android.os.CancellationSignal;
import android.os.Handler;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.github.barteksc.pdfviewer.reflow.ReflowBitmapProcessor;

import java.util.List;

final class ReflowRenderCoordinator {
    private static final int PAGE_PREFETCH_RADIUS = 1;
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
    private volatile int visibleStart;
    private volatile int visibleEnd;
    private volatile int renderGeneration;
    private volatile boolean disposed;

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
        updateVisibleRange();
        cancelFarRenderRequests();
        mainHandler.removeCallbacks(renderVisibleRunnable);
        if (hasVisibleRenderCandidate()) {
            mainHandler.post(renderVisibleRunnable);
        } else {
            mainHandler.postDelayed(renderVisibleRunnable, RENDER_DEBOUNCE_MS);
        }
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

        updateVisibleRange();

        for (int page = visibleStart; page <= visibleEnd; page++) {
            requestRenderPage(page);
        }

        int renderStart = Math.max(0, visibleStart - PAGE_PREFETCH_RADIUS);
        int renderEnd = Math.min(pageSlots.size() - 1, visibleEnd + PAGE_PREFETCH_RADIUS);
        if (cachedBitmapBytes() < options.maxCachedBitmapBytes) {
            for (int distance = 1; distance <= PAGE_PREFETCH_RADIUS; distance++) {
                requestRenderPage(visibleStart - distance);
                requestRenderPage(visibleEnd + distance);
            }
        }

        recycleFarPages(renderStart, renderEnd);
    }

    private void updateVisibleRange() {
        if (pageSlots.isEmpty()) {
            visibleStart = 0;
            visibleEnd = 0;
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
    }

    private void requestRenderPage(int page) {
        if (page < 0 || page >= pageSlots.size()) {
            return;
        }

        ReflowPageSlot slot = pageSlots.get(page);
        if (slot.hasRenderedContent() || slot.renderRequested) {
            return;
        }

        slot.markRenderRequested();
        int generation = renderGeneration;
        CancellationSignal cancellationSignal = session.renderPage(
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
                    public void onRendered(
                            int callbackPage,
                            ReflowBitmapProcessor.Result result,
                            CancellationSignal callbackCancellationSignal
                    ) {
                        handleRenderedPage(generation, callbackPage, result, callbackCancellationSignal);
                    }

                    @Override
                    public void onSkipped(int callbackPage, CancellationSignal callbackCancellationSignal) {
                        handleSkippedPage(generation, callbackPage, callbackCancellationSignal);
                    }

                    @Override
                    public void onFailed(int callbackPage, CancellationSignal callbackCancellationSignal) {
                        handleFailedPage(generation, callbackPage, callbackCancellationSignal);
                    }
                }
        );
        slot.setRenderCancellationSignal(cancellationSignal);
    }

    private boolean hasVisibleRenderCandidate() {
        for (int page = visibleStart; page <= visibleEnd && page < pageSlots.size(); page++) {
            if (page < 0) {
                continue;
            }
            ReflowPageSlot slot = pageSlots.get(page);
            if (!slot.hasRenderedContent() && !slot.renderRequested) {
                return true;
            }
        }
        return false;
    }

    private void handleRenderedPage(
            int generation,
            int page,
            ReflowBitmapProcessor.Result result,
            CancellationSignal cancellationSignal
    ) {
        if (!isCurrent(generation) || page < 0 || page >= pageSlots.size()) {
            result.recycle();
            return;
        }

        ReflowPageSlot slot = pageSlots.get(page);
        if (!slot.isRenderCancellationSignal(cancellationSignal)) {
            result.recycle();
            return;
        }
        ReflowScrollAnchor anchor = ReflowScrollAnchor.capture(pageSlots, scrollView.getScrollY());
        slot.bindResult(result);
        restoreAnchorAfterLayout(anchor);
        recycleFarPages(
                Math.max(0, visibleStart - PAGE_PREFETCH_RADIUS),
                Math.min(pageSlots.size() - 1, visibleEnd + PAGE_PREFETCH_RADIUS)
        );
    }

    private void handleSkippedPage(int generation, int page, CancellationSignal cancellationSignal) {
        if (isCurrent(generation) && page >= 0 && page < pageSlots.size()) {
            ReflowPageSlot slot = pageSlots.get(page);
            if (slot.isRenderCancellationSignal(cancellationSignal)) {
                slot.markRenderSkipped();
            }
        }
    }

    private void handleFailedPage(int generation, int page, CancellationSignal cancellationSignal) {
        if (isCurrent(generation) && page >= 0 && page < pageSlots.size()) {
            ReflowPageSlot slot = pageSlots.get(page);
            if (slot.isRenderCancellationSignal(cancellationSignal)) {
                slot.markRenderFailed();
            }
        }
    }

    private void cancelFarRenderRequests() {
        for (ReflowPageSlot slot : pageSlots) {
            if (slot.renderRequested && !isNearVisibleRange(slot.page)) {
                slot.cancelRenderRequest();
            }
        }
    }

    private void recycleFarPages(int keepStart, int keepEnd) {
        for (int i = 0; i < pageSlots.size(); i++) {
            ReflowPageSlot slot = pageSlots.get(i);
            if (!slot.hasRenderedContent()) {
                continue;
            }
            if (i < keepStart || i > keepEnd) {
                slot.recycleBitmap();
            }
        }
        trimCacheToBudget();
    }

    private void trimCacheToBudget() {
        int visibleCenter = (visibleStart + visibleEnd) / 2;
        while (cachedBitmapBytes() > options.maxCachedBitmapBytes) {
            ReflowPageSlot farthest = null;
            int farthestDistance = -1;
            for (ReflowPageSlot slot : pageSlots) {
                if (!slot.hasRenderedContent() || (slot.page >= visibleStart && slot.page <= visibleEnd)) {
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
        }
    }

    private long cachedBitmapBytes() {
        long bytes = 0L;
        for (ReflowPageSlot slot : pageSlots) {
            bytes += slot.cachedBytes();
        }
        return bytes;
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
