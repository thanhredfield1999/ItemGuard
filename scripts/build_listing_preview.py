"""Render the Spigot listing preview from the description, and refuse to render a lie.

PREVIEW.html was the last hand-kept copy in the release folder: nothing generated it, so on
2026-09-17 it still announced "Tested on 14 servers" and carried the SHA of a jar that had been
replaced. A preview that disagrees with the description is worse than no preview, because it is
the file the owner reads before uploading.

So the preview is generated, never edited, and it fails loudly when the description disagrees
with the artifact it describes:

    python scripts/build_listing_preview.py

Only the BBCode subset the description actually uses is supported. An unsupported tag is a hard
error rather than something silently rendered as literal text, which is how a stray `[B]` would
otherwise reach a preview and look like a typo to the reader instead of a bug to us.
"""
from __future__ import annotations

import hashlib
import html
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
UPLOAD = REPO / "release" / "spigot-upload"
DESCRIPTION = UPLOAD / "description.bbcode.txt"
JAR = UPLOAD / "ItemGuard-LITE-1.0.0.jar"
TARGET = UPLOAD / "PREVIEW.html"

KNOWN_TAGS = {
    "[B]", "[/B]", "[I]", "[/I]", "[CODE]", "[/CODE]", "[LIST]", "[/LIST]", "[LIST=1]",
    "[/LIST]", "[*]",
}

SHELL = """<!doctype html><meta charset="utf-8">
<title>ItemGuard LITE - listing preview</title>
<style>
body{{background:#2b2b2b;color:#dcdcdc;font:15px/1.6 "Segoe UI",sans-serif;max-width:940px;
margin:0 auto;padding:28px}}
b{{color:#fff}} pre{{background:#1f1f1f;border:1px solid #3a3a3a;border-radius:6px;padding:14px;
overflow-x:auto;font:13px/1.5 Consolas,monospace;color:#c8e6c9}}
details{{background:#1f1f1f;border:1px solid #3a3a3a;border-radius:6px;padding:12px 14px;
margin:14px 0}} summary{{cursor:pointer;color:#ffd54f;font-weight:600}}
ul{{margin:8px 0 8px 22px}} a{{color:#80cbc4}}
.sha{{background:#1f1f1f;border-left:3px solid #ffd54f;padding:10px 14px;font:13px Consolas,monospace}}
</style>
<p class="sha">Preview generated from description.bbcode.txt by scripts/build_listing_preview.py.<br>
Jar in this folder: {sha}</p>
{body}
"""


def check_descriptor_matches_jar(text: str) -> str:
    """The description states the SHA of the jar it describes. It has to be this jar."""
    digest = hashlib.sha256(JAR.read_bytes()).hexdigest()
    if digest not in text:
        raise SystemExit(
            "description.bbcode.txt does not quote the SHA-256 of the jar in this folder.\n"
            f"  jar         {digest}\n"
            "Fix the description (or repackage) before rendering the preview."
        )
    return digest


def unsupported_tags(text: str) -> list:
    """Tag-shaped tokens the renderer does not know, so a new one cannot slip through unnoticed."""
    found = {tag for tag in re.findall(r"\[/?[A-Za-z][^\]]*\]", text)
             if tag.upper() not in KNOWN_TAGS and not tag.upper().startswith(
                 ("[SPOILER", "[/SPOILER", "[URL", "[/URL", "[SIZE", "[/SIZE"))}
    return sorted(found)


def render(text: str) -> str:
    blocks = []
    for raw in re.split(r"\n{2,}", text):
        chunk = html.escape(raw.strip())
        chunk = re.sub(r"\[CODE\](.*?)\[/CODE\]", r"<pre>\1</pre>", chunk, flags=re.S)
        chunk = re.sub(r"\[SPOILER=&quot;(.*?)&quot;\]", r'<details><summary>\1</summary>', chunk)
        chunk = chunk.replace("[/SPOILER]", "</details>")
        chunk = re.sub(r"\[URL=([^\]]+)\]", r'<a href="\1">', chunk).replace("[/URL]", "</a>")
        chunk = re.sub(r"\[SIZE=\d+\]", "", chunk).replace("[/SIZE]", "")
        chunk = chunk.replace("[B]", "<b>").replace("[/B]", "</b>")
        chunk = chunk.replace("[I]", "<i>").replace("[/I]", "</i>")
        chunk = re.sub(r"\[LIST=1\](.*?)\[/LIST\]", r"<ol>\1</ol>", chunk, flags=re.S)
        chunk = re.sub(r"\[LIST\](.*?)\[/LIST\]", r"<ul>\1</ul>", chunk, flags=re.S)
        chunk = chunk.replace("[*]", "<li>")
        # A single newline inside one paragraph is a line break, except inside <pre> where the
        # block is already laid out and must stay byte-faithful.
        parts = re.split(r"(<pre>.*?</pre>)", chunk, flags=re.S)
        for index, part in enumerate(parts):
            if not part.startswith("<pre>"):
                parts[index] = part.replace("\n", "<br>")
        chunk = "".join(parts)
        if chunk.startswith("<ul>"):
            chunk = chunk.replace("<li>", "<li>", 1)
            chunk = re.sub(r"<li>(?![^<]*</li>)", "<li>", chunk)
        blocks.append(f"<p>{chunk}</p>" if not chunk.startswith(("<pre>", "<details>", "<ul>"))
                      else chunk)
    return "\n".join(blocks)


def main() -> None:
    text = DESCRIPTION.read_text(encoding="utf-8")
    digest = check_descriptor_matches_jar(text)
    unknown = unsupported_tags(text)
    if unknown:
        raise SystemExit(f"unsupported BBCode tag(s) in the description: {unknown}")
    TARGET.write_text(SHELL.format(sha=digest, body=render(text)), encoding="utf-8")
    print(f"wrote {TARGET}")
    print(f"  sha256 {digest}")


if __name__ == "__main__":
    main()
