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

        int dotColor;
        if (session.isAnomalous()) {
            // A reboot/offline gap between the start and end of this session
            // makes its real duration untrustworthy - say so in farmer terms
            // rather than showing a misleadingly huge or technical duration.
            holder.tvDuration.setText("Incomplete fogging record");
            holder.tvDuration.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.alert_orange));
            dotColor = R.color.alert_orange;
        } else if (!session.isCompleted()) {
            // Display only: the elapsed calculation is unchanged, just
            // formatted readably ("25h 48m" rather than "1548m elapsed").
            long elapsedMs = Math.max(0, System.currentTimeMillis() - startTimestamp);
            holder.tvDuration.setText("Running now · " + DurationFormatter.formatRuntime(elapsedMs));
            holder.tvDuration.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.primary));
            dotColor = R.color.primary;
        } else {
            holder.tvDuration.setText(DurationFormatter.formatSession(session.getDurationMs()));
            holder.tvDuration.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_dark));
            dotColor = R.color.state_success;
        }

        holder.dotActivity.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(holder.itemView.getContext(), dotColor)));

        holder.tvType.setText(session.getDisplayType());

        // Last row needs no trailing rule - the section below provides it.
        holder.divider.setVisibility(position == getItemCount() - 1 ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return sessions == null ? 0 : sessions.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDate;
        TextView tvDuration;
        TextView tvType;
        View dotActivity;
        View divider;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tvDate);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            tvType = itemView.findViewById(R.id.tvType);
            dotActivity = itemView.findViewById(R.id.dotActivity);
            divider = itemView.findViewById(R.id.activityDivider);
        }
    }
}
