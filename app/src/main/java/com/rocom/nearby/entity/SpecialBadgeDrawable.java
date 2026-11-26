package com.rocom.nearby.entity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

final class SpecialBadgeDrawable extends Drawable {

    private static final int MODE_STRANGE = 0;
    private static final int MODE_COLORFUL = 1;
    private static final int MODE_SHINY = 2;
    private static final float RADIUS_DP = 5f;
    private static final float PADDING_H_DP = 7f;
    private static final float PADDING_V_DP = 2f;
    private static final int STRANGE_ORANGE = 0xFFEE9335;
    private static final int STRANGE_YELLOW = 0xFFECC931;
    private static final int STRANGE_GREEN = 0xFF59E07A;
    private static final int STRANGE_BLUE = 0xFF52B6E7;
    private static final int STRANGE_RED = 0xFFEC4048;
    private static final int STRANGE_PINK = 0xFFEC3B82;
    private static final int SHINY_TOP_LEFT = 0xFF39A2CB;
    private static final int SHINY_BOTTOM_RIGHT = 0xFFFFC65F;
    private static final int COLORFUL_YELLOW = 0xFFE7C134;
    private static final int COLORFUL_LIME = 0xFFCACC4F;
    private static final int COLORFUL_BLUE = 0xFF3E9CD5;
    private static final int COLORFUL_INDIGO = 0xFF609CEE;
    private static final int COLORFUL_VIOLET = 0xFF8379DC;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF shaderBounds = new RectF();
    private final float radius;
    private final int paddingH;
    private final int paddingV;
    private final int mode;
    private Shader shader;

    private SpecialBadgeDrawable(Context context, int mode) {
        float density = context.getResources().getDisplayMetrics().density;
        radius = RADIUS_DP * density;
        paddingH = Math.round(PADDING_H_DP * density);
        paddingV = Math.round(PADDING_V_DP * density);
        this.mode = mode;
    }

    static SpecialBadgeDrawable strange(Context context) {
        return new SpecialBadgeDrawable(context, MODE_STRANGE);
    }

    static SpecialBadgeDrawable colorful(Context context) {

        return new SpecialBadgeDrawable(context, MODE_COLORFUL);
    }

    static SpecialBadgeDrawable shiny(Context context) {
        return new SpecialBadgeDrawable(context, MODE_SHINY);
    }

    private Shader createShader(RectF r) {
        switch (mode) {
            case MODE_STRANGE:
                return createStrangeShader(r);
            case MODE_COLORFUL:
                return createColorfulShader(r);
            default:
                return createShinyShader(r);
        }
    }

    private Shader createStrangeShader(RectF r) {
        int[] colors = { STRANGE_YELLOW, STRANGE_GREEN, STRANGE_BLUE, STRANGE_RED, STRANGE_PINK, STRANGE_ORANGE };
        double alphaDeg = Math.toDegrees(Math.atan2(r.height() / 2.0, r.width() / 2.0));
        float alpha = (float) Math.max(1.0, Math.min(89.0, alphaDeg));
        float rightBottom = alpha;
        float leftBottom = 180f - alpha;
        float leftTop = 180f + alpha;
        float rightTop = 360f - alpha;
        float bottomMiddle = 90f;
        float topMiddle = 270f;
        float[] degrees = {0f, rightBottom, rightBottom, bottomMiddle, bottomMiddle, leftBottom, leftBottom, leftTop, leftTop, topMiddle, topMiddle, rightTop, rightTop, 360f};
        int[] finalColors = new int[degrees.length];
        for (int i = 0; i < degrees.length; i++)
            finalColors[i] = colors[(i / 2) % colors.length];
        float[] positions = new float[degrees.length];
        for (int i = 0; i < degrees.length; i++)
            positions[i] = degrees[i] / 360f;
        return new SweepGradient(r.centerX(), r.centerY(), finalColors, positions);
    }

    private Shader createColorfulShader(RectF r) {
        int[] colors = {COLORFUL_YELLOW, COLORFUL_LIME, COLORFUL_BLUE, COLORFUL_INDIGO, COLORFUL_VIOLET};
        float[] stops = {0f, 0.25f, 0.5f, 0.75f, 1f};
        return new LinearGradient(r.left, r.top, r.right, r.bottom, colors, stops, Shader.TileMode.CLAMP);
    }

    private Shader createShinyShader(RectF r) {
        float halfWidth = r.width() / 2f;
        float halfHeight = r.height() / 2f;
        float axis = 2f * (float) Math.hypot(halfHeight, halfWidth);
        float halfRamp = axis > 0f ? Math.min(0.49f, 0.5f / axis) : 0f;
        return new LinearGradient(r.centerX() - halfHeight, r.centerY() - halfWidth, r.centerX() + halfHeight, r.centerY() + halfWidth, new int[]{SHINY_TOP_LEFT, SHINY_TOP_LEFT, SHINY_BOTTOM_RIGHT, SHINY_BOTTOM_RIGHT}, new float[]{0f, 0.5f - halfRamp, 0.5f + halfRamp, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        rect.set(bounds);
        if (shader == null || !shaderBounds.equals(rect)) {
            shaderBounds.set(rect);
            shader = createShader(rect);
        }
        paint.setShader(shader);
        float corner = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        canvas.drawRoundRect(rect, corner, corner, paint);
    }

    @Override
    public boolean getPadding(Rect padding) {
        padding.set(paddingH, paddingV, paddingH, paddingV);
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        if (paint.getAlpha() == alpha) return;
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
