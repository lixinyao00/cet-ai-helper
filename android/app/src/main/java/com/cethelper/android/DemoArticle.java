package com.cethelper.android;

final class DemoArticle {
    static final String ID = "decline-demo-v1";
    static final String BODY = "The report attributed the decline to lower demand.\n\n"
            + "Some students decline the invitation.";

    private DemoArticle() {}

    static ReadingArticle open(String title) {
        return new ReadingArticle(ID, title, BODY);
    }
}
