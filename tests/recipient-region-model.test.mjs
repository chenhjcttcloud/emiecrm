import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {changeRegion,recipientRegionOptions} from '../src/recipientRegionModel.js';

const regions=JSON.parse(readFileSync(new URL('../src/chinaAdministrativeRegions.json',import.meta.url),'utf8'));
const draft=(province='广东省',city='深圳市',district='龙岗区')=>({recipientProvince:province,recipientCity:city,
  recipientDistrict:district,recipientAddress:'万科星火online10栋',recipientName:'原收件人'});

test('Guangdong Shenzhen Longgang resolve canonical names, suffix abbreviations and explicit province aliases',()=>{
  for(const province of ['广东省','广东','粤'])for(const city of ['深圳市','深圳']) {
    const source=draft(province,city),before=structuredClone(source),options=recipientRegionOptions(source,regions);
    assert.ok(options.cities.includes('深圳市'));assert.ok(options.districts.includes('龙岗区'));
    assert.deepEqual(source,before);
  }
  assert.ok(!recipientRegionOptions(draft('广东','深'),regions).districts.length,'a partial prefix never picks a first city');
  for(const prefix of ['内','西'])assert.equal(recipientRegionOptions(draft(prefix),regions).cities.length,0,'a geographic province stem is not an ethnicity suffix');
});

test('province and city changes clear only descendants while same values and equivalent aliases preserve them',()=>{
  const source=Object.freeze(draft('广东','深圳','龙岗'));
  const provinceAlias=changeRegion(source,'recipientProvince','广东',regions);
  assert.equal(provinceAlias.recipientProvince,'广东省');assert.equal(provinceAlias.recipientCity,'深圳');assert.equal(provinceAlias.recipientDistrict,'龙岗');
  const cityAlias=changeRegion(source,'recipientCity','深圳',regions);
  assert.equal(cityAlias.recipientProvince,'广东');assert.equal(cityAlias.recipientCity,'深圳市');assert.equal(cityAlias.recipientDistrict,'龙岗');
  const unchanged=draft();
  assert.equal(changeRegion(unchanged,'recipientProvince','广东省',regions),unchanged);
  assert.equal(changeRegion(unchanged,'recipientCity','深圳市',regions),unchanged);
  const canonical=changeRegion(source,'recipientProvince','广东省',regions);
  assert.equal(canonical.recipientCity,'深圳');assert.equal(canonical.recipientDistrict,'龙岗');
  assert.equal(changeRegion(source,'recipientCity','深圳市',regions).recipientDistrict,'龙岗');
  const city=changeRegion(source,'recipientCity','广州市',regions);
  assert.equal(city.recipientProvince,'广东');assert.equal(city.recipientDistrict,'');
  const province=changeRegion(source,'recipientProvince','浙江省',regions);
  assert.equal(province.recipientCity,'');assert.equal(province.recipientDistrict,'');
  for(const changed of [canonical,city,province])assert.equal(changed.recipientAddress,source.recipientAddress);
  assert.equal(source.recipientDistrict,'龙岗');
});

test('explicit unique manual commits canonicalize province city and district while untouched fields and unknown text stay original',()=>{
  const original=Object.freeze(draft('广东','深圳','龙岗'));
  recipientRegionOptions(original,regions);assert.equal(original.recipientProvince,'广东');assert.equal(original.recipientCity,'深圳');
  const province=changeRegion(original,'recipientProvince','广东',regions);
  const city=changeRegion(province,'recipientCity','深圳',regions);
  const district=changeRegion(city,'recipientDistrict','龙岗',regions);
  assert.deepEqual(district,{...original,recipientProvince:'广东省',recipientCity:'深圳市',recipientDistrict:'龙岗区'});
  assert.equal(district.recipientAddress,original.recipientAddress);
  assert.equal(changeRegion(district,'recipientDistrict','未收录行政区域',regions).recipientDistrict,'未收录行政区域');
  const ambiguous=[{name:'甲省',children:[{name:'同名市',children:['同名区','同名县']}]}];
  const manual=changeRegion(draft('甲省','同名市','旧区域'),'recipientDistrict','同名',ambiguous);
  assert.equal(manual.recipientDistrict,'同名','ambiguous district aliases stay manual and never pick a first row');
});

test('the three single-city municipalities fill their actual city on explicit selection; Chongqing keeps both branches',()=>{
  for(const [short,full] of [['北京','北京市'],['天津','天津市'],['上海','上海市']]) {
    const selected=changeRegion(draft(),'recipientProvince',short,regions);
    assert.equal(selected.recipientCity,full);assert.equal(selected.recipientDistrict,'');
    const options=recipientRegionOptions(draft(short,'市辖区'),regions);
    assert.deepEqual(options.cities,[full]);assert.ok(options.districts.length>=16);
  }
  const chongqing=changeRegion(draft(),'recipientProvince','重庆',regions);
  assert.equal(chongqing.recipientCity,'');assert.equal(chongqing.recipientDistrict,'');
  assert.deepEqual(recipientRegionOptions(chongqing,regions).cities,['重庆市','县']);
  assert.ok(recipientRegionOptions(draft('重庆','重庆'),regions).districts.includes('渝中区'));
  assert.ok(recipientRegionOptions(draft('重庆','县'),regions).districts.includes('城口县'));
});

test('Dongguan and Zhongshan preserve their existing town and street entries rather than inventing district names',()=>{
  const dongguan=recipientRegionOptions(draft('广东','东莞'),regions).districts;
  const zhongshan=recipientRegionOptions(draft('广东','中山'),regions).districts;
  assert.ok(dongguan.includes('中堂镇'));assert.ok(dongguan.includes('南城街道'));
  assert.ok(zhongshan.includes('南头镇'));assert.ok(zhongshan.includes('五桂山街道'));
  assert.ok(!dongguan.includes('东莞区'));assert.ok(!zhongshan.includes('中山区'));
});

test('direct-county groups and autonomous-region abbreviations resolve only within their selected province',()=>{
  for(const province of ['海南','河南','湖北']) {
    const canonical=recipientRegionOptions(draft(province,'省直辖县'),regions);
    assert.deepEqual(recipientRegionOptions(draft(province,'省直辖县级行政区划'),regions),canonical);
    assert.ok(canonical.districts.length);
  }
  assert.ok(recipientRegionOptions(draft('海南','省直辖县级行政区划'),regions).districts.includes('五指山市'));
  assert.ok(recipientRegionOptions(draft('广西','南宁'),regions).districts.includes('青秀区'));
  assert.ok(recipientRegionOptions(draft('新疆','自治区直辖县'),regions).districts.includes('石河子市'));
  assert.ok(recipientRegionOptions(draft('四川','阿坝'),regions).districts.includes('汶川县'));
});

test('Hong Kong Macau and Taiwan use the original catalog branches and never borrow another parent districts',()=>{
  assert.deepEqual(recipientRegionOptions(draft('香港'),regions).cities,['香港岛','九龙','新界']);
  assert.ok(recipientRegionOptions(draft('港','九龙'),regions).districts.includes('九龙城区'));
  assert.ok(recipientRegionOptions(draft('澳门','澳门半岛'),regions).districts.includes('大堂区'));
  assert.ok(recipientRegionOptions(draft('澳','离岛'),regions).districts.includes('嘉模堂区'));
  assert.ok(recipientRegionOptions(draft('台湾','台北'),regions).districts.includes('中正区'));
  assert.equal(recipientRegionOptions(draft('广东','台北'),regions).districts.length,0);
});

test('unknown parents and complete legacy free-text addresses remain intact and can continue through manual entry',()=>{
  const source=Object.freeze({recipientProvince:'未收录省份',recipientCity:'手填城市',recipientDistrict:'手填区域',recipientAddress:'完整原文地址，无需重新解析'});
  assert.deepEqual(recipientRegionOptions(source,regions).cities,[]);
  assert.equal(changeRegion(source,'recipientProvince','未收录省份',regions),source);
  const changed=changeRegion(source,'recipientCity','另一个手填城市',regions);
  assert.equal(changed.recipientProvince,source.recipientProvince);assert.equal(changed.recipientDistrict,'');
  assert.equal(changed.recipientAddress,source.recipientAddress);assert.equal(source.recipientDistrict,'手填区域');
  const old={recipientCity:'历史手动城市',recipientDistrict:'历史手动区县',recipientAddress:'旧完整地址'};
  recipientRegionOptions(old,regions);assert.equal(old.recipientCity,'历史手动城市');assert.equal(old.recipientDistrict,'历史手动区县');
  assert.equal(changeRegion(old,'recipientCity','历史手动城市',regions),old);
  assert.deepEqual(recipientRegionOptions({recipientAddress:'广东省深圳市龙岗区旧地址'},regions).cities,[]);
});

test('ambiguous aliases never select the first match and identical city names remain parent scoped',()=>{
  const fixture=[{name:'甲省',children:[{name:'同名市',children:['甲一区']},{name:'同名地区',children:['甲二区']}]},
    {name:'乙省',children:[{name:'同名市',children:['乙一区']}]}];
  assert.deepEqual(recipientRegionOptions(draft('甲','同名'),fixture).districts,[]);
  assert.deepEqual(recipientRegionOptions(draft('甲','同名市'),fixture).districts,['甲一区']);
  assert.deepEqual(recipientRegionOptions(draft('乙','同名'),fixture).districts,['乙一区']);
  const source=draft('甲','同名市','甲一区');
  assert.equal(changeRegion(source,'recipientCity','同名',fixture).recipientDistrict,'');
  const duplicate=[{name:'甲省',aliases:['共享'],children:[]},{name:'乙省',aliases:['共享'],children:[]}];
  assert.deepEqual(recipientRegionOptions(draft('共享'),duplicate).cities,[]);
});
