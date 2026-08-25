package com.rockmap.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared visual language for every user-draggable RockMap floating component. */
public final class RockMapDragHandle {
    private RockMapDragHandle() {}

    /** Small labeled handle used in expanded floating panels and the guided-tour coach. */
    public static View labeled(Context context, int textColor, View.OnTouchListener dragListener,
                               String contentDescription) {
        LinearLayout handle = new LinearLayout(context);
        handle.setOrientation(LinearLayout.HORIZONTAL);
        handle.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        handle.setMinimumHeight(dp(context, 40));
        handle.setPadding(dp(context, 4), 0, dp(context, 3), 0);
        handle.setClickable(true);
        handle.setFocusable(true);
        handle.setContentDescription(contentDescription);
        handle.setOnTouchListener(dragListener);

        TextView label = new TextView(context);
        label.setText("DRAG");
        label.setTextSize(9.5f);
        label.setTextColor(textColor);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        handle.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40)));

        FrameLayout iconHolder = new FrameLayout(context);
        iconHolder.setPadding(dp(context, 3), 0, 0, 0);
        MoveIconView icon = new MoveIconView(context, textColor);
        iconHolder.addView(icon, new FrameLayout.LayoutParams(
                dp(context, 18), dp(context, 18), Gravity.CENTER));
        handle.addView(iconHolder, new LinearLayout.LayoutParams(
                dp(context, 26), dp(context, 40)));
        return handle;
    }

    /** Icon-only handle for compact collapsed controls where a DRAG word would add clutter. */
    public static View compact(Context context, int color, View.OnTouchListener dragListener,
                               String contentDescription) {
        FrameLayout handle = new FrameLayout(context);
        handle.setMinimumWidth(dp(context, 36));
        handle.setMinimumHeight(dp(context, 40));
        handle.setClickable(true);
        handle.setFocusable(true);
        handle.setContentDescription(contentDescription);
        handle.setOnTouchListener(dragListener);
        handle.addView(new MoveIconView(context, color), new FrameLayout.LayoutParams(
                dp(context, 18), dp(context, 18), Gravity.CENTER));
        return handle;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Resource-independent four-direction move icon so all draggable controls match exactly. */
    private static final class MoveIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        MoveIconView(Context context, int color) {
            super(context);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, context.getResources().getDisplayMetrics().density * 1.45f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setContentDescription(null);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float edge = Math.max(2f, Math.min(w, h) * 0.13f);
            float head = Math.max(2.5f, Math.min(w, h) * 0.20f);

            // Cross shafts.
            canvas.drawLine(edge, cy, w - edge, cy, paint);
            canvas.drawLine(cx, edge, cx, h - edge, paint);

            path.reset();
            // Left arrowhead.
            path.moveTo(edge, cy);
            path.lineTo(edge + head, cy - head);
            path.moveTo(edge, cy);
            path.lineTo(edge + head, cy + head);
            // Right arrowhead.
            path.moveTo(w - edge, cy);
            path.lineTo(w - edge - head, cy - head);
            path.moveTo(w - edge, cy);
            path.lineTo(w - edge - head, cy + head);
            // Top arrowhead.
            path.moveTo(cx, edge);
            path.lineTo(cx - head, edge + head);
            path.moveTo(cx, edge);
            path.lineTo(cx + head, edge + head);
            // Bottom arrowhead.
            path.moveTo(cx, h - edge);
            path.lineTo(cx - head, h - edge - head);
            path.moveTo(cx, h - edge);
            path.lineTo(cx + head, h - edge - head);
            canvas.drawPath(path, paint);
        }
    }
}
