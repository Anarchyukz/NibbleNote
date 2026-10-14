# PantryPal

**Your recipes, your kitchen.** PantryPal is a free, open-source Android recipe organiser built with Kotlin and Jetpack Compose. The MVP stores recipes locally and imports from websites that publish Schema.org `Recipe` JSON-LD data.

## MVP features
- Add, edit, search and delete recipes.
- Local device storage; no account or server required.
- Import recipe name, description, ingredients and instructions from supported recipe pages.
- GitHub Actions builds a debug APK on pushes, pull requests and manual runs.
- Push a `v*` tag to build and attach an APK to a GitHub Release.

## Build
Requirements: JDK 17, Android SDK 36, Gradle 8.13.

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`. In GitHub Actions, download the `PantryPal-debug-apk` artifact. To publish a versioned testing release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow currently publishes a debug-signed APK for testing. Configure secure release signing before wider distribution; never commit signing keys or passwords.

## Donations
PantryPal is free. Add voluntary donation links here when ready:
- GitHub Sponsors: setup pending
- Ko-fi: setup pending

Donations are optional and do not unlock features.

## Importing limitations
The importer looks for Schema.org `Recipe` JSON-LD. Some sites block automated access or don't provide structured data. Instagram and TikTok imports are not guaranteed in this MVP and may require separate integrations. Only import content you have permission to use.

## Privacy
Recipes are stored in app-private local storage. Importing makes a network request to the URL entered. This MVP has no account, analytics or cloud sync. Back up important recipes separately until export/backup is implemented.

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
