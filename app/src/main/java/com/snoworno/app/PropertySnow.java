package com.snoworno.app;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

final class PropertySnow {
    private static final int DAYS = 7;
    private static final int HOURS = DAYS * 24;
    private static final int WINDOW = 72;

    enum Surface {
        LAWN("Lawn", 0.13, 0.08, 0.006),
        PAVEMENT("Driveway", 0.21, 0.21, 0.013);

        final String label;
        final double response;
        final double groundMelt;
        final double solarWarmth;

        Surface(String label, double response, double groundMelt, double solarWarmth) {
            this.label = label;
            this.response = response;
            this.groundMelt = groundMelt;
            this.solarWarmth = solarWarmth;
        }
    }

    record Depth(double lower, double median, double upper) {}
    record Day(Depth remaining) {}
    record Result(Depth fallen, Depth peak, Depth remaining, double oneInchShare,
                  int modelCount, int memberCount, Day[] days) {
        boolean available() { return modelCount > 0; }
    }

    private record Snapshot(double fallen, double peak, double depth) {}
    private record Run(Snapshot window, Snapshot[] midnight) {}
    private record Family(int expectedMembers, List<Run> runs) {}
    private record Weighted(Snapshot snapshot, double weight) {}
    private record Blend(int modelCount, List<Weighted> members) {}

    static Result project(Forecast forecast, Surface surface, boolean shade) {
        List<Family> families = new ArrayList<>();
        for (Forecast.Model model : forecast.models) {
            List<Run> runs = new ArrayList<>();
            for (Forecast.Member member : model.members()) {
                runs.add(simulate(member, forecast.firstFutureHour, surface, shade));
            }
            families.add(new Family(model.members().size(), runs));
        }

        Blend window = blend(families, Run::window);
        Day[] days = new Day[DAYS];
        for (int day = 0; day < DAYS; day++) {
            int index = day;
            Blend dayBlend = blend(families, run -> run.midnight()[index]);
            if (dayBlend.modelCount() > 0) {
                days[day] = new Day(depth(dayBlend.members(), Snapshot::depth));
            }
        }

        List<Weighted> values = window.members();
        if (values.isEmpty()) return new Result(null, null, null, Double.NaN, 0, 0, days);
        double oneInch = values.stream().filter(sample -> sample.snapshot().peak() >= 1)
                .mapToDouble(Weighted::weight).sum() / window.modelCount();
        return new Result(depth(values, Snapshot::fallen), depth(values, Snapshot::peak),
                depth(values, Snapshot::depth), Math.min(1, oneInch),
                window.modelCount(), values.size(), days);
    }

    private static Run simulate(Forecast.Member member, int start, Surface surface, boolean shade) {
        Snapshot[] midnight = new Snapshot[DAYS];
        Snapshot window = null;
        double depthCm = 0, snowWater = 0, fallen = 0, peak = 0;
        double shadedFraction = shade ? .25 : 1;
        double substrate = Double.NaN;
        for (int t = start; t < HOURS; t++) {
            Forecast.Hour[] day = member.days()[t / 24];
            Forecast.Hour hour = day == null ? null : day[t % 24];
            if (hour == null) break;
            double air = hour.celsius();
            double sunlight = hour.sunWatts() * shadedFraction;
            if (!Double.isFinite(substrate)) substrate = initialGround(member, t, air);

            // A lagged ground temperature proxy. Snow progressively insulates the surface beneath it.
            double insulated = Math.exp(-depthCm / 5);
            double target = air + sunlight * surface.solarWarmth;
            substrate += (target - substrate) * surface.response * (.25 + .75 * insulated);

            double snowfall = hour.snowCm();
            fallen += snowfall;
            depthCm += snowfall;
            snowWater += snowfall * 10 / 7; // The forecast feed uses a 7:1 depth-to-water ratio.
            if (snowWater > 0) {
                double heat = Math.max(0, substrate) * surface.groundMelt * insulated
                        + Math.max(0, air) * .11
                        + sunlight * (air >= -1 ? .0017 : .00025)
                        + hour.rainMm() * Math.max(0, air) * .0125;
                double melted = Math.min(snowWater, heat);
                depthCm *= 1 - melted / snowWater;
                snowWater -= melted;

                // Settling shrinks depth without removing water. Cap density at 400 kg/m³.
                double settling = air > -1 ? .015 : .006;
                depthCm = Math.max(snowWater / 4, depthCm * (1 - settling));
            }
            peak = Math.max(peak, depthCm);
            if (t == start + WINDOW - 1 || t % 24 == 23) {
                Snapshot snapshot = new Snapshot(fallen / 2.54, peak / 2.54, depthCm / 2.54);
                if (t == start + WINDOW - 1) window = snapshot;
                if (t % 24 == 23) midnight[t / 24] = snapshot;
            }
        }
        return new Run(window, midnight);
    }

    private static double initialGround(Forecast.Member member, int start, double air) {
        double total = air;
        int count = 1;
        for (int t = Math.max(0, start - 12); t < start; t++) {
            Forecast.Hour[] day = member.days()[t / 24];
            Forecast.Hour hour = day == null ? null : day[t % 24];
            if (hour == null) continue;
            total += hour.celsius();
            count++;
        }
        return total / count;
    }

    private static Blend blend(List<Family> families, java.util.function.Function<Run, Snapshot> select) {
        List<Weighted> values = new ArrayList<>();
        int count = 0;
        for (Family family : families) {
            List<Snapshot> valid = new ArrayList<>();
            for (Run run : family.runs()) {
                Snapshot point = select.apply(run);
                if (point != null) valid.add(point);
            }
            if (valid.size() < 2 || valid.size() * 2 < family.expectedMembers()) continue;
            count++;
            for (Snapshot point : valid) values.add(new Weighted(point, 1.0 / valid.size()));
        }
        return new Blend(count, values);
    }

    private static Depth depth(List<Weighted> values, ToDoubleFunction<Snapshot> metric) {
        ToDoubleFunction<Weighted> measure = sample -> metric.applyAsDouble(sample.snapshot());
        return new Depth(Forecast.quantile(values, measure, Weighted::weight, .10),
                Forecast.quantile(values, measure, Weighted::weight, .50),
                Forecast.quantile(values, measure, Weighted::weight, .90));
    }
}
