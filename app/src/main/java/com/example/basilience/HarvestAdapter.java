package com.example.basilience;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class HarvestAdapter extends RecyclerView.Adapter<HarvestAdapter.ViewHolder> {

    public interface OnHarvestActionListener {
        void onEdit(Harvest harvest);
        void onDelete(Harvest harvest);
    }

    private final List<Harvest> harvestList;
    private final OnHarvestActionListener listener;
    private boolean canManage; // True if user is Admin AND cycle is ACTIVE

    public HarvestAdapter(List<Harvest> harvestList, boolean canManage, OnHarvestActionListener listener) {
        this.harvestList = harvestList;
        this.canManage = canManage;
        this.listener = listener;
    }

    public void setCanManage(boolean canManage) {
        this.canManage = canManage;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cycle_harvest_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Harvest entry = harvestList.get(position);
        
        holder.tvDate.setText(DateUtils.formatDateTime(entry.getHarvestDate()));
        // Shared formatter so a weight never reads one way here and another
        // way in the hero, the chart marker, or the PDF.
        holder.tvWeight.setText(HarvestFormatter.formatWeight(entry.getWeight()));

        holder.tvRecordedBy.setText("Recorded by: " + entry.getRecordedByName());

        String source = entry.getSource() != null ? entry.getSource().toUpperCase() : "MANUAL";
        holder.tvSource.setText(source);

        // Notes row is shown only when the entry actually has one, so a bare
        // "Note:" label can never appear with nothing after it. The label is
        // emphasised while the note itself stays regular weight.
        String notes = entry.getNotes();
        if (notes != null && !notes.trim().isEmpty()) {
            SpannableString noteText = new SpannableString("Note: " + notes.trim());
            noteText.setSpan(new StyleSpan(Typeface.BOLD), 0, "Note:".length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.tvNotes.setVisibility(View.VISIBLE);
            holder.tvNotes.setText(noteText);
        } else {
            holder.tvNotes.setVisibility(View.GONE);
        }

        // Entire card is clickable for the ripple effect
        holder.itemView.setOnClickListener(v -> {
            if (canManage && listener != null) {
                listener.onEdit(entry);
            }
        });

        if (canManage) {
            holder.ivMore.setVisibility(View.VISIBLE);
            holder.ivMore.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(v.getContext(), v);
                popup.getMenu().add("Edit Harvest");
                popup.getMenu().add("Delete Entry");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getTitle().equals("Edit Harvest")) {
                        if (listener != null) listener.onEdit(entry);
                    } else if (item.getTitle().equals("Delete Entry")) {
                        if (listener != null) listener.onDelete(entry);
                    }
                    return true;
                });
                popup.show();
            });
        } else {
            holder.ivMore.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return harvestList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDate, tvWeight, tvRecordedBy, tvSource, tvNotes;
        ImageView ivMore;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvWeight = itemView.findViewById(R.id.tvHarvestWeight);
            tvDate = itemView.findViewById(R.id.tvHarvestDate);
            tvRecordedBy = itemView.findViewById(R.id.tvRecordedBy);
            tvSource = itemView.findViewById(R.id.tvSource);
            tvNotes = itemView.findViewById(R.id.tvNotes);
            ivMore = itemView.findViewById(R.id.ivMoreActions);
        }
    }
}
