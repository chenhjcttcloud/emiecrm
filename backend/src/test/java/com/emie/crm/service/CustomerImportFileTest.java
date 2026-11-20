package com.emie.crm.service;

import static org.junit.jupiter.api.Assertions.*;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class CustomerImportFileTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode csv(String text) {
    return CustomerImportFile.parse(mapper, "客户.CSV", text.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] bytes(Workbook book) throws Exception {
    var output = new ByteArrayOutputStream();
    book.write(output);
    return output.toByteArray();
  }

  @Test void readsBomQuotedCommasMultilineAndEscapedQuotesWithoutLosingIdentifiers() {
    var result = csv("\uFEFF客户名称,外部客户编号,联系人手机,备注\r\n"
        + "\"甲,乙公司\",000012345678901234567,013812345678,\"第一行\r\n第二行\"\"引号\"\"\"\r\n"
        + "\r\n丙公司,C-002,13800000000,完成\r\n");
    assertEquals("客户名称", result.path("headers").get(0).asText());
    assertEquals(2, result.path("rows").size());
    var first = result.path("rows").get(0);
    assertEquals(2, first.path("rowNumber").asInt());
    assertEquals("甲,乙公司", first.path("values").get(0).asText());
    assertEquals("000012345678901234567", first.path("values").get(1).asText());
    assertEquals("013812345678", first.path("values").get(2).asText());
    assertEquals("第一行\n第二行\"引号\"", first.path("values").get(3).asText());
    assertEquals(5, result.path("rows").get(1).path("rowNumber").asInt());
  }

  @Test void readsTsvAndLegacyChineseEncoding() {
    String input = "客户名称\t外部客户编号\n测试公司\t00001\n";
    var result = CustomerImportFile.parse(mapper, "客户.tsv", input.getBytes(Charset.forName("GB18030")));
    assertEquals("测试公司", result.path("rows").get(0).path("values").get(0).asText());
    assertEquals("00001", result.path("rows").get(0).path("values").get(1).asText());
  }

  @Test void rejectsMalformedCsvAndHeaderProblems() {
    for (String text : new String[] {
        "客户名称,外部客户编号\n甲\n", "客户名称,外部客户编号\n甲,001,多余\n",
        "客户名称,外部客户编号\n\"甲,001\n", "客户名称,外部客户编号\n\"甲\"余,001\n",
        "客户名称,外部客户编号\n甲\"公司,001\n", "客户名称, 客户名称 \n甲,乙\n",
        "客户名称,\n甲,001\n", "\n客户名称,外部客户编号\n甲,001\n", "客户名称\n\n"}) {
      assertThrows(ApiException.class, () -> csv(text), text);
    }
  }

  @Test void rejectsScientificIdentifierStringsAndOversizeFilesOrCells() {
    assertTrue(assertThrows(ApiException.class,
        () -> csv("客户名称,外部客户编号\n甲,1.23456789012345E+18\n")).getMessage().contains("科学计数法"));
    assertThrows(ApiException.class, () -> csv("客户名称\n" + "甲".repeat(1001)));
    assertThrows(ApiException.class, () -> CustomerImportFile.parse(mapper, "客户.csv", new byte[CustomerImportFile.MAX_BYTES + 1]));
    assertThrows(ApiException.class, () -> CustomerImportFile.parse(mapper, "客户.txt", new byte[] {1}));
    assertThrows(ApiException.class, () -> CustomerImportFile.parse(mapper, "客户.csv", new byte[0]));
    assertThrows(ApiException.class, () -> csv("客户名称\n" + "甲\n".repeat(2001)));
    assertThrows(ApiException.class, () -> csv(String.join(",", java.util.Collections.nCopies(51, "列")) + "\n甲\n"));
  }

  @Test void readsXlsxTextIdentifiersAndKeepsPhysicalRowNumbers() throws Exception {
    try (var book = new XSSFWorkbook()) {
      var sheet = book.createSheet("客户");
      var head = sheet.createRow(0);
      head.createCell(0).setCellValue("客户名称");
      head.createCell(1).setCellValue("聚水潭客户编号");
      head.createCell(2).setCellValue("联系人手机");
      var row = sheet.createRow(4);
      row.createCell(0).setCellValue("甲公司");
      row.createCell(1).setCellValue("000012345678901234567");
      row.createCell(2).setCellValue("013800000000");
      var parsed = CustomerImportFile.parse(mapper, "客户.xlsx", bytes(book));
      assertEquals(5, parsed.path("rows").get(0).path("rowNumber").asInt());
      assertEquals("000012345678901234567", parsed.path("rows").get(0).path("values").get(1).asText());
      assertEquals("013800000000", parsed.path("rows").get(0).path("values").get(2).asText());
    }
  }

  @Test void rejectsNumericPhonesIdentifiersAndFormulaCells() throws Exception {
    for (String header : new String[] {"外部客户编号", "客户编号", "联系人手机", "channel_co_id"}) {
      try (var book = new XSSFWorkbook()) {
        var sheet = book.createSheet("客户");
        sheet.createRow(0).createCell(0).setCellValue(header);
        var row = sheet.createRow(1);
        row.createCell(0).setCellValue(13812345678d);
        assertTrue(assertThrows(ApiException.class,
            () -> CustomerImportFile.parse(mapper, "客户.xlsx", bytes(book))).getMessage().contains("按文本保存"));
      }
    }
    try (var book = new XSSFWorkbook()) {
      var sheet = book.createSheet("客户");
      sheet.createRow(0).createCell(0).setCellValue("客户名称");
      sheet.createRow(1).createCell(0).setCellFormula("\"客户甲\"");
      assertTrue(assertThrows(ApiException.class,
          () -> CustomerImportFile.parse(mapper, "客户.xlsx", bytes(book))).getMessage().contains("含公式"));
    }
  }

  @Test void rejectsHighPrecisionNumbersEvenUnderUnknownHeaders() throws Exception {
    try (var book = new XSSFWorkbook()) {
      var sheet = book.createSheet("客户");
      sheet.createRow(0).createCell(0).setCellValue("自定义编码");
      sheet.createRow(1).createCell(0).setCellValue(1234567890123456d);
      assertThrows(ApiException.class, () -> CustomerImportFile.parse(mapper, "客户.xlsx", bytes(book)));
    }
  }

  @Test void supportsOldExcelFilesButRejectsHiddenExtraTablesAndMissingHeaders() throws Exception {
    try (var book = new HSSFWorkbook()) {
      var sheet = book.createSheet("客户");
      sheet.createRow(0).createCell(0).setCellValue("客户名称");
      sheet.createRow(1).createCell(0).setCellValue("客户甲");
      assertEquals("客户甲", CustomerImportFile.parse(mapper, "客户.xls", bytes(book))
          .path("rows").get(0).path("values").get(0).asText());
      book.createSheet("第二张表").createRow(0).createCell(0).setCellValue("另一个客户表");
      assertThrows(ApiException.class, () -> CustomerImportFile.parse(mapper, "客户.xls", bytes(book)));
    }
    try (var book = new XSSFWorkbook()) {
      var sheet = book.createSheet("客户");
      sheet.createRow(0).createCell(0).setCellValue("客户名称");
      sheet.createRow(1).createCell(0).setCellValue("客户甲");
      sheet.getRow(1).createCell(1).setCellValue("没有表头");
      assertThrows(ApiException.class, () -> CustomerImportFile.parse(mapper, "客户.xlsx", bytes(book)));
    }
  }

  @Test void rejectsInvalidExcelAndExcessiveZipExpansion() throws Exception {
    assertThrows(ApiException.class, () -> CustomerImportFile.parse(mapper, "客户.xlsx", "not excel".getBytes(StandardCharsets.UTF_8)));
    var output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("large.xml"));
      byte[] block = new byte[1024 * 1024];
      for (int i = 0; i < 33; i++) zip.write(block);
      zip.closeEntry();
    }
    assertTrue(assertThrows(ApiException.class, () -> CustomerImportFile.parse(mapper, "客户.xlsx", output.toByteArray()))
        .getMessage().contains("解压后过大"));
  }
}
