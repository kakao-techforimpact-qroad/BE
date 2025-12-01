package com.qroad.be.service;

import com.qroad.be.dto.UserMainDTO;
import com.qroad.be.repository.ArticleRepository;
import com.qroad.be.dto.ArticleSimpleDTO;
import com.qroad.be.repository.PaperRepository;
import com.qroad.be.repository.QrCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserMainService {

    private final QrCodeRepository qrCodeRepository;
    private final PaperRepository paperRepository;
    private final ArticleRepository articleRepository;

    public UserMainDTO getPagerById(Long paperId) {

        LocalDate publishedDate = paperRepository.findPublishedDateById(paperId);
        List<ArticleSimpleDTO> articles = articleRepository.findArticlesByPaperId(paperId);

        return new UserMainDTO(articles.size() , publishedDate, articles);
    }




}
