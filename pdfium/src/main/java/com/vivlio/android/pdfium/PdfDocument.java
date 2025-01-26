package com.vivlio.android.pdfium;

import android.graphics.RectF;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class PdfDocument {

    public static class Meta {
        String title;
        String author;
        String subject;
        String keywords;
        String creator;
        String producer;
        String creationDate;
        String modDate;

        public String getTitle() {
            return title;
        }

        public String getAuthor() {
            return author;
        }

        public String getSubject() {
            return subject;
        }

        public String getKeywords() {
            return keywords;
        }

        public String getCreator() {
            return creator;
        }

        public String getProducer() {
            return producer;
        }

        public String getCreationDate() {
            return creationDate;
        }

        public String getModDate() {
            return modDate;
        }
    }

    public static class Bookmark {
        private final List<Bookmark> children = new ArrayList<>();
        String title;
        long pageIdx;
        float rawXOffset;
        float rawYOffset;
        float rawZoom;
        long mNativePtr;

        public List<Bookmark> getChildren() {
            return children;
        }

        public boolean hasChildren() {
            return !children.isEmpty();
        }

        public String getTitle() {
            return title;
        }

        public long getPageIdx() {
            return pageIdx;
        }

        public float getRawXOffset() {
            return rawXOffset;
        }

        public float getRawYOffset() {
            return rawYOffset;
        }

        public float getRawZoom() {
            return rawZoom;
        }


        @Keep
        void setValue(int pageIdx, float rawXOffset, float rawYOffset, float rawZoom) {
            this.pageIdx = pageIdx;
            this.rawXOffset = rawXOffset;
            this.rawYOffset = rawYOffset;
            this.rawZoom = rawZoom;
        }

        @NonNull
        @Override
        public String toString() {
            return "Bookmark{" +
                    "title='" + title + '\'' +
                    ", pageIdx=" + pageIdx +
                    ", rawXOffset=" + rawXOffset +
                    ", rawYOffset=" + rawYOffset +
                    ", rawZoom=" + rawZoom +
                    ", childrenCount=" + children.size() +
                    '}';
        }
    }


    public static class Link {
        private final RectF bounds;
        private final Integer destPageIdx;
        private final String uri;

        public Link(RectF bounds, Integer destPageIdx, String uri) {
            this.bounds = bounds;
            this.destPageIdx = destPageIdx;
            this.uri = uri;
        }

        public Integer getDestPageIdx() {
            return destPageIdx;
        }

        public String getUri() {
            return uri;
        }

        public RectF getBounds() {
            return bounds;
        }
    }

    PdfDocument() {
        var a = new ArrayList<>();
    }

    public long mNativeDocPtr;
    ParcelFileDescriptor parcelFileDescriptor;

    public final Map<Integer, Long> mNativePagesPtr = new ArrayMap<>();
    public final Map<Integer, Long> mNativeTextPtr = new ArrayMap<>();

    public boolean hasPage(int index) {
        return mNativePagesPtr.containsKey(index);
    }

    public boolean hasText(int index) {
        return mNativeTextPtr.containsKey(index);
    }
}
