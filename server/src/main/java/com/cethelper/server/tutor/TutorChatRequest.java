package com.cethelper.server.tutor;

import java.util.List;

/** AI 助教流式请求：历史消息 + 可选上下文。 */
public record TutorChatRequest(List<TutorChatMessage> messages, TutorContextPayload context) {
}
