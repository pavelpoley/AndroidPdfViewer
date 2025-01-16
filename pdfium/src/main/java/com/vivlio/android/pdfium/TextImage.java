package com.vivlio.android.pdfium;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class TextImage implements Comparable<TextImage> {
    public final float left;
    public final float top;
    public final float right;
    public final float bottom;
    public final boolean isImage;
    public final String text;
    public final Bitmap bitmap;

    public TextImage(float left,
                     float top,
                     float right,
                     float bottom,
                     boolean isImage,
                     @Nullable String text,
                     @Nullable Bitmap bitmap
    ) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.isImage = isImage;
        this.text = text;
        this.bitmap = bitmap;
    }

    @NonNull
    @Override
    public String toString() {
        return "TextImage{" +
                "left=" + left +
                ", top=" + top +
                ", right=" + right +
                ", bottom=" + bottom +
                ", isImage=" + isImage +
                ", text='" + text + '\'' +
                ", bitmap=" + bitmap +
                '}';
    }

    @Override
    public int compareTo(TextImage o) {
        int compareLeft = Float.compare(this.left, o.left);
        if (compareLeft != 0) {
            return compareLeft;
        }
        int compareTop = Float.compare(this.top, o.top);
        if (compareTop != 0) {
            return compareTop;
        }
        int compareRight = Float.compare(this.right, o.right);
        if (compareRight != 0) {
            return compareRight;
        }
        return Float.compare(this.bottom, o.bottom);
    }

}
