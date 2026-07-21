package com.example.basilience;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {

    private final List<Device> deviceList;
    private final OnItemClickListener clickListener;
    private final OnItemLongClickListener longClickListener;

    // Interface para sa Click / Tap
    public interface OnItemClickListener {
        void onItemClick(Device device);
    }

    // 🔥 Interface para sa Long Press / Unclaim
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

    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDeviceAvatar;
        private final TextView tvDeviceName;
        private final TextView tvDeviceStatus;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDeviceAvatar = itemView.findViewById(R.id.tvDeviceAvatar);
            tvDeviceName = itemView.findViewById(R.id.tvDeviceName);
            tvDeviceStatus = itemView.findViewById(R.id.tvDeviceStatus);
        }

        public void bind(final Device device, final OnItemClickListener clickListener, final OnItemLongClickListener longClickListener) {
            String name = device.getDevice_name();
            tvDeviceName.setText(name);

            if (device.getStatus() != null) {
                tvDeviceStatus.setText("Status: " + device.getStatus().toUpperCase());
            } else {
                tvDeviceStatus.setText("Status: UNKNOWN");
            }

            if (name != null && !name.isEmpty()) {
                String firstLetter = name.substring(0, 1).toUpperCase();
                tvDeviceAvatar.setText(firstLetter);
            } else {
                tvDeviceAvatar.setText("D");
            }

            // Kapag pinindot lang (Tap)
            itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onItemClick(device);
                }
            });


            itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onItemLongClick(device);
                }
                return true; // Return true para hindi na gumana ang normal click
            });
        }
    }
}