# SnowOrNo

Native Android prototype for location-specific snow accumulation, elevation-specific snowfall amounts and a 30-day weather outlook. Utah is the primary test region, with Denver and Loveland Pass presets for Colorado. Custom coordinates and elevation are supported.

Created by [MooseAI, LLC](https://mooseaillc.com) · Support: [support@mooseaillc.com](mailto:support@mooseaillc.com)

## Build and release

Install JDK 25, Android SDK Platform 36, and Build Tools 36.0.0. Set `JAVA_HOME` to JDK 25 and `ANDROID_HOME` to the Android SDK directory. In Android Studio, open the repository and select JDK 25 and the Gradle Wrapper in Gradle settings. The app uses AGP 9.5.2 and the wrapper pins Gradle 9.9.2. Java source and bytecode remain at 17, and the app runs on Android 8.0 and later.

```sh
./gradlew --version
./gradlew assembleDebug
```

Run the app from Android Studio on an emulator or device, or install the APK at `app/build/outputs/apk/debug/app-debug.apk`. Debug builds use Android's debug key and can run without the release credentials.

For a release, place the existing upload keystore and a local `keystore.properties` in the repository root. Set `storeFile=snoworno-upload.jks` and `keyAlias=snoworno-upload`, and supply `storePassword` and `keyPassword` from secure storage. Both files are Git-ignored.

```sh
./gradlew clean bundleRelease
```

The signed bundle is written to `app/build/outputs/bundle/release/app-release.aab`. Use the [MooseAI Android release procedure](docs/android-release.md) to verify it before submitting to Play Console.

## Run

- On first launch, the app requests a live forecast for Salt Lake City. It refreshes for the selected location on launch, on location changes, when returning to the app after 15 minutes, and when you tap the **Refresh** icon. Use **Change → Choose on map** to place a pin and review the coordinates and terrain elevation, or choose **Search by address**, enter coordinates manually, or select a preset. The location name and surface settings are stored on the device. Coordinates and elevation are submitted to the forecast provider.
- The forecast cache holds the most recent successful responses for up to 12 coordinate-and-elevation combinations. Each entry lasts up to 48 hours. The app can show cached guidance during a refresh or after an outage, with its retrieval time and a cache status label. The forecast calendar advances to the current local date when reading saved data. Expired or unreadable entries are skipped.
- Tap **Save favorite** to keep the current location. Tap **Favorites** to switch among saved locations or use **Manage** to rename or remove one. Favorites persist on the device independently of the forecast cache.
- The map uses [OpenStreetMap](https://www.openstreetmap.org/copyright) street tiles. Address search uses the Android device's geocoder. Selecting an address or a map pin sends its coordinates to the [Open-Meteo Elevation API](https://open-meteo.com/en/docs/elevation-api). The terrain elevation automatically fills the editable elevation field when a result is available. Enter it manually if the lookup fails.

- The **Settings** icon beside Refresh opens independent temperature (°F/°C) and precipitation depth (in/cm) preferences. Wind units follow precipitation units (mph or km/h). Light mode is optional. Set the surface and exposure under **Snow at Location**. The Calendar weather icon legend is also in Settings. Preferences are saved on the device.

**Settings → History backup** exports downloaded weather, archived storm forecasts, and measurements as one compressed file. Import merges that file into the device’s history. Existing weather hours and local measurements take priority on conflicts.

## Forecast behavior

**Storm history** archives detected storms automatically whenever a live forecast loads for a location. It keeps the original and latest forecast for each matched event. In **Location → Storm history** or **Storm → Storm history**, you can review the archive and optionally enter new snowfall measured during a saved window, including zero. For accuracy comparisons the app freezes the first complete forecast retrieved within seven days before the storm. Model scores show mean absolute error and the number of comparable observations per model. History stays on the device and is keyed by coordinates and elevation. Forward forecasts weight model families equally. An experimental trial median is shown separately after five eligible measurements with forecast medians of at least 0.25 inches, using a median observed-to-forecast multiplier bounded to 0.75–1.25.

In **Storm** and **Storm history**, the active storm is highlighted. Use a card’s caret to collapse it to the dates and expected snowfall, or use the floating up arrow to return to the top. Tap a confidence rating in Storm or Calendar to see the reason for that rating.

Use **Daily snowfall** on a storm to record new snow one period at a time. Set the local cutoff to your usual clearing time before the first entry. The first and last periods use the exact saved storm boundaries. Enter zero explicitly for a dry period. Daily readings become the measured storm total after every period is filled. An existing whole-storm reading can remain as the measurement or be cleared to switch to daily readings.

- **Location** shows the current hourly model temperature and the forecast lows and highs for today and tomorrow. It separates new snowfall from peak depth and depth remaining after 72 hours for a lawn or driveway, with sun or mostly shaded exposure. It reports the share of model guidance reaching one inch of depth. **Snow depth at midnight** shows how much snow may be on the chosen surface at the end of each local date after snowfall, melt and settling, with the median and middle 80% of model estimates. Calculations begin at the current local model hour with a bare, untreated surface. A lagged ground-temperature proxy, solar input, rain and settling drive the depth estimate. Surface coefficients are provisional and await validation against measurements. Tap a midnight depth date to open that date in Calendar.
- **Storm** shows the current storm first, when one is in progress. The Storm outlook appears below the event cards. Storm history is accessible beside the forecast status. The app requests three prior days of model guidance to identify storm starts before midnight. Storm cards link to the first forecast day of the event, or today if the storm began on a prior date. Storm cards show local start and end, temperature and wind, the middle 80% of storm totals, median, confidence, contributing model families, and the expandable snowfall distribution. The cumulative chart shows the median and 10th–90th percentiles as snowfall builds, using the same members as the storm total. For ongoing storms, a dashed line marks the current forecast hour, with separate ranges for modeled snowfall so far and expected snowfall remaining. Completed daily measurements entered in Storm history can be compared with a saved pre-storm median for the same period, when available. Extended storms use six-hour chart steps. A storm hour needs at least 30% model support for 0.01 inches of snowfall. Up to six intervening hours below that threshold stay in the same storm. Each model family has equal total weight. Days 15–30 are very-low-confidence signals with timing displayed in approximate six-hour windows. Coverage gaps and potentially truncated events are labeled.
- **Calendar** shows 30 selectable dates, with a weather icon and daily low above the daily high when guidance is available. The icing icon flags modeled rain at or below freezing. Use **Go to date** to browse the past ten years. Calendar downloads hourly weather by month as you browse and keeps downloaded months available offline. It shows daily highs, lows and precipitation totals, hourly weather and available ground snow depth, and saved storm forecasts and snowfall measurements. Older weather is modeled ERA5 Seamless reanalysis via Open-Meteo. The most recent completed days use recent model estimates until reanalysis becomes available. Tap **Today** to select the current date at the forecast location. Use **Back** or Android’s Back gesture to return to the screen and scroll position you came from. Through day 14, tap a date for daily weather, and hourly temperature, wind and snowfall ranges, and snowfall distributions for the day and any storm that overlaps it. The day card also plots cumulative snowfall as a median and a 10th–90th percentile band. Today’s chart starts at the current forecast hour and ends at local midnight. The running storm range carries across midnight and sums each member's hours before computing percentiles. A storm hour needs at least 30% model support for 0.01 inches of snowfall. Up to six intervening hours below that threshold stay in the same storm. Elapsed live hours are marked separately and a storm cut off by the forecast boundary is labeled. Days 15–21, 22–28 and 29–30 show their coarse outlook windows. Calendar times use the site's current UTC offset.
- **Fresh snow & melt details** shows a separate open-surface estimate of conditions, cumulative snow-water loss and potential refreeze through day seven.

Live mode makes HTTPS requests to the [Open-Meteo Ensemble API](https://open-meteo.com/en/docs/ensemble-api) for NOAA GEFS, ECMWF IFS and Canadian GEM (GEPS) member data. Open-Meteo's public hosted API is intended for noncommercial use. Forecast data are attributed to NOAA, ECMWF and Environment and Climate Change Canada via Open-Meteo. The location and requested elevation feed the provider's statistical post-processing. The forecast is a point estimate using models with grids larger than one square mile. Extended guidance uses coarser model data. Canadian extended runs are available on select cycles. Source availability and lead times can vary by forecast cycle. Wind is forecast at 10 meters above ground. Previously cached forecasts may lack wind until a successful refresh.

## Forecasting roadmap

1. Add a serving and archival pipeline for direct public feeds. Start with near-term NOAA regional guidance and NBM benchmarks. Blend GEFS and ECMWF for medium-range guidance. Evaluate extended-range ensemble guidance for weekly temperature and precipitation.
2. Add the map and elevation controls. Use USGS 3DEP to sample a selected square mile and describe its elevation distribution. Downscale temperature, precipitation phase and snowfall separately. Display the footprint and distinguish the output grid from validated forecast resolution.
3. Backtest on archived storms across the Salt Lake Valley, benches, Wasatch canyons and high terrain. Use quality-controlled snowfall observations for accumulation, and NRCS SNOTEL snow depth and water equivalent for snowpack verification. Compare against NOAA/NWS baseline guidance using probability reliability, Brier scores and interval coverage by lead time and elevation band.
4. Validate the location-specific estimates on lawn and pavement, including warm-ground storms. Add a measured starting depth for locations with an existing snowpack.
5. Build ski conditions next for broader use. Prioritize base and summit elevation, slope aspect, freezing level, overnight refreeze and fresh snow quality. Validate the ski model independently against Wasatch observations.


### Additional public forecast guidance

- [Utah Snow Ensemble](https://chpc.utah.edu/highlights/read.php?article=2024_steenburgh_utah_snow_ensemble&source=recent) and the [University of Utah Weather Center](https://weather.utah.edu/index.php?d=240HS&r=NU&t=ensgefsds) publish terrain-focused Western U.S. snowfall guidance in six-hour steps through day 10. The product post-processes the same GEFS and ECMWF families already used here, so it is a comparison and downscaling candidate. Machine-readable access and reuse terms need confirmation before integrating it.
- [NOAA RRFS and REFS](https://registry.opendata.aws/noaa-rrfs-ops/) offer public high-resolution short-range guidance. [NWS probabilistic snowfall](https://www.weather.gov/winter) is a separate operational benchmark for near-term amounts.
