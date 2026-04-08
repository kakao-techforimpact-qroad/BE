from __future__ import annotations

from dataclasses import replace
import re

from segmenter import ArticleDraft


def _norm_title_key(s: str) -> str:
    s = re.sub(r"\s+", "", s)
    s = re.sub(r"[^\w가-힣]", "", s)
    return s[:28].lower()


def _sim_prefix(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    n = min(len(a), len(b), 16)
    if n == 0:
        return 0.0
    hit = sum(1 for i in range(n) if a[i] == b[i])
    return hit / n


def _match_front_article(
    front_articles: list[ArticleDraft],
    continuation: ArticleDraft,
) -> ArticleDraft | None:
    anchor = _norm_title_key(continuation.continuation_anchor)
    best: tuple[float, ArticleDraft] | None = None

    for front in front_articles:
        if front.continued_to_page is None:
            continue
        if continuation.page_start != front.continued_to_page:
            continue

        score = 0.0
        front_key = _norm_title_key(front.title)
        cont_key = _norm_title_key(continuation.title)

        if anchor:
            score += _sim_prefix(front_key, anchor) * 0.55
        score += _sim_prefix(front_key, cont_key) * 0.35

        if front.reporter and continuation.reporter and front.reporter == continuation.reporter:
            score += 0.2

        if best is None or score > best[0]:
            best = (score, front)

    if best and best[0] >= 0.35:
        return best[1]
    return None


def merge_continued_articles(articles: list[ArticleDraft]) -> list[ArticleDraft]:
    """
    1면 일부 기사와 내지 이어짐 기사를 병합한다.
    """
    if not articles:
        return []

    sorted_articles = sorted(
        articles,
        key=lambda a: (a.page_start, a._meta.get("column", 0), a.article_id),
    )
    front_articles = [a for a in sorted_articles if a.page_start == 1]
    consumed_ids: set[str] = set()
    merged: list[ArticleDraft] = []

    for article in sorted_articles:
        if article.article_id in consumed_ids:
            continue

        if article.page_start == 1:
            merged.append(article)
            continue

        if not article.continued_from_front:
            merged.append(article)
            continue

        match = _match_front_article(front_articles, article)
        if match is None:
            merged.append(article)
            continue

        merged_article = replace(
            match,
            page_end=max(match.page_end, article.page_end),
            subtitle=match.subtitle or article.subtitle,
            body=(match.body.rstrip() + "\n" + article.body.lstrip()).strip(),
            reporter=match.reporter or article.reporter,
            email=match.email or article.email,
            captions=list(dict.fromkeys(match.captions + article.captions)),
            source_pages=sorted(set(match.source_pages + article.source_pages)),
            continued_to_page=None,
        )
        consumed_ids.add(match.article_id)
        consumed_ids.add(article.article_id)
        merged.append(merged_article)

    dedup: dict[str, ArticleDraft] = {}
    for a in merged:
        dedup[a.article_id] = a

    return sorted(
        dedup.values(),
        key=lambda a: (a.page_start, a.page_end, a.article_id),
    )
