from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from parser import parse_pdf_blocks
from segmenter import segment_articles
from merge import merge_continued_articles
from export import write_articles_json, articles_to_jsonable


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="규칙 기반 신문 PDF 기사 분리기(레이아웃 우선 MVP)"
    )
    p.add_argument("--pdf", required=True, help="입력 PDF 경로")
    p.add_argument(
        "--output",
        default="",
        help="선택 출력 JSON 경로. --stdout-json 사용 시 생략 가능",
    )
    p.add_argument(
        "--stdout-json",
        action="store_true",
        help="최종 기사 JSON을 표준출력으로만 출력(추가 로그 없음)",
    )
    p.add_argument(
        "--debug-dir",
        default="",
        help="중간 디버그 산출물 저장 디렉터리(선택)",
    )
    p.add_argument(
        "--dump-pages",
        action="store_true",
        help="파싱된 페이지 블록 전체를 디버그 파일로 저장",
    )
    p.add_argument(
        "--progress-stderr",
        action="store_true",
        help="진행률 이벤트를 표준에러(stderr)로 출력",
    )
    return p


def main() -> None:
    args = build_parser().parse_args()
    pdf_path = Path(args.pdf)
    if not pdf_path.exists():
        raise SystemExit(f"PDF 파일을 찾을 수 없습니다: {pdf_path}")

    pages = parse_pdf_blocks(pdf_path)

    debug_dir = args.debug_dir.strip() or None
    if args.dump_pages and debug_dir:
        d = Path(debug_dir)
        d.mkdir(parents=True, exist_ok=True)
        (d / "pages_blocks.json").write_text(
            json.dumps([p.to_dict() for p in pages], ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def on_progress(processed: int, total: int) -> None:
        if args.progress_stderr:
            print(f"PROGRESS {processed} {total}", file=sys.stderr, flush=True)

    draft_articles = segment_articles(
        pages,
        debug_dir=debug_dir,
        progress_callback=on_progress if args.progress_stderr else None,
    )
    merged_articles = merge_continued_articles(draft_articles)
    payload = articles_to_jsonable(merged_articles)

    if args.output.strip():
        write_articles_json(args.output, merged_articles)

    if args.stdout_json:
        # 연동 모드에서는 표준출력에 JSON 본문만 출력한다.
        # ensure_ascii=True 로 고정해 인코딩 차이(cp949/utf-8)에서도 깨짐 없이 전달한다.
        sys.stdout.write(json.dumps(payload, ensure_ascii=True))
        return

    if args.output.strip():
        print(f"출력파일={Path(args.output).resolve()}")
    print(f"페이지수={len(pages)}")
    print(f"초안기사수={len(draft_articles)}")
    print(f"병합기사수={len(merged_articles)}")

    if debug_dir:
        debug_summary = {
            "pages": len(pages),
            "draft_articles": len(draft_articles),
            "merged_articles": len(merged_articles),
            "articles_preview": payload[:5],
        }
        Path(debug_dir, "summary.json").write_text(
            json.dumps(debug_summary, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
