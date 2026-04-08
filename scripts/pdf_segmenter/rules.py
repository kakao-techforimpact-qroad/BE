from __future__ import annotations

import re
import os
from dataclasses import dataclass
from typing import Iterable

from parser import TextBlock, PageLayout, ImageBlock


# ----------------------------
# 조정 가능한 상수
# ----------------------------
TOP_HEADER_RATIO = 0.11
BOTTOM_FOOTER_RATIO = 0.08

TITLE_SIZE_ZSCORE_WEIGHT = 2.2
TITLE_BOLD_BONUS = 1.0
TITLE_SHORT_LINE_BONUS = 0.8
TITLE_TOP_AREA_BONUS = 0.6
TITLE_MARGIN_BONUS = 0.6
TITLE_SCORE_THRESHOLD = 2.0

BODY_GAP_Y_MAX = 38.0
CAPTION_MAX_CHARS = 90
CAPTION_MAX_DISTANCE = 45.0

REPORTER_LINE_MAX_CHARS = 64
# 예시 규칙(장터면 시작 페이지)은 하드코딩하지 않는다.
# 0 이하이면 면 기반 광고 강제 분류를 비활성화한다.
CLASSIFIED_PAGE_MIN = int(os.getenv("PDF_SEGMENTER_CLASSIFIED_PAGE_MIN", "0"))

AD_SCORE_THRESHOLD = 4
AD_DENSE_SMALL_BLOCK_COUNT = 14


# ----------------------------
# 정규식 패턴
# ----------------------------
PHONE_RE = re.compile(r"(?:\(?0\d{1,2}\)?[-.\s]?\d{3,4}[-.\s]?\d{4})")
EMAIL_RE = re.compile(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+")
REPORTER_EMAIL_RE = re.compile(
    r"([\uAC00-\uD7A3]{2,4})\s+([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+)"
)

HEADER_DATE_ISSUE_RE = re.compile(r"\d{4}\s*년\s*\d{1,2}\s*월\s*\d{1,2}\s*일|호")
HEADER_PAGE_SECTION_RE = re.compile(
    r"^\d+\s*[\uAC00-\uD7A3A-Za-z]{1,8}$|^[\uAC00-\uD7A3A-Za-z]{1,8}\s*\d+$"
)

CONT_TO_INNER_RE = re.compile(r"[▶>\]]?\s*기사\s*(\d+)\s*면\s*이어짐")
CONT_FROM_FRONT_RE = re.compile(
    r"[▷>]\s*1면\s*[‘'\"“]?([^’'\"”]{2,40})[’'\"”]?\s*이어짐\s*기사"
)

AD_KEYWORDS = {
    "광고", "홍보", "문의", "상담", "분양", "매매", "월세", "전세", "보증금",
    "구인", "구직", "채용", "모집", "장터", "부동산", "입찰", "공고", "가격", "할인",
}


def bbox_height(bbox: tuple[float, float, float, float]) -> float:
    return max(0.0, bbox[3] - bbox[1])


def bbox_width(bbox: tuple[float, float, float, float]) -> float:
    return max(0.0, bbox[2] - bbox[0])


def bbox_center_x(bbox: tuple[float, float, float, float]) -> float:
    return (bbox[0] + bbox[2]) / 2.0


def bbox_center_y(bbox: tuple[float, float, float, float]) -> float:
    return (bbox[1] + bbox[3]) / 2.0


def reading_sort_key(block: TextBlock) -> tuple[float, float]:
    return (block.bbox[1], block.bbox[0])


def is_header_or_footer(block: TextBlock, page: PageLayout) -> bool:
    y0, y1 = block.bbox[1], block.bbox[3]
    top_cut = page.height * TOP_HEADER_RATIO
    bottom_cut = page.height * (1.0 - BOTTOM_FOOTER_RATIO)
    text = block.text.replace("\n", " ").strip()
    compact = re.sub(r"\s+", "", text)

    if y1 <= top_cut:
        if HEADER_DATE_ISSUE_RE.search(text) or HEADER_PAGE_SECTION_RE.search(compact):
            return True
        if len(text) <= 30 and any(ch.isdigit() for ch in text):
            return True

    if y0 >= bottom_cut and len(text) <= 40:
        return True

    return False


def page_font_stats(blocks: Iterable[TextBlock]) -> tuple[float, float]:
    sizes = [b.font_size for b in blocks if b.font_size > 0]
    if not sizes:
        return 0.0, 1.0
    mean = sum(sizes) / len(sizes)
    var = sum((s - mean) ** 2 for s in sizes) / max(1, len(sizes))
    std = var ** 0.5
    if std < 0.1:
        std = 0.1
    return mean, std


def title_score(
    block: TextBlock,
    *,
    page: PageLayout,
    page_mean_size: float,
    page_std_size: float,
) -> float:
    """
    블록이 제목 후보인지 점수화한다.
    의미 기반보다 레이아웃/폰트 신호를 우선해 분리의 결정성을 확보한다.
    """
    score = 0.0
    z = (block.font_size - page_mean_size) / max(0.1, page_std_size)
    score += z * TITLE_SIZE_ZSCORE_WEIGHT

    if block.is_bold:
        score += TITLE_BOLD_BONUS

    if block.line_count <= 2 and block.char_count <= 90:
        score += TITLE_SHORT_LINE_BONUS

    if block.bbox[1] <= page.height * 0.45:
        score += TITLE_TOP_AREA_BONUS

    # 본문 스니펫보다 제목 블록은 세로 여백이 큰 경우가 많다.
    if bbox_height(block.bbox) >= block.font_size * 1.8:
        score += TITLE_MARGIN_BONUS

    # 광고성 문구/너무 짧은 조각은 감점한다.
    if is_probable_ad_block(block):
        score -= 2.4
    if block.char_count < 6:
        score -= 1.0

    return score


def has_reporter_email(text: str) -> bool:
    return bool(REPORTER_EMAIL_RE.search(text))


def extract_reporter_email(text: str) -> tuple[str | None, str | None]:
    m = REPORTER_EMAIL_RE.search(text)
    if not m:
        return None, None
    return m.group(1), m.group(2)


def detect_continued_to_page(text: str) -> int | None:
    m = CONT_TO_INNER_RE.search(text)
    if not m:
        return None
    try:
        return int(m.group(1))
    except ValueError:
        return None


def detect_continued_from_front(text: str) -> str | None:
    m = CONT_FROM_FRONT_RE.search(text)
    if not m:
        return None
    return m.group(1).strip()


def is_classified_page(page_number: int) -> bool:
    if CLASSIFIED_PAGE_MIN <= 0:
        return False
    return page_number >= CLASSIFIED_PAGE_MIN


def is_probable_ad_block(block: TextBlock) -> bool:
    text = block.text.replace("\n", " ")
    lower = text.lower()
    score = 0

    if PHONE_RE.search(text):
        score += 2
    if any(k in text for k in AD_KEYWORDS):
        score += 2
    if "@" in text and not EMAIL_RE.search(text):
        score += 1
    if re.search(r"\d+\s*만원|\d+\s*원|\d+\s*평", text):
        score += 2
    if len(text) < 26 and re.search(r"[!★☎◆■□△▽▶▷]", text):
        score += 1
    if re.search(r"(tel|fax|http|www)", lower):
        score += 1

    return score >= AD_SCORE_THRESHOLD


def detect_ad_blocks(page: PageLayout, blocks: list[TextBlock]) -> set[str]:
    ad_ids: set[str] = set()
    small_dense = [b for b in blocks if b.char_count <= 24 and b.font_size <= 11.5]
    if len(small_dense) >= AD_DENSE_SMALL_BLOCK_COUNT:
        # 짧은 소형 블록이 과도하게 많으면 장터/광고 영역일 가능성이 높다.
        ad_ids.update(b.block_id for b in small_dense)

    for b in blocks:
        if is_probable_ad_block(b):
            ad_ids.add(b.block_id)

    if is_classified_page(page.page_number):
        # 장터면은 기본적으로 광고로 보고, 명확히 기사형인 경우만 남긴다.
        for b in blocks:
            if b.block_id in ad_ids:
                continue
            if b.font_size < 13.5 and not has_reporter_email(b.text):
                ad_ids.add(b.block_id)

    return ad_ids


def caption_candidates_for_image(
    image: ImageBlock,
    text_blocks: list[TextBlock],
) -> list[TextBlock]:
    x0, _, x1, y1 = image.bbox
    candidates: list[TextBlock] = []
    for b in text_blocks:
        bx0, by0, bx1, _ = b.bbox
        below = by0 >= y1 and (by0 - y1) <= CAPTION_MAX_DISTANCE
        overlap_x = min(x1, bx1) - max(x0, bx0)
        overlap_ok = overlap_x > 0
        shortish = b.char_count <= CAPTION_MAX_CHARS and b.line_count <= 3
        if below and overlap_ok and shortish:
            candidates.append(b)
    candidates.sort(key=reading_sort_key)
    return candidates[:2]


@dataclass
class ContinuationHint:
    target_page: int | None
    from_front_anchor: str | None


def extract_continuation_hint(text: str) -> ContinuationHint:
    return ContinuationHint(
        target_page=detect_continued_to_page(text),
        from_front_anchor=detect_continued_from_front(text),
    )
