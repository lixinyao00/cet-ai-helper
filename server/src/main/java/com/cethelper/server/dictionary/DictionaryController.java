package com.cethelper.server.dictionary;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 词典联想与查词接口，需要有效 Bearer 凭证。 */
@RestController
@RequestMapping("/api/v1/dictionary")
public class DictionaryController {

    private final DictionaryService dictionaryService;

    public DictionaryController(DictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @GetMapping("/suggest")
    public List<DictionarySuggestion> suggest(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return dictionaryService.suggest(query, level, limit);
    }

    @PostMapping("/lookup")
    public DictionaryEntryResponse lookup(@RequestBody(required = false) DictionaryLookupRequest request) {
        return dictionaryService.lookup(request);
    }
}
