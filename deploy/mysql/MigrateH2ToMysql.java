import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 → MySQL 一次性数据迁移（claim-pro 切库用）。
 * 语义：以 H2 为权威源，全量覆盖 MySQL claim 库的 14 张业务表（目标库须为新建空库或可覆盖）。
 * H2 文件只读打开，迁移后原样保留作备份。
 *
 * 用法（先停 claim-pro 应用）：
 *   MIGRATE_MYSQL_PASSWORD='<口令>' java -cp <h2.jar>:<mysql-connector-j.jar> \
 *     deploy/mysql/MigrateH2ToMysql.java ./data/claim \
 *     'jdbc:mysql://127.0.0.1:3306/claim?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8' claim
 */
public class MigrateH2ToMysql {

    private static final String[] TABLES = {
            "policy_snapshot", "part_price", "claim_case", "claim_material", "claim_approval",
            "ledger_business", "ledger_fund", "pay_outbox", "recon_diff", "repair_shop_flag",
            "evidence_index", "claim_decision", "tool_audit", "core_register", "graph_case_shop"
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("参数: <h2文件基名> <mysqlJdbcUrl> <mysqlUser>；口令走环境变量 MIGRATE_MYSQL_PASSWORD");
            System.exit(2);
        }
        String pw = System.getenv("MIGRATE_MYSQL_PASSWORD");
        if (pw == null || pw.isBlank()) {
            System.err.println("缺少环境变量 MIGRATE_MYSQL_PASSWORD");
            System.exit(2);
        }
        String h2Url = "jdbc:h2:file:" + args[0] + ";MODE=MySQL;ACCESS_MODE_DATA=r";
        boolean ok = true;
        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "");
             Connection my = DriverManager.getConnection(args[1], args[2], pw)) {
            my.setAutoCommit(false);
            for (String table : TABLES) {
                if (!tableExists(h2, table)) {
                    System.out.printf("%-20s SKIP（H2 快照无此表，由种子/启动补齐）%n", table);
                    continue;
                }
                List<String> cols = new ArrayList<>();
                List<Object[]> rows = new ArrayList<>();
                try (Statement st = h2.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    for (int i = 1; i <= n; i++) {
                        cols.add(md.getColumnName(i));
                    }
                    while (rs.next()) {
                        Object[] row = new Object[n];
                        for (int i = 0; i < n; i++) {
                            row[i] = rs.getObject(i + 1);
                        }
                        rows.add(row);
                    }
                }
                try (Statement st = my.createStatement()) {
                    st.executeUpdate("DELETE FROM " + table);
                }
                String placeholders = String.join(",", java.util.Collections.nCopies(cols.size(), "?"));
                String insert = "INSERT INTO " + table + "(" + String.join(",", cols) + ") VALUES(" + placeholders + ")";
                try (PreparedStatement ps = my.prepareStatement(insert)) {
                    int batch = 0;
                    for (Object[] row : rows) {
                        for (int i = 0; i < row.length; i++) {
                            ps.setObject(i + 1, row[i]);
                        }
                        ps.addBatch();
                        if (++batch % 500 == 0) {
                            ps.executeBatch();
                        }
                    }
                    ps.executeBatch();
                }
                long target;
                try (Statement st = my.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                    target = rs.getLong(1);
                }
                boolean match = target == rows.size();
                ok &= match;
                System.out.printf("%-20s H2=%-6d MySQL=%-6d %s%n", table, rows.size(), target, match ? "OK" : "MISMATCH");
            }
            if (ok) {
                my.commit();
                System.out.println("迁移完成并已提交。H2 文件未改动，可作备份保留。");
            } else {
                my.rollback();
                System.err.println("存在行数不一致，已回滚。");
                System.exit(1);
            }
        }
    }

    private static boolean tableExists(Connection h2, String table) throws SQLException {
        try (ResultSet rs = h2.getMetaData().getTables(null, null, table.toUpperCase(), null)) {
            return rs.next();
        }
    }
}