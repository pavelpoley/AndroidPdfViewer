package com.github.barteksc.pdfviewer;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.source.AssetSource;
import com.github.barteksc.pdfviewer.source.ByteArraySource;
import com.github.barteksc.pdfviewer.source.DocumentSource;
import com.github.barteksc.pdfviewer.source.FileSource;
import com.github.barteksc.pdfviewer.source.InputStreamSource;
import com.github.barteksc.pdfviewer.source.UriSource;
import com.vivlio.android.pdfium.util.Size;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Experimental bitmap-based PDF reading mode.
 * <p>
 * This view lazily renders PDF pages, reflows their bitmap text, and stacks
 * the results in a continuous vertical scroll.
 */
@SuppressWarnings("unused")
public class PDFReflowView extends ScrollView {

    private static final String TAG = PDFReflowView.class.getSimpleName();
    private static final float DEFAULT_SOURCE_SCALE = 1.15f;
    private static final float DEFAULT_TEXT_SIZE_DP = 15f;
    private static final int DEFAULT_MAX_SOURCE_WIDTH = 1200;
    private static final int DEFAULT_MAX_SOURCE_PIXELS = 1_600_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinearLayout pagesContainer;
    private final List<ReflowPageSlot> pageSlots = new ArrayList<>();

    @Nullable
    private Configurator waitingDocumentConfigurator;
    @Nullable
    private TextView statusView;
    @Nullable
    private ReflowDocumentSession documentSession;
    @Nullable
    private ReflowRenderCoordinator renderCoordinator;
    @Nullable
    private ReflowLoadConfig currentConfig;

    private volatile int loadGeneration = 0;

    public PDFReflowView(Context context) {
        this(context, null);
    }

    public PDFReflowView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PDFReflowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFillViewport(true);
        setBackgroundColor(Color.WHITE);
        pagesContainer = new LinearLayout(context);
        pagesContainer.setOrientation(LinearLayout.VERTICAL);
        pagesContainer.setBackgroundColor(Color.WHITE);
        addView(pagesContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && waitingDocumentConfigurator != null) {
            Configurator configurator = waitingDocumentConfigurator;
            waitingDocumentConfigurator = null;
            load(configurator);
        } else if ((w != oldw || h != oldh) && !pageSlots.isEmpty()) {
            resetRenderedPagesForNewViewport();
            scheduleVisibleRender();
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        scheduleVisibleRender();
    }

    @Override
    protected void onDetachedFromWindow() {
        recycle();
        super.onDetachedFromWindow();
    }

    public void recycle() {
        waitingDocumentConfigurator = null;
        disposeCurrentLoad();
        pagesContainer.removeAllViews();
        pageSlots.clear();
        removeStatus();
    }

    public Configurator fromAsset(String assetName) {
        return new Configurator(new AssetSource(assetName));
    }

    public Configurator fromFile(File file) {
        return new Configurator(new FileSource(file));
    }

    public Configurator fromUri(Uri uri) {
        return new Configurator(new UriSource(uri));
    }

    public Configurator fromBytes(byte[] bytes) {
        return new Configurator(new ByteArraySource(bytes));
    }

    public Configurator fromStream(InputStream stream) {
        return new Configurator(new InputStreamSource(stream));
    }

    public Configurator fromSource(DocumentSource documentSource) {
        return new Configurator(documentSource);
    }

    private void load(@NonNull Configurator configurator) {
        if (getWidth() == 0) {
            waitingDocumentConfigurator = configurator.copy();
            return;
        }

        disposeCurrentLoad();
        pagesContainer.removeAllViews();
        pageSlots.clear();
        scrollTo(0, 0);

        int generation = ++loadGeneration;
        ReflowLoadConfig loadConfig = configurator.toLoadConfig();
        ReflowRenderOptions options = createRenderOptions(loadConfig);

        showStatus("Opening PDF...");
        ReflowDocumentSession session = new ReflowDocumentSession(
                getContext().getApplicationContext(),
                mainHandler,
                loadConfig
        );
        documentSession = session;
        session.open(new ReflowDocumentSession.OpenCallback() {
            @Override
            public void onOpened(ReflowDocumentSession openedSession, Size[] pageSizes) {
                setupDocument(loadConfig, options, generation, openedSession, pageSizes);
            }

            @Override
            public void onError(Throwable throwable) {
                postError(loadConfig, generation, throwable);
            }

            @Override
            public boolean isCancelled() {
                return generation != loadGeneration || Thread.currentThread().isInterrupted();
            }
        });
    }

    private ReflowRenderOptions createRenderOptions(ReflowLoadConfig loadConfig) {
        int targetWidth = getPageWidth();
        int minOutputHeight = Math.max(1, getHeight() / 2);
        return new ReflowRenderOptions(
                targetWidth,
                minOutputHeight,
                dp(loadConfig.pageSpacingDp),
                Math.max(1, dp(loadConfig.textSizeDp)),
                loadConfig.sourceScale,
                loadConfig.maxSourceWidthPx,
                loadConfig.maxSourcePixels
        );
    }

    private void setupDocument(
            ReflowLoadConfig loadConfig,
            ReflowRenderOptions options,
            int generation,
            ReflowDocumentSession session,
            Size[] pageSizes
    ) {
        if (generation != loadGeneration) {
            session.close();
            return;
        }

        removeStatus();
        currentConfig = loadConfig;
        pageSlots.clear();
        pagesContainer.removeAllViews();

        for (int page = 0; page < pageSizes.length; page++) {
            ReflowPageSlot slot = ReflowPageSlot.create(getContext(), page, pageSizes[page], options, dp(16));
            pageSlots.add(slot);
            pagesContainer.addView(slot.container, slot.layoutParams);
        }

        renderCoordinator = new ReflowRenderCoordinator(
                mainHandler,
                this,
                pagesContainer,
                pageSlots,
                session,
                loadConfig,
                options
        );

        if (loadConfig.onLoadCompleteListener != null) {
            loadConfig.onLoadCompleteListener.loadComplete(pageSizes.length);
        }

        pagesContainer.post(this::scheduleVisibleRender);
    }

    private void scheduleVisibleRender() {
        if (renderCoordinator != null) {
            renderCoordinator.scheduleVisibleRender();
        }
    }

    private void resetRenderedPagesForNewViewport() {
        ReflowLoadConfig loadConfig = currentConfig;
        ReflowRenderCoordinator coordinator = renderCoordinator;
        if (loadConfig == null || coordinator == null) {
            return;
        }
        coordinator.resetForViewport(createRenderOptions(loadConfig));
    }

    private int getPageWidth() {
        return Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
    }

    private void postError(ReflowLoadConfig loadConfig, int generation, Throwable throwable) {
        mainHandler.post(() -> {
            if (generation != loadGeneration) {
                return;
            }
            showStatus("Unable to reflow PDF");
            if (loadConfig.onErrorListener != null) {
                loadConfig.onErrorListener.onError(throwable);
            } else {
                Log.e(TAG, "Unable to reflow PDF", throwable);
            }
        });
    }

    private void showStatus(String message) {
        if (statusView == null) {
            statusView = new TextView(getContext());
            statusView.setGravity(Gravity.CENTER);
            statusView.setTextColor(Color.DKGRAY);
            statusView.setTextSize(16f);
            int padding = dp(24);
            statusView.setPadding(padding, padding, padding, padding);
        }
        statusView.setText(message);
        if (statusView.getParent() == null && pagesContainer.getChildCount() == 0) {
            pagesContainer.addView(statusView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
    }

    private void removeStatus() {
        if (statusView != null && statusView.getParent() == pagesContainer) {
            pagesContainer.removeView(statusView);
        }
    }

    private void disposeCurrentLoad() {
        loadGeneration++;

        if (renderCoordinator != null) {
            renderCoordinator.dispose();
            renderCoordinator = null;
        }

        ReflowDocumentSession session = documentSession;
        documentSession = null;
        currentConfig = null;

        for (ReflowPageSlot slot : pageSlots) {
            slot.releaseBitmap();
        }

        if (session != null) {
            session.close();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public interface OnLoadCompleteListener {
        void loadComplete(int pagesCount);
    }

    public interface OnErrorListener {
        void onError(Throwable throwable);
    }

    public class Configurator {

        private final DocumentSource documentSource;

        @Nullable
        private String password;
        @Nullable
        private OnLoadCompleteListener onLoadCompleteListener;
        @Nullable
        private OnErrorListener onErrorListener;

        private boolean annotationRendering = false;
        private int pageSpacingDp = 12;
        private float textSizeDp = DEFAULT_TEXT_SIZE_DP;
        private float sourceScale = DEFAULT_SOURCE_SCALE;
        private int maxSourceWidthPx = DEFAULT_MAX_SOURCE_WIDTH;
        private int maxSourcePixels = DEFAULT_MAX_SOURCE_PIXELS;

        private Configurator(DocumentSource documentSource) {
            this.documentSource = documentSource;
        }

        public Configurator password(@Nullable String password) {
            this.password = password;
            return this;
        }

        public Configurator enableAnnotationRendering(boolean annotationRendering) {
            this.annotationRendering = annotationRendering;
            return this;
        }

        public Configurator spacing(int spacingDp) {
            this.pageSpacingDp = Math.max(0, spacingDp);
            return this;
        }

        public Configurator textSizeDp(float textSizeDp) {
            if (textSizeDp > 0f) {
                this.textSizeDp = textSizeDp;
            }
            return this;
        }

        public Configurator sourceScale(float sourceScale) {
            if (sourceScale >= 1f) {
                this.sourceScale = sourceScale;
            }
            return this;
        }

        public Configurator maxSourceWidth(int maxSourceWidthPx) {
            if (maxSourceWidthPx > 0) {
                this.maxSourceWidthPx = maxSourceWidthPx;
            }
            return this;
        }

        public Configurator maxSourcePixels(int maxSourcePixels) {
            if (maxSourcePixels > 0) {
                this.maxSourcePixels = maxSourcePixels;
            }
            return this;
        }

        public Configurator onLoad(OnLoadCompleteListener onLoadCompleteListener) {
            this.onLoadCompleteListener = onLoadCompleteListener;
            return this;
        }

        public Configurator onError(OnErrorListener onErrorListener) {
            this.onErrorListener = onErrorListener;
            return this;
        }

        public void load() {
            PDFReflowView.this.load(this);
        }

        private Configurator copy() {
            Configurator copy = new Configurator(documentSource);
            copy.password = password;
            copy.onLoadCompleteListener = onLoadCompleteListener;
            copy.onErrorListener = onErrorListener;
            copy.annotationRendering = annotationRendering;
            copy.pageSpacingDp = pageSpacingDp;
            copy.textSizeDp = textSizeDp;
            copy.sourceScale = sourceScale;
            copy.maxSourceWidthPx = maxSourceWidthPx;
            copy.maxSourcePixels = maxSourcePixels;
            return copy;
        }

        private ReflowLoadConfig toLoadConfig() {
            return new ReflowLoadConfig(
                    documentSource,
                    password,
                    onLoadCompleteListener,
                    onErrorListener,
                    annotationRendering,
                    pageSpacingDp,
                    textSizeDp,
                    sourceScale,
                    maxSourceWidthPx,
                    maxSourcePixels
            );
        }
    }
}
