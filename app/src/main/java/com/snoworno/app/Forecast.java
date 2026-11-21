package com.snoworno.app;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

final class Forecast {
    static final int DAYS = 30;
    static final int STORM_LOOKBACK_DAYS = 3;
    static final double[] BINS = {0.1, 2, 4, 8, 12};
    static final String[] BIN_LABELS = {"Under 0.1 in", "0.1–<2 in", "2–<4 in", "4–<8 in", "8–<12 in", "12 in or more"};

    record Site(String name, String region, double latitude, double longitude, int feet) {}
    record Hour(double celsius, double snowCm, double rainMm, double cloud, double sunWatts, double windKph) {
        Hour(double celsius, double snowCm, double rainMm, double cloud, double sunWatts) {
            this(celsius, snowCm, rainMm, cloud, sunWatts, Double.NaN);
        }
    }
    record Member(Hour[][] days, double[] earlierSnowCm) {
        Member(Hour[][] days) { this(days, new double[0]); }

        double snowfallAt(int hour) {
            if (hour < 0) {
                int index = earlierSnowCm.length + hour;
                return index < 0 ? Double.NaN : earlierSnowCm[index];
            }
            Hour[] day = days[hour / 24];
            Hour sample = day == null ? null : day[hour % 24];
            return sample == null ? Double.NaN : sample.snowCm();
        }
    }
    record Model(String name, List<Member> members) {}
    record Conditions(double low, double high, double wind, double peakWind) {}
    record Confidence(String rating, String reason) {}
    static final Confidence EXTENDED_CONFIDENCE = new Confidence("Very low",
            "This window includes extended guidance from day 15 onward. Snowfall amounts and timing are especially uncertain at this lead time.");
    private record ConditionsSample(Conditions conditions, double weight) {}
    enum Weather {
        SUNNY("Sunny"), PARTLY_SUNNY("Partly sunny"), PARTLY_CLOUDY("Partly cloudy"), CLOUDY("Cloudy"),
        RAIN("Rain"), MIXED("Rain and snow"), SNOW("Snow"), ICE("Icing possible");

        final String label;

        Weather(String label) { this.label = label; }
    }

    record Sample(double snow, double rain, double freezingRain, double low, double high, double cloud,
                  double freshTemp, double melt, double remaining, boolean refreeze, double wind, double peakWind, double weight) {
        Sample withWeight(double value) {
            return new Sample(snow, rain, freezingRain, low, high, cloud, freshTemp, melt, remaining, refreeze, wind, peakWind, value);
        }
    }

    final LocalDate start;
    final List<Model> models;
    final int firstFutureHour;
    final ZoneOffset offset;

    Forecast(LocalDate start, List<Model> models) {
        this(start, models, 0);
    }

    Forecast(LocalDate start, List<Model> models, int firstFutureHour) {
        this(start, models, firstFutureHour, ZoneOffset.UTC);
    }

    Forecast(LocalDate start, List<Model> models, int firstFutureHour, ZoneOffset offset) {
        this.start = start;
        this.models = models;
        this.firstFutureHour = firstFutureHour;
        this.offset = offset;
    }

    Summary summarize(int first, int end) {
        List<Sample> weighted = new ArrayList<>();
        List<Double> modelMedianSnow = new ArrayList<>();
        List<String> modelNames = new ArrayList<>();
        for (Model model : models) {
            List<Sample> family = new ArrayList<>();
            for (Member member : model.members()) {
                Sample sample = accumulate(member, first, end);
                if (sample != null) family.add(sample);
            }
            if (family.size() < 2 || family.size() * 2 < model.members().size()) continue;
            modelNames.add(model.name());
            for (Sample sample : family) weighted.add(sample.withWeight(1.0 / family.size()));
            modelMedianSnow.add(quantile(family, Sample::snow, 0.50));
        }
        return new Summary(first, end, weighted, modelMedianSnow, modelNames);
    }

    Conditions conditions(int firstHour, int endHour) {
        List<ConditionsSample> weighted = new ArrayList<>();
        for (Model model : models) {
            List<Conditions> valid = new ArrayList<>();
            for (Member member : model.members()) {
                Conditions period = memberConditions(member, firstHour, endHour);
                if (period != null) valid.add(period);
            }
            if (valid.size() < 2 || valid.size() * 2 < model.members().size()) continue;
            for (Conditions period : valid) weighted.add(new ConditionsSample(period, 1.0 / valid.size()));
        }
        return new Conditions(
                quantile(weighted, row -> row.conditions().low(), ConditionsSample::weight, .50),
                quantile(weighted, row -> row.conditions().high(), ConditionsSample::weight, .50),
                quantile(weighted, row -> row.conditions().wind(), ConditionsSample::weight, .50),
                quantile(weighted, row -> row.conditions().peakWind(), ConditionsSample::weight, .50));
    }

    private static Conditions memberConditions(Member member, int firstHour, int endHour) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        Wind wind = new Wind();
        for (int index = firstHour; index < endHour; index++) {
            Hour[] day = member.days()[index / 24];
            Hour hour = day == null ? null : day[index % 24];
            if (hour == null) return null;
            low = Math.min(low, hour.celsius());
            high = Math.max(high, hour.celsius());
            wind.add(hour);
        }
        return new Conditions(fahrenheit(low), fahrenheit(high), wind.average(), wind.peak);
    }

    private static final class Wind {
        double total;
        double peak = Double.NaN;
        int count;

        void add(Hour hour) {
            if (!Double.isFinite(hour.windKph())) return;
            total += hour.windKph();
            peak = count == 0 ? hour.windKph() : Math.max(peak, hour.windKph());
            count++;
        }

        double average() { return count == 0 ? Double.NaN : total / count; }
    }

    private static Sample accumulate(Member member, int first, int end) {
        double snow = 0, rain = 0, freezingRain = 0, cloud = 0, lows = 0, highs = 0;
        double snowTemp = 0, melt = 0, snowpack = 0;
        boolean refrozen = false, thawedSinceSnow = false;
        Wind wind = new Wind();
        for (int day = first; day < end; day++) {
            Hour[] hours = member.days()[day];
            if (hours == null) return null;
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (Hour hour : hours) {
                if (hour == null) return null;
                double temp = hour.celsius();
                wind.add(hour);
                min = Math.min(min, temp);
                max = Math.max(max, temp);
                snow += hour.snowCm();
                rain += hour.rainMm();
                if (temp <= 0) freezingRain += hour.rainMm();
                cloud += hour.cloud();
                snowTemp += hour.snowCm() * temp;

                // The provider's snowfall depth assumes 7:1. Convert back to water equivalent.
                snowpack += hour.snowCm() * 10 / 7;
                if (hour.snowCm() >= 0.25) {
                    refrozen = false;
                    thawedSinceSnow = false;
                }
                if (temp < -0.5 && thawedSinceSnow && snowpack > 0) refrozen = true;
                // A provisional hourly melt proxy in mm SWE. Refreeze is tracked as a surface signal.
                if (temp > -1 && snowpack > 0) {
                    double potential = Math.max(0, temp) * (3.0 / 24)
                            + Math.max(0, hour.sunWatts()) * 0.002
                            + hour.rainMm() * Math.max(0, temp) * 0.0125;
                    double realized = Math.min(snowpack, potential);
                    melt += realized;
                    snowpack -= realized;
                    if (realized > 0) thawedSinceSnow = true;
                }
            }
            lows += min;
            highs += max;
        }
        double freshWater = snow * 10 / 7;
        return new Sample(snow / 2.54, rain / 25.4, freezingRain / 25.4, fahrenheit(lows / (end - first)),
                fahrenheit(highs / (end - first)), cloud / (24 * (end - first)),
                snow == 0 ? Double.NaN : fahrenheit(snowTemp / snow),
                freshWater < 0.36 ? Double.NaN : Math.min(100, 100 * melt / freshWater),
                snowpack / 25.4, refrozen, wind.average(), wind.peak, 1);
    }

    static double fahrenheit(double celsius) {
        return celsius * 9 / 5 + 32;
    }

    static Confidence snowfallConfidence(int families, boolean extended, boolean beyondWeek,
                                         boolean diverge, double median, double width) {
        if (families == 0) return new Confidence("Unavailable", "Too few members have complete snowfall guidance for this window.");
        if (extended) return EXTENDED_CONFIDENCE;
        if (beyondWeek) return new Confidence("Low", "The window extends beyond the first seven forecast days, when snowfall guidance is less reliable.");
        if (families < 2) return new Confidence("Low", "Only one model family has enough complete data for this window.");
        if (diverge) return new Confidence("Low", "The model families disagree on median snowfall. Their difference exceeds the agreement threshold.");
        if (width <= Math.max(2, median * .50)) {
            return new Confidence("Higher", "The window falls within the first seven forecast days, model families agree, and the middle 80% of snowfall estimates is relatively narrow.");
        }
        return new Confidence("Moderate", "The model families agree on the median within the first seven forecast days, but individual members suggest a wide range of snowfall amounts.");
    }

    static double quantile(List<Sample> samples, ToDoubleFunction<Sample> value, double q) {
        return quantile(samples, value, Sample::weight, q);
    }

    static <T> double quantile(List<T> samples, ToDoubleFunction<T> value,
                               ToDoubleFunction<T> weight, double q) {
        List<T> sorted = samples.stream().filter(s -> Double.isFinite(value.applyAsDouble(s)))
                .sorted(Comparator.comparingDouble(value)).collect(java.util.stream.Collectors.toList());
        if (sorted.isEmpty()) return Double.NaN;
        double total = sorted.stream().mapToDouble(weight).sum();
        double running = 0;
        for (T row : sorted) {
            running += weight.applyAsDouble(row);
            if (running + 1e-12 >= q * total) return value.applyAsDouble(row);
        }
        return value.applyAsDouble(sorted.get(sorted.size() - 1));
    }

    static final class Summary {
        final int first, end;
        final List<Sample> samples;
        final List<Double> medians;
        final List<String> sources;

        Summary(int first, int end, List<Sample> samples, List<Double> medians, List<String> sources) {
            this.first = first;
            this.end = end;
            this.samples = samples;
            this.medians = medians;
            this.sources = sources;
        }

        boolean available() { return !samples.isEmpty(); }
        double snow(double q) { return quantile(samples, Sample::snow, q); }
        double rain(double q) { return quantile(samples, Sample::rain, q); }
        double low() { return quantile(samples, Sample::low, .50); }
        double high() { return quantile(samples, Sample::high, .50); }
        Conditions conditions() {
            return new Conditions(low(), high(), quantile(samples, Sample::wind, .50),
                    quantile(samples, Sample::peakWind, .50));
        }

        double[] buckets() {
            double[] counts = new double[BIN_LABELS.length];
            double total = samples.stream().mapToDouble(Sample::weight).sum();
            if (total == 0) return counts;
            for (Sample member : samples) {
                int bin = 0;
                while (bin < BINS.length && member.snow() >= BINS[bin]) bin++;
                counts[bin] += member.weight() / total;
            }
            return counts;
        }

        String agreement() {
            if (medians.size() < 2) return "Single model";
            double spread = medians.stream().mapToDouble(x -> x).max().orElse(0)
                    - medians.stream().mapToDouble(x -> x).min().orElse(0);
            double tolerance = Math.max(0.5, snow(.5) * .35);
            return spread <= tolerance ? "Models aligned" : "Models diverge";
        }

        Confidence confidence() {
            return snowfallConfidence(sources.size(), first >= 14, end > 7, agreement().equals("Models diverge"),
                    snow(.50), snow(.90) - snow(.10));
        }

        String weather() {
            return weatherIcon().label;
        }

        Weather weatherIcon() {
            if (quantile(samples, Sample::freezingRain, .5) >= .02) return Weather.ICE;
            boolean snowy = snow(.5) >= .1;
            boolean rainy = rain(.5) >= .05;
            if (snowy && rainy) return Weather.MIXED;
            if (snowy) return Weather.SNOW;
            if (rainy) return Weather.RAIN;
            double cloud = quantile(samples, Sample::cloud, .5);
            if (cloud >= 70) return Weather.CLOUDY;
            if (cloud >= 45) return Weather.PARTLY_CLOUDY;
            if (cloud >= 20) return Weather.PARTLY_SUNNY;
            return Weather.SUNNY;
        }

        String surface() {
            double temp = quantile(samples, Sample::freshTemp, .5);
            double thaw = quantile(samples, Sample::melt, .5);
            if (snow(.5) < .1 || !Double.isFinite(temp)) return "Little fresh snow expected";
            if (thaw >= 80) return "Fresh snow mostly melts";
            double wetWeight = samples.stream().filter(s -> s.refreeze() && s.remaining() > 0)
                    .mapToDouble(Sample::weight).sum();
            double total = samples.stream().mapToDouble(Sample::weight).sum();
            if (wetWeight >= total * .5 && thaw >= 10) return "Melt and refreeze possible";
            if (thaw >= 25 || temp >= 30) return "Wet or softening snow";
            return temp <= 24 ? "Cold, dry snow favored" : "Cool, denser snow favored";
        }
    }
}
