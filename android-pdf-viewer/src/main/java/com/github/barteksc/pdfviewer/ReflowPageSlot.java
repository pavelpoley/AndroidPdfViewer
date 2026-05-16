package com.github.barteksc.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.reflow.ReflowBitmapProcessor;
import com.vivlio.android.pdfium.util.Size;

import java.util.ArrayList;
import java.util.List;

final class ReflowPageSlot {
    final int page;
    final Size pageSize;
    final FrameLayout container;
    final TextView placeholder;
    final LinearLayout.LayoutParams layoutParams;
    @Nullable
    final View pageDelimiter;
    final int pageDelimiterHeight;
    int estimatedHeight;
    int currentHeight;

    @Nullable
    ReflowBitmapProcessor.Result result;
    @Nullable
    LinearLayout tilesContainer;
    @Nullable
    private CancellationSignal renderCancellationSignal;

    boolean renderRequested;
    boolean failed;

    private final Context context;
    private final List<ImageView> tileViews = new ArrayList<>();

    private ReflowPageSlot(
            Context context,
            int page,
            Size pageSize,
            FrameLayout container,
            TextView placeholder,
            LinearLayout.LayoutParams layoutParams,
            @Nullable View pageDelimiter,
            int pageDelimiterHeight,
            int estimatedHeight
    ) {
        this.context = context;
        this.page = page;
        this.pageSize = pageSize;
        this.container = container;
        this.placeholder = placeholder;
        this.layoutParams = layoutParams;
        this.pageDelimiter = pageDelimiter;
        this.pageDelimiterHeight = pageDelimiterHeight;
        this.estimatedHeight = estimatedHeight;
        this.currentHeight = estimatedHeight;
    }

    static ReflowPageSlot create(Context context, int page, Size pageSize, ReflowRenderOptions options, int paddingPx) {
        int estimatedHeight = estimateHeight(pageSize, options);
        FrameLayout container = new FrameLayout(context);
        container.setBackgroundColor(Color.WHITE);

        TextView placeholder = new TextView(context);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setTextColor(Color.DKGRAY);
        placeholder.setTextSize(15f);
        placeholder.setText(defaultPageText(page));
        placeholder.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                estimatedHeight
        );
        if (page > 0) {
            layoutParams.topMargin = options.pageSpacingPx;
        }

        View pageDelimiter = null;
        int pageDelimiterHeight = 0;
        if (page > 0) {
            pageDelimiter = new View(context);
            pageDelimiter.setBackgroundColor(Color.rgb(238, 238, 238));
            pageDelimiterHeight = Math.max(1, Math.round(context.getResources().getDisplayMetrics().density));
        }

        ReflowPageSlot slot = new ReflowPageSlot(
                context,
                page,
                pageSize,
                container,
                placeholder,
                layoutParams,
                pageDelimiter,
                pageDelimiterHeight,
                estimatedHeight
        );
        slot.showContent(placeholder);
        return slot;
    }

    static int estimateHeight(Size pageSize, ReflowRenderOptions options) {
        if (pageSize == null || pageSize.getWidth() <= 0 || pageSize.getHeight() <= 0) {
            return Math.max(1, options.minOutputHeight);
        }
        int proportionalHeight = Math.round(options.targetWidth * (pageSize.getHeight() / (float) pageSize.getWidth()));
        return Math.max(options.minOutputHeight, proportionalHeight);
    }

    void markRenderRequested() {
        renderRequested = true;
        failed = false;
        placeholder.setText("Reflowing page " + (page + 1) + "...");
    }

    void setRenderCancellationSignal(@Nullable CancellationSignal cancellationSignal) {
        renderCancellationSignal = cancellationSignal;
    }

    boolean isRenderCancellationSignal(CancellationSignal cancellationSignal) {
        return renderCancellationSignal == cancellationSignal;
    }

    void cancelRenderRequest() {
        if (renderCancellationSignal != null && !renderCancellationSignal.isCanceled()) {
            renderCancellationSignal.cancel();
        }
        renderCancellationSignal = null;
        renderRequested = false;
    }

    void markRenderSkipped() {
        renderRequested = false;
        renderCancellationSignal = null;
        if (result == null && !failed) {
            placeholder.setText(defaultPageText(page));
        }
    }

    void markRenderFailed() {
        renderRequested = false;
        renderCancellationSignal = null;
        failed = true;
        placeholder.setText(failedPageText(page));
    }

    void bindResult(ReflowBitmapProcessor.Result newResult) {
        releaseRenderedContent();
        renderRequested = false;
        renderCancellationSignal = null;
        failed = false;
        result = newResult;

        if (tilesContainer == null) {
            tilesContainer = new LinearLayout(context);
            tilesContainer.setOrientation(LinearLayout.VERTICAL);
            tilesContainer.setBackgroundColor(Color.WHITE);
        }
        tilesContainer.removeAllViews();
        tileViews.clear();

        for (Bitmap tile : newResult.tiles) {
            ImageView tileView = new ImageView(context);
            tileView.setBackgroundColor(Color.WHITE);
            tileView.setScaleType(ImageView.ScaleType.FIT_XY);
            tileView.setImageBitmap(tile);
            tilesContainer.addView(tileView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, tile.getHeight())
            ));
            tileViews.add(tileView);
        }

        showContent(tilesContainer);
        updateHeight(newResult.totalHeight);
    }

    void resetForViewport(ReflowRenderOptions options) {
        failed = false;
        estimatedHeight = estimateHeight(pageSize, options);
        currentHeight = estimatedHeight;
        recycleBitmap();
    }

    void recycleBitmap() {
        releaseBitmap();
        placeholder.setText(failed ? failedPageText(page) : defaultPageText(page));
        showContent(placeholder);
        updateHeight(currentHeight);
    }

    void releaseBitmap() {
        cancelRenderRequest();
        releaseRenderedContent();
    }

    private void releaseRenderedContent() {
        if (result != null) {
            result.recycle();
        }
        result = null;
        for (ImageView tileView : tileViews) {
            tileView.setImageDrawable(null);
        }
        tileViews.clear();
        if (tilesContainer != null) {
            tilesContainer.removeAllViews();
        }
    }

    boolean hasRenderedContent() {
        return result != null;
    }

    long cachedBytes() {
        return result == null ? 0L : result.byteCount;
    }

    void updateHeight(int height) {
        currentHeight = Math.max(1, height);
        layoutParams.height = currentHeight;
        container.setLayoutParams(layoutParams);
    }

    private void showContent(View content) {
        container.removeAllViews();
        container.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        addPageDelimiter();
    }

    private void addPageDelimiter() {
        if (pageDelimiter == null) {
            return;
        }
        container.addView(pageDelimiter, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                pageDelimiterHeight,
                Gravity.TOP
        ));
    }

    private static String defaultPageText(int page) {
        return "Page " + (page + 1);
    }

    private static String failedPageText(int page) {
        return "Unable to reflow page " + (page + 1);
    }
}
