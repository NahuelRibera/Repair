package dev.repair.api.dataquality;

import dev.repair.api.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DataQualityController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DataQualityRepository repository;

    public DataQualityController(DataQualityRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/data-quality/summary")
    public DataQualitySummaryDto summary() {
        return repository.summary();
    }

    @GetMapping("/api/data-quality/issues")
    public PageResult<DataQualityIssueDto> issues(
            @RequestParam(required = false) String rule,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return repository.searchIssues(rule, severity, page, Math.min(size, MAX_PAGE_SIZE));
    }
}
