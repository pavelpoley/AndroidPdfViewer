/**
 * Copyright 2017 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer.listener;

import android.graphics.RectF;
import android.view.MotionEvent;

import com.github.barteksc.pdfviewer.link.LinkHandler;
import com.github.barteksc.pdfviewer.model.LinkTapEvent;

public class Callbacks {

    /**
     * Call back object to call when the PDF is loaded
     */
    private OnLoadCompleteListener onLoadCompleteListener;

    /**
     * Call back object to call when document loading error occurs
     */
    private OnErrorListener onErrorListener;

    /**
     * Call back object to call when the page load error occurs
     */
    private OnPageErrorListener onPageErrorListener;

    /**
     * Call back object to call when the document is initially rendered
     */
    private OnRenderListener onRenderListener;

    /**
     * Call back object to call when the page has changed
     */
    private OnPageChangeListener onPageChangeListener;

    /**
     * Call back object to call when the page is scrolled
     */
    private OnPageScrollListener onPageScrollListener;

    /**
     * Call back object to call when the above layer is to drawn
     */
    private OnDrawListener onDrawListener;

    private OnDrawListener onDrawAllListener;

    /**
     * Call back object to call when the user does a tap gesture
     */
    private OnTapListener onTapListener;

    /**
     * Call back object to call when the user does a long tap gesture
     */
    private OnLongPressListener onLongPressListener;

    /**
     * Call back object to call when clicking link
     */
    private LinkHandler linkHandler;

    /**
     * Call back to call when user zoom is finished
     */
    private OnScaleListener onScaleListener;

    /**
     * Call back to call when user select some text
     */
    private Runnable onSelectionListener;

    private OnSelectionListener onSelectionEndedListener;
    private OnTextSelectionListener onTextSelectionListener;

    /**
     * Call back to call when user search text
     */
    private OnSearchBeginListener onSearchBeginListener;

    private OnSearchMatchListener onSearchMatchListener;

    private OnSearchEndListener onSearchEndListener;

    private OnHighlightClickListener onHighlightClickListener;


    public void setOnLoadComplete(OnLoadCompleteListener onLoadCompleteListener) {
        this.onLoadCompleteListener = onLoadCompleteListener;
    }

    public void callOnLoadComplete(int pagesCount) {
        if (onLoadCompleteListener != null) {
            onLoadCompleteListener.loadComplete(pagesCount);
        }
    }

    public void setOnError(OnErrorListener onErrorListener) {
        this.onErrorListener = onErrorListener;
    }

    public OnErrorListener getOnError() {
        return onErrorListener;
    }

    public void setOnPageError(OnPageErrorListener onPageErrorListener) {
        this.onPageErrorListener = onPageErrorListener;
    }

    public boolean callOnPageError(int page, Throwable error) {
        if (onPageErrorListener != null) {
            onPageErrorListener.onPageError(page, error);
            return true;
        }
        return false;
    }

    public void setOnRender(OnRenderListener onRenderListener) {
        this.onRenderListener = onRenderListener;
    }

    public void callOnRender(int pagesCount) {
        if (onRenderListener != null) {
            onRenderListener.onInitiallyRendered(pagesCount);
        }
    }

    public void setOnPageChange(OnPageChangeListener onPageChangeListener) {
        this.onPageChangeListener = onPageChangeListener;
    }

    public void callOnPageChange(int page, int pagesCount) {
        if (onPageChangeListener != null) {
            onPageChangeListener.onPageChanged(page, pagesCount);
        }
    }

    public void setOnPageScroll(OnPageScrollListener onPageScrollListener) {
        this.onPageScrollListener = onPageScrollListener;
    }

    public void callOnPageScroll(int currentPage, float offset) {
        if (onPageScrollListener != null) {
            onPageScrollListener.onPageScrolled(currentPage, offset);
        }
    }

    public void setOnDraw(OnDrawListener onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public OnDrawListener getOnDraw() {
        return onDrawListener;
    }

    public void setOnDrawAll(OnDrawListener onDrawAllListener) {
        this.onDrawAllListener = onDrawAllListener;
    }

    public OnDrawListener getOnDrawAll() {
        return onDrawAllListener;
    }

    public void setOnTap(OnTapListener onTapListener) {
        this.onTapListener = onTapListener;
    }

    public boolean callOnTap(MotionEvent event) {
        return onTapListener != null && onTapListener.onTap(event);
    }

    public void setOnLongPress(OnLongPressListener onLongPressListener) {
        this.onLongPressListener = onLongPressListener;
    }

    public void callOnLongPress(MotionEvent event) {
        if (onLongPressListener != null) {
            onLongPressListener.onLongPress(event);
        }
    }

    public void setLinkHandler(LinkHandler linkHandler) {
        this.linkHandler = linkHandler;
    }

    public void setOnHighlightClickListener(OnHighlightClickListener onHighlightClickListener) {
        this.onHighlightClickListener = onHighlightClickListener;
    }

    public void callLinkHandler(LinkTapEvent event) {
        if (linkHandler != null) {
            linkHandler.handleLinkEvent(event);
        }
    }

    public void setOnScale(OnScaleListener onScaleListener) {
        this.onScaleListener = onScaleListener;
    }

    public void callOnScale(float zoomLevel) {
        if (onScaleListener != null) {
            onScaleListener.onScale(zoomLevel);
        }
    }

    public void setOnSelection(Runnable onSelectionListener) {
        this.onSelectionListener = onSelectionListener;
    }

    public void setOnSelectionEndedListener(OnSelectionListener onSelectionEndedListener) {
        this.onSelectionEndedListener = onSelectionEndedListener;
    }

    public void setOnTextSelectionEndedListener(OnTextSelectionListener onTextSelectionListener) {
        this.onTextSelectionListener = onTextSelectionListener;
    }

    public void callIsTextSelectionInProgress() {
        if (onSelectionListener != null) {
            onSelectionListener.run();
        }
    }

    public boolean hasTextSelectionListener() {
        return this.onTextSelectionListener != null || this.onSelectionListener != null;
    }

    public void callOnSelectionEnded(String text, int page, long recordId, RectF rectF, float rawX, float rawY) {
        if (onSelectionEndedListener != null) {
            onSelectionEndedListener.onSelection(text, rectF);
        }

        if (this.onTextSelectionListener != null) {
            this.onTextSelectionListener.onSelection(text, page, recordId, rectF, rawX, rawY);
        }
    }

    public void setOnSearchBegin(OnSearchBeginListener onSearchBeginListener) {
        this.onSearchBeginListener = onSearchBeginListener;
    }

    public void callOnSearchBegin() {
        if (onSearchBeginListener != null) {
            onSearchBeginListener.onSearchBegin();
        }
    }

    public void setOnSearchEnd(OnSearchEndListener onSearchEndListener) {
        this.onSearchEndListener = onSearchEndListener;
    }

    public void callOnSearchEnd() {
        if (onSearchEndListener != null) {
            onSearchEndListener.onSearchEnd();
        }
    }

    public void setOnSearchMatch(OnSearchMatchListener onSearchMatchListener) {
        this.onSearchMatchListener = onSearchMatchListener;
    }

    public void callOnSearchMatch(int page, int totalMatched, String word) {
        if (onSearchMatchListener != null) {
            onSearchMatchListener.onSearchMatch(page, totalMatched, word);
        }
    }

    public void callOnHighlightClick(String string, int page, long selectionId, RectF rectF) {
        if (this.onHighlightClickListener != null) {
            this.onHighlightClickListener.onClick(string, page, selectionId, rectF);
        }
    }
}
