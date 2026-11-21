package com.snoworno.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SnowTimeline {
    static final int DAILY_DAYS = 14;
    private static final int LOOKBACK = Forecast.STORM_LOOKBACK_DAYS * 24;
    private static final int LIMIT = Forecast.DAYS * 24;
    private static final int DRY_GAP = 6;
    private static final int NO_STORM = Integer.MIN_VALUE;
    private static final double SNOW_HOUR = .01;
    private static final double EVENT_SHARE = .30;

    record Event(int first, int end, boolean mayStartEarlier, boolean mayContinue) {
        boolean contains(int hour) { return hour >= first && hour < end; }
        boolean overlaps(int firstHour, int endHour) { return first < endHour && end > firstHour; }
        boolean extended() { return end > DAILY_DAYS * 24; }
    }

    record ModelDetail(String name, int members, double median) {}
    record CumulativePoint(int hour, double low, double median, double high) {}
    private record Track(double[] inches, int[] missing) {
        double total(int start, int end) {
            start += LOOKBACK;
            end += LOOKBACK;
            return missing[end] == missing[start] ? inches[end] - inches[start] : Double.NaN;
        }
    }

    private record Model(String name, List<Track> tracks) {}
    private record Value(double amount, double weight) {}
    private record WeightedTrack(Track track, double weight) {}
    private final List<Model> models = new ArrayList<>();
    final List<Event> events = new ArrayList<>();
    final List<Event> stormEvents = new ArrayList<>();
    final int firstHour;
    private int coveredHours;

    SnowTimeline(Forecast forecast) {
        firstHour = Math.min(forecast.firstFutureHour, LIMIT);
        for (Forecast.Model source : forecast.models) {
            List<Track> tracks = new ArrayList<>();
            for (Forecast.Member member : source.members()) {
                double[] inches = new double[LOOKBACK + LIMIT + 1];
                int[] missing = new int[inches.length];
                for (int i = 0; i < inches.length - 1; i++) {
                    double snow = member.snowfallAt(i - LOOKBACK);
                    boolean available = Double.isFinite(snow);
                    inches[i + 1] = inches[i] + (available ? snow / 2.54 : 0);
                    missing[i + 1] = missing[i] + (available ? 0 : 1);
                }
                tracks.add(new Track(inches, missing));
            }
            models.add(new Model(source.name(), tracks));
        }
        detectEvents(firstHour, events);
        detectEvents(-LOOKBACK, stormEvents);
        stormEvents.removeIf(event -> event.end() <= firstHour);
    }

    private void detectEvents(int scanStart, List<Event> output) {
        int first = NO_STORM, lastSnow = NO_STORM;
        boolean startUncertain = false;
        boolean boundary = true;
        for (int hour = scanStart; hour < LIMIT; hour++) {
            Stats sample = stats(hour, hour + 1, false);
            if (!sample.available()) {
                if (first != NO_STORM) output.add(new Event(first, lastSnow + 1, startUncertain, true));
                first = NO_STORM;
                boundary = true;
            } else if (sample.snowShare(SNOW_HOUR) >= EVENT_SHARE) {
                if (first == NO_STORM) {
                    first = hour;
                    startUncertain = boundary;
                }
                lastSnow = hour;
            } else if (first != NO_STORM && hour - lastSnow > DRY_GAP) {
                output.add(new Event(first, lastSnow + 1, startUncertain, false));
                first = NO_STORM;
            }
            if (sample.available()) {
                if (output == events) coveredHours++;
                boundary = false;
            }
        }
        if (first != NO_STORM) output.add(new Event(first, lastSnow + 1, startUncertain, true));
    }

    Stats stats(int start, int end) {
        if (start < firstHour) return new Stats(end, Collections.emptyList(), Collections.emptyList());
        return stats(start, end, true);
    }

    Stats stormStats(Event event) { return stats(event.first(), event.end(), true); }

    Stats modeledSoFar(Event event) { return stats(event.first(), firstHour, true); }

    private Stats stats(int start, int end, boolean modelDetails) {
        List<Value> all = new ArrayList<>();
        List<ModelDetail> details = new ArrayList<>();
        if (start < -LOOKBACK || end > LIMIT || start >= end) return new Stats(end, all, details);
        for (Model model : models) {
            List<Track> tracks = completeTracks(model, start, end);
            if (tracks.isEmpty()) continue;
            double weight = 1.0 / tracks.size();
            List<Value> family = new ArrayList<>();
            for (Track track : tracks) family.add(new Value(track.total(start, end), weight));
            all.addAll(family);
            double median = modelDetails ? percentile(family, .5) : Double.NaN;
            details.add(new ModelDetail(model.name(), family.size(), median));
        }
        return new Stats(end, all, details);
    }

    private static List<Track> completeTracks(Model model, int start, int end) {
        List<Track> tracks = new ArrayList<>();
        for (Track track : model.tracks()) {
            if (Double.isFinite(track.total(start, end))) tracks.add(track);
        }
        return tracks.size() >= 2 && tracks.size() * 2 >= model.tracks().size() ? tracks : Collections.emptyList();
    }

    List<CumulativePoint> cumulative(Event event) {
        return cumulativeWindow(event.first(), event.end(), event.extended() ? 6 : 1);
    }

    List<CumulativePoint> cumulative(int start, int end, int step) {
        if (start < firstHour) return Collections.emptyList();
        return cumulativeWindow(start, end, step);
    }

    private List<CumulativePoint> cumulativeWindow(int start, int end, int step) {
        if (start < -LOOKBACK || end > LIMIT || start >= end || step < 1) return Collections.emptyList();
        // Keep the same members and family weights from start to finish so totals cannot fall
        // when coverage changes. The final point uses the same ensemble as the window total.
        List<WeightedTrack> ensemble = new ArrayList<>();
        for (Model model : models) {
            List<Track> tracks = completeTracks(model, start, end);
            for (Track track : tracks) ensemble.add(new WeightedTrack(track, 1.0 / tracks.size()));
        }
        if (ensemble.isEmpty()) return Collections.emptyList();

        List<CumulativePoint> points = new ArrayList<>();
        points.add(new CumulativePoint(start, 0, 0, 0));
        int next = (Math.floorDiv(start, step) + 1) * step;
        for (int hour = Math.min(next, end); ; hour = Math.min(hour + step, end)) {
            List<Value> totals = new ArrayList<>(ensemble.size());
            for (WeightedTrack member : ensemble) {
                totals.add(new Value(member.track().total(start, hour), member.weight()));
            }
            points.add(new CumulativePoint(hour, percentile(totals, .10), percentile(totals, .50), percentile(totals, .90)));
            if (hour == end) return points;
        }
    }

    int availableHours() { return coveredHours; }
    boolean hasCoverageGaps() { return coveredHours < LIMIT - firstHour; }

    Event eventAt(int hour) {
        for (Event event : events) if (event.contains(hour)) return event;
        return null;
    }

    private static double percentile(List<Value> sample, double percentile) {
        return Forecast.quantile(sample, Value::amount, Value::weight, percentile);
    }

    static final class Stats {
        final int end;
        final List<ModelDetail> models;
        private final List<Value> samples;

        private Stats(int end, List<Value> samples, List<ModelDetail> models) {
            this.end = end;
            this.samples = samples;
            this.models = models;
        }

        boolean available() { return !samples.isEmpty(); }
        int count() { return samples.size(); }
        double amount(double q) { return percentile(samples, q); }
        double snowShare(double threshold) {
            return !available() ? 0 : samples.stream().filter(v -> v.amount() >= threshold - 1e-9)
                    .mapToDouble(Value::weight).sum() / models.size();
        }

        double[] buckets() {
            double[] bins = new double[Forecast.BIN_LABELS.length];
            for (Value sample : samples) {
                int bucket = 0;
                while (bucket < Forecast.BINS.length && sample.amount() >= Forecast.BINS[bucket] - 1e-9) bucket++;
                bins[bucket] += sample.weight() / models.size();
            }
            return bins;
        }

        Forecast.Confidence confidence() {
            double median = amount(.5);
            double agreement = models.stream().mapToDouble(ModelDetail::median).max().orElse(0)
                    - models.stream().mapToDouble(ModelDetail::median).min().orElse(0);
            return Forecast.snowfallConfidence(models.size(), end > DAILY_DAYS * 24, end > 7 * 24,
                    agreement > Math.max(.5, .35 * median), median, amount(.90) - amount(.10));
        }
    }
}
