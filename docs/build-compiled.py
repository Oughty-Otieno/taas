"""Compiles docs/*.md into one PDF (via headless Chrome) and one Word file (via python-docx).

Usage: python3 build.py <docs dir> <out dir> <work dir>
"""
import html
import json
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_BREAK, WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

DOCS, OUT, WORK = (Path(a) for a in sys.argv[1:4])
WORK.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
PROFILE = WORK / "chrome-profile"
TITLE = "ZenToken: Tokenisation as a Service"
SUBTITLE = "System documentation"
TODAY = date.today().strftime("%d %B %Y").lstrip("0")
GREEN = "0F7B55"
INK = "13201C"

FILES = ["README.md"] + sorted(p.name for p in DOCS.glob("[0-9][0-9]-*.md"))

# ----------------------------------------------------------------------------------------------
# Markdown → blocks (the subset the docs use)
# ----------------------------------------------------------------------------------------------

def parse(text):
    lines = text.replace("\\|", "\x00").split("\n")
    blocks, i = [], 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        if line.startswith("```"):
            lang = line[3:].strip()
            body = []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                body.append(lines[i].replace("\x00", "\\|"))
                i += 1
            i += 1
            blocks.append(("mermaid" if lang == "mermaid" else "code", "\n".join(body)))
            continue
        m = re.match(r"(#{1,4}) (.*)", line)
        if m:
            blocks.append(("h", len(m.group(1)), m.group(2).strip()))
            i += 1
            continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip().replace("\x00", "|") for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-{2,}:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            blocks.append(("table", rows))
            continue
        if line.startswith("> "):
            body = []
            while i < len(lines) and lines[i].startswith(">"):
                body.append(lines[i][1:].strip())
                i += 1
            blocks.append(("quote", " ".join(body).replace("\x00", "|")))
            continue
        m = re.match(r"(\s*)(- |\d+\. )(.*)", line)
        if m:
            ordered = m.group(2)[0].isdigit()
            items = []
            while i < len(lines):
                m = re.match(r"(\s*)(- |\d+\. )(.*)", lines[i])
                if m:
                    items.append([len(m.group(1)) // 2, m.group(3)])
                elif lines[i].startswith("  ") and lines[i].strip() and items:
                    items[-1][1] += " " + lines[i].strip()
                else:
                    break
                i += 1
            blocks.append(("list", ordered, [(lvl, t.replace("\x00", "|")) for lvl, t in items]))
            continue
        body = []
        while i < len(lines) and lines[i].strip() and not re.match(r"(```|#{1,4} |\||> |\s*- |\s*\d+\. )", lines[i]):
            body.append(lines[i].strip())
            i += 1
        blocks.append(("p", " ".join(body).replace("\x00", "|")))
    return blocks


INLINE = re.compile(r"(`[^`]+`|\*\*.+?\*\*|\[[^\]]+\]\([^)]+\)|<https?://[^>]+>|\*[^*\s][^*]*\*)")


def inline(text):
    """Splits text into (kind, text, href) runs: text, code, bold, italic, link."""
    out = []
    pos = 0
    for m in INLINE.finditer(text):
        if m.start() > pos:
            out.append(("text", text[pos:m.start()], None))
        tok = m.group(0)
        if tok.startswith("`"):
            out.append(("code", tok[1:-1], None))
        elif tok.startswith("**"):
            for kind, t, h in inline(tok[2:-2]):
                out.append(("boldcode" if kind == "code" else "bold", t, h))
        elif tok.startswith("["):
            lm = re.match(r"\[([^\]]+)\]\(([^)]+)\)", tok)
            href = lm.group(2)
            out.append(("link" if href.startswith("http") else "text", lm.group(1), href))
        elif tok.startswith("<"):
            out.append(("link", tok[1:-1], tok[1:-1]))
        else:
            out.append(("italic", tok[1:-1], None))
        pos = m.end()
    if pos < len(text):
        out.append(("text", text[pos:], None))
    return out


chapters = []
for name in FILES:
    blocks = parse((DOCS / name).read_text())
    if name == "README.md":
        blocks[0] = ("h", 1, "Introduction")
        # The live-docs paragraph points at sections of this file; keep it.
    chapters.append(blocks)

# ----------------------------------------------------------------------------------------------
# Diagrams: render every Mermaid block in Chrome, keep the SVG (PDF) and a PNG (Word)
# ----------------------------------------------------------------------------------------------

diagrams = [b[1] for ch in chapters for b in ch if b[0] == "mermaid"]


def chrome(*args):
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-first-run", "--hide-scrollbars",
                    f"--user-data-dir={PROFILE}", *args], check=True, capture_output=True, timeout=180)


render_page = WORK / "render.html"
render_page.write_text(f"""<!doctype html><html><head><meta charset="utf-8">
<script src="https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"></script></head><body>
<div id="out"></div>
<script>
const SRC = {json.dumps(diagrams)};
mermaid.initialize({{startOnLoad:false, theme:'base', securityLevel:'strict',
  fontFamily:'Helvetica Neue, Helvetica, Arial, sans-serif',
  themeVariables:{{primaryColor:'#ECE8DD', primaryBorderColor:'#0F7B55', primaryTextColor:'#13201C',
    lineColor:'#47514C', secondaryColor:'#F6F4EE', tertiaryColor:'#F6F4EE', fontSize:'15px',
    actorBkg:'#ECE8DD', actorBorder:'#0F7B55', noteBkgColor:'#F6F4EE', noteBorderColor:'#D5CFC0'}}}});
(async () => {{
  let html = '';
  for (let i = 0; i < SRC.length; i++) {{
    const r = await mermaid.render('dg' + i, SRC[i]);
    html += '<div class="d" data-i="' + i + '">' + r.svg + '</div><!--end-->';
  }}
  document.getElementById('out').innerHTML = html;
}})();
</script></body></html>""")
dom = WORK / "dom.html"
with open(dom, "w") as f:
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-first-run", f"--user-data-dir={PROFILE}",
                    "--virtual-time-budget=30000", "--dump-dom", render_page.as_uri()],
                   check=True, stdout=f, stderr=subprocess.DEVNULL, timeout=180)
svgs = {int(m.group(1)): m.group(2) for m in
        re.finditer(r'<div class="d" data-i="(\d+)">(.*?)</div><!--end-->', dom.read_text(), re.S)}
if len(svgs) != len(diagrams):
    sys.exit(f"Rendered {len(svgs)} of {len(diagrams)} diagrams; see {dom}")

pngs = {}
for i, svg in svgs.items():
    vb = re.search(r'viewBox="([-\d.]+) ([-\d.]+) ([\d.]+) ([\d.]+)"', svg)
    w, h = int(float(vb.group(3))) + 2, int(float(vb.group(4))) + 2
    page = WORK / f"shot{i}.html"
    page.write_text(f"""<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;background:#fff}} svg{{width:{w}px!important;height:{h}px!important;max-width:none!important;display:block}}
</style></head><body>{svg}</body></html>""")
    png = WORK / f"diagram{i}.png"
    chrome(f"--screenshot={png}", f"--window-size={w},{h}", "--force-device-scale-factor=2", page.as_uri())
    pngs[i] = (png, w, h)

# ----------------------------------------------------------------------------------------------
# PDF: one HTML document printed by Chrome
# ----------------------------------------------------------------------------------------------

def slug(text, used):
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-") or "s"
    base, n = s, 2
    while s in used:
        s, n = f"{base}-{n}", n + 1
    used.add(s)
    return s


def html_inline(text):
    out = []
    for kind, t, href in inline(text):
        e = html.escape(t)
        out.append({"code": f"<code>{e}</code>", "bold": f"<b>{e}</b>", "boldcode": f"<b><code>{e}</code></b>",
                    "italic": f"<i>{e}</i>", "link": f'<a href="{html.escape(href or "")}">{e}</a>'}.get(kind, e))
    return "".join(out)


used, toc, body, d_index = set(), [], [], 0
for ci, blocks in enumerate(chapters):
    body.append('<section class="chapter">')
    for b in blocks:
        kind = b[0]
        if kind == "h":
            level, text = b[1], b[2]
            anchor = slug(text, used)
            if level <= 2:
                toc.append((level, text, anchor))
            body.append(f'<h{level} id="{anchor}">{html_inline(text)}</h{level}>')
        elif kind == "p":
            body.append(f"<p>{html_inline(b[1])}</p>")
        elif kind == "quote":
            body.append(f'<div class="note">{html_inline(b[1])}</div>')
        elif kind == "code":
            body.append(f"<pre>{html.escape(b[1])}</pre>")
        elif kind == "mermaid":
            body.append(f'<figure class="diagram">{svgs[d_index]}</figure>')
            d_index += 1
        elif kind == "list":
            tag = "ol" if b[1] else "ul"
            body.append(f"<{tag}>" + "".join(f"<li>{html_inline(t)}</li>" for _, t in b[2]) + f"</{tag}>")
        elif kind == "table":
            rows = b[1]
            head = "".join(f"<th>{html_inline(c)}</th>" for c in rows[0])
            rest = "".join("<tr>" + "".join(f"<td>{html_inline(c)}</td>" for c in r) + "</tr>" for r in rows[1:])
            body.append(f"<table><thead><tr>{head}</tr></thead><tbody>{rest}</tbody></table>")
    body.append("</section>")

toc_html = "".join(
    f'<li class="toc{lvl}"><a href="#{a}">{html.escape(re.sub(r"[`*]", "", t))}</a></li>' for lvl, t, a in toc)

pdf_html = WORK / "documentation.html"
pdf_html.write_text(f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>{TITLE}</title>
<style>
@page {{ size: A4; margin: 20mm 18mm 22mm 18mm; }}
:root {{ --ink:#{INK}; --body:#2E3833; --green:#{GREEN}; --paper:#F6F4EE; --paper2:#ECE8DD; --line:#D5CFC0; }}
body {{ font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; font-size: 10pt; line-height: 1.5; color: var(--body); margin: 0; }}
h1, h2, h3, h4 {{ color: var(--ink); line-height: 1.25; page-break-after: avoid; break-after: avoid; }}
h1 {{ font-size: 22pt; margin: 0 0 14pt; padding-bottom: 6pt; border-bottom: 2pt solid var(--green); }}
h2 {{ font-size: 14pt; margin: 20pt 0 6pt; }}
h3 {{ font-size: 11.5pt; margin: 14pt 0 4pt; }}
h4 {{ font-size: 10.5pt; margin: 12pt 0 4pt; }}
p {{ margin: 0 0 7pt; }}
a {{ color: var(--green); text-decoration: none; }}
code {{ font-family: Menlo, 'SF Mono', Consolas, monospace; font-size: 8.6pt; background: var(--paper); padding: 0 2pt; border-radius: 2pt; color: var(--ink); }}
pre {{ font-family: Menlo, 'SF Mono', Consolas, monospace; font-size: 8pt; line-height: 1.4; background: var(--paper);
      border-left: 3pt solid var(--green); padding: 8pt 10pt; white-space: pre-wrap; word-break: break-word; break-inside: avoid; }}
table {{ width: 100%; border-collapse: collapse; margin: 6pt 0 12pt; font-size: 8.8pt; break-inside: auto; }}
tr {{ break-inside: avoid; }}
thead {{ display: table-header-group; }}
th {{ background: var(--paper2); color: var(--ink); text-align: left; font-weight: 600; }}
th, td {{ border: 0.6pt solid var(--line); padding: 4pt 6pt; vertical-align: top; }}
td code, th code {{ font-size: 8pt; }}
ul, ol {{ margin: 0 0 8pt; padding-left: 18pt; }}
li {{ margin-bottom: 3pt; }}
.note {{ background: var(--paper); border-left: 3pt solid #9A5800; padding: 7pt 10pt; margin: 6pt 0 10pt; }}
figure.diagram {{ margin: 8pt 0 14pt; text-align: center; break-inside: avoid; }}
figure.diagram svg {{ max-width: 100% !important; height: auto; max-height: 200mm; }}
.chapter {{ break-before: page; }}
.cover {{ height: 250mm; display: flex; flex-direction: column; justify-content: space-between; }}
.cover .band {{ background: var(--ink); color: var(--paper); padding: 40mm 16mm 16mm; }}
.cover .eyebrow {{ font-size: 10pt; letter-spacing: 2pt; text-transform: uppercase; color: #7FD1A8; font-weight: 600; }}
.cover h1 {{ color: var(--paper); border: none; font-size: 34pt; margin: 8pt 0; padding: 0; }}
.cover .sub {{ font-size: 15pt; color: #C9D6CF; }}
.cover .lede {{ font-size: 11.5pt; color: var(--body); padding: 0 16mm; max-width: 140mm; }}
.cover .meta {{ font-size: 9.5pt; color: #5F6670; padding: 0 16mm; }}
.toc {{ break-before: page; }}
.toc ul {{ list-style: none; padding: 0; }}
.toc li.toc1 {{ font-weight: 600; margin-top: 8pt; font-size: 11pt; }}
.toc li.toc2 {{ padding-left: 14pt; font-size: 9.5pt; margin: 1pt 0; }}
.toc a {{ color: var(--ink); }}
</style></head><body>
<div class="cover">
  <div class="band"><div class="eyebrow">{SUBTITLE}</div><h1>{TITLE}</h1>
  <div class="sub">Workflow Engine · Action Ledger · Token Ledger</div></div>
  <p class="lede">A platform that creates, tracks, transfers and retires digital units of value, from loyalty points to
  fractional shares of real-world assets, and can always prove who owns what, and why.</p>
  <div class="meta">Compiled from the repository's <code>docs/</code> folder on {TODAY}.<br>
  The Markdown files in <code>docs/</code> are the source; this document is generated from them.</div>
</div>
<div class="toc"><h1>Contents</h1><ul>{toc_html}</ul></div>
{''.join(body)}
</body></html>""")

pdf = OUT / "ZenToken-Documentation.pdf"
chrome(f"--print-to-pdf={pdf}", "--no-pdf-header-footer", "--virtual-time-budget=5000", pdf_html.as_uri())

# ----------------------------------------------------------------------------------------------
# Word
# ----------------------------------------------------------------------------------------------

doc = Document()
sec = doc.sections[0]
sec.page_width, sec.page_height = Cm(21), Cm(29.7)
sec.left_margin = sec.right_margin = Cm(2)
sec.top_margin, sec.bottom_margin = Cm(2), Cm(2.2)
TEXT_WIDTH_CM = 17.0

styles = doc.styles
normal = styles["Normal"]
normal.font.name = "Calibri"
normal.font.size = Pt(10.5)
normal.font.color.rgb = RGBColor.from_string("2E3833")
normal.paragraph_format.space_after = Pt(6)
normal.paragraph_format.line_spacing = 1.15
normal.element.rPr.rFonts.set(qn("w:eastAsia"), "Calibri")
for name, size, before, after in (("Heading 1", 20, 0, 10), ("Heading 2", 14, 16, 6), ("Heading 3", 12, 12, 4),
                                  ("Heading 4", 11, 10, 4)):
    s = styles[name]
    s.font.name = "Calibri"
    s.element.rPr.rFonts.set(qn("w:asciiTheme"), "") if False else None
    rfonts = s.element.rPr.find(qn("w:rFonts"))
    for attr in ("w:asciiTheme", "w:hAnsiTheme", "w:eastAsiaTheme", "w:cstheme"):
        if rfonts is not None and rfonts.get(qn(attr)) is not None:
            del rfonts.attrib[qn(attr)]
    s.font.size = Pt(size)
    s.font.bold = True
    s.font.italic = False
    s.font.color.rgb = RGBColor.from_string(INK)
    s.paragraph_format.space_before = Pt(before)
    s.paragraph_format.space_after = Pt(after)
    s.paragraph_format.keep_with_next = True


def shade(el, fill):
    pr = el.get_or_add_tcPr() if hasattr(el, "get_or_add_tcPr") else el.get_or_add_pPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), fill)
    pr.append(shd)


def border_left(p, color, size=18):
    pPr = p._p.get_or_add_pPr()
    bdr = OxmlElement("w:pBdr")
    left = OxmlElement("w:left")
    for k, v in (("w:val", "single"), ("w:sz", str(size)), ("w:space", "8"), ("w:color", color)):
        left.set(qn(k), v)
    bdr.append(left)
    pPr.append(bdr)


def border_bottom(p, color, size=12):
    pPr = p._p.get_or_add_pPr()
    bdr = OxmlElement("w:pBdr")
    b = OxmlElement("w:bottom")
    for k, v in (("w:val", "single"), ("w:sz", str(size)), ("w:space", "4"), ("w:color", color)):
        b.set(qn(k), v)
    bdr.append(b)
    pPr.append(bdr)


def add_hyperlink(p, text, url, size=None):
    rid = p.part.relate_to(url, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
                           is_external=True)
    h = OxmlElement("w:hyperlink")
    h.set(qn("r:id"), rid)
    r = OxmlElement("w:r")
    rpr = OxmlElement("w:rPr")
    c = OxmlElement("w:color")
    c.set(qn("w:val"), GREEN)
    u = OxmlElement("w:u")
    u.set(qn("w:val"), "single")
    rpr.append(c)
    rpr.append(u)
    if size:
        sz = OxmlElement("w:sz")
        sz.set(qn("w:val"), str(int(size * 2)))
        rpr.append(sz)
    r.append(rpr)
    t = OxmlElement("w:t")
    t.text = text
    t.set(qn("xml:space"), "preserve")
    r.append(t)
    h.append(r)
    p._p.append(h)


def runs(p, text, size=None):
    for kind, t, href in inline(text):
        if kind == "link":
            add_hyperlink(p, t, href, size)
            continue
        r = p.add_run(t)
        if size:
            r.font.size = Pt(size)
        if kind in ("bold", "boldcode"):
            r.bold = True
        if kind == "italic":
            r.italic = True
        if kind in ("code", "boldcode"):
            r.font.name = "Consolas"
            r._element.rPr.rFonts.set(qn("w:hAnsi"), "Consolas")
            r.font.size = Pt((size or 10.5) - 1)
            r.font.color.rgb = RGBColor.from_string(INK)


def set_cell_margins(table, top=50, bottom=50, left=90, right=90):
    tblPr = table._tbl.tblPr
    mar = OxmlElement("w:tblCellMar")
    for side, v in (("top", top), ("left", left), ("bottom", bottom), ("right", right)):
        e = OxmlElement(f"w:{side}")
        e.set(qn("w:w"), str(v))
        e.set(qn("w:type"), "dxa")
        mar.append(e)
    tblPr.append(mar)


def repeat_header(row):
    trPr = row._tr.get_or_add_trPr()
    e = OxmlElement("w:tblHeader")
    e.set(qn("w:val"), "true")
    trPr.append(e)


def column_widths(rows):
    """Share the text width by each column's longest content, with a floor."""
    n = len(rows[0])
    lens = [max(min(len(re.sub(r"[`*]", "", r[c])) if c < len(r) else 0, 60) for r in rows) for c in range(n)]
    lens = [max(l, 6) for l in lens]
    total = sum(lens)
    return [TEXT_WIDTH_CM * l / total for l in lens]


# Cover
cover = doc.add_paragraph()
cover.paragraph_format.space_before = Pt(150)
r = cover.add_run(SUBTITLE.upper())
r.font.size, r.bold = Pt(11), True
r.font.color.rgb = RGBColor.from_string(GREEN)
t = doc.add_paragraph()
r = t.add_run(TITLE)
r.font.size, r.bold = Pt(32), True
r.font.color.rgb = RGBColor.from_string(INK)
border_bottom(t, GREEN, 18)
s = doc.add_paragraph()
r = s.add_run("Workflow Engine · Action Ledger · Token Ledger")
r.font.size = Pt(15)
r.font.color.rgb = RGBColor.from_string("47514C")
lede = doc.add_paragraph()
lede.paragraph_format.space_before = Pt(24)
r = lede.add_run("A platform that creates, tracks, transfers and retires digital units of value, from loyalty points "
                 "to fractional shares of real-world assets, and can always prove who owns what, and why.")
r.font.size = Pt(12)
meta = doc.add_paragraph()
meta.paragraph_format.space_before = Pt(160)
r = meta.add_run(f"Compiled from the repository's docs/ folder on {TODAY}. "
                 "The Markdown files are the source; this document is generated from them.")
r.font.size = Pt(9.5)
r.font.color.rgb = RGBColor.from_string("5F6670")
doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)

# Table of contents (a field Word fills in when the document opens)
doc.add_paragraph("Contents", style="Heading 1")
p = doc.add_paragraph()
run = p.add_run()
for tag, text in (("begin", None), (None, 'TOC \\o "1-2" \\h \\z \\u'), ("separate", None), ("end", None)):
    if tag:
        fc = OxmlElement("w:fldChar")
        fc.set(qn("w:fldCharType"), tag)
        run._r.append(fc)
        if tag == "separate":
            t = OxmlElement("w:t")
            t.text = "Right-click and choose Update Field to show the table of contents."
            run._r.append(t)
    else:
        it = OxmlElement("w:instrText")
        it.set(qn("xml:space"), "preserve")
        it.text = text
        run._r.append(it)
settings = doc.settings.element
upd = OxmlElement("w:updateFields")
upd.set(qn("w:val"), "true")
settings.append(upd)

numbering_ids = {}
d_index = 0
for ci, blocks in enumerate(chapters):
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)
    for b in blocks:
        kind = b[0]
        if kind == "h":
            hp = doc.add_paragraph(style=f"Heading {min(b[1], 4)}")
            runs(hp, b[2])
            if b[1] == 1:
                border_bottom(hp, GREEN, 12)
        elif kind == "p":
            runs(doc.add_paragraph(), b[1])
        elif kind == "quote":
            q = doc.add_paragraph()
            shade(q._p, "F6F4EE")
            border_left(q, "9A5800")
            runs(q, b[1])
        elif kind == "code":
            for k, line in enumerate(b[1].split("\n")):
                cp = doc.add_paragraph()
                cp.paragraph_format.space_after = Pt(0)
                cp.paragraph_format.line_spacing = 1.0
                cp.paragraph_format.keep_with_next = True
                shade(cp._p, "F6F4EE")
                border_left(cp, GREEN, 12)
                r = cp.add_run(line if line else " ")
                r.font.name = "Consolas"
                r._element.rPr.rFonts.set(qn("w:hAnsi"), "Consolas")
                r.font.size = Pt(8.5)
                r.font.color.rgb = RGBColor.from_string(INK)
            doc.add_paragraph().paragraph_format.space_after = Pt(2)
        elif kind == "mermaid":
            png, w, h = pngs[d_index]
            d_index += 1
            width_cm = min(TEXT_WIDTH_CM, w / 37.8 * 0.75)   # px at 96 dpi → cm, drawn a little smaller
            if h / w * width_cm > 21:
                width_cm = 21 * w / h
            ip = doc.add_paragraph()
            ip.alignment = WD_ALIGN_PARAGRAPH.CENTER
            ip.add_run().add_picture(str(png), width=Cm(width_cm))
        elif kind == "list":
            style = "List Number" if b[1] else "List Bullet"
            if b[1]:
                # Restart numbering for each numbered list.
                num = doc.part.numbering_part.numbering_definitions._numbering
                abstract = num.xpath(f'.//w:num[@w:numId="{styles[style]._element.pPr.numPr.numId.val}"]/w:abstractNumId')[0].val
                new = num.add_num(abstract)
                lo = new.add_lvlOverride(0)
                lo.add_startOverride(1)
            for lvl, text in b[2]:
                lp = doc.add_paragraph(style=style if lvl == 0 else f"{style} 2")
                lp.paragraph_format.space_after = Pt(3)
                if b[1]:
                    lp._p.get_or_add_pPr().get_or_add_numPr().get_or_add_numId().val = new.numId
                runs(lp, text)
        elif kind == "table":
            rows = b[1]
            ncols = len(rows[0])
            table = doc.add_table(rows=0, cols=ncols)
            table.style = "Table Grid"
            table.alignment = WD_TABLE_ALIGNMENT.CENTER
            table.autofit = False
            widths = column_widths(rows)
            set_cell_margins(table)
            for ri, row in enumerate(rows):
                cells = table.add_row().cells
                for c in range(ncols):
                    cell = cells[c]
                    cell.width = Cm(widths[c])
                    para = cell.paragraphs[0]
                    para.paragraph_format.space_after = Pt(0)
                    para.paragraph_format.line_spacing = 1.05
                    runs(para, row[c] if c < len(row) else "", size=9)
                    if ri == 0:
                        shade(cell._tc, "ECE8DD")
                        for r in para.runs:
                            r.bold = True
                            r.font.color.rgb = RGBColor.from_string(INK)
                if ri == 0:
                    repeat_header(table.rows[0])
            grid = table._tbl.tblGrid
            for c, gc in enumerate(grid.findall(qn("w:gridCol"))):
                gc.set(qn("w:w"), str(int(widths[c] * 567)))
            for e in table._tbl.iter(qn("w:tblBorders")):
                for side in e:
                    side.set(qn("w:color"), "D5CFC0")
            doc.add_paragraph().paragraph_format.space_after = Pt(2)

# Footer: title and page number
footer = sec.footer.paragraphs[0]
footer.alignment = WD_ALIGN_PARAGRAPH.RIGHT
fr = footer.add_run(f"{TITLE}  ·  page ")
fr.font.size = Pt(8.5)
fr.font.color.rgb = RGBColor.from_string("5F6670")
pr = footer.add_run()
for tag, text in (("begin", None), (None, "PAGE"), ("end", None)):
    if tag:
        fc = OxmlElement("w:fldChar")
        fc.set(qn("w:fldCharType"), tag)
        pr._r.append(fc)
    else:
        it = OxmlElement("w:instrText")
        it.text = text
        pr._r.append(it)
pr.font.size = Pt(8.5)
sec.different_first_page_header_footer = True

docx_path = OUT / "ZenToken-Documentation.docx"
doc.save(docx_path)
print(f"diagrams={len(svgs)} chapters={len(chapters)}")
print(pdf)
print(docx_path)
