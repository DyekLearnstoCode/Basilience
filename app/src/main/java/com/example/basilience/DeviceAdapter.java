package com.example.basilience;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.content.ContextCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {

    private final List<Device> deviceList;
    private final OnItemClickListener clickListener;
    private final OnItemLongClickListener longClickListener;

    public interface OnItemClickListener {
        void onItemClick(Device device);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(Device device);
    }

    public DeviceAdapter(List<Device> deviceList, OnItemClickListener clickListener, OnItemLongClickListener longClickListener) {
        this.deviceList = deviceList;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        Device device = deviceList.get(position);
        holder.bind(device, clickListener, longClickListener);
    }

    @Override
    public int getItemCount() {
        return deviceList != null ? deviceList.size() : 0;
    }

    @Override
    public void onViewRecycled(@NonNull DeviceViewHolder holder) {
        super.onViewRecycled(holder);
        holder.cleanup();
    }

    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDeviceAvatar;
        private final TextView tvDeviceName;
        private final TextView tvDeviceStatus;
        private final View vStatusDot;
        private ValueEventListener statusListener;
        private DatabaseReference statusRef;
        private Boolean backendOnline;
        private Long lastServerSeen;
        private boolean provisioning;
        private final Handler statusHandler = new Handler(Looper.getMainLooper());
        private final Runnable refreshStatus = new Runnable() {
            @Override
            public void run() {
                applyResolvedStatus();
                statusHandler.postDelayed(this, 2_000L);
            }
        };

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDeviceAvatar = itemView.findViewById(R.id.tvDeviceAvatar);
            tvDeviceName = itemView.findViewById(R.id.tvDeviceName);
            tvDeviceStatus = itemView.findViewById(R.id.tvDeviceStatus);
            vStatusDot = itemView.findViewById(R.id.vStatusDot);
        }

        public void bind(final Device device, final OnItemClickListener clickListener, final OnItemLongClickListener longClickListener) {
            String name = device.getDeviceName();
            tvDeviceName.setText(name != null ? name : "Device");

            if (name != null && !name.isEmpty()) {
                String firstLetter = name.substring(0, 1).toUpperCase();
                tvDeviceAvatar.setText(firstLetter);
            } else {
                tvDeviceAvatar.setText("D");
            }

            // Remove existing listener if recycled
            if (statusRef != null && statusListener != null) {
                statusRef.removeEventListener(statusListener);
            }

            backendOnline = null;
            lastServerSeen = null;
            provisioning = false;
            applyStatus(DeviceConnectivityState.RECONNECTING);

            // Realtime listener for live status updates
            if (device.getDeviceId() != null) {
                String rtdbUrl = "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app";
                statusRef = FirebaseDatabase.getInstance(rtdbUrl).getReference("devices/" + device.getDeviceId());
                
                statusListener = new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        backendOnline = snapshot.child("status/online").getValue(Boolean.class);
                        lastServerSeen = snapshot.child("status/lastServerSeen").getValue(Long.class);
                        provisioning = Boolean.TRUE.equals(
                                snapshot.child("status/provisioning").getValue(Boolean.class));
                        applyResolvedStatus();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) { }
                };

                statusRef.addValueEventListener(statusListener);
                statusHandler.removeCallbacks(refreshStatus);
                statusHandler.post(refreshStatus);
            }

            itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onItemClick(device);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onItemLongClick(device);
                }
                return true;
            });
        }

        public void cleanup() {
            if (statusRef != null && statusListener != null) {
                statusRef.removeEventListener(statusListener);
                statusRef = null;
                statusListener = null;
            }
            statusHandler.removeCallbacks(refreshStatus);
            backendOnline = null;
            lastServerSeen = null;
            provisioning = false;
        }

        private void applyResolvedStatus() {
            applyStatus(DeviceConnectionManager.resolveState(
                    backendOnline, lastServerSeen, provisioning, System.currentTimeMillis()));
        }

        private void applyStatus(DeviceConnectivityState state) {
            tvDeviceStatus.setText(state.getLabel());
            tvDeviceStatus.setTextColor(Color.parseColor("#757575"));
            if (vStatusDot != null) {
                int color = ContextCompat.getColor(itemView.getContext(), state.getColorRes());
                vStatusDot.setBackgroundTintList(ColorStateList.valueOf(color));
            }
        }
    }
}
