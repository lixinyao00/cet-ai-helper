package com.cethelper.server.explain;

import com.cethelper.server.common.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 语境释义接口，需要有效 Bearer 凭证。 */
@RestController
@RequestMapping("/api/v1")
public class ExplanationController {

    private final ExplanationService explanationService;

    public ExplanationController(ExplanationService explanationService) {
        this.explanationService = explanationService;
    }

    @PostMapping("/explanations")
    public ExplanationResponse explain(@RequestBody(required = false) ExplanationRequest request) {
        if (request == null) {
            throw ApiException.invalidInput("请输入选词及其所在原句。");
        }
        return explanationService.explain(request.selection(), request.sentence());
    }
}
