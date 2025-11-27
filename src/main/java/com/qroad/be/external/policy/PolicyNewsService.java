package com.qroad.be.external.policy;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.qroad.be.domain.PolicyEntity;
import com.qroad.be.repository.PolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyNewsService {

    private final PolicyRepository policyRepository;

    private static final String SERVICE_KEY =
            "서비스 키";
    private static final String BASE_URL =
            "http://apis.data.go.kr/1371000/policyNewsService/policyNewsList";

    // 공공누리 안내문 제거 정규식
    private static final Pattern COPYRIGHT_PATTERN = Pattern.compile(
            "정책브리핑의 정책뉴스자료는[\\s\\S]*?www\\.korea\\.kr>?",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * XML → DTO 파싱
     */
    private List<PolicyNewsDTO> parseXml(String xml) throws Exception {
        XmlMapper xmlMapper = new XmlMapper();

        ResponseWrapper wrapper = xmlMapper.readValue(xml, ResponseWrapper.class);

        if (wrapper == null || wrapper.getBody() == null || wrapper.getBody().getItems() == null) {
            return Collections.emptyList();
        }

        return wrapper.getBody().getItems();
    }

    /**
     * 🔥 최신 정책뉴스 수집 + DB 저장 + HTML 제거 후 텍스트 저장
     * (최근 3일 자동 계산)
     */
    @Transactional
    public void fetchAndSavePolicies() throws IOException {
        int targetCount = 3000;
        int savedCount = 0;

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(2);

        while (savedCount < targetCount) {

            log.info("요청 구간: {} ~ {}", start, end);

            List<PolicyNewsDTO> items;

            try {
                items = fetchPolicyNews(start, end);
            } catch (Exception e) {
                log.warn("API 실패: {}~{} (건너뜀)", start, end);
                end = start.minusDays(1);
                start = end.minusDays(2);
                continue;
            }

            log.info("API 결과 개수: {}", items.size());

            for (PolicyNewsDTO dto : items) {

                // 정책뉴스만 저장
                if (!"policy".equalsIgnoreCase(dto.getGroupingCode())) {
                    continue;
                }

                // HTML → 텍스트
                if (dto.getDataContents() != null) {
                    String cleanText = Jsoup.parse(dto.getDataContents()).text();

                    // 공공누리 문구 제거
                    cleanText = COPYRIGHT_PATTERN.matcher(cleanText).replaceAll("").trim();

                    dto.setDataContents(cleanText);
                }

                // Subtitle 정리 (HTML 제거)
                if (dto.getSubTitle1() != null) {
                    dto.setSubTitle1(Jsoup.parse(dto.getSubTitle1()).text().trim());
                }

                // 중복 체크
                if (policyRepository.existsBylink(dto.getOriginalUrl())) {
                    continue;
                }

                PolicyEntity entity = PolicyEntity.fromDto(dto);
                policyRepository.save(entity);
                savedCount++;

                if (savedCount >= targetCount) break;
            }

            log.info("누적 저장 개수: {}", savedCount);

            // 날짜 구간 뒤로 이동
            end = start.minusDays(1);
            start = end.minusDays(2);
        }

        log.info("최종 저장 개수: {}", savedCount);
    }


    /**
     * API 호출
     */
    public List<PolicyNewsDTO> fetchPolicyNews(LocalDate start, LocalDate end) throws Exception {

        StringBuilder urlBuilder = new StringBuilder(BASE_URL);
        urlBuilder.append("?serviceKey=").append(SERVICE_KEY);
        urlBuilder.append("&startDate=").append(start.format(DateTimeFormatter.BASIC_ISO_DATE));
        urlBuilder.append("&endDate=").append(end.format(DateTimeFormatter.BASIC_ISO_DATE));

        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8")
        );

        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();

        return parseXml(sb.toString());
    }

    /**
     * Excel 저장
     */
    public void writeExcel(List<PolicyNewsDTO> items) throws IOException {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("PolicyNews");

        // Header
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("newsId");
        header.createCell(1).setCellValue("title");
        header.createCell(2).setCellValue("subTitle");
        header.createCell(3).setCellValue("content");
        header.createCell(4).setCellValue("minister");
        header.createCell(5).setCellValue("url");
        header.createCell(6).setCellValue("approveDate");

        // Body
        int rowNum = 1;
        for (PolicyNewsDTO dto : items) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(dto.getNewsId());
            row.createCell(1).setCellValue(dto.getTitle());
            row.createCell(2).setCellValue(dto.getSubTitle1());
            row.createCell(3).setCellValue(dto.getDataContents());
            row.createCell(4).setCellValue(dto.getMinisterName());
            row.createCell(5).setCellValue(dto.getOriginalUrl());
            row.createCell(6).setCellValue(dto.getApproveDate());
        }

        File dir = new File("export");
        if (!dir.exists()) dir.mkdirs();

        FileOutputStream fos = new FileOutputStream("export/policy_news.xlsx");
        workbook.write(fos);
        fos.close();
        workbook.close();

        log.info("Excel 파일 생성 완료: export/policy_news.xlsx");
    }
}
