package com.vivlio.android.pdfium.util;

import androidx.annotation.NonNull;

@SuppressWarnings("unused")
public class SizeF {
    private final float width;
    private final float height;

    public SizeF(float width, float height) {
        this.width = width;
        this.height = height;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public boolean isEmpty() {
        return width == 0 && height == 0;
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof SizeF) {
            final SizeF other = (SizeF) obj;
            return width == other.width && height == other.height;
        }
        return false;
    }

    @NonNull
    @Override
    public String toString() {
        return width + "x" + height;
    }

    @Override
    public int hashCode() {
        return Float.floatToIntBits(width) ^ Float.floatToIntBits(height);
    }

    public Size toSize() {
        return new Size((int) width, (int) height);
    }
}
