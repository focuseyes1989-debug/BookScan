package app.bookscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class PageCropView extends View {
    private final Bitmap preview;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF image = new RectF();
    private final Path border = new Path();
    private final Path shade = new Path();
    private final float[] corners = { .025f, .025f, .975f, .025f, .975f, .975f, .025f, .975f };
    private final float handle;
    private int dragging = -1;

    PageCropView(Context context, Bitmap preview) {
        super(context);
        this.preview = preview;
        handle = 11 * getResources().getDisplayMetrics().density;
        setContentDescription("စာရွက်ထောင့် လေးခုကို ဆွဲညှိရန်");
    }

    float[] selection() {
        return corners.clone();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        float scale = Math.min((w - 4 * handle) / preview.getWidth(),
                (h - 4 * handle) / preview.getHeight());
        float width = preview.getWidth() * scale;
        float height = preview.getHeight() * scale;
        image.set((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff111c20);
        canvas.drawBitmap(preview, null, image, paint);
        border.rewind();
        border.moveTo(x(0), y(0));
        for (int i = 1; i < 4; i++) border.lineTo(x(i), y(i));
        border.close();

        shade.rewind();
        shade.addRect(image, Path.Direction.CW);
        shade.addPath(border);
        shade.setFillType(Path.FillType.EVEN_ODD);
        paint.setColor(0x99000000);
        canvas.drawPath(shade, paint);

        paint.setColor(0xffadf3d1);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(handle / 5);
        canvas.drawPath(border, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            paint.setColor(Color.BLACK);
            canvas.drawCircle(x(i), y(i), handle + 2, paint);
            paint.setColor(0xffadf3d1);
            canvas.drawCircle(x(i), y(i), handle, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                double nearest = handle * 3;
                for (int i = 0; i < 4; i++) {
                    double distance = Math.hypot(event.getX() - x(i), event.getY() - y(i));
                    if (distance < nearest) {
                        nearest = distance;
                        dragging = i;
                    }
                }
                return dragging >= 0;
            case MotionEvent.ACTION_MOVE:
                if (dragging < 0) return false;
                float[] proposed = corners.clone();
                proposed[2 * dragging] = clamp((event.getX() - image.left) / image.width());
                proposed[2 * dragging + 1] = clamp((event.getY() - image.top) / image.height());
                if (isConvex(proposed)) {
                    System.arraycopy(proposed, 0, corners, 0, corners.length);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                // Fall through to clear the active handle.
            case MotionEvent.ACTION_CANCEL:
                dragging = -1;
                return true;
            default:
                return false;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private float x(int i) { return image.left + image.width() * corners[2 * i]; }
    private float y(int i) { return image.top + image.height() * corners[2 * i + 1]; }

    private static float clamp(float value) {
        return Math.max(0, Math.min(1, value));
    }

    private static boolean isConvex(float[] p) {
        for (int i = 0; i < 8; i += 2) {
            int j = (i + 2) % 8;
            int k = (i + 4) % 8;
            float cross = (p[j] - p[i]) * (p[k + 1] - p[j + 1])
                    - (p[j + 1] - p[i + 1]) * (p[k] - p[j]);
            if (cross < .005f) return false;
        }
        return true;
    }

    static Bitmap crop(Bitmap original, float[] selection) {
        float[] source = selection.clone();
        for (int i = 0; i < source.length; i += 2) {
            source[i] *= original.getWidth();
            source[i + 1] *= original.getHeight();
        }
        int width = (int) Math.ceil(Math.max(distance(source, 0, 2), distance(source, 4, 6)));
        int height = (int) Math.ceil(Math.max(distance(source, 0, 6), distance(source, 2, 4)));
        float[] destination = { 0, 0, width, 0, width, height, 0, height };
        Matrix transform = new Matrix();
        if (!transform.setPolyToPoly(source, 0, destination, 0, 4)) {
            throw new IllegalArgumentException("Invalid page crop");
        }
        Bitmap cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cropped);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(original, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
        return cropped;
    }

    private static double distance(float[] p, int a, int b) {
        return Math.hypot(p[a] - p[b], p[a + 1] - p[b + 1]);
    }
}
