# `:core:designsystem`

The Mage Free client's shared visual system: the branded Material 3 [`MageTheme`](src/main/kotlin/magefree/designsystem/theme/MageTheme.kt)
(color, typography, shape, tokens), the reusable components (buttons, list rows, section chrome,
state surfaces, the decision prompt), the card-forward components (card tile, full card view), and
the adaptive / accessible foundations described below. Everything is stateless, previewable, and
token-styled — no magic numbers or colors.

## Adaptive layout foundations

### Window size class — one access point

`layout/WindowSize.kt` is the **single** place that derives the window width bucket. Callers obtain a
design-system `WindowWidthClass` (`Compact` / `Medium` / `Expanded`, with `usesBottomBar` /
`usesNavigationRail` helpers) from `windowWidthClass(size)` — the one call site of the experimental
Material window size-class API. Because that experimental call is centralized here, migrating to the
stable `currentWindowAdaptiveInfo()` later is a single-file edit with no caller changes.

- Runtime: `windowWidthClass(DpSize)` (e.g. a `BoxWithConstraints` `DpSize(maxWidth, maxHeight)`).
- Pure / testable: `windowWidthClassFor(width: Dp)` — the same thresholds (`600.dp` / `840.dp`),
  no Compose runtime, unit-tested in `WindowSizeTest`.

The app shell (`:app` `AppShell`) chooses a bottom `NavigationBar` (compact) vs. a side
`NavigationRail` (medium/expanded) from this class.

### Inset ownership — one convention

`layout/Insets.kt` documents and enforces a single rule so system-bar / cutout / chrome insets are
applied exactly once:

- **The shell owns *chrome* insets.** It applies the system-bar / cutout insets its navigation chrome
  occupies **and marks them consumed** (`consumeWindowInsets` / `windowInsetsPadding`, both
  consumption-aware). The connection strip and nav host sit inside that consumed region.
- **Screens own only *content* insets.** A screen adds `Modifier.contentInsets()` for the unconsumed
  safe-drawing insets (typically a side display cutout). It is consumption-aware, so it never
  re-pads what the shell already handled and cannot double-count.

Screens must **not** use `Modifier.safeDrawingPadding()` / `systemBarsPadding()` directly — that
re-adds the full system-bar inset on top of the shell's and double-counts (the original `HomeScreen`
bug, where bottom padding stacked on top of the bottom bar). `HomeScreen` now uses `contentInsets()`.

## Accessibility rules (design-system requirements)

These are requirements for every component and screen, not nice-to-haves:

- **Dynamic type.** Text and its containers must grow with the user's font scale without clipping or
  truncating meaningful content. Verify with the `@FontScalePreviews` multipreview
  (`theme/Previews.kt`) at 1.0× / 1.3× / 2.0×; representative components and the full
  `ComponentCatalog` carry it. Prefer wrapping/scrolling over fixed heights; avoid `maxLines` on
  content that must stay legible.
- **Touch targets ≥ 48dp.** Interactive elements meet `Sizing.minTouchTarget` (48dp). The button
  wrappers, list rows, and `Modifier.cardInspectable` enforce this.
- **Content descriptions.** Every interactive/meaningful element has a content description; decorative
  icons set `contentDescription = null` and let their label carry the meaning. Merged surfaces (the
  decision prompt header, the card tile) expose one coherent description.
- **Never state by color alone.** State (loading / empty / error, emphasis) is always carried by text
  and/or shape; color only reinforces it.

## Developer component catalog

`catalog/ComponentCatalog.kt` is a scrollable gallery that renders every component in its states. Use
its `@ThemePreviews` (light/dark) and `@FontScalePreviews` for static QA. It is hosted live behind a
**debug-only** entry: `:app`'s `ComponentCatalogScreen` (reached from the Settings dev affordance,
`CatalogRoute`, outside the shell chrome) wraps it with a light/dark toggle and a simulated-width
toggle. This is the chosen host (a debug entry in `:app`), not a production destination.
