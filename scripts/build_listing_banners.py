"""Build the listing feature banners from the real screenshots.

Every banner is a CROP of a frame the server actually produced, plus a caption strip. No
text from the plugin is ever re-typed into the image: if a claim is not legible in the
screenshot itself, it does not belong on the banner. The captions describe what the frame
shows; they never assert behaviour the frame does not contain.

Input:  docs/release/assets/raw/<time>.png   (1920x1009 client frames)
Output: docs/release/assets/<nn>-<name>.png  (1280x720 banners)
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / 'docs/release/assets/raw'
OUT = ROOT / 'docs/release/assets'

WIDTH, HEIGHT = 1280, 720

# ---------------------------------------------------------------------------------------
# Caption strip design
#
# The first version used a 3px teal rule over a blue-black panel. That is the default
# "feature card" arrangement every generated graphic lands on, and it reads as template
# rather than craft. Three things fix it, none of them colour:
#
#   1. A real type scale. 37/18 is a 2.05 ratio - display against body. The old 31/19 was
#      1.63, close enough to look accidental rather than chosen.
#   2. A third level: a small wide-tracked uppercase label. It carries information (what
#      kind of surface the frame shows) instead of a decorative index number, and it gives
#      the stack a top edge so the title is not floating.
#   3. A scrim. Minecraft frames are busy right down to the last row of pixels; cutting
#      straight to a flat bar looks pasted. A gradient dissolve over the final 108px makes
#      the capture end deliberately.
#
# Typeface is Bahnschrift - a condensed geometric grotesque in the DIN lineage. Technical
# rather than marketing, and specifically not the Inter/Roboto default that signals
# "generated". Body stays Segoe UI: a neutral companion that does not compete.
# ---------------------------------------------------------------------------------------
# 148 not 132: the body line ends at 120px, so a 132 strip left 12px below against 24px
# above - the text sat visibly low in its own band. 148 balances it, with the extra
# few pixels going to the bottom where optical weight wants them.
BAR = 148
PAD_X = 56
# The chat crops now stop at y=961 instead of 1009: that removes the client's hotbar, which
# is Minecraft chrome rather than plugin output, AND makes the box match the banner's own
# aspect exactly - so fill() no longer slices 48px off wherever it likes. Fixing it at the
# crop is correct; hiding it under a taller scrim would have been covering up a bad frame.
SCRIM = 112                   # dissolve height above the strip

BG = (14, 15, 17)             # neutral near-black, no blue cast
INK = (242, 242, 243)         # off-white, never pure #FFF
DIM = (140, 142, 148)         # supporting line
LABEL = (110, 112, 118)       # eyebrow, quietest level
RULE = (34, 35, 39)           # hairline

EYEBROW = ImageFont.truetype('C:/Windows/Fonts/bahnschrift.ttf', 13)
TITLE = ImageFont.truetype('C:/Windows/Fonts/bahnschrift.ttf', 37)
BODY = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 18)

EYEBROW_TRACK = 2.6           # PIL has no letter-spacing; drawn per glyph below
TITLE_TRACK = -0.4            # large type needs tightening to look set, not typed


def tracked(draw, xy, text, font, fill, track):
    """Draw text with manual letter-spacing.

    Wide tracking on small caps and slight negative tracking on display sizes are the
    difference between type that was set and type that was merely rendered. PIL offers
    neither, so each glyph is placed by hand.
    """
    x, y = xy
    for char in text:
        draw.text((x, y), char, font=font, fill=fill)
        x += draw.textlength(char, font=font) + track
    return x


def scrim(banner, top):
    """Fade the capture into the strip instead of butting them together."""
    band = banner.crop((0, top - SCRIM, WIDTH, top)).convert('RGB')
    overlay = Image.new('RGB', band.size, BG)
    mask = Image.linear_gradient('L').resize(band.size)   # 0 at top -> 255 at bottom
    banner.paste(Image.composite(overlay, band, mask), (0, top - SCRIM))


# source, crop box on the 1920x1009 frame, title, caption, crop anchor
#
# Each entry was checked against the frame before being listed: 21.33.35 is the only capture
# with the menu actually open, and 21.42.31 carries the clearest duplicate alerts (four lines,
# Locations: 4). Frames 21.36.00 and 21.42.35 are deliberately unused - they show the old
# "Duplicate findings" wording that was corrected in the shipping build.
BANNERS = [
    ('01-check', '21.27.03.png', (0, 228, 925, 530),
     'Read the ID of any item',
     '/ig check shows the identity \u2014 and says plainly when an item has none.', 'left'),
    ('02-history', '21.33.15.png', (0, 434, 1180, 961),
     'A readable history, per item',
     'The command states its own window on screen: counts are not lifetime totals.',
     'left'),
    # Ends just after the plugin's own closing line; the client's 'Saved screenshot as ...'
    # chatter below it is Minecraft noise, not ItemGuard output.
    ('03-timeline', '21.34.10.png', (0, 434, 1180, 961),
     'Drill into one item',
     '/ig history #ID walks the recorded events for that exact identity.', 'left'),
    # Crop matches the banner's own aspect (1280 x 572 after the caption strip). The old box
    # was 2.049 against a 2.238 area, so fill() scaled to width and sliced the menu's bottom
    # edge off - the inventory rows and the window border were simply gone.
    ('04-gui', '21.33.35.png', (327, 262, 1594, 828),
     'Browse your items in a menu',
     '/ig gui pages through recorded items. Clicks and drags inside are cancelled.'),
    ('05-duplicate-alert', '21.42.31.png', (0, 434, 1180, 961),
     'Duplicate identities are reported',
     'Staff holding itemguard.notify are warned when one identity is seen in several places.',
     'left'),
    # 925x530 client frame, so the box is scaled to that capture rather than the 1920 ones.
    # Cropped below Minecraft's own "Chat messages can't be verified" toast: that is client
    # chrome on an offline test server, not plugin output.
    ('06-stats', '00.03.12.png', (0, 258, 925, 530),
     'Counts that say what they mean',
     'Detections and distinct items are separate numbers \u2014 one item seen in ten places is '
     'still one item.', 'left'),
]


def fit(image, box, anchor='center'):
    """Crop, then scale to fill the frame area without distortion.

    Chat sits flush against the left edge of the client, so a centred crop shaves the first
    characters off lines like "[ItemGuard LITE]". Those frames anchor left instead.
    """
    crop = image.crop(box)
    area = (WIDTH, HEIGHT - BAR)
    scale = max(area[0] / crop.width, area[1] / crop.height)
    crop = crop.resize((round(crop.width * scale), round(crop.height * scale)),
                       Image.LANCZOS)
    left = 0 if anchor == 'left' else (crop.width - area[0]) // 2
    top = (crop.height - area[1]) // 2
    return crop.crop((left, top, left + area[0], top + area[1]))


EYEBROWS = {
    '01-check': 'CHAT  /ig check',
    '02-history': 'CHAT  /ig history',
    '03-timeline': 'CHAT  /ig history #ID',
    '04-gui': 'MENU  /ig gui',
    '05-duplicate-alert': 'ALERT  staff notification',
    '06-stats': 'CHAT  /ig stats',
}


def build(name, source, box, title, caption, anchor='center'):
    frame = Image.open(RAW / source).convert('RGB')
    banner = Image.new('RGB', (WIDTH, HEIGHT), BG)
    banner.paste(fit(frame, box, anchor), (0, 0))

    top = HEIGHT - BAR
    scrim(banner, top)

    draw = ImageDraw.Draw(banner)
    draw.rectangle([0, top, WIDTH, HEIGHT], fill=BG)
    draw.rectangle([0, top, WIDTH, top], fill=RULE)

    # Three levels, each with its own weight and colour, stacked on one left edge. A single
    # alignment spine is what makes a caption read as designed rather than placed.
    tracked(draw, (PAD_X, top + 24), EYEBROWS[name].upper(), EYEBROW, LABEL, EYEBROW_TRACK)
    tracked(draw, (PAD_X, top + 48), title, TITLE, INK, TITLE_TRACK)
    draw.text((PAD_X, top + 96), caption, font=BODY, fill=DIM)

    path = OUT / (name + '.png')
    banner.save(path)
    return path


if __name__ == '__main__':
    for entry in BANNERS:
        print(build(*entry).name)
