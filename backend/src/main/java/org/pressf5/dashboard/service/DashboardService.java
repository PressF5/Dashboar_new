package org.pressf5.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

@Service
public class DashboardService {
 private final JdbcTemplate jdbc;
 private final ObjectMapper mapper;
 public DashboardService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
 public List<Map<String,Object>> getDates() { return jdbc.queryForList("SELECT snapshot_date::text AS date FROM statistics_snapshot ORDER BY snapshot_date DESC"); }
 public List<Map<String,Object>> getMsu() { return jdbc.queryForList("SELECT id, name FROM msu ORDER BY name"); }
 public List<Map<String,Object>> getOrganizations(Long msuId) {
  if (msuId == null) return jdbc.queryForList("SELECT id, msu_id AS \"msuId\", name FROM educational_organization ORDER BY name");
  return jdbc.queryForList("SELECT id, msu_id AS \"msuId\", name FROM educational_organization WHERE msu_id = ? ORDER BY name", msuId);
 }
 public Map<String,Object> getStatistics(LocalDate requestedDate, Long msuId, Long organizationId) {
  LocalDate date = requestedDate;
  if (date == null) {
   List<LocalDate> dates = jdbc.query("SELECT snapshot_date FROM statistics_snapshot ORDER BY snapshot_date DESC LIMIT 1", (rs, n) -> rs.getObject(1, LocalDate.class));
   if (dates.isEmpty()) return Map.of("date", "", "scope", "empty", "metrics", List.of(), "message", "Нет загруженной статистики");
   date = dates.get(0);
  }
  List<Long> ids = jdbc.query("SELECT id FROM statistics_snapshot WHERE snapshot_date = ?", (rs, n) -> rs.getLong(1), date);
  if (ids.isEmpty()) return Map.of("date", date.toString(), "scope", "empty", "metrics", List.of(), "message", "Нет данных за выбранную дату");
  long snapshotId = ids.get(0); String title; String json; String scope;
  if (organizationId != null) {
   List<Map<String,Object>> rows = jdbc.queryForList("SELECT eo.name, ss.metrics::text AS metrics FROM school_statistics ss JOIN educational_organization eo ON eo.id=ss.organization_id WHERE ss.snapshot_id=? AND eo.id=?", snapshotId, organizationId);
   if (rows.isEmpty()) return Map.of("date", date.toString(), "scope", "organization", "metrics", List.of(), "message", "Нет данных для выбранной ОО за эту дату");
   title = String.valueOf(rows.get(0).get("name")); json = String.valueOf(rows.get(0).get("metrics")); scope = "organization";
  } else if (msuId != null) {
   List<Map<String,Object>> rows = jdbc.queryForList("SELECT m.name, ms.metrics::text AS metrics FROM msu_statistics ms JOIN msu m ON m.id=ms.msu_id WHERE ms.snapshot_id=? AND m.id=?", snapshotId, msuId);
   if (rows.isEmpty()) return Map.of("date", date.toString(), "scope", "msu", "metrics", List.of(), "message", "Нет данных для выбранного МСУ за эту дату");
   title = String.valueOf(rows.get(0).get("name")); json = String.valueOf(rows.get(0).get("metrics")); scope = "msu";
  } else return Map.of("date", date.toString(), "scope", "region", "metrics", List.of(), "message", "Выберите МСУ или ОО для просмотра статистики");
  try {
   Map<String,Object> values = mapper.readValue(json, new TypeReference<>() {});
   List<Map<String,Object>> defs = jdbc.queryForList("SELECT metric_key AS key, label, metric_order AS \"order\", category_key AS \"categoryKey\", category_label AS \"categoryLabel\", unit, is_percentage AS percentage FROM dashboard_metric ORDER BY metric_order");
   List<Map<String,Object>> metrics = new ArrayList<>();
   for (Map<String,Object> def : defs) { Map<String,Object> metric = new LinkedHashMap<>(def); Object value = values.get(String.valueOf(def.get("key"))); metric.put("value", value); metric.put("hasData", value != null); metrics.add(metric); }
   return Map.of("date", date.toString(), "scope", scope, "title", title, "metrics", metrics);
  } catch (Exception ex) { throw new IllegalStateException("Не удалось прочитать показатели", ex); }
 }
}
