package com.example.basilience;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        public static final String TYPE_CONNECTIVITY_OFFLINE = "connectivity_offline";
        public static final String TYPE_CONNECTIVITY_RECOVERY = "connectivity_recovery";
        public static final String TYPE_INFO = "info";

        public String docId;
        public String message;
        public long timestamp;
        public String type = TYPE_INFO;
        // Read state belongs to the signed-in user, not to the notification.
        // Derived from the document's readBy map, never from the legacy shared
        // isRead field - naming it explicitly so it cannot be wired back to a
        // document-wide boolean by mistake.
        public boolean isReadForCurrentUser = false;
        public boolean isHeader = false;
        public String headerText;
        public boolean showMarkAllAction;
        // Only ever populated for a notification tied to an actual stored
        // harvest record (never for a pre-harvest reminder) - a persisted
        // name snapshot taken at the moment the harvest was recorded, so it
        // stays historically correct even if the recorder's profile name
        // later changes. recorderUid is a fallback for older/incomplete
        // records that never captured a name snapshot.
        public String recorderName;
        public String recorderUid;
        // Set only for an event replayed from the firmware's offline queue -
        // see functions/onNotificationQueued. Drives a subtle secondary note,
        // never a badge.
        public boolean offlineRecorded = false;
        public boolean smsFallbackUsed = false;
        // True device-observed time for an offline-queue-replayed event
        // (functions/onNotificationQueued) - display only, null for every
        // normal real-time notification. timestamp above is always
        // insertion-monotonic (drives sort/pagination in
        // Database_Helper/NotificationFragment) and must never be replaced
        // by this value; this is purely what gets shown to the user instead
        // of timestamp when present.
        public Long occurredAtMs;
        // Canonical grouping key (see NotificationParameterKeys) - one of
        // PH/EC/WATER_TEMP/AIR_TEMP/HUMIDITY/WATER_LEVEL, or null for a
        // non-parameter notification or a parameter one whose key couldn't
        // be resolved (shown under "Unspecified", never silently dropped
        // into the System/Other bucket alongside actual non-parameter types).
        public String parameterKey;

        public NotificationItem() {}

        public NotificationItem(String docId, String message, long timestamp, String type,
                                 boolean isReadForCurrentUser) {
            this(docId, message, timestamp, type, isReadForCurrentUser, null, null);
        }

        public NotificationItem(String docId, String message, long timestamp, String type,
                                 boolean isReadForCurrentUser,
                                 String recorderName, String recorderUid) {
            this.docId = docId;
            this.message = message;
            this.timestamp = timestamp;
            this.type = type;
            this.isReadForCurrentUser = isReadForCurrentUser;
            this.isHeader = false;
            this.recorderName = recorderName;
            this.recorderUid = recorderUid;
        }

        public static NotificationItem createHeader(String headerText, boolean showMarkAllAction) {
            NotificationItem item = new NotificationItem();
            item.isHeader = true;
            item.headerText = headerText;
            item.showMarkAllAction = showMarkAllAction;
            return item;
        }
    }

    /**
     * The timestamp to SHOW the user - occurredAtMs (true device time) when
     * present, else the insertion-time timestamp field. Never use this for
     * sort/grouping/pagination; those must stay keyed on item.timestamp
     * alone so ordering stays insertion-monotonic (see occurredAtMs's own
     * field comment and functions/onNotificationQueued).
     */
    public static long displayTimestamp(NotificationItem item) {
        return item.occurredAtMs != null ? item.occurredAtMs : item.timestamp;
    }

    /** Shared with NotificationFragment's detail popup so row and popup titles can never drift apart. */
    public static String titleForType(String type) {
        if (NotificationItem.TYPE_PARAMETER.equals(type)) return "PARAMETER ALERT";
        if (NotificationItem.TYPE_HARVEST.equals(type)) return "HARVEST READY";
        if (NotificationItem.TYPE_HARDWARE.equals(type)) return "HARDWARE ISSUE";
        if (NotificationItem.TYPE_CONNECTIVITY_OFFLINE.equals(type)) return "DEVICE UNREACHABLE";
        if (NotificationItem.TYPE_CONNECTIVITY_RECOVERY.equals(type)) return "DEVICE BACK ONLINE";
        return "INFORMATION";
    }

    private List<NotificationItem> notifications = new ArrayList<>();
    private final OnNotificationClickListener clickListener;
    private final Runnable markAllReadAction;
    private boolean markAllEnabled;
    private boolean markingAllRead;
    // Selection mode (see NotificationFragment) - the Set is owned and
    // mutated by the Fragment; the adapter only ever reads it to decide
    // checkbox visibility/checked state, so there is a single source of
    // truth for "what's selected" rather than a second copy that could
    // drift out of sync with it.
    private boolean selectionModeActive;
    private Set<String> selectedDocIds = Collections.emptySet();
    // Fallback-tier cache: resolves a recorder UID to a display name (from
    // users/{uid}.fullName, the same source the Harvest page itself uses)
    // only when a record predates the recorderName snapshot. Avoids
    // re-fetching the same profile on every scroll/rebind.
    private final Map<String, String> resolvedRecorderNames = new HashMap<>();
    // Without this, switching to a filter that reveals a batch of not-yet-
    // resolved harvest rows at once (e.g. many older records lacking a
    // recorderName snapshot) fired one Firestore read PER BIND for the same
    // uid, since resolvedRecorderNames is only populated after a read
    // completes. Guards against issuing a second read for a uid that's
    // already in flight; cleared on completion either way so a failed
    // lookup can still be retried on a later bind.
    private final Set<String> pendingRecorderLookups = new HashSet<>();

    public NotificationAdapter(OnNotificationClickListener clickListener,
                               Runnable markAllReadAction) {
        this.clickListener = clickListener;
        this.markAllReadAction = markAllReadAction;
    }

    /**
     * Replaces the rendered list, dispatching only the minimal set of
     * RecyclerView updates via DiffUtil instead of notifyDataSetChanged() -
     * with thousands of historical notifications loaded, a full-adapter
     * rebind on every Firestore snapshot or single-item read-state tap was
     * the main source of main-thread jank (see the performance audit this
     * redesign is based on). Must be called on the main thread; the diff
     * itself is cheap enough (list of rendered/visible-filtered items, not
     * the full raw history) to compute inline here.
     */
    public void submitList(List<NotificationItem> newList) {
        List<NotificationItem> old = notifications;
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffCallback(old, newList));
        notifications = newList;
        result.dispatchUpdatesTo(this);
    }

    public void setSelectionMode(boolean active, Set<String> selectedDocIds) {
        this.selectedDocIds = selectedDocIds;
        if (this.selectionModeActive == active) {
            // Selection contents can still have changed (e.g. Cancel clears
            // the set) even when the mode flag itself didn't flip.
            notifyItemRangeChanged(0, notifications.size());
            return;
        }
        this.selectionModeActive = active;
        notifyItemRangeChanged(0, notifications.size());
    }

    /** Selection changed but mode didn't - refreshes checkbox states only. */
    public void refreshSelectionState() {
        if (!selectionModeActive) return;
        notifyItemRangeChanged(0, notifications.size());
    }

    public void setMarkAllReadState(boolean enabled, boolean loading) {
        if (markAllEnabled == enabled && markingAllRead == loading) return;
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

            String timeStr = DateUtils.formatDateTime(displayTimestamp(item));
            contentHolder.tvTimestamp.setText(timeStr);

            String title = titleForType(item.type);
            int color = ContextCompat.getColor(contentHolder.itemView.getContext(), R.color.state_no_data);
            int iconRes = R.drawable.nav_notif_icon;

            if (NotificationItem.TYPE_PARAMETER.equals(item.type)) {
                iconRes = R.drawable.ic_error_red;
                color = ContextCompat.getColor(contentHolder.itemView.getContext(), R.color.state_critical);
            } else if (NotificationItem.TYPE_HARVEST.equals(item.type)) {
                iconRes = R.drawable.ic_harvest_green;
                color = ContextCompat.getColor(contentHolder.itemView.getContext(), R.color.state_success);
            } else if (NotificationItem.TYPE_HARDWARE.equals(item.type)) {
                iconRes = R.drawable.ic_hardware_orange;
                color = 0xFFEF6C00;
            } else if (NotificationItem.TYPE_CONNECTIVITY_OFFLINE.equals(item.type)) {
                iconRes = R.drawable.ic_error_red;
                color = ContextCompat.getColor(contentHolder.itemView.getContext(), R.color.state_critical);
            } else if (NotificationItem.TYPE_CONNECTIVITY_RECOVERY.equals(item.type)) {
                iconRes = R.drawable.ic_hardware_orange;
                color = ContextCompat.getColor(contentHolder.itemView.getContext(), R.color.state_success);
            }

            contentHolder.tvTitle.setText(title);
            contentHolder.tvTitle.setTextColor(color);
            contentHolder.ivIcon.setImageResource(iconRes);
            contentHolder.ivIcon.setColorFilter(color);
            contentHolder.viewTypeColor.setBackgroundColor(color);
            contentHolder.iconBackground.getBackground().setTint(color);

            bindRecordedBy(contentHolder, item);

            // Read / Unread UI formatting
            if (item.isReadForCurrentUser) {
                if (contentHolder.vUnreadDot != null) contentHolder.vUnreadDot.setVisibility(View.GONE);
                contentHolder.tvMessage.setTypeface(null, Typeface.NORMAL);
                contentHolder.tvMessage.setTextColor(0xFF666666);
            } else {
                if (contentHolder.vUnreadDot != null) contentHolder.vUnreadDot.setVisibility(View.VISIBLE);
                contentHolder.tvMessage.setTypeface(null, Typeface.BOLD);
                contentHolder.tvMessage.setTextColor(ContextCompat.getColor(
                        contentHolder.itemView.getContext(), R.color.black));
            }

            if (contentHolder.cbSelect != null) {
                if (selectionModeActive) {
                    contentHolder.cbSelect.setVisibility(View.VISIBLE);
                    contentHolder.cbSelect.setChecked(item.docId != null && selectedDocIds.contains(item.docId));
                } else {
                    contentHolder.cbSelect.setVisibility(View.GONE);
                }
            }

            contentHolder.itemView.setOnClickListener(v -> {
                if (clickListener != null && !item.isHeader) {
                    clickListener.onNotificationClick(item);
                }
            });
        }
    }

    // Shares one secondary-note line with two mutually exclusive uses (a
    // notification is never both a stored harvest record and an offline-
    // replayed event): "Recorded by" for an actual stored harvest record,
    // or a subtle note when the event was captured/delivered while the
    // device was offline. The persisted recorder-name snapshot is preferred
    // over a live UID lookup, which is only a fallback for older records.
    private void bindRecordedBy(ContentViewHolder holder, NotificationItem item) {
        if (holder.tvRecordedBy == null) return;

        if (item.offlineRecorded) {
            holder.tvRecordedBy.setVisibility(View.VISIBLE);
            holder.tvRecordedBy.setText(item.smsFallbackUsed
                    ? "Delivered by SMS while device was offline"
                    : "Recorded while device was offline");
            return;
        }

        if (!NotificationItem.TYPE_HARVEST.equals(item.type)
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

    /**
     * Item identity is docId for content rows, headerText for headers (both
     * stable across re-renders of the same underlying data); "contents the
     * same" compares every field that actually affects rendering, so a
     * single item's read-state flip only triggers a targeted rebind of that
     * one row instead of the whole list.
     */
    private static class DiffCallback extends DiffUtil.Callback {
        private final List<NotificationItem> oldList;
        private final List<NotificationItem> newList;

        DiffCallback(List<NotificationItem> oldList, List<NotificationItem> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }

        @Override
        public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            NotificationItem a = oldList.get(oldItemPosition);
            NotificationItem b = newList.get(newItemPosition);
            if (a.isHeader != b.isHeader) return false;
            if (a.isHeader) return Objects.equals(a.headerText, b.headerText);
            return a.docId != null && a.docId.equals(b.docId);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            NotificationItem a = oldList.get(oldItemPosition);
            NotificationItem b = newList.get(newItemPosition);
            if (a.isHeader) return a.showMarkAllAction == b.showMarkAllAction;
            return a.isReadForCurrentUser == b.isReadForCurrentUser
                    && Objects.equals(a.message, b.message)
                    && a.timestamp == b.timestamp
                    && Objects.equals(a.occurredAtMs, b.occurredAtMs)
                    && Objects.equals(a.type, b.type)
                    && Objects.equals(a.parameterKey, b.parameterKey)
                    && a.offlineRecorded == b.offlineRecorded
                    && a.smsFallbackUsed == b.smsFallbackUsed
                    && Objects.equals(a.recorderName, b.recorderName)
                    && Objects.equals(a.recorderUid, b.recorderUid);
        }
    }
}
