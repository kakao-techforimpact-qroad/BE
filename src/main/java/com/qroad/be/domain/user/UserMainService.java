package com.qroad.be.domain.user;

import com.qroad.be.domain.article.ArticleRepository;
import com.qroad.be.domain.article.ArticleSimpleDTO;
import com.qroad.be.domain.paper.PaperRepository;
import com.qroad.be.domain.qrcode.QrCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserMainService {

    private final QrCodeRepository qrCodeRepository;
    private final PaperRepository paperRepository;
    private final ArticleRepository articleRepository;

    public UserMainDTO getPagerByQrKey(String qrKey) {

        Long paperId = qrCodeRepository.findPaperIdByQrKey(qrKey);
        LocalDate publishedDate = paperRepository.findPublishedDateById(paperId);
        List<ArticleSimpleDTO> articles = articleRepository.findArticlesByPaperId(paperId);

        return new UserMainDTO(articles.size() , publishedDate, articles);
    }

}
