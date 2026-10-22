package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * Picks the koi pond's look from the real sky: local sunrise/sunset and the current weather.
 * City-level location comes from the IP address (ipwho.is, cached a day), the weather from
 * Open-Meteo (no key, cached 30 min). Without a network the clock alone decides day, sunset
 * or night, using the last known sunrise and sunset.
 */
final class PondWeather {

    private static final String TAG = "PondWeather";
    private static final long LOCATION_TTL = 24 * 3600_000L, WEATHER_TTL = 30 * 60_000L, WEATHER_STALE = 3 * 3600_000L;
    /** Minutes either side of sunrise and sunset that count as golden hour. */
    private static final int GOLDEN = 40;

    /** A nagomi weather preset id, and whether rain ripples fall on the pond. */
    static final class Look {
        final String preset;
        final boolean rain;

        Look(String preset, boolean rain) {
            this.preset = preset;
            this.rain = rain;
        }

        @Override public String toString() { return preset + (rain ? "+rain" : ""); }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /**
     * The look for right now from what is cached, without any network. {@code seed} keeps the
     * random choices (which clear-sky preset) stable for one screensaver session.
     */
    static Look current(Context context, int seed) {
        SharedPreferences p = prefs(context);
        Calendar cal = Calendar.getInstance();
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int sunrise = p.getInt("sunrise", 6 * 60 + 30), sunset = p.getInt("sunset", 19 * 60 + 30);
        boolean fresh = System.currentTimeMillis() - p.getLong("weatherAt", 0) < WEATHER_STALE;
        int code = fresh ? p.getInt("code", -1) : -1;

        boolean rain = isRain(code), fog = code == 45 || code == 48 || isSnow(code);
        boolean golden = Math.abs(now - sunrise) <= GOLDEN || Math.abs(now - sunset) <= GOLDEN;
        boolean night = now < sunrise - GOLDEN || now > sunset + GOLDEN;
        if (night) return new Look("moonlight", rain);
        if (rain) return new Look("rain", true);
        if (golden && !fog) return new Look("sunset", false);
        if (fog) return new Look("mist", false);
        if (code == 2 || code == 3) return new Look("overcast", false);
        if (code < 0) { // no weather known: any daytime sky
            String[] any = {"sunny", "deep-clear", "overcast"};
            return new Look(any[Math.floorMod(seed, any.length)], false);
        }
        return new Look((seed & 1) == 0 ? "sunny" : "deep-clear", false);
    }

    /** Updates the cache from the network if it is stale. Blocking: call off the main thread. */
    static void refresh(Context context) {
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        try {
            if (now - p.getLong("locationAt", 0) > LOCATION_TTL) {
                JSONObject loc = new JSONObject(get("https://ipwho.is/"));
                if (loc.optBoolean("success", false)) {
                    p.edit().putFloat("lat", (float) loc.getDouble("latitude"))
                            .putFloat("lon", (float) loc.getDouble("longitude"))
                            .putString("city", loc.optString("city"))
                            .putLong("locationAt", now).apply();
                }
            }
            if (!p.contains("lat") || now - p.getLong("weatherAt", 0) < WEATHER_TTL) return;
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=weather_code,is_day&daily=sunrise,sunset&timezone=auto&forecast_days=1",
                    p.getFloat("lat", 0), p.getFloat("lon", 0));
            JSONObject w = new JSONObject(get(url));
            JSONObject daily = w.getJSONObject("daily");
            int code = w.getJSONObject("current").getInt("weather_code");
            p.edit().putInt("code", code)
                    .putInt("sunrise", minuteOfDay(daily.getJSONArray("sunrise").getString(0)))
                    .putInt("sunset", minuteOfDay(daily.getJSONArray("sunset").getString(0)))
                    .putLong("weatherAt", now).apply();
            Log.i(TAG, p.getString("city", "?") + ": weather code " + code);
        } catch (Exception e) {
            Log.i(TAG, "offline, using the clock: " + e);
        }
    }

    /** "2026-10-21T07:24" → 444. */
    private static int minuteOfDay(String isoLocal) {
        String time = isoLocal.substring(isoLocal.indexOf('T') + 1);
        return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
    }

    /** WMO codes: drizzle, rain, freezing rain, showers, thunderstorms. */
    private static boolean isRain(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
    }

    private static boolean isSnow(int code) {
        return (code >= 71 && code <= 77) || code == 85 || code == 86;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private PondWeather() {}
}
