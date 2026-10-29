package org.pressf5.dashboard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class ExcelImportService {
 private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final ZoneId zone;
 public ExcelImportService(JdbcTemplate jdbc, ObjectMapper mapper, Environment env) {
  this.jdbc=jdbc; this.mapper=mapper; this.zone=ZoneId.of(env.getProperty("app.time-zone", "Europe/Moscow"));
 }
 @Transactional
 public Map<String,Object> importWorkbook(MultipartFile upload) {
  if (upload == null || upload.isEmpty()) throw new IllegalArgumentException("Выберите Excel-файл.");
  String filename=Optional.ofNullable(upload.getOriginalFilename()).orElse("report.xlsx");
  String lower=filename.toLowerCase(Locale.ROOT);
  if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) throw new IllegalArgumentException("Поддерживаются файлы .xlsx и .xls.");
  LocalDate date=LocalDate.now(zone); Path temp=null;
  try {
   temp=Files.createTempFile("school-dashboard-", lower.endsWith(".xls") ? ".xls" : ".xlsx"); upload.transferTo(temp);
   try (InputStream in=Files.newInputStream(temp); Workbook workbook=WorkbookFactory.create(in)) {
    Sheet general=workbook.getSheet("Общее");
    if (general == null) throw new IllegalArgumentException("В книге отсутствует лист «Общее».");
    Row headersRow=general.getRow(1);
    if (headersRow == null || headersRow.getLastCellNum() < 2) throw new IllegalArgumentException("Не найдена строка заголовков на листе «Общее».");
    List<Metric> metrics=buildMetrics(headersRow, general);
    upsertDefinitions(metrics);
    Long batchId=jdbc.queryForObject("INSERT INTO import_batch(snapshot_date, original_filename, status, message) VALUES (?, ?, 'PROCESSING', 'Обработка файла') RETURNING id", Long.class, date, filename);
    jdbc.update("INSERT INTO statistics_snapshot(snapshot_date, source_import_id) VALUES (?, ?) ON CONFLICT(snapshot_date) DO UPDATE SET source_import_id=EXCLUDED.source_import_id, created_at=now()", date, batchId);
    Long snapshotId=jdbc.queryForObject("SELECT id FROM statistics_snapshot WHERE snapshot_date=?", Long.class, date);
    jdbc.update("DELETE FROM school_statistics WHERE snapshot_id=?", snapshotId);
    jdbc.update("DELETE FROM msu_statistics WHERE snapshot_id=?", snapshotId);
    int schoolCount=0; Set<String> msuNames=new LinkedHashSet<>(); FormulaEvaluator evaluator=workbook.getCreationHelper().createFormulaEvaluator();
    for (int s=0;s<workbook.getNumberOfSheets();s++) {
     Sheet sheet=workbook.getSheetAt(s); String msuName=sheet.getSheetName().trim();
     if (msuName.equalsIgnoreCase("Общее") || msuName.equalsIgnoreCase("Подсветка") || sheet.getLastRowNum()<2) continue;
     long msuId=ensureMsu(msuName); msuNames.add(msuName);
     Row aggregate=sheet.getRow(2);
     if (aggregate != null) jdbc.update("INSERT INTO msu_statistics(snapshot_id,msu_id,metrics) VALUES (?, ?, CAST(? AS jsonb)) ON CONFLICT(snapshot_id,msu_id) DO UPDATE SET metrics=EXCLUDED.metrics", snapshotId, msuId, json(readMetrics(aggregate,metrics,evaluator)));
     for (int ri=3;ri<=sheet.getLastRowNum();ri++) {
      Row row=sheet.getRow(ri); if (row==null) continue;
      String orgName=cellText(row.getCell(0),evaluator).trim();
      if (orgName.isBlank() || orgName.equalsIgnoreCase("Школа")) continue;
      Map<String,Object> values=readMetrics(row,metrics,evaluator);
      if (values.values().stream().allMatch(Objects::isNull)) continue;
      long orgId=ensureOrganization(msuId,orgName);
      jdbc.update("INSERT INTO school_statistics(snapshot_id,organization_id,metrics) VALUES (?, ?, CAST(? AS jsonb)) ON CONFLICT(snapshot_id,organization_id) DO UPDATE SET metrics=EXCLUDED.metrics", snapshotId,orgId,json(values)); schoolCount++;
     }
    }
    if (msuNames.isEmpty()) throw new IllegalArgumentException("Не найдены листы МСУ в книге.");
    jdbc.update("UPDATE import_batch SET status='SUCCESS', message='Импорт завершён', schools_read=?, created_or_updated_msu=? WHERE id=?",schoolCount,msuNames.size(),batchId);
    return Map.of("batchId",batchId,"snapshotDate",date.toString(),"status","SUCCESS","schoolsRead",schoolCount,"msuCount",msuNames.size(),"message","Импорт завершён; исходный файл будет удалён.");
   }
  } catch (Exception ex) {
   if (ex instanceof IllegalArgumentException iae) throw iae;
   throw new IllegalArgumentException("Не удалось обработать Excel: " + (ex.getMessage()==null ? "ошибка чтения файла" : ex.getMessage()),ex);
  } finally { if (temp!=null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { } }
 }
 private List<Metric> buildMetrics(Row row, Sheet sheet) {
  List<Metric> result=new ArrayList<>();
  Row groups=sheet.getRow(0); String currentGroup="Показатели";
  for (int c=1;c<row.getLastCellNum();c++) {
   String label=cellText(row.getCell(c),null).trim(); if (label.isBlank()) continue;
   String group=groups==null?"":cellText(groups.getCell(c),null).trim();
   if (group.isBlank() && groups!=null) {
    for (org.apache.poi.ss.util.CellRangeAddress range:sheet.getMergedRegions()) {
     if (range.getFirstRow()<=0 && range.getLastRow()>=0 && range.getFirstColumn()<=c && range.getLastColumn()>=c) {
      group=cellText(groups.getCell(range.getFirstColumn()),null).trim(); break;
     }
    }
   }
   if (!group.isBlank()) currentGroup=group;
   String categoryLabel=currentGroup;
   String category="group_"+categoryLabel.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+","_").replaceAll("^_+|_+$","");
   boolean percent=label.contains("%") || label.toLowerCase(Locale.ROOT).contains("процент");
   result.add(new Metric("metric_"+c,label,c,category,categoryLabel,percent?"%":null,percent));
  }
  return result;
 }
 private void upsertDefinitions(List<Metric> metrics) {
  for (Metric m:metrics) jdbc.update("INSERT INTO dashboard_metric(metric_key,label,metric_order,category_key,category_label,unit,is_percentage) VALUES(?,?,?,?,?,?,?) ON CONFLICT(metric_key) DO UPDATE SET label=EXCLUDED.label,metric_order=EXCLUDED.metric_order,category_key=EXCLUDED.category_key,category_label=EXCLUDED.category_label,unit=EXCLUDED.unit,is_percentage=EXCLUDED.is_percentage",m.key,m.label,m.column,m.category,m.categoryLabel,m.unit,m.percentage);
 }
 private Map<String,Object> readMetrics(Row row,List<Metric> metrics,FormulaEvaluator evaluator) {
  Map<String,Object> map=new LinkedHashMap<>();
  for(Metric m:metrics) {
   Cell cell=row.getCell(m.column,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
   Object v=value(cell,evaluator);
   String format=cell==null?"":cell.getCellStyle().getDataFormatString();
   if(m.percentage && v instanceof Number n && format.contains("%") && n.doubleValue()>=0 && n.doubleValue()<=1) v=n.doubleValue()*100.0;
   map.put(m.key,v);
  }
  return map;
 }
 private Object value(Cell cell,FormulaEvaluator evaluator) {
  if(cell==null) return null;
  CellType type=cell.getCellType();
  if(type==CellType.FORMULA) type=cell.getCachedFormulaResultType();
  if(type==CellType.NUMERIC) return cell.getNumericCellValue();
  if(type==CellType.STRING) return clean(cell.getStringCellValue());
  if(type==CellType.BOOLEAN) return cell.getBooleanCellValue();
  return null;
 }
 private String cellText(Cell cell,FormulaEvaluator evaluator) { Object v=value(cell,evaluator); return v==null?"":String.valueOf(v); }
 private String clean(String value) { if(value==null) return null; String s=value.trim(); return s.isEmpty()||s.equals("-")||s.equals("—")?null:s; }
 private long ensureMsu(String name) { jdbc.update("INSERT INTO msu(name) VALUES(?) ON CONFLICT(name) DO NOTHING",name); return jdbc.queryForObject("SELECT id FROM msu WHERE name=?",Long.class,name); }
 private long ensureOrganization(long msuId,String name) { jdbc.update("INSERT INTO educational_organization(msu_id,name) VALUES(?,?) ON CONFLICT(msu_id,name) DO NOTHING",msuId,name); return jdbc.queryForObject("SELECT id FROM educational_organization WHERE msu_id=? AND name=?",Long.class,msuId,name); }
 private String json(Map<String,Object> map) { try{return mapper.writeValueAsString(map);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);} }
 private record Metric(String key,String label,int column,String category,String categoryLabel,String unit,boolean percentage) {}
}
