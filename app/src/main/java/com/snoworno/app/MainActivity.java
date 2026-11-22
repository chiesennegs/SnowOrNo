package com.snoworno.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int CALENDAR_TAB = 2;
    private static final int IMPORT_HISTORY = 401, EXPORT_HISTORY = 402;
    private record CalendarOrigin(int tab, int scrollY) {}
    private record HistoryMonth(WeatherHistoryStore.PlaceKey site, YearMonth month, LocalDate today) {}

    private int bg, panel, line, ink, muted, accent, selectedFill, warn;
    private boolean lightMode, settingsOpen, feedbackOpen;
    private String dailyStormId;
    private DisplayUnits units;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("h a", Locale.US);
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("MMM d, h a", Locale.US);
    private static final DateTimeFormatter OBSERVATION_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy, h a", Locale.US);
    private static final DateTimeFormatter MEASUREMENT_TIME = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", Locale.US);
    private static final DateTimeFormatter CUTOFF_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter RETRIEVED = DateTimeFormatter.ofPattern("MMM d, h:mm a z", Locale.US);
    private static final long REFRESH_AFTER_MS = 15 * 60 * 1000L;
    private static final String COMPANY_SITE = "https://mooseaillc.com";
    private static final String PRIVACY_POLICY_URL = COMPANY_SITE + "/privacy/snoworno";
    private static final String SUPPORT_EMAIL = "support@mooseaillc.com";
    private static final String OPEN_METEO_URL = "https://open-meteo.com/";
    private static final String WEATHER_DATA_LICENSE = "https://creativecommons.org/licenses/by/4.0/";

    private static final Forecast.Site[] SITES = {
            new Forecast.Site("Alta", "Wasatch Range, Utah", 40.58840, -111.63810, 8530),
            new Forecast.Site("Snowbird", "Wasatch Range, Utah", 40.58100, -111.65610, 7760),
            new Forecast.Site("Park City", "Wasatch Back, Utah", 40.65140, -111.50780, 7000),
            new Forecast.Site("Salt Lake City", "Salt Lake Valley, Utah", 40.76080, -111.89100, 4226),
            new Forecast.Site("Brian Head", "Southern Utah", 37.69280, -112.84870, 9800),
            new Forecast.Site("Denver", "Front Range, Colorado", 39.73920, -104.99030, 5280),
            new Forecast.Site("Loveland Pass", "Front Range, Colorado", 39.66360, -105.88270, 11990)
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService locationWorker = Executors.newSingleThreadExecutor();
    private Forecast.Site site;
    private FavoriteLocations favorites;
    private StormFeedback stormFeedback;
    private Forecast forecast;
    private ForecastCache forecastCache;
    private WeatherHistoryStore weatherHistory;
    private final ExecutorService historyWorker = Executors.newSingleThreadExecutor();
    private Future<?> historyRequest;
    private HistoryMonth historyMonth;
    private WeatherHistoryStore.Month historicalWeather;
    private boolean historyLoading, historyFailed, historyTransfer;
    private Instant retrievedAt;
    private SnowTimeline timeline;
    private LinearLayout root, body;
    private ScrollView scroller;
    private TextView returnToTop;
    private boolean loading, showingCache;
    private long lastAttempt = -1;
    private PropertySnow.Surface surface = PropertySnow.Surface.LAWN;
    private boolean shade, showMeltDetails;
    private Future<?> activeRequest;
    private int request, tab;
    private LocalDate calendarDate;
    private CalendarOrigin calendarOrigin;
    private final Set<Instant> expandedStorms = new HashSet<>();
    private final Set<Instant> collapsedStorms = new HashSet<>();
    private final Set<String> collapsedHistory = new HashSet<>();
    private int insetTop, insetBottom, insetRight;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        site = restoreSite();
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        units = new DisplayUnits(prefs.getBoolean("celsius", false), prefs.getBoolean("centimeters", false));
        lightMode = prefs.getBoolean("light_mode", false);
        applyPalette();
        favorites = FavoriteLocations.fromJson(prefs.getString("favorite_locations", "[]"));
        stormFeedback = StormFeedback.read(prefs.getString("storm_feedback", "[]"));
        surface = prefs.getBoolean("driveway", false) ? PropertySnow.Surface.PAVEMENT : PropertySnow.Surface.LAWN;
        shade = prefs.getBoolean("shade", false);
        forecastCache = new ForecastCache(new java.io.File(getCacheDir(), "forecasts"));
        weatherHistory = new WeatherHistoryStore(this);
        scroller = new ScrollView(this);
        scroller.setBackgroundColor(bg);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        root = column();
        returnToTop = action("↑", true, () -> scroller.smoothScrollTo(0, 0));
        returnToTop.setTextSize(24);
        returnToTop.setContentDescription("Return to top");
        returnToTop.setTooltipText("Return to top");
        returnToTop.setElevation(dp(6));
        returnToTop.setVisibility(View.GONE);
        FrameLayout screen = new FrameLayout(this);
        screen.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        screen.addView(returnToTop, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM | Gravity.RIGHT));
        scroller.setOnScrollChangeListener((view, x, y, oldX, oldY) -> updateReturnToTop());
        updateInsets();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                insetTop = bars.top;
                insetBottom = bars.bottom;
                insetRight = bars.right;
                updateInsets();
            }
            return insets;
        });
        scroller.addView(root);
        setContentView(screen);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!loading && (lastAttempt < 0 || SystemClock.elapsedRealtime() - lastAttempt >= REFRESH_AFTER_MS)) {
            loadForecast();
        }
    }

    private Forecast.Site restoreSite() {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        try {
            String n = prefs.getString("name", null);
            if (n == null) return SITES[3];
            double lat = Double.parseDouble(prefs.getString("lat", ""));
            double lon = Double.parseDouble(prefs.getString("lon", ""));
            int elevation = prefs.getInt("feet", 0);
            if (ForecastSource.validSite(lat, lon, elevation)) {
                return new Forecast.Site(n, prefs.getString("region", ""), lat, lon, elevation);
            }
        } catch (NumberFormatException ignored) {
            return SITES[3];
        }
        return SITES[3];
    }

    private void updateInsets() {
        root.setPadding(dp(20), dp(18) + insetTop, dp(20), dp(24) + insetBottom);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)returnToTop.getLayoutParams();
        params.rightMargin = dp(18) + insetRight;
        params.bottomMargin = dp(18) + insetBottom;
        returnToTop.setLayoutParams(params);
    }

    private void updateReturnToTop() {
        boolean stormList = !settingsOpen && (feedbackOpen ? dailyStormId == null : tab == 1 && forecast != null);
        returnToTop.setVisibility(stormList && scroller.getScrollY() > dp(180) ? View.VISIBLE : View.GONE);
    }

    private void applyPalette() {
        bg = lightMode ? 0xffeaf4ff : 0xff0a1730;
        panel = getColor(lightMode ? R.color.snow_panel_light : R.color.snow_panel_dark);
        line = lightMode ? 0xffa9c3e0 : 0xff345681;
        ink = lightMode ? 0xff112b4d : 0xfff3f8ff;
        muted = lightMode ? 0xff405a7a : 0xffb9cce7;
        accent = getColor(lightMode ? R.color.snow_accent_light : R.color.snow_accent_dark);
        selectedFill = lightMode ? 0xffd8eaff : 0xff1b3b66;
        warn = lightMode ? 0xff804216 : 0xffffd09a;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            var controller = getWindow().getInsetsController();
            int bars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (controller != null) controller.setSystemBarsAppearance(lightMode ? bars : 0, bars);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(lightMode
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        }
    }

    private void openSettings() {
        settingsOpen = true;
        render();
        scroller.scrollTo(0, 0);
    }

    private void closeSettings() {
        settingsOpen = false;
        render();
        scroller.scrollTo(0, 0);
    }

    @Override public void onBackPressed() {
        if (settingsOpen) closeSettings();
        else if (dailyStormId != null) closeDailySnowfall();
        else if (feedbackOpen) closeFeedback();
        else if (tab == CALENDAR_TAB && calendarOrigin != null) returnFromCalendar();
        else super.onBackPressed();
    }

    private void renderSettings() {
        LinearLayout heading = row();
        heading.addView(action("‹ Back", false, this::closeSettings));
        TextView title = label("Settings", 25, ink, true);
        title.setPadding(dp(14), 0, 0, 0);
        heading.addView(title);
        add(root, heading, 0);
        add(root, label("SnowOrNo · v" + versionName(), 14, muted, false), 5);

        LinearLayout preferences = card();
        add(preferences, label("Temperature", 17, ink, true), 0);
        LinearLayout temperatures = row();
        temperatures.addView(action("°F", !units.celsius(), () ->
                saveSettings(new DisplayUnits(false, units.centimeters()), lightMode)));
        temperatures.addView(action("°C", units.celsius(), () ->
                saveSettings(new DisplayUnits(true, units.centimeters()), lightMode)));
        add(preferences, temperatures, 5);
        add(preferences, label("Precipitation and snow depth", 17, ink, true), 15);
        LinearLayout snowfall = row();
        snowfall.addView(action("Inches", !units.centimeters(), () ->
                saveSettings(new DisplayUnits(units.celsius(), false), lightMode)));
        snowfall.addView(action("Centimeters", units.centimeters(), () ->
                saveSettings(new DisplayUnits(units.celsius(), true), lightMode)));
        add(preferences, snowfall, 5);
        add(preferences, label("Wind displays in mph with inches and km/h with centimeters.", 13, muted, false), 6);

        android.widget.Switch appearance = new android.widget.Switch(dialogContext());
        appearance.setText("Light mode");
        appearance.setTextColor(ink);
        appearance.setMinHeight(dp(48));
        appearance.setChecked(lightMode);
        appearance.setOnCheckedChangeListener((button, checked) -> saveSettings(units, checked));
        add(preferences, appearance, 15);
        add(root, preferences, 15);

        LinearLayout snowAtLocation = card();
        add(snowAtLocation, label("Snow at Location", 19, ink, true), 0);
        add(snowAtLocation, action("Surface · " + surface.label, false, this::chooseSurface), 8);
        add(snowAtLocation, action("Sun exposure · " + (shade ? "Mostly shaded" : "Open to sun"),
                false, this::chooseExposure), 6);
        add(root, snowAtLocation, 12);

        LinearLayout legend = card();
        add(legend, label("Calendar weather icons", 19, ink, true), 0);
        for (Forecast.Weather weather : Forecast.Weather.values()) {
            LinearLayout item = row();
            item.addView(new WeatherIcon(weather), new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView meaning = label(weather.label, 14, ink, false);
            meaning.setPadding(dp(10), 0, 0, 0);
            item.addView(meaning, new LinearLayout.LayoutParams(0, -2, 1));
            add(legend, item, 5);
        }
        add(legend, label("Icing possible means the models show rain while the air is at or below freezing.",
                13, muted, false), 7);
        add(root, legend, 12);

        LinearLayout history = card();
        add(history, label("History backup", 19, ink, true), 0);
        add(history, label("Includes downloaded weather history, saved storm forecasts, and snowfall measurements.",
                13, muted, false), 5);
        TextView export = action("Export history", false, () -> chooseHistoryFile(EXPORT_HISTORY));
        TextView importFile = action("Import history", false, () -> chooseHistoryFile(IMPORT_HISTORY));
        export.setEnabled(!historyTransfer);
        importFile.setEnabled(!historyTransfer);
        add(history, export, 8);
        add(history, importFile, 6);
        if (historyTransfer) add(history, label("Transferring history…", 13, muted, false), 6);
        add(root, history, 12);
        add(root, action("About & support", false, this::showAbout), 12);
        add(root, action("Privacy policy", false, () -> openLink(PRIVACY_POLICY_URL)), 6);

        root.addView(new android.widget.Space(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout logos = row();
        ImageView snoworno = brandImage(R.drawable.snoworno_logo, "SnowOrNo");
        ImageView mooseai = brandImage(R.drawable.mooseai_logo, "MooseAI, LLC");
        LinearLayout.LayoutParams appLogo = new LinearLayout.LayoutParams(0, dp(104), 1);
        appLogo.setMarginEnd(dp(8));
        LinearLayout.LayoutParams companyLogo = new LinearLayout.LayoutParams(0, dp(104), 1);
        companyLogo.setMarginStart(dp(8));
        logos.addView(snoworno, appLogo);
        logos.addView(mooseai, companyLogo);
        add(root, logos, 16);
    }

    private ImageView brandImage(int resource, String description) {
        ImageView image = new ImageView(this);
        image.setImageResource(resource);
        image.setContentDescription(description);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return image;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "unknown";
        }
    }

    private void chooseHistoryFile(int operation) {
        boolean exporting = operation == EXPORT_HISTORY;
        Intent intent = new Intent(exporting ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(exporting ? "application/gzip" : "*/*");
        if (exporting) intent.putExtra(Intent.EXTRA_TITLE, "SnowOrNo-history.gz");
        try {
            startActivityForResult(intent, operation);
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "File picker unavailable", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int operation, int result, Intent data) {
        super.onActivityResult(operation, result, data);
        if ((operation != IMPORT_HISTORY && operation != EXPORT_HISTORY) || result != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null || historyTransfer) return;
        boolean exporting = operation == EXPORT_HISTORY;
        final String storms;
        try {
            storms = exporting ? stormFeedback.serialize() : null;
        } catch (JSONException error) {
            Toast.makeText(this, "Couldn’t prepare storm history for export", Toast.LENGTH_LONG).show();
            return;
        }
        historyTransfer = true;
        renderAtScroll(scroller.getScrollY());
        historyWorker.submit(() -> {
            try {
                StormFeedback imported = null;
                if (exporting) {
                    try (var output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new java.io.IOException("Unable to open backup destination");
                        weatherHistory.exportTo(output, storms);
                    }
                } else {
                    try (var input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new java.io.IOException("Unable to open backup file");
                        imported = weatherHistory.importFrom(input);
                    }
                }
                StormFeedback restored = imported;
                runOnUiThread(() -> finishHistoryTransfer(restored, exporting ? "History exported" : "History imported"));
            } catch (Exception error) {
                android.util.Log.w("SnowOrNo", "History transfer failed", error);
                runOnUiThread(() -> finishHistoryTransfer(null,
                        exporting ? "Couldn’t export history" : "Couldn’t import this history backup"));
            }
        });
    }

    private void finishHistoryTransfer(StormFeedback imported, String notice) {
        if (imported != null) {
            if (isDestroyed()) stormFeedback = StormFeedback.read(getPreferences(MODE_PRIVATE).getString("storm_feedback", "[]"));
            stormFeedback.merge(imported);
            saveFeedback();
            if (historyRequest != null) historyRequest.cancel(true);
            historyMonth = null;
            historicalWeather = null;
        }
        historyTransfer = false;
        if (isDestroyed()) return;
        Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
        renderAtScroll(scroller.getScrollY());
    }

    private void showAbout() {
        LinearLayout details = column();
        details.setPadding(dp(24), dp(8), dp(24), 0);
        add(details, label("Version " + versionName(), 14, muted, false), 0);
        add(details, label("Created by MooseAI, LLC", 16, ink, true), 6);
        add(details, action("mooseaillc.com", false, () -> openLink(COMPANY_SITE)), 12);
        add(details, action(SUPPORT_EMAIL, false, () -> openLink("mailto:" + SUPPORT_EMAIL)), 7);
        dialogBuilder().setTitle("SnowOrNo").setView(details).setPositiveButton("Done", null).show();
    }

    private void saveSettings(DisplayUnits value, boolean light) {
        units = value;
        lightMode = light;
        getPreferences(MODE_PRIVATE).edit().putBoolean("celsius", value.celsius())
                .putBoolean("centimeters", value.centimeters()).putBoolean("light_mode", light).apply();
        applyPalette();
        render();
    }

    private void selectSite(Forecast.Site value) {
        Forecast.Site saved = favorites.find(value);
        site = saved == null ? value : saved;
        putSelectedSite(getPreferences(MODE_PRIVATE).edit()).apply();
        setForecast(null);
        calendarDate = null;
        historyMonth = null;
        if (historyRequest != null) historyRequest.cancel(true);
        expandedStorms.clear();
        collapsedStorms.clear();
        collapsedHistory.clear();
        if (calendarOrigin != null) calendarOrigin = new CalendarOrigin(calendarOrigin.tab(), 0);
        loadForecast();
    }

    private SharedPreferences.Editor putSelectedSite(SharedPreferences.Editor editor) {
        return editor.putString("name", site.name()).putString("region", site.region())
                .putString("lat", Double.toString(site.latitude())).putString("lon", Double.toString(site.longitude()))
                .putInt("feet", site.feet());
    }

    private void loadForecast() {
        lastAttempt = SystemClock.elapsedRealtime();
        loading = true;
        showingCache = forecast != null;
        if (activeRequest != null) activeRequest.cancel(true);
        int id = ++request;
        Forecast.Site target = site;
        render();
        activeRequest = worker.submit(() -> {
            ForecastSource.Download saved = forecastCache.read(target, Instant.now());
            runOnUiThread(() -> {
                if (isDestroyed() || request != id) return;
                if (saved == null && forecast == null) return;
                setForecast(saved);
                showingCache = saved != null;
                render();
            });

            ForecastSource.Download result = null;
            try {
                if (Thread.currentThread().isInterrupted()) return;
                result = ForecastSource.live(target);
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    forecastCache.write(target, result);
                } catch (Exception error) {
                    android.util.Log.w("SnowOrNo", "Forecast cache write failed", error);
                }
            } catch (Exception error) {
                android.util.Log.w("SnowOrNo", "Ensemble forecast unavailable", error);
            }
            ForecastSource.Download completed = result;
            SnowTimeline publishedTimeline = completed == null ? null : new SnowTimeline(completed.forecast());
            runOnUiThread(() -> {
                if (isDestroyed() || request != id) return;
                if (completed != null) {
                    setForecast(completed);
                    timeline = publishedTimeline;
                    if (stormFeedback.archive(target, forecast, completed.retrievedAt(), timeline)) saveFeedback();
                    showingCache = false;
                }
                loading = false;
                render();
            });
        });
    }

    private void setForecast(ForecastSource.Download entry) {
        forecast = entry == null ? null : entry.forecast();
        retrievedAt = entry == null ? null : entry.retrievedAt();
        timeline = null;
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        locationWorker.shutdownNow();
        if (historyRequest != null) historyRequest.cancel(true);
        historyWorker.execute(weatherHistory::close);
        historyWorker.shutdown();
        super.onDestroy();
    }

    private void render() {
        root.removeAllViews();
        scroller.setBackgroundColor(bg);
        returnToTop.setTextColor(accent);
        returnToTop.setBackground(shape(selectedFill, accent, 26));
        updateReturnToTop();
        if (settingsOpen) {
            renderSettings();
            return;
        }
        if (feedbackOpen) {
            if (dailyStormId != null) {
                for (StormFeedback.Entry entry : stormFeedback.location(site)) {
                    if (entry.id().equals(dailyStormId)) {
                        renderDailySnowfall(entry);
                        return;
                    }
                }
                dailyStormId = null;
            }
            renderFeedback();
            updateReturnToTop();
            return;
        }
        LinearLayout header = row();
        ImageView appLogo = brandImage(R.mipmap.app_icon, null);
        appLogo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        logoParams.setMarginEnd(dp(8));
        header.addView(appLogo, logoParams);
        TextView wordmark = label("SnowOrNo", 25, ink, true);
        header.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        View refresh = iconButton(false, loading ? "Refreshing forecast" : "Refresh forecast", this::loadForecast);
        refresh.setEnabled(!loading);
        refresh.setAlpha(loading ? .45f : 1f);
        header.addView(refresh);
        LinearLayout.LayoutParams gear = new LinearLayout.LayoutParams(dp(48), dp(48));
        gear.leftMargin = dp(8);
        header.addView(iconButton(true, "Settings", this::openSettings), gear);
        add(root, header, 0);

        LinearLayout place = card();
        LinearLayout placeRow = row();
        LinearLayout placeText = column();
        add(placeText, label(site.name(), 26, ink, true), 0);
        add(placeText, label(site.region() + "  ·  " + String.format(Locale.US, "%,d ft", site.feet()), 14, muted, false), 4);
        placeRow.addView(placeText, new LinearLayout.LayoutParams(0, -2, 1));
        placeRow.addView(action("Change", false, this::chooseLocation));
        add(place, placeRow, 0);
        add(place, label(String.format(Locale.US, "%.4f°, %.4f°  ·  Point forecast", site.latitude(),
                site.longitude()), 13, muted, false), 10);
        if (forecast != null) addLocationTemperatures(place);
        LinearLayout savedLocations = row();
        Forecast.Site saved = favorites.find(site);
        TextView save = action(saved == null ? "☆ Save favorite" : "★ Saved", saved != null, () -> {
            if (saved != null) {
                showFavoriteActions(saved, () -> {});
            } else {
                favorites.add(site);
                saveFavorites();
                render();
            }
        });
        save.setContentDescription(saved == null ? "Save this location as a favorite" : "Manage this favorite");
        savedLocations.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams listButton = new LinearLayout.LayoutParams(0, -2, 1);
        listButton.leftMargin = dp(8);
        savedLocations.addView(action("Favorites", false, this::showFavorites), listButton);
        add(place, savedLocations, 10);
        add(root, place, 16);

        LinearLayout tabs = row();
        String[] names = {"Location", "Storm", "Calendar"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) params.leftMargin = dp(7);
            tabs.addView(action(names[i], tab == i, () -> selectTab(index)), params);
        }
        add(root, tabs, 14);
        body = column();
        add(root, body, 15);
        List<StormFeedback.Entry> savedStorms = tab == CALENDAR_TAB ? stormFeedback.location(site) : Collections.emptyList();
        LocalDate today = tab == CALENDAR_TAB ? calendarToday(savedStorms) : null;
        if (tab == CALENDAR_TAB) renderCalendarShortcuts(today);

        if (forecast == null) {
            LinearLayout status = card();
            add(status, label(loading ? "Loading ensemble forecasts" : "Live forecast unavailable", 21, ink, true), 0);
            add(status, label(loading ? "Requesting NOAA, ECMWF, and Canadian guidance."
                    : "No recent forecast is saved for this location. Check your connection and tap the refresh icon.", 14, muted, false), 8);
            add(body, status, tab == CALENDAR_TAB ? 10 : 0);
            if (tab == CALENDAR_TAB) renderCalendar(savedStorms, today);
            add(body, action("Storm history", false, this::openFeedback), 12);
            return;
        }

        String sourceStatus = showingCache ? (loading ? "CACHED FORECAST · REFRESHING" : "CACHED FORECAST · LIVE UPDATE UNAVAILABLE")
                : "LIVE MODEL GUIDANCE";
        LinearLayout source = column();
        add(source, label(sourceStatus, 12, showingCache ? warn : accent, true), 0);
        add(source, label("Retrieved " + RETRIEVED.format(retrievedAt.atZone(ZoneId.systemDefault())), 12, muted, false), 3);
        add(source, openMeteoCredit("Weather data by Open-Meteo"), 3);
        if (tab == 1) {
            LinearLayout toolbar = row();
            toolbar.addView(source, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams history = new LinearLayout.LayoutParams(-2, -2);
            history.setMarginStart(dp(8));
            toolbar.addView(action("Storm history", false, this::openFeedback), history);
            add(body, toolbar, 0);
        } else {
            add(body, source, tab == CALENDAR_TAB ? 10 : 0);
        }
        if (tab == 0) renderLocation();
        if (tab == 1) renderStorm();
        if (tab == CALENDAR_TAB) renderCalendar(savedStorms, today);

        TextView notes = action("Forecast methods & sources", false, this::showMethods);
        add(body, notes, 18);
    }

    private void renderLocation() {
        PropertySnow.Result result = PropertySnow.project(forecast, surface, shade);
        LinearLayout totals = card();
        add(totals, label("Next 72 hours", 15, accent, true), 0);
        if (result.available()) {
            add(totals, label(range(result.peak().lower(), result.peak().upper()), 40, ink, true), 3);
            add(totals, label("Peak depth on " + surface.label.toLowerCase(Locale.US)
                    + "  ·  Median " + amount(result.peak().median()) + " " + units.depthUnit(), 14, muted, false), 3);
            add(totals, label(percentage(result.oneInchShare()) + " of model guidance reaches " + (units.centimeters() ? "2.54 cm" : "1 in"), 14, accent, true), 12);
            add(totals, label("New snowfall  " + propertyDepth(result.fallen()), 14, ink, false), 9);
            add(totals, label("Depth after 72 hours  " + propertyDepth(result.remaining()), 14, ink, false), 5);
            addWeather(totals, forecast.conditions(forecast.firstFutureHour, forecast.firstFutureHour + 72));
            add(totals, label(result.modelCount() + (result.modelCount() == 1 ? " model" : " models")
                    + "  ·  " + result.memberCount() + " members  ·  Experimental estimate", 12, muted, false), 9);
        } else {
            add(totals, label("Complete 72-hour guidance unavailable", 20, ink, true), 8);
        }
        add(body, totals, 11);

        LinearLayout timeline = card();
        add(timeline, label("Snow depth at midnight", 21, ink, true), 0);
        add(timeline, label("How much snow may be on your " + surface.label.toLowerCase(Locale.US)
                + " at the end of each date, after new snow, melting, and settling. The forecast starts with a bare surface.", 14, muted, false), 5);
        add(timeline, label("Local time  ·  Median depth (middle 80% of model estimates)", 12, accent, false), 8);
        for (int day = 0; day < result.days().length; day++) {
            PropertySnow.Day point = result.days()[day];
            LinearLayout entry = row();
            int targetDay = day;
            String date = dates(day, day + 1);
            TextView openDate = action(date, false, () -> openCalendar(targetDay));
            openDate.setTextColor(accent);
            openDate.setContentDescription("Open " + date + " in Calendar");
            entry.addView(openDate);
            entry.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
            entry.addView(label(point == null ? "Unavailable" : propertyDepth(point.remaining()), 14, muted, false));
            add(timeline, entry, 6);
        }
        add(body, timeline, 11);
        add(body, action("Storm history", false, this::openFeedback), 12);
        add(body, action(showMeltDetails ? "Hide fresh snow details" : "Fresh snow & melt details", false, () -> {
            showMeltDetails = !showMeltDetails;
            render();
        }), 12);
        if (showMeltDetails) renderSnowpack();
    }

    private void addLocationTemperatures(LinearLayout place) {
        OffsetDateTime now = Instant.now().atOffset(forecast.offset);
        int today = (int)ChronoUnit.DAYS.between(forecast.start, now.toLocalDate());
        int hour = today * 24 + now.getHour();
        double current = hour >= 0 && hour < Forecast.DAYS * 24
                ? forecast.conditions(hour, hour + 1).low() : Double.NaN;
        String reading = Double.isFinite(current)
                ? units.temperatures(current, current) + " (model estimate)" : "Unavailable";
        add(place, label("Current temperature  ·  " + reading, 14, accent, true), 8);
        addDailyTemperature(place, "Today", today);
        addDailyTemperature(place, "Tomorrow", today + 1);
    }

    private void addDailyTemperature(LinearLayout section, String period, int day) {
        String range = "Unavailable";
        if (day >= 0 && day < Forecast.DAYS) {
            Forecast.Conditions conditions = forecast.conditions(day * 24, (day + 1) * 24);
            if (Double.isFinite(conditions.low()) && Double.isFinite(conditions.high())) {
                range = units.temperatures(conditions.low(), conditions.high());
            }
        }
        add(section, label(period + " low / high  ·  " + range, 14, ink, false), 7);
    }

    private String propertyDepth(PropertySnow.Depth depth) {
        return amount(depth.median()) + " " + units.depthUnit() + "  (" + amount(depth.lower()) + "–" + amount(depth.upper()) + ")";
    }

    private void chooseSurface() {
        dialogBuilder().setTitle("Surface at this location")
                .setSingleChoiceItems(new String[]{"Lawn", "Driveway"}, surface.ordinal(), (dialog, choice) -> {
                    surface = PropertySnow.Surface.values()[choice];
                    getPreferences(MODE_PRIVATE).edit()
                            .putBoolean("driveway", surface == PropertySnow.Surface.PAVEMENT).apply();
                    dialog.dismiss();
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseExposure() {
        dialogBuilder().setTitle("Sun exposure")
                .setSingleChoiceItems(new String[]{"Open to sun", "Mostly shaded"}, shade ? 1 : 0, (dialog, choice) -> {
                    shade = choice == 1;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("shade", shade).apply();
                    dialog.dismiss();
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void renderStorm() {
        if (timeline == null) timeline = new SnowTimeline(forecast);

        LinearLayout overview = card();
        add(overview, label("Storm outlook", 18, ink, true), 0);
        add(overview, label(dates(0, Forecast.DAYS) + "  ·  Local time", 14, muted, false), 4);
        add(overview, confidenceButton("Days 15–30 · Very low confidence",
                Forecast.EXTENDED_CONFIDENCE), 7);
        add(overview, label("Forecast model families have equal weight. Any local snowfall adjustment is shown as a trial preview.", 13, muted, false), 5);
        if (timeline.hasCoverageGaps()) {
            add(overview, label("Model coverage is incomplete for this forecast window.", 13, warn, true), 7);
        }
        if (timeline.stormEvents.isEmpty()) {
            add(overview, label(timeline.availableHours() == 0 ? "Storm guidance unavailable"
                    : "No storms meet the snowfall threshold in available guidance.", 15, ink, true), 8);
        }
        SnowTimeline.Event active = currentStorm(Instant.now());
        List<SnowTimeline.Event> storms = new ArrayList<>(timeline.stormEvents);
        if (active != null) {
            storms.remove(active);
            storms.add(0, active);
        }
        for (SnowTimeline.Event event : storms) {
            boolean current = event.equals(active);
            LinearLayout container = stormCard(current);
            LinearLayout storm = column();
            boolean extended = event.extended();
            boolean inProgress = event.first() < timeline.firstHour;
            int first = event.first(), end = event.end();
            if (extended) {
                first = Math.floorDiv(first, 6) * 6;
                end = (end + 5) / 6 * 6;
            }
            SnowTimeline.Stats stats = timeline.stormStats(event);
            Instant eventStart = forecastInstant(event.first());
            String title = current ? "Current storm" : extended ? "Extended storm signal" : "Storm window";
            String dates = (extended ? "Approx. " : "") + eventTime(first) + " – " + eventTime(end);
            addStormToggle(container, storm, title, dates,
                    expectedSnow(stats.amount(.10), stats.amount(.50), stats.amount(.90)), current,
                    collapsedStorms, eventStart);

            add(storm, label((extended ? "Approx. start  " : inProgress ? "Started  " : "Starts  ") + eventTime(first), 15, ink, false), 0);
            add(storm, label((extended ? "Approx. end  " : "Ends  ") + eventTime(end), 15, ink, false), 3);
            showEventBoundary(storm, event);
            int futureStart = Math.max(event.first(), timeline.firstHour);
            addWeather(storm, forecast.conditions(futureStart, event.end()));

            addDistribution(storm, event.mayStartEarlier() || event.mayContinue()
                    ? "Snowfall in the available window" : "Total new snowfall", stats);
            if (stats.available()) {
                addCumulativeSnowChart(storm, timeline.cumulative(event), first, end,
                        inProgress ? timeline.firstHour : null);
            }
            if (inProgress) addStormProgress(storm, event);
            if (stats.available()) {
                add(storm, label("Model guidance", 14, ink, true), 11);
                for (SnowTimeline.ModelDetail model : stats.models) {
                    add(storm, label(model.name() + "  ·  " + model.members() + " members  ·  "
                            + amount(model.median()) + " " + units.depthUnit() + " median", 13, muted, false), 5);
                }
                LinearLayout breakdown = column();
                addHistogram(breakdown, stats.buckets());
                boolean expanded = expandedStorms.contains(eventStart);
                breakdown.setVisibility(expanded ? View.VISIBLE : View.GONE);
                TextView show = action(expanded ? "Hide snowfall distribution" : "Show snowfall distribution", false, () -> {});
                show.setOnClickListener(view -> {
                    boolean open = breakdown.getVisibility() == View.VISIBLE;
                    if (open) expandedStorms.remove(eventStart);
                    else expandedStorms.add(eventStart);
                    breakdown.setVisibility(open ? View.GONE : View.VISIBLE);
                    show.setText(open ? "Show snowfall distribution" : "Hide snowfall distribution");
                });
                add(storm, show, 9);
                add(storm, breakdown, 0);
                addStormFeedback(storm, event, stats);
            }
            add(storm, action(event.first() < 0 ? "Open today in Calendar" : "Open first day in Calendar",
                    false, () -> openCalendar(Math.max(0, event.first() / 24))), 9);
            add(body, container, 12);
        }
        add(body, overview, 12);
    }

    private Instant forecastInstant(int hour) {
        return forecast.start.atStartOfDay().plusHours(hour).toInstant(forecast.offset);
    }

    private SnowTimeline.Event currentStorm(Instant now) {
        if (forecast == null) return null;
        if (timeline == null) timeline = new SnowTimeline(forecast);
        for (SnowTimeline.Event event : timeline.stormEvents) {
            if (!now.isBefore(forecastInstant(event.first())) && now.isBefore(forecastInstant(event.end()))) return event;
        }
        return null;
    }

    private void addStormProgress(LinearLayout card, SnowTimeline.Event event) {
        SnowTimeline.Stats elapsed = timeline.modeledSoFar(event);
        SnowTimeline.Stats remaining = timeline.stats(timeline.firstHour, event.end());
        addStormAmount(card, "Modeled so far", elapsed);
        addStormAmount(card, "Forecast remaining", remaining);

        StormFeedback.Entry entry = stormFeedback.matchingEntry(site, forecastInstant(event.first()), forecastInstant(event.end()));
        StormFeedback.Progress progress = entry == null ? null : entry.progress(Instant.now());
        if (progress == null) {
            add(card, label("Record completed periods in Storm history to compare with a saved forecast.",
                    12, muted, false), 5);
            return;
        }
        var window = entry.measurementWindow();
        String period = MEASUREMENT_TIME.format(window.start().atOffset(window.offset())) + " – "
                + MEASUREMENT_TIME.format(progress.through().atOffset(window.offset()));
        add(card, label("Measured " + amount(progress.measured()) + " " + units.depthUnit() + "  ·  " + period,
                13, ink, true), 6);
        String comparison = "Pre-storm estimate unavailable for that period";
        if (Double.isFinite(progress.forecastMedian())) {
            double error = progress.measured() - progress.forecastMedian();
            comparison = "Saved pre-storm median " + amount(progress.forecastMedian()) + " " + units.depthUnit()
                    + "  ·  Measured minus forecast " + (error > 0 ? "+" : "") + amount(error) + " " + units.depthUnit();
        }
        add(card, label(comparison, 12, muted, false), 4);
    }

    private void addStormAmount(LinearLayout card, String heading, SnowTimeline.Stats stats) {
        String reading = stats.available() ? range(stats.amount(.10), stats.amount(.90)) + "  ·  Median "
                + amount(stats.amount(.50)) + " " + units.depthUnit() : "Unavailable";
        add(card, label(heading + "  ·  " + reading, 13, ink, true), 6);
    }

    private void addStormFeedback(LinearLayout card, SnowTimeline.Event event, SnowTimeline.Stats stats) {
        double factor = stormFeedback.review(site).multiplier();
        if (Double.isFinite(factor) && !event.extended()) {
            add(card, label("Local adjustment trial  ·  " + amount(stats.amount(.50) * factor)
                    + " " + units.depthUnit() + " median", 13, muted, false), 8);
        }
    }

    private void openFeedback() {
        feedbackOpen = true;
        render();
        scroller.scrollTo(0, 0);
    }

    private void closeFeedback() {
        feedbackOpen = false;
        render();
        scroller.scrollTo(0, 0);
    }

    private void renderFeedback() {
        LinearLayout heading = row();
        heading.addView(action("‹ Back", false, this::closeFeedback));
        TextView title = label("Storm history", 21, ink, true);
        title.setPadding(dp(12), 0, 0, 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, heading, 0);
        add(root, label(site.name() + "  ·  " + String.format(Locale.US, "%,d ft", site.feet()),
                15, muted, true), 8);
        add(root, openMeteoCredit("Forecast data by Open-Meteo"), 3);

        StormFeedback.Review review = stormFeedback.review(site);
        LinearLayout tuning = card();
        add(tuning, label("Local forecast tuning", 20, ink, true), 0);
        add(tuning, label(review.measured() + " recorded  ·  " + review.eligible() + " eligible for tuning", 14, muted, false), 5);
        if (Double.isFinite(review.meanError())) {
            add(tuning, label("Average observed minus forecast  ·  " + (review.meanError() > 0 ? "+" : "")
                    + amount(review.meanError()) + " " + units.depthUnit(), 14, ink, false), 6);
        }
        if (Double.isFinite(review.multiplier())) {
            String adjustment = String.format(Locale.US, "%+.0f%%", (review.multiplier() - 1) * 100);
            add(tuning, label("Suggested trial adjustment  " + adjustment, 17, accent, true), 7);
            add(tuning, label("Storm cards preview the adjusted median alongside the original forecast.", 13, muted, false), 4);
        } else {
            add(tuning, label("A trial adjustment appears after " + StormFeedback.MIN_TUNING_STORMS
                    + " eligible measurements. Complete forecasts retrieved up to seven days before the storm with a median of at least "
                    + (units.centimeters() ? "0.635 cm" : "0.25 in") + " qualify.", 13, muted, false), 6);
        }
        add(tuning, label("Storm history and measurements are stored on this device.", 12, muted, false), 7);
        add(root, tuning, 11);

        if (!review.models().isEmpty()) {
            LinearLayout scores = card();
            add(scores, label("Model accuracy at this location", 19, ink, true), 0);
            add(scores, label("Average absolute miss. Each model may cover a different set of measured storm windows.", 13, muted, false), 5);
            for (StormFeedback.ModelScore score : review.models()) {
                add(scores, label(score.name() + "  ·  " + amount(score.meanAbsoluteError()) + " " + units.depthUnit()
                        + "  ·  " + score.count() + (score.count() == 1 ? " window" : " windows"), 14, ink, false), 7);
                add(scores, label("Observed minus forecast " + (score.meanError() > 0 ? "+" : "")
                        + amount(score.meanError()) + " " + units.depthUnit(), 12, muted, false), 2);
            }
            add(root, scores, 11);
        }
        List<StormFeedback.Entry> entries = stormFeedback.location(site);
        if (entries.isEmpty()) {
            add(root, label("Storms are saved automatically when a live forecast loads for this location.",
                    15, muted, false), 15);
        } else {
            Instant now = Instant.now();
            SnowTimeline.Event active = currentStorm(now);
            StormFeedback.Entry activeEntry = active == null ? null
                    : stormFeedback.matchingEntry(site, forecastInstant(active.first()), forecastInstant(active.end()));
            if (forecast == null) {
                activeEntry = entries.stream().filter(entry -> !now.isBefore(entry.latest().start()) && now.isBefore(entry.latest().end()))
                        .max(java.util.Comparator.comparing(entry -> entry.latest().retrieved())).orElse(null);
            }
            List<StormFeedback.Entry> upcoming = new ArrayList<>();
            List<StormFeedback.Entry> past = new ArrayList<>();
            for (StormFeedback.Entry entry : entries) {
                (entry == activeEntry || now.isBefore(entry.measurementWindow().end()) ? upcoming : past).add(entry);
            }
            if (!upcoming.isEmpty()) {
                add(root, label("Upcoming & in progress", 17, ink, true), 15);
                for (int i = upcoming.size() - 1; i >= 0; i--) {
                    StormFeedback.Entry entry = upcoming.get(i);
                    renderFeedbackEntry(entry, entry == activeEntry);
                }
            }
            if (!past.isEmpty()) {
                add(root, label("Past forecast windows", 17, ink, true), 15);
                for (StormFeedback.Entry entry : past) renderFeedbackEntry(entry, false);
            }
        }
    }

    private void renderFeedbackEntry(StormFeedback.Entry entry, boolean current) {
        LinearLayout container = stormCard(current);
        LinearLayout card = column();
        StormFeedback.Snapshot latest = entry.latest();
        addStormToggle(container, card, current ? "Current storm" : "Saved storm",
                feedbackWindow(latest), expectedSnow(latest.low(), latest.median(), latest.high()),
                current, collapsedHistory, entry.id());
        addFeedbackSnapshot(card, "Original forecast", entry.original());
        if (!entry.latest().equals(entry.original())) addFeedbackSnapshot(card, "Latest forecast", entry.latest());

        StormFeedback.Snapshot window = entry.measurementWindow();
        add(card, label("Measurement window", 14, accent, true), 10);
        add(card, label(feedbackWindow(window), 14, ink, false), 4);
        if (entry.verification() != null && !window.equals(entry.original()) && !window.equals(entry.latest())) {
            add(card, label("Scored against " + range(window.low(), window.high()) + "  ·  Median "
                    + amount(window.median()) + " " + units.depthUnit(), 13, muted, false), 4);
        }
        if (entry.measured() != null) {
            double error = entry.measured() - window.median();
            String difference = Double.isFinite(error) ? "  ·  Observed minus forecast "
                    + (error > 0 ? "+" : "") + amount(error) + " " + units.depthUnit() : "";
            add(card, label("Measured " + amount(entry.measured()) + " " + units.depthUnit() + difference,
                    14, ink, true), 8);
            if (entry.verification() != null) {
                for (StormFeedback.ModelEstimate model : window.estimates()) {
                    double miss = Math.abs(entry.measured() - model.median());
                    add(card, label(model.name() + "  ·  Median " + amount(model.median()) + " " + units.depthUnit()
                            + "  ·  Miss " + amount(miss) + " " + units.depthUnit(), 12, muted, false), 3);
                }
            } else {
                add(card, label("Saved as an observation. Accuracy scores use complete forecasts issued before the storm.",
                        13, muted, false), 4);
            }
        }
        if (entry.wholeStorm() == null) {
            int recorded = entry.daily().amounts().size();
            if (recorded > 0) {
                add(card, label(recorded + " of " + entry.dailyPeriods().size() + " periods recorded  ·  "
                        + amount(entry.dailyTotal()) + " " + units.depthUnit() + " logged", 13, muted, false), 6);
            }
            add(card, action("Daily snowfall", false, () -> {
                dailyStormId = entry.id();
                render();
                scroller.scrollTo(0, 0);
            }), 9);
        }
        if (!Instant.now().isBefore(window.end()) && entry.daily().amounts().isEmpty()) {
            LinearLayout controls = row();
            controls.addView(action(entry.wholeStorm() == null ? "Record storm total" : "Edit storm total",
                    false, () -> enterObservation(entry, null)), new LinearLayout.LayoutParams(0, -2, 1));
            if (entry.wholeStorm() != null) {
                LinearLayout.LayoutParams clear = new LinearLayout.LayoutParams(0, -2, 1);
                clear.leftMargin = dp(8);
                controls.addView(action("Clear measurement", false, () -> confirmClearObservation(entry, null)), clear);
            }
            add(card, controls, 9);
        }
        add(root, container, 11);
    }

    private void addFeedbackSnapshot(LinearLayout card, String title, StormFeedback.Snapshot snapshot) {
        add(card, label(title, 15, accent, true), 5);
        add(card, label(feedbackWindow(snapshot), 14, ink, false), 3);
        add(card, label(Double.isFinite(snapshot.median()) ? range(snapshot.low(), snapshot.high()) + "  ·  Median "
                + amount(snapshot.median()) + " " + units.depthUnit() : "Complete snowfall guidance unavailable",
                14, ink, false), 3);
        add(card, label((snapshot.models().isEmpty() ? "" : snapshot.models() + "  ·  ") + "Retrieved "
                + OBSERVATION_TIME.format(snapshot.retrieved().atOffset(snapshot.offset())), 12, muted, false), 3);
        if (!snapshot.complete()) add(card, label("Partial storm window", 12, warn, false), 3);
    }

    private String feedbackWindow(StormFeedback.Snapshot snapshot) {
        return OBSERVATION_TIME.format(snapshot.start().atOffset(snapshot.offset())) + " → "
                + OBSERVATION_TIME.format(snapshot.end().atOffset(snapshot.offset()));
    }

    private void closeDailySnowfall() {
        dailyStormId = null;
        render();
        scroller.scrollTo(0, 0);
    }

    private void renderDailySnowfall(StormFeedback.Entry entry) {
        LinearLayout heading = row();
        heading.addView(action("‹ Back", false, this::closeDailySnowfall));
        TextView title = label("Daily snowfall", 21, ink, true);
        title.setPadding(dp(12), 0, 0, 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, heading, 0);

        var periods = entry.dailyPeriods();
        var amounts = entry.daily().amounts();
        LinearLayout summary = card();
        add(summary, label(site.name(), 18, ink, true), 0);
        add(summary, openMeteoCredit("Forecast data by Open-Meteo"), 3);
        add(summary, label(feedbackWindow(entry.measurementWindow()), 14, muted, false), 4);
        add(summary, label("Set the cutoff to when you usually clear snow. Measure before clearing and enter the new snow for the period that ended. If you cleared more than once, add those measurements together.",
                14, ink, false), 9);
        int cutoff = entry.daily().cutoffMinutes();
        String cutoffTime = CUTOFF_TIME.format(java.time.LocalTime.of(cutoff / 60, cutoff % 60));
        if (amounts.isEmpty()) {
            add(summary, action("Daily cutoff · " + cutoffTime, false, () -> chooseDailyCutoff(entry)), 9);
        } else {
            add(summary, label("Daily cutoff  ·  " + cutoffTime + " local time", 14, muted, false), 7);
        }
        add(summary, label(amounts.size() + " of " + periods.size() + " periods recorded  ·  "
                + amount(entry.dailyTotal()) + " " + units.depthUnit() + " logged", 14, accent, true), 8);
        if (entry.measured() == null) {
            add(summary, label("Enter every period, including 0 for a dry period, to score the storm.", 13, muted, false), 4);
        }
        add(root, summary, 12);

        Instant now = Instant.now();
        for (StormFeedback.DailyPeriod period : periods) {
            LinearLayout day = card();
            add(day, label(dailyWindow(entry, period), 15, accent, true), 0);
            Double measurement = amounts.get(period.start());
            if (measurement != null) {
                add(day, label("New snow  ·  " + amount(measurement) + " " + units.depthUnit(), 14, ink, true), 4);
            }
            if (!now.isBefore(period.end())) {
                LinearLayout controls = row();
                controls.addView(action(measurement == null ? "Record snowfall" : "Edit snowfall", false,
                        () -> enterObservation(entry, period)), new LinearLayout.LayoutParams(0, -2, 1));
                if (measurement != null) {
                    LinearLayout.LayoutParams clear = new LinearLayout.LayoutParams(0, -2, 1);
                    clear.leftMargin = dp(8);
                    controls.addView(action("Clear", false, () -> confirmClearObservation(entry, period)), clear);
                }
                add(day, controls, 8);
            } else {
                add(day, label("Record after this period ends", 13, muted, false), 5);
            }
            add(root, day, 10);
        }
    }

    private void chooseDailyCutoff(StormFeedback.Entry entry) {
        int minutes = entry.daily().cutoffMinutes();
        new android.app.TimePickerDialog(dialogContext(), (view, hour, minute) -> {
            if (stormFeedback.dailyCutoff(entry.id(), hour * 60 + minute)) saveFeedback();
            render();
        }, minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private String dailyWindow(StormFeedback.Entry entry, StormFeedback.DailyPeriod period) {
        var offset = entry.measurementWindow().offset();
        return MEASUREMENT_TIME.format(period.start().atOffset(offset)) + " → "
                + MEASUREMENT_TIME.format(period.end().atOffset(offset));
    }

    private void enterObservation(StormFeedback.Entry entry, StormFeedback.DailyPeriod period) {
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), 5);
        add(form, label(period == null ? feedbackWindow(entry.measurementWindow()) : dailyWindow(entry, period),
                14, ink, true), 4);
        add(form, label("Enter new snow measured during this window. Use 0 if no snow fell.",
                14, muted, false), 5);
        Double previous = period == null ? entry.wholeStorm() : entry.daily().amounts().get(period.start());
        EditText input = input(form, "New snowfall (" + units.depthUnit() + ")",
                previous == null ? "" : liquid(previous),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        AlertDialog dialog = dialogBuilder().setTitle("Measured snowfall").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                double measurement = Double.parseDouble(input.getText().toString().trim().replace(',', '.'));
                double inches = units.centimeters() ? measurement / 2.54 : measurement;
                if (!saveObservation(entry, period, inches)) throw new NumberFormatException();
                dialog.dismiss();
                render();
            } catch (NumberFormatException error) {
                input.setError("Enter a snowfall amount of zero or more.");
            }
        }));
        dialog.show();
    }

    private boolean saveObservation(StormFeedback.Entry entry, StormFeedback.DailyPeriod period, Double inches) {
        Instant now = Instant.now();
        boolean saved = period == null ? stormFeedback.observe(entry.id(), inches, now)
                : stormFeedback.observeDay(entry.id(), period, inches, now);
        if (saved) saveFeedback();
        return saved;
    }

    private void confirmClearObservation(StormFeedback.Entry entry, StormFeedback.DailyPeriod period) {
        dialogBuilder().setTitle("Clear measured snowfall?")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (dialog, which) -> {
                    saveObservation(entry, period, null);
                    render();
                }).show();
    }

    private void saveFeedback() {
        try {
            getPreferences(MODE_PRIVATE).edit().putString("storm_feedback", stormFeedback.serialize()).apply();
        } catch (JSONException error) {
            Toast.makeText(this, "Couldn’t save forecast feedback.", Toast.LENGTH_SHORT).show();
        }
    }

    private void selectTab(int index) {
        if (index == tab) return;
        if (index == CALENDAR_TAB) {
            openCalendar(calendarDate);
        } else if (calendarOrigin != null && index == calendarOrigin.tab()) {
            returnFromCalendar();
        } else {
            calendarOrigin = null;
            tab = index;
            renderAtScroll(0);
        }
    }

    private void openCalendar(int day) {
        openCalendar(forecast.start.plusDays(day));
    }

    private void openCalendar(LocalDate date) {
        calendarOrigin = new CalendarOrigin(tab, scroller.getScrollY());
        tab = CALENDAR_TAB;
        calendarDate = date;
        renderAtScroll(0);
    }

    private void returnFromCalendar() {
        CalendarOrigin origin = calendarOrigin;
        calendarOrigin = null;
        tab = origin.tab();
        renderAtScroll(origin.scrollY());
    }

    private LocalDate calendarToday(List<StormFeedback.Entry> savedStorms) {
        if (forecast != null) return LocalDate.now(forecast.offset);
        if (historyMonth != null && historicalWeather != null && !historicalWeather.days().isEmpty()) {
            return LocalDate.now(historicalWeather.zone());
        }
        return savedStorms.stream().max(Comparator.comparing(entry -> entry.latest().retrieved()))
                .map(entry -> LocalDate.now(entry.latest().offset())).orElseGet(LocalDate::now);
    }

    private void renderCalendarShortcuts(LocalDate today) {
        LinearLayout shortcuts = row();
        if (calendarOrigin != null) shortcuts.addView(action("‹ Back", false, this::returnFromCalendar));
        shortcuts.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        shortcuts.addView(action("Go to date", false, () -> chooseCalendarDate(today)));
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-2, -2);
        margin.setMarginStart(dp(6));
        shortcuts.addView(action("Today", false, () -> selectDate(today)), margin);
        add(body, shortcuts, 0);
    }

    private void chooseCalendarDate(LocalDate today) {
        LocalDate first = today.minusYears(HistoricalWeather.YEARS);
        LocalDate last = forecast == null ? today : forecast.start.plusDays(Forecast.DAYS - 1);
        LocalDate date = calendarDate == null || calendarDate.isBefore(first) || calendarDate.isAfter(last) ? today : calendarDate;
        var dialog = new android.app.DatePickerDialog(dialogContext(), (view, year, month, day) ->
                selectDate(LocalDate.of(year, month + 1, day)), date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        ZoneId device = ZoneId.systemDefault();
        dialog.getDatePicker().setMinDate(first.atStartOfDay(device).toInstant().toEpochMilli());
        dialog.getDatePicker().setMaxDate(last.atStartOfDay(device).toInstant().toEpochMilli());
        dialog.show();
    }

    private void loadWeatherMonth(YearMonth month, LocalDate today) {
        var job = new HistoryMonth(WeatherHistoryStore.PlaceKey.from(site), month, today);
        if (job.equals(historyMonth)) return;
        if (historyRequest != null) historyRequest.cancel(true);
        historyMonth = job;
        historicalWeather = null;
        historyFailed = false;
        LocalDate first = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        LocalDate firstAllowed = today.minusYears(HistoricalWeather.YEARS);
        LocalDate from = first.isBefore(firstAllowed) ? firstAllowed : first;
        LocalDate last = end.isAfter(today) ? today.minusDays(1) : end.minusDays(1);
        historyLoading = !last.isBefore(from);
        if (!historyLoading) return;

        Forecast.Site target = site;
        ZoneId fallback = forecast == null ? ZoneOffset.UTC : forecast.offset;
        historyRequest = historyWorker.submit(() -> {
            WeatherHistoryStore.Month cached = null;
            boolean failed = false;
            try {
                cached = weatherHistory.read(target, first, end, fallback);
                var initial = cached;
                runOnUiThread(() -> showWeatherMonth(job, initial, true, false));
                LocalDate recentStart = HistoricalWeather.recentStart(today);
                LocalDate archiveEnd = last.isBefore(recentStart) ? last : recentStart.minusDays(1);
                LocalDate recentFrom = from.isBefore(recentStart) ? recentStart : from;
                failed = !fetchMissingWeather(target, cached, from, archiveEnd, false);
                if (Thread.currentThread().isInterrupted()) return;
                failed |= !fetchMissingWeather(target, cached, recentFrom, last, true);
                cached = weatherHistory.read(target, first, end, fallback);
            } catch (Exception error) {
                android.util.Log.w("SnowOrNo", "Weather history unavailable", error);
                failed = true;
            }
            if (Thread.currentThread().isInterrupted()) return;
            var downloaded = cached;
            boolean unavailable = failed;
            runOnUiThread(() -> showWeatherMonth(job, downloaded, false, unavailable));
        });
    }

    private boolean fetchMissingWeather(Forecast.Site target, WeatherHistoryStore.Month cached,
                                        LocalDate first, LocalDate last, boolean recent) {
        LocalDate missingStart = null, missingEnd = null;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            var day = cached.days().get(date);
            if (day != null && day.complete() && (recent || !day.recent())) continue;
            if (missingStart == null) missingStart = date;
            missingEnd = date;
        }
        if (missingStart == null) return true;
        try {
            var batch = HistoricalWeather.fetch(target, missingStart, missingEnd, recent);
            if (Thread.currentThread().isInterrupted()) return false;
            weatherHistory.save(target, batch);
            return true;
        } catch (Exception error) {
            android.util.Log.w("SnowOrNo", "Historical model data unavailable", error);
            return false;
        }
    }

    private void showWeatherMonth(HistoryMonth job, WeatherHistoryStore.Month month, boolean loading, boolean failed) {
        if (isDestroyed() || historyMonth != job) return;
        historicalWeather = month;
        historyLoading = loading;
        historyFailed = failed;
        if (tab == CALENDAR_TAB && !settingsOpen && !feedbackOpen) renderAtScroll(scroller.getScrollY());
    }

    private void addWeather(LinearLayout section, Forecast.Conditions conditions) {
        add(section, label("Air " + units.temperatures(conditions.low(), conditions.high()) + "  ·  "
                + windText(conditions), 14, muted, false), 5);
    }

    private String windText(Forecast.Conditions conditions) {
        if (!Double.isFinite(conditions.wind())) return "Wind unavailable";
        String average = units.wind(conditions.wind());
        String peak = units.wind(conditions.peakWind());
        return "Wind " + average + (average.equals(peak) ? "" : " · peak " + peak);
    }

    private void showEventBoundary(LinearLayout card, SnowTimeline.Event event) {
        if (event.mayStartEarlier() || event.mayContinue()) {
            String text = event.mayStartEarlier() && event.mayContinue() ? "Storm could extend beyond both ends of this window."
                    : event.mayStartEarlier() ? "Storm may have started earlier." : "Storm may continue beyond this window.";
            add(card, label(text, 13, warn, false), 5);
        }
    }

    private void addHistogram(LinearLayout distribution, double[] buckets) {
        add(distribution, label("Snowfall distribution", 19, ink, true), 10);
        add(distribution, label("Share of equally weighted model families", 13, muted, false), 4);
        for (int i = 0; i < buckets.length; i++) {
            LinearLayout bucket = row();
            TextView range = label(units.bin(i), 14, ink, false);
            bucket.addView(range, new LinearLayout.LayoutParams(dp(113), -2));
            View track = new ProbabilityBar(buckets[i]);
            bucket.addView(track, new LinearLayout.LayoutParams(0, dp(10), 1));
            TextView percent = label(percentage(buckets[i]), 14, accent, true);
            percent.setGravity(Gravity.END);
            bucket.addView(percent, new LinearLayout.LayoutParams(dp(51), -2));
            add(distribution, bucket, 12);
        }
    }

    private void renderCalendar(List<StormFeedback.Entry> savedStorms, LocalDate today) {
        if (timeline == null && forecast != null) timeline = new SnowTimeline(forecast);
        LocalDate firstForecast = forecast == null ? today.plusDays(1) : forecast.start;
        LocalDate last = forecast == null ? today : firstForecast.plusDays(Forecast.DAYS - 1);
        var history = StormFeedback.calendarDates(savedStorms, firstForecast);
        LocalDate first = today.minusYears(HistoricalWeather.YEARS);
        LocalDate selected = calendarDate == null || calendarDate.isBefore(first) || calendarDate.isAfter(last)
                ? today : calendarDate;
        int calendarDay = (int)ChronoUnit.DAYS.between(firstForecast, selected);

        LinearLayout month = card();
        month.setPadding(dp(8), dp(15), dp(8), dp(15));
        YearMonth current = YearMonth.from(selected);
        loadWeatherMonth(current, today);
        LinearLayout monthHeader = row();
        addMonthButton(monthHeader, "‹", current.minusMonths(1), first, last);
        TextView title = label(current.format(MONTH), 18, ink, true);
        title.setGravity(Gravity.CENTER);
        monthHeader.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        addMonthButton(monthHeader, "›", current.plusMonths(1), first, last);
        add(month, monthHeader, 0);

        LinearLayout weekdays = row();
        for (String day : new String[]{"S", "M", "T", "W", "T", "F", "S"}) {
            TextView name = label(day, 12, muted, true);
            name.setGravity(Gravity.CENTER);
            weekdays.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        }
        add(month, weekdays, 6);
        int offset = current.atDay(1).getDayOfWeek().getValue() % 7;
        int cells = ((offset + current.lengthOfMonth() + 6) / 7) * 7;
        for (int week = 0; week < cells; week += 7) {
            LinearLayout days = row();
            days.setMinimumHeight(dp(88));
            for (int slot = week; slot < week + 7; slot++) {
                int date = slot - offset + 1;
                View cell = new View(this);
                if (date > 0 && date <= current.lengthOfMonth()) {
                    LocalDate target = current.atDay(date);
                    int day = (int)ChronoUnit.DAYS.between(firstForecast, target);
                    boolean liveDay = forecast != null && day >= 0 && day < Forecast.DAYS;
                    boolean archived = !target.isBefore(first) && history.containsKey(target);
                    boolean pastDay = !target.isBefore(first) && target.isBefore(today);
                    if (liveDay || archived || pastDay) {
                        boolean storm = archived || (liveDay && timeline.events.stream().anyMatch(event -> event.overlaps(day * 24, (day + 1) * 24)));
                        Forecast.Summary weather = liveDay ? forecast.summarize(day, day + 1) : null;
                        var past = historicalWeather == null ? null : historicalWeather.days().get(target);
                        cell = calendarDateCell(target, target.equals(selected), storm, archived, weather, past);
                    } else {
                        TextView inactive = label(Integer.toString(date), 13, muted, false);
                        inactive.setGravity(Gravity.CENTER);
                        cell = inactive;
                    }
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
                params.setMargins(dp(1), 0, dp(1), 0);
                days.addView(cell, params);
            }
            add(month, days, 3);
        }
        add(month, label("Low above high (" + (units.celsius() ? "°C" : "°F") + ") · Blue dates mark forecast or archived storms."
                + " Extended outlook begins on day 15.", 12, muted, false), 8);
        add(body, month, 10);

        if (forecast == null || calendarDay < 0) {
            renderArchivedCalendarDay(selected, today, history.getOrDefault(selected, Collections.emptyList()));
            return;
        }
        Forecast.Summary selectedWeather = forecast.summarize(calendarDay, calendarDay + 1);
        if (calendarDay >= SnowTimeline.DAILY_DAYS) {
            int firstOutlookDay = SnowTimeline.DAILY_DAYS + (calendarDay - SnowTimeline.DAILY_DAYS) / 7 * 7;
            LinearLayout extended = card();
            add(extended, label(selected.format(DATE) + " · Extended outlook", 20, ink, true), 0);
            addCalendarWeather(extended, selectedWeather);
            add(extended, label("Extended dates show multi-day guidance. Hourly detail is shown through day 14.", 14, muted, false), 4);
            addOutlookRow(extended, firstOutlookDay, Math.min(firstOutlookDay + 7, Forecast.DAYS), true);
            add(body, extended, 12);
            renderCalendarStorms(calendarDay * 24, (calendarDay + 1) * 24);
            return;
        }

        int dayStart = calendarDay * 24;
        int dayEnd = dayStart + 24;
        LinearLayout dayCard = card();
        add(dayCard, label(selected.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)),
                22, ink, true), 0);
        if (selectedWeather.available()) {
            addCalendarWeather(dayCard, selectedWeather);
            add(dayCard, label("Rain " + liquid(selectedWeather.rain(.5))
                    + " " + units.depthUnit(), 14, muted, false), 5);
            add(dayCard, label(windText(selectedWeather.conditions()), 14, muted, false), 3);
        }
        int availableStart = Math.max(dayStart, timeline.firstHour);
        SnowTimeline.Stats dailySnow = timeline.stats(availableStart, dayEnd);
        addDistribution(dayCard, calendarDay == 0 && timeline.firstHour > 0 ? "Snowfall for the rest of today" : "Day snowfall", dailySnow);
        if (dailySnow.available()) {
            addCumulativeSnowChart(dayCard, timeline.cumulative(availableStart, dayEnd, 1), availableStart, dayEnd, null);
        }
        SnowTimeline.Stats[] rates = new SnowTimeline.Stats[24];
        for (int hour = 0; hour < rates.length; hour++) rates[hour] = timeline.stats(dayStart + hour, dayStart + hour + 1);
        add(dayCard, label("Hourly snowfall", 17, ink, true), 12);
        add(dayCard, new HourChart(rates), 4, dp(120));
        add(dayCard, label("Blue bars show the median. Thin lines show the middle 80%.", 12, muted, false), 3);
        if (dailySnow.available()) addHistogram(dayCard, dailySnow.buckets());
        add(body, dayCard, 12);

        renderCalendarStorms(dayStart, dayEnd);
        renderHours(dayStart, rates);
    }

    private LinearLayout calendarDateCell(LocalDate date, boolean selected, boolean storm, boolean archived,
                                          Forecast.Summary weather, HistoricalWeather.Day past) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(3), dp(6), dp(3), dp(9));
        cell.setBackground(shape(selected ? selectedFill : panel, selected ? accent : line, 8));
        cell.setSelected(selected);
        cell.setFocusable(true);
        cell.setOnClickListener(view -> selectDate(date));
        TextView number = label(Integer.toString(date.getDayOfMonth()), 14,
                storm ? accent : weather == null || weather.first < SnowTimeline.DAILY_DAYS ? ink : muted, true);
        number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(number);

        String description = date.format(DATE) + (storm ? ", storm window" : "");
        boolean forecastAvailable = weather != null && weather.available();
        if (forecastAvailable || past != null) {
            Forecast.Weather condition = forecastAvailable ? weather.weatherIcon() : past.condition();
            if (condition != null) cell.addView(new WeatherIcon(condition), new LinearLayout.LayoutParams(dp(21), dp(21)));
            String unit = units.celsius() ? " Celsius" : " Fahrenheit";
            long low = units.temperature(forecastAvailable ? weather.low() : past.lowF());
            long high = units.temperature(forecastAvailable ? weather.high() : past.highF());
            for (long temperature : new long[]{low, high}) {
                TextView value = label(temperature + "°", 11, ink, false);
                value.setIncludeFontPadding(false);
                value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-2, -2);
                spacing.topMargin = dp(2);
                cell.addView(value, spacing);
            }
            if (condition != null) description += ", " + condition.label;
            description += ", low " + low + unit + ", high " + high + unit;
        } else if (archived) {
            cell.addView(new WeatherIcon(Forecast.Weather.SNOW), new LinearLayout.LayoutParams(dp(21), dp(21)));
            description += ", archived storm forecast";
        } else {
            cell.addView(label("—", 13, muted, false));
            description += ", daily weather unavailable";
        }
        cell.setContentDescription(description);
        return cell;
    }

    private void renderArchivedCalendarDay(LocalDate date, LocalDate today, List<StormFeedback.Entry> storms) {
        LinearLayout card = card();
        add(card, label(date.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.US)), 22, ink, true), 0);
        var history = historicalWeather == null ? null : historicalWeather.days().get(date);
        if (history != null) {
            addHistoricalDay(card, history);
        } else if (date.isBefore(today)) {
            add(card, label(historyLoading ? "Loading historical weather…" : "Historical weather unavailable", 14, muted, false), 6);
        } else {
            add(card, label("Today’s forecast is unavailable", 14, muted, false), 6);
        }
        if (date.isBefore(today) && !historyLoading && (historyFailed || history == null)) {
            add(card, action("Retry weather history", false, () -> {
                historyMonth = null;
                renderAtScroll(scroller.getScrollY());
            }), 8);
        }
        for (StormFeedback.Entry storm : storms) {
            addFeedbackSnapshot(card, "Original storm forecast", storm.original());
            if (!storm.latest().equals(storm.original())) addFeedbackSnapshot(card, "Latest saved forecast", storm.latest());
            Double measured = storm.measured();
            if (measured != null) {
                add(card, label("Measured storm total  ·  " + amount(measured) + " " + units.depthUnit(),
                        14, ink, true), 6);
            } else if (storm.hasObservations()) {
                add(card, label("Daily measurements logged  ·  " + amount(storm.dailyTotal()) + " " + units.depthUnit(),
                        14, ink, true), 6);
            }
        }
        if (!storms.isEmpty()) add(card, action("Storm history", false, this::openFeedback), 10);
        add(body, card, 12);
        if (history != null) renderHistoricalHours(history, historicalWeather.zone());
    }

    private void addHistoricalDay(LinearLayout card, HistoricalWeather.Day day) {
        add(card, openMeteoCredit(day.recent() ? "Recent model estimate · Open-Meteo"
                : "Historical reanalysis · Open-Meteo ERA5 Seamless"), 6);
        String weather = day.condition() == null ? "" : day.condition().label + "  ·  ";
        add(card, label(weather + "Low / high " + units.temperatures(day.lowF(), day.highF()), 14, ink, true), 6);
        String period = day.complete() ? "" : "Recorded hours · ";
        add(card, label(period + "Rain " + historyDepth(day.rainIn()) + "  ·  New snow " + historyDepth(day.snowIn()),
                14, ink, false), 5);
        if (!day.complete()) {
            add(card, label("Partial day · " + day.hours().size() + " of " + day.expectedHours() + " hours available",
                    13, warn, false), 5);
        }
    }

    private String historyDepth(double inches) {
        return Double.isFinite(inches) ? liquid(inches) + " " + units.depthUnit() : "Unavailable";
    }

    private String hourlyHistoricalDepth(int value, double divisor) {
        return value == HistoricalWeather.MISSING ? "—" : liquid(value / divisor);
    }

    private void renderHistoricalHours(HistoricalWeather.Day day, ZoneId zone) {
        LinearLayout card = card();
        add(card, label("Historical weather by hour", 21, ink, true), 0);
        add(card, label("Local time · Snow, rain and depth in " + units.depthUnit() + " · Wind in " + units.windUnit(),
                12, muted, false), 4);
        var clock = DateTimeFormatter.ofPattern("h:mm a z", Locale.US);
        var scroll = new HorizontalScrollView(this);
        scroll.setFillViewport(true);
        var table = new TableLayout(this);
        table.setStretchAllColumns(true);
        table.addView(hourRow(true, "Starts", "Air", "Rain", "New snow", "Snow depth", "Wind", "Gust"));
        for (var hour : day.hours()) {
            double temp = Forecast.fahrenheit(hour.temperature() / 10.0);
            table.addView(hourRow(false, clock.format(Instant.ofEpochSecond(hour.time()).atZone(zone)),
                    units.temperatures(temp, temp), hourlyHistoricalDepth(hour.rain(), 254.0),
                    hourlyHistoricalDepth(hour.snow(), 25.4), hourlyHistoricalDepth(hour.depth(), 25.4),
                    hour.wind() == HistoricalWeather.MISSING ? "—" : units.windValue(hour.wind() / 10.0),
                    hour.gust() == HistoricalWeather.MISSING ? "—" : units.windValue(hour.gust() / 10.0)));
        }
        scroll.addView(table);
        add(card, scroll, 8);
        add(body, card, 12);
    }

    private void addCalendarWeather(LinearLayout card, Forecast.Summary weather) {
        if (!weather.available()) return;
        Forecast.Weather condition = weather.weatherIcon();
        LinearLayout summary = row();
        summary.addView(new WeatherIcon(condition), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView text = label(condition.label + "  ·  Low / high " + units.temperatures(weather.low(), weather.high()),
                14, ink, false);
        text.setPadding(dp(8), 0, 0, 0);
        summary.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        add(card, summary, 5);
    }

    private void renderCalendarStorms(int dayStart, int dayEnd) {
        for (SnowTimeline.Event event : timeline.events) {
            if (!event.overlaps(dayStart, dayEnd)) continue;
            LinearLayout storm = card();
            add(storm, label(event.extended() ? "Extended storm signal" : event.first() / 24 != (event.end() - 1) / 24
                    ? "Storm spans multiple days" : "Storm window", 20, accent, true), 0);
            int first = event.extended() ? event.first() / 6 * 6 : event.first();
            int end = event.extended() ? (event.end() + 5) / 6 * 6 : event.end();
            add(storm, label((event.extended() ? "Approx. " : "") + eventTime(first) + " → " + eventTime(end), 14, ink, false), 5);
            showEventBoundary(storm, event);
            addWeather(storm, forecast.conditions(event.first(), event.end()));
            SnowTimeline.Stats eventSnow = timeline.stats(event.first(), event.end());
            addDistribution(storm, event.mayStartEarlier() || event.mayContinue() ? "Snowfall in the visible storm window" : "Storm total", eventSnow);
            if (eventSnow.available()) addHistogram(storm, eventSnow.buckets());
            add(body, storm, 12);
        }

    }

    private void addMonthButton(LinearLayout parent, String symbol, YearMonth target, LocalDate first, LocalDate last) {
        boolean enabled = !target.atEndOfMonth().isBefore(first) && !target.atDay(1).isAfter(last);
        TextView button = action(symbol, false, () -> {
            LocalDate start = target.atDay(1);
            selectDate(start.isBefore(first) ? first : start);
        });
        button.setContentDescription(symbol.equals("‹") ? "Previous month" : "Next month");
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1 : .35f);
        parent.addView(button);
    }

    private void selectDate(LocalDate date) {
        calendarDate = date;
        renderAtScroll(scroller.getScrollY());
    }

    private void renderAtScroll(int position) {
        render();
        // The new content must be measured before ScrollView can restore a deep offset.
        scroller.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                scroller.getViewTreeObserver().removeOnPreDrawListener(this);
                scroller.scrollTo(0, position);
                return true;
            }
        });
    }

    private String eventTime(int hour) {
        return forecast.start.atStartOfDay().plusHours(hour).format(EVENT_TIME);
    }

    private void addDistribution(LinearLayout card, String heading, SnowTimeline.Stats stats) {
        add(card, label(heading, 15, ink, true), 8);
        if (!stats.available()) {
            add(card, label("Complete guidance unavailable", 14, muted, false), 5);
            return;
        }
        add(card, label(range(stats.amount(.10), stats.amount(.90)), 35, ink, true), 3);
        add(card, label("Middle 80% of model guidance  ·  Median " + amount(stats.amount(.50)) + " " + units.depthUnit(), 13, muted, false), 3);
        Forecast.Confidence confidence = stats.confidence();
        add(card, confidenceButton(confidence.rating() + " confidence  ·  " + stats.count() + " members", confidence), 5);
    }

    private TextView confidenceButton(String text, Forecast.Confidence confidence) {
        TextView button = action(text + "  ⓘ", false, () -> dialogBuilder()
                .setTitle("Forecast confidence · " + confidence.rating())
                .setMessage(confidence.reason())
                .setPositiveButton("Done", null).show());
        button.setTextColor(accent);
        button.setContentDescription(text + ". Explain confidence rating");
        return button;
    }

    private void renderHours(int dayStart, SnowTimeline.Stats[] rates) {
        LinearLayout card = card();
        add(card, label("Hour by hour", 21, ink, true), 0);
        add(card, label("Local time  ·  Snow in " + units.depthUnit() + "  ·  Wind in " + units.windUnit(),
                12, muted, false), 4);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setFillViewport(true);
        TableLayout table = new TableLayout(this);
        table.setStretchAllColumns(true);
        table.addView(hourRow(true, "Starts", "Snow", "Storm total", "Air", "Wind"));
        for (int hour = 0; hour < rates.length; hour++) {
            int absolute = dayStart + hour;
            SnowTimeline.Stats rate = rates[hour];
            String rateText = rate.available() ? hourlyRange(rate) : absolute < timeline.firstHour ? "Elapsed" : "N/A";
            SnowTimeline.Event event = timeline.eventAt(absolute);
            String soFar = "—", temperature = "—", wind = "—";
            if (event != null) {
                SnowTimeline.Stats total = timeline.stats(event.first(), absolute + 1);
                soFar = total.available() ? hourlyRange(total) : "N/A";
            }
            if (rate.available()) {
                Forecast.Conditions conditions = forecast.conditions(absolute, absolute + 1);
                temperature = units.temperatures(conditions.low(), conditions.high());
                wind = Double.isFinite(conditions.wind()) ? units.windValue(conditions.wind()) : "N/A";
            }
            String start = forecast.start.atStartOfDay().plusHours(absolute).format(HOUR);
            table.addView(hourRow(false, start, rateText, soFar, temperature, wind));
        }
        scroll.addView(table);
        add(card, scroll, 8);
        add(card, label("Storm totals include earlier days in the same storm and end at the close of each hour.", 12, muted, false), 8);
        add(body, card, 12);
    }

    private TableRow hourRow(boolean header, String... values) {
        TableRow row = new TableRow(this);
        for (int column = 0; column < values.length; column++) {
            TextView cell = label(values[column], 13, header ? accent : ink, header);
            cell.setSingleLine(true);
            cell.setIncludeFontPadding(false);
            cell.setGravity(column == 0 ? Gravity.START : Gravity.END);
            cell.setPadding(dp(3), dp(3), dp(5), dp(3));
            row.addView(cell, new TableRow.LayoutParams(-2, -2));
        }
        return row;
    }

    private String hourlyRange(SnowTimeline.Stats stats) {
        String low = liquid(stats.amount(.10)), high = liquid(stats.amount(.90));
        return low.equals(high) ? low : low + "–" + high;
    }

    private void addOutlookRow(LinearLayout section, int first, int end, boolean extended) {
        Forecast.Summary value = forecast.summarize(first, end);
        View divider = new View(this);
        divider.setBackgroundColor(line);
        add(section, divider, 12, dp(1));
        LinearLayout head = row();
        head.addView(label(dates(first, end), 16, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        Forecast.Confidence confidence = value.confidence();
        head.addView(confidenceButton(confidence.rating(), confidence));
        add(section, head, 10);
        if (!value.available()) return;
        String pattern = extended ? (value.snow(.50) >= .1 || value.rain(.50) >= .05
                ? "Precipitation possible" : "Mostly dry signal") : value.weather();
        add(section, label(pattern, 14, ink, false), 4);
        addWeather(section, value.conditions());
        String snow = extended ? range(value.snow(.10), value.snow(.90)) : amount(value.snow(.5)) + " " + units.depthUnit();
        String rain = extended ? range(value.rain(.10), value.rain(.90)) : liquid(value.rain(.5)) + " " + units.depthUnit();
        add(section, label("Snow " + snow + "  ·  Rain " + rain, 14, muted, false), 3);
        if (!extended) add(section, label(value.agreement(), 12, muted, false), 3);
    }

    private void renderSnowpack() {
        LinearLayout detail = card();
        add(detail, label("Fresh snow & melt", 22, ink, true), 0);
        add(detail, label("Experimental estimate for an open, level surface. Tracks fresh snow and daylight through the next seven days.", 14, muted, false), 5);
        for (int d = 0; d < 7; d++) {
            Forecast.Summary part = forecast.summarize(0, d + 1);
            View divider = new View(this);
            divider.setBackgroundColor(line);
            add(detail, divider, 12, dp(1));
            add(detail, label("Through " + dates(d, d + 1), 16, accent, true), 9);
            if (!part.available()) {
                add(detail, label("Unavailable", 14, muted, false), 3);
                continue;
            }
            add(detail, label(part.surface(), 16, ink, true), 4);
            double melt = Forecast.quantile(part.samples, Forecast.Sample::melt, .50);
            double low = Forecast.quantile(part.samples, Forecast.Sample::melt, .10);
            double high = Forecast.quantile(part.samples, Forecast.Sample::melt, .90);
            Forecast.Summary day = forecast.summarize(d, d + 1);
            addWeather(detail, day.conditions());
            if (day.available()) {
                add(detail, label(Math.round(Forecast.quantile(day.samples, Forecast.Sample::cloud, .50))
                        + "% cloud cover", 14, muted, false), 3);
            }
            if (Double.isFinite(melt) && part.snow(.50) >= .1) {
                add(detail, label(Math.round(melt) + "% of fresh snow water melted"
                        + "  ·  " + Math.round(low) + "–" + Math.round(high) + "% model range", 14, muted, false), 4);
            }
        }
        add(body, detail, 10);
    }

    private String dates(int first, int end) {
        String from = forecast.start.plusDays(first).format(DATE);
        return end - first == 1 ? from : from + "–" + forecast.start.plusDays(end - 1).format(DATE);
    }

    private void showFavorites() {
        int maximumHeight = Math.max(100, Math.min(380, getResources().getConfiguration().screenHeightDp - 210));
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                int limit = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED
                        ? dp(maximumHeight) : Math.min(MeasureSpec.getSize(height), dp(maximumHeight));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout entries = column();
        entries.setPadding(dp(16), dp(6), dp(16), dp(8));
        scroll.addView(entries);
        AlertDialog list = dialogBuilder().setTitle("Favorite locations").setView(scroll)
                .setPositiveButton("Done", null).create();
        populateFavorites(list, entries);
        list.show();
    }

    private void populateFavorites(AlertDialog list, LinearLayout entries) {
        entries.removeAllViews();
        if (favorites.sites().isEmpty()) {
            add(entries, label("Save a location from the forecast screen to find it here.", 15, muted, false), 8);
            return;
        }
        for (Forecast.Site favorite : favorites.sites()) {
            LinearLayout entry = row();
            LinearLayout details = column();
            details.setPadding(dp(8), dp(6), dp(4), dp(6));
            add(details, label(favorite.name(), 16, ink, true), 0);
            add(details, label(String.format(Locale.US, "%.4f°, %.4f°  ·  %,d ft",
                    favorite.latitude(), favorite.longitude(), favorite.feet()), 12, muted, false), 2);
            boolean selected = FavoriteLocations.samePoint(site, favorite);
            if (selected) add(details, label("Current location", 12, accent, false), 2);
            details.setContentDescription("Open forecast for " + favorite.name());
            details.setFocusable(true);
            details.setOnClickListener(view -> {
                list.dismiss();
                if (!selected) selectSite(favorite);
            });
            entry.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            TextView manage = action("Manage", false, () ->
                    showFavoriteActions(favorite, () -> populateFavorites(list, entries)));
            manage.setContentDescription("Manage favorite " + favorite.name());
            entry.addView(manage);
            add(entries, entry, 6);
        }
    }

    private void showFavoriteActions(Forecast.Site favorite, Runnable refreshList) {
        dialogBuilder().setTitle(favorite.name())
                .setItems(new String[]{"Rename", "Remove from favorites"}, (dialog, choice) -> {
                    if (choice == 0) {
                        renameFavorite(favorite, refreshList);
                    } else {
                        favorites.remove(favorite);
                        saveFavorites();
                        render();
                        refreshList.run();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void renameFavorite(Forecast.Site favorite, Runnable refreshList) {
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText name = input(form, "Favorite name", favorite.name(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setSelectAllOnFocus(true);
        AlertDialog dialog = dialogBuilder().setTitle("Rename favorite").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                name.setError("Enter a name.");
                return;
            }
            Forecast.Site renamed = favorites.rename(favorite, value);
            if (renamed != null) {
                if (FavoriteLocations.samePoint(site, renamed)) site = renamed;
                saveFavorites();
                render();
                refreshList.run();
            }
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void saveFavorites() {
        try {
            SharedPreferences.Editor changes = getPreferences(MODE_PRIVATE).edit()
                    .putString("favorite_locations", favorites.toJson());
            putSelectedSite(changes).apply();
        } catch (JSONException error) {
            Toast.makeText(this, "Couldn’t save favorites.", Toast.LENGTH_SHORT).show();
        }
    }

    private void chooseLocation() {
        String[] choices = new String[SITES.length + 4];
        choices[0] = "Choose on map";
        choices[1] = "Search by address";
        choices[2] = "Search by city or ZIP";
        choices[3] = "Enter coordinates and elevation";
        for (int i = 0; i < SITES.length; i++) choices[i + 4] = SITES[i].name() + "  ·  " + SITES[i].region();
        dialogBuilder().setTitle("Forecast location")
                .setItems(choices, (dialog, position) -> {
                    if (position == 0) showMap();
                    else if (position == 1) searchLocation(true);
                    else if (position == 2) searchLocation(false);
                    else if (position == 3) editLocation(site.latitude(), site.longitude(), site.feet(), null);
                    else selectSite(SITES[position - 4]);
                }).show();
    }

    private void searchLocation(boolean street) {
        String title = street ? "Search by address" : "Search by city or ZIP";
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), 4);
        EditText field = input(form, street ? "U.S. street address" : "U.S. city or ZIP", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        field.setHint(street ? "Street, city, state and ZIP" : "City, state or ZIP code");
        add(form, label(street ? "Sends your search to the U.S. Census Geocoder."
                : "Sends your search to Open-Meteo.", 13, muted, false), 6);
        AlertDialog dialog = dialogBuilder().setTitle(title).setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Search", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String query = field.getText().toString().trim();
            if (query.isEmpty()) {
                field.setError(street ? "Enter a street address." : "Enter a city or ZIP code.");
                return;
            }
            view.setEnabled(false);
            field.setError(null);
            locationWorker.submit(() -> {
                List<LocationSearch.Match> results;
                try {
                    results = street ? LocationSearch.findStreet(query) : LocationSearch.findPlace(query);
                } catch (Exception error) {
                    // Network exceptions can include the search URL and address.
                    runOnUiThread(() -> {
                        if (dialog.isShowing() && !isDestroyed()) {
                            view.setEnabled(true);
                            field.setError("Search is unavailable. Try again or choose a map pin.");
                        }
                    });
                    return;
                }
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || isDestroyed()) return;
                    view.setEnabled(true);
                    if (results.isEmpty()) {
                        field.setError(street ? "No match. Try a full address or choose a map pin."
                                : "No match. Try a city and state or a five-digit ZIP code.");
                        return;
                    }
                    dialog.dismiss();
                    String[] labels = results.stream().map(LocationSearch.Match::label).toArray(String[]::new);
                    dialogBuilder().setTitle(street ? "Choose an address" : "Choose a place")
                            .setItems(labels, (picker, which) -> {
                                LocationSearch.Match selected = results.get(which);
                                editLocation(selected.latitude(), selected.longitude(), null, selected.label());
                            }).setNegativeButton("Cancel", null).show();
                });
            });
        }));
        dialog.show();
    }

    private void showMap() {
        LinearLayout panel = column();
        panel.setPadding(dp(14), 0, dp(14), 0);
        TextView coordinates = label("", 14, accent, true);
        LocationMap map = new LocationMap(this, site.latitude(), site.longitude(),
                (latitude, longitude) -> coordinates.setText(mapCoordinates(latitude, longitude)));
        coordinates.setText(mapCoordinates(map.latitude(), map.longitude()));
        add(panel, label("Tap a location or drag it under the pin.", 14, muted, false), 3);
        int height = Math.max(110, Math.min(370, getResources().getConfiguration().screenHeightDp - 285));
        add(panel, map, 8, dp(height));
        TextView credit = label("© OpenStreetMap contributors", 12, ink, false);
        credit.setOnClickListener(view -> openLink("https://www.openstreetmap.org/copyright"));
        add(panel, credit, 4);
        LinearLayout controls = row();
        controls.addView(action("−", false, () -> map.zoomBy(-1)));
        controls.addView(action("+", false, () -> map.zoomBy(1)));
        controls.addView(action("Retry map", false, map::resetFailedTiles));
        add(panel, controls, 8);
        add(panel, coordinates, 6);
        AlertDialog dialog = dialogBuilder().setTitle("Select on map").setView(panel)
                .setNegativeButton("Cancel", null).setPositiveButton("Use pin", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            double latitude = map.latitude(), longitude = map.longitude();
            if (!ForecastSource.validSite(latitude, longitude, 0)) {
                coordinates.setText("Choose a point in the U.S. forecast area.");
                return;
            }
            dialog.dismiss();
            editLocation(latitude, longitude, null, null);
        }));
        dialog.show();
    }

    private static String mapCoordinates(double latitude, double longitude) {
        return String.format(Locale.US, "%.5f°, %.5f°", latitude, longitude);
    }

    private void editLocation(double latitude, double longitude, Integer elevationFeet, String addressName) {
        LinearLayout form = column();
        form.setPadding(dp(22), dp(12), dp(22), 0);
        String locationName = site.region().equals("Custom forecast point") ? site.name() : "My location";
        EditText name = input(form, "Location name", addressName == null ? locationName : addressName, InputType.TYPE_CLASS_TEXT);
        int decimal = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        String latText = String.format(Locale.US, "%.6f", latitude);
        String lonText = String.format(Locale.US, "%.6f", longitude);
        EditText lat = input(form, "Latitude", latText, decimal);
        EditText lon = input(form, "Longitude", lonText, decimal);
        EditText feet = input(form, "Elevation (feet)", elevationFeet == null ? "" : elevationFeet.toString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        AlertDialog dialog = dialogBuilder().setTitle("Custom location").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                double a = Double.parseDouble(lat.getText().toString().trim());
                double b = Double.parseDouble(lon.getText().toString().trim());
                int elevation = Integer.parseInt(feet.getText().toString().trim());
                if (!ForecastSource.validSite(a, b, elevation)) {
                    feet.setError("Check the coordinates are in the U.S. forecast area and elevation is −1,500 to 21,000 ft.");
                    return;
                }
                String title = name.getText().toString().trim();
                selectSite(new Forecast.Site(title.isEmpty() ? "Custom location" : title, "Custom forecast point", a, b, elevation));
                dialog.dismiss();
            } catch (NumberFormatException ex) {
                feet.setError("Enter numeric coordinates and elevation.");
            }
        }));
        dialog.show();
        if (elevationFeet == null) {
            feet.setHint("Looking up terrain elevation…");
            locationWorker.submit(() -> {
                Integer elevation = null;
                try {
                    elevation = ForecastSource.elevationFeet(latitude, longitude);
                } catch (Exception ignored) { }
                Integer found = elevation;
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || isDestroyed()) return;
                    feet.setHint("Enter elevation in feet");
                    if (found != null && feet.length() == 0 && latText.contentEquals(lat.getText())
                            && lonText.contentEquals(lon.getText())) feet.setText(found.toString());
                });
            });
        }
    }

    private EditText input(LinearLayout form, String title, String value, int type) {
        TextView caption = label(title, 14, muted, false);
        add(form, caption, 8);
        EditText field = new EditText(dialogContext());
        field.setSingleLine(true);
        field.setContentDescription(title);
        field.setInputType(type);
        field.setText(value);
        add(form, field, 0);
        return field;
    }

    private TextView openMeteoCredit(String text) {
        TextView credit = label(text, 12, accent, false);
        credit.setPaintFlags(credit.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        credit.setOnClickListener(view -> openLink(OPEN_METEO_URL));
        return credit;
    }

    private void showMethods() {
        dialogBuilder().setTitle("Forecast methods")
                .setMessage("Live data: NOAA GEFS, ECMWF IFS, and Canada GEM (GEPS) via Open-Meteo."
                        + "\n\nOpen-Meteo weather data are licensed under CC BY 4.0. SnowOrNo processes that data into forecast ranges, storm windows, and snow depth estimates."
                        + "\n\nThe last successful forecast for this location may be shown while refreshing or when a live update fails. Saved forecasts expire after 48 hours. Retrieval time is shown in your device's timezone."
                        + "\n\nThe point and elevation are inputs to model post-processing. Model grids cover a larger area."
                        + "\n\nSnowOrNo archives storm forecasts as they load. Measured snowfall can inform a labeled experimental median preview. Forward forecast ranges and model weights use the original ensemble. Per-model accuracy uses the first complete forecast retrieved up to seven days before each measured storm."
                        + "\n\nTemperature ranges show the median modeled low and high during the window. Wind is the median average speed at 10 meters above ground. Peak wind shows the median of the members' highest hourly speeds."
                        + "\n\nSnowfall scenarios are model-member shares. Each model family has equal weight. Confidence is provisional and uses lead time, ensemble spread and the difference between model medians."
                        + "\n\nForecast windows follow the site's local calendar using the current UTC offset. An extended window requires complete model data across all its days."
                        + "\n\nStorm windows begin when at least 30% of the weighted guidance shows 0.01 inches of snow in an hour. Breaks of up to six hours are grouped into the same storm. Running totals are calculated per member before combining the ensemble. Days 15–30 use six-hour display windows for timing and carry very low confidence."
                        + "\n\nLocation estimates start from the current model hour in live mode. They use a ground-temperature proxy with separate lawn and pavement settings. Shade reduces modeled solar input. Peak and remaining depth account for melt and settling on an initially bare surface."
                        + "\n\nFresh snow details use modeled temperature, rain and cloud cover to estimate melt and refreeze of fresh snow water on level, open terrain.")
                .setNeutralButton("Utah Snow Ensemble", (dialog, which) ->
                        openLink("https://weather.utah.edu/index.php?d=240HS&r=NU&t=ensgefsds"))
                .setNegativeButton("CC BY 4.0", (dialog, which) -> openLink(WEATHER_DATA_LICENSE))
                .setPositiveButton("Done", null).show();
    }

    private android.view.ContextThemeWrapper dialogContext() {
        return new android.view.ContextThemeWrapper(this, lightMode
                ? R.style.SnowOrNo_Light : R.style.SnowOrNo_Dark);
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(dialogContext());
    }

    private ImageButton iconButton(boolean settings, String description, Runnable callback) {
        ImageButton button = new ImageButton(this);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setImageResource(settings ? R.drawable.ic_settings : R.drawable.ic_refresh);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(ink));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setBackground(shape(panel, line, 12));
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    private void openLink(String address) {
        Intent page = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
        try {
            startActivity(page);
        } catch (android.content.ActivityNotFoundException ignored) { }
    }

    private String amount(double x) { return units.depth(x, 1); }
    private String liquid(double x) { return units.depth(x, 2); }
    private String range(double low, double high) { return amount(low) + "–" + amount(high) + " " + units.depthUnit(); }
    private static String percentage(double x) {
        if (x > 0 && x < .005) return "<1%";
        if (x < 1 - 1e-9 && x > .995) return ">99%";
        return Math.round(100 * x) + "%";
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(15), dp(15), dp(15), dp(15));
        card.setBackground(shape(panel, line, 20));
        return card;
    }

    private LinearLayout stormCard(boolean current) {
        LinearLayout card = card();
        if (current) card.setBackground(shape(selectedFill, accent, 20));
        return card;
    }

    private String expectedSnow(double low, double median, double high) {
        if (!Double.isFinite(median)) return "Expected snowfall unavailable";
        String medianText = amount(median) + " " + units.depthUnit();
        return "Expected snow " + (Double.isFinite(low) && Double.isFinite(high)
                ? range(low, high) + "  ·  Median " + medianText : medianText);
    }

    private <T> void addStormToggle(LinearLayout card, LinearLayout details, String name, String dates,
                                    String snowfall, boolean current, Set<T> collapsed, T key) {
        LinearLayout header = row();
        LinearLayout text = column();
        TextView title = label(name, 20, accent, true);
        add(text, title, 0);
        LinearLayout summary = column();
        add(summary, label(dates, 15, current ? accent : ink, true), 0);
        add(summary, label(snowfall, 13, ink, false), 3);
        add(text, summary, 0);
        header.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView caret = action("", false, () -> {});
        caret.setTextSize(20);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        buttonParams.setMarginStart(dp(8));
        header.addView(caret, buttonParams);
        add(card, header, 0);
        add(card, details, 7);
        Runnable update = () -> {
            boolean closed = collapsed.contains(key);
            details.setVisibility(closed ? View.GONE : View.VISIBLE);
            title.setVisibility(closed ? View.GONE : View.VISIBLE);
            summary.setVisibility(closed ? View.VISIBLE : View.GONE);
            caret.setText(closed ? "▾" : "▴");
            caret.setContentDescription((closed ? "Expand " : "Collapse ") + name.toLowerCase(Locale.US) + ", " + dates);
        };
        caret.setOnClickListener(view -> {
            if (!collapsed.remove(key)) collapsed.add(key);
            update.run();
        });
        update.run();
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private TextView action(String text, boolean selected, Runnable callback) {
        TextView view = label(text, 14, selected ? accent : ink, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), dp(6), dp(12), dp(6));
        view.setBackground(shape(selected ? selectedFill : panel, selected ? accent : line, 12));
        view.setSelected(selected);
        view.setFocusable(true);
        view.setOnClickListener(ignored -> callback.run());
        return view;
    }

    private void add(LinearLayout parent, View view, int gap) { add(parent, view, gap, -2); }
    private void add(LinearLayout parent, View view, int gap, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = dp(gap);
        parent.addView(view, params);
    }

    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class WeatherIcon extends View {
        private final Forecast.Weather weather;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ice = new Path();

        WeatherIcon(Forecast.Weather weather) {
            super(MainActivity.this);
            this.weather = weather;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setStrokeCap(Paint.Cap.ROUND);
            ice.moveTo(12, 4);
            ice.lineTo(19, 11);
            ice.lineTo(12, 21);
            ice.lineTo(5, 11);
            ice.close();
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            float scale = Math.min(getWidth(), getHeight()) / 24f;
            canvas.translate((getWidth() - 24 * scale) / 2f, (getHeight() - 24 * scale) / 2f);
            canvas.scale(scale, scale);
            switch (weather) {
                case SUNNY -> sun(canvas, 12, 12, 5);
                case PARTLY_SUNNY -> {
                    sun(canvas, 9, 9, 4);
                    cloud(canvas, 14, 16, .76f);
                }
                case PARTLY_CLOUDY -> {
                    sun(canvas, 8, 8, 3.5f);
                    cloud(canvas, 13, 15, 1);
                }
                case CLOUDY -> cloud(canvas, 12, 13, 1.05f);
                case RAIN, MIXED, SNOW -> {
                    cloud(canvas, 12, 10, 1);
                    if (weather != Forecast.Weather.SNOW) rain(canvas, 8);
                    if (weather == Forecast.Weather.RAIN) rain(canvas, 15);
                    else flake(canvas, weather == Forecast.Weather.MIXED ? 16 : 12, 18.5f);
                }
                case ICE -> {
                    paint.setColor(accent);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.7f);
                    canvas.drawPath(ice, paint);
                    canvas.drawLine(5, 11, 19, 11, paint);
                    canvas.drawLine(12, 4, 12, 21, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
            canvas.restore();
        }

        private void sun(Canvas canvas, float x, float y, float radius) {
            paint.setColor(lightMode ? 0xffa85a00 : 0xffffca67);
            paint.setStrokeWidth(1.4f);
            canvas.drawCircle(x, y, radius, paint);
            for (int ray = 0; ray < 8; ray++) {
                canvas.save();
                canvas.rotate(45 * ray, x, y);
                canvas.drawLine(x, y - radius - 1.8f, x, y - radius - 3.4f, paint);
                canvas.restore();
            }
        }

        private void cloud(Canvas canvas, float x, float y, float scale) {
            paint.setColor(lightMode ? 0xff526e91 : 0xffd2e2f7);
            canvas.save();
            canvas.translate(x, y);
            canvas.scale(scale, scale);
            canvas.drawCircle(-5, 0, 4, paint);
            canvas.drawCircle(-.6f, -2.4f, 5, paint);
            canvas.drawCircle(5.2f, .3f, 3.8f, paint);
            canvas.drawRoundRect(-8.8f, -.7f, 9, 4.1f, 2.3f, 2.3f, paint);
            canvas.restore();
        }

        private void rain(Canvas canvas, float x) {
            paint.setColor(lightMode ? 0xff115d96 : 0xff78c8fb);
            paint.setStrokeWidth(1.9f);
            canvas.drawLine(x + 1, 16.5f, x - .8f, 20.6f, paint);
        }

        private void flake(Canvas canvas, float x, float y) {
            paint.setColor(accent);
            paint.setStrokeWidth(1.4f);
            for (int branch = 0; branch < 3; branch++) {
                canvas.save();
                canvas.rotate(60 * branch, x, y);
                canvas.drawLine(x, y - 3.4f, x, y + 3.4f, paint);
                canvas.restore();
            }
        }
    }

    private void addCumulativeSnowChart(LinearLayout card, List<SnowTimeline.CumulativePoint> points,
                                        int axisStart, int axisEnd, Integer dividingHour) {
        if (points.isEmpty()) return;
        add(card, label("Cumulative snowfall (" + units.depthUnit() + ")", 17, ink, true), 12);
        add(card, new CumulativeSnowChart(points, axisStart, axisEnd, dividingHour), 5, dp(170));
        add(card, label("Blue line · Median   |   Shaded band · Low to high (10th–90th percentile)",
                12, muted, false), 4);
        if (dividingHour != null) {
            add(card, label("Dashed line · " + eventTime(dividingHour) + " separates elapsed model guidance from the forecast remaining",
                    12, muted, false), 4);
        }
    }

    private final class CumulativeSnowChart extends View {
        private final List<SnowTimeline.CumulativePoint> points;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int axisStart, axisEnd;
        private final Integer dividingHour;
        private final double tick;

        CumulativeSnowChart(List<SnowTimeline.CumulativePoint> points, int axisStart, int axisEnd, Integer dividingHour) {
            super(MainActivity.this);
            this.points = points;
            this.axisStart = axisStart;
            this.axisEnd = axisEnd;
            this.dividingHour = dividingHour;
            SnowTimeline.CumulativePoint total = points.get(points.size() - 1);
            double target = total.high() * (units.centimeters() ? 2.54 : 1) / 3;
            double magnitude = Math.pow(10, Math.floor(Math.log10(Math.max(.1, target))));
            tick = Math.max(.1, Math.ceil(target / magnitude) * magnitude);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setContentDescription("Cumulative snowfall from " + eventTime(axisStart) + " to " + eventTime(axisEnd)
                    + ". Final low, median and high: " + amount(total.low()) + ", " + amount(total.median()) + ", and "
                    + amount(total.high()) + " " + units.depthUnit() + ".");
        }

        @Override protected void onDraw(Canvas canvas) {
            float top = dp(10), bottom = getHeight() - dp(36), right = getWidth() - dp(3);
            int decimals = tick < 1 ? 1 : 0;
            paint.setTextSize(dp(11));
            paint.setTextAlign(Paint.Align.LEFT);
            float left = paint.measureText(axisNumber(3 * tick, decimals)) + dp(8);
            if (left >= right || top >= bottom) return;

            for (int mark = 0; mark <= 3; mark++) {
                float y = bottom - (bottom - top) * mark / 3;
                paint.setColor(line);
                paint.setStrokeWidth(dp(1));
                canvas.drawLine(left, y, right, y, paint);
                paint.setColor(muted);
                canvas.drawText(axisNumber(tick * mark, decimals), 0, y + dp(4), paint);
            }

            double maximum = tick * 3 / (units.centimeters() ? 2.54 : 1);
            Path low = new Path(), median = new Path(), high = new Path(), band = new Path();
            for (int i = 0; i < points.size(); i++) {
                SnowTimeline.CumulativePoint point = points.get(i);
                float x = axisX(point.hour(), left, right);
                plot(low, i, x, chartY(point.low(), maximum, top, bottom));
                plot(median, i, x, chartY(point.median(), maximum, top, bottom));
                float y = chartY(point.high(), maximum, top, bottom);
                plot(high, i, x, y);
                plot(band, i, x, y);
            }
            for (int i = points.size() - 1; i >= 0; i--) {
                SnowTimeline.CumulativePoint point = points.get(i);
                band.lineTo(axisX(point.hour(), left, right), chartY(point.low(), maximum, top, bottom));
            }
            band.close();
            paint.setColor(accent);
            paint.setAlpha(55);
            canvas.drawPath(band, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(muted);
            canvas.drawPath(low, paint);
            canvas.drawPath(high, paint);
            paint.setColor(accent);
            paint.setStrokeWidth(dp(2));
            canvas.drawPath(median, paint);
            if (dividingHour != null) {
                float x = axisX(dividingHour, left, right);
                paint.setColor(warn);
                paint.setStrokeWidth(dp(1));
                paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(4), dp(4)}, 0));
                canvas.drawLine(x, top, x, bottom, paint);
                paint.setPathEffect(null);
            }
            paint.setStyle(Paint.Style.FILL);

            paint.setColor(muted);
            drawTime(canvas, axisStart, left, bottom, Paint.Align.LEFT);
            drawTime(canvas, axisEnd, right, bottom, Paint.Align.RIGHT);
            if (points.size() >= 5 && right - left >= dp(155)) {
                int hour = points.get(points.size() / 2).hour();
                drawTime(canvas, hour, axisX(hour, left, right), bottom, Paint.Align.CENTER);
            }
        }

        private String axisNumber(double value, int decimals) {
            return String.format(Locale.US, "%." + decimals + "f", value);
        }

        private float axisX(int hour, float left, float right) {
            return left + (right - left) * (hour - axisStart) / (axisEnd - axisStart);
        }

        private float chartY(double value, double maximum, float top, float bottom) {
            return bottom - (bottom - top) * (float)(value / maximum);
        }

        private void plot(Path path, int index, float x, float y) {
            if (index == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }

        private void drawTime(Canvas canvas, int hour, float x, float bottom, Paint.Align align) {
            var time = forecast.start.atStartOfDay().plusHours(hour);
            paint.setTextAlign(align);
            canvas.drawText(time.format(DATE), x, bottom + dp(15), paint);
            canvas.drawText(time.format(HOUR), x, bottom + dp(29), paint);
        }
    }

    private final class HourChart extends View {
        private final double[][] values = new double[24][];
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private double max = .1;

        HourChart(SnowTimeline.Stats[] hours) {
            super(MainActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            for (int i = 0; i < hours.length; i++) {
                if (!hours[i].available()) continue;
                values[i] = new double[]{hours[i].amount(.10), hours[i].amount(.50), hours[i].amount(.90)};
                max = Math.max(max, values[i][2]);
            }
            max = Math.ceil(max * 10 - 1e-9) / 10;
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float top = dp(12), bottom = getHeight() - dp(20), left = dp(31);
            float step = (getWidth() - left) / 24;
            paint.setTextSize(dp(11));
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            paint.setColor(muted);
            canvas.drawText(amount(max), 0, top + dp(5), paint);
            canvas.drawText("0", 0, bottom, paint);
            paint.setColor(line);
            canvas.drawLine(left, top, getWidth(), top, paint);
            canvas.drawLine(left, bottom, getWidth(), bottom, paint);
            paint.setStrokeWidth(dp(1));
            for (int hour = 0; hour < values.length; hour++) {
                float x = left + (hour + .5f) * step;
                double[] metrics = values[hour];
                if (metrics != null) {
                    paint.setColor(accent);
                    float median = bottom - (float)(metrics[1] / max) * (bottom - top);
                    canvas.drawRect(x - step * .22f, median, x + step * .22f, bottom, paint);
                    paint.setColor(ink);
                    float high = bottom - (float)(metrics[2] / max) * (bottom - top);
                    float low = bottom - (float)(metrics[0] / max) * (bottom - top);
                    if (high != low) canvas.drawLine(x, high, x, low, paint);
                }
                if (hour % 6 == 0) {
                    paint.setColor(muted);
                    canvas.drawText(hour == 0 ? "12a" : hour == 6 ? "6a" : hour == 12 ? "12p" : "6p",
                            x - step / 2, getHeight() - dp(4), paint);
                }
            }
        }
    }

    private final class ProbabilityBar extends View {
        private final double probability;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ProbabilityBar(double probability) {
            super(MainActivity.this);
            this.probability = probability;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float h = getHeight(), w = getWidth();
            paint.setColor(line);
            canvas.drawRoundRect(0, 0, w, h, h / 2, h / 2, paint);
            if (probability > 0) {
                paint.setColor(accent);
                canvas.drawRoundRect(0, 0, Math.min(w, Math.max(dp(2), (float)(w * probability))),
                        h, h / 2, h / 2, paint);
            }
        }
    }
}
