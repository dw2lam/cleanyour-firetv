package online.davidlam.cinejoy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/**
 * The remote's virtual mouse pointer: a white arrow with a dark rim and soft shadow (readable on dark and
 * light pages), plus a short lime ring when it "clicks". Purely visual: it is not clickable or focusable,
 * so the touch/hover/scroll events MainActivity dispatches into the layout fall through it to the page.
 */
final class CursorView extends View {
    private static final long PULSE_MS = 280;

    private final Bitmap arrow;   // pre-rendered once (shadow layers render reliably on a software canvas)
    private final float hot;      // offset of the arrow's tip inside the bitmap (same on x and y)
    private final float dp;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float x = -1, y = -1;
    private long pulseStart = -1;

    CursorView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        float u = 1.15f * dp;   // one unit of the arrow outline below; the arrow is ~19 units (~22 dp) tall
        float pad = Math.round(4f * u);  // whole pixels, so the tip lands exactly on the cursor point
        hot = pad;

        Path p = new Path();
        p.moveTo(0, 0);
        p.lineTo(0, 16.5f * u);
        p.lineTo(4.1f * u, 12.6f * u);
        p.lineTo(6.9f * u, 18.9f * u);
        p.lineTo(9.6f * u, 17.7f * u);
        p.lineTo(6.85f * u, 11.5f * u);
        p.lineTo(12.2f * u, 11.5f * u);
        p.close();
        p.offset(pad, pad);

        arrow = Bitmap.createBitmap((int) Math.ceil(12.2f * u + 2 * pad), (int) Math.ceil(18.9f * u + 2 * pad),
                Bitmap.Config.ARGB_8888);
        arrow.setDensity(Bitmap.DENSITY_NONE); // draw 1:1, never density-scaled
        Canvas c = new Canvas(arrow);
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.FILL_AND_STROKE);
        rim.setStrokeJoin(Paint.Join.ROUND);
        rim.setStrokeWidth(1.5f * u);
        rim.setColor(0xFF111111);
        rim.setShadowLayer(2.4f * u, 0, 1.1f * u, 0x80000000);
        c.drawPath(p, rim);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFFFFFF);
        c.drawPath(p, fill);

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * dp);
        ring.setColor(0xFF95FF50);

        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    float cursorX() { return x; }
    float cursorY() { return y; }

    void moveTo(float nx, float ny) {
        x = nx;
        y = ny;
        invalidate();
    }

    /** Click feedback: a lime ring that grows and fades around the tip. */
    void pulse() {
        pulseStart = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (x < 0) { x = w / 2f; y = h / 2f; }  // first layout: start in the middle of the screen
        x = Math.min(x, w - 1f);
        y = Math.min(y, h - 1f);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (pulseStart >= 0) {
            float t = (SystemClock.uptimeMillis() - pulseStart) / (float) PULSE_MS;
            if (t >= 1f) {
                pulseStart = -1;
            } else {
                float e = 1f - (1f - t) * (1f - t);  // ease-out
                ring.setAlpha((int) (255 * (1f - e)));
                c.drawCircle(x, y, (6f + 18f * e) * dp, ring);
                postInvalidateOnAnimation();
            }
        }
        c.drawBitmap(arrow, Math.round(x - hot), Math.round(y - hot), null);
    }
}
