package com.diyp.next.ui.adapter;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.diyp.next.R;
import com.diyp.next.data.model.EpgItem;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class EpgAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public static final int TYPE_HEADER = 0;
    public static final int TYPE_ITEM = 1;

    public static class EpgRow {
        public final int type;
        public final EpgItem item;
        public final String dateStr;
        public final String dateLabel;

        public EpgRow(int type, EpgItem item, String dateStr, String dateLabel) {
            this.type = type;
            this.item = item;
            this.dateStr = dateStr;
            this.dateLabel = dateLabel;
        }
    }

    public interface OnEpgItemClickListener {
        void onEpgItemClick(int position, EpgItem item, String dateStr);
    }

    private final List<EpgRow> rows = new ArrayList<>();
    private final Set<String> loadedDates = new HashSet<>();
    private String currentDateStr = "";
    private OnEpgItemClickListener clickListener;
    private RecyclerView recyclerView;

    public void setOnEpgItemClickListener(OnEpgItemClickListener listener) {
        this.clickListener = listener;
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

    public void notifyDataSetChangedSafe() {
        if (recyclerView != null && recyclerView.isComputingLayout()) {
            recyclerView.post(this::notifyDataSetChanged);
        } else {
            notifyDataSetChanged();
        }
    }

    public void notifyItemRangeInsertedSafe(int positionStart, int itemCount) {
        if (recyclerView != null && recyclerView.isComputingLayout()) {
            recyclerView.post(() -> notifyItemRangeInserted(positionStart, itemCount));
        } else {
            notifyItemRangeInserted(positionStart, itemCount);
        }
    }

    public void clear() {
        rows.clear();
        loadedDates.clear();
        currentDateStr = "";
        notifyDataSetChangedSafe();
    }

    public void setData(List<EpgItem> items) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        setData(items, today, "今天");
    }

    public void setData(List<EpgItem> items, String dateStr, String dateLabel) {
        rows.clear();
        loadedDates.clear();
        this.currentDateStr = (dateStr != null && !dateStr.isEmpty()) ? dateStr : new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        appendRowsForDate(items, this.currentDateStr, dateLabel);
        notifyDataSetChangedSafe();
    }

    public void appendDate(List<EpgItem> items, String dateStr, String dateLabel) {
        if (dateStr == null || loadedDates.contains(dateStr)) return;
        int startPos = rows.size();
        appendRowsForDate(items, dateStr, dateLabel);
        int inserted = rows.size() - startPos;
        if (inserted > 0) {
            notifyItemRangeInsertedSafe(startPos, inserted);
        }
    }

    public int prependDate(List<EpgItem> items, String dateStr, String dateLabel) {
        if (dateStr == null || loadedDates.contains(dateStr)) return 0;
        loadedDates.add(dateStr);
        List<EpgRow> newRows = new ArrayList<>();
        String label = (dateLabel != null && !dateLabel.isEmpty()) ? dateLabel : dateStr;
        newRows.add(new EpgRow(TYPE_HEADER, null, dateStr, label));
        if (items == null || items.isEmpty()) {
            newRows.add(new EpgRow(TYPE_ITEM, new EpgItem("", "", "暂无节目信息"), dateStr, label));
        } else {
            for (EpgItem item : items) {
                newRows.add(new EpgRow(TYPE_ITEM, item, dateStr, label));
            }
        }
        rows.addAll(0, newRows);
        notifyItemRangeInsertedSafe(0, newRows.size());
        return newRows.size();
    }

    private void appendRowsForDate(List<EpgItem> items, String dateStr, String dateLabel) {
        loadedDates.add(dateStr);
        String label = (dateLabel != null && !dateLabel.isEmpty()) ? dateLabel : dateStr;
        rows.add(new EpgRow(TYPE_HEADER, null, dateStr, label));
        if (items == null || items.isEmpty()) {
            rows.add(new EpgRow(TYPE_ITEM, new EpgItem("", "", "暂无节目信息"), dateStr, label));
        } else {
            for (EpgItem item : items) {
                rows.add(new EpgRow(TYPE_ITEM, item, dateStr, label));
            }
        }
    }

    public boolean hasDateLoaded(String dateStr) {
        return dateStr != null && loadedDates.contains(dateStr);
    }

    public int findPositionForDate(String dateStr) {
        if (dateStr == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            if (dateStr.equals(rows.get(i).dateStr)) {
                return i;
            }
        }
        return -1;
    }

    public String getDateAtPosition(int position) {
        if (position >= 0 && position < rows.size()) {
            return rows.get(position).dateStr;
        }
        return null;
    }

    public int findCurrentPlayingProgramIndex() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (int i = 0; i < rows.size(); i++) {
            EpgRow r = rows.get(i);
            if (r.type == TYPE_ITEM && today.equals(r.dateStr) && r.item != null) {
                int start = r.item.getStartMinutes();
                int end = r.item.getEndMinutes();
                if (end < 0) {
                    for (int j = i + 1; j < rows.size(); j++) {
                        EpgRow nextR = rows.get(j);
                        if (nextR.type == TYPE_ITEM && today.equals(nextR.dateStr) && nextR.item != null) {
                            end = nextR.item.getStartMinutes();
                            break;
                        }
                    }
                    if (end < 0) end = 24 * 60;
                }
                if (end < start) end += 24 * 60;
                if (nowMinutes >= start && nowMinutes < end) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int findProgramIndexForTime(long epochMs) {
        if (epochMs <= 0) return findCurrentPlayingProgramIndex();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String targetDate = sdf.format(new Date(epochMs));
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMs);
        int targetMinutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);

        for (int i = 0; i < rows.size(); i++) {
            EpgRow r = rows.get(i);
            if (r.type == TYPE_ITEM && targetDate.equals(r.dateStr) && r.item != null) {
                int start = r.item.getStartMinutes();
                int end = r.item.getEndMinutes();
                if (end < 0) {
                    for (int j = i + 1; j < rows.size(); j++) {
                        EpgRow nextR = rows.get(j);
                        if (nextR.type == TYPE_ITEM && targetDate.equals(nextR.dateStr) && nextR.item != null) {
                            end = nextR.item.getStartMinutes();
                            break;
                        }
                    }
                    if (end < 0) end = 24 * 60;
                }
                if (end < start) end += 24 * 60;
                if (targetMinutes >= start && targetMinutes < end) {
                    return i;
                }
            }
        }
        return -1;
    }

    public String getCurrentDateStr() {
        return currentDateStr;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            TextView tv = new TextView(parent.getContext());
            int heightPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28, parent.getContext().getResources().getDisplayMetrics());
            tv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextColor(Color.parseColor("#99aaaaaa"));
            tv.setBackgroundColor(Color.parseColor("#1affffff"));
            tv.setFocusable(false);
            tv.setClickable(false);
            return new HeaderViewHolder(tv);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_epg, parent, false);
            return new ItemViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder rawHolder, int position) {
        EpgRow row = rows.get(position);
        if (rawHolder instanceof HeaderViewHolder) {
            HeaderViewHolder h = (HeaderViewHolder) rawHolder;
            h.tvHeader.setText("—— " + row.dateLabel + " ——");
        } else if (rawHolder instanceof ItemViewHolder) {
            ItemViewHolder holder = (ItemViewHolder) rawHolder;
            EpgItem item = row.item;
            boolean isEmptyPlaceholder = "暂无节目信息".equals(item.getTitle());

            if (isEmptyPlaceholder) {
                holder.tvTime.setText("");
                holder.tvTitle.setText(item.getTitle());
                holder.tvTitle.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.text_gray));
                holder.itemView.setFocusable(false);
                holder.itemView.setClickable(false);
                return;
            }

            holder.itemView.setFocusable(true);
            holder.itemView.setClickable(true);
            holder.tvTime.setText(item.getStart());
            holder.tvTitle.setText(item.getTitle());

            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            boolean isToday = row.dateStr.equals(today);

            if (isToday) {
                Calendar now = Calendar.getInstance();
                int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
                int startMin = item.getStartMinutes();
                int endMin = item.getEndMinutes();
                if (endMin < 0) {
                    for (int j = position + 1; j < rows.size(); j++) {
                        EpgRow nextR = rows.get(j);
                        if (nextR.type == TYPE_ITEM && today.equals(nextR.dateStr) && nextR.item != null) {
                            endMin = nextR.item.getStartMinutes();
                            break;
                        }
                    }
                    if (endMin < 0) endMin = 24 * 60;
                }
                if (endMin < startMin) endMin += 24 * 60;

                boolean isCurrent = (nowMinutes >= startMin && nowMinutes < endMin);
                if (isCurrent) {
                    holder.tvTime.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.colorAccent));
                    holder.tvTitle.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.colorAccent));
                } else {
                    holder.tvTime.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.text_gray));
                    holder.tvTitle.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.text_white));
                }
            } else {
                holder.tvTime.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.text_gray));
                holder.tvTitle.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.text_white));
            }

            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pos >= 0 && pos < rows.size() && clickListener != null) {
                    EpgRow r = rows.get(pos);
                    if (r.type == TYPE_ITEM && r.item != null) {
                        clickListener.onEpgItemClick(pos, r.item, r.dateStr);
                    }
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvHeader = (TextView) itemView;
        }
    }

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        TextView tvTime;
        TextView tvTitle;

        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tv_epg_time);
            tvTitle = itemView.findViewById(R.id.tv_epg_title);
        }
    }
}
