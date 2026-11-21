package com.cethelper.server.writing;

import com.cethelper.server.common.ApiException;
import com.cethelper.server.writing.WritingPractice.Context;
import com.cethelper.server.writing.WritingPractice.ExerciseAttempt;
import com.cethelper.server.writing.WritingPractice.ExerciseFeedback;
import com.cethelper.server.writing.WritingPractice.ExerciseVerdict;
import com.cethelper.server.writing.WritingPractice.OriginalIssue;
import com.cethelper.server.writing.WritingPractice.Retest;
import com.cethelper.server.writing.WritingPractice.RetestAttempt;
import com.cethelper.server.writing.WritingPractice.RetestFeedback;
import com.cethelper.server.writing.WritingPractice.RetestVerdict;
import com.cethelper.server.writing.WritingPractice.Task;
import java.util.Map;
import org.springframework.http.HttpStatus;

final class DemoWritingPractice {

    private static final Context DEMO_CONTEXT = demoContext();

    private static final Task RETEST_TASK = new Task(
            "想象你在学校电台。用复数主语和一般现在时写一句英文，说明几位学生主播每周会做的一件事。",
            "Imagine a school radio station. Describe one thing the student hosts do every week.");

    private static final Map<String, ExerciseResult> EXERCISE_RESULTS = Map.of(
            "The volunteers at the animal shelter feed the dogs every morning.",
            new ExerciseResult(ExerciseVerdict.CORRECT, "你写出了完整句子。中心词 volunteers 是复数，用 feed 与它一致。",
                    "到新情境里自己选一个复数主语，写出它经常做的事。"),
            "Every morning, the volunteers at the animal shelter feed the dogs.",
            new ExerciseResult(ExerciseVerdict.CORRECT, "Every morning 放在句首符合题意，复数主语 volunteers 也正确搭配了 feed。",
                    "到新情境里自己选一个复数主语，写出它经常做的事。"),
            "The volunteers at the animal shelter feeds the dogs every morning.",
            new ExerciseResult(ExerciseVerdict.NEEDS_REVISION,
                    "句子完整，every morning 也交代了习惯。主语中心词是复数 volunteers，动词 feeds 与它不一致。",
                    "把 feeds 改成 feed，再读一遍完整句子。"),
            "feed",
            new ExerciseResult(ExerciseVerdict.NEEDS_REVISION, "feed 选对了，复数主语 volunteers 要用动词原形。题目还要求写出完整句子。",
                    "把 feed 放回题面，抄写改好的整句话。"));

    private static final Map<String, RetestResult> RETEST_RESULTS = Map.of(
            "The student hosts check the microphones every Friday.",
            new RetestResult(RetestVerdict.TARGET_USED, "The student hosts check",
                    "你用复数主语 The student hosts 写清了主播的每周工作，check 与主语一致。",
                    "回到作文，用同样的方法检查 Many students 后面的动词。"),
            "Every Friday, the student hosts test their microphones.",
            new RetestResult(RetestVerdict.TARGET_USED, "the student hosts test",
                    "你换了表达方式。复数主语 the student hosts 搭配 test，说明了主播每周做的事。",
                    "回到作文，用同样的方法检查 Many students 后面的动词。"),
            "The student hosts checks the microphones every Friday.",
            new RetestResult(RetestVerdict.SAME_ERROR, "The student hosts checks",
                    "你用了复数主语 The student hosts，写出了每周的活动。checks 是第三人称单数形式，和复数主语不一致。",
                    "将 checks 改成 check，然后另写一句复数主语的一般现在时句子。"),
            "The station opens at noon every Friday.",
            new RetestResult(RetestVerdict.INSUFFICIENT_EVIDENCE, null,
                    "The station 和 opens 搭配正确。这里的主语是单数，本题要观察复数主语的动词形式。",
                    "用复数的学生主播作主语，写一句他们每周做的事。"));

    private DemoWritingPractice() {
    }

    static ExerciseFeedback gradeExercise(ExerciseAttempt attempt) {
        requireSupportedContext(attempt.context());
        ExerciseResult result = EXERCISE_RESULTS.get(attempt.answer());
        if (result == null) {
            throw unsupported("演示模式仅支持 README 中列出的动物收容所练习答案。自选答案请切换 live 模式。");
        }
        return new ExerciseFeedback(attempt.context().issue(), attempt.answer(), result.verdict(),
                result.feedback(), result.nextStep(), "demo");
    }

    static Retest createRetest(Context context) {
        requireSupportedContext(context);
        return new Retest(context.issue(), RETEST_TASK, "demo");
    }

    static RetestFeedback gradeRetest(RetestAttempt attempt) {
        requireSupportedContext(attempt.context());
        RetestResult result = RETEST_RESULTS.get(attempt.answer());
        if (!RETEST_TASK.equals(attempt.task()) || result == null) {
            throw unsupported("演示模式仅支持 README 中列出的学校电台复测题和答案。自选题目或答案请切换 live 模式。");
        }
        return new RetestFeedback(attempt.context().issue(), attempt.answer(), result.verdict(), result.quote(),
                result.feedback(), result.nextStep(), "demo");
    }

    private static void requireSupportedContext(Context context) {
        if (!DEMO_CONTEXT.equals(context)) {
            throw unsupported("演示模式仅支持校园志愿服务草稿的主谓一致问题及其动物收容所练习。自选练习请切换 live 模式。");
        }
    }

    private static Context demoContext() {
        WritingReviewResponse review = DemoWritingCatalog.lookup(new WritingReviewRequest(
                DemoWritingCatalog.PROMPT, DemoWritingCatalog.DRAFT)).orElseThrow();
        WritingReviewResponse.Exercise exercise = review.exercise();
        return new Context(OriginalIssue.from(review.issues().get(exercise.issueIndex())),
                new Task(exercise.instruction(), exercise.item()));
    }

    private static ApiException unsupported(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_UNSUPPORTED", message);
    }

    private record ExerciseResult(ExerciseVerdict verdict, String feedback, String nextStep) {
    }

    private record RetestResult(RetestVerdict verdict, String quote, String feedback, String nextStep) {
    }
}
