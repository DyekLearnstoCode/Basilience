package com.example.basilience;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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

        h.tvCycleNo.setText(String.valueOf(c.getCycleNo()));
        h.tvStartDate.setText(c.getStartDate());
        h.tvEndDate.setText(c.getEndDate() == null ? "" : c.getEndDate());

        h.itemView.setOnClickListener(v -> onCycleClick.onClick(c, position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvCycleNo, tvStartDate, tvEndDate;

        VH(@NonNull View itemView) {
            super(itemView);
            tvCycleNo = itemView.findViewById(R.id.tvCycleNumber);
            tvStartDate = itemView.findViewById(R.id.tvStartDate);
            tvEndDate = itemView.findViewById(R.id.tvEndDate);
        }
    }
}