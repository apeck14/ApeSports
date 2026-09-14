package tv.gridiron.app;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

/** Four permanently attached slots. Layout changes never reparent a video surface. */
final class PlaybackWall extends ViewGroup {
    private int count = 4;
    private int expanded = -1;

    PlaybackWall(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setClipChildren(true);
        setClipToPadding(true);
    }

    void showLayout(int newCount, int newExpanded) {
        if (newCount != 1 && newCount != 2 && newCount != 4) {
            throw new IllegalArgumentException("Layout must contain 1, 2, or 4 games");
        }
        if (newExpanded < -1 || newExpanded >= newCount) {
            throw new IllegalArgumentException("Expanded slot must belong to the current layout");
        }
        if (count == newCount && expanded == newExpanded) return;
        count = newCount;
        expanded = newExpanded;
        requestLayout();
    }

    private boolean isShownSlot(int slot) {
        return expanded >= 0 ? slot == expanded : slot < count;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int columns = expanded >= 0 || count != 4 ? 1 : 2;
        int rows = expanded >= 0 || count == 1 ? 1 : 2;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            boolean shown = isShownSlot(i);
            int column = expanded >= 0 ? 0 : i % columns;
            int row = expanded >= 0 ? 0 : i / columns;
            // Integer boundaries account for every pixel, including odd-sized displays.
            int childWidth = shown ? (column + 1) * width / columns - column * width / columns
                    : Math.max(1, child.getMeasuredWidth());
            int childHeight = shown ? (row + 1) * height / rows - row * height / rows
                    : Math.max(1, child.getMeasuredHeight());
            child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int columns = expanded >= 0 || count != 4 ? 1 : 2;
        int rows = expanded >= 0 || count == 1 ? 1 : 2;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            boolean shown = isShownSlot(i);
            // Keep hidden surfaces VISIBLE and attached. GONE/INVISIBLE can destroy a
            // SurfaceView's Surface on older TVs. Translate outside the clipped wall instead.
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
            child.setTranslationX(shown ? (expanded >= 0 ? 0 : i % columns * width / columns) : width + 1);
            child.setTranslationY(shown ? (expanded >= 0 ? 0 : i / columns * height / rows) : 0);
            child.setFocusable(shown);
            child.setImportantForAccessibility(shown ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }
}
