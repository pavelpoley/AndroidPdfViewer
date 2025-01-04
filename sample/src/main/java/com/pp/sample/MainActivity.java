package com.pp.sample;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.pp.sample.databinding.ActivityMainBinding;
import com.pp.sample.databinding.LayoutMenuPopupTextSelectionBinding;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private ActivityMainBinding binding;

    private static final long DEBOUNCE_DELAY_MS = 300; // Delay in milliseconds
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    private PopupWindow popupWindow;
    private LayoutMenuPopupTextSelectionBinding menuBinding;


    @SuppressLint("NewApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater(), null, false);
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        PDFView pdfView = binding.pdfView;
        pdfView.fromAsset("sample.pdf")
                .scrollHandle(new DefaultScrollHandle(this))
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .defaultPage(0)
                .onSelection(this::onTextSelected)
                .spacing(10)
                .load();
        pdfView.setSelectionPaintView(binding.docSelection);
    }


    private void onTextSelected(String selectedText, RectF selectionRect) {
        if (debounceRunnable != null) {
            debounceHandler.removeCallbacks(debounceRunnable);
        }

        debounceRunnable = () -> {
            if (selectedText != null && !selectedText.isEmpty()) {
                showContextMenuWithPopupWindow(selectedText, selectionRect);
            }
        };

        debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS);
    }

    private void copyTextToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Selected Text", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show();
    }

    private void shareText(String text) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(shareIntent, "Share via"));
    }


    @Override
    protected void onDestroy() {
        binding = null;
        menuBinding = null;
        super.onDestroy();
    }


    private void showContextMenuWithPopupWindow(String selectedText, RectF rect) {
        preparePopUpMenu();
        popupWindow.showAtLocation(binding.pdfView, Gravity.NO_GRAVITY, (int) rect.centerX(), (int) rect.top);
        menuBinding.menuCopy.setOnClickListener(v -> {
            copyTextToClipboard(selectedText);
            popupWindow.dismiss();
        });
        menuBinding.menuShare.setOnClickListener(v -> {
            shareText(selectedText);
            popupWindow.dismiss();
        });
    }

    private void preparePopUpMenu() {
        if (popupWindow != null && popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
        if (menuBinding == null) {
            menuBinding = LayoutMenuPopupTextSelectionBinding.inflate(getLayoutInflater(), null, false);
            View popupView = menuBinding.getRoot();
            popupWindow = new PopupWindow(popupView,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }


    }
}