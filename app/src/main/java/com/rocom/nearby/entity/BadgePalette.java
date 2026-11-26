package com.rocom.nearby.entity;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.appcompat.content.res.AppCompatResources;

import com.rocom.nearby.R;

final class BadgePalette {

    private static final int[] BLOODLINE_COLORS = {
            R.color.badge_common,
            R.color.badge_flora,
            R.color.badge_fire,
            R.color.badge_water,
            R.color.badge_light,
            R.color.badge_earth,
            R.color.badge_ice,
            R.color.badge_dragon,
            R.color.badge_electro,
            R.color.badge_poison,
            R.color.badge_bug,
            R.color.badge_fighting,
            R.color.badge_wing,
            R.color.badge_charm,
            R.color.badge_shadow,
            R.color.badge_havoc,
            R.color.badge_mech,
            R.color.badge_stellar,
            R.color.badge_alpha
    };

    static Drawable badgeTint(Context context, Badge badge) {
        switch (badge.kind) {
            case COLORFUL:
                return SpecialBadgeDrawable.colorful(context);
            case SHINY:
                return SpecialBadgeDrawable.shiny(context);
            case STRANGE:
                return SpecialBadgeDrawable.strange(context);
            case POLLUTION:
                return badgeTinted(context, R.color.badge_pollution);
            case BLOODLINE: {
                int index = badge.value - Badge.BLOODLINE_MIN;
                if (index < 0 || index >= BLOODLINE_COLORS.length)
                    return badgeTinted(context, R.color.badge);
                return badgeTinted(context, BLOODLINE_COLORS[index]);
            }
            default:
                return badgeTinted(context, R.color.badge);
        }
    }

    private static Drawable badgeTinted(Context context, int colorRes) {
        Drawable background = AppCompatResources.getDrawable(context, R.drawable.badge);
        if (background == null)
            return null;
        background = background.mutate();
        background.setTint(context.getColor(colorRes));
        return background;
    }
}
