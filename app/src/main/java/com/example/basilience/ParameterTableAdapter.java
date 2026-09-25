package com.example.basilience;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.basilience.models.ParameterTableRow;

import java.util.List;

/** Renders one row of Parameter Report's combined readings table - see item_parameter_table_row.xml. */
public class ParameterTableAdapter extends RecyclerView.Adapter<ParameterTableAdapter.ViewHolder> {

    private final List<ParameterTableRow> rows;

    public ParameterTableAdapter(List<ParameterTableRow> rows) {
        this.rows = rows;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_parameter_table_row, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ParameterTableRow row = rows.get(position);
        holder.colDateTime.setText(row.dateTimeText);
        bindCell(holder.colPh, row.ph, row.phOutOfRange);
        bindCell(holder.colEc, row.ec, row.ecOutOfRange);
        bindCell(holder.colAirTemp, row.airTemp, row.airTempOutOfRange);
        bindCell(holder.colHumidity, row.humidity, row.humidityOutOfRange);
        bindCell(holder.colWaterTemp, row.waterTemp, row.waterTempOutOfRange);
        bindCell(holder.colWaterLevel, row.waterLevel, row.waterLevelOutOfRange);
    }

    private void bindCell(TextView view, String text, boolean outOfRange) {
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(view.getContext(),
                outOfRange ? R.color.state_critical : R.color.state_primary_text));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView colDateTime, colPh, colEc, colAirTemp, colHumidity, colWaterTemp, colWaterLevel;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            colDateTime = itemView.findViewById(R.id.colDateTime);
            colPh = itemView.findViewById(R.id.colPh);
            colEc = itemView.findViewById(R.id.colEc);
            colAirTemp = itemView.findViewById(R.id.colAirTemp);
            colHumidity = itemView.findViewById(R.id.colHumidity);
            colWaterTemp = itemView.findViewById(R.id.colWaterTemp);
            colWaterLevel = itemView.findViewById(R.id.colWaterLevel);
        }
    }
}
