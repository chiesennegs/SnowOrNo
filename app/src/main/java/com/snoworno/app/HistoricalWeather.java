package com.snoworno.app;

import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class HistoricalWeather {
    static final int YEARS = 10;
    static final int MISSING = Integer.MIN_VALUE;
    static final int RECENT = 1, REANALYSIS = 2;
    private static final String[] FIELDS = {"temperature_2m", "rain", "snowfall", "snow_depth",
            "wind_speed_10m", "wind_gusts_10m", "cloud_cover", "weather_code"};

    record Hour(long time, int temperature, int rain, int snow, int depth, int wind, int gust,
                int cloud, int code, int source) {}
    record Batch(ZoneId zone, List<Hour> hours) {}

    record Day(List<Hour> hours, double lowF, double highF, double rainIn, double snowIn,
               Forecast.Weather condition, boolean recent, int expectedHours) {
        boolean complete() { return hours.size() == expectedHours; }
    }

    static LocalDate recentStart(LocalDate today) { return today.minusDays(6); }

    static Batch fetch(Forecast.Site site, LocalDate first, LocalDate last, boolean recent) throws Exception {
        String endpoint = recent ? "api.open-meteo.com/v1/forecast" : "archive-api.open-meteo.com/v1/archive";
        // Pad the fixed-offset API time series, then assign hours using the site's actual zone rules.
        String url = String.format(Locale.US, "https://%s?latitude=%.6f&longitude=%.6f&elevation=%.1f"
                        + "&start_date=%s&end_date=%s&timezone=auto&timeformat=unixtime&hourly=%s%s",
                endpoint, site.latitude(), site.longitude(), site.feet() * .3048,
                first.minusDays(1), last.plusDays(1), String.join(",", FIELDS), recent ? "" : "&models=era5_seamless");
        return parse(ForecastSource.read(url), first, last, recent ? RECENT : REANALYSIS);
    }

    static Batch parse(JSONObject payload, LocalDate first, LocalDate last, int source) throws Exception {
        ZoneId zone = ZoneId.of(payload.getString("timezone"));
        var hourly = payload.getJSONObject("hourly");
        var times = hourly.getJSONArray("time");
        var fields = new org.json.JSONArray[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) fields[i] = hourly.optJSONArray(FIELDS[i]);
        List<Hour> samples = new ArrayList<>();
        for (int i = 0; i + 1 < times.length(); i++) {
            long time = times.getLong(i);
            LocalDate date = Instant.ofEpochSecond(time).atZone(zone).toLocalDate();
            if (date.isBefore(first) || date.isAfter(last)) continue;
            // Rain and snowfall are the preceding hour at each API timestamp.
            boolean nextHour = times.getLong(i + 1) - time == 3600;
            int temperature = scaled(fields[0], i, 10);
            if (temperature == MISSING) continue;
            samples.add(new Hour(time, temperature,
                    nextHour ? scaled(fields[1], i + 1, 10) : MISSING,
                    nextHour ? scaled(fields[2], i + 1, 10) : MISSING, scaled(fields[3], i, 1000),
                    scaled(fields[4], i, 10), scaled(fields[5], i, 10), scaled(fields[6], i, 1),
                    scaled(fields[7], i, 1), source));
        }
        if (samples.isEmpty()) throw new IOException("No historical hours available");
        return new Batch(zone, samples);
    }

    private static int scaled(org.json.JSONArray values, int index, int factor) {
        double value = values == null ? Double.NaN : values.optDouble(index);
        return Double.isFinite(value) ? (int)Math.round(value * factor) : MISSING;
    }

    static Day summarize(LocalDate date, ZoneId zone, List<Hour> hours) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        double rain = 0, snow = 0, clouds = 0;
        int rainHours = 0, snowHours = 0, cloudHours = 0;
        boolean recent = false, ice = false;
        for (Hour hour : hours) {
            double temp = Forecast.fahrenheit(hour.temperature() / 10.0);
            low = Math.min(low, temp);
            high = Math.max(high, temp);
            if (hour.rain() != MISSING) { rain += hour.rain(); rainHours++; }
            if (hour.snow() != MISSING) { snow += hour.snow(); snowHours++; }
            if (hour.cloud() != MISSING) { clouds += hour.cloud(); cloudHours++; }
            ice |= hour.code() == 56 || hour.code() == 57 || hour.code() == 66 || hour.code() == 67
                    || (hour.temperature() <= 0 && hour.rain() > 0);
            recent |= hour.source() == RECENT;
        }
        int expected = (int)((date.plusDays(1).atStartOfDay(zone).toEpochSecond()
                - date.atStartOfDay(zone).toEpochSecond()) / 3600);
        if (hours.isEmpty()) return new Day(hours, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, false, expected);
        boolean snowy = snow > 0, rainy = rain > 0;
        double cloud = cloudHours == 0 ? Double.NaN : clouds / cloudHours;
        Forecast.Weather condition = ice ? Forecast.Weather.ICE
                : snowy && rainy ? Forecast.Weather.MIXED : snowy ? Forecast.Weather.SNOW : rainy ? Forecast.Weather.RAIN
                : !Double.isFinite(cloud) ? null : cloud >= 70 ? Forecast.Weather.CLOUDY
                : cloud >= 45 ? Forecast.Weather.PARTLY_CLOUDY : cloud >= 20 ? Forecast.Weather.PARTLY_SUNNY : Forecast.Weather.SUNNY;
        return new Day(hours, low, high, rainHours == hours.size() ? rain / 254.0 : Double.NaN,
                snowHours == hours.size() ? snow / 25.4 : Double.NaN, condition, recent, expected);
    }
}
