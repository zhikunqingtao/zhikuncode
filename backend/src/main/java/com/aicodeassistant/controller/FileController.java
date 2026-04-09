package com.aicodeassistant.controller;

import com.aicodeassistant.service.FileSearchService;
import com.aicodeassistant.service.FileSearchService.FileSearchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文件搜索 API — 支持 @文件附件功能的后端端点。
 *
 * @see <a href="SPEC §4.3">@文件附件功能</a>
 */
@RestController
public class FileController {

    private final FileSearchService fileSearchService;

    public FileController(FileSearchService fileSearchService) {
        this.fileSearchService = fileSearchService;
    }

    @GetMapping("/api/files/search")
    public ResponseEntity<List<FileSearchResult>> searchFiles(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String workingDir) {
        String dir = workingDir != null ? workingDir : System.getProperty("user.dir");
        List<FileSearchResult> results = fileSearchService.fuzzySearch(q, dir, limit);
        return ResponseEntity.ok(results);
    }
}
