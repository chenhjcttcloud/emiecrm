package com.emie.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreditShippingAddressTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private ObjectNode full(String address) { return mapper.createObjectNode().put("address", address); }
  private ObjectNode structured(String province, String city, String district, String address) {
    return full(address).put("province",province).put("city",city).put("district",district);
  }

  @Test void acceptsCompleteProvincePrefectureCountyAndMunicipalityAddressesWithoutRewritingThem() {
    for(String address:new String[]{"广东省深圳市龙岗区万科星火online10栋", "广东省 深圳市 南山区 科技园路1号",
        "中国浙江省杭州市西湖区文三路1号", "广西壮族自治区南宁市青秀区民族大道1号",
        "云南省大理白族自治州大理市人民路1号", "内蒙古自治区阿拉善盟阿拉善左旗测试路1号",
        "黑龙江省大兴安岭地区漠河市测试路1号", "新疆维吾尔自治区伊犁哈萨克自治州伊宁市测试路1号",
        "北京市朝阳区建国路1号", "上海市闵行区虹桥路1号", "天津市和平区南京路1号", "重庆市渝北区测试路1号"}) {
      var recipient=full(address); var before=recipient.deepCopy();
      assertTrue(CreditShippingAddress.valid(recipient),address); assertEquals(before,recipient);
    }
  }

  @Test void separateRegionsAllowAnUnprefixedDetailedStreetAddress() {
    assertTrue(CreditShippingAddress.valid(structured("广东省","深圳市","龙岗区","万科星火online10栋")));
    assertTrue(CreditShippingAddress.valid(structured("北京市","北京市","朝阳区","建国路1号")));
    assertTrue(CreditShippingAddress.valid(structured("上海市","市辖区","闵行区","虹桥路1号")));
    assertTrue(CreditShippingAddress.valid(structured("海南省","省直辖县级行政区划","琼海市","嘉积镇人民路1号")));
    assertTrue(CreditShippingAddress.valid(structured("香港特别行政区","九龙","油尖旺区","广东道1号")));
    assertTrue(CreditShippingAddress.valid(structured("澳门特别行政区","澳门半岛","花地玛堂区","测试路1号")));
  }

  @Test void existingDirectoryCountyGroupsAndSpecialRegionsAcceptStructuredInputWithoutRewritingIt() {
    for (String[] path : new String[][]{
        {"河南省", "省直辖县", "济源市"}, {"湖北省", "省直辖县", "仙桃市"},
        {"海南省", "省直辖县", "琼海市"}, {"重庆市", "县", "奉节县"},
        {"澳门特别行政区", "离岛", "嘉模堂区"}, {"台湾省", "金门县", "金城镇"},
        {"台湾省", "南投县", "南投市"}, {"台湾省", "连江县", "南竿乡"}}) {
      var recipient = structured(path[0], path[1], path[2], "测试路10号");
      var before = recipient.deepCopy();
      assertTrue(CreditShippingAddress.valid(recipient), String.join("/", path));
      assertEquals(before, recipient);
    }
    // The county-shaped city exception is limited to the Taiwan branch of the existing directory.
    assertFalse(CreditShippingAddress.valid(structured("福建省", "金门县", "金城镇", "测试路10号")));
    assertFalse(CreditShippingAddress.valid(structured("台湾省", "金门", "金城镇", "测试路10号")));
    assertFalse(CreditShippingAddress.valid(structured("香港特别行政区", "离岛", "嘉模堂区", "测试路10号")));
  }

  @Test void nonstandardDirectoryDistrictNamesAreAllowedOnlyAtTheirExactParentPath() {
    for (String[] path : new String[][]{
        {"河北省", "保定市", "保定白沟新城"}, {"内蒙古自治区", "锡林郭勒盟", "乌拉盖管委会"},
        {"广东省", "东莞市", "松山湖"}, {"广东省", "东莞市", "东莞港"},
        {"广东省", "东莞市", "东莞生态园"}, {"广东省", "中山市", "火炬开发区街道办事处"},
        {"海南省", "儋州市", "华南热作学院"}, {"青海省", "海西蒙古族藏族自治州", "大柴旦行政委员会"},
        {"台湾省", "宜兰县", "钓鱼台"}, {"台湾省", "花莲县", "太鲁阁"}}) {
      var recipient = structured(path[0], path[1], path[2], "测试路10号");
      var before = recipient.deepCopy();
      assertTrue(CreditShippingAddress.valid(recipient), String.join("/", path));
      assertEquals(before, recipient);
      assertFalse(CreditShippingAddress.valid(structured(path[0], "错误市", path[2], "测试路10号")), path[2]);
      String otherProvince = "广东省".equals(path[0]) ? "河北省" : "广东省";
      assertFalse(CreditShippingAddress.valid(structured(otherProvince, path[1], path[2], "测试路10号")), path[2]);
      assertFalse(CreditShippingAddress.valid(structured(path[0], path[1], path[2] + "ABC", "测试路10号")), path[2]);
      assertFalse(CreditShippingAddress.valid(structured(path[0], path[1], path[2].substring(0, 1) + "\n" + path[2].substring(1), "测试路10号")), path[2]);
    }
    assertFalse(CreditShippingAddress.valid(structured("广东省", "东莞市", "任意地点", "测试路10号")));
    assertFalse(CreditShippingAddress.valid(structured("广东省", "东莞市", "ASCII", "测试路10号")));
    assertFalse(CreditShippingAddress.valid(structured("广东省", "东莞市", "松山湖", "路".repeat(201))));
    // These are structured-field compatibility cases; no new free-form full-address guessing is introduced.
    assertFalse(CreditShippingAddress.valid(full("广东省东莞市松山湖测试路10号")));
  }

  @Test void incompleteFullAddressesAndSpecialRegionFallbacksRequireExplicitAdministrativeFields() {
    for(String address:new String[]{"万科星火", "完整收货地址", "测试勿投递", "广东省深圳市万科星火online10栋",
        "深圳市龙岗区万科星火online10栋", "广东省龙岗区万科星火online10栋", "广东省深圳市南山区",
        "北京市建国路1号", "火星省测试市测试区1号", "海南省琼海市嘉积镇人民路1号", "香港特别行政区九龙广东道1号", "澳门特别行政区测试路1号"})
      assertFalse(CreditShippingAddress.valid(full(address)),address);
    assertTrue(CreditShippingAddress.MESSAGE.contains("省直辖县"));
    assertTrue(CreditShippingAddress.MESSAGE.contains("香港澳门"));
  }

  @Test void partialMalformedOrOversizeStructuredRegionsAreNeverSilentlyDropped() {
    var missing=structured("广东省","深圳市","南山区","科技园1号"); missing.remove("district");
    assertFalse(CreditShippingAddress.valid(missing));
    for(String field:new String[]{"province","city","district"}) {
      for(var invalid:new com.fasterxml.jackson.databind.JsonNode[]{mapper.nullNode(),mapper.getNodeFactory().textNode(" "),
          mapper.getNodeFactory().numberNode(123),mapper.getNodeFactory().textNode("长".repeat(51))}) {
        var recipient=structured("广东省","深圳市","南山区","科技园1号"); recipient.set(field,invalid);
        assertFalse(CreditShippingAddress.valid(recipient),field+"="+invalid);
      }
    }
    assertFalse(CreditShippingAddress.valid(structured("广东","深圳市","南山区","科技园1号")));
    assertFalse(CreditShippingAddress.valid(structured("广东省","深圳","南山区","科技园1号")));
    assertFalse(CreditShippingAddress.valid(structured("广东省","深圳市","南山","科技园1号")));
    assertFalse(CreditShippingAddress.valid(structured("香港特别行政区","深圳市","南山区","科技园1号")));
    assertFalse(CreditShippingAddress.valid(full("广东省深圳市南山区测试\n路1号")));
    assertFalse(CreditShippingAddress.valid(structured("广东省","深圳市","南山区","路".repeat(201))));
  }

  @Test void draftFieldMappingUsesTheSameRulesAndKeepsTheSubmittedDataUntouched() {
    var draft=mapper.createObjectNode().put("recipientAddress","万科星火online10栋")
        .put("recipientProvince","广东省").put("recipientCity","深圳市").put("recipientDistrict","龙岗区");
    var before=draft.deepCopy(); assertTrue(CreditShippingAddress.validDraft(draft)); assertEquals(before,draft);
    draft.remove("recipientCity"); assertFalse(CreditShippingAddress.validDraft(draft));
    draft.remove(java.util.List.of("recipientProvince","recipientDistrict"));
    draft.put("recipientAddress","广东省深圳市龙岗区万科星火online10栋"); assertTrue(CreditShippingAddress.validDraft(draft));
    draft.put("recipientAddress","万科星火"); assertFalse(CreditShippingAddress.validDraft(draft));
  }
}
