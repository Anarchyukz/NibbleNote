# NibbleNote

**Your recipes, neatly kept.** NibbleNote is a free, open-source Android recipe organiser built with Kotlin and Jetpack Compose. The MVP stores recipes locally and imports from websites that publish Schema.org `Recipe` JSON-LD data.

## Features
- Add, edit, search and delete recipes.
- Local device storage; no account or server required.
- Import recipe name, description, ingredients and instructions from supported recipe pages.
- Nutrition details, recipe website browsing and themed discovery.
- Slow-cooker recipe collection.
- GitHub Actions builds an installable APK when code is updated.
- Versioned APK releases can be published from a `v*` tag.

## Download and install

**[Download NibbleNote for Android (APK)](https://github.com/Anarchyukz/PantryPal/releases/latest/download/app-debug.apk)** — this direct link downloads the APK attached to the latest published GitHub Release.

For the newest development build before it has been published as a release, open the [latest successful GitHub Actions build](https://github.com/Anarchyukz/PantryPal/actions), select the successful run, and download `NibbleNote-APK` from **Artifacts**. Extract the ZIP and install the APK on your Android device.

The automated build uses Android's debug signing key unless release-signing secrets are configured in GitHub. For a stable signing identity across future updates, configure `PANTRYPAL_KEYSTORE_BASE64`, `PANTRYPAL_KEYSTORE_PASSWORD`, `PANTRYPAL_KEY_ALIAS` and `PANTRYPAL_KEY_PASSWORD` as repository Actions secrets before distributing signed release builds. These existing secret names are retained to avoid breaking your build setup. Never commit signing keys or passwords.

## Build from source
Requirements: JDK 17, Android SDK 36, Gradle 8.13.

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

To publish a versioned APK, push a tag beginning with `v` (for example, `v1.1.0`). Configure release signing first if you need an APK that can be updated in place across future builds.

## Donations
NibbleNote is free. Add voluntary donation links here when ready:
- GitHub Sponsors: setup pending
- Ko-fi: setup pending

Donations are optional and do not unlock features.

## Importing limitations
The importer looks for Schema.org `Recipe` JSON-LD. Some sites block automated access or don't provide structured data. Social media imports may require pasting the recipe caption/text and are not guaranteed to work for every post. Only import content you have permission to use.

## Privacy
Recipes are stored in app-private local storage. Importing makes a network request to the URL entered. This app has no account, analytics or cloud sync. Back up important recipes separately until export/backup is implemented.

## Roadmap
- [ ] Recipe backup/export
- [ ] Meal planner and shopping list
- [ ] Portion scaling
- [ ] Images and favourites
- [ ] More robust website/social imports
- [ ] Optional sync with sustainable free hosting
- [ ] Configure donation links

## Licence
MIT. See [LICENSE](LICENSE).
