package com.pp.sample;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.pp.sample.databinding.ActivityPdfSearchResultBinding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfSearchResultActivity extends AppCompatActivity {

    private ActivityPdfSearchResultBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityPdfSearchResultBinding.inflate(getLayoutInflater(), null, false);
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        binding.pdfSearchResultToolbar.setNavigationOnClickListener(v -> finish());
        var recyclerView = binding.pdfSearchResultRecyclerView;
        var adapter = new PdfSearchListAdapter();
        recyclerView.setAdapter(adapter);
        binding.pdfSearchResultProgress.setVisibility(View.VISIBLE);
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            var res = SearchResultsCacheManager.readSearchResultsFromFile(this);
            runOnUiThread(() -> {
                adapter.submitList(res);
                binding.pdfSearchResultProgress.setVisibility(View.GONE);
            });
        });
        executor.shutdown();

        adapter.setOnItemClickListener(item -> {
            Intent intent = new Intent();
            intent.putExtra("pageIndex", item.getPageIndex());
            intent.putExtra("xOffset", item.getxOffset());
            intent.putExtra("yOffset", item.getyOffset());
            setResult(RESULT_OK, intent);
            finish();
        });

    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}