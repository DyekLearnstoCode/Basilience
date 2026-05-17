package com.example.basilience;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotificationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CONTENT = 1;

    public static class NotificationItem {
        public static final String TYPE_PARAMETER = "parameter";
        public static final String TYPE_HARVEST = "harvest";
        public static final String TYPE_HARDWARE = "hardware";
        public static final String TYPE_INFO = "info";

        public String message;
        public long timestamp;
        public String type = TYPE_INFO;
        public boolean isHeader = false;
        public String headerText;

        public NotificationItem() {}

        public NotificationItem(String message, long timestamp, String type) {
            this.message = message;
            this.timestamp = timestamp;
            this.type = type;
            this.isHeader = false;
        }

        public static NotificationItem createHeader(String headerText) {
            NotificationItem item = new NotificationItem();
            item.isHeader = true;
            item.headerText = headerText;
            return item;
        }
    }

    private final List<NotificationItem> notifications;

    public NotificationAdapter(List<NotificationItem> notifications) {
        this.notifications = notifications;
    }

    @Override
    public int getItemViewType(int position) {
        return notifications.get(position).isHeader ? TYPE_HEADER : TYPE_CONTENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.notification_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.notification_item, parent, false);
            return new ContentViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        NotificationItem item = notifications.get(position);

        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).tvHeader.setText(item.headerText);
        } else if (holder instanceof ContentViewHolder) {
            ContentViewHolder contentHolder = (ContentViewHolder) holder;
            contentHolder.tvMessage.setText(item.message);
            
            SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.US);
            SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.US);
            String timeStr = timeFormat.format(new Date(item.timestamp)) + " • " + dateFormat.format(new Date(item.timestamp));
            contentHolder.tvTimestamp.setText(timeStr);

            String title = "INFORMATION";
            int color = 0xFF757575; // Gray
            int iconRes = R.drawable.nav_notif_icon;

            if (NotificationItem.TYPE_PARAMETER.equals(item.type)) {
                title = "PARAMETER ALERT";
                iconRes = R.drawable.ic_error_red;
                color = 0xFFD32F2F; // Darker Red
            } else if (NotificationItem.TYPE_HARVEST.equals(item.type)) {
                title = "HARVEST READY";
                iconRes = R.drawable.ic_harvest_green;
                color = 0xFF2E7D32; // Darker Green
            } else if (NotificationItem.TYPE_HARDWARE.equals(item.type)) {
                title = "HARDWARE ISSUE";
                iconRes = R.drawable.ic_hardware_orange;
                color = 0xFFEF6C00; // Darker Orange
            }

            contentHolder.tvTitle.setText(title);
            contentHolder.tvTitle.setTextColor(color);
            contentHolder.ivIcon.setImageResource(iconRes);
            contentHolder.ivIcon.setColorFilter(color);
            contentHolder.viewTypeColor.setBackgroundColor(color);
            contentHolder.iconBackground.getBackground().setTint(color);
        }
    }

    @Override
    public int getItemCount() {
        return notifications.size();
    }

    public static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvHeader;
        public HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tvHeader);
        }
    }

    public static class ContentViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage, tvTimestamp, tvTitle;
        ImageView ivIcon;
        View viewTypeColor, iconBackground;

        public ContentViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            viewTypeColor = itemView.findViewById(R.id.viewTypeColor);
            iconBackground = itemView.findViewById(R.id.iconBackground);
        }
    }
}
