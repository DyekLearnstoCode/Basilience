package com.example.basilience;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class Personnel_Adapter extends RecyclerView.Adapter<Personnel_Adapter.ViewHolder> {

    List<Personnel> list;
    OnItemClick listener;

    public interface OnItemClick {
        void onClick(Personnel p, int position);
    }

    public Personnel_Adapter(List<Personnel> list, OnItemClick listener) {
        this.list = list;
        this.listener = listener;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_personnel, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder h, int pos) {
        Personnel p = list.get(pos);

        String name = p.getName() != null ? p.getName() : "";
        String role = p.getRole() != null ? p.getRole() : "";

        h.name.setText(name);
        h.role.setText(role);

        // Avatar = first letter (safe)
        String firstLetter = name.trim().isEmpty() ? "?" : name.trim().substring(0, 1).toUpperCase();
        h.avatar.setText(firstLetter);

        h.itemView.setOnClickListener(v -> {
            int position = h.getAdapterPosition();
            if (position != RecyclerView.NO_POSITION) {
                listener.onClick(list.get(position), position);
            }
        });
    }

    @Override
    public int getItemCount() { return list.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, role, avatar;

        public ViewHolder(View v) {
            super(v);
            name = v.findViewById(R.id.tvName);
            role = v.findViewById(R.id.tvRole);
            avatar = v.findViewById(R.id.tvAvatar);
        }
    }
}