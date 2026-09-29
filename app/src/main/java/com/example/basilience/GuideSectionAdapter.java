package com.example.basilience;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.basilience.models.GuideSection;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a list of {@link GuideSection}s as repeated instructional
 * sections (title, description, image/placeholder, numbered steps, optional
 * tip/warning) using one shared item layout, instead of duplicating the same
 * XML block once per section. See item_guide_section.xml.
 *
 * <p>A restructured hardware-component section (purpose/indicators/common
 * problems/troubleshooting all set) renders those in place of a plain
 * description; a section that doesn't set them renders exactly as before -
 * this is what lets the Mobile Guide and the not-yet-restructured Hardware
 * Guide sections keep their original look with zero changes.
 */
public class GuideSectionAdapter extends RecyclerView.Adapter<GuideSectionAdapter.ViewHolder> {

    /** Notified when the Admin taps "Edit Guide" on a component's section. */
    public interface OnEditClickListener {
        void onEditClick(HardwareComponentKey component);
    }

    private List<GuideSection> sections;
    private final boolean editable;
    @Nullable
    private final OnEditClickListener editClickListener;
    /** Position to briefly highlight after a notification deep-links here, or -1 for none. */
    private int highlightedPosition = -1;

    public GuideSectionAdapter(List<GuideSection> sections) {
        this(sections, false, null);
    }

    /** @param editable Whether an "Edit Guide" button should be offered on each hardware-component section (Admin viewing a known device's Hardware Guide only). */
    public GuideSectionAdapter(List<GuideSection> sections, boolean editable, @Nullable OnEditClickListener editClickListener) {
        this.sections = new ArrayList<>(sections);
        this.editable = editable;
        this.editClickListener = editClickListener;
    }

    /** Swaps in a new list (e.g. once this device's Firestore overrides merge in) and refreshes the whole list. */
    public void setSections(List<GuideSection> sections) {
        this.sections = new ArrayList<>(sections);
        notifyDataSetChanged();
    }

    /** Index of the first section tagged with this component key, or -1 if none. */
    public int indexOf(HardwareComponentKey key) {
        if (key == null) return -1;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).getHardwareKey() == key) return i;
        }
        return -1;
    }

    /** Briefly tints one row so a deep-linked-to section is visually easy to spot after the scroll. Pass -1 to clear. */
    public void setHighlightedPosition(int position) {
        int previous = highlightedPosition;
        highlightedPosition = position;
        if (previous >= 0) notifyItemChanged(previous);
        if (position >= 0) notifyItemChanged(position);
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

        // A video-card section with no hardwareKey (e.g. the Mobile Guide's
        // globally-configured video) still gets an Edit button when this
        // adapter instance was constructed as editable - the null component
        // just tells the listener "this is a video-only edit," which is the
        // only kind of section MobileGuideFragment ever makes editable.
        if (editable && (section.getHardwareKey() != null || section.hasVideoCard())) {
            HardwareComponentKey component = section.getHardwareKey();
            holder.btnEditGuide.setVisibility(View.VISIBLE);
            holder.btnEditGuide.setOnClickListener(v -> {
                if (editClickListener != null) editClickListener.onEditClick(component);
            });
        } else {
            holder.btnEditGuide.setVisibility(View.GONE);
            holder.btnEditGuide.setOnClickListener(null);
        }

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
        boolean hasRemoteImage = section.getImageUrl() != null;
        if (hasRemoteImage) {
            // Glide's own disk cache is what keeps an Admin-uploaded photo viewable offline
            // after its first successful load; the bundled resource (or a plain placeholder
            // icon if none exists) covers the brief window before that first load completes.
            Glide.with(context)
                    .load(section.getImageUrl())
                    .placeholder(hasImage ? section.getImageResId() : android.R.color.transparent)
                    .error(hasImage ? section.getImageResId() : android.R.color.transparent)
                    .into(holder.ivSectionImage);
            holder.ivSectionImage.setVisibility(View.VISIBLE);
            holder.layoutImagePlaceholder.setVisibility(View.GONE);
            holder.imageArea.setVisibility(View.VISIBLE);
        } else if (hasImage) {
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

        LayoutInflater inflater = LayoutInflater.from(context);
        boolean isStructured = section.getPurpose() != null
                || !section.getIndicators().isEmpty()
                || !section.getCommonProblems().isEmpty()
                || !section.getTroubleshooting().isEmpty();

        if (section.getPurpose() != null) {
            holder.tvPurpose.setText(section.getPurpose());
            holder.tvPurposeLabel.setVisibility(View.VISIBLE);
            holder.tvPurpose.setVisibility(View.VISIBLE);
        } else {
            holder.tvPurposeLabel.setVisibility(View.GONE);
            holder.tvPurpose.setVisibility(View.GONE);
        }

        holder.stepsContainer.removeAllViews();
        List<String> steps = section.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            View stepView = inflater.inflate(R.layout.item_guide_step, holder.stepsContainer, false);
            TextView tvNumber = stepView.findViewById(R.id.tvStepNumber);
            TextView tvText = stepView.findViewById(R.id.tvStepText);
            tvNumber.setText(String.valueOf(i + 1));
            tvText.setText(steps.get(i));
            holder.stepsContainer.addView(stepView);
        }
        holder.stepsContainer.setVisibility(steps.isEmpty() ? View.GONE : View.VISIBLE);
        holder.tvNormalOperationLabel.setVisibility(isStructured && !steps.isEmpty() ? View.VISIBLE : View.GONE);

        bindBulletList(inflater, holder.indicatorsContainer, holder.tvIndicatorsLabel, section.getIndicators(), false);
        bindBulletList(inflater, holder.commonProblemsContainer, holder.tvCommonProblemsLabel, section.getCommonProblems(), false);
        bindBulletList(inflater, holder.troubleshootingContainer, holder.tvTroubleshootingLabel, section.getTroubleshooting(), true);

        holder.itemView.setBackgroundColor(position == highlightedPosition
                ? ContextCompat.getColor(context, R.color.state_success_bg)
                : android.graphics.Color.TRANSPARENT);

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

        bindVideoCard(holder, section, context);
    }

    /**
     * Renders a section's optional video card. Two states only, never a
     * broken/blank player: no URL yet -> "Coming Soon" badge; a real URL ->
     * a "Watch Video" button that opens it via ACTION_VIEW (browser/YouTube
     * app/etc., whichever the device already resolves it to), matching how
     * the rest of the app never embeds a video player of its own.
     */
    private static void bindVideoCard(ViewHolder holder, GuideSection section, Context context) {
        if (!section.hasVideoCard()) {
            holder.layoutVideoCard.setVisibility(View.GONE);
            return;
        }
        holder.layoutVideoCard.setVisibility(View.VISIBLE);
        holder.tvVideoTitle.setText(section.getVideoTitle());
        holder.tvVideoDescription.setText(section.getVideoDescription());

        boolean hasVideo = section.hasVideo();
        holder.tvVideoComingSoonBadge.setVisibility(hasVideo ? View.GONE : View.VISIBLE);
        holder.btnWatchVideo.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        if (hasVideo) {
            String videoUrl = section.getVideoUrl();
            holder.btnWatchVideo.setOnClickListener(v -> {
                try {
                    context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)));
                } catch (ActivityNotFoundException e) {
                    NotificationHelper.showError(context, "No app found to open this video link.");
                }
            });
        } else {
            holder.btnWatchVideo.setOnClickListener(null);
        }
    }

    @Override
    public int getItemCount() {
        return sections.size();
    }

    /** Shared renderer for Indicators/Common Problems (bulleted) and Troubleshooting (numbered). */
    private static void bindBulletList(LayoutInflater inflater, LinearLayout container,
                                        TextView label, List<String> items, boolean numbered) {
        container.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            View row = inflater.inflate(R.layout.item_guide_step, container, false);
            TextView tvNumber = row.findViewById(R.id.tvStepNumber);
            TextView tvText = row.findViewById(R.id.tvStepText);
            tvNumber.setText(numbered ? String.valueOf(i + 1) : "•");
            tvText.setText(items.get(i));
            container.addView(row);
        }
        label.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        container.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvSectionTitle, tvRoleLabel, tvSectionDescription, tvImagePlaceholderCaption, tvTip, tvWarning;
        TextView tvPurposeLabel, tvPurpose, tvNormalOperationLabel;
        TextView tvIndicatorsLabel, tvCommonProblemsLabel, tvTroubleshootingLabel;
        ImageView ivSectionImage;
        View imageArea;
        LinearLayout layoutImagePlaceholder, stepsContainer, layoutTip, layoutWarning;
        LinearLayout indicatorsContainer, commonProblemsContainer, troubleshootingContainer;
        MaterialButton btnEditGuide;
        LinearLayout layoutVideoCard;
        TextView tvVideoTitle, tvVideoDescription, tvVideoComingSoonBadge;
        MaterialButton btnWatchVideo;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            btnEditGuide = itemView.findViewById(R.id.btnEditGuide);
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
            tvPurposeLabel = itemView.findViewById(R.id.tvPurposeLabel);
            tvPurpose = itemView.findViewById(R.id.tvPurpose);
            tvNormalOperationLabel = itemView.findViewById(R.id.tvNormalOperationLabel);
            tvIndicatorsLabel = itemView.findViewById(R.id.tvIndicatorsLabel);
            indicatorsContainer = itemView.findViewById(R.id.indicatorsContainer);
            tvCommonProblemsLabel = itemView.findViewById(R.id.tvCommonProblemsLabel);
            commonProblemsContainer = itemView.findViewById(R.id.commonProblemsContainer);
            tvTroubleshootingLabel = itemView.findViewById(R.id.tvTroubleshootingLabel);
            troubleshootingContainer = itemView.findViewById(R.id.troubleshootingContainer);
            layoutVideoCard = itemView.findViewById(R.id.layoutVideoCard);
            tvVideoTitle = itemView.findViewById(R.id.tvVideoTitle);
            tvVideoDescription = itemView.findViewById(R.id.tvVideoDescription);
            tvVideoComingSoonBadge = itemView.findViewById(R.id.tvVideoComingSoonBadge);
            btnWatchVideo = itemView.findViewById(R.id.btnWatchVideo);
        }
    }
}
