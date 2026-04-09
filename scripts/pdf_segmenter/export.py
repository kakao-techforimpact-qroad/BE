from __future__ import annotations

from pathlib import Path
import json

from segmenter import ArticleDraft


def articles_to_jsonable(articles: list[ArticleDraft]) -> list[dict]:
    out = []
    for a in articles:
        out.append(
            {
                "article_id": a.article_id,
                "page_start": a.page_start,
                "page_end": a.page_end,
                "section": a.section,
                "title": a.title,
                "subtitle": a.subtitle,
                "body": a.body,
                "reporter": a.reporter,
                "email": a.email,
                "continued_from_front": a.continued_from_front,
                "captions": a.captions,
                "source_pages": a.source_pages,
            }
        )
    return out


def write_articles_json(path: str | Path, articles: list[ArticleDraft]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = articles_to_jsonable(articles)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
