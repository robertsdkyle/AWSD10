# Custom build: GIF panel and text stickers

This fork adds a **GIF** key to the toolbar (next to Clipboard). Tap it and the
letters are replaced by a GIF keyboard mode:

- **Its own search box.** Tap the search box at the top and the letter keys come back with
  the search field above them. What you type goes into the search, not into the app.
  Results update as you type (after a short pause). Enter or the back arrow shows the
  full grid again.
- **Search what you typed.** Type `happy birthday`, tap GIF, and it searches the last
  few words on the line. Sending a GIF from that search deletes the words you typed,
  so the GIF replaces them.
- **Recent and Trending** show when the search is empty. Sections with nothing in them
  are hidden. If loading fails you get a message and a Retry button, never a blank grid.
- **Text stickers.** Whenever there is search text, a Stickers row shows that text drawn
  in four animated styles (bounce, wobble, rainbow, pop). They are made on the phone; no
  network needed.
- **Chips** across the top: your typed search, Trending, and quick picks (lol, yes,
  no, hype, bruh...).
- **Tap a GIF or sticker to send it.** Apps that accept images from keyboards (Messages,
  WhatsApp, Discord, Telegram...) get the actual animated GIF. Apps that don't get
  the GIF's link (or, for stickers, the plain text) instead.
- Tap **X** or the GIF key again to go back to typing.
- **Privacy.** The keyboard only goes online for the GIF panel. In password and incognito
  fields the panel loads nothing. Settings > Preferences > GIFs > **Enable GIFs** turns the
  whole thing off (and forgets recent GIFs).
- More columns on tablets and in landscape.

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
- `SEARCH_DEBOUNCE_MS`: how long to wait after typing before searching.
- `RECENT_MAX`: how many recently sent GIFs to remember.
- `TILE_TARGET_DP` / `MIN_COLUMNS`: grid tile size and the minimum number of columns.

Sticker styles and colors are in `keyboard/gif/sticker/StickerRenderer.kt`.
- `REPLACE_TYPED_QUERY`: set to `false` to keep your typed words after sending.
- `PAGE_SIZE` / `MAX_RESULTS`: how many GIFs load per scroll and in total.

Searches use the keyboard's current language, so with a Spanish layout active
`hola` searches Spanish GIFs.

## How stickers work

Each sticker is described by a "recipe" URI, for example
`sticker://text?anim=bounce&line=LOL&line=ok&w=256&h=256` (optional `start` / `stop`
pick part of the animation loop, in percent). The renderer draws the text with Android's
own drawing tools (system bold font, outline, extrude), makes about 20 frames, and encodes
them with a small built-in GIF encoder. A Glide loader for `sticker://` URIs means stickers
are cached on disk by recipe and scroll like any other GIF. When sending, apps that don't
take GIFs get an animated WebP, then a still PNG, then plain text, whichever they accept.

## Files touched

- `keyboard/gif/`: `GifController.kt` (state, search, sending), `GifPanelView.kt` (grid),
  `GifSearchStripView.kt` (search field over the letters), `GiphyClient.kt`, `GifSender.kt`,
  `GifImages.kt` (Glide setup), `GifRecents.kt`, `GifQuery.kt`
- `keyboard/gif/sticker/`: `StickerRecipe.kt`, `StickerRenderer.kt`, `GifEncoder.kt`,
  `AnimatedWebpWriter.kt`, `StickerModelLoader.kt`
- `KeyboardSwitcher.java`: GIF mode views (grid / search strip) and their transitions
- `KeyboardActionListenerImpl.kt`: sends keystrokes to the GIF search while it's being typed
- `InputLogic.java`: handles the GIF key, the setting and private fields, grabs the typed words
- `KeyCode.kt`, `ToolbarUtils.kt`, `KeyboardIconsSet.kt`: the toolbar key
- `main_keyboard_frame.xml`, `KeyboardWrapperView.kt`: panel placement (incl. one-handed mode)
- Settings: `Settings.java`, `SettingsValues.java`, `Defaults.kt`, `PreferencesScreen.kt`
- `AndroidManifest.xml`: internet permission; `clipboard_provider_path.xml`: shares the GIF file
- `app/build.gradle.kts`: reads `giphyApiKey`, adds Glide
- Tests: `app/src/test/java/helium314/keyboard/gif/StickerTest.kt`

HeliBoard is GPL-3.0, so if you share APKs, share this source too (a public GitHub
fork covers it).
