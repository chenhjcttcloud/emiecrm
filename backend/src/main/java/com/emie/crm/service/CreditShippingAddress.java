package com.emie.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates address completeness only; it does not invent or geocode a shipping destination. */
final class CreditShippingAddress {
  static final String MESSAGE = "按账期发货请填写省、市（州/盟）、区县及详细收货地址；省直辖县、香港澳门可分别填写省、市、区县";
  private static final Set<String> MUNICIPALITIES = Set.of("北京市", "天津市", "上海市", "重庆市");
  private static final Set<String> PROVINCES = Set.of("河北省", "山西省", "辽宁省", "吉林省", "黑龙江省", "江苏省", "浙江省",
      "安徽省", "福建省", "江西省", "山东省", "河南省", "湖北省", "湖南省", "广东省", "海南省", "四川省", "贵州省", "云南省",
      "陕西省", "甘肃省", "青海省", "台湾省", "内蒙古自治区", "广西壮族自治区", "西藏自治区", "宁夏回族自治区", "新疆维吾尔自治区");
  private static final Set<String> SPECIAL = Set.of("香港特别行政区", "澳门特别行政区");
  private static final Set<String> DIRECT_COUNTY_GROUPS = Set.of("省直辖县", "省直辖县级行政区划", "自治区直辖县级行政区划", "省直辖行政单位", "自治区直辖行政单位");
  // Exact paths already offered by chinaAdministrativeRegions.json; this is not an administrative-data update.
  private static final Map<String, Set<String>> DIRECTORY_DISTRICT_EXCEPTIONS = Map.of(
      "河北省/保定市", Set.of("保定白沟新城"),
      "内蒙古自治区/锡林郭勒盟", Set.of("乌拉盖管委会"),
      "广东省/东莞市", Set.of("松山湖", "东莞港", "东莞生态园"),
      "广东省/中山市", Set.of("火炬开发区街道办事处"),
      "海南省/儋州市", Set.of("华南热作学院"),
      "青海省/海西蒙古族藏族自治州", Set.of("大柴旦行政委员会"),
      "台湾省/宜兰县", Set.of("钓鱼台"),
      "台湾省/花莲县", Set.of("太鲁阁"));
  private static final String HAN = "[\\p{IsHan}·]{1,30}";
  private static final String COUNTY = HAN + "(?:自治县|自治旗|林区|特区|新区|区|县|旗|市)";
  private static final Pattern CITY = Pattern.compile(HAN + "(?:自治州|地区|盟|市)");
  private static final Pattern TAIWAN_COUNTY = Pattern.compile(HAN + "县");
  private static final Pattern DISTRICT = Pattern.compile(COUNTY + "|" + HAN + "(?:街道|镇|乡)");
  private static final Pattern FULL_PROVINCE = Pattern.compile(HAN + "(?:自治州|地区|盟|市)" + COUNTY + ".{2,}");
  private static final Pattern FULL_MUNICIPALITY = Pattern.compile("(?:市辖区)?" + COUNTY + ".{2,}");

  private CreditShippingAddress() {}

  static boolean validDraft(JsonNode draft) {
    var recipient = JsonNodeFactory.instance.objectNode();
    for (var field : Map.of("address", "recipientAddress", "province", "recipientProvince", "city", "recipientCity", "district", "recipientDistrict").entrySet())
      if (draft.has(field.getValue())) recipient.set(field.getKey(), draft.path(field.getValue()));
    return valid(recipient);
  }

  static boolean valid(JsonNode recipient) {
    String address = text(recipient.path("address"), 200);
    if (address == null) return false;
    boolean structured = recipient.has("province") || recipient.has("city") || recipient.has("district");
    if (structured) {
      String province = text(recipient.path("province"), 50), city = text(recipient.path("city"), 50), district = text(recipient.path("district"), 50);
      if (province == null || city == null || district == null) return false;
      if (!DISTRICT.matcher(district).matches()
          && !DIRECTORY_DISTRICT_EXCEPTIONS.getOrDefault(province + "/" + city, Set.of()).contains(district)) return false;
      if (SPECIAL.contains(province)) return specialCity(province, city);
      if (MUNICIPALITIES.contains(province)) return city.equals(province) || Set.of("市辖区", "县").contains(city);
      return PROVINCES.contains(province) && (CITY.matcher(city).matches() || DIRECT_COUNTY_GROUPS.contains(city)
          || ("台湾省".equals(province) && TAIWAN_COUNTY.matcher(city).matches()));
    }
    // The API permits omitting the three region fields only when the full address contains them.
    String compact = address.replaceAll("\\s+", "").replaceFirst("^(?:中华人民共和国|中国)", "");
    for (String province : MUNICIPALITIES)
      if (compact.startsWith(province)) return FULL_MUNICIPALITY.matcher(compact.substring(province.length())).matches();
    for (String province : PROVINCES)
      if (compact.startsWith(province)) return FULL_PROVINCE.matcher(compact.substring(province.length())).matches();
    // County-administered and Hong Kong/Macao addresses can use explicit region fields instead.
    return false;
  }

  private static String text(JsonNode value, int limit) {
    if (!value.isTextual()) return null;
    String text = value.asText().strip();
    return text.isEmpty() || text.length() > limit || text.chars().anyMatch(Character::isISOControl) ? null : text;
  }

  private static boolean specialCity(String province, String city) {
    return "香港特别行政区".equals(province) ? Set.of("香港", "香港岛", "九龙", "新界").contains(city)
        : Set.of("澳门", "澳门半岛", "离岛", "氹仔", "路环", "路氹城").contains(city);
  }
}
