package com.example.basilience;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class Personnel_Main_Fragment extends Fragment {

    private RecyclerView recyclerView;
    private final List<Personnel> list = new ArrayList<>();
    private Personnel_Adapter adapter;
    private android.widget.TextView tvLoadingPersonnel;

    private Database_Helper helper;

    public Personnel_Main_Fragment() {
        super(R.layout.personnel_main);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        helper = new Database_Helper();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.recyclerPersonnel);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        
        tvLoadingPersonnel = view.findViewById(R.id.tvLoadingPersonnel);

        Button btnAdd = view.findViewById(R.id.btnAddPersonnel);
        Button btnAddExisting = view.findViewById(R.id.btnAddExistingPersonnel);

        NavController navController =
                Navigation.findNavController(requireActivity(), R.id.nav_host_fragment);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        adapter = new Personnel_Adapter(list, (p, pos) -> {
            Bundle args = new Bundle();
            args.putString("personnelId", p.getId()); // IMPORTANT: docId for update/delete
            navController.navigate(R.id.action_personnelFragment_to_personneldetailsFragment, args);
        });
        recyclerView.setAdapter(adapter);

        // Listen for result from add fragment, then reload
        getParentFragmentManager().setFragmentResultListener(
                "personnel_add_result",
                getViewLifecycleOwner(),
                (requestKey, bundle) -> {
                    if (bundle.getBoolean("added", false)) {
                        loadFarmersFromFirestore();
                    }
                }
        );

        // Listen for result from details fragment (updated/deleted), then reload
        getParentFragmentManager().setFragmentResultListener(
                "personnel_details_result",
                getViewLifecycleOwner(),
                (requestKey, bundle) -> loadFarmersFromFirestore()
        );

        // initial load
        loadFarmersFromFirestore();

        // navigate to add fragment
        btnAdd.setOnClickListener(v -> navController.navigate(R.id.action_personnelFragment_to_personneladdFragment));
        btnAddExisting.setOnClickListener(v -> showAddExistingPersonnelDialog());
    }

    private void loadFarmersFromFirestore() {
        if (tvLoadingPersonnel != null) tvLoadingPersonnel.setVisibility(View.VISIBLE);
        if (recyclerView != null) recyclerView.setVisibility(View.GONE);

        helper.getMyPersonnelByRole(RoleConstants.ROLE_FARMER)
                .addOnSuccessListener(qs -> {
                    if (!isAdded()) return;
                    if (tvLoadingPersonnel != null) tvLoadingPersonnel.setVisibility(View.GONE);
                    list.clear();

                    for (DocumentSnapshot doc : qs.getDocuments()) {
                        String id = doc.getId();
                        String name = doc.getString("fullName"); // Changed from "name" to "fullName" to match createUserProfile
                        String role = doc.getString("role");
                        String email = doc.getString("email");
                        String phone = doc.getString("phone");

                        list.add(new Personnel(
                                id,
                                name != null ? name : "",
                                role != null ? role : "",
                                email != null ? email : "",
                                phone != null ? phone : ""
                        ));
                    }

                    adapter.notifyDataSetChanged();
                    
                    if (recyclerView != null) {
                        if (list.isEmpty()) {
                            tvLoadingPersonnel.setText("No personnel added yet.");
                            tvLoadingPersonnel.setVisibility(View.VISIBLE);
                        } else {
                            recyclerView.setVisibility(View.VISIBLE);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    if (tvLoadingPersonnel != null) {
                        tvLoadingPersonnel.setText("Failed to load personnel");
                    }
                    NotificationHelper.showError(requireContext(),
                            "Failed to load farmers: " + e.getMessage());
                });
    }

    private void showAddExistingPersonnelDialog() {
        View inputView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_personnel_email_input, null);
        TextInputEditText input = inputView.findViewById(R.id.etPersonnelEmail);

        androidx.appcompat.app.AlertDialog dialog = NotificationHelper.showCustomViewDialog(requireContext(), "Add Existing Personnel",
                "Link an existing unlinked personnel account using its email address.", inputView,
                "Add", "Cancel", (d, ignored) -> {
            String email = input.getText().toString().trim();
            if (email.isEmpty()) { input.setError("Email is required"); return; }
            MaterialButton positive = d.findViewById(R.id.dialog_button);
            MaterialButton negative = d.findViewById(R.id.dialog_button_secondary);
            if (positive != null) positive.setEnabled(false);
            if (negative != null) negative.setEnabled(false);
            helper.linkExistingPersonnelByEmail(email).addOnSuccessListener(unused -> {
                if (!isAdded()) return;
                d.dismiss();
                NotificationHelper.showSuccess(requireContext(), "Personnel linked successfully.");
                loadFarmersFromFirestore();
            }).addOnFailureListener(e -> {
                if (!isAdded()) return;
                if (positive != null) positive.setEnabled(true);
                if (negative != null) negative.setEnabled(true);
                NotificationHelper.showError(requireContext(), e.getMessage());
            });
        });

        if (dialog != null) {
            android.widget.ImageView icon = dialog.findViewById(R.id.dialog_icon);
            if (icon != null) icon.setImageResource(R.drawable.ic_account_info);
        }
    }
}
