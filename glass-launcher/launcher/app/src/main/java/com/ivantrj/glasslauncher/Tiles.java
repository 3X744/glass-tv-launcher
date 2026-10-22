package com.ivantrj.glasslauncher;

import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/** Shared binding and focus visuals for dock and grid tiles (both use item_tile.xml). */
final class Tiles {
    static final float FOCUS_SCALE = 1.08f;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    static void bind(View cell, AppInfo app) {
        View tile = cell.findViewById(R.id.tile);
        ImageView banner = cell.findViewById(R.id.banner);
        ImageView icon = cell.findViewById(R.id.icon);
        TextView label = cell.findViewById(R.id.label);
        tile.setClipToOutline(true);
        label.setText(app.label);
        label.setTypeface(Fonts.outfit(cell.getContext(), 400));
        if (app.banner != null) {
            banner.setImageDrawable(app.banner);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(app.icon);
            icon.setVisibility(View.VISIBLE);
        }
    }

    /** Dock tiles: the name shows only on focus (the big title above the dock names it too). */
    static void setFocused(View cell, boolean focused, boolean animate) {
        setFocused(cell, focused, animate, 0f);
    }

    /** idleLabelAlpha: how visible the name is when not focused (grid tiles keep a dim name). */
    static void setFocused(View cell, boolean focused, boolean animate, float idleLabelAlpha) {
        View tile = cell.findViewById(R.id.tile);
        View label = cell.findViewById(R.id.label);
        float scale = focused ? FOCUS_SCALE : 1f;
        float alpha = focused ? 1f : idleLabelAlpha;
        tile.animate().cancel();
        label.animate().cancel();
        if (animate) {
            tile.animate().scaleX(scale).scaleY(scale).setDuration(200).setInterpolator(EASE).start();
            label.animate().alpha(alpha).setDuration(focused ? 220 : 120).start();
        } else {
            tile.setScaleX(scale);
            tile.setScaleY(scale);
            label.setAlpha(alpha);
        }
    }

    private Tiles() {}
}
