package com.example.basilience;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.database.FirebaseDatabase;
import android.util.Log;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
}
