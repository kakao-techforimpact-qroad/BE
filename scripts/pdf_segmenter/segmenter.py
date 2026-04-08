from __future__ import annotations

from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Callable
import json
import re

from parser import PageLayout, TextBlock
from rules import (
    BODY_GAP_Y_MAX,
    TITLE_SCORE_THRESHOLD,
    bbox_center_x,
    detect_ad_blocks,
    extract_continuation_hint,
    extract_reporter_email,
    has_reporter_email,
    is_header_or_footer,
    page_font_stats,
    reading_sort_key,
    title_score,
    caption_candidates_for_image,
)


@dataclass
class ArticleDraft:
    article_id: str
    page_start: int
    page_end: int
    section: str
    title: str
    subtitle: str
    body: str
    reporter: str
    email: str
    continued_from_front: bool
    continued_to_page: int | None
    continuation_anchor: str
    captions: list[str]
    source_pages: list[int]
    _meta: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        out = asdict(self)
        out.pop("_meta", None)
        return out


def _safe_slug(s: str) -> str:
    s = re.sub(r"\s+", "", s)
    s = re.sub(r"[^\w\uAC00-\uD7A3]", "", s)
    return s[:24]


def _infer_columns(blocks: list[TextBlock], page_width: float) -> list[float]:
    """
    x축 간격이 큰 지점을 기준으로 최대 2개의 컬럼 경계를 추정한다.
    """
    xs = sorted(b.bbox[0] for b in blocks)
    if len(xs) < 6:
        return []

    gaps: list[tuple[float, int]] = []
    for i in range(len(xs) - 1):
        gaps.append((xs[i + 1] - xs[i], i))
    gaps.sort(reverse=True, key=lambda x: x[0])

    threshold = page_width * 0.1
    splits: list[float] = []
    for gap, idx in gaps[:6]:
        if gap < threshold:
            break
        split = (xs[idx] + xs[idx + 1]) / 2.0
        if any(abs(split - s) < page_width * 0.05 for s in splits):
            continue
        left = sum(1 for x in xs if x < split)
        right = len(xs) - left
        if left < 4 or right < 4:
            continue
        splits.append(split)
        if len(splits) >= 2:
            break
    return sorted(splits)


def _assign_col(center_x: float, splits: list[float]) -> int:
    for i, s in enumerate(splits):
        if center_x < s:
            return i
    return len(splits)


def _estimate_section(page: PageLayout, blocks: list[TextBlock]) -> str:
    # 섹션명은 보통 헤더에 있으므로 제거 후에는 비어 있을 수 있다.
    # 폴백으로 페이지 상단의 짧은 라벨형 텍스트를 스캔한다.
    top = sorted(page.text_blocks, key=reading_sort_key)[:8]
    for b in top:
        t = b.text.replace("\n", " ").strip()
        if 1 <= len(t) <= 12 and any(ch.isalpha() or ("\uac00" <= ch <= "\ud7a3") for ch in t):
            if not any(c.isdigit() for c in t):
                return t
    return ""


def _build_article_id(page_number: int, idx: int) -> str:
    return f"p{page_number}_a{idx:03d}"


def _split_subtitle(title_block: TextBlock, body_blocks: list[TextBlock]) -> tuple[str, list[TextBlock]]:
    if not body_blocks:
        return "", body_blocks
    first = body_blocks[0]
    gap = first.bbox[1] - title_block.bbox[3]
    looks_like_subtitle = (
        0 <= gap <= 28
        and first.char_count <= 110
        and first.line_count <= 3
        and first.font_size >= max(10.0, title_block.font_size * 0.58)
    )
    if looks_like_subtitle:
        return first.text.replace("\n", " ").strip(), body_blocks[1:]
    return "", body_blocks


def _extract_reporter_from_tail(blocks: list[TextBlock]) -> tuple[str, str, int | None]:
    # 기자명/이메일은 기사 말미에 오는 경우가 많아서 뒤에서부터 탐색한다.
    for i in range(len(blocks) - 1, -1, -1):
        b = blocks[i]
        if has_reporter_email(b.text) and b.char_count <= 90:
            reporter, email = extract_reporter_email(b.text)
            return reporter or "", email or "", i
    return "", "", None


def segment_articles(
    pages: list[PageLayout],
    *,
    debug_dir: str | Path | None = None,
    llm_post_processor: Callable[[ArticleDraft], ArticleDraft] | None = None,
    progress_callback: Callable[[int, int], None] | None = None,
) -> list[ArticleDraft]:
    """
    레이아웃 우선 기사 분리 파이프라인.
    LLM은 선택 사항이며 후처리 훅으로만 사용한다.
    """
    debug_path = Path(debug_dir) if debug_dir else None
    if debug_path:
        debug_path.mkdir(parents=True, exist_ok=True)

    all_articles: list[ArticleDraft] = []

    total_pages = len(pages)
    for page_index, page in enumerate(pages, start=1):
        raw_blocks = list(page.text_blocks)
        filtered = [b for b in raw_blocks if not is_header_or_footer(b, page)]
        ad_ids = detect_ad_blocks(page, filtered)
        candidate_blocks = [b for b in filtered if b.block_id not in ad_ids]

        if debug_path:
            (debug_path / f"p{page.page_number:02d}_raw_blocks.json").write_text(
                json.dumps([b.to_dict() for b in raw_blocks], ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            (debug_path / f"p{page.page_number:02d}_clean_blocks.json").write_text(
                json.dumps([b.to_dict() for b in candidate_blocks], ensure_ascii=False, indent=2),
                encoding="utf-8",
            )

        if not candidate_blocks:
            continue

        mean_size, std_size = page_font_stats(candidate_blocks)
        scored: list[tuple[TextBlock, float]] = []
        for b in candidate_blocks:
            s = title_score(
                b,
                page=page,
                page_mean_size=mean_size,
                page_std_size=std_size,
            )
            scored.append((b, s))

        title_blocks = [b for b, s in scored if s >= TITLE_SCORE_THRESHOLD]
        title_blocks.sort(key=reading_sort_key)
        if not title_blocks:
            continue

        splits = _infer_columns(candidate_blocks, page.width)
        by_col: dict[int, list[TextBlock]] = {}
        for t in title_blocks:
            col = _assign_col(bbox_center_x(t.bbox), splits)
            by_col.setdefault(col, []).append(t)
        for col in by_col:
            by_col[col].sort(key=reading_sort_key)

        section = _estimate_section(page, candidate_blocks)
        page_article_idx = 0
        used_ids: set[str] = set()

        for col, titles_in_col in sorted(by_col.items(), key=lambda x: x[0]):
            col_blocks = [
                b for b in candidate_blocks
                if _assign_col(bbox_center_x(b.bbox), splits) == col
            ]
            col_blocks.sort(key=reading_sort_key)

            for i, t in enumerate(titles_in_col):
                y_start = t.bbox[3]
                y_end = page.height
                if i + 1 < len(titles_in_col):
                    y_end = titles_in_col[i + 1].bbox[1] - 1.0

                body_blocks = [
                    b for b in col_blocks
                    if b.block_id != t.block_id
                    and b.block_id not in used_ids
                    and b.bbox[1] >= y_start
                    and b.bbox[3] <= y_end + BODY_GAP_Y_MAX
                ]
                body_blocks.sort(key=reading_sort_key)

                if not body_blocks and t.char_count < 8:
                    continue

                subtitle, body_blocks_wo_sub = _split_subtitle(t, body_blocks)
                reporter, email, reporter_idx = _extract_reporter_from_tail(body_blocks_wo_sub)

                if reporter_idx is not None:
                    article_text_blocks = body_blocks_wo_sub[: reporter_idx + 1]
                    body_text_blocks = body_blocks_wo_sub[:reporter_idx]
                else:
                    article_text_blocks = body_blocks_wo_sub
                    body_text_blocks = body_blocks_wo_sub

                body_text = "\n".join(b.text for b in body_text_blocks).strip()
                if not body_text:
                    body_text = "\n".join(b.text for b in body_blocks_wo_sub).strip()

                for b in article_text_blocks:
                    used_ids.add(b.block_id)
                used_ids.add(t.block_id)

                merged_text_for_marks = "\n".join([t.text] + [b.text for b in article_text_blocks])
                cont = extract_continuation_hint(merged_text_for_marks)

                captions: list[str] = []
                for img in page.image_blocks:
                    image_caps = caption_candidates_for_image(img, article_text_blocks)
                    captions.extend([c.text.replace("\n", " ").strip() for c in image_caps])
                # 순서를 유지하면서 중복 캡션을 제거한다.
                seen: set[str] = set()
                dedup_caps = []
                for c in captions:
                    if c not in seen:
                        seen.add(c)
                        dedup_caps.append(c)

                page_article_idx += 1
                article = ArticleDraft(
                    article_id=_build_article_id(page.page_number, page_article_idx),
                    page_start=page.page_number,
                    page_end=page.page_number,
                    section=section,
                    title=t.text.replace("\n", " ").strip(),
                    subtitle=subtitle,
                    body=body_text,
                    reporter=reporter,
                    email=email,
                    continued_from_front=bool(cont.from_front_anchor),
                    continued_to_page=cont.target_page,
                    continuation_anchor=cont.from_front_anchor or "",
                    captions=dedup_caps,
                    source_pages=[page.page_number],
                    _meta={
                        "title_block_id": t.block_id,
                        "column": col,
                        "title_key": _safe_slug(t.text),
                    },
                )

                if llm_post_processor is not None:
                    article = llm_post_processor(article)

                all_articles.append(article)

        if debug_path:
            (debug_path / f"p{page.page_number:02d}_articles.json").write_text(
                json.dumps([a.to_dict() for a in all_articles if page.page_number in a.source_pages], ensure_ascii=False, indent=2),
                encoding="utf-8",
            )

        if progress_callback is not None:
            progress_callback(page_index, total_pages)

    return all_articles
