package com.snoworno.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class LocationSearch {
    private static final String STREET_ENDPOINT =
            "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress";
    private static final String PLACE_ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search";
    private static final int RESULT_LIMIT = 5;

    record Match(String label, double latitude, double longitude) {}

    static List<Match> findStreet(String query) throws Exception {
        String address = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        return parseStreet(ForecastSource.read(STREET_ENDPOINT + "?address=" + address
                + "&benchmark=Public_AR_Current&format=json"));
    }

    static List<Match> findPlace(String query) throws Exception {
        String name = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        return parsePlace(ForecastSource.read(PLACE_ENDPOINT + "?name=" + name
                + "&countryCode=US&language=en&count=" + RESULT_LIMIT));
    }

    static List<Match> parseStreet(JSONObject response) throws JSONException {
        JSONArray rows = response.getJSONObject("result").getJSONArray("addressMatches");
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < rows.length() && matches.size() < RESULT_LIMIT; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            JSONObject coordinates = row.optJSONObject("coordinates");
            if (coordinates == null) continue;
            addMatch(matches, row.optString("matchedAddress"), coordinates.optDouble("y"),
                    coordinates.optDouble("x"));
        }
        return matches;
    }

    static List<Match> parsePlace(JSONObject response) {
        JSONArray rows = response.optJSONArray("results");
        List<Match> matches = new ArrayList<>();
        if (rows == null) return matches;
        for (int i = 0; i < rows.length() && matches.size() < RESULT_LIMIT; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !"US".equals(row.optString("country_code"))) continue;
            String name = row.optString("name");
            String state = row.optString("admin1");
            if (name.trim().isEmpty()) continue;
            addMatch(matches, state.trim().isEmpty() ? name : name + ", " + state,
                    row.optDouble("latitude"), row.optDouble("longitude"));
        }
        return matches;
    }

    private static void addMatch(List<Match> matches, String label, double latitude, double longitude) {
        if (!label.trim().isEmpty() && ForecastSource.validSite(latitude, longitude, 0)) {
            Match match = new Match(label, latitude, longitude);
            if (!matches.contains(match)) matches.add(match);
        }
    }
}
