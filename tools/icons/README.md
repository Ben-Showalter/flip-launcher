# Generated icons

`make_icons.py` renders the launcher's `ic_*` icons from `icons.csv` into
`app/src/main/res/drawable-nodpi/` and regenerates
`app/src/main/java/com/flipos/launcher/data/BuiltInIconMap.kt`.

Each icon is a 192px transparent canvas with a large glyph from
[Material Design Icons](https://pictogrammers.com/library/mdi/) (Apache-2.0)
in one flat color, on a 50% transparent black tile that the launcher clips to
its squircle. There's no gradient or shadow.

## Add or change an icon

1. Add a row to `icons.csv`: `name,mdi-glyph,color,match`.
   - `mdi-glyph` is the icon's name on pictogrammers.com (e.g. `calculator-variant`).
     Use generic symbols, not brand logos.
   - `color` is a key of `PALETTE` in `make_icons.py`.
   - `match` (optional, space-separated) decides which apps get it from
     Appearance → App Icons → Use Flip Launcher icons: `cat:<dialer|sms|contacts|camera|gallery|calendar|email|music|settings>`,
     `pkg:<package>` (that app's main entry) or `cmp:<package>/<class>` (one exact entry).
   Row order is the order in the Change Icon picker; the first matching row wins.
2. Render:
   ```
   python3 -m venv /tmp/icons-venv && /tmp/icons-venv/bin/pip install cairosvg pillow
   /tmp/icons-venv/bin/python tools/icons/make_icons.py
   ```
   It downloads glyphs from GitHub; behind a TLS-intercepting proxy set
   `SSL_CERT_FILE` to the proxy's CA bundle.
3. Commit the CSV, the PNGs and `BuiltInIconMap.kt` together.
