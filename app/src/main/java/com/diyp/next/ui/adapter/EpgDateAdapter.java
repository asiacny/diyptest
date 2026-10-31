package com.diyp.next.ui.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.diyp.next.R;
import java.util.ArrayList;
import java.util.List;

public class EpgDateAdapter extends RecyclerView.Adapter<EpgDateAdapter.ViewHolder> {
    public static class DateModel {
        public final String label;
        public final String dateStr;
        public final int dayOffset;

        public DateModel(String label, String dateStr, int dayOffset) {
            this.label = label;
            this.dateStr = dateStr;
            this.dayOffset = dayOffset;
        }
    }

    public interface OnDateSelectedListener {
        void onDateSelected(int position, DateModel date);
    }

    private final List<DateModel> items = new ArrayList<>();
    private int selectedPosition = 0;
    private OnDateSelectedListener listener;
    private RecyclerView recyclerView;

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

    public void notifyDataSetChangedSafe() {
        if (recyclerView != null && recyclerView.isComputingLayout()) {
            recyclerView.post(this::notifyDataSetChanged);
        } else {
            notifyDataSetChanged();
        }
    }

    public void notifyItemChangedSafe(int position) {
        if (position < 0 || position >= items.size()) return;
        if (recyclerView != null && recyclerView.isComputingLayout()) {
            recyclerView.post(() -> notifyItemChanged(position));
        } else {
            notifyItemChanged(position);
        }
    }

    public void setItems(List<DateModel> items, int defaultSelectIndex) {
        this.items.clear();
        if (items != null) {
            this.items.addAll(items);
        }
        this.selectedPosition = defaultSelectIndex;
        notifyDataSetChangedSafe();
    }

    public void setListener(OnDateSelectedListener listener) {
        this.listener = listener;
    }

    public void setSelectedPosition(int position) {
        if (position >= 0 && position < items.size() && position != selectedPosition) {
            int oldPos = selectedPosition;
            selectedPosition = position;
            notifyItemChangedSafe(oldPos);
            notifyItemChangedSafe(selectedPosition);
        }
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public DateModel getSelectedItem() {
        if (selectedPosition >= 0 && selectedPosition < items.size()) {
            return items.get(selectedPosition);
        }
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_epg_date, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DateModel item = items.get(position);
        holder.tvDate.setText(item.label);
        boolean isSelected = (position == selectedPosition);
        holder.itemView.setSelected(isSelected);
        holder.tvDate.setTextColor(isSelected ? holder.itemView.getContext().getResources().getColor(R.color.colorAccent) : Color.WHITE);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < items.size()) {
                if (pos != selectedPosition) {
                    int oldPos = selectedPosition;
                    selectedPosition = pos;
                    notifyItemChangedSafe(oldPos);
                    notifyItemChangedSafe(selectedPosition);
                    if (listener != null) {
                        listener.onDateSelected(selectedPosition, item);
                    }
                }
            }
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pos < items.size() && pos != selectedPosition) {
                    int oldPos = selectedPosition;
                    selectedPosition = pos;
                    notifyItemChangedSafe(oldPos);
                    notifyItemChangedSafe(selectedPosition);
                    if (listener != null) {
                        listener.onDateSelected(selectedPosition, item);
                    }
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDate;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tv_date_label);
        }
    }
}
