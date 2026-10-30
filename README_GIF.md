# Custom build: GIF panel

This fork adds a **GIF** key to the toolbar (next to Clipboard). Tap it and the
letters are replaced by a GIPHY grid:

- **Search what you typed.** Type `happy birthday`, tap GIF, and it searches the last
  few words on the line. Sending a GIF from that search deletes the words you typed,
  so the GIF replaces them.
- **Chips** across the top: your typed search, Trending, and quick picks (lol, yes,
  no, hype, bruh...).
- **Tap a GIF to send it.** Apps that accept images from keyboards (Messages,
  WhatsApp, Discord, Telegram...) get the actual animated GIF. Apps that don't get
  the GIF's link instead.
- Tap **X** or the GIF key again to go back to typing.
- The keyboard only goes online for the GIF panel. Nothing you type is sent anywhere
  except the search words when you open the panel.

## 1. Get a GIPHY API key (free)

1. Go to https://developers.giphy.com and sign in.
2. Create an App, choose **API** (not SDK), give it any name.
3. Copy the API key.

A beta key allows about 100 searches an hour. Every friend using your build shares
that limit, since the key is baked into the app.

## 2. Build it

1. Install Android Studio (it brings the Android SDK; let it also install the NDK when
   it asks, HeliBoard has a native part).
2. Open this folder in Android Studio and let Gradle sync.
3. Open `local.properties` in the project root (Android Studio creates it) and add:
   ```
   giphyApiKey=YOUR_KEY_HERE
   ```
   `local.properties` is git-ignored, so the key won't end up on GitHub.
4. Pick the `debug` build variant and run **Build > Build APK(s)**. The APK lands in
   `app/build/outputs/apk/debug/`.

## 3. Put it on the Pixel

1. On the phone: Settings > About phone > tap **Build number** 7 times, then
   Settings > System > Developer options > turn on **USB debugging**.
2. Plug in and press Run in Android Studio, or copy the APK to the phone and open it.
3. Settings > System > Keyboard > On-screen keyboard > enable the newly installed
   HeliBoard (the debug build installs separately from any regular HeliBoard), then
   switch to it from any text box.
4. For swipe typing, follow the HeliBoard wiki's steps for loading the gesture typing
   library.

## Tweaking

Everything adjustable lives at the top of
`app/src/main/java/helium314/keyboard/keyboard/gif/GiphyClient.kt` in `GifConfig`:

- `RATING`: `"r"` is GIPHY's least filtered setting; `"pg-13"`, `"pg"`, `"g"` are tamer.
- `QUICK_PICKS`: the chips at the top.
- `REPLACE_TYPED_QUERY`: set to `false` to keep your typed words after sending.
- `PAGE_SIZE` / `MAX_RESULTS`: how many GIFs load per scroll and in total.

Searches use the keyboard's current language, so with a Spanish layout active
`hola` searches Spanish GIFs.

## Files touched

- New: `keyboard/gif/GiphyClient.kt`, `keyboard/gif/GifPanelView.kt`, `res/drawable/ic_gif.xml`
- `KeyboardSwitcher.java`: shows/hides the panel over the keyboard
- `InputLogic.java`: handles the GIF key and grabs the typed words
- `KeyCode.kt`, `ToolbarUtils.kt`, `KeyboardIconsSet.kt`: the new toolbar key
- `main_keyboard_frame.xml`, `KeyboardWrapperView.kt`: panel placement (incl. one-handed mode)
- `AndroidManifest.xml`: internet permission; `clipboard_provider_path.xml`: shares the GIF file
- `app/build.gradle.kts`: reads `giphyApiKey`

HeliBoard is GPL-3.0, so if you share APKs, share this source too (a public GitHub
fork covers it).
