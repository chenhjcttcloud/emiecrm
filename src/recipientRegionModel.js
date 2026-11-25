const REGION_KEYS = ['recipientProvince', 'recipientCity', 'recipientDistrict'];
const MUNICIPALITIES = new Set(['北京市', '天津市', '上海市', '重庆市']);
const PROVINCE_ALIASES = {
  '北京市': ['京'], '天津市': ['津'], '河北省': ['冀'], '山西省': ['晋'],
  '内蒙古自治区': ['内蒙古', '蒙'], '辽宁省': ['辽'], '吉林省': ['吉'], '黑龙江省': ['黑'],
  '上海市': ['沪'], '江苏省': ['苏'], '浙江省': ['浙'], '安徽省': ['皖'], '福建省': ['闽'],
  '江西省': ['赣'], '山东省': ['鲁'], '河南省': ['豫'], '湖北省': ['鄂'], '湖南省': ['湘'],
  '广东省': ['粤'], '广西壮族自治区': ['广西', '桂'], '海南省': ['琼'], '重庆市': ['渝'],
  '四川省': ['川', '蜀'], '贵州省': ['贵', '黔'], '云南省': ['云', '滇'],
  '西藏自治区': ['西藏', '藏'], '陕西省': ['陕', '秦'], '甘肃省': ['甘', '陇'],
  '青海省': ['青'], '宁夏回族自治区': ['宁夏', '宁'], '新疆维吾尔自治区': ['新疆', '新'],
  '台湾省': ['台湾', '台'], '香港特别行政区': ['香港', '港'], '澳门特别行政区': ['澳门', '澳'],
};
const GROUP_ALIASES = {
  '省直辖县': ['省直辖县级行政区划', '省直辖县级行政区', '省直辖县级', '省直辖'],
  '自治区直辖县级行政区划': ['自治区直辖县', '自治区直辖县级', '自治区直辖'],
};
// Remove administrative suffixes only; never search by a prefix or guess a first match.
const ETHNIC_SUFFIX = '(?:(?:土家|布依|哈尼|景颇|傈僳|朝鲜|维吾尔|柯尔克孜|哈萨克|蒙古|苗|侗|藏|羌|彝|壮|白|傣|回|瑶|黎)(?:族)?)+';
const AUTONOMOUS_SUFFIX = new RegExp(ETHNIC_SUFFIX + '自治(?:区|州|县)$');

const text = value => String(value ?? '').trim();
const name = row => typeof row === 'string' ? row : typeof row?.name === 'string' ? row.name : '';
const children = row => Array.isArray(row?.children) ? row.children : [];
const choices = rows => [...new Set(rows.map(name).filter(Boolean))];

function aliases(row, level, province) {
  const canonical = name(row);
  const result = new Set([canonical]);
  const withoutEthnicity = canonical.replace(AUTONOMOUS_SUFFIX, '');
  if (level > 0 && withoutEthnicity && withoutEthnicity !== canonical) result.add(withoutEthnicity);
  const suffix = level === 0 ? /(?:特别行政区|自治区|省|市)$/
    : level === 1 ? /(?:自治州|地区|市|盟)$/ : /(?:自治县|街道办事处|街道|区|县|市|镇|乡)$/;
  const shortened = canonical.replace(suffix, '');
  if (shortened) result.add(shortened);
  if (level === 0) for (const alias of PROVINCE_ALIASES[canonical] ?? []) result.add(alias);
  if (level === 1) {
    for (const alias of GROUP_ALIASES[canonical] ?? []) result.add(alias);
    if (MUNICIPALITIES.has(name(province)) && canonical === name(province)) result.add('市辖区');
  }
  if (Array.isArray(row?.aliases)) for (const alias of row.aliases)
    if (typeof alias === 'string' && alias.trim()) result.add(alias.trim());
  return result;
}

function resolve(rows, value, level, province) {
  const entered = text(value);
  if (!entered) return null;
  const exact = rows.filter(row => name(row) === entered);
  if (exact.length) return exact.length === 1 ? exact[0] : null;
  const matched = rows.filter(row => aliases(row, level, province).has(entered));
  return matched.length === 1 ? matched[0] : null;
}

function parents(draft, regions) {
  const provinces = Array.isArray(regions) ? regions : [];
  const province = resolve(provinces, draft?.recipientProvince, 0);
  const cities = children(province);
  return {provinces, province, cities, city: resolve(cities, draft?.recipientCity, 1, province)};
}

/** Resolve suggestions without normalizing or mutating any saved address field. */
export function recipientRegionOptions(draft = {}, regions = []) {
  const {provinces, cities, city} = parents(draft, regions);
  return {provinces: choices(provinces), cities: choices(cities), districts: choices(children(city))};
}

/** Only an explicit user change clears descendants; equivalent canonical/alias values keep them. */
export function changeRegion(draft = {}, key, value, regions = []) {
  if (!REGION_KEYS.includes(key)) throw Error('不支持的收货地区字段');
  const entered = String(value ?? '');
  const {provinces, province, cities, city} = parents(draft, regions);
  const siblings = key === 'recipientProvince' ? provinces : key === 'recipientCity' ? cities : children(city);
  const level = REGION_KEYS.indexOf(key);
  const previous = resolve(siblings, draft[key], level, province);
  const selected = resolve(siblings, entered, level, province);
  const nextValue = selected ? name(selected) : entered;
  if (nextValue === String(draft[key] ?? '')) return draft;
  const next = {...draft, [key]: nextValue};
  if (key === 'recipientDistrict') return next;
  const same = text(draft[key]) === text(entered) || previous && selected && name(previous) === name(selected);
  if (same) return next;
  next.recipientDistrict = '';
  if (key === 'recipientProvince') {
    next.recipientCity = '';
    if (selected && MUNICIPALITIES.has(name(selected)) && children(selected).length === 1)
      next.recipientCity = name(children(selected)[0]);
  }
  return next;
}
