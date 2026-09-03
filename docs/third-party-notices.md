# Third-party notices

Assets and libraries this project redistributes, and the terms they arrive under. Anything merely
*fetched at runtime* — card art, for instance — is not listed here, because it is not redistributed.

---

## Mana font

**Files:** `core/designsystem/src/main/res/font/mana.ttf`
**Project:** [Mana](https://mana.andrewgioia.com/) by Andrew Gioia —
<https://github.com/andrewgioia/mana>, version 1.18.0.

**The licensing is not all one thing**, and the distinction matters because only one part of it is
shipped here. From the project's own README:

> All mana, tap, and card type symbol images are copyright Wizards of the Coast
>
> The Mana font is licensed under the the SIL OFL 1.1
>
> Mana CSS, LESS, and Sass files are licensed under the MIT License
>
> Attribution is **greatly appreciated** but not required!

So:

- **The font file is SIL OFL 1.1**, not MIT. The MIT part is the stylesheets, which this project does
  not use — the glyph codepoints were read out of `mana.css` and transcribed into
  `ManaSymbols.kt`, and no CSS is redistributed.
- **The symbol artwork remains © Wizards of the Coast.** That is the same standing position every
  Magic client is in, upstream XMage included; it is noted rather than resolved here.

**What OFL 1.1 requires of us**, and how it is met:

| Condition | Here |
|---|---|
| The font may be bundled and redistributed with software, including commercially | It is bundled, unmodified |
| The licence and copyright notice must travel with it | This file |
| The font may not be sold on its own | It is not sold at all |
| A modified version may not use a Reserved Font Name | The font is not modified |

The Mana project ships no `LICENSE` file of its own, which is why the notice is reproduced here rather
than vendored alongside the binary. The full licence text is at <https://scripts.sil.org/OFL>.

Attribution is not required by the project, and is given anyway: the symbols in this app are Andrew
Gioia's Mana font.

---

## Why the font rather than downloading the symbols

Recorded because the alternative was implemented first and then replaced, and the reasoning should
outlive the commit.

Upstream's client does not ship symbol art. It scrapes Scryfall's stylesheet at runtime, decodes the
base64 SVGs embedded in it, and caches them per user — because it may not redistribute what it does
not have a licence to. Following that would have meant a first-run download, a cache to manage, a
dependency on the shape of a CSS file nobody promised to keep stable, and an app that shows braces
instead of symbols until it has been online.

The Mana font can be redistributed. Shipping it costs 400 KB and removes all of the above.
