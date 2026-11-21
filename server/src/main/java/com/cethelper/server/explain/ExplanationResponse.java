package com.cethelper.server.explain;

/**
 * 释义成功响应。
 *
 * @param meaning     短释义（中文）
 * @param explanation 结合原句的用法解释（中文）
 * @param mode        {@code demo} 表示内置演示释义，{@code live} 表示在线模型生成
 */
public record ExplanationResponse(String meaning, String explanation, String mode) {
}
