package com.qroad.be.pdf;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class PdfAdFilter {

    private PdfAdFilter() {
    }

    private static final List<String> AD_KEYWORDS = List.of(
            "문의", "할인", "특가", "예약", "판매", "분양", "임대", "모집", "상담",
            "전화", "TEL", "tel", "FAX", "팩스", "가격", "원", "만원", "% 할인",
            "구인", "구직", "채용", "요리사", "산후조리");

    private static final Pattern PHONE_PATTERN = Pattern.compile("\\(?\\d{2,3}\\)?[-.]\\d{3,4}[-.]\\d{4}");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)www\\.|http[s]?://");
    private static final Pattern AD_EXCLUSIVE_KEYWORD = Pattern.compile("매\\s*물|분\\s*양|주\\s*택|월\\s*세|전\\s*세|보\\s*증\\s*금|급\\s*매|모\\s*집|구\\s*인|광\\s*고|광고\\s*번호");
    private static final Pattern EXPLICIT_AD_MARKER = Pattern.compile("(?i)\\[광고]|\\(광고\\)|\\[PR]|\\[홍보]|\\[협찬]|\\(협찬\\)|광고문의|협찬문의");
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\d+\\s*만\\s*원|\\d+[,\\d]*\\s*원\\s*[(/]|\\d+\\.?\\d*\\s*%\\s*할인");
    private static final Pattern PUBLIC_NOTICE_PATTERN = Pattern.compile("채\\s*용\\s*공\\s*고|입\\s*찰\\s*공\\s*고|공\\s*고\\s*문|모\\s*집\\s*공\\s*고|지\\s*원\\s*서|접\\s*수\\s*기\\s*간|추\\s*천\\s*을\\s*받\\s*습\\s*니\\s*다");
    private static final Pattern WEEKDAY_DATE_PATTERN = Pattern.compile("\\b\\d{1,2}\\s*\\(\\s*[월화수목금토일]\\s*\\)");
    private static final Pattern TIME_PATTERN = Pattern.compile("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b");
    private static final Pattern CLASSIFIED_KEYWORD_PATTERN = Pattern.compile("주택\\s*매매|토지\\s*매매|상가|아파트|전세|월세|보증금|급매|팝니다|삽니다|구인|구직|직원\\s*구인|공인중개사");

    /**
     * 본문 텍스트가 광고/홍보성인지 휴리스틱 점수로 판별합니다.
     */
    static boolean isLikelyAd(String body) {
        if (EXPLICIT_AD_MARKER.matcher(body).find()) return true;
        if (isStrongTimetableAd(body)) return true;

        int score = 0;
        if (PHONE_PATTERN.matcher(body).find()) score += 2;
        if (URL_PATTERN.matcher(body).find()) score += 2;
        if (AD_EXCLUSIVE_KEYWORD.matcher(body).find()) score += 3;
        if (PUBLIC_NOTICE_PATTERN.matcher(body).find()) score += 2;

        long priceMatches = PRICE_PATTERN.matcher(body).results().count();
        if (priceMatches >= 2) score += 2;

        long adKwCount = AD_KEYWORDS.stream().filter(body::contains).count();
        if (adKwCount >= 3) score += 1;

        // 점수 임계값은 보수적으로 높여 정상 기사 오탐을 줄인다.
        return score >= 5;
    }

    /**
     * 분류면(장터/매물/구인) 성격 페이지인지 판단합니다.
     */
    static boolean isLikelyClassifiedPage(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        String merged = lines.stream().map(Line::getText).collect(Collectors.joining("\n"));
        int phoneCount = (int) PHONE_PATTERN.matcher(merged).results().count();
        int keywordCount = (int) CLASSIFIED_KEYWORD_PATTERN.matcher(merged).results().count();
        long bulletLines = lines.stream()
                .map(Line::getText)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(t -> t.startsWith("◆") || t.startsWith("■") || t.startsWith("▶"))
                .count();
        long nonEmptyLines = lines.stream()
                .map(Line::getText)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .count();
        long shortStructuredLines = lines.stream()
                .map(Line::getText)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(t -> !t.isEmpty() && t.length() <= 24)
                .filter(t -> PHONE_PATTERN.matcher(t).find()
                        || CLASSIFIED_KEYWORD_PATTERN.matcher(t).find()
                        || t.startsWith("◆")
                        || t.startsWith("■"))
                .count();
        double shortStructuredRatio = nonEmptyLines == 0 ? 0.0 : (double) shortStructuredLines / nonEmptyLines;

        // 페이지 전체 스킵은 오탐 비용이 크므로, "거의 분류면"일 때만 매우 보수적으로 적용한다.
        return phoneCount >= 6
                && keywordCount >= 8
                && bulletLines >= 10
                && shortStructuredRatio >= 0.35;
    }

    /**
     * 영화 시간표처럼 요일/시간 패턴이 과도하게 반복되는 광고를 탐지합니다.
     */
    private static boolean isStrongTimetableAd(String body) {
        int weekdayDateCount = (int) WEEKDAY_DATE_PATTERN.matcher(body).results().count();
        int timeCount = (int) TIME_PATTERN.matcher(body).results().count();
        long structuredLines = Arrays.stream(body.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> line.length() <= 40)
                .filter(line -> WEEKDAY_DATE_PATTERN.matcher(line).find() || TIME_PATTERN.matcher(line).find())
                .count();
        long totalLines = Arrays.stream(body.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .count();
        double ratio = totalLines == 0 ? 0.0 : (double) structuredLines / totalLines;
        return (weekdayDateCount >= 4 && timeCount >= 8) || (timeCount >= 12 && ratio >= 0.6);
    }
}
