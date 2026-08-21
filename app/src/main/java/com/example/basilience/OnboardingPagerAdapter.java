package com.example.basilience;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.basilience.models.OnboardingPage;

import java.util.List;

/** Plain (non-Fragment) adapter for the onboarding ViewPager2 - each page is static content with no independent lifecycle needs. */
public class OnboardingPagerAdapter extends RecyclerView.Adapter<OnboardingPagerAdapter.ViewHolder> {

    private final List<OnboardingPage> pages;

    public OnboardingPagerAdapter(List<OnboardingPage> pages) {
        this.pages = pages;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_onboarding_page, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OnboardingPage page = pages.get(position);

        holder.tvPageTitle.setText(page.getTitle());
        holder.tvPageDescription.setText(page.getDescription());

        if (page.getImageResId() != 0) {
            holder.ivPageImage.setImageResource(page.getImageResId());
            holder.ivPageImage.setVisibility(View.VISIBLE);
            holder.layoutImagePlaceholder.setVisibility(View.GONE);
        } else {
            holder.ivPageImage.setVisibility(View.GONE);
            holder.layoutImagePlaceholder.setVisibility(View.VISIBLE);
            holder.tvImagePlaceholderCaption.setText(page.getImagePlaceholderCaption());
        }

        if (page.getRoleNote() != null) {
            holder.tvRoleNote.setText(page.getRoleNote());
            holder.tvRoleNote.setVisibility(View.VISIBLE);
        } else {
            holder.tvRoleNote.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvPageTitle, tvPageDescription, tvRoleNote, tvImagePlaceholderCaption;
        ImageView ivPageImage;
        LinearLayout layoutImagePlaceholder;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvPageTitle = itemView.findViewById(R.id.tvPageTitle);
            tvPageDescription = itemView.findViewById(R.id.tvPageDescription);
            tvRoleNote = itemView.findViewById(R.id.tvRoleNote);
            ivPageImage = itemView.findViewById(R.id.ivPageImage);
            layoutImagePlaceholder = itemView.findViewById(R.id.layoutImagePlaceholder);
            tvImagePlaceholderCaption = itemView.findViewById(R.id.tvImagePlaceholderCaption);
        }
    }
}
