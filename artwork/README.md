# DroidDeck mark

`droiddeck-mark.svg` is the editable source for the white split-D mark. The blue orb is `#1A9FFF`; its halo and the two separator bars are transparent cutouts. The bars are centered on x=63.97, the endpoint of the curved half in the original Steam Deck symbol, and use the halo's 15.83-unit thickness. The mark is optically centered in its view box with a small rightward offset to balance its asymmetric silhouette. Place it on black when a background is needed.

Android currently consumes raster resources for the launcher layers and the Compose logo. The `mipmap-*/ic_launcher_foreground.png` files are density-sized exports of the SVG, with the mark kept inside the adaptive icon safe area. The matching backgrounds are black. The `_light` variants recolor the mark black on a white background and back the light launcher icon users can pick in Settings. `drawable-nodpi/logo.png` composes the same mark on a black circular badge.

## Distribution logos

`drawable-nodpi/distro_*.png` are PNG exports of free-licensed distribution marks from Wikimedia Commons, used as VM card and desktop wall art (`VmCatalog.logoRes`):

- `distro_ubuntu.png` — [Ubuntu logo, no wordmark, solid o, 2022](https://commons.wikimedia.org/wiki/File:Ubuntu-logo-no-wordmark-solid-o-2022.svg), Canonical Ltd., GPLv3.
- `distro_debian.png` — [Debian logo, no text](https://commons.wikimedia.org/wiki/File:Debian-logo-notext_heyiya-if.svg), User:Skymandr, CC0. The wordmark variants set the name in black, which vanishes on the dark wall, so only the swirl is used.
- `distro_arch.png` — [Arch Linux "Crystal" icon](https://commons.wikimedia.org/wiki/File:Arch_Linux_%22Crystal%22_icon.svg), Judd Vinet, Aaron Griffin and Levente Polyák, GPL.
- `drawable/distro_omarchy.xml` — [Omarchy mark](https://github.com/simple-icons/simple-icons/blob/develop/icons/omarchy.svg), Simple Icons contributors, CC0. Kept as a vector because the stepped block emblem stays crisp at card size; `DistroGlyph` tints it like the raster marks.

Only the four built-in distributions carry art; every other system keeps the generic icon.
