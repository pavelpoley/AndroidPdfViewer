package com.pp.sample;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.vivlio.android.pdfium.PdfDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


public class BookmarkAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int PARENT_TYPE = 0;
    private static final int CHILD_TYPE = 1;
    private final List<TOCItem> tocList;

    private OnTocClicked onTocClicked;


    public BookmarkAdapter(List<PdfDocument.Bookmark> bookmarks) {
        this.tocList = bookmarks.stream().map(bookmark -> new TOCItem.ParentItem(
                bookmark.getTitle(),
                bookmark.getChildren().stream().map(child -> new TOCItem.ChildItem(
                        child.getTitle(),
                        (int) child.getPageIdx(),
                        child.getRawXOffset(), child.getRawYOffset())).collect(Collectors.toList()),
                (int) bookmark.getPageIdx(),
                bookmark.getRawXOffset(),
                bookmark.getRawYOffset()
        )).collect(Collectors.toCollection(ArrayList::new));
    }


    @Override
    public int getItemViewType(int position) {
        TOCItem item = tocList.get(position);
        return item.isParent() ? PARENT_TYPE : CHILD_TYPE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == PARENT_TYPE) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_bookmark_parent, parent, false);
            return new ParentViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_bookmark_child, parent, false);
            return new ChildViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        var toc = tocList.get(position);
        if (holder instanceof ParentViewHolder && toc.isParent()) {
            ((ParentViewHolder) holder).bind((TOCItem.ParentItem) toc);
        } else if (holder instanceof ChildViewHolder && toc.isChild()) {
            ((ChildViewHolder) holder).bind((TOCItem.ChildItem) toc);
        }
    }

    @Override
    public int getItemCount() {
        return tocList.size();
    }


    private void performClick(TOCItem bookmark) {
        if (onTocClicked != null) {
            onTocClicked.onClicked(bookmark);
        }
    }

    public void setOnTocClicked(OnTocClicked onTocClicked) {
        this.onTocClicked = onTocClicked;
    }

    private void toggleExpansion(TOCItem.ParentItem parentItem, int position) {
        if (parentItem.isExpanded()) {
            collapseItems(parentItem, position);
        } else {
            expandItems(parentItem, position);
        }
    }

    private void collapseItems(TOCItem.ParentItem parentItem, int position) {
        parentItem.setExpanded(false);
        int childCount = parentItem.children.size();
        tocList.subList(position + 1, position + 1 + childCount).clear();
        notifyItemRangeRemoved(position + 1, childCount);
    }

    private void expandItems(TOCItem.ParentItem parentItem, int position) {
        parentItem.setExpanded(true);
        tocList.addAll(position + 1, parentItem.children);
        notifyItemRangeInserted(position + 1, parentItem.children.size());
    }

    class ParentViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleTextView;
        private final ImageButton expandIcon;
        private final TextView pageText;

        public ParentViewHolder(@NonNull View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.titleTextView);
            expandIcon = itemView.findViewById(R.id.expandIcon);
            pageText = itemView.findViewById(R.id.parent_page);
        }

        public void bind(TOCItem.ParentItem bookmark) {
            titleTextView.setText(bookmark.getTitle());
            expandIcon.setVisibility(bookmark.children.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            setDrawable(bookmark.isExpanded());
            View.OnClickListener onClickListener = bookmark.children.isEmpty() ? null : v -> {
                toggleExpansion(bookmark, getAdapterPosition());
                setDrawable(bookmark.isExpanded());
            };
            expandIcon.setOnClickListener(onClickListener);
            pageText.setOnClickListener(onClickListener);
            pageText.setText(String.valueOf(bookmark.getPageIndex() + 1));
            itemView.setOnClickListener(v -> performClick(bookmark));

        }


        private void setDrawable(boolean expanded) {
            var e = ResourcesCompat.getDrawable(itemView.getResources(),
                    expanded ? R.drawable.baseline_expand_less_24
                            : R.drawable.baseline_keyboard_arrow_right_24,
                    itemView.getContext().getTheme());
            if (e != null) {
                e.setTint(
                        MaterialColors.getColor(
                                itemView.getContext(),
                                com.google.android.material.R.attr.colorOutline,
                                Color.TRANSPARENT
                        )
                );
            }
            expandIcon.setImageDrawable(e);
        }
    }

    class ChildViewHolder extends RecyclerView.ViewHolder {
        private final TextView childTitleTextView;
        private final TextView pageTextView;

        public ChildViewHolder(@NonNull View itemView) {
            super(itemView);
            childTitleTextView = itemView.findViewById(R.id.childTitleTextView);
            pageTextView = itemView.findViewById(R.id.pageTextView);
        }

        public void bind(TOCItem.ChildItem bookmark) {
            childTitleTextView.setText(bookmark.getTitle());
            pageTextView.setText(String.valueOf(bookmark.getPageIndex() + 1));
            itemView.setOnClickListener(v -> performClick(bookmark));

        }
    }


    @FunctionalInterface
    public interface OnTocClicked {
        void onClicked(TOCItem item);
    }


}
