package com.cethelper.server.article;

import com.cethelper.server.common.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 文章整理接口：接收图片或 OCR 文本，返回清理后的标题与正文，需要有效 Bearer 凭证。 */
@RestController
@RequestMapping("/api/v1")
public class ArticleOrganizeController {

    private final ArticleOrganizeService articleOrganizeService;

    public ArticleOrganizeController(ArticleOrganizeService articleOrganizeService) {
        this.articleOrganizeService = articleOrganizeService;
    }

    @PostMapping("/articles/organize")
    public ArticleOrganizeResponse organize(@RequestBody(required = false) ArticleOrganizeRequest request) {
        if (request == null) {
            throw ApiException.invalidInput("请提供待整理的图片或识别文本。");
        }
        return articleOrganizeService.organize(request);
    }
}
