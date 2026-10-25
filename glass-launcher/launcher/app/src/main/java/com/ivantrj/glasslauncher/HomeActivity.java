package com.ivantrj.glasslauncher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen.
 *
 * Focus is handled here, not by Android's focus search, so D-pad movement is
 * deterministic: three zones (top bar, dock, grid), Up/Down move exactly one
 * row, and the ends of a row never fall through to another zone.
 */
public class HomeActivity extends Activity {

    private static final int TOP = 0, DOCK = 1, GRID = 2;
    private static final int COLUMNS = 4;
    private static final int PILL_MEDIA = 0, PILL_WIFI = 1, PILL_SETTINGS = 2;
    /** Opened by the Now Playing pill when nothing is playing. */
    private static final String MUSIC_APP = "com.amazon.music.tv";
    private static final String MUSIC_APP_FIRE_TV = "com.amazon.bueller.music";
    private static final int NEUTRAL_GLOW = 0xFF8E8E9E;

    private View root, page, band, glow, pillRow, header;
    private View[] pills;
    private GlassHighlight highlight;
    private TextView greeting, dockTitle, wifiName, mediaText, moveHint;
    private ImageView mediaArt, mediaPaused;
    private MediaSessionManager sessions;
    private MediaController media;
    private LinearLayout dockRow, inputsMenu;
    private RecyclerView grid;
    private ImageView wallpaper;
    private GridAdapter adapter;

    private final List<AppInfo> dockApps = new ArrayList<>();
    private List<AppInfo> gridApps = new ArrayList<>();

    private int zone = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0;
    private String restoreGridPkg;
    private int gridScrollTarget, gridScrollActual;
    private SpringValue pageSpring;

    private GradientDrawable glowDrawable;
    private int glowColor = NEUTRAL_GLOW;
    private ValueAnimator glowAnimator;

    private boolean menuOpen;
    private int menuIdx;
    /** Set by a submenu: Back / Left reopen its parent menu instead of closing. */
    private Runnable menuBack;
    private List<MenuItem> menuEntries = new ArrayList<>();
    /** Move mode (tile menu > Move): arrows move the focused tile instead of the focus. */
    private boolean moving;

    private static final class MenuItem {
        final CharSequence label; // null = a divider line
        final String detail; // smaller second line explaining the item, or null
        final Runnable action; // null = a status line, caption or divider that focus skips
        boolean caption; // small muted heading (e.g. the app name above a tile's menu)
        boolean option; // one of a set of choices, the current one marked with a leading ✓

        static MenuItem option(String label, boolean current, Runnable action) {
            MenuItem item = new MenuItem(current ? "✓  " + label : label, action);
            item.option = true;
            return item;
        }

        static MenuItem caption(CharSequence label) {
            MenuItem item = new MenuItem(label, null);
            item.caption = true;
            return item;
        }

        static MenuItem divider() {
            return new MenuItem(null, null);
        }

        MenuItem(CharSequence label, Runnable action) {
            this(label, null, action);
        }

        MenuItem(CharSequence label, String detail, Runnable action) {
            this.label = label;
            this.detail = detail;
            this.action = action;
        }
    }

    private boolean stopped = true;
    private long wallpaperStamp = -1;
    private long loadedConfigStamp;
    private float density;
    private SharedPreferences prefs;
    private WifiManager wifiManager;
    private ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("focus", MODE_PRIVATE);
        wifiManager = getApplicationContext().getSystemService(WifiManager.class);
        connectivity = getSystemService(ConnectivityManager.class);

        root = findViewById(R.id.root);
        page = findViewById(R.id.page);
        page.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyDisplayScale());
        header = findViewById(R.id.header);
        band = findViewById(R.id.band);
        glow = findViewById(R.id.glow);
        highlight = findViewById(R.id.highlight);
        greeting = findViewById(R.id.greeting);
        dockTitle = findViewById(R.id.dockTitle);
        wifiName = findViewById(R.id.wifiName);
        dockRow = findViewById(R.id.dockRow);
        grid = findViewById(R.id.grid);
        inputsMenu = findViewById(R.id.inputsMenu);
        wallpaper = findViewById(R.id.wallpaper);
        pillRow = findViewById(R.id.pills);
        pills = new View[] {findViewById(R.id.pillMedia), findViewById(R.id.pillWifi), findViewById(R.id.pillSettings)};
        mediaText = findViewById(R.id.mediaText);
        // Fixed at the cap width: the pill no longer grows or shrinks with playing / paused / idle,
        // so its left edge (and the caption under it) stays put when Select is pressed
        mediaText.setWidth(mediaText.getMaxWidth());
        mediaArt = findViewById(R.id.mediaArt);
        mediaPaused = findViewById(R.id.mediaPaused);
        moveHint = findViewById(R.id.moveHint);
        mediaArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(6));
            }
        });
        mediaArt.setClipToOutline(true);
        sessions = getSystemService(MediaSessionManager.class);

        greeting.setTypeface(Fonts.outfit(this, 300));
        ((TextClock) findViewById(R.id.clock)).setTypeface(Fonts.outfit(this, 120));
        ((TextClock) findViewById(R.id.date)).setTypeface(Fonts.outfit(this, 300));
        dockTitle.setTypeface(Fonts.outfit(this, 250));
        for (View pill : pills) ((TextView) ((LinearLayout) pill).getChildAt(1)).setTypeface(Fonts.outfit(this, 400));

        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(480 * density);
        glowDrawable.setGradientCenter(0.5f, 0.5f);
        setGlowColors(glowColor);
        glow.setBackground(glowDrawable);
        glow.setScaleY(0.42f); // squash the circle into a wide ellipse behind the dock

        pageSpring = new SpringValue(240f, 0.92f, v -> page.setTranslationY(-v));

        adapter = new GridAdapter(grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { gridScrollActual += dy; }
        });

        // The Wi-Fi pill changes width with the network name: keep the highlight on it
        pillRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (zone == TOP) moveHighlight();
            updateHintBar(); // the caption under the Now Playing pill follows the pill's new left edge
        });

        zone = prefs.getInt("zone", DOCK) == GRID ? GRID : DOCK;
        dockIdx = prefs.getInt("dockIdx", 0);
        topIdx = prefs.getInt("topIdx", 0);
        restoreGridPkg = prefs.getString("gridPkg", null);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);

        loadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, timeFilter);
        connectivity.registerDefaultNetworkCallback(networkCallback);
        updateGreeting();
        updateWifi();
        loadWallpaper();
        startMedia();
        if (configStamp() != loadedConfigStamp) loadApps(); // dock.txt / hidden.txt edited
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        unregisterReceiver(timeReceiver);
        connectivity.unregisterNetworkCallback(networkCallback);
        stopMedia();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (moving) finishMove(); // leaving mid-move keeps the tile where it is
        SharedPreferences.Editor e = prefs.edit()
                .putInt("zone", zone)
                .putInt("dockIdx", dockIdx)
                .putInt("topIdx", topIdx);
        if (gridIdx < gridApps.size()) e.putString("gridPkg", gridApps.get(gridIdx).packageName);
        e.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        io.shutdownNow();
    }

    /** Home pressed. From an app: keep the remembered focus. Already home: go back to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("reload", false)) { // sent by configure.sh
            loadApps();
            wallpaperStamp = -1;
            loadWallpaper();
            return;
        }
        boolean wasVisible = !stopped;
        closeMenu();
        if (wasVisible && !dockApps.isEmpty()) {
            zone = DOCK;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        loadedConfigStamp = configStamp();
        Context app = getApplicationContext();
        io.execute(() -> {
            AppLoader.Result result = AppLoader.load(app);
            main.post(() -> onAppsLoaded(result));
        });
    }

    private void onAppsLoaded(AppLoader.Result result) {
        String gridPkg = restoreGridPkg;
        if (gridPkg == null && gridIdx < gridApps.size()) gridPkg = gridApps.get(gridIdx).packageName;
        restoreGridPkg = null;

        dockApps.clear();
        dockApps.addAll(result.dock);
        bindDock();
        gridApps = result.grid;
        adapter.setApps(gridApps);

        dockIdx = clamp(dockIdx, 0, dockApps.size() - 1);
        int found = -1;
        for (int i = 0; gridPkg != null && i < gridApps.size(); i++) {
            if (gridApps.get(i).packageName.equals(gridPkg)) found = i;
        }
        gridIdx = clamp(found >= 0 ? found : gridIdx, 0, gridApps.size() - 1);
        if (zone == GRID && gridApps.isEmpty()) zone = DOCK;
        if (zone == DOCK && dockApps.isEmpty()) zone = TOP;

        // Place the highlight only once the new tiles have real positions
        afterLayout(() -> applyFocus(false));
    }

    private void bindDock() {
        dockRow.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        if (dockApps.isEmpty()) return;

        // Keep the normal 180dp tiles for up to four apps. With five apps, share the available
        // row evenly and shrink both tiles and icons as needed so the fifth tile remains visible.
        int count = dockApps.size();
        int preferredWidth = count * Math.round(dp(204));
        int availableWidth = Math.max(Math.round(dp(400)), page.getWidth() - Math.round(dp(144)));
        int rowWidth = Math.min(Math.min(Math.round(dp(816)), availableWidth), preferredWidth);
        FrameLayout.LayoutParams rowParams = (FrameLayout.LayoutParams) dockRow.getLayoutParams();
        rowParams.width = rowWidth;
        dockRow.setLayoutParams(rowParams);
        int cellWidth = rowWidth / count;
        int tileWidth = Math.min(Math.round(dp(180)), cellWidth - Math.round(dp(24)));
        tileWidth = Math.max(Math.round(dp(80)), tileWidth);
        int tileHeight = Math.round(tileWidth * 9f / 16f);

        // Center the band on the actual tile area, even when five tiles shrink.
        FrameLayout.LayoutParams bandParams = (FrameLayout.LayoutParams) band.getLayoutParams();
        int bandHeight = tileHeight + Math.round(dp(40));
        bandParams.width = rowWidth + Math.round(dp(24));
        bandParams.height = bandHeight;
        bandParams.topMargin = dockRow.getTop() + Math.round(dp(10))
                + tileHeight / 2 - bandHeight / 2;
        band.setLayoutParams(bandParams);
        dockRow.setGravity(Gravity.CENTER_HORIZONTAL);
        for (AppInfo app : dockApps) {
            View cell = inflater.inflate(R.layout.item_tile, dockRow, false);
            cell.setLayoutParams(new LinearLayout.LayoutParams(cellWidth, Math.round(dp(144))));
            View tile = cell.findViewById(R.id.tile);
            FrameLayout.LayoutParams tileParams = new FrameLayout.LayoutParams(
                    tileWidth, tileHeight, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            tileParams.topMargin = Math.round(dp(10));
            tile.setLayoutParams(tileParams);
            ImageView icon = cell.findViewById(R.id.icon);
            int iconSize = Math.min(Math.round(dp(54)), Math.round(tileWidth * 0.36f));
            icon.setLayoutParams(new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
            Tiles.bind(cell, app);
            // The big title above the dock already names the focused app; a second label under it competed
            cell.findViewById(R.id.label).setVisibility(View.GONE);
            dockRow.addView(cell);
        }
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (!handles(key)) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (menuOpen) {
            menuKey(key);
            return true;
        }
        if (moving) {
            moveKey(key);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_BACK: back(); break;
            case KeyEvent.KEYCODE_MENU: menuKey(); break;
            default: if (event.getRepeatCount() == 0) activate(); break;
        }
        return true;
    }

    private static boolean handles(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    private void left() {
        if (zone == TOP && topIdx > 0) topIdx--;
        else if (zone == DOCK && dockIdx > 0) dockIdx--;
        else if (zone == GRID && gridIdx % COLUMNS > 0) gridIdx--;
        else { nudge(-1, 0); return; }
        applyFocus(true);
    }

    private void right() {
        if (zone == TOP && topIdx < pills.length - 1) topIdx++;
        else if (zone == DOCK && dockIdx < dockApps.size() - 1) dockIdx++;
        else if (zone == GRID && gridIdx % COLUMNS < COLUMNS - 1 && gridIdx + 1 < gridApps.size()) gridIdx++;
        else { nudge(1, 0); return; }
        applyFocus(true);
    }

    /** A blocked arrow press bumps the focused item a little, so the press visibly registered. */
    private void nudge(int dx, int dy) {
        View v = zone == TOP ? pills[topIdx] : focusedTileView();
        v.animate().cancel();
        v.animate().translationX(dp(8) * dx).translationY(dp(8) * dy).setDuration(70)
                .withEndAction(() -> v.animate().translationX(0).translationY(0).setDuration(160).start())
                .start();
    }

    private void up() {
        if (zone == TOP) { nudge(0, -1); return; }
        if (zone == DOCK) {
            zone = TOP;
        } else if (gridIdx >= COLUMNS) {
            gridIdx -= COLUMNS;
        } else if (!dockApps.isEmpty()) {
            zone = DOCK;
            dockIdx = Math.min(gridIdx % COLUMNS, dockApps.size() - 1);
        } else {
            zone = TOP;
        }
        applyFocus(true);
    }

    private void down() {
        if (zone == TOP && !dockApps.isEmpty()) {
            zone = DOCK;
        } else if ((zone == TOP || zone == DOCK) && !gridApps.isEmpty()) {
            gridIdx = Math.min(zone == DOCK ? dockIdx : 0, gridApps.size() - 1);
            zone = GRID;
        } else if (zone == GRID && gridIdx / COLUMNS < (gridApps.size() - 1) / COLUMNS) {
            // One row down; a short last row catches the focus on its last tile
            gridIdx = Math.min(gridIdx + COLUMNS, gridApps.size() - 1);
        } else {
            nudge(0, 1);
            return;
        }
        applyFocus(true);
    }

    private void back() {
        if (zone == DOCK || dockApps.isEmpty()) return;
        zone = DOCK;
        applyFocus(true);
    }

    private void activate() {
        if (zone == TOP) {
            if (topIdx == PILL_MEDIA) openMediaApp();
            else if (topIdx == PILL_WIFI) launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else openSettingsMenu();
        } else {
            AppInfo app = focusedApp();
            if (app != null && app.packageName.equals(AppLoader.FIRE_HOME)) openFireHome();
            else if (app != null && app.packageName.equals(AppLoader.HIDDEN_APPS)) openHiddenAppsMenu();
            else if (app != null) launch(app.launchIntent);
        }
    }

    /**
     * Fire OS answers ACTION_SETTINGS with a stub that first sends Home and then opens Settings
     * inside the Amazon launcher, so open that Settings screen directly instead.
     */
    private void openSettings() {
        Intent fireSettings = new Intent().setClassName(
                "com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity");
        try {
            startActivity(fireSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            launch(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /** Settings pill: Fire TV settings and the screensaver. */
    private void openSettingsMenu() {
        openSettingsMenu(false);
    }

    /** onScreensaver: come back from the Screensaver submenu with its row selected. */
    private void openSettingsMenu(boolean onScreensaver) {
        openSettingsMenuAt(onScreensaver ? 3 : -1);
    }

    private void openSettingsMenuAt(int selectedIndex) {
        int minutes = HomeButtonService.screensaverMinutes(this);
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("Paramètres Fire TV", this::openSettings));
        items.add(MenuItem.divider());
        items.add(new MenuItem("Affichage et tuiles  ›", this::openDisplayMenu));
        items.add(new MenuItem("Économiseur d’écran  ›", minutes == 0 ? "Désactivé" : "Après " + minutes + " min",
                this::openScreensaverMenu));
        openMenu(pills[PILL_SETTINGS], items);
        if (selectedIndex >= 0 && selectedIndex < items.size()) {
            menuIdx = selectedIndex;
            updateMenuSelection();
        }
    }

    private void openDisplayMenu() {
        List<MenuItem> items = new ArrayList<>();
        int tileLimit = prefs.getInt("dockTileLimit", 5);
        int scale = prefs.getInt("displayScale", 100);
        items.add(MenuItem.caption("Affichage et tuiles"));
        items.add(MenuItem.divider());
        items.add(new MenuItem("Nombre de tuiles  ›", tileLimit + " sur la ligne", this::openDockCountMenu));
        items.add(new MenuItem("Taille des infos en haut  ›", scale + " %", this::openDisplayScaleMenu));
        openMenu(pills[PILL_SETTINGS], items);
        menuBack = () -> openSettingsMenuAt(2);
    }

    private void openDockCountMenu() {
        int current = prefs.getInt("dockTileLimit", 5);
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.caption("Nombre de tuiles"));
        items.add(MenuItem.divider());
        int selected = -1;
        for (int count = 1; count <= 5; count++) {
            final int value = count;
            if (count == current) selected = items.size();
            items.add(MenuItem.option(count + (count == 1 ? " tuile" : " tuiles"), count == current,
                    () -> setDockTileLimit(value)));
        }
        openMenu(pills[PILL_SETTINGS], items);
        menuBack = this::openDisplayMenu;
        if (selected >= 0) { menuIdx = selected; updateMenuSelection(); }
    }

    private void setDockTileLimit(int count) {
        prefs.edit().putInt("dockTileLimit", count).apply();
        loadApps();
        notice("✓  " + count + (count == 1 ? " tuile sur la ligne" : " tuiles sur la ligne"));
    }

    private void openDisplayScaleMenu() {
        int current = prefs.getInt("displayScale", 100);
        int[] scales = {70, 75, 80, 85, 90, 95, 100};
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.caption("Taille des infos en haut"));
        items.add(MenuItem.divider());
        int selected = -1;
        for (int scale : scales) {
            if (scale == current) selected = items.size();
            items.add(MenuItem.option(scale + " %", scale == current, () -> setDisplayScale(scale)));
        }
        openMenu(pills[PILL_SETTINGS], items);
        menuBack = this::openDisplayMenu;
        if (selected >= 0) { menuIdx = selected; updateMenuSelection(); }
    }

    private void setDisplayScale(int scale) {
        prefs.edit().putInt("displayScale", scale).apply();
        applyDisplayScale();
        // Scaling uses view transforms rather than layout params; recalculate the focus frame now,
        // so the white highlight follows the new size immediately without another remote-key press.
        if (zone == TOP) moveHighlight();
        notice("✓  Taille des infos : " + scale + " %");
    }

    /**
     * This setting changes only the information at the top of the screen.
     * Dock and grid tiles retain their designed size; the header shrinks from its top-left
     * corner and the status pills shrink from their top-right corner.
     */
    private void applyDisplayScale() {
        if (header == null || pillRow == null || header.getWidth() == 0 || pillRow.getWidth() == 0) return;
        // The displayed percentage is intentionally 10% larger than its rendered scale:
        // 100% now renders at 90%, leaving the whole top area less oversized.
        float scale = Math.max(70, Math.min(100, prefs.getInt("displayScale", 100))) / 100f * 0.9f;
        header.setPivotX(0f);
        header.setPivotY(0f);
        header.setScaleX(scale);
        header.setScaleY(scale);
        pillRow.setPivotX(pillRow.getWidth());
        pillRow.setPivotY(0f);
        pillRow.setScaleX(scale);
        pillRow.setScaleY(scale);
    }

    private static final int[] SCREENSAVER_MINUTES = {0, 2, 5, 10, 15, 30};

    /** Screensaver › : the idle time (✓ on the current one) and a preview. */
    private void openScreensaverMenu() {
        int current = HomeButtonService.screensaverMinutes(this);
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.caption("Écran de veille"));
        items.add(MenuItem.divider());
        int selected = -1;
        for (int minutes : SCREENSAVER_MINUTES) {
            String label = minutes == 0 ? "Désactivé" : "Après " + minutes + " min";
            if (minutes == current) selected = items.size();
            items.add(MenuItem.option(label, minutes == current, () -> setScreensaver(minutes)));
        }
        items.add(MenuItem.divider());
        items.add(new MenuItem("▶  Aperçu maintenant", () -> ScreensaverActivity.start(this)));
        openMenu(pills[PILL_SETTINGS], items);
        menuBack = () -> openSettingsMenu(true); // Back / Left step up to the Settings menu
        if (selected >= 0) { // start on the current choice
            menuIdx = selected;
            updateMenuSelection();
        }
    }

    private void setScreensaver(int minutes) {
        HomeButtonService.setScreensaverMinutes(this, minutes);
        notice(minutes == 0 ? "✓  Écran de veille désactivé" : "✓  Écran de veille après " + minutes + " min");
    }

    /** The "Fire TV Home" tile: Amazon's home until the next Home press. */
    private void openFireHome() {
        if (!HomeButtonService.visitFireHome(this)) notice("Fire TV Home n’est pas disponible sur cet appareil");
    }

    /** Menu (☰): Now Playing pauses/resumes, Settings opens its menu, a tile gets Move / App info. */
    private void menuKey() {
        if (zone == TOP) {
            if (topIdx == PILL_MEDIA) toggleMedia();
            else if (topIdx == PILL_SETTINGS) openSettingsMenu();
            return;
        }
        AppInfo app = focusedApp();
        if (app == null) return;
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.caption(app.label)); // which app this menu is for
        if (app.packageName.equals(AppLoader.FIRE_HOME)) {
            items.add(new MenuItem("Opens Amazon's home  ·  Home returns here", null));
        }
        if (app.packageName.equals(AppLoader.HIDDEN_APPS)) {
            items.add(new MenuItem("Afficher ou restaurer les applications masquées", this::openHiddenAppsMenu));
            openMenu(focusedTileView(), items);
            return;
        }
        items.add(MenuItem.divider());
        items.add(new MenuItem("Déplacer", this::startMove));
        // The Fire TV Home and clock screensaver tiles aren't real apps, so they have no info page
        if (!AppLoader.isShortcut(app.packageName)) {
            items.add(new MenuItem("Infos de l'application", this::openAppInfo));
            items.add(new MenuItem("Forcer l'arrêt", this::openAppInfo));
            items.add(new MenuItem("Masquer l’application", this::hideApp));
            items.add(new MenuItem("Désinstaller", this::uninstallApp));
        }
        openMenu(focusedTileView(), items);
    }

    /** Opens Android/Fire OS app details, where system actions such as Force stop are available. */
    private void openAppInfo() {
        AppInfo app = focusedApp();
        if (app == null || AppLoader.isShortcut(app.packageName)) return;
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null)));
    }

    /** Hides the selected app from the launcher without uninstalling it. */
    private void hideApp() {
        AppInfo app = focusedApp();
        if (app == null || AppLoader.isShortcut(app.packageName)) return;
        List<String> hidden = AppLoader.readList(this, "hidden.txt");
        if (!hidden.contains(app.packageName)) hidden.add(app.packageName);
        try (FileWriter writer = new FileWriter(new File(getFilesDir(), "hidden.txt"))) {
            for (String pkg : hidden) writer.write(pkg + "\n");
        } catch (Exception e) {
            notice("Impossible de masquer cette application");
            return;
        }
        restoreGridPkg = null;
        loadApps();
        notice("✓  " + app.label + " masquée");
    }
    /** Opens a list of hidden apps; selecting one restores it to the launcher. */
    private void openHiddenAppsMenu() {
        List<String> hidden = AppLoader.readList(this, "hidden.txt");
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.caption("Applications masquées"));
        items.add(MenuItem.divider());
        PackageManager pm = getPackageManager();
        int count = 0;
        for (String pkg : hidden) {
            if (AppLoader.isShortcut(pkg)) continue;
            String label = pkg;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException ignored) { }
            final String hiddenPackage = pkg;
            items.add(new MenuItem("↩  " + label, "Réafficher dans les applications", () -> unhideApp(hiddenPackage)));
            count++;
        }
        if (count == 0) items.add(new MenuItem("Aucune application masquée", null));
        openMenu(focusedTileView(), items);
    }

    /** Removes a package from hidden.txt, making it visible again in the grid. */
    private void unhideApp(String pkg) {
        List<String> hidden = AppLoader.readList(this, "hidden.txt");
        hidden.removeIf(pkg::equals);
        try (FileWriter writer = new FileWriter(new File(getFilesDir(), "hidden.txt"))) {
            for (String item : hidden) writer.write(item + "\n");
        } catch (Exception e) {
            notice("Impossible de réafficher cette application");
            return;
        }
        restoreGridPkg = AppLoader.HIDDEN_APPS;
        loadApps();
        notice("✓  Application réaffichée");
    }

    /** Requests the system package installer to uninstall the selected application. */
    private void uninstallApp() {
        AppInfo app = focusedApp();
        if (app == null || AppLoader.isShortcut(app.packageName)) return;
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.fromParts("package", app.packageName, null));
        try {
            launch(intent);
        } catch (ActivityNotFoundException e) {
            openAppInfo();
        }
    }

    private AppInfo focusedApp() {
        if (zone == DOCK && dockIdx < dockApps.size()) return dockApps.get(dockIdx);
        if (zone == GRID && gridIdx < gridApps.size()) return gridApps.get(gridIdx);
        return null;
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            notice("Can't open that here");
        }
    }

    // ---------------------------------------------------------------- focus visuals

    private void applyFocus(boolean animate) {
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            Tiles.setFocused(dockRow.getChildAt(i), zone == DOCK && i == dockIdx, animate);
        }
        adapter.setFocused(zone == GRID ? gridIdx : RecyclerView.NO_POSITION);
        // Pills don't grow on focus (growing crowded the neighbours and the right margin); the
        // highlight's bright rim marks them instead, the same as tiles
        for (int i = 0; i < pills.length; i++) {
            pills[i].setAlpha(zone == TOP && i != topIdx ? 0.6f : 1f);
        }

        // Down from the dock slides the whole page up; deeper rows scroll the grid itself.
        // While moving a tile in the dock, the page stays up too, so the first grid row (where a
        // swapped-out dock app lands) stays in view instead of the screen jumping.
        float pageTarget = 0;
        int scrollTarget = 0;
        if (zone == GRID || (moving && zone == DOCK && !gridApps.isEmpty())) {
            int row = zone == GRID ? gridIdx / COLUMNS : 0;
            if (row == 0) {
                pageTarget = band.getTop() - dp(30);
            } else {
                pageTarget = grid.getTop() - dp(20);
                scrollTarget = clamp((row - 1) * rowHeight(), 0, maxGridScroll());
            }
        }
        if (animate) pageSpring.animateTo(pageTarget); else pageSpring.snapTo(pageTarget);
        // At rest the grid is hidden (its first row would otherwise be cropped by the screen edge);
        // it fades in as the page slides up
        boolean gridShown = zone == GRID || moving;
        grid.animate().alpha(gridShown ? 1f : 0f).setDuration(animate ? 220 : 0).start();
        gridScrollTarget = scrollTarget;
        int dy = scrollTarget - gridScrollActual;
        if (dy != 0) {
            if (animate) grid.smoothScrollBy(0, dy, ease, 420);
            else grid.scrollBy(0, dy);
        }

        updateDockTitle(animate);
        moveHighlight();
        updateHintBar();
    }

    private void moveHighlight() {
        float l, t, w, h;
        if (zone == TOP) {
            View p = pills[topIdx];
            // The pill row is scaled from its top-right corner. Mirror that transform when
            // positioning the highlight, otherwise the white focus glass stays at 100%.
            float s = pillRow.getScaleX();
            float pad = dp(4) * s;
            float pivotX = pillRow.getPivotX();
            float pivotY = pillRow.getPivotY();
            float centerX = pillRow.getLeft() + pivotX
                    + (p.getLeft() + p.getWidth() / 2f - pivotX) * s;
            float centerY = pillRow.getTop() + pivotY
                    + (p.getTop() + p.getHeight() / 2f - pivotY) * s;
            w = p.getWidth() * s + 2 * pad;
            h = p.getHeight() * s + 2 * pad;
            l = centerX - w / 2f;
            t = centerY - h / 2f;
            highlight.moveTo(l, t, w, h, h / 2f);
            return;
        }
        float tileW = dp(180), tileH = dp(101);
        if (zone == DOCK) {
            if (dockIdx >= dockRow.getChildCount()) return;
            View cell = dockRow.getChildAt(dockIdx);
            View tile = cell.findViewById(R.id.tile);
            tileW = tile.getWidth();
            tileH = tile.getHeight();
            l = dockRow.getLeft() + cell.getLeft() + tile.getLeft();
            t = dockRow.getTop() + cell.getTop() + tile.getTop();
        } else {
            // Computed from the grid geometry and the scroll target, so the highlight
            // heads straight for the final spot while the grid is still scrolling
            int col = gridIdx % COLUMNS, row = gridIdx / COLUMNS;
            l = grid.getLeft() + grid.getPaddingLeft() + col * dp(204) + dp(12);
            t = grid.getTop() + grid.getPaddingTop() + row * rowHeight() - gridScrollTarget + dp(10);
        }
        float s = Tiles.FOCUS_SCALE, pad = dp(6);
        w = tileW * s + 2 * pad;
        h = tileH * s + 2 * pad;
        highlight.moveTo(l + tileW / 2f - w / 2f, t + tileH / 2f - h / 2f, w, h, dp(14) * s + pad);
    }

    private void updateDockTitle(boolean animate) {
        // Full in the dock, dimmed under the top bar, gone once the page slides up
        // Hidden while moving: the page is scrolled up then and the title would be cut off at the top
        float alpha = moving ? 0f : zone == DOCK ? 1f : zone == TOP ? 0.4f : 0f;
        glow.animate().alpha(zone == DOCK ? 1f : 0.5f).setDuration(animate ? 300 : 0).start();
        if (dockApps.isEmpty()) {
            dockTitle.setText("");
            return;
        }
        AppInfo app = dockApps.get(dockIdx);
        dockTitle.animate().cancel();
        if (!app.label.contentEquals(dockTitle.getText())) {
            dockTitle.setText(app.label);
            if (animate) {
                dockTitle.setAlpha(0f);
                dockTitle.setTranslationX(dp(10));
            }
            animateGlow(app.glowColor, animate);
        }
        if (animate) {
            dockTitle.animate().alpha(alpha).translationX(0).setDuration(260).setInterpolator(ease).start();
        } else {
            dockTitle.setAlpha(alpha);
            dockTitle.setTranslationX(0);
        }
    }

    private void animateGlow(int color, boolean animate) {
        if (glowAnimator != null) glowAnimator.cancel();
        if (!animate) {
            glowColor = color;
            setGlowColors(color);
            return;
        }
        glowAnimator = ValueAnimator.ofArgb(glowColor, color);
        glowAnimator.setDuration(500);
        glowAnimator.addUpdateListener(a -> {
            glowColor = (int) a.getAnimatedValue();
            setGlowColors(glowColor);
        });
        glowAnimator.start();
    }

    private void setGlowColors(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        glowDrawable.setColors(new int[] {Color.argb(120, r, g, b), Color.argb(46, r, g, b), Color.argb(0, r, g, b)});
    }

    // ---------------------------------------------------------------- dropdown menu

    /** A small glass dropdown under (or, near the bottom, over) the given view. */
    private void openMenu(View anchor, List<MenuItem> items) {
        menuEntries = items;
        menuBack = null;
        if (noticeShowing) { // a confirmation still on screen would sit on top of the menu
            main.removeCallbacks(endNotice);
            endNotice.run();
        }
        inputsMenu.removeAllViews();
        inputsMenu.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
        LayoutInflater inflater = LayoutInflater.from(this);
        menuIdx = -1;
        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            if (item.label == null) { // divider
                View line = new View(this);
                line.setBackgroundColor(0x66FFFFFF);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(1));
                lp.setMargins((int) dp(12), (int) dp(6), (int) dp(12), (int) dp(6));
                inputsMenu.addView(line, lp);
                continue;
            }
            TextView row = (TextView) inflater.inflate(R.layout.item_input, inputsMenu, false);
            row.setTypeface(Fonts.outfit(this, item.action == null ? 300 : 400));
            if (item.caption) {
                row.setTypeface(Fonts.outfit(this, 500));
                row.setTextSize(17);
                row.getLayoutParams().height = (int) dp(30);
            } else if (item.option) {
                // Choices share a check-mark gutter: the current one starts with "✓  ", the others are
                // indented by that width, so all the labels line up
                boolean checked = item.label.charAt(0) == '✓';
                float check = checked ? 0 : row.getPaint().measureText("✓  ");
                row.setPadding((int) (row.getPaddingLeft() + check), row.getPaddingTop(),
                        row.getPaddingRight(), row.getPaddingBottom());
            }
            if (item.detail != null) {
                SpannableStringBuilder text = new SpannableStringBuilder(item.label).append('\n');
                int start = text.length();
                text.append(item.detail);
                text.setSpan(new RelativeSizeSpan(0.95f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new ForegroundColorSpan(0xD9FFFFFF), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                row.setSingleLine(false);
                row.setMaxLines(2);
                row.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                row.setPadding(row.getPaddingLeft(), (int) dp(8), row.getPaddingRight(), (int) dp(8));
                row.setText(text);
            } else {
                row.setText(item.label);
            }
            if (item.action != null && menuIdx < 0) menuIdx = i; // status lines and captions aren't selectable
            inputsMenu.addView(row);
        }
        updateMenuSelection();

        inputsMenu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        // Lock the width to the content: a divider (a plain View) would otherwise stretch the menu
        // across the whole screen in the real layout pass.
        inputsMenu.getLayoutParams().width = inputsMenu.getMeasuredWidth();

        // The display-scale preference also applies to every settings and app context menu.
        // Keep the menu's right edge anchored to the pill in the top bar, and its left edge to tiles.
        float menuScale = Math.max(70, Math.min(100, prefs.getInt("displayScale", 100))) / 100f * 0.9f;
        inputsMenu.setPivotX(zone == TOP ? inputsMenu.getMeasuredWidth() : 0f);
        inputsMenu.setPivotY(0f);
        inputsMenu.setScaleX(menuScale);
        inputsMenu.setScaleY(menuScale);
        float menuWidth = inputsMenu.getMeasuredWidth();
        float menuHeight = inputsMenu.getMeasuredHeight() * menuScale;

        int[] a = new int[2], r = new int[2];
        anchor.getLocationInWindow(a);
        root.getLocationInWindow(r);
        float x = a[0] - r[0];
        if (zone == TOP) x += anchor.getWidth() * anchor.getScaleX() - menuWidth;
        // Account for the transformed bounds when clamping the menu to the screen.
        float visualLeftOffset = zone == TOP ? menuWidth * (1f - menuScale) : 0f;
        float minX = dp(24) - visualLeftOffset;
        float maxX = root.getWidth() - dp(24) - menuWidth * menuScale - visualLeftOffset;
        x = Math.max(minX, Math.min(x, maxX));

        float y = a[1] - r[1] + anchor.getHeight() * anchor.getScaleY() + dp(12);
        if (y + menuHeight > root.getHeight() - dp(16)) {
            y = a[1] - r[1] - menuHeight - dp(12);
        }
        // Keep scaled menus inside the screen, including when opened from a low grid row.
        float maxY = Math.max(dp(16), root.getHeight() - menuHeight - dp(16));
        y = Math.max(dp(16), Math.min(y, maxY));
        inputsMenu.setX(x);
        inputsMenu.setY(y - dp(8));
        inputsMenu.setAlpha(0f);
        inputsMenu.setVisibility(View.VISIBLE);
        inputsMenu.animate().alpha(1f).y(y).setDuration(180).setInterpolator(ease).start();
        menuOpen = true;
        // One focus at a time: the item that opened the menu fades its highlight while the menu is up
        highlight.animate().alpha(0f).setDuration(150).start();
        updateHintBar();
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        highlight.animate().alpha(1f).setDuration(150).start();
        updateHintBar();
        inputsMenu.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> inputsMenu.setVisibility(View.GONE)).start();
    }

    private void menuKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: // status lines (no action) are skipped
                for (int i = menuIdx - 1; i >= 0; i--) if (menuEntries.get(i).action != null) { menuIdx = i; break; }
                updateMenuSelection();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                for (int i = menuIdx + 1; i < menuEntries.size(); i++) if (menuEntries.get(i).action != null) { menuIdx = i; break; }
                updateMenuSelection();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                closeMenu();
                menuEntries.get(menuIdx).action.run();
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (menuBack != null) {
                    menuBack.run(); // a submenu: up one level
                    break;
                }
                closeMenu();
                break;
            default: // Right, Menu close it
                closeMenu();
        }
    }

    private void updateMenuSelection() {
        for (int i = 0; i < inputsMenu.getChildCount(); i++) {
            View child = inputsMenu.getChildAt(i);
            if (!(child instanceof TextView)) continue; // dividers keep their own colour
            child.setBackgroundResource(i == menuIdx ? R.drawable.menu_row_selected : 0);
        }
    }

    private View focusedTileView() {
        View cell = null;
        if (zone == DOCK && dockIdx < dockRow.getChildCount()) cell = dockRow.getChildAt(dockIdx);
        else if (zone == GRID && grid.getLayoutManager() != null) cell = grid.getLayoutManager().findViewByPosition(gridIdx);
        return cell != null ? cell : pills[topIdx]; // the whole cell, so the menu clears the app's name
    }

    // ---------------------------------------------------------------- move mode

    /** The layout before the move, so Back can put everything back. */
    private List<AppInfo> moveDockBefore, moveGridBefore;
    private int moveZoneBefore, moveDockIdxBefore, moveGridIdxBefore;

    private void startMove() {
        AppInfo app = focusedApp();
        if (app == null) return;
        moving = true;
        moveDockBefore = new ArrayList<>(dockApps);
        moveGridBefore = new ArrayList<>(gridApps);
        moveZoneBefore = zone;
        moveDockIdxBefore = dockIdx;
        moveGridIdxBefore = gridIdx;
        highlight.setCarrying(true);
        applyFocus(true); // re-anchors the page so the first grid row stays in view
    }

    private void moveKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: moveSideways(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: moveSideways(1); break;
            case KeyEvent.KEYCODE_DPAD_UP: moveVertically(-1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveVertically(1); break;
            case KeyEvent.KEYCODE_BACK: cancelMove(); break;
            default: finishMove(); // OK or Menu places the tile where it is
        }
    }

    private void cancelMove() {
        dockApps.clear();
        dockApps.addAll(moveDockBefore);
        gridApps.clear();
        gridApps.addAll(moveGridBefore);
        zone = moveZoneBefore;
        dockIdx = moveDockIdxBefore;
        gridIdx = moveGridIdxBefore;
        endMove();
        showOrder();
        notice("✕  Move cancelled · order unchanged");
    }

    private void endMove() {
        moving = false;
        highlight.setCarrying(false);
        applyFocus(true); // OK and Back both settle the page the same way (and refresh the hint bar)
    }

    // ---------------------------------------------------------------- hint bar

    /**
     * The Move instructions, with the one move that changes between dock and grid spelled out
     * where it applies (Up from the first grid row, Down from the dock).
     */
    private String moveHintText() {
        AppInfo app = focusedApp();
        String where = zone == DOCK ? "Down: into the grid   ·   "
                : zone == GRID && gridIdx < COLUMNS ? "Up: into the dock   ·   " : "";
        return "Moving " + (app != null ? app.label : "app") + "   ·   " + where
                + "Arrows: move   ·   OK: place   ·   Back: cancel";
    }
    private boolean noticeShowing;
    private final Runnable endNotice = () -> {
        noticeShowing = false;
        updateHintBar();
    };

    /** How the hint view is drawn: a quiet caption for key hints, a dark bar for instructions and notices. */
    private static final int HINT_CAPTION = 0, HINT_BAR = 1, HINT_NOTICE = 2;

    /**
     * One view (outside the sliding page, so always in view) for Move instructions, key hints
     * (Now Playing pill, grid) and short notices. Key hints are plain caption text so they don't
     * read as another button; instructions and notices sit on a dark bar.
     */
    private void updateHintBar() {
        if (noticeShowing) return;
        if (moving) showBar(moveHintText(), HINT_BAR);
        else showBar(null, HINT_CAPTION); // no key-hint captions (removed at the owner's request)
    }

    /** A confirmation on the dark bar, large and for 4 s (system toasts are hard to read at 3 m). */
    private void notice(CharSequence text) {
        noticeShowing = true;
        main.removeCallbacks(endNotice);
        showBar(text, HINT_NOTICE);
        main.postDelayed(endNotice, 4000);
    }

    private void showBar(CharSequence text, int style) {
        moveHint.animate().cancel();
        if (text == null) {
            if (moveHint.getVisibility() == View.VISIBLE) {
                moveHint.animate().alpha(0f).setDuration(140)
                        .withEndAction(() -> moveHint.setVisibility(View.GONE)).start();
            }
            return;
        }
        moveHint.setText(text);
        moveHint.setTypeface(Fonts.outfit(this, style == HINT_NOTICE ? 500 : 400));
        moveHint.setTextSize(style == HINT_NOTICE ? 20 : 17);
        // Confirmation bars follow the selected top-information scale.
        float noticeScale = style == HINT_NOTICE
                ? Math.max(70, Math.min(100, prefs.getInt("displayScale", 100))) / 100f * 0.9f : 1f;
        moveHint.setScaleX(noticeScale);
        moveHint.setScaleY(noticeScale);
        moveHint.setPivotX(moveHint.getWidth() / 2f);
        moveHint.setPivotY(0f);
        if (style == HINT_CAPTION) {
            moveHint.setBackground(null);
            moveHint.setPadding((int) dp(4), (int) dp(4), (int) dp(4), (int) dp(4));
        } else {
            moveHint.setBackgroundResource(R.drawable.menu_bg);
            moveHint.setPadding((int) dp(24), (int) dp(12), (int) dp(24), (int) dp(12));
        }
        float shown = style == HINT_CAPTION ? 0.95f : 1f;
        // When the page is scrolled up (grid, or moving a tile) the bottom of the screen is empty, so
        // the bar sits there. On the home view the dock fills the bottom, so the bar hangs under the
        // top bar instead, left-aligned with the Now Playing pill it most often describes.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) moveHint.getLayoutParams();
        // Notices up there are right-aligned with the pill row instead, so a long one stays inside the margin.
        boolean bottom = moving || zone == GRID;
        boolean underPill = !bottom && style == HINT_CAPTION;
        int gravity = bottom ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                : underPill ? Gravity.TOP | Gravity.START : Gravity.TOP | Gravity.END;
        int left = underPill ? pillRow.getLeft() + pills[PILL_MEDIA].getLeft() : 0;
        int right = !bottom && !underPill ? root.getWidth() - pillRow.getRight() : 0;
        if (lp.gravity != gravity || lp.leftMargin != left || lp.rightMargin != right) {
            lp.gravity = gravity;
            lp.topMargin = bottom ? 0 : (int) dp(96);
            lp.leftMargin = left;
            lp.rightMargin = right;
            lp.bottomMargin = bottom ? (int) dp(32) : 0;
            moveHint.setLayoutParams(lp);
        }
        if (moveHint.getVisibility() != View.VISIBLE) {
            moveHint.setAlpha(0f);
            moveHint.setVisibility(View.VISIBLE);
        }
        moveHint.animate().alpha(shown).setDuration(160).start();
    }

    private void moveSideways(int d) {
        if (zone == DOCK) {
            int to = dockIdx + d;
            if (to < 0 || to >= dockApps.size()) return;
            Collections.swap(dockApps, dockIdx, to);
            dockIdx = to;
        } else if (zone == GRID) {
            int to = gridIdx + d;
            if (to < 0 || to >= gridApps.size()) return;
            Collections.swap(gridApps, gridIdx, to);
            gridIdx = to;
        } else {
            return;
        }
        showOrder();
    }

    /** Down out of the dock drops into the grid; Up from the first grid row joins the dock (max 5). */
    private void moveVertically(int d) {
        if (zone == DOCK && d > 0) {
            AppInfo app = dockApps.remove(dockIdx);
            int to = Math.min(dockIdx, gridApps.size());
            gridApps.add(to, app);
            gridIdx = to;
            dockIdx = clamp(dockIdx, 0, dockApps.size() - 1);
            zone = GRID;
        } else if (zone == GRID && d < 0 && gridIdx < COLUMNS) {
            AppInfo app = gridApps.remove(gridIdx);
            int to = Math.min(gridIdx % COLUMNS, dockApps.size());
            if (dockApps.size() >= prefs.getInt("dockTileLimit", 5)) gridApps.add(gridIdx, dockApps.remove(to)); // full dock: swap places
            dockApps.add(to, app);
            dockIdx = to;
            zone = DOCK;
        } else if (zone == GRID) {
            int to = gridIdx + d * COLUMNS;
            if (to < 0 || to >= gridApps.size()) return;
            gridApps.add(to, gridApps.remove(gridIdx));
            gridIdx = to;
        } else {
            return;
        }
        showOrder();
    }

    private void showOrder() {
        bindDock();
        adapter.setApps(gridApps);
        afterLayout(() -> applyFocus(true));
    }

    private void finishMove() {
        endMove();
        AppLoader.writeList(this, "dock.txt", dockApps);
        AppLoader.writeList(this, "order.txt", gridApps);
        loadedConfigStamp = configStamp(); // our own write, no reload needed
        AppInfo app = focusedApp();
        // Name any app a full-dock swap pushed out, so it doesn't just vanish from the dock
        List<CharSequence> pushed = new ArrayList<>();
        for (AppInfo before : moveDockBefore) if (!dockApps.contains(before)) pushed.add(before.label);
        String extra = pushed.isEmpty() ? " · order saved" : " · " + TextUtils.join(", ", pushed) + " moved to the grid";
        notice("✓  " + (app != null ? app.label + " placed" : "Placed") + extra);
    }

    // ---------------------------------------------------------------- now playing

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged =
            controllers -> main.post(this::pickMedia);

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { updateMedia(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { updateMedia(); }
        @Override public void onSessionDestroyed() { pickMedia(); }
    };

    private ComponentName mediaListener() {
        return new ComponentName(this, MediaListener.class);
    }

    private void startMedia() {
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, mediaListener(), main);
        } catch (SecurityException e) {
            // No notification access yet: the pill just opens the music app
        }
        pickMedia();
    }

    private void stopMedia() {
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (SecurityException ignored) {
        }
        setMedia(null);
    }

    /** Prefers a player that is playing right now, else the most recent session. */
    private void pickMedia() {
        List<MediaController> list;
        try {
            list = sessions.getActiveSessions(mediaListener());
        } catch (SecurityException e) {
            list = new ArrayList<>();
        }
        MediaController pick = list.isEmpty() ? null : list.get(0);
        for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
        }
        setMedia(pick);
    }

    private void setMedia(MediaController next) {
        if (media != null && (next == null || !media.getSessionToken().equals(next.getSessionToken()))) {
            media.unregisterCallback(mediaCallback);
        }
        if (next != null && (media == null || !media.getSessionToken().equals(next.getSessionToken()))) {
            next.registerCallback(mediaCallback, main);
        }
        media = next;
        updateMedia();
    }

    /** " (feat. X)", " [with Y]" and the like: dropped so the title and artist fit the pill. */
    private static final Pattern FEATURING =
            Pattern.compile("\\s*[(\\[](feat\\.?|ft\\.?|featuring|with)\\s[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE);
    private static final String SEPARATOR = " · ";

    /**
     * "[prefix]Title · Artist" within the pill's text cap. If it all fits it's shown in full;
     * otherwise the artist keeps what it needs (up to 60% of the room) and the title is cut first,
     * so who's playing stays visible and the cut never lands on the separator. The prefix
     * ("Paused · ") is never cut, so playing and paused have the same structure.
     */
    private CharSequence mediaLabel(String prefix, CharSequence title, CharSequence artist) {
        String clean = cleanTitle(title);
        TextPaint paint = mediaText.getPaint();
        float cap = (mediaText.getMaxWidth() > 0 ? mediaText.getMaxWidth() : dp(200)) - paint.measureText(prefix);
        if (TextUtils.isEmpty(artist)) return prefix + TextUtils.ellipsize(clean, paint, cap, TextUtils.TruncateAt.END);
        float sep = paint.measureText(SEPARATOR);
        float artistWidth = paint.measureText(artist, 0, artist.length());
        if (paint.measureText(clean) + sep + artistWidth <= cap) return prefix + clean + SEPARATOR + artist;
        CharSequence a = TextUtils.ellipsize(artist, paint, Math.min(artistWidth, cap * 0.6f), TextUtils.TruncateAt.END);
        float titleRoom = cap - sep - paint.measureText(a, 0, a.length());
        CharSequence t = TextUtils.ellipsize(clean, paint, titleRoom, TextUtils.TruncateAt.END);
        return prefix + t + SEPARATOR + a;
    }

    private static String cleanTitle(CharSequence title) {
        String clean = FEATURING.matcher(title).replaceAll("").trim();
        return clean.isEmpty() ? title.toString() : clean;
    }

    private void updateMedia() {
        MediaMetadata meta = media == null ? null : media.getMetadata();
        CharSequence title = meta == null ? null : meta.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) {
            // Nothing playing: say what Select will do
            mediaArt.setImageResource(R.drawable.ic_music);
            mediaArt.setAlpha(0.85f);
            mediaPaused.setVisibility(View.GONE);
            CharSequence app = musicAppLabel();
            mediaText.setText(app != null ? "Ouvrir " + app + "  ›" : "Aucune lecture en cours");
            mediaText.setAlpha(1f);
            updateHintBar();
            return;
        }
        PlaybackState now = media.getPlaybackState();
        boolean isPlaying = now != null && now.getState() == PlaybackState.STATE_PLAYING;
        mediaText.setText(mediaLabel(isPlaying ? "" : "En pause" + SEPARATOR, title,
                meta.getText(MediaMetadata.METADATA_KEY_ARTIST)));

        Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != null) mediaArt.setImageBitmap(art);
        else mediaArt.setImageResource(R.drawable.ic_music);

        PlaybackState state = media.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        // Paused: a pause badge on the art, with the art and text dimmed a little
        mediaPaused.setVisibility(playing ? View.GONE : View.VISIBLE);
        mediaArt.setAlpha(playing ? 1f : 0.5f);
        mediaText.setAlpha(playing ? 1f : 0.75f);
        updateHintBar();
    }

    /** Prefer the Android TV Amazon Music package, with Fire TV's native package as fallback. */
    private String amazonMusicPackage() {
        PackageManager pm = getPackageManager();
        for (String candidate : new String[] {MUSIC_APP, MUSIC_APP_FIRE_TV}) {
            try {
                pm.getApplicationInfo(candidate, 0);
                return candidate;
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return MUSIC_APP;
    }

    /** The label of Amazon Music, or null if neither package is installed. */
    private CharSequence musicAppLabel() {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(amazonMusicPackage(), 0));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Menu on the pill: play/pause what's loaded (Select opens the app instead). */
    private void toggleMedia() {
        PlaybackState state = media == null ? null : media.getPlaybackState();
        if (state == null || media.getMetadata() == null) {
            notice("Aucune lecture en cours");
        } else if (state.getState() == PlaybackState.STATE_PLAYING) {
            media.getTransportControls().pause();
        } else {
            media.getTransportControls().play();
        }
    }

    private void openMediaApp() {
        String pkg = media != null ? media.getPackageName() : amazonMusicPackage();
        PackageManager pm = getPackageManager();
        Intent open = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (open == null) open = pm.getLaunchIntentForPackage(pkg);
        if (open == null && (pkg.equals(MUSIC_APP) || pkg.equals(MUSIC_APP_FIRE_TV))) {
            // Amazon Music isn't installed: open its store page.
            for (String store : new String[] {"amzn://apps/android?p=" + pkg, "market://details?id=" + pkg}) {
                Intent page = new Intent(Intent.ACTION_VIEW, Uri.parse(store));
                if (page.resolveActivity(pm) != null) {
                    launch(page);
                    return;
                }
            }
        }
        if (open == null) {
            notice((pkg.equals(MUSIC_APP) || pkg.equals(MUSIC_APP_FIRE_TV))
                    ? "Amazon Music n'est pas installé et aucune boutique n'est disponible"
                    : "Impossible d'ouvrir cette application");
            return;
        }
        launch(open);
    }

    // ---------------------------------------------------------------- clock, greeting, wifi, wallpaper

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateGreeting(); }
    };

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String text = hour < 5 ? "Bonne nuit" : hour < 12 ? "Bonjour" : hour < 18 ? "Bon après-midi"
                : hour < 22 ? "Bonsoir" : "Bonne nuit";
        greeting.setText(text);
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { main.post(HomeActivity.this::updateWifi); }
        @Override public void onLost(Network n) { main.post(HomeActivity.this::updateWifi); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { main.post(HomeActivity.this::updateWifi); }
    };

    private void updateWifi() {
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
        String text;
        if (caps == null) {
            text = "Offline";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            text = "Ethernet";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiInfo info = wifiManager.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) || ssid.isEmpty()) {
                text = "Wi-Fi"; // name hidden: location permission or location mode is off
            } else {
                text = ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
            }
        } else {
            text = "Connected";
        }
        if (!text.contentEquals(wifiName.getText())) wifiName.setText(text);
    }

    /** Optional wallpaper: wallpaper.jpg or wallpaper.png in the app's files dir (see configure.sh). */
    private void loadWallpaper() {
        File file = null;
        for (String name : new String[] {"wallpaper.jpg", "wallpaper.png"}) {
            File f = new File(getFilesDir(), name);
            if (f.isFile()) { file = f; break; }
        }
        long stamp = file == null ? 0 : file.lastModified() + file.length();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        if (file == null) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            return;
        }
        String path = file.getAbsolutePath();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        io.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory, fine for a dimmed photo
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            main.post(() -> {
                wallpaper.setImageBitmap(bmp);
                wallpaper.setColorFilter(Color.argb(115, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
                wallpaper.setVisibility(bmp == null ? View.GONE : View.VISIBLE);
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Changes whenever dock.txt or hidden.txt is added, edited or removed. */
    private long configStamp() {
        long stamp = 0;
        for (String name : new String[] {"dock.txt", "hidden.txt", "order.txt"}) {
            File f = new File(getFilesDir(), name);
            if (f.isFile()) stamp = stamp * 31 + f.lastModified() + f.length();
        }
        return stamp;
    }

    private void afterLayout(Runnable r) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
        root.requestLayout();
    }

    private int rowHeight() { return Math.round(dp(144)); }

    private int maxGridScroll() {
        int rows = (gridApps.size() + COLUMNS - 1) / COLUMNS;
        return Math.max(0, rows * rowHeight() + grid.getPaddingBottom() - grid.getHeight());
    }

    private float dp(float v) { return v * density; }

    private static int clamp(int v, int min, int max) { return max < min ? min : Math.max(min, Math.min(max, v)); }
}
