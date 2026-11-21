package com.cethelper.server.writing;

import com.cethelper.server.writing.WritingPractice.Context;
import com.cethelper.server.writing.WritingPractice.ExerciseAttempt;
import com.cethelper.server.writing.WritingPractice.ExerciseFeedback;
import com.cethelper.server.writing.WritingPractice.Retest;
import com.cethelper.server.writing.WritingPractice.RetestAttempt;
import com.cethelper.server.writing.WritingPractice.RetestFeedback;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WritingPracticeController {

    private final WritingPracticeService service;

    public WritingPracticeController(WritingPracticeService service) {
        this.service = service;
    }

    @PostMapping("/writing-exercise-attempts")
    public ExerciseFeedback gradeExercise(@RequestBody(required = false) ExerciseAttempt attempt) {
        return service.gradeExercise(attempt);
    }

    @PostMapping("/writing-retests")
    public Retest createRetest(@RequestBody(required = false) Context context) {
        return service.createRetest(context);
    }

    @PostMapping("/writing-retest-attempts")
    public RetestFeedback gradeRetest(@RequestBody(required = false) RetestAttempt attempt) {
        return service.gradeRetest(attempt);
    }
}
