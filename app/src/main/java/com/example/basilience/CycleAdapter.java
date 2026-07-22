package com.example.basilience;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

public class CycleAdapter extends RecyclerView.Adapter<CycleAdapter.VH> {

    public interface OnCycleClick {
        void onClick(Cycle cycle, int position);
    }

    private final List<Cycle> items;
    private final OnCycleClick onCycleClick;

    public CycleAdapter(List<Cycle> items, OnCycleClick onCycleClick) {
        this.items = items;
        this.onCycleClick = onCycleClick;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.cycle_item_adapter, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Cycle c = items.get(position);
        android.content.Context context = h.itemView.getContext();
        
        h.tvCycleNo.setText(String.valueOf(c.getCycleNumber()));
        
        String rawStatus = c.getStatus();
        String status = (rawStatus == null || rawStatus.isEmpty()) ? "ACTIVE" : rawStatus.toUpperCase();
        h.tvStatus.setText(status);

        boolean isCompleted = "COMPLETED".equals(status);
        int textColorRes = isCompleted ? R.color.nav_inactive : R.color.primary;
        int textColor = ContextCompat.getColor(context, textColorRes);

        h.tvCycleNo.setTextColor(textColor);
        h.tvCycleNoLabel.setTextColor(textColor);
        h.tvTotalWeight.setTextColor(textColor);

        if (isCompleted) {
            h.tvStatus.setBackgroundResource(R.drawable.status_chip_gray_bg);
            h.tvStatus.setTextColor(textColor);
            h.itemView.setAlpha(0.7f);
        } else {
            h.tvStatus.setBackgroundResource(R.drawable.status_chip_bg);
            h.tvStatus.setTextColor(Color.WHITE);
            h.itemView.setAlpha(1.0f);
        }

        if (h.itemView instanceof MaterialCardView) {
            float elevation = context.getResources().getDimension(
                    isCompleted ? R.dimen.card_elevation_completed : R.dimen.card_elevation_active);
            ((MaterialCardView) h.itemView).setCardElevation(elevation);
        }

        h.tvStartDate.setText(DateUtils.formatDate(c.getStartDate()));

        // Display Next Harvest if available, otherwise fallback to expected/end
        if (c.getNextHarvestDate() != null) {
            h.tvEndDate.setText(DateUtils.formatDate(c.getNextHarvestDate()));
        } else if (c.getExpectedHarvestDate() != null) {
            h.tvEndDate.setText(DateUtils.formatDate(c.getExpectedHarvestDate()));
        } else if (c.getEndDate() != null) {
            h.tvEndDate.setText(DateUtils.formatDate(c.getEndDate()));
        } else {
            h.tvEndDate.setText(context.getString(R.string.status_ongoing));
        }

        h.tvFrequency.setText(context.getString(R.string.days_suffix, c.getHarvestFrequencyDays()));
        h.tvHarvestCount.setText(String.valueOf(c.getTotalHarvestCount()));
        h.tvTotalWeight.setText(String.format(java.util.Locale.US, "%.1fg", c.getTotalHarvestWeight()));

        h.itemView.setOnClickListener(v -> onCycleClick.onClick(c, position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvCycleNo, tvCycleNoLabel, tvStatus, tvStartDate, tvEndDate, tvHarvestCount, tvTotalWeight, tvFrequency;

        VH(@NonNull View itemView) {
            super(itemView);
            tvCycleNo = itemView.findViewById(R.id.tvCycleNumber);
            tvCycleNoLabel = itemView.findViewById(R.id.tvCycleNumberLabel);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            tvStartDate = itemView.findViewById(R.id.tvStartDate);
            tvEndDate = itemView.findViewById(R.id.tvEndDate);
            tvFrequency = itemView.findViewById(R.id.tvFrequency);
            tvHarvestCount = itemView.findViewById(R.id.tvHarvestCount);
            tvTotalWeight = itemView.findViewById(R.id.tvTotalWeight);
        }
    }
}