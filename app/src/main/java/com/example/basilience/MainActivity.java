package com.example.basilience;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import android.util.Log;
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

        FirebaseDatabase.getInstance()
                .getReference("test")
                .setValue("hello");

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);

        // Get the NavHostFragment
        NavHostFragment navHostFragment =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment);

        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            // Automatically handles navigation based on matching IDs between menu and nav_graph
            NavigationUI.setupWithNavController(bottomNav, navController);

            // Handle sub-screens showing "Home" as active and reset Home state on click
            navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                int id = destination.getId();
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
                           id == R.id.aboutFragment || id == R.id.tosFragment) {
                    bottomNav.getMenu().findItem(R.id.settings).setChecked(true);
                }
            });

            bottomNav.setOnItemSelectedListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.home) {
                    // Always navigate to the root 'home' destination and clear backstack
                    navController.navigate(R.id.home, null, new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.getGraph().getStartDestinationId(), true)
                            .setLaunchSingleTop(true)
                            .build());
                    return true;
                }
                // Use default behavior for other items
                return NavigationUI.onNavDestinationSelected(item, navController);
            });
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
    }

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
