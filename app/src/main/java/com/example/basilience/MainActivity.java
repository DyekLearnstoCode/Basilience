package com.example.basilience;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.example.basilience.AlertManager;

import android.view.View;
import android.widget.TextView;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.database.ValueEventListener;


public class MainActivity extends AppCompatActivity {

    private MaterialCardView activeAlertBanner;
    private TextView alertTitle;
    private TextView alertMessage;

    private AlertManager alertManager;
    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        activeAlertBanner = findViewById(R.id.activeAlertBanner);
        activeAlertBanner.setTranslationY(-300f);
        alertTitle = findViewById(R.id.alertTitle);
        alertMessage = findViewById(R.id.alertMessage);

        alertManager = new AlertManager(this);
        alertManager.startListening();
        startAlertBannerListener();

        // Realtime Database Ping (Test)
        FirebaseDatabase.getInstance()
                .getReference("test")
                .setValue("hello");

        bottomNav = findViewById(R.id.bottom_navigation);

        // Get the NavHostFragment
        NavHostFragment navHostFragment =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment);

        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();

            // Awtomatikong i-setup ang navigation batay sa matching IDs
            NavigationUI.setupWithNavController(bottomNav, navController);

            // --- DESTINATION LISTENER (STRICT VISIBILITY) ---
            navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                int id = destination.getId();

                // 🔥 PINAKAMALINIS NA LOGIC:
                // Hangga't hindi umaalis sa Device Management screen (ibig sabihin hindi pa pumipili o nagki-claim ng device),
                // LAGING NAKATAGO (GONE) ang bottom navigation bar!
                if (id == R.id.DeviceManagementFragment) {
                    bottomNav.setVisibility(View.GONE);
                } else {
                    // Lalabas lamang ang bottom navigation kapag lumipat na sa Dashboard (home) o ibang tabs
                    bottomNav.setVisibility(View.VISIBLE);
                }

                // Ang iyong orihinal na Bottom Navigation item matching logic
                if (id == R.id.home || id == R.id.parametersFragment || id == R.id.userGuideFragment ||
                        id == R.id.hardwareGuideFragment || id == R.id.mobileGuideFragment ||
                        id == R.id.reportschoiceFragment || id == R.id.reportsFragment ||
                        id == R.id.foggingReportsFragment || id == R.id.cycleDetailsFragment ||
                        id == R.id.cycleaddFragment || id == R.id.harvestLogFragment ||
                        id == R.id.personnelFragment || id == R.id.personneladdFragment ||
                        id == R.id.personneldetailsFragment) {
                    bottomNav.getMenu().findItem(R.id.home).setChecked(true);
                } else if (id == R.id.Notification) {
                    bottomNav.getMenu().findItem(R.id.Notification).setChecked(true);
                } else if (id == R.id.settings || id == R.id.accountFragment ||
<<<<<<< Updated upstream
                           id == R.id.aboutFragment || id == R.id.tosFragment) {
=======
                        id == R.id.aboutFragment || id == R.id.tosFragment) {
>>>>>>> Stashed changes
                    bottomNav.getMenu().findItem(R.id.settings).setChecked(true);
                }
            });

            // --- 3. SELECTION LISTENER ---
            bottomNav.setOnItemSelectedListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.home) {
                    navController.navigate(R.id.home, null, new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.getGraph().getStartDestinationId(), true)
                            .setLaunchSingleTop(true)
                            .build());
                    return true;
                }
                return NavigationUI.onNavDestinationSelected(item, navController);
            });
        }

        // Itago ang default Action Bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
    }
<<<<<<< Updated upstream

    private void startAlertBannerListener() {

        FirebaseDatabase.getInstance(
                        "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app"
                )
                .getReference("device")
                .addValueEventListener(new ValueEventListener() {

                    @Override
                    public void onDataChange(DataSnapshot snapshot) {

                        DataSnapshot alerts =
                                snapshot.child("alerts");

                        DataSnapshot status =
                                snapshot.child("status");

                        StringBuilder message =
                                new StringBuilder();

                        boolean hasAlert = false;

                        if(Boolean.TRUE.equals(
                                alerts.child("phOutOfRange")
                                        .getValue(Boolean.class)))
                        {
                            hasAlert = true;

                            message.append(
                                    "🧪 pH Out Of Range\n"
                            );

                            if(Boolean.TRUE.equals(
                                    status.child("phUp")
                                            .getValue(Boolean.class)))
                            {
                                message.append(
                                        "Action: Dosing pH Up\n\n"
                                );
                            }
                            else if(Boolean.TRUE.equals(
                                    status.child("phDown")
                                            .getValue(Boolean.class)))
                            {
                                message.append(
                                        "Action: Dosing pH Down\n\n"
                                );
                            }
                        }

                        if(Boolean.TRUE.equals(
                                alerts.child("ecLow")
                                        .getValue(Boolean.class)))
                        {
                            hasAlert = true;

                            message.append(
                                    "🌱 EC Low\n"
                            );

                            if(Boolean.TRUE.equals(
                                    status.child("nutrients")
                                            .getValue(Boolean.class)))
                            {
                                message.append(
                                        "Action: Nutrient Pump Running\n\n"
                                );
                            }
                            else
                            {
                                message.append(
                                        "Action: Awaiting Correction\n\n"
                                );
                            }
                        }

                        if(Boolean.TRUE.equals(
                                alerts.child("highTemperature")
                                        .getValue(Boolean.class)))
                        {
                            hasAlert = true;

                            message.append(
                                    "🌡 High Temperature\n"
                            );

                            if(Boolean.TRUE.equals(
                                    status.child("canopyFan")
                                            .getValue(Boolean.class)))
                            {
                                message.append(
                                        "Action: Cooling Fan Active\n\n"
                                );
                            }
                        }

                        if(Boolean.TRUE.equals(
                                alerts.child("lowWater")
                                        .getValue(Boolean.class)))
                        {
                            hasAlert = true;

                            message.append(
                                    "💧 Low Water Level\n"
                            );

                            message.append(
                                    "Action: Refill Required\n\n"
                            );
                        }

                        if(hasAlert)
                        {
                            if (activeAlertBanner.getVisibility() != View.VISIBLE) {

                                activeAlertBanner.setVisibility(View.VISIBLE);

                                activeAlertBanner.animate()
                                        .translationY(0)
                                        .setDuration(300)
                                        .start();
                            }

                            alertMessage.setText(
                                    message.toString()
                            );
                        }
                        else
                        {
                            activeAlertBanner.animate()
                                    .translationY(-300)
                                    .setDuration(300)
                                    .withEndAction(() ->
                                            activeAlertBanner.setVisibility(View.GONE))
                                    .start();
                        }
                    }

                    @Override
                    public void onCancelled(
                            DatabaseError error
                    ) {
                    }
                });
    }
}
=======
}
>>>>>>> Stashed changes
