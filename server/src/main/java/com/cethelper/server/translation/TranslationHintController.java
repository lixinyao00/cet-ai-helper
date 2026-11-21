package com.cethelper.server.translation;

import com.cethelper.server.common.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 汉译英提示接口，需要有效 Bearer 凭证；每次只返回学生所选类型的提示。 */
@RestController
@RequestMapping("/api/v1")
public class TranslationHintController {

    private final TranslationHintService translationHintService;

    public TranslationHintController(TranslationHintService translationHintService) {
        this.translationHintService = translationHintService;
    }

    @PostMapping("/translation-hints")
    public TranslationHintResponse hint(@RequestBody(required = false) TranslationHintRequest request) {
        if (request == null) {
            throw ApiException.invalidInput("请输入中文原文和提示类型。");
        }
        HintType hintType = HintType.parse(request.hintType());
        if (hintType == null) {
            throw ApiException.invalidInput("提示类型不正确：hintType 只能是 keyword、phrase 或 structure。");
        }
        return translationHintService.hint(request.sourceText(), hintType);
    }
}
