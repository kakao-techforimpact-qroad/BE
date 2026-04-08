from __future__ import annotations

from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any


@dataclass
class TextBlock:
    page_number: int
    block_id: str
    text: str
    bbox: tuple[float, float, float, float]
    font_size: float
    font_name: str
    is_bold: bool
    line_count: int
    char_count: int

    def to_dict(self) -> dict[str, Any]:
        out = asdict(self)
        out["bbox"] = list(self.bbox)
        return out


@dataclass
class ImageBlock:
    page_number: int
    image_id: str
    bbox: tuple[float, float, float, float]

    def to_dict(self) -> dict[str, Any]:
        out = asdict(self)
        out["bbox"] = list(self.bbox)
        return out


@dataclass
class PageLayout:
    page_number: int
    width: float
    height: float
    text_blocks: list[TextBlock]
    image_blocks: list[ImageBlock]

    def to_dict(self) -> dict[str, Any]:
        return {
            "page_number": self.page_number,
            "width": self.width,
            "height": self.height,
            "text_blocks": [b.to_dict() for b in self.text_blocks],
            "image_blocks": [i.to_dict() for i in self.image_blocks],
        }


def _is_bold_font(font_name: str, span_flags: int | None = None) -> bool:
    lower = (font_name or "").lower()
    if any(token in lower for token in ("bold", "heavy", "black", "demi", "semibold")):
        return True
    # PyMuPDF span 플래그에서 4번째 비트는 보통 굵은 글꼴 여부를 나타낸다.
    if span_flags is not None and (span_flags & 16):
        return True
    return False


def _normalize_text(raw: str) -> str:
    normalized = " ".join((raw or "").split())
    return _repair_mojibake(normalized)


def _quality_score(text: str) -> int:
    hangul = sum(1 for c in text if "\uac00" <= c <= "\ud7a3")
    cjk = sum(1 for c in text if ("\u4e00" <= c <= "\u9fff") or ("\uf900" <= c <= "\ufaff"))
    replacement = text.count("\ufffd")
    latin_noise = sum(1 for c in text if c in "ÃÂÐØÅÆ")
    return (hangul * 3) - (cjk * 2) - (replacement * 5) - latin_noise


def _try_redecode(text: str, src_encoding: str) -> str | None:
    try:
        return text.encode(src_encoding).decode("utf-8")
    except Exception:
        return None


def _repair_mojibake(text: str) -> str:
    """
    UTF-8 문자열이 cp949/euc-kr 등으로 잘못 디코딩된 흔적(모지바케)을 휴리스틱으로 복구한다.
    품질 점수가 충분히 좋아질 때만 적용해 정상 문자열 손상을 방지한다.
    """
    if not text:
        return text

    original_score = _quality_score(text)
    best = text
    best_score = original_score

    for enc in ("cp949", "euc-kr", "latin1"):
        candidate = _try_redecode(text, enc)
        if not candidate:
            continue
        score = _quality_score(candidate)
        if score >= best_score + 3:
            best = candidate
            best_score = score

    return best


def parse_pdf_blocks(pdf_path: str | Path) -> list[PageLayout]:
    """
    PyMuPDF를 사용해 페이지별 텍스트/이미지 블록과 레이아웃 메타데이터를 추출한다.
    """
    try:
        import fitz  # PyMuPDF
    except Exception as exc:  # pragma: no cover
        raise RuntimeError(
            "PyMuPDF가 필요합니다. 다음 명령으로 설치하세요: pip install pymupdf"
        ) from exc

    pdf_path = str(pdf_path)
    doc = fitz.open(pdf_path)
    pages: list[PageLayout] = []

    for page_idx in range(doc.page_count):
        page = doc[page_idx]
        page_number = page_idx + 1
        page_dict = page.get_text("dict")
        text_blocks: list[TextBlock] = []
        image_blocks: list[ImageBlock] = []
        text_seq = 0
        image_seq = 0

        for block in page_dict.get("blocks", []):
            block_type = block.get("type", 0)
            bbox = tuple(float(v) for v in block.get("bbox", (0, 0, 0, 0)))

            if block_type == 1:
                image_seq += 1
                image_blocks.append(
                    ImageBlock(
                        page_number=page_number,
                        image_id=f"p{page_number}_img_{image_seq:03d}",
                        bbox=bbox,  # type: ignore[arg-type]
                    )
                )
                continue

            if block_type != 0:
                continue

            lines = block.get("lines", [])
            line_texts: list[str] = []
            span_sizes: list[float] = []
            span_fonts: list[str] = []
            bold_votes = 0
            span_count = 0

            for line in lines:
                spans = line.get("spans", [])
                chunks: list[str] = []
                for span in spans:
                    text = span.get("text", "")
                    if not text:
                        continue
                    chunks.append(text)
                    size = float(span.get("size", 0.0))
                    span_sizes.append(size)
                    font_name = str(span.get("font", ""))
                    span_fonts.append(font_name)
                    if _is_bold_font(font_name, int(span.get("flags", 0))):
                        bold_votes += 1
                    span_count += 1
                joined = _normalize_text("".join(chunks))
                if joined:
                    line_texts.append(joined)

            merged_text = "\n".join(line_texts).strip()
            if not merged_text:
                continue

            text_seq += 1
            avg_size = sum(span_sizes) / len(span_sizes) if span_sizes else 0.0
            dominant_font = max(set(span_fonts), key=span_fonts.count) if span_fonts else ""
            is_bold = (bold_votes / max(1, span_count)) >= 0.3

            text_blocks.append(
                TextBlock(
                    page_number=page_number,
                    block_id=f"p{page_number}_b{text_seq:03d}",
                    text=merged_text,
                    bbox=bbox,  # type: ignore[arg-type]
                    font_size=avg_size,
                    font_name=dominant_font,
                    is_bold=is_bold,
                    line_count=len(line_texts),
                    char_count=len(merged_text.replace("\n", "")),
                )
            )

        pages.append(
            PageLayout(
                page_number=page_number,
                width=float(page.rect.width),
                height=float(page.rect.height),
                text_blocks=text_blocks,
                image_blocks=image_blocks,
            )
        )

    doc.close()
    return pages
