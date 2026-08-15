package com.example.basilience;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.basilience.models.FoggingSession;
import com.google.firebase.Timestamp;

import java.util.List;

public class FoggingEventAdapter extends RecyclerView.Adapter<FoggingEventAdapter.ViewHolder> {

    private List<FoggingSession> sessions;

    public FoggingEventAdapter(List<FoggingSession> sessions) {
        this.sessions = sessions;
    }

    public void updateData(List<FoggingSession> newSessions) {
        this.sessions = newSessions;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_fogging_event, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FoggingSession session = sessions.get(position);
        
        long startTimestamp = session.getStartEvent().timestamp;
        holder.tvDate.setText(DateUtils.formatDateTime(new Timestamp(startTimestamp / 1000, 0)));
        
        if (!session.isCompleted()) {
            holder.tvDuration.setText("Running...");
            holder.tvDuration.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.primary));
        } else {
            long durSecs = session.getDurationMs() / 1000;
            long durMins = durSecs / 60;
            long remSecs = durSecs % 60;
            
            String durStr = durMins > 0 ? (durMins + "m " + remSecs + "s") : (remSecs + "s");
            holder.tvDuration.setText(durStr);
            holder.tvDuration.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_dark));
        }

        holder.tvType.setText(session.getDisplayType());
    }

    @Override
    public int getItemCount() {
        return sessions == null ? 0 : sessions.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDate;
        TextView tvDuration;
        TextView tvType;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tvDate);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            tvType = itemView.findViewById(R.id.tvType);
        }
    }
}
