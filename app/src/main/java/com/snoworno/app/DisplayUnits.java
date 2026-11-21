package com.snoworno.app;

import java.util.Locale;

record DisplayUnits(boolean celsius, boolean centimeters) {
    String depthUnit() { return centimeters ? "cm" : "in"; }
    String depth(double inches, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", centimeters ? inches * 2.54 : inches);
    }

    String bin(int index) {
        if (!centimeters) return Forecast.BIN_LABELS[index];
        double[] bounds = Forecast.BINS;
        if (index == 0) return "Under " + depth(bounds[0], 3) + " cm";
        if (index == bounds.length) return depth(bounds[index - 1], 2) + " cm or more";
        return depth(bounds[index - 1], index == 1 ? 3 : 2) + "–<" + depth(bounds[index], 2) + " cm";
    }

    String temperatures(double low, double high) {
        if (!Double.isFinite(low) || !Double.isFinite(high)) return "Temperature unavailable";
        long min = temperature(low), max = temperature(high);
        return (min == max ? Long.toString(min) : min + "° / " + max) + (celsius ? "°C" : "°F");
    }

    long temperature(double fahrenheit) {
        return Math.round(celsius ? (fahrenheit - 32) * 5 / 9 : fahrenheit);
    }

    String wind(double kph) {
        return windValue(kph) + " " + windUnit();
    }

    String windValue(double kph) { return Long.toString(Math.round(centimeters ? kph : kph / 1.609344)); }
    String windUnit() { return centimeters ? "km/h" : "mph"; }
}
