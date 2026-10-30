package org.pressf5.dashboard.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Service
public class PublicationImportService {
 private final JdbcTemplate jdbc;
 private final ZoneId zone;
 public PublicationImportService(JdbcTemplate jdbc, Environment env) {
  this.jdbc=jdbc; this.zone=ZoneId.of(env.getProperty("app.time-zone","Europe/Moscow"));
 }
 @Transactional
 public Map<String,Object> importWorkbook(MultipartFile upload) {
  if(upload==null||upload.isEmpty()) throw new IllegalArgumentException("Выберите Excel-файл.");
  String filename=Optional.ofNullable(upload.getOriginalFilename()).orElse("publications.xlsx");
  String lower=filename.toLowerCase(Locale.ROOT);
  if(!lower.endsWith(".xlsx")&&!lower.endsWith(".xls")) throw new IllegalArgumentException("Поддерживаются файлы .xlsx и .xls.");
  LocalDate date=LocalDate.now(zone); Path temp=null;
  try {
   temp=Files.createTempFile("publication-statistics-",lower.endsWith(".xls")?".xls":".xlsx");
   upload.transferTo(temp);
   try(InputStream in=Files.newInputStream(temp);Workbook workbook=WorkbookFactory.create(in)) {
    Sheet sheet=workbook.getSheetAt(0);
    Row header=sheet.getRow(0);
    if(header==null||sheet.getLastRowNum()<1||!text(header.getCell(1)).toLowerCase(Locale.ROOT).contains("округ"))
     throw new IllegalArgumentException("Структура файла не соответствует шаблону статистики публикаций.");
    Long batchId=jdbc.queryForObject("INSERT INTO import_batch(snapshot_date,original_filename,status,message) VALUES(?,?, 'PROCESSING','Импорт публикаций') RETURNING id",Long.class,date,filename);
    jdbc.update("INSERT INTO publication_snapshot(snapshot_date,source_import_id) VALUES(?,?) ON CONFLICT(snapshot_date) DO UPDATE SET source_import_id=EXCLUDED.source_import_id,created_at=now()",date,batchId);
    Long snapshotId=jdbc.queryForObject("SELECT id FROM publication_snapshot WHERE snapshot_date=?",Long.class,date);
    jdbc.update("DELETE FROM publication_msu_statistics WHERE snapshot_id=?",snapshotId);
    int count=0;
    for(int i=1;i<=sheet.getLastRowNum();i++) {
     Row row=sheet.getRow(i); if(row==null)continue;
     String name=text(row.getCell(1)); if(name.isBlank()||name.equals("-"))continue;
     jdbc.update("INSERT INTO msu(name) VALUES(?) ON CONFLICT(name) DO NOTHING",name);
     Long msuId=jdbc.queryForObject("SELECT id FROM msu WHERE name=?",Long.class,name);
     String code=text(row.getCell(0));
     jdbc.update("INSERT INTO publication_msu_statistics(snapshot_id,msu_id,source_code,social_publications,website_publications,parent_chat_publications,thematic_videos,publication_reach,organizations_percent) VALUES(?,?,?,?,?,?,?,?,?)",
      snapshotId,msuId,code,number(row.getCell(2)),number(row.getCell(3)),number(row.getCell(4)),number(row.getCell(5)),number(row.getCell(6)),decimal(row.getCell(7)));
     count++;
    }
    if(count==0)throw new IllegalArgumentException("В файле не найдены строки с данными по МСУ.");
    jdbc.update("UPDATE import_batch SET status='SUCCESS',message='Импорт публикаций завершён',schools_read=?,created_or_updated_msu=? WHERE id=?",count,count,batchId);
    return Map.of("batchId",batchId,"snapshotDate",date.toString(),"status","SUCCESS","msuCount",count,"message","Статистика публикаций импортирована.");
   }
  } catch(IllegalArgumentException ex){throw ex;} catch(Exception ex){throw new IllegalArgumentException("Не удалось обработать файл публикаций: "+(ex.getMessage()==null?"ошибка чтения":ex.getMessage()),ex);}
  finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
 }
 private String text(Cell cell){if(cell==null)return "";if(cell.getCellType()==CellType.STRING)return Optional.ofNullable(cell.getStringCellValue()).orElse("").trim();if(cell.getCellType()==CellType.NUMERIC)return String.valueOf((long)cell.getNumericCellValue());return "";}
 private Long number(Cell cell){String s=text(cell).replace(" ","").replace(",",".");if(s.isBlank()||s.equals("-")||s.equals("—"))return null;try{return (long)Double.parseDouble(s);}catch(NumberFormatException e){return null;}}
 private Double decimal(Cell cell){String s=text(cell).replace(" ","").replace(",",".");if(s.isBlank()||s.equals("-")||s.equals("—"))return null;try{return Double.parseDouble(s);}catch(NumberFormatException e){return null;}}
}
