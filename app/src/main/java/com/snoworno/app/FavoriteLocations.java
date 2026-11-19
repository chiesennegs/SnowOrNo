package com.snoworno.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FavoriteLocations {
    private final List<Forecast.Site> sites = new ArrayList<>();

    static FavoriteLocations fromJson(String data) {
        FavoriteLocations favorites = new FavoriteLocations();
        try {
            JSONArray entries = new JSONArray(data);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String name = entry.optString("name").trim();
                double lat = entry.optDouble("lat"), lon = entry.optDouble("lon");
                int feet = entry.optInt("feet", Integer.MIN_VALUE);
                if (!name.isEmpty() && ForecastSource.validSite(lat, lon, feet)) {
                    favorites.add(new Forecast.Site(name, entry.optString("region"), lat, lon, feet));
                }
            }
        } catch (JSONException ignored) {
            // An invalid preference starts with an empty list.
        }
        return favorites;
    }

    List<Forecast.Site> sites() { return Collections.unmodifiableList(sites); }

    Forecast.Site find(Forecast.Site site) {
        int index = indexOf(site);
        return index < 0 ? null : sites.get(index);
    }

    void add(Forecast.Site site) {
        if (find(site) == null) sites.add(site);
    }

    Forecast.Site rename(Forecast.Site site, String name) {
        int index = indexOf(site);
        if (index < 0 || name.trim().isEmpty()) return null;
        Forecast.Site saved = sites.get(index);
        Forecast.Site updated = new Forecast.Site(name.trim(), saved.region(), saved.latitude(), saved.longitude(), saved.feet());
        sites.set(index, updated);
        return updated;
    }

    void remove(Forecast.Site site) {
        int index = indexOf(site);
        if (index >= 0) sites.remove(index);
    }

    private int indexOf(Forecast.Site site) {
        for (int i = 0; i < sites.size(); i++) {
            if (samePoint(site, sites.get(i))) return i;
        }
        return -1;
    }

    static boolean samePoint(Forecast.Site a, Forecast.Site b) {
        return a.feet() == b.feet()
                && Math.round(a.latitude() * 1_000_000) == Math.round(b.latitude() * 1_000_000)
                && Math.round(a.longitude() * 1_000_000) == Math.round(b.longitude() * 1_000_000);
    }

    String toJson() throws JSONException {
        JSONArray entries = new JSONArray();
        for (Forecast.Site site : sites) {
            entries.put(new JSONObject().put("name", site.name()).put("region", site.region())
                    .put("lat", site.latitude()).put("lon", site.longitude()).put("feet", site.feet()));
        }
        return entries.toString();
    }
}
