package io.github.shaunak3000.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;

/**
 * An app tile: the app's banner with rounded corners, or its icon and name when it has no banner.
 * Focused, it grows and gets a white ring, with a dark gap inside so the ring shows on white banners too.
 */
final class Tile extends View {
    private static final int RING = 0xFFFFFFFF, MOVING_RING = 0xFFF2C14E, GAP = 0xEB080A10, PLAIN = 0x990C1018;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private final float density = getResources().getDisplayMetrics().density;
    private final float pad = 5 * density;   // room around the banner for a 2.5dp gap and a 2.5dp ring
    private final float radius;
    private final RectF art = new RectF(), gap = new RectF(), ring = new RectF();
    private final Paint banner = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private final CharSequence name;
    private final float nameBaseline;
    private boolean lifted;

    /** widthDp is the banner's width; banners are 16:9. */
    Tile(Context context, CharSequence label, Drawable bannerArt, Drawable iconArt, float widthDp) {
        super(context);
        int w = Math.round(widthDp * density), h = Math.round(widthDp * 9 / 16 * density);
        radius = w / 17f;
        art.set(pad, pad, pad + w, pad + h);
        gap.set(art);
        gap.inset(-pad / 2, -pad / 2);
        ring.set(art);
        ring.inset(-pad, -pad);

        if (bannerArt != null) {
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bannerArt.setBounds(0, 0, w, h);
            bannerArt.draw(new Canvas(bitmap));
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix offset = new Matrix();
            offset.setTranslate(pad, pad);
            shader.setLocalMatrix(offset);
            banner.setShader(shader);
            icon = null;
        } else if (iconArt != null && iconArt.getConstantState() != null) {
            icon = iconArt.getConstantState().newDrawable(getResources()).mutate();   // its own copy: bounds differ per tile
        } else {
            icon = iconArt;
        }

        text.setColor(0xFFFFFFFF);
        text.setTextSize(h * 0.15f);
        text.setTextAlign(Paint.Align.CENTER);
        name = TextUtils.ellipsize(label, text, w - 2 * pad, TextUtils.TruncateAt.END);
        int side = Math.round(h * 0.34f);
        float block = side + h * 0.08f + text.getTextSize();
        int top = Math.round(art.centerY() - block / 2);
        if (icon != null) icon.setBounds(Math.round(art.centerX() - side / 2f), top, Math.round(art.centerX() + side / 2f), top + side);
        nameBaseline = top + block - text.descent();

        setFocusable(true);
        setClickable(true);
        setContentDescription(label);
        setElevation(3 * density);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(Math.round(art.left), Math.round(art.top), Math.round(art.right), Math.round(art.bottom), radius);
            }
        });
    }

    void setLifted(boolean lifted) {
        this.lifted = lifted;
        update();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(Math.round(ring.right), Math.round(ring.bottom));
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        update();
    }

    private void update() {
        boolean raised = lifted || isFocused();
        float scale = lifted ? 1.14f : raised ? 1.1f : 1f;
        animate().scaleX(scale).scaleY(scale).translationY(lifted ? -7 * density : 0)
                .translationZ(raised ? 8 * density : 0).setInterpolator(EASE).setDuration(180).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (lifted || isFocused()) {
            fill.setColor(lifted ? MOVING_RING : RING);
            canvas.drawRoundRect(ring, radius + pad, radius + pad, fill);
            fill.setColor(GAP);
            canvas.drawRoundRect(gap, radius + pad / 2, radius + pad / 2, fill);
        }
        if (banner.getShader() != null) {
            canvas.drawRoundRect(art, radius, radius, banner);
            return;
        }
        fill.setColor(PLAIN);
        canvas.drawRoundRect(art, radius, radius, fill);
        if (icon != null) icon.draw(canvas);
        canvas.drawText(name, 0, name.length(), art.centerX(), nameBaseline, text);
    }
}
