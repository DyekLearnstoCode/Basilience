package com.example.basilience;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.basilience.models.GuideSection;

import java.util.List;

/**
 * Renders a list of {@link GuideSection}s as repeated instructional
 * sections (title, description, image/placeholder, numbered steps, optional
 * tip/warning) using one shared item layout, instead of duplicating the same
 * XML block once per section. See item_guide_section.xml.
 */
public class GuideSectionAdapter extends RecyclerView.Adapter<GuideSectionAdapter.ViewHolder> {

    private final List<GuideSection> sections;

    public GuideSectionAdapter(List<GuideSection> sections) {
        this.sections = sections;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_guide_section, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GuideSection section = sections.get(position);
        Context context = holder.itemView.getContext();

        holder.tvSectionTitle.setText(section.getTitle());

        if (section.getRoleLabel() != null) {
            holder.tvRoleLabel.setText(section.getRoleLabel());
            holder.tvRoleLabel.setVisibility(View.VISIBLE);
        } else {
            holder.tvRoleLabel.setVisibility(View.GONE);
        }

        if (section.getDescription() != null) {
            holder.tvSectionDescription.setText(section.getDescription());
            holder.tvSectionDescription.setVisibility(View.VISIBLE);
        } else {
            holder.tvSectionDescription.setVisibility(View.GONE);
        }

        boolean hasImage = section.getImageResId() != 0;
        boolean hasPlaceholder = section.getImagePlaceholderCaption() != null;
        if (hasImage) {
            holder.ivSectionImage.setImageResource(section.getImageResId());
            holder.ivSectionImage.setVisibility(View.VISIBLE);
            holder.layoutImagePlaceholder.setVisibility(View.GONE);
            holder.imageArea.setVisibility(View.VISIBLE);
        } else if (hasPlaceholder) {
            holder.ivSectionImage.setVisibility(View.GONE);
            holder.layoutImagePlaceholder.setVisibility(View.VISIBLE);
            holder.tvImagePlaceholderCaption.setText(section.getImagePlaceholderCaption());
            holder.imageArea.setVisibility(View.VISIBLE);
        } else {
            // Reference-style section (e.g. "Common Messages") with no associated image.
            holder.imageArea.setVisibility(View.GONE);
        }

        holder.stepsContainer.removeAllViews();
        List<String> steps = section.getSteps();
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < steps.size(); i++) {
            View stepView = inflater.inflate(R.layout.item_guide_step, holder.stepsContainer, false);
            TextView tvNumber = stepView.findViewById(R.id.tvStepNumber);
            TextView tvText = stepView.findViewById(R.id.tvStepText);
            tvNumber.setText(String.valueOf(i + 1));
            tvText.setText(steps.get(i));
            holder.stepsContainer.addView(stepView);
        }
        holder.stepsContainer.setVisibility(steps.isEmpty() ? View.GONE : View.VISIBLE);

        if (section.getTip() != null) {
            holder.tvTip.setText(section.getTip());
            holder.layoutTip.setVisibility(View.VISIBLE);
        } else {
            holder.layoutTip.setVisibility(View.GONE);
        }

        if (section.getWarning() != null) {
            holder.tvWarning.setText(section.getWarning());
            holder.layoutWarning.setVisibility(View.VISIBLE);
        } else {
            holder.layoutWarning.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return sections.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvSectionTitle, tvRoleLabel, tvSectionDescription, tvImagePlaceholderCaption, tvTip, tvWarning;
        ImageView ivSectionImage;
        View imageArea;
        LinearLayout layoutImagePlaceholder, stepsContainer, layoutTip, layoutWarning;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSectionTitle = itemView.findViewById(R.id.tvSectionTitle);
            tvRoleLabel = itemView.findViewById(R.id.tvRoleLabel);
            tvSectionDescription = itemView.findViewById(R.id.tvSectionDescription);
            imageArea = itemView.findViewById(R.id.imageArea);
            ivSectionImage = itemView.findViewById(R.id.ivSectionImage);
            layoutImagePlaceholder = itemView.findViewById(R.id.layoutImagePlaceholder);
            tvImagePlaceholderCaption = itemView.findViewById(R.id.tvImagePlaceholderCaption);
            stepsContainer = itemView.findViewById(R.id.stepsContainer);
            layoutTip = itemView.findViewById(R.id.layoutTip);
            tvTip = itemView.findViewById(R.id.tvTip);
            layoutWarning = itemView.findViewById(R.id.layoutWarning);
            tvWarning = itemView.findViewById(R.id.tvWarning);
        }
    }
}
