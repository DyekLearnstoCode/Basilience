package com.example.basilience;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

public class Parameters_Monitoring_Fragment extends Fragment {

    public Parameters_Monitoring_Fragment() {
        // Required empty public constructor
    }

    private Database_Helper dbHelper;
    private ListenerRegistration statusListener;

    enum Status { NORMAL, WARNING }

    private boolean isDialogShowing = false;
    private boolean isManualMode = false;

    // ===== MAIN =====
    private TextView tvPH, tvEC, tvTemp, tvHumidity;
    private TextView tvWaterTemp, tvWaterLevel;

    // ===== ACTUATORS =====
    class Actuator {
        String name;
        String dbKey;
        boolean isOn;

        Actuator(String name, String dbKey) {
            this.name = name;
            this.dbKey = dbKey;
            this.isOn = false;
        }
    }

    private DatabaseReference sensorsRef;
    private ValueEventListener sensorsListener;

    private final Actuator waterPumpValve = new Actuator("Water Pump (Valve)", "waterPump");
    private final Actuator canopyFan = new Actuator("Canopy Fan", "canopyFan");
    private final Actuator growLights = new Actuator("Grow Lights", "light");
    private final Actuator phUp = new Actuator("pH Up", "phUp");
    private final Actuator phDown = new Actuator("pH Down", "phDown");
    private final Actuator nutrients = new Actuator("Nutrients (EC)", "nutrients");
    private final Actuator waterEc = new Actuator("Water (EC)", "waterEc");
    private final Actuator fogger = new Actuator("Fogger", "fogger");
    private final Actuator reservoirFan = new Actuator("Reservoir Fan", "reservoirFan");
    private final Actuator waterCooling = new Actuator("Water Cooling", "waterCooling");
    private final Actuator waterHotting = new Actuator("Water Hotting", "waterHotting");

    private View actWaterPumpValve, actCanopyFan, actGrowLights, actPhUp, actPhDown, actNutrients, actWaterEc, actFogger, actReservoirFan, actWaterCooling, actWaterHotting;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.parameters_monitoring, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();

        // Back button
        View back = view.findViewById(R.id.btnBack);
        if (back != null) {
            back.setVisibility(View.VISIBLE);
            back.setOnClickListener(v -> Navigation.findNavController(view).popBackStack());
        }

        // Initialize Views
        setupParameterCards(view);

        // ===== MODE SWITCH =====
        SwitchMaterial modeSwitch = view.findViewById(R.id.switchMode);
        if (modeSwitch != null) {
            modeSwitch.setEnabled(false); // Disable until UID is resolved and data is loaded
            modeSwitch.setAlpha(0.5f);
            modeSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
                isManualMode = checked;
                modeSwitch.setText(checked ? "Manual Mode" : "Auto Mode");
                updateActuatorControls();
                dbHelper.updateManualMode(checked);
            });
        }

        // ===== ACTUATORS =====
        actWaterPumpValve = view.findViewById(R.id.actWaterPumpValve);
        actCanopyFan = view.findViewById(R.id.actCanopyFan);
        actGrowLights = view.findViewById(R.id.actGrowLights);
        actPhUp = view.findViewById(R.id.actPhUp);
        actPhDown = view.findViewById(R.id.actPhDown);
        actNutrients = view.findViewById(R.id.actNutrients);
        actWaterEc = view.findViewById(R.id.actWaterEc);
        actFogger = view.findViewById(R.id.actFogger);
        actReservoirFan = view.findViewById(R.id.actReservoirFan);
        actWaterCooling = view.findViewById(R.id.actWaterCooling);
        actWaterHotting = view.findViewById(R.id.actWaterHotting);

        if (actWaterPumpValve != null) setupActuatorUI(actWaterPumpValve, waterPumpValve);
        if (actCanopyFan != null) setupActuatorUI(actCanopyFan, canopyFan);
        if (actGrowLights != null) setupActuatorUI(actGrowLights, growLights);
        if (actPhUp != null) setupActuatorUI(actPhUp, phUp);
        if (actPhDown != null) setupActuatorUI(actPhDown, phDown);
        if (actNutrients != null) setupActuatorUI(actNutrients, nutrients);
        if (actWaterEc != null) setupActuatorUI(actWaterEc, waterEc);
        if (actFogger != null) setupActuatorUI(actFogger, fogger);
        if (actReservoirFan != null) setupActuatorUI(actReservoirFan, reservoirFan);
        if (actWaterCooling != null) setupActuatorUI(actWaterCooling, waterCooling);
        if (actWaterHotting != null) setupActuatorUI(actWaterHotting, waterHotting);

        updateActuatorControls(); 

        startRealTimeMonitoring();
    }

    private void setupParameterCards(View view) {
        View cardPH = view.findViewById(R.id.cardPH);
        View cardEC = view.findViewById(R.id.cardEC);
        View cardTemp = view.findViewById(R.id.cardTemp);
        View cardHumidity = view.findViewById(R.id.cardHumidity);
        View cardWaterTemp = view.findViewById(R.id.cardWaterTemp);
        View cardWaterLevel = view.findViewById(R.id.cardWaterLevel);

        if (cardPH != null) {
            tvPH = cardPH.findViewById(R.id.tvValue);
            TextView label = cardPH.findViewById(R.id.tvLabel);
            if (label != null) label.setText("pH");
        }
        if (cardEC != null) {
            tvEC = cardEC.findViewById(R.id.tvValue);
            TextView label = cardEC.findViewById(R.id.tvLabel);
            if (label != null) label.setText("EC");
        }
        if (cardTemp != null) {
            tvTemp = cardTemp.findViewById(R.id.tvValue);
            TextView label = cardTemp.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Air Temp");
        }
        if (cardHumidity != null) {
            tvHumidity = cardHumidity.findViewById(R.id.tvValue);
            TextView label = cardHumidity.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Humidity");
        }
        if (cardWaterTemp != null) {
            tvWaterTemp = cardWaterTemp.findViewById(R.id.tvValue);
            TextView label = cardWaterTemp.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Water Temp");
        }
        if (cardWaterLevel != null) {
            tvWaterLevel = cardWaterLevel.findViewById(R.id.tvValue);
            TextView label = cardWaterLevel.findViewById(R.id.tvLabel);
            if (label != null) label.setText("Water Level");
        }
    }



    private void startRealTimeMonitoring() {

        dbHelper.resolveDataUid().addOnSuccessListener(uid -> {

            if (uid != null) {

                dbHelper.setTargetUid(uid);

                View v = getView();

                if (v != null) {

                    SwitchMaterial modeSwitch =
                            v.findViewById(R.id.switchMode);

                    if (modeSwitch != null) {
                        modeSwitch.setEnabled(true);
                        modeSwitch.setAlpha(1.0f);
                    }
                }

                sensorsRef = dbHelper
                        .getSensorsReference();

                sensorsListener =
                        new ValueEventListener() {

                            @Override
                            public void onDataChange(
                                    @NonNull DataSnapshot snapshot) {

                                Double temp =
                                        snapshot.child("temperature")
                                                .getValue(Double.class);

                                Double humidity =
                                        snapshot.child("humidity")
                                                .getValue(Double.class);

                                Double waterTemp =
                                        snapshot.child("waterTemperature")
                                                .getValue(Double.class);

                                Double ph =
                                        snapshot.child("ph")
                                                .getValue(Double.class);

                                Double ec =
                                        snapshot.child("ec")
                                                .getValue(Double.class);

                                Double waterLevel =
                                        snapshot.child("waterLevel")
                                                .getValue(Double.class);

                                if (temp != null)
                                    tvTemp.setText(
                                            String.format("%.1f °C", temp));

                                if (humidity != null)
                                    tvHumidity.setText(
                                            String.format("%.1f %%", humidity));

                                if (waterTemp != null)
                                    tvWaterTemp.setText(
                                            String.format("%.1f °C", waterTemp));

                                if (ph != null)
                                    tvPH.setText(
                                            String.format("%.2f", ph));

                                if (ec != null)
                                    tvEC.setText(
                                            String.format("%.2f", ec));

                                if (waterLevel != null)
                                    tvWaterLevel.setText(
                                            String.format("%.0f %%", waterLevel));
                            }

                            @Override
                            public void onCancelled(
                                    @NonNull DatabaseError error) {

                                Log.e(
                                        "RTDB",
                                        error.getMessage()
                                );
                            }
                        };

                sensorsRef.addValueEventListener(
                        sensorsListener);
            }
        });
    }

    private void updateUIFromSnapshot(DocumentSnapshot snapshot) {
        Double pH = snapshot.getDouble("ph");
        Double ec = snapshot.getDouble("ec");
        Double temp = snapshot.getDouble("temp");
        Double hum = snapshot.getDouble("humidity");
        Double wTemp = snapshot.getDouble("waterTemp");
        Double wLevel = snapshot.getDouble("waterLevel");

        // Sync Manual Mode
        Boolean manualMode = snapshot.getBoolean("manualMode");
        isManualMode = (manualMode != null) ? manualMode : false;

        SwitchMaterial modeSwitch = getView() != null ? getView().findViewById(R.id.switchMode) : null;
        if (modeSwitch != null) {
            modeSwitch.setOnCheckedChangeListener(null);
            modeSwitch.setChecked(isManualMode);
            modeSwitch.setText(isManualMode ? "Manual Mode" : "Auto Mode");
            modeSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
                isManualMode = checked;
                modeSwitch.setText(checked ? "Manual Mode" : "Auto Mode");
                updateActuatorControls();
                dbHelper.updateManualMode(checked);
            });
        }
        updateActuatorControls();

        List<String> warnings = new ArrayList<>();
        List<String> actions = new ArrayList<>();

        if (pH != null && tvPH != null) process(tvPH, "pH", pH, 5.5, 6.5, warnings, actions);
        if (ec != null && tvEC != null) process(tvEC, "EC", ec, 1.0, 2.0, warnings, actions);
        if (temp != null && tvTemp != null) process(tvTemp, "Air Temp", temp, 20, 30, warnings, actions);
        if (hum != null && tvHumidity != null) process(tvHumidity, "Humidity", hum, 50, 80, warnings, actions);
        if (wTemp != null && tvWaterTemp != null) process(tvWaterTemp, "Water Temp", wTemp, 18, 24, warnings, actions);
        if (wLevel != null && tvWaterLevel != null) process(tvWaterLevel, "Water Level", wLevel, 60, 100, warnings, actions);

        // Always sync actuator states from DB to ensure UI is up-to-date on load
        Map<String, Object> actuators = (Map<String, Object>) snapshot.get("actuators");
        if (actuators != null) {
            syncActuatorState(waterPumpValve, actWaterPumpValve, actuators.get(waterPumpValve.dbKey));
            syncActuatorState(canopyFan, actCanopyFan, actuators.get(canopyFan.dbKey));
            syncActuatorState(growLights, actGrowLights, actuators.get(growLights.dbKey));
            syncActuatorState(phUp, actPhUp, actuators.get(phUp.dbKey));
            syncActuatorState(phDown, actPhDown, actuators.get(phDown.dbKey));
            syncActuatorState(nutrients, actNutrients, actuators.get(nutrients.dbKey));
            syncActuatorState(waterEc, actWaterEc, actuators.get(waterEc.dbKey));
            syncActuatorState(fogger, actFogger, actuators.get(fogger.dbKey));
            syncActuatorState(reservoirFan, actReservoirFan, actuators.get(reservoirFan.dbKey));
            syncActuatorState(waterCooling, actWaterCooling, actuators.get(waterCooling.dbKey));
            syncActuatorState(waterHotting, actWaterHotting, actuators.get(waterHotting.dbKey));
        }

        if (!warnings.isEmpty()) {
            showCombinedDialog(warnings, actions);
        }
    }

    private void process(TextView view, String name, double value, double min, double max,
                         List<String> warnings, List<String> actions) {
        Status current = (value < min || value > max) ? Status.WARNING : Status.NORMAL;
        String text = formatValue(name, value);

        if (current == Status.WARNING) {
            view.setText(text + " ⚠");
            view.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            warnings.add(name);
            actions.add(getSystemAction(name));
        } else {
            view.setText(text);
            view.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        }
    }

    private String formatValue(String name, double value) {
        if (name.contains("Temp")) return (int) value + "°C";
        if (name.equals("pH") || name.equals("EC")) return String.format("%.2f", value);
        return (int) value + "%";
    }

    private String getSystemAction(String parameter) {
        switch (parameter) {
            case "pH": return "Dosing pH Up/Down";
            case "EC": return "Adjusting nutrient dosing";
            case "Water Level": return "Refilling water";
            case "Air Temp": return "Fan activated";
            default: return "System adjusting";
        }
    }

    private void syncActuatorState(Actuator actuator, View card, Object stateObj) {
        if (card == null) return;
        if (stateObj instanceof Boolean) {
            boolean state = (Boolean) stateObj;
            actuator.isOn = state;
            updateActuatorUI(card, state);
        }
    }

    private void setupActuatorUI(View card, Actuator actuator) {
        TextView nameTv = card.findViewById(R.id.tvActuatorName);
        if (nameTv != null) nameTv.setText(actuator.name);
        
        SwitchMaterial toggle = card.findViewById(R.id.switchActuator);
        if (toggle != null) {
            toggle.setOnCheckedChangeListener((b, checked) -> {
                if (isManualMode) {
                    actuator.isOn = checked;
                    updateActuatorUI(card, checked);
                    dbHelper.updateActuatorState(actuator.dbKey, checked);
                }
            });
        }
    }

    private void updateActuatorUI(View card, boolean isOn) {
        TextView status = card.findViewById(R.id.tvStatus);
        SwitchMaterial toggle = card.findViewById(R.id.switchActuator);

        if (toggle != null) {
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(isOn);
            setupActuatorUI(card, getActuatorFromCard(card));
        }
        
        if (status != null) {
            status.setText(isOn ? "ON" : "OFF");
            status.setTextColor(getResources().getColor(
                    isOn ? android.R.color.holo_green_dark : android.R.color.holo_red_dark
            ));
        }
    }

    private Actuator getActuatorFromCard(View card) {
        if (card == actWaterPumpValve) return waterPumpValve;
        if (card == actCanopyFan) return canopyFan;
        if (card == actGrowLights) return growLights;
        if (card == actPhUp) return phUp;
        if (card == actPhDown) return phDown;
        if (card == actNutrients) return nutrients;
        if (card == actWaterEc) return waterEc;
        if (card == actFogger) return fogger;
        if (card == actReservoirFan) return reservoirFan;
        if (card == actWaterCooling) return waterCooling;
        return waterHotting;
    }

    private void updateActuatorControls() {
        setActuatorEnabled(actWaterPumpValve, isManualMode);
        setActuatorEnabled(actCanopyFan, isManualMode);
        setActuatorEnabled(actGrowLights, isManualMode);
        setActuatorEnabled(actPhUp, isManualMode);
        setActuatorEnabled(actPhDown, isManualMode);
        setActuatorEnabled(actNutrients, isManualMode);
        setActuatorEnabled(actWaterEc, isManualMode);
        setActuatorEnabled(actFogger, isManualMode);
        setActuatorEnabled(actReservoirFan, isManualMode);
        setActuatorEnabled(actWaterCooling, isManualMode);
        setActuatorEnabled(actWaterHotting, isManualMode);
    }

    private void setActuatorEnabled(View card, boolean enabled) {
        if (card == null) return;
        View toggle = card.findViewById(R.id.switchActuator);
        if (toggle != null) toggle.setEnabled(enabled);
        card.setAlpha(enabled ? 1.0f : 0.6f);
    }

    private void showCombinedDialog(List<String> warnings, List<String> actions) {
        if (isDialogShowing || getContext() == null) return;
        isDialogShowing = true;
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < warnings.size(); i++) {
            msg.append("• ").append(warnings.get(i)).append(": ").append(actions.get(i)).append("\n");
        }
        NotificationHelper.showNotification(requireContext(), "System Alert", msg.toString());
        // Note: we can't easily track dismissal of NotificationHelper currently to reset isDialogShowing
        // But since it's a dialog, it's fine for now.
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (sensorsRef != null &&
                sensorsListener != null) {

            sensorsRef.removeEventListener(
                    sensorsListener);
        }
    }
}
