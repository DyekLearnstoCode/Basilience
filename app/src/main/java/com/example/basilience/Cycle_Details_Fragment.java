package com.example.basilience;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;

public class Cycle_Details_Fragment extends Fragment {

    private final List<Cycle> cycles = new ArrayList<>();
    private CycleAdapter adapter;
    private Database_Helper dbHelper;
    private ListenerRegistration cycleListener;

    public Cycle_Details_Fragment() {
        super(R.layout.cycle_main);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();
        NavController navController = Navigation.findNavController(view);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        RecyclerView rv = view.findViewById(R.id.recyclerCycles);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new CycleAdapter(cycles, (cycle, pos) -> {
            Bundle args = new Bundle();
            args.putInt("cycleNo", cycle.getCycleNo());
            navController.navigate(R.id.action_cycleDetailsFragment_to_harvestLogFragment, args);
        });

        rv.setAdapter(adapter);

        // Fetch Cycles in Real-time
        startListeningToCycles();

        View btnAddCycle = view.findViewById(R.id.btnAddCycle);

        // Add Cycle -> go to CycleAddFragment
        btnAddCycle.setOnClickListener(v -> {
            Bundle args = new Bundle();
            // Pass the next cycle number based on current list size
            args.putInt("cycleNo", cycles.size() + 1);
            navController.navigate(R.id.action_cycleDetailsFragment_to_cycleaddFragment, args);
        });
    }

    private void startListeningToCycles() {
        dbHelper.resolveDataUid().addOnSuccessListener(uid -> {
            if (uid != null) {
                dbHelper.setTargetUid(uid);
                cycleListener = dbHelper.listenToCycles((snapshot, e) -> {
                    if (e != null) {
                        Toast.makeText(getContext(), "Error loading cycles", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (snapshot != null) {
                        cycles.clear();
                        for (DocumentSnapshot doc : snapshot.getDocuments()) {
                            Cycle cycle = doc.toObject(Cycle.class);
                            if (cycle != null) {
                                cycles.add(cycle);
                            }
                        }
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (cycleListener != null) {
            cycleListener.remove();
        }
    }
}
