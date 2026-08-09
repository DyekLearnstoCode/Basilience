package com.example.basilience;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class NotificationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CONTENT = 1;

    public interface OnNotificationClickListener {
        void onNotificationClick(NotificationItem item);
    }

    public static class NotificationItem {
        public static final String TYPE_PARAMETER = "parameter";
        public static final String TYPE_HARVEST = "harvest";
        public static final String TYPE_HARDWARE = "hardware";
        public static final String TYPE_INFO = "info";

        public String docId;
        public String message;
        public long timestamp;
        public String type = TYPE_INFO;
        public boolean isRead = false;
        public boolean isHeader = false;
        public String headerText;
        public boolean showMarkAllAction;

        public NotificationItem() {}

        public NotificationItem(String docId, String message, long timestamp, String type, boolean isRead) {
            this.docId = docId;
            this.message = message;
            this.timestamp = timestamp;
            this.type = type;
            this.isRead = isRead;
            this.isHeader = false;
        }

        public static NotificationItem createHeader(String headerText, boolean showMarkAllAction) {
            NotificationItem item = new NotificationItem();
            item.isHeader = true;
            item.headerText = headerText;
            item.showMarkAllAction = showMarkAllAction;
            return item;
        }
    }

    private final List<NotificationItem> notifications;
    private final OnNotificationClickListener clickListener;
    private final Runnable markAllReadAction;
    private boolean markAllEnabled;
    private boolean markingAllRead;

    public NotificationAdapter(List<NotificationItem> notifications,
                               OnNotificationClickListener clickListener,
                               Runnable markAllReadAction) {
        this.notifications = notifications;
        this.clickListener = clickListener;
        this.markAllReadAction = markAllReadAction;
    }

    public void setMarkAllReadState(boolean enabled, boolean loading) {
        markAllEnabled = enabled;
        markingAllRead = loading;
        notifyItemRangeChanged(0, notifications.size());
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
            HeaderViewHolder header = (HeaderViewHolder) holder;
            header.tvHeader.setText(item.headerText);
            header.tvMarkAllRead.setVisibility(item.showMarkAllAction ? View.VISIBLE : View.GONE);
            if (item.showMarkAllAction) {
                boolean enabled = markAllEnabled && !markingAllRead;
                header.tvMarkAllRead.setText(markingAllRead ? "Marking as read…" : "Mark all as read");
                header.tvMarkAllRead.setEnabled(enabled);
                header.tvMarkAllRead.setClickable(enabled);
                header.tvMarkAllRead.setTextColor(ContextCompat.getColor(header.itemView.getContext(),
                        enabled ? R.color.primary : android.R.color.darker_gray));
                header.tvMarkAllRead.setOnClickListener(v -> {
                    if (enabled && markAllReadAction != null) markAllReadAction.run();
                });
            }
        } else if (holder instanceof ContentViewHolder) {
            ContentViewHolder contentHolder = (ContentViewHolder) holder;
            contentHolder.tvMessage.setText(item.message);
            
            String timeStr = DateUtils.formatDateTime(item.timestamp);
            contentHolder.tvTimestamp.setText(timeStr);

            String title = "INFORMATION";
            int color = 0xFF757575; // Gray
            int iconRes = R.drawable.nav_notif_icon;

            if (NotificationItem.TYPE_PARAMETER.equals(item.type)) {
                title = "PARAMETER ALERT";
                iconRes = R.drawable.ic_error_red;
                color = 0xFFD32F2F;
            } else if (NotificationItem.TYPE_HARVEST.equals(item.type)) {
                title = "HARVEST READY";
                iconRes = R.drawable.ic_harvest_green;
                color = 0xFF2E7D32;
            } else if (NotificationItem.TYPE_HARDWARE.equals(item.type)) {
                title = "HARDWARE ISSUE";
                iconRes = R.drawable.ic_hardware_orange;
                color = 0xFFEF6C00;
            }

            contentHolder.tvTitle.setText(title);
            contentHolder.tvTitle.setTextColor(color);
            contentHolder.ivIcon.setImageResource(iconRes);
            contentHolder.ivIcon.setColorFilter(color);
            contentHolder.viewTypeColor.setBackgroundColor(color);
            contentHolder.iconBackground.getBackground().setTint(color);

            // Read / Unread UI formatting
            if (item.isRead) {
                if (contentHolder.vUnreadDot != null) contentHolder.vUnreadDot.setVisibility(View.GONE);
                contentHolder.tvMessage.setTypeface(null, Typeface.NORMAL);
                contentHolder.tvMessage.setTextColor(0xFF666666);
            } else {
                if (contentHolder.vUnreadDot != null) contentHolder.vUnreadDot.setVisibility(View.VISIBLE);
                contentHolder.tvMessage.setTypeface(null, Typeface.BOLD);
                contentHolder.tvMessage.setTextColor(0xFF000000);
            }

            contentHolder.itemView.setOnClickListener(v -> {
                if (clickListener != null && !item.isHeader) {
                    clickListener.onNotificationClick(item);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return notifications.size();
    }

    public static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvHeader, tvMarkAllRead;
        public HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tvHeader);
            tvMarkAllRead = itemView.findViewById(R.id.tvMarkAllRead);
        }
    }

    public static class ContentViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage, tvTimestamp, tvTitle;
        ImageView ivIcon;
        View viewTypeColor, iconBackground, vUnreadDot;

        public ContentViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            viewTypeColor = itemView.findViewById(R.id.viewTypeColor);
            iconBackground = itemView.findViewById(R.id.iconBackground);
            vUnreadDot = itemView.findViewById(R.id.vUnreadDot);
        }
    }
}
