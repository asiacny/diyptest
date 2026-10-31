package com.diyp.next.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.airbnb.lottie.LottieAnimationView;
import com.diyp.next.R;
import com.diyp.next.data.model.Channel;
import com.diyp.next.data.repository.EpgRepository;
import com.diyp.next.data.repository.SettingsRepository;
import java.util.ArrayList;
import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {
    private List<Channel> channels = new ArrayList<>();
    private Channel playingChannel = null;
    private int selectedPosition = 0;
    private OnChannelClickListener listener;
    private RecyclerView recyclerView;

    public interface OnChannelClickListener {
        void onChannelClick(int position, Channel channel);
        void onChannelFocused(int position, Channel channel);
    }

    public void setListener(OnChannelClickListener listener) {
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
        if (position < 0 || position >= channels.size()) return;
        if (recyclerView != null && recyclerView.isComputingLayout()) {
            recyclerView.post(() -> {
                if (position >= 0 && position < channels.size()) {
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

    public void setData(List<Channel> channels) {
        this.channels = channels != null ? channels : new ArrayList<>();
        notifyDataSetChangedSafe();
    }

    public void setPlayingChannel(Channel channel) {
        this.playingChannel = channel;
        notifyDataSetChangedSafe();
    }

    public void setSelectedPosition(int position) {
        if (position < 0 || position >= channels.size() || position == selectedPosition) {
            return;
        }
        int prev = selectedPosition;
        selectedPosition = position;
        if (prev >= 0 && prev < channels.size()) {
            notifyItemChangedSafe(prev, "selection");
        }
        if (selectedPosition >= 0 && selectedPosition < channels.size()) {
            notifyItemChangedSafe(selectedPosition, "selection");
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            holder.itemView.setSelected(position == selectedPosition);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public Channel getSelectedChannel() {
        if (selectedPosition >= 0 && selectedPosition < channels.size()) {
            return channels.get(selectedPosition);
        }
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Channel channel = channels.get(position);

        // Lottie Playing Animation & Channel Number (1:1 with DIYP original: wave replaces number in-place)
        boolean isPlaying = (playingChannel != null && (playingChannel == channel
                || (playingChannel.getName() != null && playingChannel.getName().equalsIgnoreCase(channel.getName()))));

        try {
            if (isPlaying) {
                holder.tvNumber.setVisibility(View.GONE);
                holder.lottiePlaying.setVisibility(View.VISIBLE);
                if (!holder.lottiePlaying.isAnimating()) {
                    holder.lottiePlaying.playAnimation();
                }
            } else {
                holder.lottiePlaying.setVisibility(View.GONE);
                if (holder.lottiePlaying.isAnimating()) {
                    holder.lottiePlaying.cancelAnimation();
                }
                if (SettingsRepository.get().isHideChannelNum()) {
                    holder.tvNumber.setVisibility(View.GONE);
                } else {
                    holder.tvNumber.setVisibility(View.VISIBLE);
                    holder.tvNumber.setText(String.valueOf(channel.getNumber()));
                }
            }
        } catch (Throwable t) {
            holder.lottiePlaying.setVisibility(View.GONE);
            holder.tvNumber.setVisibility(View.VISIBLE);
            holder.tvNumber.setText(String.valueOf(channel.getNumber()));
        }

        // Channel Name
        holder.tvName.setText(channel.getName());

        // Channel EPG summary
        String curProg = channel.getCurrentProgram();
        if (curProg != null && !curProg.isEmpty() && !curProg.equals("暂无节目信息")) {
            holder.tvEpg.setVisibility(View.VISIBLE);
            holder.tvEpg.setText(curProg);
        } else {
            holder.tvEpg.setVisibility(View.GONE);
            // Preload EPG asynchronously if available
            EpgRepository.queryEpg(channel, updatedChannel -> {
                if (holder.tvName != null && channel.getName().equals(holder.tvName.getText().toString())) {
                    String prog = updatedChannel.getCurrentProgram();
                    if (prog != null && !prog.isEmpty() && !prog.equals("暂无节目信息")) {
                        holder.tvEpg.setVisibility(View.VISIBLE);
                        holder.tvEpg.setText(prog);
                    }
                }
            });
        }

        // Channel Logo
        com.diyp.next.util.BenchMarkManager.loadLogo(holder.ivLogo, channel.getName(), channel.getLogoUrl());

        holder.itemView.setSelected(position == selectedPosition);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos >= 0 && pos < channels.size()) {
                setSelectedPosition(pos);
                if (listener != null) {
                    listener.onChannelClick(pos, channels.get(pos));
                }
            }
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pos >= 0 && pos < channels.size()) {
                    if (pos != selectedPosition) {
                        int prev = selectedPosition;
                        selectedPosition = pos;
                        if (prev >= 0 && prev < channels.size()) {
                            notifyItemChangedSafe(prev, "selection");
                        }
                        notifyItemChangedSafe(pos, "selection");
                        if (listener != null) {
                            listener.onChannelFocused(pos, channels.get(pos));
                        }
                    }
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvNumber;
        TextView tvName;
        TextView tvEpg;
        ImageView ivLogo;
        LottieAnimationView lottiePlaying;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvNumber = itemView.findViewById(R.id.tv_channel_number);
            tvName = itemView.findViewById(R.id.tv_channel_name);
            tvEpg = itemView.findViewById(R.id.tv_channel_epg);
            ivLogo = itemView.findViewById(R.id.iv_channel_logo);
            lottiePlaying = itemView.findViewById(R.id.lottie_playing);
        }
    }
}
