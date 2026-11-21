package com.cethelper.server.translation;

import com.cethelper.server.common.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 汉译英批改接口，需要有效 Bearer 凭证。 */
@RestController
@RequestMapping("/api/v1")
public class TranslationReviewController {

    private final TranslationReviewService translationReviewService;

    public TranslationReviewController(TranslationReviewService translationReviewService) {
        this.translationReviewService = translationReviewService;
    }

    @PostMapping("/translation-reviews")
    public TranslationReviewResponse review(@RequestBody(required = false) TranslationReviewRequest request) {
        if (request == null) {
            throw ApiException.invalidInput("请输入中文原文和你的英文译文。");
        }
        return translationReviewService.review(request.sourceText(), request.studentTranslation());
    }
}
