package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds launchable apps and splits them into the dock and the grid. Runs off the main thread. */
final class AppLoader {

    /** Pseudo package of the "Fire TV Home" tile, which opens Amazon's home screen (see HomeButtonService). */
    static final String FIRE_HOME = "glass.firetv-home";
    /** Pseudo package for the clock screensaver tile. */
    static final String KOI_POND = "glass.koi-pond";
    /** Recovery tile for apps hidden from the launcher. */
    static final String HIDDEN_APPS = "glass.hidden-apps";

    /** Tiles that are Glass's own shortcuts rather than installed apps (no App info page). */
    static boolean isShortcut(String pkg) {
        return FIRE_HOME.equals(pkg) || KOI_POND.equals(pkg) || HIDDEN_APPS.equals(pkg);
    }

    static final class Result {
        final List<AppInfo> dock = new ArrayList<>();
        final List<AppInfo> grid = new ArrayList<>();
    }

    static Result load(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, ActivityInfo> activities = new LinkedHashMap<>();
        // TV (leanback) activities win over phone-style ones from the same package
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, activities);
        collect(pm, Intent.CATEGORY_LAUNCHER, activities);
        activities.remove(context.getPackageName());
        for (String pkg : context.getResources().getStringArray(R.array.hidden_packages)) activities.remove(pkg);
        for (String pkg : readList(context, "hidden.txt")) activities.remove(pkg);

        Map<String, AppInfo> apps = new LinkedHashMap<>();
        for (ActivityInfo ai : activities.values()) {
            AppInfo app = toApp(pm, ai);
            if (app != null) apps.put(app.packageName, app);
        }
        apps.put(FIRE_HOME, fireHomeTile(context));
        apps.put(KOI_POND, koiPondTile(context));

        Result result = new Result();
        List<String> dock = readList(context, "dock.txt");
        // An empty dock.txt is a dock the user emptied on screen; only a missing file means defaults
        if (dock.isEmpty() && !new File(context.getFilesDir(), "dock.txt").isFile()) {
            dock = Arrays.asList(context.getResources().getStringArray(R.array.dock_packages));
        }
        int dockLimit = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
                .getInt("dockTileLimit", 5);
        dockLimit = Math.max(1, Math.min(5, dockLimit));
        for (String pkg : dock) {
            // Apps beyond the selected dock limit stay available in the grid.
            if (result.dock.size() >= dockLimit) continue;
            AppInfo app = apps.remove(pkg);
            if (app != null) result.dock.add(app);
        }
        result.grid.addAll(apps.values());
        Collator collator = Collator.getInstance();
        result.grid.sort((a, b) -> collator.compare(a.label, b.label));
        // order.txt (written when a tile is moved on screen) pins the grid order; apps not in it
        // (newly installed) keep their alphabetical place after the ordered ones.
        List<String> order = readList(context, "order.txt");
        if (!order.isEmpty()) {
            result.grid.sort((a, b) -> Integer.compare(rank(order, a), rank(order, b)));
        }
        // Always keep the recovery tile at the end of the grid, regardless of alphabetical order.
        result.grid.add(hiddenAppsTile(context));
        return result;
    }

    private static int rank(List<String> order, AppInfo app) {
        int i = order.indexOf(app.packageName);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Writes one package per line to a file in the app's private files dir (see readList). */
    static void writeList(Context context, String fileName, List<AppInfo> apps) {
        StringBuilder sb = new StringBuilder();
        for (AppInfo app : apps) sb.append(app.packageName).append('\n');
        try (FileWriter w = new FileWriter(new File(context.getFilesDir(), fileName))) {
            w.write(sb.toString());
        } catch (Exception ignored) {
        }
    }

    /**
     * One package per line from a file in the app's private files dir. configure.sh
     * writes these over ADB: dock.txt replaces the dock from res/values/config.xml,
     * hidden.txt adds to the built-in hidden list.
     */
    static List<String> readList(Context context, String fileName) {
        List<String> pkgs = new ArrayList<>();
        File file = new File(context.getFilesDir(), fileName);
        if (!file.isFile()) return pkgs;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) pkgs.add(line);
            }
        } catch (Exception ignored) {
        }
        return pkgs;
    }

    private static void collect(PackageManager pm, String category, Map<String, ActivityInfo> out) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            if (!out.containsKey(ri.activityInfo.packageName)) out.put(ri.activityInfo.packageName, ri.activityInfo);
        }
    }

    /** Takes part in the dock, grid order and Move like an app; HomeActivity opens it specially. */
    private static AppInfo fireHomeTile(Context context) {
        Drawable icon = context.getDrawable(R.drawable.icon_firetv_home);
        return new AppInfo(FIRE_HOME, "Fire TV Home", null, icon, sampleColor(icon), new Intent());
    }

    private static AppInfo hiddenAppsTile(Context context) {
        Drawable icon = context.getDrawable(R.drawable.icon_hidden_apps);
        return new AppInfo(HIDDEN_APPS, "Applications masquées", null, icon, sampleColor(icon), new Intent());
    }

    private static AppInfo koiPondTile(Context context) {
        Drawable icon = context.getDrawable(R.drawable.icon_koi_pond);
        Intent pond = new Intent(context, ScreensaverActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new AppInfo(KOI_POND, "Horloge", null, icon, sampleColor(icon), pond);
    }

    private static AppInfo toApp(PackageManager pm, ActivityInfo ai) {
        try {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(ai.packageName);
            if (launch == null) launch = pm.getLaunchIntentForPackage(ai.packageName);
            if (launch == null) return null;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            String label = String.valueOf(ai.loadLabel(pm));
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            Drawable icon = ai.loadIcon(pm);
            int glow = sampleColor(banner != null ? banner : icon);
            return new AppInfo(ai.packageName, label, banner, icon, glow, launch);
        } catch (Exception e) {
            return null;
        }
    }

    /** Weighted mean of the colourful pixels, pushed to a bright, saturated glow colour. */
    static int sampleColor(Drawable d) {
        if (d == null) return 0xFF8E8E9E;
        Bitmap bmp = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Rect old = new Rect(d.getBounds());
        d.setBounds(0, 0, 32, 18);
        d.draw(new Canvas(bmp));
        d.setBounds(old);
        double sx = 0, sy = 0, ss = 0, wsum = 0;
        float[] hsv = new float[3];
        for (int py = 0; py < 18; py++) {
            for (int px = 0; px < 32; px++) {
                int c = bmp.getPixel(px, py);
                float a = Color.alpha(c) / 255f;
                if (a < 0.5f) continue;
                Color.colorToHSV(c, hsv);
                double w = hsv[1] * hsv[2] * hsv[2] * a; // favour vivid, bright pixels
                double rad = Math.toRadians(hsv[0]);
                sx += Math.cos(rad) * w;
                sy += Math.sin(rad) * w;
                ss += hsv[1] * w;
                wsum += w;
            }
        }
        bmp.recycle();
        if (wsum < 8) return 0xFF8E8E9E; // monochrome banner: soft neutral glow
        float hue = (float) ((Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360);
        float sat = (float) Math.max(0.5, Math.min(0.9, ss / wsum));
        return Color.HSVToColor(new float[] {hue, sat, 0.95f});
    }

    private AppLoader() {}
}
