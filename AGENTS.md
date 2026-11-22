# MooseAI LLC Android development

SnowOrNo is a native Java Android app. Use JDK 25 to run Gradle 9.9.2 through the checked-in wrapper with Android Gradle Plugin 9.5.2. Compile and target API 36 with a minimum SDK of 26. Keep source and bytecode compatibility at Java 17. Preserve `com.mooseaillc.snoworno`, the existing namespace `com.snoworno.app`, and its Google Play upload certificate.

## Apply this standard to MooseAI LLC Android projects

- Use each project's `./gradlew` for local builds and CI. Commit both launcher scripts, the wrapper JAR, and wrapper properties with an exact distribution version and official SHA-256. Verify the wrapper JAR against Gradle's published checksum on upgrades.
- Before upgrading, verify the JDK and Gradle matrix, AGP's Gradle and SDK requirements, and the framework and language plugin constraints. Choose stable versions and validate together in the actual project. Use SnowOrNo's verified combination as a starting point for native projects and retain a framework-supported combination where needed. Keep source and bytecode at Java 17 by default.
- Check Google Play's current target API deadline and any applicable form-factor rules before release. Compile against a stable SDK that supports that target and test changed Android behavior on the new target. Preserve minimum SDK unless the app has a documented reason to raise it.
- Use `com.mooseaillc.<appname>` for new apps with a lowercase identifier. Keep established application IDs, namespaces, signing identities, and release history. Increase `versionCode` for each new Play release after checking all tracks, and set a user-facing `versionName`.
- Use Google Play App Signing with the app's existing upload key. Load release secrets from an ignored local `keystore.properties`. Keep debug signing separate and make release builds fail clearly when credentials are missing. Keep private keys and credentials out of Git, logs, and artifacts. Never generate or replace an existing signing key without authorization.
- Run applicable tests and lint, build the release AAB through the wrapper, and verify its package, version, target SDK, and certificate against the existing upload key. Confirm the Play Console application and upload certificate before submission. Publish only with explicit authorization.
- Use the [reusable Android release procedure](docs/android-release.md) for setup, signing, validation, and CI. Copy these instructions and the procedure to new app repositories and record their verified toolchain and identity.
