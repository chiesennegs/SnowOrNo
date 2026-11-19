package com.snoworno.app;

import java.util.ArrayList;
import java.util.List;

final class SnowTimeline {
    static final int DAILY_DAYS = 14;
    private static final int LIMIT = Forecast.DAYS * 24;
    private static final int DRY_GAP = 6;
    private static final double SNOW_HOUR = .01;
    private static final double EVENT_SHARE = .30;

    record Event(int first, int end, boolean mayStartEarlier, boolean mayContinue) {
        boolean contains(int hour) { return hour >= first && hour < end; }
        boolean overlaps(int firstHour, int endHour) { return first < endHour && end > firstHour; }
        boolean extended() { return end > DAILY_DAYS * 24; }
    }

    record ModelDetail(String name, int members, double median) {}
    private record Track(double[] inches, int[] missing) {
        double total(int start, int end) {
            return missing[end] == missing[start] ? inches[end] - inches[start] : Double.NaN;
        }
    }

    private record Model(String name, List<Track> tracks) {}
    private record Value(double amount, double weight) {}
    private final List<Model> models = new ArrayList<>();
    final List<Event> events = new ArrayList<>();
    final int firstHour;
    private int coveredHours;

    SnowTimeline(Forecast forecast) {
        firstHour = Math.min(forecast.firstFutureHour, LIMIT);
        for (Forecast.Model source : forecast.models) {
            List<Track> tracks = new ArrayList<>();
            for (Forecast.Member member : source.members()) {
                double[] inches = new double[LIMIT + 1];
                int[] missing = new int[LIMIT + 1];
                for (int hour = 0; hour < LIMIT; hour++) {
                    Forecast.Hour[] day = member.days()[hour / 24];
                    Forecast.Hour sample = day == null ? null : day[hour % 24];
                    inches[hour + 1] = inches[hour] + (sample == null ? 0 : sample.snowCm() / 2.54);
                    missing[hour + 1] = missing[hour] + (sample == null ? 1 : 0);
                }
                tracks.add(new Track(inches, missing));
            }
            models.add(new Model(source.name(), tracks));
        }
        detectEvents();
    }

    private void detectEvents() {
        int first = -1, lastSnow = -1;
        boolean startUncertain = false;
        boolean boundary = true;
        for (int hour = firstHour; hour < LIMIT; hour++) {
            Stats sample = stats(hour, hour + 1, false);
            if (!sample.available()) {
                if (first >= 0) events.add(new Event(first, lastSnow + 1, startUncertain, true));
                first = -1;
                boundary = true;
            } else if (sample.snowShare(SNOW_HOUR) >= EVENT_SHARE) {
                if (first < 0) {
                    first = hour;
                    startUncertain = boundary;
                }
                lastSnow = hour;
            } else if (first >= 0 && hour - lastSnow > DRY_GAP) {
                events.add(new Event(first, lastSnow + 1, startUncertain, false));
                first = -1;
            }
            if (sample.available()) {
                coveredHours++;
                boundary = false;
            }
        }
        if (first >= 0) events.add(new Event(first, lastSnow + 1, startUncertain, true));
    }

    Stats stats(int start, int end) {
        return stats(start, end, true);
    }

    private Stats stats(int start, int end, boolean modelDetails) {
        List<Value> all = new ArrayList<>();
        List<ModelDetail> details = new ArrayList<>();
        if (start < firstHour || end > LIMIT || start >= end) return new Stats(end, all, details);
        for (Model model : models) {
            List<Value> family = new ArrayList<>();
            for (Track track : model.tracks()) {
                double value = track.total(start, end);
                if (Double.isFinite(value)) family.add(new Value(value, 1));
            }
            if (family.size() < 2 || family.size() * 2 < model.tracks().size()) continue;
            double weight = 1.0 / family.size();
            for (Value member : family) all.add(new Value(member.amount(), weight));
            double median = modelDetails ? percentile(family, .5) : Double.NaN;
            details.add(new ModelDetail(model.name(), family.size(), median));
        }
        return new Stats(end, all, details);
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

        String confidence() {
            if (!available()) return "Unavailable";
            if (end > DAILY_DAYS * 24) return "Very low";
            double median = amount(.5);
            double agreement = models.stream().mapToDouble(ModelDetail::median).max().orElse(0)
                    - models.stream().mapToDouble(ModelDetail::median).min().orElse(0);
            if (models.size() < 2 || end > 7 * 24 || agreement > Math.max(.5, .35 * median)) return "Low";
            return amount(.90) - amount(.10) <= Math.max(.5, .50 * median) ? "Higher" : "Moderate";
        }
    }
}
