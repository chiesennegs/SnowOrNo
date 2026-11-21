package com.snoworno.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.http.HttpResponseCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LocationMap extends View {
    interface Selection { void changed(double latitude, double longitude); }

    private static final int MIN_ZOOM = 3, MAX_ZOOM = 19;
    private record Tile(int zoom, int x, int y) {}

    private final android.util.LruCache<Tile, Bitmap> tiles = new android.util.LruCache<>(70);
    private final android.util.LruCache<Tile, Boolean> failed = new android.util.LruCache<>(120);
    private final Set<Tile> pending = new HashSet<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final GestureDetector pan;
    private final ScaleGestureDetector pinch;
    private final Selection listener;
    private final float density;
    private final int markerColor;
    private int zoom = 14;
    private double x, y;
    private float pinchFactor = 1;
    private boolean multiTouch;

    LocationMap(Context context, double lat, double lon, Selection listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        markerColor = context.getColor(R.color.snow_accent_dark);
        x = (lon + 180) / 360;
        double sin = Math.sin(Math.toRadians(lat));
        y = .5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
        if (HttpResponseCache.getInstalled() == null) {
            try {
                HttpResponseCache.install(new File(context.getCacheDir(), "street-tiles"), 24L * 1024 * 1024);
            } catch (java.io.IOException ignored) {
                // Keep the in-memory tile cache when the disk cache is unavailable.
            }
        }
        pan = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent last, float dx, float dy) {
                if (!multiTouch) move(dx, dy);
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                if (!multiTouch) {
                    performClick();
                    move(event.getX() - getWidth() / 2f, event.getY() - getHeight() / 2f);
                }
                return true;
            }
        });
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                pinchFactor = 1;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                pinchFactor *= detector.getScaleFactor();
                if (pinchFactor >= 1.4f || pinchFactor <= .71f) {
                    zoomBy(pinchFactor > 1 ? 1 : -1);
                    pinchFactor = 1;
                }
                return true;
            }
        });
        setContentDescription("Street map. Drag to move the pin or tap a new point.");
    }

    void zoomBy(int amount) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + amount));
        invalidate();
    }

    void resetFailedTiles() {
        failed.evictAll();
        invalidate();
    }

    double latitude() { return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y)))); }
    double longitude() { return x * 360 - 180; }

    private float tileSize() { return 256 * Math.min(1.5f, density); }

    private void move(float dx, float dy) {
        double size = tileSize() * (1 << zoom);
        x += dx / size;
        x -= Math.floor(x);
        y = Math.max(0, Math.min(1, y + dy / size));
        listener.changed(latitude(), longitude());
        invalidate();
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            multiTouch = false;
        }
        if (event.getPointerCount() > 1) multiTouch = true;
        pinch.onTouchEvent(event);
        pan.onTouchEvent(event);
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff132747);
        int count = 1 << zoom;
        float tileSize = tileSize();
        double left = x * count - getWidth() / (2.0 * tileSize);
        double top = y * count - getHeight() / (2.0 * tileSize);
        int endX = (int)Math.ceil(left + getWidth() / tileSize);
        int endY = Math.min(count, (int)Math.ceil(top + getHeight() / tileSize));
        Set<Tile> visible = new HashSet<>();
        for (int j = Math.max(0, (int)Math.floor(top)); j < endY; j++) {
            for (int i = (int)Math.floor(left); i < endX; i++) {
                Tile tile = new Tile(zoom, Math.floorMod(i, count), j);
                visible.add(tile);
                Bitmap bitmap = tiles.get(tile);
                float l = (float)((i - left) * tileSize), t = (float)((j - top) * tileSize);
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, new RectF(l, t, l + tileSize + .5f, t + tileSize + .5f), ink);
                } else {
                    ink.setColor(0xff345681);
                    canvas.drawLine(l, t, l + tileSize, t, ink);
                    canvas.drawLine(l, t, l, t + tileSize, ink);
                    fetch(tile);
                }
            }
        }
        if (!visible.isEmpty() && visible.stream().allMatch(tile -> failed.get(tile) != null)) {
            ink.setTextSize(13 * density);
            ink.setColor(0xfff3f8ff);
            ink.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Street tiles unavailable · Retry map", getWidth() / 2f, 26 * density, ink);
        }
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        ink.setColor(0xff0a1730);
        canvas.drawCircle(cx, cy, 12 * density, ink);
        ink.setColor(markerColor);
        canvas.drawCircle(cx, cy, 8 * density, ink);
        ink.setColor(0xff0a1730);
        canvas.drawCircle(cx, cy, 2.5f * density, ink);
    }

    private void fetch(Tile tile) {
        if (loader.isShutdown() || pending.size() >= 12 || pending.contains(tile) || failed.get(tile) != null) return;
        pending.add(tile);
        loader.execute(() -> {
            Bitmap image = load(tile);
            post(() -> {
                pending.remove(tile);
                if (image == null) failed.put(tile, true);
                else tiles.put(tile, image);
                invalidate();
            });
        });
    }

    private static Bitmap load(Tile tile) {
        HttpURLConnection connection = null;
        try {
            String address = "https://tile.openstreetmap.org/" + tile.zoom() + "/" + tile.x() + "/" + tile.y() + ".png";
            connection = (HttpURLConnection)new URL(address).openConnection();
            connection.setRequestProperty("User-Agent", "SnowOrNo-Android/0.3");
            connection.setConnectTimeout(5500);
            connection.setReadTimeout(5500);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (java.io.IOException ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override protected void onDetachedFromWindow() {
        loader.shutdownNow();
        HttpResponseCache cache = HttpResponseCache.getInstalled();
        if (cache != null) cache.flush();
        super.onDetachedFromWindow();
    }
}
