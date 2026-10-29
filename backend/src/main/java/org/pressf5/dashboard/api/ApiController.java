package org.pressf5.dashboard.api;

import org.pressf5.dashboard.service.DashboardService;
import org.pressf5.dashboard.service.ExcelImportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final DashboardService dashboardService;
    private final ExcelImportService excelImportService;
    private final JdbcTemplate jdbc;

    public ApiController(DashboardService dashboardService, ExcelImportService excelImportService, JdbcTemplate jdbc) {
        this.dashboardService = dashboardService;
        this.excelImportService = excelImportService;
        this.jdbc = jdbc;
    }

    @GetMapping("/dashboard/dates") public Object dates() { return dashboardService.getDates(); }
    @GetMapping("/dashboard/msu") public Object msu() { return dashboardService.getMsu(); }
    @GetMapping("/dashboard/organizations") public Object organizations(@RequestParam(required = false) Long msuId) { return dashboardService.getOrganizations(msuId); }

    @GetMapping("/dashboard/statistics")
    public Object statistics(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             @RequestParam(required = false) Long msuId,
                             @RequestParam(required = false) Long organizationId) {
        return dashboardService.getStatistics(date, msuId, organizationId);
    }

    @PostMapping(value = "/admin/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(excelImportService.importWorkbook(file));
        } catch (RuntimeException ex) {
            String filename = file == null ? null : file.getOriginalFilename();
            String message = ex.getMessage() == null ? "Неизвестная ошибка импорта" : ex.getMessage();
            if (message.length() > 1800) message = message.substring(0, 1800);
            jdbc.update("INSERT INTO import_batch(snapshot_date, original_filename, status, message) VALUES (?, ?, 'FAILED', ?)",
                    java.time.LocalDate.now(), filename, message);
            throw ex;
        }
    }

    @GetMapping("/admin/imports")
    public Object importHistory() {
        return jdbc.queryForList("SELECT id, uploaded_at, snapshot_date, original_filename, status, message, schools_read, created_or_updated_msu FROM import_batch ORDER BY uploaded_at DESC LIMIT 100");
    }

    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "UP"); }
}
