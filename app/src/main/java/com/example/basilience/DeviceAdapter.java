package com.example.basilience;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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

            // Set initial state from Firestore model
            applyStatus(device.isOnline(), device.getStatus());

            // Realtime listener for live status updates
            if (device.getDeviceId() != null) {
                String rtdbUrl = "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app";
                statusRef = FirebaseDatabase.getInstance(rtdbUrl).getReference("devices/" + device.getDeviceId());
                
                statusListener = new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Boolean statusOnline = snapshot.child("status/online").getValue(Boolean.class);
                        Boolean wifiConn = snapshot.child("status/wifiConnected").getValue(Boolean.class);

                        boolean isOnline = statusOnline != null && statusOnline;
                        boolean isWifiConnected = wifiConn == null || wifiConn;

                        if (isOnline && !isWifiConnected) {
                            applyStatusCustom("CONNECTING", "Connecting to Internet", Color.parseColor("#2196F3")); // Blue
                        } else if (isOnline) {
                            applyStatusCustom("ONLINE", "Online", Color.parseColor("#4CAF50")); // Green
                        } else {
                            // wifiConnected is only the ESP32's last reported value. Once the
                            // device is offline it cannot be presented as a current Wi-Fi state.
                            applyStatusCustom("OFFLINE", "Device Offline", Color.parseColor("#F44336")); // Wi-Fi state is stale/unknown
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) { }
                };

                statusRef.addValueEventListener(statusListener);
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
        }

        private void applyStatus(boolean isOnline, String rawStatus) {
            if (rawStatus != null && rawStatus.equalsIgnoreCase("CONNECTING")) {
                applyStatusCustom("CONNECTING", "Connecting to Internet", Color.parseColor("#2196F3")); // Blue
            } else if (isOnline) {
                applyStatusCustom("ONLINE", "Online", Color.parseColor("#4CAF50")); // Green
            } else {
                applyStatusCustom("OFFLINE", "Device Offline", Color.parseColor("#F44336")); // Wi-Fi state is stale/unknown
            }
        }

        private void applyStatusCustom(String code, String label, int color) {
            tvDeviceStatus.setText(label);
            tvDeviceStatus.setTextColor(Color.parseColor("#757575"));
            if (vStatusDot != null) {
                vStatusDot.setBackgroundTintList(ColorStateList.valueOf(color));
            }
        }
    }
}
