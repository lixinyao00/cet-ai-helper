package com.cethelper.server.writing;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.config.CetProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WritingReviewService {

    private final LiveWritingReviewClient liveClient;

    public WritingReviewService(CetProperties properties, ObjectProvider<LiveWritingReviewClient> liveClientProvider) {
        this.liveClient = properties.getExplanation().isLive() ? liveClientProvider.getObject() : null;
    }

    public WritingReviewResponse review(WritingReviewRequest request) {
        if (request == null) {
            throw ApiException.invalidInput("请输入作文题目和你的英文作文。");
        }
        String prompt = requireInput(request.prompt(), 2000, "作文题目");
        String essay = requireInput(request.essay(), 6000, "英文作文");
        WritingReviewRequest normalized = new WritingReviewRequest(prompt, essay);
        if (liveClient != null) {
            return liveClient.review(normalized);
        }
        return DemoWritingCatalog.lookup(normalized)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_UNSUPPORTED",
                        "演示模式仅支持校园志愿服务题的内置草稿和修订稿。自选题目或作文请切换 live 模式批改。"));
    }

    private static String requireInput(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalidInput("请输入" + label + "。");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw ApiException.invalidInput(label + "过长：最多 " + maxLength + " 个字符，请精简后再试。");
        }
        return stripped;
    }
}
