package com.cethelper.server.writing;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WritingReviewController {

    private final WritingReviewService service;

    public WritingReviewController(WritingReviewService service) {
        this.service = service;
    }

    @PostMapping("/writing-reviews")
    public WritingReviewResponse review(@RequestBody(required = false) WritingReviewRequest request) {
        return service.review(request);
    }
}
