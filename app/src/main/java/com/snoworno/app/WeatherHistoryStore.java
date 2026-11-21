package com.snoworno.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class WeatherHistoryStore extends SQLiteOpenHelper {
    private static final String BACKUP_HEADER = "SnowOrNo history 1";
    private static final String COLUMNS = "time,t,r,s,d,w,g,c,k,source";
    private static final String INSERT = " INTO weather (place," + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)";
    private static final long MAX_BACKUP_BYTES = 256L * 1024 * 1024;

    record PlaceKey(int lat, int lon, int feet) {
        static PlaceKey from(Forecast.Site site) {
            return new PlaceKey((int)Math.round(site.latitude() * 1_000_000),
                    (int)Math.round(site.longitude() * 1_000_000), site.feet());
        }

        String[] args() { return new String[]{Integer.toString(lat), Integer.toString(lon), Integer.toString(feet)}; }
    }

    record Month(ZoneId zone, Map<LocalDate, HistoricalWeather.Day> days) {}

    WeatherHistoryStore(Context context) {
        super(context.getApplicationContext(), "weather-history.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE places (id INTEGER PRIMARY KEY, lat INTEGER NOT NULL, lon INTEGER NOT NULL,"
                + " feet INTEGER NOT NULL, zone TEXT NOT NULL, UNIQUE(lat,lon,feet))");
        db.execSQL("CREATE TABLE weather (place INTEGER NOT NULL, time INTEGER NOT NULL, t INTEGER NOT NULL,"
                + " r INTEGER NOT NULL, s INTEGER NOT NULL, d INTEGER NOT NULL, w INTEGER NOT NULL,"
                + " g INTEGER NOT NULL, c INTEGER NOT NULL, k INTEGER NOT NULL, source INTEGER NOT NULL,"
                + " PRIMARY KEY(place,time)) WITHOUT ROWID");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported weather history schema");
    }

    Month read(Forecast.Site site, LocalDate first, LocalDate end, ZoneId fallback) {
        SQLiteDatabase db = getReadableDatabase();
        long place;
        ZoneId zone;
        try (Cursor result = db.rawQuery("SELECT id,zone FROM places WHERE lat=? AND lon=? AND feet=?",
                PlaceKey.from(site).args())) {
            if (!result.moveToFirst()) return new Month(fallback, new HashMap<>());
            place = result.getLong(0);
            zone = ZoneId.of(result.getString(1));
        }
        Map<LocalDate, List<HistoricalWeather.Hour>> hours = new HashMap<>();
        try (Cursor rows = db.rawQuery("SELECT " + COLUMNS + " FROM weather WHERE place=? AND time>=? AND time<? ORDER BY time",
                new String[]{Long.toString(place), Long.toString(first.atStartOfDay(zone).toEpochSecond()),
                        Long.toString(end.atStartOfDay(zone).toEpochSecond())})) {
            while (rows.moveToNext()) {
                HistoricalWeather.Hour hour = hour(rows, 0);
                LocalDate day = Instant.ofEpochSecond(hour.time()).atZone(zone).toLocalDate();
                hours.computeIfAbsent(day, date -> new ArrayList<>()).add(hour);
            }
        }
        Map<LocalDate, HistoricalWeather.Day> days = new HashMap<>();
        hours.forEach((date, values) -> days.put(date, HistoricalWeather.summarize(date, zone, values)));
        return new Month(zone, days);
    }

    void save(Forecast.Site site, HistoricalWeather.Batch batch) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try (var insert = db.compileStatement("INSERT OR REPLACE" + INSERT);
             var fill = db.compileStatement("INSERT OR IGNORE" + INSERT)) {
            long place = place(db, PlaceKey.from(site), batch.zone());
            for (var hour : batch.hours()) put(hour.source() == HistoricalWeather.REANALYSIS ? insert : fill, place, hour);
            prune(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static long place(SQLiteDatabase db, PlaceKey key, ZoneId zone) {
        db.execSQL("INSERT OR IGNORE INTO places (lat,lon,feet,zone) VALUES (?,?,?,?)",
                new Object[]{key.lat(), key.lon(), key.feet(), zone.getId()});
        try (Cursor result = db.rawQuery("SELECT id FROM places WHERE lat=? AND lon=? AND feet=?", key.args())) {
            if (!result.moveToFirst()) throw new IllegalStateException("Weather location not saved");
            return result.getLong(0);
        }
    }

    private static long oldestHour() {
        return LocalDate.now(ZoneOffset.UTC).minusYears(HistoricalWeather.YEARS).minusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    private static void prune(SQLiteDatabase db) {
        db.execSQL("DELETE FROM weather WHERE time < ?", new Object[]{oldestHour()});
    }

    void exportTo(OutputStream output, String storms) throws Exception {
        long oldest = oldestHour();
        try (var writer = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(output), StandardCharsets.UTF_8));
             Cursor rows = getReadableDatabase().rawQuery("SELECT p.lat,p.lon,p.feet,p.zone," + COLUMNS
                     + " FROM weather JOIN places p ON weather.place=p.id WHERE time>=? ORDER BY weather.place,time",
                     new String[]{Long.toString(oldest)})) {
            writer.write(BACKUP_HEADER);
            writer.newLine();
            writer.write(storms);
            writer.newLine();
            while (rows.moveToNext()) {
                var values = new JSONArray().put(rows.getInt(0)).put(rows.getInt(1)).put(rows.getInt(2)).put(rows.getString(3));
                for (int i = 4; i < 14; i++) values.put(rows.getLong(i));
                writer.write(values.toString());
                writer.newLine();
            }
        }
    }

    StormFeedback importFrom(InputStream input) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(new LimitedBackup(new GZIPInputStream(input)), StandardCharsets.UTF_8))) {
            if (!BACKUP_HEADER.equals(reader.readLine())) throw new IOException("Unsupported history backup");
            String serialized = reader.readLine();
            if (serialized == null) throw new IOException("Missing storm history");
            StormFeedback storms = StormFeedback.readBackup(serialized);
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try (var insert = db.compileStatement("INSERT OR IGNORE" + INSERT)) {
                Map<PlaceKey, Long> places = new HashMap<>();
                long oldest = oldestHour(), now = Instant.now().getEpochSecond();
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONArray values = new JSONArray(line);
                    if (values.length() != 14) throw new IOException("Invalid weather history record");
                    var key = new PlaceKey(values.getInt(0), values.getInt(1), values.getInt(2));
                    if (!ForecastSource.validSite(key.lat() / 1_000_000.0, key.lon() / 1_000_000.0, key.feet())) {
                        throw new IOException("Invalid weather history location");
                    }
                    ZoneId zone = ZoneId.of(values.getString(3));
                    var hour = new HistoricalWeather.Hour(values.getLong(4), values.getInt(5), values.getInt(6),
                            values.getInt(7), values.getInt(8), values.getInt(9), values.getInt(10), values.getInt(11),
                            values.getInt(12), values.getInt(13));
                    if (hour.temperature() < -1000 || hour.temperature() > 700 || hour.time() > now
                            || hour.source() < HistoricalWeather.RECENT || hour.source() > HistoricalWeather.REANALYSIS) {
                        throw new IOException("Invalid historical weather values");
                    }
                    if (hour.time() < oldest) continue;
                    long place = places.computeIfAbsent(key, value -> place(db, value, zone));
                    put(insert, place, hour);
                }
                prune(db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return storms;
        }
    }

    private static HistoricalWeather.Hour hour(Cursor row, int column) {
        return new HistoricalWeather.Hour(row.getLong(column), row.getInt(column + 1), row.getInt(column + 2),
                row.getInt(column + 3), row.getInt(column + 4), row.getInt(column + 5), row.getInt(column + 6),
                row.getInt(column + 7), row.getInt(column + 8), row.getInt(column + 9));
    }

    private static void put(SQLiteStatement insert, long place, HistoricalWeather.Hour hour) {
        long[] values = {place, hour.time(), hour.temperature(), hour.rain(), hour.snow(), hour.depth(),
                hour.wind(), hour.gust(), hour.cloud(), hour.code(), hour.source()};
        for (int i = 0; i < values.length; i++) insert.bindLong(i + 1, values[i]);
        insert.executeInsert();
    }

    private static final class LimitedBackup extends FilterInputStream {
        private long size;

        LimitedBackup(InputStream stream) { super(stream); }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0) count(read);
            return read;
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) count(1);
            return value;
        }

        private void count(int count) throws IOException {
            size += count;
            if (size > MAX_BACKUP_BYTES) throw new IOException("History backup is too large");
        }
    }
}
