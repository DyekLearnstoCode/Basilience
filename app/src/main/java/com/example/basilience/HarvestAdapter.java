package com.example.basilience;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class HarvestAdapter extends RecyclerView.Adapter<HarvestAdapter.ViewHolder> {

    private final List<HarvestLogFragment.HarvestEntry> harvestList;

    public HarvestAdapter(List<HarvestLogFragment.HarvestEntry> harvestList) {
        this.harvestList = harvestList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cycle_harvest_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HarvestLogFragment.HarvestEntry entry = harvestList.get(position);
        holder.tvDate.setText(entry.date);
        holder.tvWeight.setText(entry.weight + "g");
    }

    @Override
    public int getItemCount() {
        return harvestList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDate, tvWeight;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tvHarvestDate);
            tvWeight = itemView.findViewById(R.id.tvHarvestWeight);
        }
    }
}
