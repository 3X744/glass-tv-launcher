package com.ivantrj.glasslauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Minimal OLED-friendly clock: pure black, muted text, and a small position shift. */
public class ScreensaverActivity extends Activity {

    static volatile boolean showing;

    static void start(Context context) {
        context.startActivity(new Intent(context, ScreensaverActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ClockView clockView;
    private int pressedKey = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (clockView != null) clockView.invalidate();
            main.postDelayed(this, 15_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showing = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // Allow Fire OS to turn the display off after its normal screen-off timeout.
        long sleepAfter = Settings.System.getLong(getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, 15 * 60_000L);
        main.postDelayed(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON),
                sleepAfter);

        clockView = new ClockView(this);
        setContentView(clockView);
        main.post(tick);
    }

    private static final class ClockView extends View {
        private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE);

        ClockView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            timePaint.setColor(Color.rgb(112, 112, 120));
            timePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            timePaint.setTextAlign(Paint.Align.CENTER);
            datePaint.setColor(Color.rgb(72, 72, 80));
            datePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            datePaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);

            float w = getWidth();
            float h = getHeight();
            float density = getResources().getDisplayMetrics().density;
            timePaint.setTextSize(Math.min(w * 0.145f, 116 * density));
            datePaint.setTextSize(19 * density);

            // Slowly move the clock among low-amplitude positions to reduce OLED image retention.
            long minute = System.currentTimeMillis() / 60_000L;
            int slot = (int) (minute % 9);
            float[] xShift = {-0.035f, 0f, 0.028f, 0.035f, 0f, -0.028f, 0.018f, -0.018f, 0f};
            float[] yShift = {-0.025f, -0.01f, 0.015f, 0.03f, 0.01f, -0.02f, 0.025f, 0f, -0.03f};
            float x = w * (0.5f + xShift[slot]);
            float centerY = h * (0.5f + yShift[slot]);

            Date now = new Date();
            String time = timeFormat.format(now);
            String date = dateFormat.format(now);
            if (!date.isEmpty()) {
                date = date.substring(0, 1).toUpperCase(Locale.FRANCE) + date.substring(1);
            }

            Paint.FontMetrics tf = timePaint.getFontMetrics();
            float timeBaseline = centerY - (tf.ascent + tf.descent) / 2f - 8 * density;
            canvas.drawText(time, x, timeBaseline, timePaint);
            Paint.FontMetrics df = datePaint.getFontMetrics();
            float dateBaseline = timeBaseline + (tf.descent - tf.ascent) * 0.58f
                    - (df.ascent + df.descent) / 2f + 16 * density;
            canvas.drawText(date, x, dateBaseline, datePaint);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            pressedKey = event.getKeyCode();
        } else if (event.getAction() == KeyEvent.ACTION_UP && event.getKeyCode() == pressedKey) {
            finish();
        }
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        showing = false;
        main.removeCallbacksAndMessages(null);
        clockView = null;
    }
}
