"""Logo concepts for ItemGuard, drawn as vectors and judged at the size that matters.

SpigotMC renders a resource icon at 96x96. That is the real constraint, and it is what the
current logo fails: a shaded wooden chest with a padlock has four or five separate elements,
a dark palette, and fine texture - at 96px it collapses into a brown smudge.

Every competing icon that works obeys the same three rules:

    CoreProtect  1.2M downloads   'CO' monogram, flat blue, two colours
    X-WARDEN     new premium      one shield, one letter, red on black
    LuckPerms    8.8M downloads   one clover, flat green, two colours
    EssentialsX  5.9M downloads   one flask, flat pink, two colours

    -> ONE shape. FLAT fill. TWO or THREE colours. No gradients, no texture, no scene.

Each concept below is built from geometry rather than illustration, so it stays sharp at any
size and can be re-coloured in one place. Run this file to write every concept at 512 plus a
96px contact sheet for judging.
"""
import math
import pathlib

from PIL import Image, ImageDraw, ImageFont

OUT = pathlib.Path('E:/AI.WORK/ItemGuard/docs/release/assets/logo-concepts')
OUT.mkdir(parents=True, exist_ok=True)

S = 512                       # working size; everything scales from here
BOLD = 'C:/Windows/Fonts/bahnschrift.ttf'
HEAVY = 'C:/Windows/Fonts/seguibl.ttf'

# Palette. Deliberately narrow: a logo that needs more than three colours is an illustration.
INK = (17, 18, 20)
PAPER = (247, 247, 245)
AMBER = (232, 168, 44)        # the one accent - reads at 96px against both ink and paper
TEAL = (38, 178, 168)
CRIMSON = (206, 63, 58)


def canvas(bg):
    img = Image.new('RGB', (S, S), bg)
    return img, ImageDraw.Draw(img)


def save(img, name):
    path = OUT / f'{name}.png'
    img.save(path)
    return path


# ---------------------------------------------------------------------------------------
# 1. Monogram in a slot
#
# CoreProtect's 'CO' is the most-downloaded icon on the platform and it is two letters on a
# flat field. The Minecraft-native twist: set the letters inside an inventory slot, the square
# bevel every player recognises instantly.
# ---------------------------------------------------------------------------------------
def concept_slot_monogram():
    img, d = canvas(AMBER)
    m = S * 0.16
    d.rounded_rectangle([m, m, S - m, S - m], radius=int(S * 0.06), fill=INK)
    f = ImageFont.truetype(HEAVY, int(S * 0.34))
    text = 'IG'
    w = d.textlength(text, font=f)
    box = f.getbbox(text)
    d.text(((S - w) / 2, (S - (box[3] - box[1])) / 2 - box[1]), text, font=f, fill=AMBER)
    return save(img, '01-slot-monogram')


# ---------------------------------------------------------------------------------------
# 2. Two squares, one marked
#
# States the product in pure geometry: two identical objects, and the tool can tell them
# apart. No metaphor to decode, and it is the only concept here that says what the plugin
# actually does rather than "security in general".
# ---------------------------------------------------------------------------------------
def concept_twin_mark():
    img, d = canvas(PAPER)
    size = S * 0.30
    gap = S * 0.07
    total = size * 2 + gap
    x0 = (S - total) / 2
    y0 = (S - size) / 2
    r = int(S * 0.045)
    d.rounded_rectangle([x0, y0, x0 + size, y0 + size], radius=r, fill=INK)
    x1 = x0 + size + gap
    d.rounded_rectangle([x1, y1 := y0, x1 + size, y1 + size], radius=r, fill=AMBER)
    # the mark: a single dot, the identity the second object carries
    dot = size * 0.22
    cx, cy = x1 + size / 2, y1 + size / 2
    d.ellipse([cx - dot / 2, cy - dot / 2, cx + dot / 2, cy + dot / 2], fill=INK)
    return save(img, '02-twin-mark')


# ---------------------------------------------------------------------------------------
# 3. Shield with a slot cut out
#
# The category shape (shield = protection) but reduced to a single silhouette with a square
# void, so it stays one object at 96px. X-WARDEN uses a shield too, which is an argument for
# keeping ours flatter and lighter than theirs rather than competing on the same dark red.
# ---------------------------------------------------------------------------------------
def concept_shield_slot():
    img, d = canvas(INK)
    w, h = S * 0.52, S * 0.60
    x, y = (S - w) / 2, (S - h) / 2
    shield = [
        (x + w / 2, y),
        (x + w, y + h * 0.18),
        (x + w, y + h * 0.58),
        (x + w / 2, y + h),
        (x, y + h * 0.58),
        (x, y + h * 0.18),
    ]
    d.polygon(shield, fill=TEAL)
    side = w * 0.34
    cx, cy = x + w / 2, y + h * 0.44
    d.rounded_rectangle([cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2],
                        radius=int(side * 0.16), fill=INK)
    return save(img, '03-shield-slot')


# ---------------------------------------------------------------------------------------
# 4. Fingerprint block
#
# The idea in one image: an item with a fingerprint. Concentric arcs inside a slot silhouette.
# Riskiest of the set at small size - arcs need enough stroke weight to survive, which is why
# the ring count is three and not six.
# ---------------------------------------------------------------------------------------
def concept_fingerprint():
    img, d = canvas(PAPER)
    m = S * 0.20
    d.rounded_rectangle([m, m, S - m, S - m], radius=int(S * 0.07), fill=INK)
    cx, cy = S / 2, S / 2
    stroke = int(S * 0.045)
    for i, r in enumerate((S * 0.075, S * 0.135, S * 0.195)):
        # open arcs, not full circles: a fingerprint reads by its breaks
        start, end = (200, 340) if i % 2 == 0 else (20, 160)
        d.arc([cx - r, cy - r, cx + r, cy + r], start, end, fill=AMBER, width=stroke)
        d.arc([cx - r, cy - r, cx + r, cy + r], start + 180, end + 180, fill=AMBER, width=stroke)
    d.ellipse([cx - stroke * 0.7, cy - stroke * 0.7, cx + stroke * 0.7, cy + stroke * 0.7],
              fill=AMBER)
    return save(img, '04-fingerprint')


# ---------------------------------------------------------------------------------------
# 5. Tag
#
# A luggage tag is the literal object the plugin attaches. Single silhouette, one hole, one
# flat colour - the simplest shape in the set and therefore the safest at 96px.
# ---------------------------------------------------------------------------------------
def concept_tag():
    img, d = canvas(CRIMSON)
    w, h = S * 0.52, S * 0.44
    x, y = (S - w) / 2, (S - h) / 2
    cut = w * 0.26
    d.polygon([
        (x + cut, y), (x + w, y), (x + w, y + h), (x + cut, y + h), (x, y + h / 2),
    ], fill=PAPER)
    hole = w * 0.11
    hx, hy = x + cut * 0.78, y + h / 2
    d.ellipse([hx - hole / 2, hy - hole / 2, hx + hole / 2, hy + hole / 2], fill=CRIMSON)
    bar = h * 0.11
    for i in range(2):
        by = y + h * (0.36 + i * 0.26)
        d.rounded_rectangle([x + w * 0.42, by, x + w * 0.84, by + bar],
                            radius=int(bar / 2), fill=CRIMSON)
    return save(img, '05-tag')


# ---------------------------------------------------------------------------------------
# 6. Slot with a corner notch
#
# The most restrained option: the inventory slot alone, with one corner cut away and refilled
# in the accent. Reads as "this square is marked" without any literal object. Closest in
# spirit to LuckPerms - a single abstract shape that becomes recognisable through repetition.
# ---------------------------------------------------------------------------------------
def concept_notch():
    img, d = canvas(INK)
    m = S * 0.22
    r = int(S * 0.07)
    d.rounded_rectangle([m, m, S - m, S - m], radius=r, fill=PAPER)
    notch = (S - 2 * m) * 0.42
    d.polygon([(S - m - notch, m), (S - m, m), (S - m, m + notch)], fill=AMBER)
    d.rounded_rectangle([m, m, S - m, S - m], radius=r, outline=INK, width=int(S * 0.025))
    return save(img, '06-notch')


def export_chosen():
    """Write the selected mark at every size the listing and repo need.

    Concept 01 was chosen. Rendering each size from the vector source rather than
    downscaling one PNG keeps the letterforms crisp: at 64px a downscaled 512 would blur the
    stroke joins, while re-drawing lets the glyph sit on whole pixels.
    """
    out = pathlib.Path('E:/AI.WORK/ItemGuard/docs/release/assets')
    global S
    original = S
    made = []
    for size in (512, 256, 128, 96, 64):
        S = size
        img, d = canvas(AMBER)
        m = S * 0.16
        d.rounded_rectangle([m, m, S - m, S - m], radius=max(2, int(S * 0.06)), fill=INK)
        # Scale the type a little larger on small canvases: at 64px the glyphs need to claim
        # more of the slot or they read as a dark square with specks in it.
        ratio = 0.34 if size >= 128 else 0.40
        f = ImageFont.truetype(HEAVY, max(8, int(S * ratio)))
        text = 'IG'
        w = d.textlength(text, font=f)
        box = f.getbbox(text)
        d.text(((S - w) / 2, (S - (box[3] - box[1])) / 2 - box[1]), text, font=f, fill=AMBER)
        path = out / f'itemguard-logo-{size}.png'
        img.save(path)
        made.append(path)
    S = original
    return made


def contact_sheet(paths):
    """Judge at 96px. Anything that fails here fails on the resource list."""
    cell, gap, label = 96, 24, 26
    sheet = Image.new('RGB', (len(paths) * cell + (len(paths) - 1) * gap, cell + label),
                      (14, 15, 17))
    d = ImageDraw.Draw(sheet)
    f = ImageFont.truetype(BOLD, 13)
    for i, p in enumerate(paths):
        x = i * (cell + gap)
        sheet.paste(Image.open(p).convert('RGB').resize((cell, cell), Image.LANCZOS), (x, 0))
        d.text((x, cell + 7), p.stem.split('-', 1)[0], font=f, fill=(150, 152, 158))
    out = OUT / 'contact-96.png'
    sheet.save(out)
    return out


if __name__ == '__main__':
    made = [
        concept_slot_monogram(),
        concept_twin_mark(),
        concept_shield_slot(),
        concept_fingerprint(),
        concept_tag(),
        concept_notch(),
    ]
    for p in made:
        print(p.name)
    print(contact_sheet(made).name)
    for p in export_chosen():
        print(p.name)
