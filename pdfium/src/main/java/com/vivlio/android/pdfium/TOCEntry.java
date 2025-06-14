package com.vivlio.android.pdfium;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class TOCEntry implements Parcelable, java.io.Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 8254565845856584565L;
    private final String title;
    private final int pageIndex;
    private final int level;
    private final int parentIndex;
    private final float x;
    private final float y;
    private final float zoom;

    // Constructor
    public TOCEntry(String title, int pageIndex, int level,
                    int parentIndex, float x, float y, float zoom) {
        this.title = title;
        this.pageIndex = pageIndex;
        this.level = level;
        this.parentIndex = parentIndex;
        this.x = x;
        this.y = y;
        this.zoom = zoom;
    }

    protected TOCEntry(Parcel in) {
        title = in.readString();
        pageIndex = in.readInt();
        level = in.readInt();
        parentIndex = in.readInt();
        x = in.readFloat();
        y = in.readFloat();
        zoom = in.readFloat();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title);
        dest.writeInt(pageIndex);
        dest.writeInt(level);
        dest.writeInt(parentIndex);
        dest.writeFloat(x);
        dest.writeFloat(y);
        dest.writeFloat(zoom);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<TOCEntry> CREATOR = new Creator<TOCEntry>() {
        @Override
        public TOCEntry createFromParcel(Parcel in) {
            return new TOCEntry(in);
        }

        @Override
        public TOCEntry[] newArray(int size) {
            return new TOCEntry[size];
        }
    };

    public String getTitle() {
        return title;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public int getLevel() {
        return level;
    }


    public int getParentIndex() {
        return parentIndex;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getRawX() {
        return x;
    }

    public float getRawY() {
        return y;
    }

    public float getZoom() {
        return zoom;
    }

    @NonNull
    @Override
    public String toString() {
        return "TOCEntry{" +
                "title='" + title + '\'' +
                ", pageIndex=" + pageIndex +
                ", level=" + level +
                ", parentIndex=" + parentIndex +
                ", x=" + x +
                ", y=" + y +
                ", zoom=" + zoom +
                '}';
    }
}