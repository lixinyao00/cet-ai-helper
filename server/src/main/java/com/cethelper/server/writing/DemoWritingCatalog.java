package com.cethelper.server.writing;

import com.cethelper.server.writing.WritingReviewResponse.Exercise;
import com.cethelper.server.writing.WritingReviewResponse.Issue;
import com.cethelper.server.writing.WritingReviewResponse.IssueType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DemoWritingCatalog {

    static final String PROMPT = "For this part, you are allowed 30 minutes to write an essay on the "
            + "benefits of volunteering for college students. Give an example to support your view. "
            + "You should write at least 120 words but no more than 180 words.";

    static final String DRAFT = """
            Many students prefers to spend their weekends relaxing, but volunteering is another way to use that time well. It gives college students a chance to help others while putting what they learn in class into practice.

            Last semester, our class organize a weekend cleanup near the campus. At first, we all wanted to work on the same street, and no one had brought enough bags. We divided the area into smaller sections and asked a nearby shop for extra bags. By the end of the morning, we had learned how to share tasks and solve a problem together.

            A student does not have to volunteer every weekend to benefit. Even a few hours a month can build confidence and make the local community a better place to live.
            """.strip();

    static final String REVISION = """
            Many students prefer to spend their weekends relaxing, but volunteering is another way to use that time well. It gives college students a chance to help others while putting what they learn in class into practice.

            Last semester, our class organized a weekend cleanup near the campus. At first, we all wanted to work on the same street, and no one had brought enough bags. We divided the area into smaller sections and asked a nearby shop for extra bags. By the end of the morning, we had learned how to share tasks and solve a problem together.

            A student does not have to volunteer every weekend to benefit. Even a few hours a month can build confidence and make the local community a better place to live.
            """.strip();

    private static final Map<WritingReviewRequest, WritingReviewResponse> REVIEWS = Map.of(
            new WritingReviewRequest(PROMPT, DRAFT),
            new WritingReviewResponse(PROMPT, DRAFT, "文章用校园清洁活动说明了志愿服务如何锻炼协作能力，例子切题。建议先检查开头的主谓一致和叙述过去经历时的动词时态。",
                    List.of(
                            new Issue(IssueType.GRAMMAR, "Many students prefers",
                                    "主语 Many students 是复数，prefers 是第三人称单数形式，两者不一致。",
                                    "把 prefers 改成 prefer，写成 Many students prefer。"),
                            new Issue(IssueType.GRAMMAR, "Last semester, our class organize a weekend cleanup",
                                    "Last semester 把活动放在已经结束的过去，用 organize 会让时态和时间背景冲突。",
                                    "把 organize 改成 organized。")),
                    new Exercise(0, "从括号内选出与主语一致的动词形式，并写下完整句子。",
                            "The volunteers at the animal shelter (feeds / feed) the dogs every morning."),
                    "demo"),
            new WritingReviewRequest(PROMPT, REVISION),
            new WritingReviewResponse(PROMPT, REVISION, "文章切题，清洁活动的细节说明了志愿服务为什么能锻炼协作和解决问题的能力。开头、事例和结尾衔接清楚。",
                    List.of(), null, "demo"));

    private DemoWritingCatalog() {
    }

    static Optional<WritingReviewResponse> lookup(WritingReviewRequest request) {
        return Optional.ofNullable(REVIEWS.get(request));
    }
}
