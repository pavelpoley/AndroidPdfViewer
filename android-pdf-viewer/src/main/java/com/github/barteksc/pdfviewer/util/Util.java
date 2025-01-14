/*
 * Copyright (C) 2016 Bartosz Schiller.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer.util;

import android.content.Context;

import androidx.core.util.TypedValueCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class Util {
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 4;

    public static int dpToPx(Context context, int dpValue) {
        return (int) TypedValueCompat.dpToPx(dpValue, context.getResources().getDisplayMetrics());
    }

    public static float dpToPx(Context context, float dpValue) {
        return TypedValueCompat.dpToPx(dpValue, context.getResources().getDisplayMetrics());
    }

    public static <T> boolean indexExists(final List<T> list, final int index) {
        return index >= 0 && index < list.size();
    }

    public static boolean indexExists(int count, final int index) {
        return index >= 0 && index < count;
    }

    public static byte[] toByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int n;
        while (-1 != (n = inputStream.read(buffer))) {
            os.write(buffer, 0, n);
        }
        return os.toByteArray();
    }
}
