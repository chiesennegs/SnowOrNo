package com.snoworno.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

final class StormFeedback {
    static final int MIN_TUNING_STORMS = 5;
    private static final Duration TUNING_LEAD = Duration.ofDays(7);
    private final List<Entry> entries = new ArrayList<>();

    record ModelEstimate(String name, double median) {}
    record ForecastPoint(int hour, double median) {}
    record Progress(Instant through, double measured, double forecastMedian) {}
    record Snapshot(Instant retrieved, Instant start, Instant end, ZoneOffset offset, double low,
                    double median, double high, String models, List<ModelEstimate> estimates, boolean complete,
                    List<ForecastPoint> curve) {
        long overlap(Instant first, Instant last) {
            Instant from = start.isAfter(first) ? start : first;
            Instant until = end.isBefore(last) ? end : last;
            return Math.max(0, Duration.between(from, until).getSeconds());
        }

        Snapshot withCurve(SnowTimeline timeline, SnowTimeline.Event event) {
            List<ForecastPoint> points = new ArrayList<>();
            for (SnowTimeline.CumulativePoint point : timeline.cumulative(event)) {
                points.add(new ForecastPoint(point.hour() - event.first(), point.median()));
            }
            return new Snapshot(retrieved, start, end, offset, low, median, high, models, estimates, complete, points);
        }

        double medianThrough(Instant through) {
            if (!complete || !retrieved.isBefore(start) || through.isBefore(start) || through.isAfter(end)) return Double.NaN;
            if (through.equals(end)) return median;
            double hour = Duration.between(start, through).getSeconds() / 3600.0;
            for (int i = 1; i < curve.size(); i++) {
                ForecastPoint before = curve.get(i - 1), after = curve.get(i);
                if (hour < before.hour() || hour > after.hour()) continue;
                double share = (hour - before.hour()) / (after.hour() - before.hour());
                return before.median() + share * (after.median() - before.median());
            }
            return Double.NaN;
        }

        boolean eligible() {
            return complete && Double.isFinite(median) && retrieved.isBefore(start)
                    && Duration.between(retrieved, start).compareTo(TUNING_LEAD) <= 0;
        }
    }

    record DailyPeriod(Instant start, Instant end) {}

    record DailyLog(int cutoffMinutes, Map<Instant, Double> amounts) {
        static DailyLog empty() { return new DailyLog(0, Collections.emptyMap()); }
    }

    record Entry(String id, Forecast.Site site, Snapshot original, Snapshot latest, Snapshot verification,
                 Double wholeStorm, DailyLog daily) {
        Snapshot measurementWindow() { return verification == null ? original : verification; }
        boolean hasObservations() { return wholeStorm != null || !daily.amounts().isEmpty(); }
        double dailyTotal() { return daily.amounts().values().stream().mapToDouble(Double::doubleValue).sum(); }

        Double measured() {
            if (wholeStorm != null) return wholeStorm;
            if (daily.amounts().isEmpty()) return null;
            List<DailyPeriod> periods = dailyPeriods();
            return daily.amounts().size() == periods.size()
                    && periods.stream().allMatch(period -> daily.amounts().containsKey(period.start())) ? dailyTotal() : null;
        }

        Progress progress(Instant now) {
            Snapshot window = measurementWindow();
            if (wholeStorm != null && !now.isBefore(window.end())) {
                return new Progress(window.end(), wholeStorm, window.medianThrough(window.end()));
            }
            if (daily.amounts().isEmpty()) return null;
            Instant through = null;
            double total = 0;
            for (DailyPeriod period : dailyPeriods()) {
                Double reading = daily.amounts().get(period.start());
                if (reading == null || now.isBefore(period.end())) break;
                total += reading;
                through = period.end();
            }
            return through == null ? null : new Progress(through, total, window.medianThrough(through));
        }

        List<DailyPeriod> dailyPeriods() {
            Snapshot window = measurementWindow();
            List<DailyPeriod> periods = new ArrayList<>();
            var localStart = window.start().atOffset(window.offset());
            LocalTime cutoff = LocalTime.of(daily.cutoffMinutes() / 60, daily.cutoffMinutes() % 60);
            var boundary = localStart.toLocalDate().atTime(cutoff).atOffset(window.offset());
            if (!boundary.toInstant().isAfter(window.start())) boundary = boundary.plusDays(1);
            Instant start = window.start();
            while (start.isBefore(window.end())) {
                Instant next = boundary.toInstant();
                Instant end = next.isBefore(window.end()) ? next : window.end();
                periods.add(new DailyPeriod(start, end));
                start = end;
                boundary = boundary.plusDays(1);
            }
            return periods;
        }
    }

    record ModelScore(String name, int count, double meanAbsoluteError, double meanError) {}
    record Review(int measured, int eligible, double meanError, double multiplier, List<ModelScore> models) {}

    private static final class Errors {
        int count;
        double absolute, signed;
        void add(double error) {
            count++;
            absolute += Math.abs(error);
            signed += error;
        }
    }

    static StormFeedback read(String serialized) {
        StormFeedback log = new StormFeedback();
        try {
            JSONArray rows = new JSONArray(serialized);
            for (int i = 0; i < rows.length(); i++) {
                try {
                    JSONObject row = rows.getJSONObject(i);
                    var site = new Forecast.Site(row.getString("name"), "", row.getDouble("lat"),
                            row.getDouble("lon"), row.getInt("feet"));
                    if (!ForecastSource.validSite(site.latitude(), site.longitude(), site.feet())) continue;

                    boolean legacy = !row.has("original");
                    Snapshot original = readSnapshot(legacy ? row : row.getJSONObject("original"));
                    Snapshot latest = row.has("latest") ? readSnapshot(row.getJSONObject("latest")) : original;
                    Snapshot verification = row.has("verification") ? readSnapshot(row.getJSONObject("verification"))
                            : legacy && original.eligible() ? original : null;
                    Double measured = row.isNull("measured") ? null : row.getDouble("measured");
                    if (measured == null || validAmount(measured)) {
                        log.entries.add(new Entry(row.getString("id"), site, original, latest, verification, measured,
                                readDaily(row)));
                    }
                } catch (JSONException | java.time.DateTimeException ignored) {
                    // Keep the other observations if one saved record is unreadable.
                }
            }
        } catch (JSONException ignored) { }
        return log;
    }

    static StormFeedback readBackup(String serialized) throws JSONException {
        StormFeedback log = read(serialized);
        if (log.entries.size() != new JSONArray(serialized).length()) throw new JSONException("Invalid storm history");
        return log;
    }

    void merge(StormFeedback imported) {
        for (Entry incoming : imported.entries) {
            int index = -1;
            for (int i = 0; i < entries.size(); i++) {
                Entry local = entries.get(i);
                if (local.id().equals(incoming.id()) && FavoriteLocations.samePoint(local.site(), incoming.site())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) index = matchingEntry(incoming.site(), incoming.latest().start(), incoming.latest().end(), Collections.emptySet());
            if (index < 0) {
                entries.add(incoming);
            } else {
                Entry local = entries.get(index);
                Entry base = !local.hasObservations() && incoming.hasObservations() ? incoming : local;
                Snapshot latest = incoming.latest().retrieved().isAfter(local.latest().retrieved()) ? incoming.latest() : local.latest();
                entries.set(index, new Entry(local.id(), local.site(), base.original(), latest, base.verification(),
                        base.wholeStorm(), base.daily()));
            }
        }
    }

    private static DailyLog readDaily(JSONObject row) {
        int cutoff = row.optInt("dailyCutoff", 0);
        if (cutoff < 0 || cutoff >= 24 * 60) return DailyLog.empty();
        JSONArray values = row.optJSONArray("daily");
        Map<Instant, Double> measurements = new HashMap<>();
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                JSONObject observation = values.optJSONObject(i);
                if (observation == null || !observation.has("start")) continue;
                double inches = observation.optDouble("inches");
                if (validAmount(inches)) measurements.put(Instant.ofEpochMilli(observation.optLong("start")), inches);
            }
        }
        return new DailyLog(cutoff, measurements);
    }

    private static Snapshot readSnapshot(JSONObject row) throws JSONException {
        Instant start = time(row, "start"), end = time(row, "end");
        if (!start.isBefore(end)) throw new JSONException("Empty storm window");
        JSONArray models = row.optJSONArray("estimates");
        List<ModelEstimate> estimates = new ArrayList<>();
        if (models != null) {
            for (int i = 0; i < models.length(); i++) {
                JSONObject estimate = models.getJSONObject(i);
                estimates.add(new ModelEstimate(estimate.getString("name"), estimate.getDouble("median")));
            }
        }
        List<ForecastPoint> curve = new ArrayList<>();
        JSONArray points = row.optJSONArray("curve");
        if (points != null) {
            for (int i = 0; i < points.length(); i++) {
                JSONArray point = points.optJSONArray(i);
                if (point == null) break;
                int hour = point.optInt(0, -1);
                double median = point.optDouble(1);
                if (!validAmount(median) || (i == 0 ? hour != 0 : hour <= curve.get(i - 1).hour())) break;
                curve.add(new ForecastPoint(hour, median));
            }
        }
        return new Snapshot(time(row, "retrieved"), start, end, ZoneOffset.ofTotalSeconds(row.getInt("offset")),
                row.optDouble("low"), row.optDouble("median"), row.optDouble("high"), row.getString("models"),
                estimates, row.optBoolean("complete", true), curve);
    }

    boolean archive(Forecast.Site site, Forecast forecast, Instant retrieved, SnowTimeline timeline) {
        Set<String> matched = new HashSet<>();
        boolean changed = false;
        for (SnowTimeline.Event event : timeline.stormEvents) {
            SnowTimeline.Stats stats = timeline.stormStats(event);
            var midnight = forecast.start.atStartOfDay();
            Instant start = midnight.plusHours(event.first()).toInstant(forecast.offset);
            Instant end = midnight.plusHours(event.end()).toInstant(forecast.offset);
            List<ModelEstimate> estimates = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (SnowTimeline.ModelDetail model : stats.models) {
                estimates.add(new ModelEstimate(model.name(), model.median()));
                names.add(model.name());
            }
            Snapshot snapshot = new Snapshot(retrieved, start, end, forecast.offset, stats.amount(.10),
                    stats.amount(.50), stats.amount(.90), String.join(" + ", names), estimates,
                    !event.mayStartEarlier() && !event.mayContinue(), Collections.emptyList());
            int index = matchingEntry(site, start, end, matched);
            if (index < 0) {
                if (snapshot.complete() && retrieved.isBefore(start) && !event.extended()) {
                    snapshot = snapshot.withCurve(timeline, event);
                }
                Entry entry = new Entry(UUID.randomUUID().toString(), site, snapshot, snapshot,
                        snapshot.eligible() ? snapshot : null, null, DailyLog.empty());
                entries.add(entry);
                matched.add(entry.id());
                changed = true;
            } else {
                Entry entry = entries.get(index);
                matched.add(entry.id());
                if (!retrieved.isAfter(entry.latest().retrieved())) continue;
                Snapshot verify = entry.verification();
                if (verify == null && !entry.hasObservations() && snapshot.eligible()) {
                    if (!event.extended()) snapshot = snapshot.withCurve(timeline, event);
                    verify = snapshot;
                }
                entries.set(index, new Entry(entry.id(), entry.site(), entry.original(), snapshot, verify,
                        entry.wholeStorm(), entry.daily()));
                changed = true;
            }
        }
        return changed;
    }

    Entry matchingEntry(Forecast.Site site, Instant start, Instant end) {
        int index = matchingEntry(site, start, end, Collections.emptySet());
        return index < 0 ? null : entries.get(index);
    }

    private int matchingEntry(Forecast.Site site, Instant start, Instant end, Set<String> matched) {
        int index = -1;
        long mostOverlap = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (matched.contains(e.id()) || !FavoriteLocations.samePoint(site, e.site())) continue;
            long overlap = e.latest().overlap(start, end);
            if (overlap > mostOverlap) {
                index = i;
                mostOverlap = overlap;
            }
        }
        return index;
    }

    List<Entry> location(Forecast.Site site) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) if (FavoriteLocations.samePoint(site, entry.site())) result.add(entry);
        result.sort(Comparator.comparing((Entry e) -> e.measurementWindow().start()).reversed());
        return result;
    }

    static NavigableMap<LocalDate, List<Entry>> calendarDates(List<Entry> entries, LocalDate before) {
        NavigableMap<LocalDate, List<Entry>> dates = new TreeMap<>();
        for (Entry entry : entries) {
            Set<LocalDate> days = new HashSet<>();
            addWindowDates(days, entry.original(), before);
            addWindowDates(days, entry.latest(), before);
            if (entry.verification() != null) addWindowDates(days, entry.verification(), before);
            for (LocalDate day : days) dates.computeIfAbsent(day, key -> new ArrayList<>()).add(entry);
        }
        return dates;
    }

    private static void addWindowDates(Set<LocalDate> dates, Snapshot window, LocalDate before) {
        LocalDate first = window.start().atOffset(window.offset()).toLocalDate();
        LocalDate last = window.end().minusNanos(1).atOffset(window.offset()).toLocalDate();
        for (LocalDate day = first; !day.isAfter(last) && day.isBefore(before); day = day.plusDays(1)) {
            dates.add(day);
        }
    }

    boolean observe(String id, Double snowfallInches, Instant now) {
        if (snowfallInches != null && !validAmount(snowfallInches)) return false;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.id().equals(id) || !e.daily().amounts().isEmpty() || now.isBefore(e.measurementWindow().end())) continue;
            entries.set(i, new Entry(e.id(), e.site(), e.original(), e.latest(), e.verification(), snowfallInches, e.daily()));
            return true;
        }
        return false;
    }

    boolean dailyCutoff(String id, int minutes) {
        if (minutes < 0 || minutes >= 24 * 60) return false;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.id().equals(id) || e.hasObservations()) continue;
            entries.set(i, new Entry(e.id(), e.site(), e.original(), e.latest(), e.verification(), null,
                    new DailyLog(minutes, Collections.emptyMap())));
            return true;
        }
        return false;
    }

    boolean observeDay(String id, DailyPeriod period, Double inches, Instant now) {
        if ((inches != null && !validAmount(inches)) || now.isBefore(period.end())) return false;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.id().equals(id) || e.wholeStorm() != null || !e.dailyPeriods().contains(period)) continue;
            Map<Instant, Double> amounts = new HashMap<>(e.daily().amounts());
            if (inches == null) amounts.remove(period.start());
            else amounts.put(period.start(), inches);
            entries.set(i, new Entry(e.id(), e.site(), e.original(), e.latest(), e.verification(), null,
                    new DailyLog(e.daily().cutoffMinutes(), amounts)));
            return true;
        }
        return false;
    }

    Review review(Forecast.Site site) {
        int measured = 0;
        double error = 0;
        List<Double> ratios = new ArrayList<>();
        Map<String, Errors> modelErrors = new HashMap<>();
        for (Entry entry : location(site)) {
            if (entry.measured() == null) continue;
            measured++;
            Snapshot verify = entry.verification();
            if (verify == null) continue;
            for (ModelEstimate estimate : verify.estimates()) {
                modelErrors.computeIfAbsent(estimate.name(), key -> new Errors())
                        .add(entry.measured() - estimate.median());
            }
            if (verify.median() < .25) continue;
            error += entry.measured() - verify.median();
            ratios.add(entry.measured() / verify.median());
        }
        int count = ratios.size();
        double factor = Double.NaN;
        if (count >= MIN_TUNING_STORMS) {
            Collections.sort(ratios);
            double median = (ratios.get((count - 1) / 2) + ratios.get(count / 2)) / 2;
            factor = Math.max(.75, Math.min(1.25, median));
        }
        List<ModelScore> scores = new ArrayList<>();
        modelErrors.forEach((name, values) -> scores.add(new ModelScore(name, values.count,
                values.absolute / values.count, values.signed / values.count)));
        scores.sort(Comparator.comparingDouble(ModelScore::meanAbsoluteError).thenComparing(ModelScore::name));
        return new Review(measured, count, count == 0 ? Double.NaN : error / count, factor, scores);
    }

    String serialize() throws JSONException {
        JSONArray rows = new JSONArray();
        for (Entry e : entries) {
            var row = new JSONObject().put("id", e.id()).put("name", e.site().name()).put("lat", e.site().latitude())
                    .put("lon", e.site().longitude()).put("feet", e.site().feet()).put("original", writeSnapshot(e.original()));
            if (!e.latest().equals(e.original())) row.put("latest", writeSnapshot(e.latest()));
            if (e.verification() != null) row.put("verification", writeSnapshot(e.verification()));
            if (e.wholeStorm() != null) row.put("measured", e.wholeStorm());
            if (e.daily().cutoffMinutes() != 0) row.put("dailyCutoff", e.daily().cutoffMinutes());
            if (!e.daily().amounts().isEmpty()) {
                JSONArray observations = new JSONArray();
                for (var observation : e.daily().amounts().entrySet()) {
                    observations.put(new JSONObject().put("start", observation.getKey().toEpochMilli())
                            .put("inches", observation.getValue()));
                }
                row.put("daily", observations);
            }
            rows.put(row);
        }
        return rows.toString();
    }

    private static JSONObject writeSnapshot(Snapshot s) throws JSONException {
        JSONArray estimates = new JSONArray();
        for (ModelEstimate model : s.estimates()) {
            estimates.put(new JSONObject().put("name", model.name()).put("median", model.median()));
        }
        JSONObject row = new JSONObject().put("retrieved", s.retrieved().toEpochMilli()).put("start", s.start().toEpochMilli())
                .put("end", s.end().toEpochMilli()).put("offset", s.offset().getTotalSeconds())
                .put("low", jsonAmount(s.low())).put("median", jsonAmount(s.median()))
                .put("high", jsonAmount(s.high())).put("models", s.models())
                .put("estimates", estimates).put("complete", s.complete());
        if (!s.curve().isEmpty()) {
            JSONArray curve = new JSONArray();
            for (ForecastPoint point : s.curve()) curve.put(new JSONArray().put(point.hour()).put(point.median()));
            row.put("curve", curve);
        }
        return row;
    }

    private static Object jsonAmount(double value) { return Double.isFinite(value) ? value : JSONObject.NULL; }

    private static Instant time(JSONObject row, String key) throws JSONException {
        return Instant.ofEpochMilli(row.getLong(key));
    }

    private static boolean validAmount(double inches) { return Double.isFinite(inches) && inches >= 0; }
}
