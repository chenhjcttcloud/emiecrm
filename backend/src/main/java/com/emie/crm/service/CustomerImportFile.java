package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;

import com.emie.crm.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Reads one customer table without evaluating spreadsheet formulas or changing identifiers. */
public final class CustomerImportFile {
  public static final int MAX_BYTES = 5 * 1024 * 1024;
  public static final int MAX_ROWS = 2000;
  public static final int MAX_COLUMNS = 50;
  public static final int MAX_CELL_LENGTH = 1000;
  private static final int MAX_UNZIPPED_BYTES = 32 * 1024 * 1024;
  private static final int MAX_ZIP_ENTRIES = 512;
  private static final int MAX_PHYSICAL_ROWS = 20001;
  private static final Set<String> IDENTIFIER_HEADERS = Set.of(
      "id", "externalid", "externalcustomerid", "customerid", "customercode", "customer_id",
      "phone", "mobile", "contactphone", "contact_phone", "telephone", "tel", "receiver_mobile",
      "receiver_phone", "channel_co_id", "drp_co_id", "buyer_id", "open_id", "shop_buyer_id",
      "客户编号", "客户编码", "外部编号", "外部客户编号", "外部客户编码", "聚水潭客户编号",
      "聚水潭客户id", "聚水潭编号", "分销商编号", "经销商编号", "采购商编号", "买家编号", "买家账号",
      "买家帐号", "手机", "手机号", "手机号码", "联系手机", "联系人手机", "联系人手机号", "电话", "联系电话", "联系人电话");

  private CustomerImportFile() {}

  public static JsonNode parse(ObjectMapper mapper, String filename, byte[] bytes) {
    require(bytes != null && bytes.length > 0, "请选择非空的客户文件");
    require(bytes.length <= MAX_BYTES, "客户文件不能超过5MB");
    String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    try {
      List<TableRow> table;
      if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
        if (name.endsWith(".xlsx")) inspectZip(bytes);
        table = spreadsheet(bytes);
      } else if (name.endsWith(".csv") || name.endsWith(".tsv")) {
        table = delimited(decode(bytes), name.endsWith(".tsv") ? '\t' : ',');
      } else {
        throw new ApiException("请选择Excel（.xlsx/.xls）、CSV或TSV客户文件");
      }
      require(!table.isEmpty(), "文件没有客户表头");
      var header = table.getFirst();
      require(header.number() == 1, "请在文件第一行填写客户表头");
      List<String> headers = new ArrayList<>();
      var distinct = new HashSet<String>();
      require(!header.values().isEmpty() && header.values().size() <= MAX_COLUMNS, "客户文件最多50列");
      for (String value : header.values()) {
        String label = value.strip();
        if (headers.isEmpty() && label.startsWith("\uFEFF")) label = label.substring(1).strip();
        require(!label.isEmpty(), "表头不能为空，请删除空白列或补齐列名");
        require(distinct.add(label), "表头重复：" + label);
        headers.add(label);
      }
      var result = mapper.createObjectNode();
      ArrayNode headerNode = result.putArray("headers");
      headers.forEach(headerNode::add);
      ArrayNode rows = result.putArray("rows");
      for (int i = 1; i < table.size(); i++) {
        var line = table.get(i);
        if (line.values().stream().allMatch(String::isBlank)) continue;
        require(line.values().size() == headers.size(), "第" + line.number() + "行列数与表头不一致");
        require(rows.size() < MAX_ROWS, "每次最多导入2000个客户，请拆分文件");
        var row = rows.addObject().put("rowNumber", line.number());
        ArrayNode values = row.putArray("values");
        for (int column = 0; column < line.values().size(); column++) {
          String value = line.values().get(column);
          require(value.length() <= MAX_CELL_LENGTH, "第" + line.number() + "行存在超过1000字的单元格");
          require(!identifierHeader(headers.get(column))
                  || !value.strip().matches("[+-]?[0-9]+(?:\\.[0-9]+)?[eE][+-]?[0-9]+"),
              "第" + line.number() + "行编号或手机号使用科学计数法，请将原始数据按文本保存后再导入");
          values.add(value);
        }
      }
      require(!rows.isEmpty(), "文件只有表头，没有可导入的客户");
      return result;
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException("无法读取客户文件，请确认文件未加密、未损坏，并按模板重新保存");
    }
  }

  private static void inspectZip(byte[] bytes) throws Exception {
    require(bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K', "Excel文件格式无效，请重新保存为.xlsx");
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      byte[] buffer = new byte[8192];
      long expanded = 0;
      int entries = 0;
      while (zip.getNextEntry() != null) {
        require(++entries <= MAX_ZIP_ENTRIES, "Excel文件内容过多，请仅保留需要导入的客户表");
        int read;
        while ((read = zip.read(buffer)) != -1) {
          expanded += read;
          require(expanded <= MAX_UNZIPPED_BYTES, "Excel文件解压后过大，请仅保留需要导入的客户表");
        }
        zip.closeEntry();
      }
    }
  }

  private static List<TableRow> spreadsheet(byte[] bytes) throws Exception {
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      require(book.getNumberOfSheets() > 0, "Excel文件没有工作表");
      var sheet = book.getSheetAt(0);
      for (int i = 1; i < book.getNumberOfSheets(); i++) {
        for (Row row : book.getSheetAt(i)) {
          for (Cell cell : row) {
            require(cell.getCellType() == CellType.BLANK || (cell.getCellType() == CellType.STRING
                    && cell.getStringCellValue().isBlank()),
                "Excel包含多个有内容的工作表，请仅保留要导入的客户表");
          }
        }
      }
      require(sheet.getPhysicalNumberOfRows() <= MAX_PHYSICAL_ROWS, "Excel空白行过多，请仅保留客户数据区域");
      Row header = sheet.getRow(0);
      require(header != null, "请在Excel第一行填写客户表头");
      int columns = 0;
      for (Cell cell : header) {
        if (!cellText(cell, false).isBlank()) columns = Math.max(columns, cell.getColumnIndex() + 1);
      }
      require(columns > 0 && columns <= MAX_COLUMNS, "请在第一行填写客户表头，最多50列");
      List<String> names = new ArrayList<>();
      for (int c = 0; c < columns; c++) names.add(cellText(header.getCell(c), false));
      var result = new ArrayList<TableRow>();
      result.add(new TableRow(1, names));
      for (Row row : sheet) {
        if (row.getRowNum() == 0) continue;
        var values = new ArrayList<String>();
        boolean content = false;
        for (Cell cell : row) {
          if (cell.getColumnIndex() >= columns && !cellText(cell, false).isBlank())
            throw new ApiException("第" + (row.getRowNum() + 1) + "行存在没有表头的数据列");
        }
        for (int c = 0; c < columns; c++) {
          String value = cellText(row.getCell(c), identifierHeader(names.get(c)));
          content |= !value.isBlank();
          values.add(value);
        }
        if (!content) continue;
        require(result.size() <= MAX_ROWS, "每次最多导入2000个客户，请拆分文件");
        result.add(new TableRow(row.getRowNum() + 1, values));
      }
      return result;
    }
  }

  private static boolean identifierHeader(String value) {
    String normalized = value.replace("\uFEFF", "").strip().toLowerCase(Locale.ROOT).replace(" ", "");
    return IDENTIFIER_HEADERS.contains(normalized)
        || normalized.endsWith("客户编号") || normalized.endsWith("客户编码");
  }

  private static String cellText(Cell cell, boolean identifier) {
    if (cell == null || cell.getCellType() == CellType.BLANK) return "";
    String position = "第" + (cell.getRowIndex() + 1) + "行第" + (cell.getColumnIndex() + 1) + "列";
    require(cell.getCellType() != CellType.FORMULA, position + "含公式，请粘贴为文本后再导入");
    String value;
    if (cell.getCellType() == CellType.STRING) value = cell.getStringCellValue();
    else if (cell.getCellType() == CellType.NUMERIC) {
      double number = cell.getNumericCellValue();
      String format = cell.getCellStyle().getDataFormatString();
      require(!identifier && Double.isFinite(number) && Math.abs(number) < 1e15
              && (format == null || !format.toUpperCase(Locale.ROOT).contains("E+")),
          position + "为数字编号或手机号，可能丢失前导零或精度，请将原始数据按文本保存后再导入");
      value = BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    } else {
      require(cell.getCellType() != CellType.ERROR, position + "存在Excel错误，请修正后导入");
      value = cell.getBooleanCellValue() ? "TRUE" : "FALSE";
    }
    require(value.length() <= MAX_CELL_LENGTH, position + "超过1000字");
    return value;
  }

  private static String decode(byte[] bytes) throws CharacterCodingException {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      return Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
  }

  private static List<TableRow> delimited(String text, char separator) {
    var table = new ArrayList<TableRow>();
    var values = new ArrayList<String>();
    var field = new StringBuilder();
    boolean quoted = false, afterQuote = false, fieldStarted = false;
    int physicalLine = 1, rowLine = 1;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (i == 0 && c == '\uFEFF') continue;
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
          else { quoted = false; afterQuote = true; }
        } else {
          if (c == '\r' || c == '\n') {
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            field.append('\n'); physicalLine++;
          } else field.append(c);
        }
      } else if (c == separator || c == '\r' || c == '\n') {
        values.add(field.toString()); field.setLength(0); fieldStarted = false; afterQuote = false;
        require(values.size() <= MAX_COLUMNS, "客户文件最多50列");
        if (c != separator) {
          addLine(table, rowLine, values); values = new ArrayList<>();
          if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
          physicalLine++; rowLine = physicalLine;
        }
      } else if (c == '"') {
        require(!fieldStarted && !afterQuote, "第" + physicalLine + "行引号位置无效");
        quoted = true; fieldStarted = true;
      } else {
        require(!afterQuote, "第" + physicalLine + "行引号结束后存在多余字符");
        field.append(c); fieldStarted = true;
      }
      require(field.length() <= MAX_CELL_LENGTH, "第" + physicalLine + "行存在超过1000字的单元格");
    }
    require(!quoted, "客户文件存在未闭合引号，请检查CSV格式");
    if (!values.isEmpty() || fieldStarted || !field.isEmpty()) {
      values.add(field.toString());
      addLine(table, rowLine, values);
    }
    return table;
  }

  private static void addLine(List<TableRow> table, int number, List<String> values) {
    if (values.stream().allMatch(String::isBlank)) return;
    require(values.size() <= MAX_COLUMNS, "客户文件最多50列");
    require(table.size() <= MAX_ROWS, "每次最多导入2000个客户，请拆分文件");
    table.add(new TableRow(number, values));
  }

  private record TableRow(int number, List<String> values) {}
}
