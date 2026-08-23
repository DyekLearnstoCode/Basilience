package com.example.basilience;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Read-only Privacy Policy screen, reached from Settings.
 *
 * Registration can already show this document before an account exists; this
 * gives a signed-in user somewhere to re-read it. Both surfaces render
 * {@link LegalContent#PRIVACY_BODY}, which stays the single source of the
 * wording - nothing is duplicated here.
 *
 * Shares the Terms screen's layout: the same chrome, with the title and body
 * supplied at runtime.
 */
public class PrivacyPolicyFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_terms, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView tvTitle = view.findViewById(R.id.tvLegalTitle);
        if (tvTitle != null) tvTitle.setText(LegalContent.PRIVACY_TITLE);

        TextView tvBody = view.findViewById(R.id.tvTermsBody);
        if (tvBody != null) tvBody.setText(LegalContent.PRIVACY_BODY);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v ->
                    androidx.navigation.Navigation.findNavController(view).popBackStack());
        }
    }
}
