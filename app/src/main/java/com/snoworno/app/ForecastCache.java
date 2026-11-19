package com.snoworno.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class ForecastCache {
    private static final Duration MAX_AGE = Duration.ofHours(48);
    private static final int MAX_LOCATIONS = 12;
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final Object WRITE_LOCK = new Object();
    private final File directory;

    ForecastCache(File directory) { this.directory = directory; }

    ForecastSource.Download read(Forecast.Site site, Instant now) {
        File file = file(site);
        if (!file.isFile() || !usableTime(Instant.ofEpochMilli(file.lastModified()), now)) return null;
        try (var input = new GZIPInputStream(new FileInputStream(file));
             var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new IOException("Forecast cache entry too large");
                output.write(buffer, 0, count);
            }
            JSONObject record = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            Instant retrieved = Instant.ofEpochMilli(record.getLong("retrieved"));
            if (!usableTime(retrieved, now)) return null;
            JSONArray saved = record.getJSONArray("feeds");
            List<ForecastSource.Feed> feeds = new ArrayList<>();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.getJSONObject(i);
                feeds.add(new ForecastSource.Feed(item.getString("name"), item.getJSONObject("payload")));
            }
            return ForecastSource.assemble(feeds, site, retrieved, now);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean usableTime(Instant retrieved, Instant now) {
        Duration age = Duration.between(retrieved, now);
        return !age.isNegative() && age.compareTo(MAX_AGE) < 0;
    }

    void write(Forecast.Site site, ForecastSource.Download forecast) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Forecast cache directory unavailable");
        }
        JSONArray feeds = new JSONArray();
        for (ForecastSource.Feed feed : forecast.feeds()) {
            feeds.put(new JSONObject().put("name", feed.name()).put("payload", feed.payload()));
        }
        JSONObject record = new JSONObject().put("retrieved", forecast.retrievedAt().toEpochMilli()).put("feeds", feeds);
        byte[] serialized = record.toString().getBytes(StandardCharsets.UTF_8);
        if (serialized.length > MAX_BYTES) throw new IOException("Forecast cache entry too large");

        File temporary = File.createTempFile("writing-", ".tmp", directory);
        try {
            try (var output = new GZIPOutputStream(new FileOutputStream(temporary))) {
                output.write(serialized);
            }
            synchronized (WRITE_LOCK) {
                File target = file(site);
                Instant received = forecast.retrievedAt();
                if (Thread.currentThread().isInterrupted() || target.lastModified() > received.toEpochMilli()) return;
                // File age provides a fast expiry check before reading and inflating the payload.
                Files.setLastModifiedTime(temporary.toPath(), java.nio.file.attribute.FileTime.from(received));
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                prune(received);
            }
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private File file(Forecast.Site site) {
        String key = String.format(Locale.US, "v1_%.6f_%.6f_%d.gz", site.latitude(), site.longitude(), site.feet());
        return new File(directory, key);
    }

    private void prune(Instant now) {
        File[] entries = directory.listFiles((dir, name) -> name.startsWith("v1_") && name.endsWith(".gz"));
        if (entries == null) return;
        Arrays.sort(entries, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = 0; i < entries.length; i++) {
            if (i >= MAX_LOCATIONS || Instant.ofEpochMilli(entries[i].lastModified()).isBefore(now.minus(MAX_AGE))) {
                entries[i].delete();
            }
        }
    }
}
