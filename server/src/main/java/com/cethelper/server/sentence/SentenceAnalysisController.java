package com.cethelper.server.sentence;

import com.cethelper.server.common.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 整句翻译与长难句拆解接口，需要有效 Bearer 凭证。 */
@RestController
@RequestMapping("/api/v1")
public class SentenceAnalysisController {

    private final SentenceAnalysisService sentenceAnalysisService;

    public SentenceAnalysisController(SentenceAnalysisService sentenceAnalysisService) {
        this.sentenceAnalysisService = sentenceAnalysisService;
    }

    @PostMapping("/sentence-analyses")
    public SentenceAnalysisResponse analyze(@RequestBody(required = false) SentenceAnalysisRequest request) {
        if (request == null) {
            throw ApiException.invalidInput("请输入要分析的英文原句。");
        }
        return sentenceAnalysisService.analyze(request.sentence());
    }
}
