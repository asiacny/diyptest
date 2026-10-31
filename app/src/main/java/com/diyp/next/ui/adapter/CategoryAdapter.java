package com.diyp.next.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.diyp.next.R;
import com.diyp.next.data.model.ChannelGroup;
import java.util.ArrayList;
import java.util.List;

public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {
    private List<ChannelGroup> groups = new ArrayList<>();
    private int selectedPosition = 0;
    private OnCategoryClickListener listener;
    private RecyclerView recyclerView;

    public interface OnCategoryClickListener {
        void onCategorySelected(int position, ChannelGroup group);
    }

    public void setListener(OnCategoryClickListener listener) {
        this.listener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.recyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        this.recyclerView = null;
    }

    public void notifyItemChangedSafe(int position, Object payload) {
        if (position < 0 || position >= groups.size()) return;
        if (recyclerView != null && recyclerView.isComputingLayout()) {
            recyclerView.post(() -> {
                if (position >= 0 && position < groups.size()) {
                    notifyItemChanged(position, payload);
                }
            });
        } else {
            notifyItemChanged(position, payload);
        }
    }

    public void notifyDataSetChangedSafe() {
        if (recyclerView != null && recyclerView.isComputingLayout()) {
            recyclerView.post(this::notifyDataSetChanged);
        } else {
            notifyDataSetChanged();
        }
    }

    public void setData(List<ChannelGroup> groups) {
        this.groups = groups != null ? groups : new ArrayList<>();
        notifyDataSetChangedSafe();
    }

    public void setSelectedPosition(int position) {
        if (position < 0 || position >= groups.size() || position == selectedPosition) {
            return;
        }
        int prev = selectedPosition;
        selectedPosition = position;
        if (prev >= 0 && prev < groups.size()) {
            notifyItemChangedSafe(prev, "selection");
        }
        if (selectedPosition >= 0 && selectedPosition < groups.size()) {
            notifyItemChangedSafe(selectedPosition, "selection");
        }
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_category, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            holder.itemView.setSelected(position == selectedPosition);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChannelGroup group = groups.get(position);
        holder.tvName.setText(group.getName());
        holder.itemView.setSelected(position == selectedPosition);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos >= 0 && pos < groups.size()) {
                setSelectedPosition(pos);
                if (listener != null) {
                    listener.onCategorySelected(pos, groups.get(pos));
                }
            }
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pos >= 0 && pos < groups.size()) {
                    if (pos != selectedPosition) {
                        int prev = selectedPosition;
                        selectedPosition = pos;
                        if (prev >= 0 && prev < groups.size()) {
                            notifyItemChangedSafe(prev, "selection");
                        }
                        notifyItemChangedSafe(pos, "selection");
                        if (listener != null) {
                            listener.onCategorySelected(pos, groups.get(pos));
                        }
                    }
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_category_name);
        }
    }
}
