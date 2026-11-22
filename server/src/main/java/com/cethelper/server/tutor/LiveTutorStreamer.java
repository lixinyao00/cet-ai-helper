package com.cethelper.server.tutor;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * live 模式的助教流式生产者：把系统提示与最近 20 条历史交给 Spring AI，
 * 返回内容增量的 {@link Flux}。超时与错误映射由 {@link TutorService} 统一处理。
 */
@Component
@ConditionalOnProperty(prefix = "cet.explanation", name = "mode", havingValue = "live")
public class LiveTutorStreamer {

    private static final int MAX_HISTORY = 20;

    private final ChatClient chatClient;

    public LiveTutorStreamer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Flux<String> stream(TutorContextPayload context, List<TutorChatMessage> messages) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(TutorPrompts.system(context)));
        List<TutorChatMessage> history = messages == null ? List.of() : messages;
        int start = Math.max(0, history.size() - MAX_HISTORY);
        for (TutorChatMessage message : history.subList(start, history.size())) {
            if (message == null || message.content() == null) {
                continue;
            }
            if ("assistant".equals(message.role())) {
                promptMessages.add(new AssistantMessage(message.content()));
            } else {
                promptMessages.add(new UserMessage(message.content()));
            }
        }
        return chatClient.prompt(new Prompt(promptMessages)).stream().content();
    }
}
