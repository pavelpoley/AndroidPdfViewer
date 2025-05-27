package com.pp.sample;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.github.barteksc.pdfviewer.model.SentencedSearchResult;
import com.pp.sample.databinding.ListItemPdfSearchResultBinding;

public class PdfSearchListAdapter extends ListAdapter<SentencedSearchResult, PdfSearchListAdapter.PdfSearchResultViewHolder> {
    private static final DiffUtil.ItemCallback<SentencedSearchResult> DIFF_CALLBACK = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull SentencedSearchResult oldItem,
                                       @NonNull SentencedSearchResult newItem) {
            return oldItem == newItem; // reference checking
        }

        @Override
        public boolean areContentsTheSame(@NonNull SentencedSearchResult oldItem,
                                          @NonNull SentencedSearchResult newItem) {
            return oldItem.equals(newItem);
        }
    };

    private OnItemClickListener onItemClickListener;

    public PdfSearchListAdapter() {
        super(DIFF_CALLBACK);
    }

    public void setOnItemClickListener(OnItemClickListener onItemClickListener) {
        this.onItemClickListener = onItemClickListener;
    }

    @NonNull
    @Override
    public PdfSearchResultViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PdfSearchResultViewHolder(
                ListItemPdfSearchResultBinding.inflate(
                        LayoutInflater.from(parent.getContext()),
                        parent,
                        false
                )
        );
    }

    @Override
    public void onBindViewHolder(@NonNull PdfSearchResultViewHolder holder, int position) {
        SentencedSearchResult item = getItem(position);
        holder.bind(item, onItemClickListener);
    }

    @Override
    public void onViewRecycled(@NonNull PdfSearchResultViewHolder holder) {
        super.onViewRecycled(holder);
        holder.unbind();
    }


    public static class PdfSearchResultViewHolder extends RecyclerView.ViewHolder {

        private final ListItemPdfSearchResultBinding binding;

        private OnItemClickListener onItemClickListener;
        private SentencedSearchResult model;

        public PdfSearchResultViewHolder(@NonNull ListItemPdfSearchResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.getRoot().setOnClickListener(v -> {
                if (onItemClickListener != null && model != null) {
                    onItemClickListener.onItemClick(model);
                }
            });
        }

        public void bind(
                SentencedSearchResult model,
                OnItemClickListener onItemClickListener
        ) {
            this.onItemClickListener = onItemClickListener;
            this.model = model;
            binding.listItemPdfSearchResultText.setText(model.getSpannedText(Color.YELLOW));
            binding.listItemPdfSearchResultPage.setText(binding.getRoot().getContext().getString(R.string.page_placeholder, model.getPageIndex() + 1));
        }

        public void unbind() {
            this.onItemClickListener = null;
            this.model = null;
            binding.listItemPdfSearchResultText.setText("");
            binding.listItemPdfSearchResultPage.setText("");
        }

    }

    public interface OnItemClickListener {
        void onItemClick(SentencedSearchResult item);
    }
}
