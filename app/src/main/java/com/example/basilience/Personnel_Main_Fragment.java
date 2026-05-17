package com.example.basilience;

import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;

public class Personnel_Main_Fragment extends Fragment {

    private RecyclerView recyclerView;
    private final List<Personnel> list = new ArrayList<>();
    private Personnel_Adapter adapter;

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

        Button btnAdd = view.findViewById(R.id.btnAddPersonnel);

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
    }

    private void loadFarmersFromFirestore() {
        helper.getMyPersonnelByRole("farmer")
                .addOnSuccessListener(qs -> {
                    list.clear();

                    for (DocumentSnapshot doc : qs.getDocuments()) {
                        String id = doc.getId();
                        String name = doc.getString("name");
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
                })
                .addOnFailureListener(e ->
                        NotificationHelper.showError(requireContext(),
                                "Failed to load farmers: " + e.getMessage())
                );
    }
}