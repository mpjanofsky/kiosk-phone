// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** SDK 1, overlay. The KS colors, shapes and typeface for native overlays. KS builds it and passes a new one when its theme changes. */
public final class KsTheme {
    /** Supplies the KS typeface at a weight from 100 to 900, or null for the platform default. */
    public interface Fonts {
        Typeface typeface(int weight);
    }

    private final boolean dark;
    private final float density;
    private final Map<String, Integer> colors;
    private final Fonts fonts;

    /** Built by KS. Tests may build one with their own colors. */
    public KsTheme(boolean dark, float density, Map<String, Integer> colors, Fonts fonts) {
        this.dark = dark;
        this.density = density;
        this.colors = Collections.unmodifiableMap(new HashMap<>(colors));
        this.fonts = fonts;
    }

    public boolean isDark() { return dark; }

    /** An ARGB color by Material 3 role name, such as surface, onSurface, primary or outlineVariant, plus success. */
    public int color(String role) {
        Integer value = colors.get(role);
        if (value == null) throw new IllegalArgumentException("Unknown color role: " + role);
        return value;
    }

    /** Every color role this KS version provides. */
    public Map<String, Integer> colors() { return colors; }

    /** Pixels for a length in dp. */
    public int px(float dp) { return Math.round(dp * density); }

    /** Corner radius of cards, dialogs and other primary surfaces, in dp. */
    public int radiusCard() { return 24; }
    /** Corner radius of rows and small tappable surfaces inside a card, in dp. */
    public int radiusRow() { return 14; }
    /** Corner radius of text fields and other input controls, in dp. */
    public int radiusControl() { return 12; }
    /** Inner padding of a card, in dp. */
    public int inset() { return 20; }
    /** Gap between cards, in dp. */
    public int cardGap() { return 16; }

    /** The KS typeface at a weight from 100 to 900. */
    public Typeface typeface(int weight) {
        Typeface typeface = fonts == null ? null : fonts.typeface(Math.max(100, Math.min(900, weight)));
        return typeface != null ? typeface : Typeface.DEFAULT;
    }

    /** A card background like the one KS toasts float over the dashboard: surfaceContainerHigh, the card radius and a hairline outline. */
    public GradientDrawable card() {
        GradientDrawable card = new GradientDrawable();
        card.setColor(color("surfaceContainerHigh"));
        card.setCornerRadius(px(radiusCard()));
        card.setStroke(Math.max(1, px(1)), color("outlineVariant"));
        return card;
    }

    /** Style text with the KS typeface at a size in sp, a weight and a color role. */
    public void styleText(TextView view, float sp, int weight, String role) {
        view.setTypeface(typeface(weight));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(role));
    }

    /** Style a TextView or Button as a KS pill button. Filled uses primary, otherwise it is a quiet text button. */
    public void stylePill(TextView view, boolean filled) {
        int text = color(filled ? "onPrimary" : "primary");
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(px(100));
        shape.setColor(color("primary"));
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(px(100));
        mask.setColor(Color.WHITE);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(31, Color.red(text), Color.green(text), Color.blue(text))), filled ? shape : null, mask));
        styleText(view, 14.5f, 600, filled ? "onPrimary" : "primary");
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(px(20), px(12), px(20), px(12));
        view.setMinWidth(px(96));
        view.setMinimumWidth(px(96));
        view.setMinHeight(px(44));
        view.setMinimumHeight(px(44));
        view.setStateListAnimator(null);
    }

    /** Style a SeekBar like the KS sliders: a 4 dp track filled in primary over surfaceContainerHighest and a 20 dp primary thumb. */
    public void styleSlider(SeekBar view) {
        int track = px(4);
        GradientDrawable empty = new GradientDrawable();
        empty.setCornerRadius(track / 2f);
        empty.setColor(color("surfaceContainerHighest"));
        GradientDrawable filled = new GradientDrawable();
        filled.setCornerRadius(track / 2f);
        filled.setColor(color("primary"));
        LayerDrawable progress = new LayerDrawable(new Drawable[] {empty, new ClipDrawable(filled, Gravity.START, ClipDrawable.HORIZONTAL)});
        progress.setId(0, android.R.id.background);
        progress.setId(1, android.R.id.progress);
        for (int layer = 0; layer < 2; layer++) {
            progress.setLayerHeight(layer, track);
            progress.setLayerGravity(layer, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        view.setProgressDrawable(progress);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(color("primary"));
        thumb.setSize(px(20), px(20));
        view.setThumb(thumb);
        view.setSplitTrack(false);
        view.setBackground(null);
        // The thumb's half width on each side, and a 44 dp touch target.
        view.setPadding(px(10), 0, px(10), 0);
        view.setMinimumHeight(px(44));
    }
}
