package com.example.basilience;

import android.content.SharedPreferences;
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
            modeSwitch.setEnabled(false); // Disable until device ID is resolved
            modeSwitch.setAlpha(0.5f);
            
            SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            String role = prefs.getString("user_role", "FARMER");
            boolean isAdmin = "ADMIN".equalsIgnoreCase(role);

            modeSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
                if (isAdmin) {
                    isManualMode = checked;
                    modeSwitch.setText(checked ? "Manual Mode" : "Auto Mode");
                    updateActuatorControls();
                    dbHelper.updateManualMode(checked);
                }
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
        if (getContext() == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (deviceId == null) {
            Log.e("Monitoring", "No device selected");
            return;
        }

        dbHelper.setSelectedDeviceId(deviceId);

        View v = getView();
        if (v != null) {
            SwitchMaterial modeSwitch = v.findViewById(R.id.switchMode);
            if (modeSwitch != null) {
                SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
                String role = prefs.getString("user_role", "FARMER");
                boolean isAdmin = "ADMIN".equalsIgnoreCase(role);
                
                modeSwitch.setEnabled(isAdmin);
                modeSwitch.setAlpha(isAdmin ? 1.0f : 0.6f);
            }
        }

        sensorsRef = dbHelper.getSensorsReference();
        if (sensorsRef == null) {
            Log.e("Monitoring", "Sensors reference is null. Ensure deviceId is set.");
            return;
        }
        sensorsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;

                Double temp = snapshot.child("temperature").getValue(Double.class);
                Double humidity = snapshot.child("humidity").getValue(Double.class);
                Double waterTemp = snapshot.child("waterTemperature").getValue(Double.class);
                Double ph = snapshot.child("ph").getValue(Double.class);
                Double ec = snapshot.child("ec").getValue(Double.class);
                Double waterLevel = snapshot.child("waterLevel").getValue(Double.class);

                if (temp != null) tvTemp.setText(String.format("%.1f °C", temp));
                if (humidity != null) tvHumidity.setText(String.format("%.1f %%", humidity));
                if (waterTemp != null) tvWaterTemp.setText(String.format("%.1f °C", waterTemp));
                if (ph != null) tvPH.setText(String.format("%.2f", ph));
                if (ec != null) tvEC.setText(String.format("%.2f", ec));
                if (waterLevel != null) tvWaterLevel.setText(String.format("%.0f %%", waterLevel));

                // Sync Manual Mode from RTDB
                Boolean manualMode = snapshot.child("manualMode").getValue(Boolean.class);
                if (manualMode != null) {
                    isManualMode = manualMode;
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
                }

                // Sync Actuators from RTDB
                DataSnapshot actuators = snapshot.child("actuators");
                if (actuators.exists()) {
                    syncActuatorState(waterPumpValve, actWaterPumpValve, actuators.child(waterPumpValve.dbKey).getValue());
                    syncActuatorState(canopyFan, actCanopyFan, actuators.child(canopyFan.dbKey).getValue());
                    syncActuatorState(growLights, actGrowLights, actuators.child(growLights.dbKey).getValue());
                    syncActuatorState(phUp, actPhUp, actuators.child(phUp.dbKey).getValue());
                    syncActuatorState(phDown, actPhDown, actuators.child(phDown.dbKey).getValue());
                    syncActuatorState(nutrients, actNutrients, actuators.child(nutrients.dbKey).getValue());
                    syncActuatorState(waterEc, actWaterEc, actuators.child(waterEc.dbKey).getValue());
                    syncActuatorState(fogger, actFogger, actuators.child(fogger.dbKey).getValue());
                    syncActuatorState(reservoirFan, actReservoirFan, actuators.child(reservoirFan.dbKey).getValue());
                    syncActuatorState(waterCooling, actWaterCooling, actuators.child(waterCooling.dbKey).getValue());
                    syncActuatorState(waterHotting, actWaterHotting, actuators.child(waterHotting.dbKey).getValue());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("RTDB", error.getMessage());
            }
        };
        sensorsRef.addValueEventListener(sensorsListener);
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
            // Re-setup listener after programmatic check to avoid recursion and maintain logic
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
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (sensorsRef != null && sensorsListener != null) {
            sensorsRef.removeEventListener(sensorsListener);
        }
    }
}
