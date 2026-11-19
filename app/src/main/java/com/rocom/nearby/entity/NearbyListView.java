package com.rocom.nearby.entity;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.rocom.nearby.R;
import com.rocom.nearby.capture.NearbyAlertNotification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class NearbyListView extends LinearLayout {

    private static final int PADDING_DP = 12;
    private static final int CHIP_TEXT_SP = 10;
    private static final int CHIP_GAP_DP = 4;
    private static final String NATURE_PREFIX = "nature:";
    private static final String BADGE_POLLUTION_KEY = "pollution";
    private final ArrayList<Row> rows = new ArrayList<>();
    private final String[] bearings;
    private TextView countView;
    private LinearLayout rowContainer;
    @Nullable
    private OnEntityClickListener entityListener;
    private boolean publishesAlerts = true;
    private boolean headFlush;

    public NearbyListView(Context context) {
        this(context, null);
    }

    public NearbyListView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NearbyListView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_content);
        applyContentPadding();
        bearings = getResources().getStringArray(R.array.direction);
        setVisibility(GONE);
    }

    public interface OnEntityClickListener {
        void onEntityClick(NearbyEntity entity);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        resolveChildren();
    }

    private void resolveChildren() {
        if (countView == null) {
            countView = findViewById(R.id.nearby_count);
        }
        if (rowContainer == null) {
            rowContainer = findViewById(R.id.nearby_rows);
        }
    }

    public void setOnEntityClickListener(@Nullable OnEntityClickListener listener) {
        if (entityListener == listener) {
            return;
        }
        entityListener = listener;
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).applyClick();
        }
    }

    public void setAlertPublishing(boolean enabled) {
        publishesAlerts = enabled;
    }

    public void setHeadFlush(boolean flush) {
        if (headFlush == flush) {
            return;
        }
        headFlush = flush;
        applyContentPadding();
        for (int i = 0; i < rows.size(); i++) {
            applyRowMargin(i);
        }
    }

    private void applyContentPadding() {
        int side = dp(PADDING_DP);
        setPadding(side, headFlush ? 0 : side, side, headFlush ? 0 : side);
    }

    public void bind(@Nullable List<NearbyEntity> entities, double selfX, double selfY) {
        resolveChildren();
        if (rowContainer == null) {
            setVisibility(GONE);
            if (publishesAlerts) {
                NearbyAlertNotification.releaseAll(getContext());
            }
            return;
        }
        int count = entities == null ? 0 : entities.size();
        boolean hasSelf = Double.isFinite(selfX) && Double.isFinite(selfY);
        int shown = 0;
        int counted = 0;
        ArrayList<String> alerts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            NearbyEntity entity = entities.get(i);
            if (entity == null || (!hasSelf && !entity.fighting)) {
                continue;
            }
            rowAt(shown).bind(entity, selfX, selfY, publishesAlerts ? alerts : null);
            counted++;
            shown++;
        }
        hideRowsFrom(shown);
        if (shown == 0) {
            setVisibility(GONE);
            if (publishesAlerts) {
                NearbyAlertNotification.releaseAll(getContext());
            }
            return;
        }
        if (publishesAlerts) {
            NearbyAlertNotification.retain(getContext(), alerts);
        }
        if (countView != null) {
            countView.setText(getContext().getString(R.string.nearby_count, counted));
        }
        setVisibility(VISIBLE);
    }

    private Row rowAt(int index) {
        while (rows.size() <= index) {
            View view = LayoutInflater.from(getContext())
                    .inflate(R.layout.entity, rowContainer, false);
            rowContainer.addView(view);
            rows.add(new Row(view));
            applyRowMargin(rows.size() - 1);
        }
        return rows.get(index);
    }

    private void applyRowMargin(int index) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        View root = rows.get(index).root;
        ViewGroup.LayoutParams params = root.getLayoutParams();
        if (!(params instanceof LinearLayout.LayoutParams)) {
            return;
        }
        LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) params;
        int target = headFlush && index == 0 ? 0 : rows.get(index).topMarginPx;
        if (layoutParams.topMargin != target) {
            layoutParams.topMargin = target;
            root.setLayoutParams(layoutParams);
        }
    }

    private static int topMarginOf(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params instanceof LinearLayout.LayoutParams
                ? ((LinearLayout.LayoutParams) params).topMargin
                : 0;
    }

    private void hideRowsFrom(int firstHidden) {
        for (int i = firstHidden; i < rows.size(); i++) {
            rows.get(i).root.setVisibility(GONE);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private String bearingText(int sector) {
        if (sector < 0 || sector >= bearings.length) {
            return "";
        }
        return bearings[sector];
    }

    private final class Row {

        private final View root;
        private final int topMarginPx;
        private final TextView name;
        private final TextView level;
        private final LinearLayout badgeGroup;
        private final ArrayList<TextView> badgeChips = new ArrayList<>();
        private final TextView bearing;
        private final TextView distance;
        private final TextView fight;
        private final TextView heightLabel;
        private final TextView height;
        private final TextView weightLabel;
        private final TextView weight;
        private final TextView voiceLabel;
        private final TextView voice;
        @Nullable
        private NearbyEntity boundEntity;
        private boolean natureExpanded;
        private String natureIdentity = "";

        Row(View view) {
            root = view;
            topMarginPx = topMarginOf(view);
            name = view.findViewById(R.id.entity_name);
            level = view.findViewById(R.id.entity_level);
            badgeGroup = view.findViewById(R.id.entity_badge);
            bearing = view.findViewById(R.id.entity_bearing);
            distance = view.findViewById(R.id.entity_distance);
            fight = view.findViewById(R.id.entity_fight);
            heightLabel = view.findViewById(R.id.entity_height_label);
            height = view.findViewById(R.id.entity_height);
            weightLabel = view.findViewById(R.id.entity_weight_label);
            weight = view.findViewById(R.id.entity_weight);
            voiceLabel = view.findViewById(R.id.entity_voice_label);
            voice = view.findViewById(R.id.entity_voice);
            heightLabel.setText(R.string.nearby_height_label);
            weightLabel.setText(R.string.nearby_weight_label);
            voiceLabel.setText(R.string.nearby_voice_label);
        }

        void bind(NearbyEntity entity, double selfX, double selfY,
                  @Nullable Collection<String> alerts) {
            root.setVisibility(VISIBLE);
            boundEntity = entity;
            String identity = entity.identityKey();
            if (!identity.equals(natureIdentity)) {
                natureIdentity = identity;
                natureExpanded = false;
            }
            name.setText(nameText(entity));
            if (entity.level > 0) {
                level.setText(getContext().getString(R.string.nearby_level, entity.level));
                level.setVisibility(VISIBLE);
            } else {
                level.setVisibility(GONE);
            }
            bindBadges(entity, alerts);
            if (entity.fighting) {
                bearing.setVisibility(GONE);
                distance.setVisibility(GONE);
                fight.setText(R.string.nearby_fighting);
                fight.setVisibility(VISIBLE);
            } else {
                fight.setVisibility(GONE);
                bearing.setVisibility(VISIBLE);
                distance.setVisibility(VISIBLE);
                double dx = entity.worldX - selfX;
                double dy = entity.worldY - selfY;
                bearing.setText(bearingText(NearbyEntityFormat.bearingIndex(dx, dy)));
                distance.setText(NearbyEntityFormat.distance(
                        NearbyEntityFormat.distanceMeters(dx, dy)));
            }
            height.setText(NearbyEntityFormat.height(entity.heightMeters,
                    entity.heightPercentile));
            weight.setText(NearbyEntityFormat.weight(entity.weightKg,
                    entity.weightPercentile));
            voice.setText(NearbyEntityFormat.voice(entity.voice));
            weight.setTextColor(hotColor(entity.weightPercentile
                    >= NearbyEntity.WEIGHT_PERCENTILE_BIG
                    || entity.weightPercentile <= NearbyEntity.WEIGHT_PERCENTILE_SMALL));
            voice.setTextColor(hotColor(entity.voice >= NearbyEntity.VOICE_HIGH
                    || entity.voice <= NearbyEntity.VOICE_LOW));
            if (entity.fighting) {
                root.setBackgroundResource(R.drawable.bg_entity_fight);
            } else if (entity.badges.contains(BADGE_POLLUTION_KEY)) {
                root.setBackgroundResource(R.drawable.bg_entity_pollution);
            } else if (!entity.badges.isEmpty()) {
                root.setBackgroundResource(R.drawable.bg_entity_special);
            } else {
                root.setBackgroundResource(R.drawable.bg_entity_normal);
            }
            applyClick();
        }

        private void bindBadges(NearbyEntity entity, @Nullable Collection<String> alerts) {
            List<String> badges = entity.badges;
            int shown = 0;
            if (badges != null) {
                for (int i = 0; i < badges.size(); i++) {
                    CharSequence text = badgeLabel(badges.get(i));
                    if (text.length() == 0) {
                        continue;
                    }
                    TextView chip = chipAt(shown);
                    chip.setText(text);
                    chip.setBackgroundResource(isPollutionBadge(badges.get(i))
                            ? R.drawable.badge_pollution : R.drawable.badge);
                    chip.setVisibility(VISIBLE);
                    if (alerts != null) {
                        String alert = NearbyAlertNotification.announceBadge(
                                getContext(), entity, badges.get(i));
                        if (alert != null) {
                            alerts.add(alert);
                        }
                    }
                    shown++;
                }
            }
            for (int i = shown; i < badgeChips.size(); i++) {
                badgeChips.get(i).setVisibility(GONE);
            }
            applyChipGaps(shown);
            badgeGroup.setVisibility(shown == 0 ? GONE : VISIBLE);
        }

        private void applyChipGaps(int shown) {
            int gap = dp(CHIP_GAP_DP);
            for (int i = 0; i < badgeChips.size(); i++) {
                TextView chip = badgeChips.get(i);
                ViewGroup.LayoutParams params = chip.getLayoutParams();
                if (!(params instanceof LinearLayout.LayoutParams)) {
                    continue;
                }
                LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) params;
                int target = i < shown - 1 ? gap : 0;
                if (layoutParams.rightMargin != target) {
                    layoutParams.rightMargin = target;
                    chip.setLayoutParams(layoutParams);
                }
            }
        }

        private boolean isPollutionBadge(@Nullable String key) {
            return key != null && BADGE_POLLUTION_KEY.equals(key.trim());
        }

        private CharSequence badgeLabel(@Nullable String key) {
            CharSequence trimmedKey = trimmed(key);
            if (trimmedKey.length() == 0) {
                return "";
            }
            String keyText = trimmedKey.toString();
            if (keyText.startsWith(NATURE_PREFIX)) {
                return natureLabel(keyText.substring(NATURE_PREFIX.length()),
                        natureExpanded);
            }
            int label;
            switch (keyText) {
                case "colorful":
                    label = R.string.badge_colorful;
                    break;
                case "shiny":
                    label = R.string.badge_shiny;
                    break;
                case "pollution":
                    label = R.string.badge_pollution;
                    break;
                case "big":
                    label = R.string.badge_big;
                    break;
                case "small":
                    label = R.string.badge_small;
                    break;
                case "high":
                    label = R.string.badge_high;
                    break;
                case "low":
                    label = R.string.badge_low;
                    break;
                default:
                    return trimmedKey;
            }
            return getContext().getString(label);
        }

        private CharSequence natureLabel(String idText, boolean withEffect) {
            int id;
            try {
                id = Integer.parseInt(idText);
            } catch (NumberFormatException e) {
                return "";
            }
            String[] names = getResources().getStringArray(R.array.nature);
            String[] effects = getResources().getStringArray(R.array.nature_effect);
            int index = id - 1;
            if (index < 0 || index >= names.length || index >= effects.length) {
                return "";
            }
            String natureName = names[index].trim();
            String effect = effects[index].trim();
            if (!withEffect) {
                return natureName;
            }
            if (natureName.length() == 0) {
                return effect;
            }
            if (effect.length() == 0) {
                return natureName;
            }
            return getContext().getString(R.string.badge_nature_format, natureName, effect);
        }

        private void applyClick() {
            NearbyEntity entity = boundEntity;
            if (entity == null) {
                root.setOnClickListener(null);
                root.setClickable(false);
                return;
            }
            if (entity.fighting) {
                root.setOnClickListener(view -> toggleNature());
                root.setClickable(true);
                return;
            }
            if (entityListener == null) {
                root.setOnClickListener(null);
                root.setClickable(false);
                return;
            }
            root.setOnClickListener(view -> {
                NearbyEntity current = boundEntity;
                OnEntityClickListener target = entityListener;
                if (current != null && target != null) {
                    target.onEntityClick(current);
                }
            });
            root.setClickable(true);
        }

        private void toggleNature() {
            NearbyEntity entity = boundEntity;
            if (entity == null) {
                return;
            }
            natureExpanded = !natureExpanded;
            bindBadges(entity, null);
        }

        private TextView chipAt(int index) {
            while (badgeChips.size() <= index) {
                TextView chip = new TextView(getContext());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                chip.setLayoutParams(params);
                chip.setBackgroundResource(R.drawable.badge);
                chip.setGravity(Gravity.CENTER);
                chip.setIncludeFontPadding(false);
                chip.setSingleLine(true);
                chip.setTextColor(getContext().getColor(R.color.badge_text));
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_TEXT_SP);
                chip.setTypeface(Typeface.DEFAULT_BOLD);
                badgeGroup.addView(chip);
                badgeChips.add(chip);
            }
            return badgeChips.get(index);
        }

        private int hotColor(boolean hot) {
            return getContext().getColor(hot ? R.color.badge : R.color.text);
        }

        private CharSequence nameText(NearbyEntity entity) {
            return trimmed(entity.name);
        }

        private CharSequence trimmed(@Nullable String value) {
            return value == null ? "" : value.trim();
        }
    }
}
