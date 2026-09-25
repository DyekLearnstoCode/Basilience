package com.example.basilience;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Backed by Paging 3 / Room (see NotificationRepository) rather than an
 * in-memory list - RecyclerView updates are driven by submitData(), and
 * diffing is PagingDataAdapter's own, not a hand-rolled DiffUtil pass over
 * the full list.
 *
 * <p>No month-header rows: Paging 3's PagingData.insertSeparators() is a
 * Kotlin suspend function with no practical Java-only call path, and adding
 * Kotlin/coroutines just for section headers was explicitly ruled out. Date
 * context is still visible per-row (tvTimestamp); "Mark all as read" moved
 * from a per-month header action to a persistent button in
 * notification_feature.xml instead.
 */
public class NotificationAdapter extends PagingDataAdapter<NotificationEntity, NotificationAdapter.ContentViewHolder> {

    public interface OnNotificationClickListener {
        void onNotificationClick(NotificationEntity item);
    }

    private static final DiffUtil.ItemCallback<NotificationEntity> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<NotificationEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull NotificationEntity oldItem, @NonNull NotificationEntity newItem) {
                    return oldItem.notificationId.equals(newItem.notificationId);
                }

                @Override
                public boolean areContentsTheSame(@NonNull NotificationEntity oldItem, @NonNull NotificationEntity newItem) {
                    return oldItem.isRead == newItem.isRead
                            && Objects.equals(oldItem.message, newItem.message)
                            && oldItem.timestamp == newItem.timestamp
                            && Objects.equals(oldItem.occurredAtMs, newItem.occurredAtMs)
                            && Objects.equals(oldItem.type, newItem.type)
                            && Objects.equals(oldItem.parameterKey, newItem.parameterKey)
                            && oldItem.offlineRecorded == newItem.offlineRecorded
                            && oldItem.smsFallbackUsed == newItem.smsFallbackUsed
                            && Objects.equals(oldItem.recorderName, newItem.recorderName)
                            && Objects.equals(oldItem.recorderUid, newItem.recorderUid);
                }
            };

    private final OnNotificationClickListener clickListener;
    // Selection mode (see NotificationFragment) - the Set is owned and
    // mutated by the Fragment; the adapter only ever reads it to decide
    // checkbox visibility/checked state.
    private boolean selectionModeActive;
    private Set<String> selectedIds = Collections.emptySet();
    // Fallback-tier cache: resolves a recorder UID to a display name only
    // when a record predates the recorderName snapshot. Avoids re-fetching
    // the same profile on every rebind.
    private final Map<String, String> resolvedRecorderNames = new HashMap<>();
    private final Set<String> pendingRecorderLookups = new HashSet<>();

    public NotificationAdapter(OnNotificationClickListener clickListener) {
        super(DIFF_CALLBACK);
        this.clickListener = clickListener;
    }

    /** Shared with NotificationFragment's detail popup so row and popup titles can never drift apart. */
    public static String titleForType(String type) {
        if (NotificationEntity.TYPE_PARAMETER.equals(type)) return "PARAMETER ALERT";
        if (NotificationEntity.TYPE_HARVEST.equals(type)) return "HARVEST READY";
        if (NotificationEntity.TYPE_HARDWARE.equals(type)) return "HARDWARE ISSUE";
        if (NotificationEntity.TYPE_CONNECTIVITY_OFFLINE.equals(type)) return "DEVICE UNREACHABLE";
        if (NotificationEntity.TYPE_CONNECTIVITY_RECOVERY.equals(type)) return "DEVICE BACK ONLINE";
        return "INFORMATION";
    }

    /**
     * The timestamp to SHOW the user - occurredAtMs (true device time) when
     * present, else the insertion-time timestamp field. Never use this for
     * sort/grouping/pagination; those stay keyed on item.timestamp alone
     * (see functions/onNotificationQueued and NotificationEntity's field
     * comment) so ordering stays insertion-monotonic.
     */
    public static long displayTimestamp(NotificationEntity item) {
        return item.occurredAtMs != null ? item.occurredAtMs : item.timestamp;
    }

    public void setSelectionMode(boolean active, Set<String> selectedIds) {
        this.selectedIds = selectedIds;
        this.selectionModeActive = active;
        notifyDataSetChanged();
    }

    /** Selection changed but mode didn't - refreshes checkbox states only. */
    public void refreshSelectionState() {
        if (!selectionModeActive) return;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ContentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.notification_item, parent, false);
        return new ContentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ContentViewHolder holder, int position) {
        NotificationEntity item = getItem(position);
        // Paging 3 placeholder - shouldn't occur (placeholders disabled in
        // NotificationRepository's PagingConfig), guarded defensively anyway.
        if (item == null) return;

        holder.tvMessage.setText(item.message);
        holder.tvTimestamp.setText(DateUtils.formatDateTime(displayTimestamp(item)));

        String title = titleForType(item.type);
        int color = ContextCompat.getColor(holder.itemView.getContext(), R.color.state_no_data);
        int iconRes = R.drawable.nav_notif_icon;

        if (NotificationEntity.TYPE_PARAMETER.equals(item.type)) {
            iconRes = R.drawable.ic_error_red;
            color = ContextCompat.getColor(holder.itemView.getContext(), R.color.state_critical);
        } else if (NotificationEntity.TYPE_HARVEST.equals(item.type)) {
            iconRes = R.drawable.ic_harvest_green;
            color = ContextCompat.getColor(holder.itemView.getContext(), R.color.state_success);
        } else if (NotificationEntity.TYPE_HARDWARE.equals(item.type)) {
            iconRes = R.drawable.ic_hardware_orange;
            color = 0xFFEF6C00;
        } else if (NotificationEntity.TYPE_CONNECTIVITY_OFFLINE.equals(item.type)) {
            iconRes = R.drawable.ic_error_red;
            color = ContextCompat.getColor(holder.itemView.getContext(), R.color.state_critical);
        } else if (NotificationEntity.TYPE_CONNECTIVITY_RECOVERY.equals(item.type)) {
            iconRes = R.drawable.ic_hardware_orange;
            color = ContextCompat.getColor(holder.itemView.getContext(), R.color.state_success);
        }

        holder.tvTitle.setText(title);
        holder.tvTitle.setTextColor(color);
        holder.ivIcon.setImageResource(iconRes);
        holder.ivIcon.setColorFilter(color);
        holder.viewTypeColor.setBackgroundColor(color);
        holder.iconBackground.getBackground().setTint(color);

        bindRecordedBy(holder, item);

        if (item.isRead) {
            if (holder.vUnreadDot != null) holder.vUnreadDot.setVisibility(View.GONE);
            holder.tvMessage.setTypeface(null, Typeface.NORMAL);
            holder.tvMessage.setTextColor(0xFF666666);
        } else {
            if (holder.vUnreadDot != null) holder.vUnreadDot.setVisibility(View.VISIBLE);
            holder.tvMessage.setTypeface(null, Typeface.BOLD);
            holder.tvMessage.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.black));
        }

        if (holder.cbSelect != null) {
            if (selectionModeActive) {
                holder.cbSelect.setVisibility(View.VISIBLE);
                holder.cbSelect.setChecked(selectedIds.contains(item.notificationId));
            } else {
                holder.cbSelect.setVisibility(View.GONE);
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onNotificationClick(item);
        });
    }

    // Shares one secondary-note line with two mutually exclusive uses (a
    // notification is never both a stored harvest record and an offline-
    // replayed event): "Recorded by" for an actual stored harvest record,
    // or a subtle note when the event was captured/delivered while the
    // device was offline. The persisted recorder-name snapshot is preferred
    // over a live UID lookup, which is only a fallback for older records.
    private void bindRecordedBy(ContentViewHolder holder, NotificationEntity item) {
        if (holder.tvRecordedBy == null) return;

        if (item.offlineRecorded) {
            holder.tvRecordedBy.setVisibility(View.VISIBLE);
            holder.tvRecordedBy.setText(item.smsFallbackUsed
                    ? "Delivered by SMS while device was offline"
                    : "Recorded while device was offline");
            return;
        }

        if (!NotificationEntity.TYPE_HARVEST.equals(item.type)
                || (isEmpty(item.recorderName) && isEmpty(item.recorderUid))) {
            holder.tvRecordedBy.setVisibility(View.GONE);
            return;
        }

        if (!isEmpty(item.recorderName)) {
            holder.tvRecordedBy.setVisibility(View.VISIBLE);
            holder.tvRecordedBy.setText("Recorded by: " + item.recorderName);
            return;
        }

        String cached = resolvedRecorderNames.get(item.recorderUid);
        if (cached != null) {
            holder.tvRecordedBy.setVisibility(View.VISIBLE);
            holder.tvRecordedBy.setText("Recorded by: " + cached);
            return;
        }

        holder.tvRecordedBy.setVisibility(View.VISIBLE);
        holder.tvRecordedBy.setText("Recorded by: Unknown");

        final String uid = item.recorderUid;
        if (!pendingRecorderLookups.add(uid)) return; // already in flight for this uid

        FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    pendingRecorderLookups.remove(uid);
                    String fullName = doc != null ? doc.getString("fullName") : null;
                    if (isEmpty(fullName)) return;
                    resolvedRecorderNames.put(uid, fullName);
                    int position = holder.getBindingAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        notifyItemChanged(position);
                    }
                })
                .addOnFailureListener(e -> pendingRecorderLookups.remove(uid));
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class ContentViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage, tvTimestamp, tvTitle, tvRecordedBy;
        ImageView ivIcon;
        View viewTypeColor, iconBackground, vUnreadDot;
        CheckBox cbSelect;

        public ContentViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvRecordedBy = itemView.findViewById(R.id.tvRecordedBy);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            viewTypeColor = itemView.findViewById(R.id.viewTypeColor);
            iconBackground = itemView.findViewById(R.id.iconBackground);
            vUnreadDot = itemView.findViewById(R.id.vUnreadDot);
            cbSelect = itemView.findViewById(R.id.cbSelect);
        }
    }
}
