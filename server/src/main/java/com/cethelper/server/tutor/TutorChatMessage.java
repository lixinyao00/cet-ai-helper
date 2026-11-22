package com.cethelper.server.tutor;

/** 一条对话消息：{@code role} 只允许 user / assistant。 */
public record TutorChatMessage(String role, String content) {
}
