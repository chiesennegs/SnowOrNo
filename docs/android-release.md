# MooseAI LLC Android release procedure

## Verify the toolchain

Start a native Java project with JDK 25, Gradle 9.9.2, AGP 9.5.2, Java 17 source and bytecode, SDK Platform 36, and Build Tools 36.0.0. This is the SnowOrNo combination. For other projects, check framework, Kotlin, Compose, and native build requirements before selecting versions. Pin plugin versions and the project wrapper, and record any required SDK, NDK, or build tools versions in the project README.

- Check JDK runtime support in the [Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime).
- Check the minimum Gradle version and supported SDKs in the [AGP release notes](https://developer.android.com/build/releases/gradle-plugin) and stable patches in [Google Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml).
- Check the [Google Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878) and Android behavior changes. At the time of the SnowOrNo migration, new phone and tablet apps and updates must target API 36.

Bootstrap a new project with the selected Gradle distribution, then use the wrapper everywhere. Use Gradle's [published checksums](https://gradle.org/release-checksums/) for the distribution and wrapper JAR.

```sh
gradle wrapper --gradle-version "$gradle_version" --gradle-distribution-sha256-sum "$gradle_sha256"
./gradlew wrapper --gradle-version "$gradle_version" --gradle-distribution-sha256-sum "$gradle_sha256"
./gradlew --version
```

Commit `gradlew` (executable), `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties`. Repeat wrapper generation with the new distribution during upgrades so both scripts and the JAR match.

## Configure signing and release inputs

Keep the upload key in restricted storage with a secure backup. Use the existing key for the Play listing and look up the registered **Upload key certificate** in Play Console. Keep developer debug signing on Android's default debug key. Adapt the [SnowOrNo signing configuration](../app/build.gradle) as needed for the project's release variants. A missing signing input must fail a release build with an actionable message.

Populate `keystore.properties` locally or in the CI release job.

```properties
storeFile=app-upload.jks
keyAlias=app-upload
storePassword=<from secure storage>
keyPassword=<from secure storage>
```

Resolve the keystore path relative to the repository root or set an absolute path. Ignore `keystore.properties`, `local.properties`, `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, and generated build outputs. Verify matching ignore rules with `git check-ignore -v -- keystore.properties app-upload.jks`. Use `git ls-files -- keystore.properties app-upload.jks` to confirm neither file is tracked.

## Build and inspect

Use the pinned toolchain from a clean checkout. Run the project's existing tests and lint and perform a smoke test on an Android device or emulator when targeting a new API. Record the commit, tool versions, and bundle SHA-256 for each release.

```sh
./gradlew --version
./gradlew lintDebug testDebugUnitTest
./gradlew clean bundleRelease --warning-mode all
bundle=app/build/outputs/bundle/release/app-release.aab
java -jar "$BUNDLETOOL_JAR" validate --bundle="$bundle"
java -jar "$BUNDLETOOL_JAR" dump manifest --bundle="$bundle" --module=base
jarsigner -verify "$bundle"
keytool -printcert -jarfile "$bundle"
shasum -a 256 "$bundle"
```

Use a verified release of [bundletool](https://developer.android.com/tools/bundletool). Confirm the bundle package, version code, version name, and target SDK from its manifest. Compare the bundle certificate's SHA-256 fingerprint with `keytool -list -v -keystore <upload-keystore> -alias <upload-alias>` and the Upload key certificate in Play Console. Let `keytool` prompt for the password. Confirm the version code is unused, review the build and Play acceptance warnings, and complete the listing and required Play declarations, including Data safety. See [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756).

## GitHub Actions

- In pull request jobs, install the project JDK and SDK versions, validate the wrapper, and run `./gradlew lintDebug testDebugUnitTest assembleDebug`. Give these jobs read-only repository permissions and no release credentials.
- In a trusted release workflow, use a protected environment and pin third-party actions to reviewed commit SHAs. Load the existing upload keystore and properties from environment secrets into temporary, permission-restricted files. Build with `./gradlew clean bundleRelease` and apply the same manifest and certificate checks before retaining the signed AAB. Remove signing files after the job and exclude them from caches and artifacts.
- Keep Play submission separate from the build job so the signed bundle can be reviewed before upload.
