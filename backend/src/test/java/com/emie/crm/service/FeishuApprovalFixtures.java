package com.emie.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class FeishuApprovalFixtures {
  static final ObjectMapper MAPPER = new ObjectMapper();
  private FeishuApprovalFixtures() {}
  static ObjectNode state() throws Exception {
    return (ObjectNode) MAPPER.readTree("""
        {"revision":1,"customers":[{"id":"c1","name":"隔离测试客户","balance":0}],
         "products":[{"id":"p1","name":"隔离商品","model":"黑色","jstSku":"FIXTURE-SKU","source":"jushuitan","enabled":true}],
         "orders":[]}
        """);
  }
  static ObjectNode order() throws Exception {
    return (ObjectNode) MAPPER.readTree("""
        {"id":"o1","type":"销售订单","status":"审批中","customerId":"c1","customer":"隔离测试客户",
         "owner":"销售员","approvalRequestId":"fixture-request-1","total":24,"paid":0,"note":"订单备注","updated":"2026-11-23 09:00:00",
         "items":[{"product":"p1","qty":2,"price":12}]}
        """);
  }
  static ObjectNode definition() throws Exception {
    return (ObjectNode) MAPPER.readTree("""
        {"status":"ACTIVE","form":[
          {"name":"订单类型","type":"radioV2","id":"type","required":true,"option":[{"text":"销售订单","value":"sale"},{"text":"预收款订单","value":"deposit"}]},
          {"name":"签单人","type":"input","id":"owner","required":true},
          {"name":"客户名称","type":"input","id":"customer","required":true},
          {"name":"订单总额","type":"amount","id":"total","required":true,"option":{"currencyRange":["CNY"]}},
          {"name":"备注","type":"textarea","id":"note"},
          {"name":"订单产品明细","type":"fieldList","id":"items","children":[
            {"name":"产品名称","type":"input","id":"product","required":true},
            {"name":"规格型号","type":"input","id":"model"},
            {"name":"数量","type":"number","id":"quantity","required":true},
            {"name":"销售价","type":"number","id":"price","required":true},
            {"name":"总金额","type":"number","id":"subtotal"}]}
        ],"node_list":[{"need_approver":false}]}
        """);
  }
}
