import{test}from'node:test';import assert from'node:assert/strict';import{csvCell}from'../src/csv.js';
test('CSV exports neutralize formula prefixes including whitespace but preserve numeric refunds',()=>{
 for(const value of ['=1+1','+cmd','-1+2','@SUM(A1)','\t=1','\n=1','  =1'])assert.ok(csvCell(value).startsWith('"\''));
 assert.equal(csvCell(-0.01),'"-0.01"');assert.equal(csvCell('客户"甲'),'"客户""甲"');assert.equal(csvCell(null),'""');
});
