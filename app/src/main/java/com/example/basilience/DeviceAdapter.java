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
    private final OnItemClickListener listener;

    // Interface para malaman ng Fragment kung anong card ang pinindot
    public interface OnItemClickListener {
        void onItemClick(Device device);
    }

    public DeviceAdapter(List<Device> deviceList, OnItemClickListener listener) {
        this.deviceList = deviceList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Dito natin binabasa ang ginawa mong layout card
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false); // Siguraduhing "item_device.xml" ang pangalan ng layout mo
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        Device device = deviceList.get(position);
        holder.bind(device, listener);
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDeviceAvatar;
        private final TextView tvDeviceName;
        private final TextView tvDeviceStatus;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            // I-bind ang mga text view base sa IDs sa layout mo
            tvDeviceAvatar = itemView.findViewById(R.id.tvDeviceAvatar);
            tvDeviceName = itemView.findViewById(R.id.tvDeviceName);
            tvDeviceStatus = itemView.findViewById(R.id.tvDeviceStatus);
        }

        public void bind(final Device device, final OnItemClickListener listener) {
            String name = device.getDevice_name();
            tvDeviceName.setText(name);
            tvDeviceStatus.setText("Status: " + device.getStatus().toUpperCase());

            // Kunin ang unang letra ng device name para ilagay sa circle avatar (e.g. "Basil 1" -> "B")
            if (name != null && !name.isEmpty()) {
                String firstLetter = name.substring(0, 1).toUpperCase();
                tvDeviceAvatar.setText(firstLetter);
            } else {
                tvDeviceAvatar.setText("D");
            }

            // Kapag pinindot ang card
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(device);
                }
            });
        }
    }
}