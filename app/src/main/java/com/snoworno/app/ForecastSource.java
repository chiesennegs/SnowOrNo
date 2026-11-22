package com.snoworno.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

final class ForecastSource {
    private static final String ENDPOINT = "https://ensemble-api.open-meteo.com/v1/ensemble";
    private static final double[] LATITUDE_LIMITS = {18, 72};
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;

    record Feed(String name, JSONObject payload) {}
    record Download(Forecast forecast, Instant retrievedAt, List<Feed> feeds) {}

    static Download live(Forecast.Site site) throws IOException {
        List<Feed> feeds = new ArrayList<>();
        Exception failure = null;
        String[][] sources = {
                {"ncep_gefs_seamless", "NOAA GEFS", "31"},
                {"ecmwf_ifs025_ensemble", "ECMWF IFS", "16"},
                {"cmc_gem_geps", "Canada GEM (GEPS)", "31"}
        };
        List<Callable<Feed>> requests = new ArrayList<>();
        for (String[] source : sources) {
            String url = String.format(Locale.US, "%s?latitude=%.6f&longitude=%.6f&elevation=%.1f"
                            + "&models=%s&timezone=auto&forecast_days=%s&past_days=%d"
                            + "&wind_speed_unit=kmh&hourly=temperature_2m,snowfall,rain,cloud_cover,wind_speed_10m",
                    ENDPOINT, site.latitude(), site.longitude(), site.feet() * .3048, source[0], source[2],
                    Forecast.STORM_LOOKBACK_DAYS);
            requests.add(() -> new Feed(source[1], read(url)));
        }
        var downloads = Executors.newFixedThreadPool(sources.length);
        try {
            for (var result : downloads.invokeAll(requests)) {
                try {
                    feeds.add(result.get());
                } catch (ExecutionException error) {
                    failure = error;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Forecast request cancelled", error);
        } finally {
            downloads.shutdownNow();
        }
        Instant received = Instant.now();
        Download result = assemble(feeds, site, received, received);
        if (result == null) throw new IOException("No usable ensemble forecast received", failure);
        return result;
    }

    static Download assemble(List<Feed> feeds, Forecast.Site site, Instant received, Instant now) {
        List<Forecast.Model> models = new ArrayList<>();
        List<Feed> accepted = new ArrayList<>();
        LocalDate start = null;
        ZoneOffset offset = null;
        int firstFutureHour = 0;
        for (Feed feed : feeds) {
            try {
                Forecast response = parse(feed.payload(), feed.name(), site, now);
                boolean usable = response.summarize(0, 1).available() || response.summarize(1, 2).available();
                if (usable && (start == null || start.equals(response.start) && offset.equals(response.offset))) {
                    start = response.start;
                    offset = response.offset;
                    firstFutureHour = Math.max(firstFutureHour, response.firstFutureHour);
                    models.addAll(response.models);
                    accepted.add(feed);
                }
            } catch (Exception ignored) {
                // Keep usable guidance if another model is unavailable or malformed.
            }
        }
        if (start == null) return null;
        return new Download(new Forecast(start, models, firstFutureHour, offset), received, accepted);
    }

    static JSONObject read(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IOException("HTTPS is required.");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(12000);
            if (connection.getResponseCode() != 200) throw new IOException("Weather feed HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (output.size() + size > MAX_RESPONSE_BYTES) throw new IOException("Weather response too large");
                    output.write(buffer, 0, size);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            connection.disconnect();
        }
    }

    static int elevationFeet(double latitude, double longitude) throws Exception {
        String address = String.format(Locale.US, "https://api.open-meteo.com/v1/elevation?latitude=%.6f&longitude=%.6f",
                latitude, longitude);
        double meters = read(address).getJSONArray("elevation").getDouble(0);
        int feet = (int)Math.round(meters / .3048);
        if (!Double.isFinite(meters) || !validSite(latitude, longitude, feet)) throw new IOException("Elevation unavailable");
        return feet;
    }

    static Forecast parse(JSONObject payload, String name, Forecast.Site site, Instant at) throws Exception {
        JSONObject data = payload.getJSONObject("hourly");
        JSONArray times = data.getJSONArray("time");
        int offsetSeconds = payload.optInt("utc_offset_seconds", 0);
        OffsetDateTime now = at.atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds));
        LocalDate start = now.toLocalDate();
        int[] days = new int[times.length()];
        int[] hours = new int[times.length()];
        LocalDate[] dates = new LocalDate[Forecast.DAYS];
        for (int i = 0; i < dates.length; i++) dates[i] = start.plusDays(i);
        for (int i = 0; i < times.length(); i++) {
            String time = times.getString(i);
            LocalDate date = LocalDate.parse(time.substring(0, 10));
            int endHour = Integer.parseInt(time.substring(11, 13));
            // The midnight accumulation belongs to the preceding local calendar day.
            days[i] = (int)(date.toEpochDay() - start.toEpochDay()) - (endHour == 0 ? 1 : 0);
            hours[i] = (endHour + 23) % 24;
        }

        List<Forecast.Member> members = new ArrayList<>();
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.matches("snowfall(_member[0-9]+)?")) continue;
            String suffix = key.substring("snowfall".length());
            JSONArray snowfall = data.optJSONArray(key);
            JSONArray temperature = data.optJSONArray("temperature_2m" + suffix);
            JSONArray rain = data.optJSONArray("rain" + suffix);
            JSONArray cloud = data.optJSONArray("cloud_cover" + suffix);
            JSONArray wind = data.optJSONArray("wind_speed_10m" + suffix);
            if (snowfall == null || temperature == null || rain == null || cloud == null) continue;

            Forecast.Hour[][] grid = new Forecast.Hour[Forecast.DAYS][24];
            double[] earlierSnow = new double[Forecast.STORM_LOOKBACK_DAYS * 24];
            Arrays.fill(earlierSnow, Double.NaN);
            for (int i = 0; i < days.length; i++) {
                int d = days[i];
                if (d < 0) {
                    double s = snowfall.optDouble(i);
                    if (d >= -Forecast.STORM_LOOKBACK_DAYS && Double.isFinite(s)) {
                        earlierSnow[earlierSnow.length + d * 24 + hours[i]] = Math.max(0, s);
                    }
                    continue;
                }
                if (d >= grid.length) continue;
                double s = snowfall.optDouble(i), t = temperature.optDouble(i);
                double r = rain.optDouble(i), c = cloud.optDouble(i);
                if (!Double.isFinite(t) || !Double.isFinite(s)
                        || !Double.isFinite(r) || !Double.isFinite(c)) continue;
                c = Math.max(0, Math.min(100, c));
                double windKph = wind == null ? Double.NaN : wind.optDouble(i);
                grid[d][hours[i]] = new Forecast.Hour(t, Math.max(0, s), Math.max(0, r), c,
                        solar(site.latitude(), dates[d], hours[i], c), windKph >= 0 ? windKph : Double.NaN);
            }
            members.add(new Forecast.Member(grid, earlierSnow));
        }
        return new Forecast(start, Collections.singletonList(new Forecast.Model(name, members)), now.getHour(), now.getOffset());
    }

    // Cloud-attenuated daylight approximation. Terrain shading and slope are outside this prototype.
    private static double solar(double latitude, LocalDate day, int hour, double cloud) {
        double lat = Math.toRadians(latitude);
        double declination = Math.toRadians(23.44) * Math.sin(2 * Math.PI * (day.getDayOfYear() - 80) / 365.25);
        double angle = Math.toRadians((hour + 0.5 - 12) * 15);
        double elevation = Math.sin(lat) * Math.sin(declination)
                + Math.cos(lat) * Math.cos(declination) * Math.cos(angle);
        return 850 * Math.max(0, elevation) * (1 - .75 * Math.pow(cloud / 100, 3));
    }

    static boolean validSite(double latitude, double longitude, int feet) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= LATITUDE_LIMITS[0] && latitude <= LATITUDE_LIMITS[1]
                && ((longitude >= -180 && longitude <= -65) || longitude >= 172 && longitude <= 180)
                && feet >= -1500 && feet <= 21000;
    }
}
