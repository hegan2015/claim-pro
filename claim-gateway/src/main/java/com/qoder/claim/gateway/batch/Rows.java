package com.qoder.claim.gateway.batch;

import java.util.Map;

/** H2 返回大写列名、MySQL 返回小写：批处理查询结果兼容取值。 */
final class Rows {

    private Rows() {
    }

    static Object val(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v : row.get(key.toUpperCase());
    }

    static String str(Map<String, Object> row, String key) {
        Object v = val(row, key);
        return v == null ? null : String.valueOf(v);
    }

    static long num(Map<String, Object> row, String key) {
        Object v = val(row, key);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}