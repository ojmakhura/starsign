#!/usr/bin/env python3
"""Builds the StarSign brand artefacts from the SVG masters in brand/src.

Needs Inkscape (text-to-path and rasterising; the masters use Liberation Sans Bold) and Pillow.
Run from anywhere:  python3 brand/build.py
Outputs: brand/dist (everything), copied into the portal's public/ and the Keycloak theme.
"""
import shutil
import subprocess
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent
SRC = ROOT / 'src'
DIST = ROOT / 'dist'
REPO = ROOT.parent
PORTAL = REPO / 'starsign-portal' / 'public'
KEYCLOAK = REPO / 'docker' / 'compose' / 'keycloak' / 'themes' / 'starsign' / 'login' / 'resources' / 'img'

DIST.mkdir(exist_ok=True)


def inkscape(*args):
    subprocess.run(['inkscape', *map(str, args)], check=True, capture_output=True)


def outline(name):
    """Plain SVG with the text converted to paths, so it renders the same without the font."""
    target = DIST / f'{name}.svg'
    inkscape(SRC / f'{name}.svg', '--export-text-to-path', '--export-plain-svg', '--export-filename', target)
    return target


def png(svg, target, width, height=None, background=None):
    args = [svg, '--export-type=png', '--export-filename', target, '-w', width]
    if height:
        args += ['-h', height]
    if background:
        args += ['-b', background, '-y', '1']
    inkscape(*args)
    return target


# Compact wordmarks (mark + name, no tagline) for the sidebar and narrow headers
import re
for mode in ['light', 'dark']:
    lockup = (SRC / f'logo-{mode}.svg').read_text()
    compact = re.sub(r'\n  <text x="256" y="190"[^\n]*</text>', '', lockup)
    compact = compact.replace('viewBox="0 0 860 240" width="860"', 'viewBox="0 0 660 240" width="660"')
    compact = compact.replace('<text x="252" y="136"', '<text x="252" y="154"').replace(' — Secure digital signatures &amp; audit', '')
    (SRC / f'wordmark-{mode}.svg').write_text(compact)

svgs = {name: outline(name) for name in ['mark', 'mark-fullbleed', 'logo-light', 'logo-dark', 'wordmark-light', 'wordmark-dark', 'seal']}

# Favicons and app icons
shutil.copy(svgs['mark'], DIST / 'favicon.svg')
sizes = [16, 32, 48, 64]
frames = [Image.open(png(svgs['mark'], DIST / f'favicon-{s}.png', s)).convert('RGBA') for s in sizes]
frames[-1].save(DIST / 'favicon.ico', sizes=[(s, s) for s in sizes], append_images=frames[:-1])
png(svgs['mark-fullbleed'], DIST / 'apple-touch-icon.png', 180)
png(svgs['mark'], DIST / 'icon-192.png', 192)
png(svgs['mark'], DIST / 'icon-512.png', 512)
png(svgs['mark-fullbleed'], DIST / 'icon-maskable-512.png', 512)

# Raster logos: e-mail clients do not render SVG. 2x for a 240px-wide header.
png(svgs['logo-light'], DIST / 'email-logo.png', 560, background='#ffffff')
png(svgs['logo-light'], DIST / 'logo-light.png', 860)
png(svgs['logo-dark'], DIST / 'logo-dark.png', 860)
png(svgs['seal'], DIST / 'seal.png', 256)
png(svgs['seal'], DIST / 'seal-512.png', 512)

# Social / Open Graph card, 1200x630
og = Image.new('RGBA', (1200, 630), '#0a0d14')
glow = Image.new('RGBA', (1200, 630), (0, 0, 0, 0))
logo = Image.open(png(svgs['logo-dark'], DIST / '_og-logo.png', 760)).convert('RGBA')
seal = Image.open(png(svgs['seal'], DIST / '_og-seal.png', 300)).convert('RGBA')
og.alpha_composite(logo, (60, (630 - logo.height) // 2))
og.alpha_composite(seal, (1200 - seal.width - 50, (630 - seal.height) // 2))
og.convert('RGB').save(DIST / 'og-image.png', optimize=True)
for tmp in DIST.glob('_*'):
    tmp.unlink()

# Copy into the consumers
brand = PORTAL / 'brand'
brand.mkdir(exist_ok=True)
for name in ['logo-light.svg', 'logo-dark.svg', 'wordmark-light.svg', 'wordmark-dark.svg', 'seal.svg', 'mark.svg', 'email-logo.png', 'og-image.png',
             'icon-192.png', 'icon-512.png', 'icon-maskable-512.png', 'seal.png', 'logo-light.png', 'logo-dark.png']:
    shutil.copy(DIST / name, brand / name)
for name in ['favicon.ico', 'favicon.svg', 'apple-touch-icon.png', 'mark.svg']:
    shutil.copy(DIST / name, PORTAL / name)
if KEYCLOAK.parent.parent.exists():
    KEYCLOAK.mkdir(parents=True, exist_ok=True)
    for name in ['logo-light.svg', 'logo-dark.svg', 'wordmark-light.svg', 'wordmark-dark.svg', 'mark.svg', 'favicon.ico']:
        shutil.copy(DIST / name, KEYCLOAK / name)
print('Built', sorted(p.name for p in DIST.iterdir()))
