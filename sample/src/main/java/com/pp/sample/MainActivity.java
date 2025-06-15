package com.pp.sample;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.github.barteksc.pdfviewer.util.Util;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.pp.sample.databinding.ActivityMainBinding;
import com.pp.sample.databinding.LayoutMenuPopupTextSelectionBinding;

import java.util.concurrent.Executors;

@SuppressWarnings("unused")
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private ActivityMainBinding binding;

    private static final long DEBOUNCE_DELAY_MS = 500; // Delay in milliseconds
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    private PopupWindow popupWindow;
    private LayoutMenuPopupTextSelectionBinding menuBinding;
    ActivityResultLauncher<String> launcher = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::loadPdf);


    private int currentSearchItemIndex = 0;
    private int totalSearchItems = 0;

    private SearchView searchView;
    private MenuItem searchMenuItem;

    private BottomSheetBehavior<View> bottomSheetBehavior;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater(), null, false);
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return ViewCompat.onApplyWindowInsets(v, insets);
        });

        PDFView pdfView = binding.pdfView;
        loadPdf(null);
        bottomSheetBehavior = BottomSheetBehavior.from(binding.persistentBottomSheet);
        bottomSheetBehavior.setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * .5f));
        bottomSheetBehavior.setHideable(true);
        bottomSheetBehavior.setDraggable(true);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        binding.tableOfContentRv.setLayoutManager(layoutManager);
        binding.tableOfContentRv.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));


        setSupportActionBar(binding.mainToolbar);
        binding.navigateNextBtn.setOnClickListener(v -> {
            if (pdfView.navigateToNextSearchItem()) {
                currentSearchItemIndex++;
                updateSearchNavigation();
            }
        });
        binding.navigatePrevBtn.setOnClickListener(v -> {
            if (pdfView.navigateToPreviousSearchItem()) {
                currentSearchItemIndex--;
                updateSearchNavigation();
            }
        });

        binding.showAllSearchResult.setOnClickListener(v -> {
            var e = Executors.newSingleThreadExecutor();
            v.setEnabled(false);
            e.execute(() -> {
                SearchResultsCacheManager.saveSearchResultsToFile(this, pdfView);
                runOnUiThread(() -> {
                    v.setEnabled(true);
                    var intent = new Intent(this, PdfSearchResultActivity.class);
                    intent.putExtra("data", "serialized");
                    startActivityForResult(intent, 7);
                    searchView.clearFocus();
                });
                e.shutdown();
            });
        });


        pdfView.setSelectionPaintView(binding.docSelection);
        binding.docSelection
                .modifySelectionUi()
                .updateSelectionPaint(paint -> paint.setColor(Color.argb(100, 0, 0, 255)))
                .updateEndDragHandleDrawable(drawable -> drawable.setColorFilter(Color.YELLOW, android.graphics.PorterDuff.Mode.SRC_IN))
                .updateStartDragHandleDrawable(drawable -> {

                })
                .apply();

        binding.closeSearchBtn.setOnClickListener(v -> resetAndCloseSearchView());
        binding.openFile.setOnClickListener(v -> launcher.launch("application/pdf"));
        binding.closeTableOfContent.setOnClickListener(v ->
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN));
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 7 && resultCode == RESULT_OK && data != null) {
            long recordId = data.getLongExtra("recordId", 0L);
            int recordIndex = data.getIntExtra("recordIndex", 0);
            if (binding.pdfView.navigateToSearchItem(recordId, true)) {
                this.currentSearchItemIndex = recordIndex;
                updateSearchNavigation();
            }
        }

    }

    private void updateSearchNavigation() {
        binding.searchMatchedTextView.setText(getString(
                R.string.search_navigation_placeholder,
                totalSearchItems == 0 ? 0 : currentSearchItemIndex + 1,
                totalSearchItems
        ));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_text_selection_popup, menu);
        MenuItem item = menu.findItem(R.id.search_menu_item);
        View actionView = item.getActionView();
        if (actionView instanceof SearchView) {
            setupSearchView((SearchView) actionView, item);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_table_of_content) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            if (binding.tableOfContentRv.getAdapter() == null) {
                var toc = binding.pdfView.getTableOfContents();
                BookmarkAdapter adapter = new BookmarkAdapter(toc);
                adapter.setOnTocClicked(tocItem -> {
                    binding.pdfView.jumpToWithOffset(tocItem.getPageIndex(),
                            tocItem.getRawX(),
                            tocItem.getRawY());
                    Log.d(TAG, "onOptionsItemSelected: " + tocItem);
                });
                binding.tableOfContentRv.setAdapter(adapter);
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void setupSearchView(SearchView searchView, MenuItem menuItem) {
        this.searchView = searchView;
        this.searchMenuItem = menuItem;
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                showSearchNavigation();
                binding.pdfView.search(query);
                if (TextUtils.isEmpty(query)) {
                    hideSearchNavigation();
                }
                updateSearchNavigation();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });
        searchView.setOnCloseListener(() -> {
            binding.pdfView.clearSearch();
            hideSearchNavigation();
            return false;
        });
    }

    private void hideSearchNavigation() {
        binding.searchResultNavigationLayout.setVisibility(View.GONE);
    }

    private void showSearchNavigation() {
        currentSearchItemIndex = 0;
        totalSearchItems = 0;
        binding.searchResultNavigationLayout.setVisibility(View.VISIBLE);
    }

    private void resetAndCloseSearchView() {
        if (searchView == null || searchMenuItem == null) return;
        searchView.setQuery("", false);
        searchView.clearFocus();
        searchMenuItem.collapseActionView();
    }

    private boolean mVisible = true;

    private void loadPdf(Uri uri) {
        hidePopupMenu();
        if (this.binding.pdfView.getHasSelection()) {
            this.binding.pdfView.clearSelection();
        }
        resetAndCloseSearchView();
        PDFView.Configurator configurator = (uri == null ?
                binding.pdfView.fromAsset("sample.pdf")
                : binding.pdfView.fromUri(uri)
        ).scrollHandle(new DefaultScrollHandle(this))
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableDoubleTap(true)
                .enableMergedSelectionLines(true)
                .setLineThreshold(15)
                .onLoad(nbPages -> {
                    this.binding.pdfView
                            .appendHighlight(1, Util.packIntegers(0, 8));
                })
                .onHighlightClick((string, pageIndex, selectionId, rectF) -> {
                    Log.d(TAG, "loadPdf: " + selectionId);
                })
                .setVerticalExpandPercent(.25f)
                .defaultPage(0)
                .onPageScroll((page, positionOffset) -> hidePopupMenu())
                .spacing(10)
                .onTextSelection(this::onTextSelected)
                .onSelectionInProgress(this::hidePopupMenu)
                .onSearchMatch((page, totalMatched, word) -> {
                    totalSearchItems += totalMatched;
                    updateSearchNavigation();
                })
                .onTap(e -> {
                    WindowInsetsControllerCompat winInsets =
                            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                    if (mVisible) {
                        mVisible = false;
                        winInsets.hide(WindowInsetsCompat.Type.systemBars());
                    } else {
                        winInsets.show(WindowInsetsCompat.Type.systemBars());
                        mVisible = true;
                    }

                    hidePopupMenu();
                    return true;
                });
        configurator.load();
    }

    private void highlightArea() {
        this.binding.pdfView.clearSelection();
    }

    private void onTextSelected(String selectedText, int page, long id, RectF selectionRect, float rawX, float rawY) {
        hidePopupMenu();
        if (debounceRunnable != null) {
            debounceHandler.removeCallbacks(debounceRunnable);
        }

        debounceRunnable = () -> {
            if (selectedText != null && !selectedText.isBlank() && binding.pdfView.getHasSelection()) {
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
        SearchResultsCacheManager
                .deleteSearchResultsCache(this);
        super.onDestroy();
    }

    private void showContextMenuWithPopupWindow(final String selectedText, final RectF rect) {
        preparePopUpMenu();
        if (!binding.pdfView.getHasSelection()) return;
        popupWindow.showAtLocation(binding.pdfView, Gravity.NO_GRAVITY, (int) rect.centerX() - menuBinding.getRoot().getWidth() / 2, (int) rect.top);
        menuBinding.menuCopy.setOnClickListener(v -> {
            binding.pdfView.clearSelection();
            copyTextToClipboard(selectedText);
            popupWindow.dismiss();
        });
        menuBinding.menuShare.setOnClickListener(v -> {
            binding.pdfView.clearSelection();
            shareText(selectedText);
            popupWindow.dismiss();
        });
    }

    private void preparePopUpMenu() {
        hidePopupMenu();
        if (popupWindow == null) {
            menuBinding = LayoutMenuPopupTextSelectionBinding.inflate(getLayoutInflater(), null, false);
            View popupView = menuBinding.getRoot();
            popupWindow = new PopupWindow(popupView,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }


    }

    private void hidePopupMenu() {
        if (popupWindow != null && popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
    }
}