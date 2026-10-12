// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins;

/** SDK 1, overlay. Where a native overlay sits and how big it is. Sizes and insets are in dp. */
public final class OverlaySpec {
    /** Size the overlay to its view's measured size. */
    public static final int WRAP = -1;
    /** Fill the kiosk area, less the inset. */
    public static final int FILL = -2;

    public static final String TOP_LEFT = "top-left";
    public static final String TOP = "top";
    public static final String TOP_RIGHT = "top-right";
    public static final String LEFT = "left";
    public static final String CENTER = "center";
    public static final String RIGHT = "right";
    public static final String BOTTOM_LEFT = "bottom-left";
    public static final String BOTTOM = "bottom";
    public static final String BOTTOM_RIGHT = "bottom-right";

    private String anchor = CENTER;
    private int width = WRAP;
    private int height = WRAP;
    private int inset;
    private boolean closeOnBack = true;
    private boolean onTop;
    private boolean touchable = true;

    /** A wrapped overlay at one of the anchor constants. */
    public static OverlaySpec at(String anchor) { return new OverlaySpec().anchor(anchor); }
    /** An overlay that covers the whole kiosk area. */
    public static OverlaySpec fullScreen() { return new OverlaySpec().size(FILL, FILL); }

    public OverlaySpec anchor(String anchor) { this.anchor = anchor; return this; }
    /** WRAP, FILL or 1 to 4096 dp for each side. */
    public OverlaySpec size(int width, int height) { this.width = width; this.height = height; return this; }
    /** Distance from the kiosk edges, 0 to 200 dp. */
    public OverlaySpec inset(int inset) { this.inset = inset; return this; }
    /** Back closes the overlay and sends overlay.closed. On by default. */
    public OverlaySpec closeOnBack(boolean closeOnBack) { this.closeOnBack = closeOnBack; return this; }
    /** Draw where the KS voice overlay does: over the screensaver, the menu and camera views. Off by default. */
    public OverlaySpec onTop(boolean onTop) { this.onTop = onTop; return this; }
    /** Off makes the overlay visual only: every touch passes through to whatever is under it. On by default. */
    public OverlaySpec touchable(boolean touchable) { this.touchable = touchable; return this; }

    public String anchor() { return anchor; }
    public int width() { return width; }
    public int height() { return height; }
    public int inset() { return inset; }
    public boolean closeOnBack() { return closeOnBack; }
    public boolean onTop() { return onTop; }
    public boolean touchable() { return touchable; }
}
