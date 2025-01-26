package com.pp.sample;

import androidx.annotation.NonNull;

import java.util.List;


public interface TOCItem {
    class ParentItem implements TOCItem {
        public final String title;
        public final List<ChildItem> children;
        public final int pageIndex;
        public final float rawY;
        private final float rawX;
        private boolean isExpanded;

        public ParentItem(String title, List<ChildItem> children, int pageIndex, float rawX, float rawY) {
            this.title = title;
            this.children = children;
            this.pageIndex = pageIndex;
            this.rawX = rawX;
            this.rawY = rawY;
            this.isExpanded = false;
        }

        public boolean isExpanded() {
            return isExpanded;
        }

        public void setExpanded(boolean expanded) {
            isExpanded = expanded;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public int getPageIndex() {
            return pageIndex;
        }

        @Override
        public float getRawY() {
            return rawY;
        }

        @Override
        public float getRawX() {
            return rawX;
        }

        @NonNull
        @Override
        public String toString() {
            return "ParentItem{" +
                    "title='" + title + '\'' +
                    ", pageIndex=" + pageIndex +
                    ", rawY=" + rawY +
                    ", rawX=" + rawX +
                    ", isExpanded=" + isExpanded +
                    '}';
        }
    }

    class ChildItem implements TOCItem {
        public final String title;
        public final int pageIndex;
        public final float rawY;
        private final float rawX;

        public ChildItem(String title, int pageIndex, float rawX,float rawY) {
            this.title = title;
            this.pageIndex = pageIndex;
            this.rawX = rawX;
            this.rawY = rawY;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public float getRawY() {
            return rawY;
        }

        @Override
        public float getRawX() {
            return rawX;
        }

        @Override
        public int getPageIndex() {
            return pageIndex;
        }

        @NonNull
        @Override
        public String toString() {
            return "ChildItem{" +
                    "title='" + title + '\'' +
                    ", pageIndex=" + pageIndex +
                    ", rawY=" + rawY +
                    ", rawX=" + rawX +
                    '}';
        }
    }


    String getTitle();

    int getPageIndex();

    float getRawX();
    float getRawY();

    default boolean isParent() {
        return this instanceof ParentItem;
    }

    default boolean isChild() {
        return this instanceof ChildItem;
    }


}
