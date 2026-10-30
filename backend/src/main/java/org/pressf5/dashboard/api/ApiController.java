package org.pressf5.dashboard.api;

import org.pressf5.dashboard.service.DashboardService;
import org.pressf5.dashboard.service.ExcelImportService;
import org.pressf5.dashboard.service.PublicationImportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.env.Environment;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final DashboardService dashboardService;
    private final ExcelImportService excelImportService;
    private final PublicationImportService publicationImportService;
    private final JdbcTemplate jdbc;
    private final ZoneId zoneId;

    public ApiController(DashboardService dashboardService, ExcelImportService excelImportService, PublicationImportService publicationImportService, JdbcTemplate jdbc, Environment environment) {
        this.dashboardService = dashboardService;
        this.excelImportService = excelImportService;
        this.publicationImportService = publicationImportService;
        this.jdbc = jdbc;
        this.zoneId = ZoneId.of(environment.getProperty("app.time-zone", "Europe/Moscow"));
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
                    java.time.LocalDate.now(zoneId), filename, message);
            throw ex;
        }
    }


    @GetMapping("/publications/dates")
    public Object publicationDates() { return jdbc.queryForList("SELECT snapshot_date::text AS date FROM publication_snapshot ORDER BY snapshot_date DESC"); }

    @GetMapping("/publications/statistics")
    public Object publicationStatistics(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                        @RequestParam(required = false) Long msuId) {
        LocalDate selected = date;
        if (selected == null) {
            var dates = jdbc.query("SELECT snapshot_date FROM publication_snapshot ORDER BY snapshot_date DESC LIMIT 1", (rs, n) -> rs.getObject(1, LocalDate.class));
            if (dates.isEmpty()) return Map.of("date", "", "metrics", java.util.List.of(), "message", "Нет загруженной статистики публикаций");
            selected = dates.get(0);
        }
        var rows = jdbc.queryForList("SELECT p.snapshot_date::text AS date,m.id AS \"msuId\",m.name,p.source_code AS \"sourceCode\",p.social_publications AS \"socialPublications\",p.website_publications AS \"websitePublications\",p.parent_chat_publications AS \"parentChatPublications\",p.thematic_videos AS \"thematicVideos\",p.publication_reach AS \"publicationReach\",p.organizations_percent AS \"organizationsPercent\" FROM publication_msu_statistics p JOIN publication_snapshot s ON s.id=p.snapshot_id JOIN msu m ON m.id=p.msu_id WHERE s.snapshot_date=? ORDER BY m.name", selected);
        if (msuId != null) rows = rows.stream().filter(r -> String.valueOf(r.get("msuId")).equals(String.valueOf(msuId))).toList();
        return Map.of("date", selected.toString(), "metrics", rows);
    }

    @PostMapping(value = "/admin/publications/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPublications(@RequestPart("file") MultipartFile file) {
        try { return ResponseEntity.ok(publicationImportService.importWorkbook(file)); }
        catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? "Неизвестная ошибка импорта" : ex.getMessage();
            if (message.length() > 1800) message = message.substring(0, 1800);
            jdbc.update("INSERT INTO import_batch(snapshot_date,original_filename,status,message) VALUES(?,?, 'FAILED',?)",
                    LocalDate.now(zoneId), file == null ? null : file.getOriginalFilename(), "Публикации: " + message);
            throw ex;
        }
    }

    @GetMapping("/admin/imports")
    public Object importHistory() {
        return jdbc.queryForList("SELECT id, uploaded_at, snapshot_date, original_filename, status, message, schools_read, created_or_updated_msu FROM import_batch ORDER BY uploaded_at DESC LIMIT 100");
    }

    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "UP"); }
}
