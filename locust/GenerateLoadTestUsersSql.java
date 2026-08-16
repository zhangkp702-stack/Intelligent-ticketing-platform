import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成按用户名分片的压测用户及乘车人初始化 SQL。
 */
public final class GenerateLoadTestUsersSql {

    private static final long USER_ID_BASE = 1_900_000_000_000_000_000L;
    private static final long PASSENGER_ID_BASE = 1_901_000_000_000_000_000L;
    private static final int SHARDING_COUNT = 32;
    private static final int TABLE_SHARDING_COUNT = 16;
    private static final String JDBC_SUFFIX = "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false&allowMultiQueries=true";

    private GenerateLoadTestUsersSql() {
    }

    /**
     * 根据参数生成压测账号 SQL，或执行已有 SQL 并校验账号数量。
     *
     * @param args 生成模式下为起始编号、结束编号和输出文件路径；执行模式以 --execute 开头
     * @throws Exception 文件生成失败时抛出异常
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--execute".equals(args[0])) {
            executeSqlFile(args);
            return;
        }
        // 默认补充 loadtest1001 到 loadtest5000，可通过命令行覆盖生成范围。
        int start = args.length > 0 ? Integer.parseInt(args[0]) : 1001;
        int end = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        Path output = Path.of(args.length > 2 ? args[2] : "load_test_users_1001_5000.sql");
        if (start < 1 || end < start) {
            throw new IllegalArgumentException("Invalid load-test user range: " + start + "-" + end);
        }

        // 先按数据库和物理表归组，确保生成的 SQL 与线上用户名分片规则一致。
        Map<Integer, List<Integer>> userIndexesByShard = groupUserIndexesByShard(start, end);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writeHeader(writer, start, end);
            for (int databaseIndex = 0; databaseIndex < 2; databaseIndex++) {
                writer.write("USE `12306_user_" + databaseIndex + "`;");
                writer.newLine();
                writer.newLine();
                int tableStart = databaseIndex * TABLE_SHARDING_COUNT;
                int tableEnd = tableStart + TABLE_SHARDING_COUNT;
                for (int tableIndex = tableStart; tableIndex < tableEnd; tableIndex++) {
                    List<Integer> userIndexes = userIndexesByShard.getOrDefault(tableIndex, List.of());
                    if (!userIndexes.isEmpty()) {
                        writeUsers(writer, tableIndex, userIndexes);
                        writePassengers(writer, tableIndex, userIndexes);
                    }
                }
            }
        }
        System.out.println("Generated load-test users: " + (end - start + 1));
        System.out.println("Output file: " + output.toAbsolutePath());
    }

    /**
     * 将已生成的压测账号 SQL 导入指定 MySQL，并校验当前压测账号总数。
     *
     * @param args 执行标记、MySQL 主机、端口和 SQL 文件路径
     * @throws Exception 数据库导入或校验失败时抛出异常
     */
    private static void executeSqlFile(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: --execute <host> <port> <sql-file>");
        }
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        Path sqlFile = Path.of(args[3]);
        String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/12306_user_0" + JDBC_SUFFIX;

        // SQL 中按分片库切换 USE，单次执行可保留原子脚本顺序且支持重复导入。
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", "root");
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            System.out.println("SQL import completed: " + sqlFile.toAbsolutePath());
            System.out.println("Load-test users in MySQL: " + countLoadTestUsers(statement));
            System.out.println("Requested range users in MySQL: " + countRequestedRangeUsers(statement, 1001, 5000));
        }
    }

    /**
     * 汇总两个用户分片库中 loadtest 前缀的有效用户数量。
     *
     * @param statement 已连接 MySQL 的执行器
     * @return 压测账号总数
     * @throws Exception 数据库查询失败时抛出异常
     */
    private static int countLoadTestUsers(Statement statement) throws Exception {
        int count = 0;
        for (int databaseIndex = 0; databaseIndex < 2; databaseIndex++) {
            // 切换到当前逻辑库后遍历其拥有的十六张物理用户表。
            statement.execute("USE `12306_user_" + databaseIndex + "`");
            int tableStart = databaseIndex * TABLE_SHARDING_COUNT;
            int tableEnd = tableStart + TABLE_SHARDING_COUNT;
            for (int tableIndex = tableStart; tableIndex < tableEnd; tableIndex++) {
                try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM `t_user_" + tableIndex + "` WHERE username LIKE 'loadtest%' AND del_flag = 0")) {
                    if (resultSet.next()) {
                        count += resultSet.getInt(1);
                    }
                }
            }
        }
        return count;
    }

    /**
     * 汇总指定编号范围内的压测账号数量，用于确认新增范围未缺失。
     *
     * @param statement 已连接 MySQL 的执行器
     * @param start 起始用户编号
     * @param end 结束用户编号
     * @return 指定编号范围内的压测账号数
     * @throws Exception 数据库查询失败时抛出异常
     */
    private static int countRequestedRangeUsers(Statement statement, int start, int end) throws Exception {
        int count = 0;
        for (int databaseIndex = 0; databaseIndex < 2; databaseIndex++) {
            // 使用生成时固定分配的用户 ID 范围统计，避免历史同前缀账号参与计数。
            statement.execute("USE `12306_user_" + databaseIndex + "`");
            int tableStart = databaseIndex * TABLE_SHARDING_COUNT;
            int tableEnd = tableStart + TABLE_SHARDING_COUNT;
            for (int tableIndex = tableStart; tableIndex < tableEnd; tableIndex++) {
                String sql = "SELECT COUNT(*) FROM `t_user_" + tableIndex + "` WHERE id >= "
                        + (USER_ID_BASE + start) + " AND id <= " + (USER_ID_BASE + end) + " AND del_flag = 0";
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    if (resultSet.next()) {
                        count += resultSet.getInt(1);
                    }
                }
            }
        }
        return count;
    }

    /**
     * 按用户名的 Java 哈希结果归组用户编号。
     *
     * @param start 起始用户编号
     * @param end 结束用户编号
     * @return 物理表编号与用户编号列表的映射
     */
    private static Map<Integer, List<Integer>> groupUserIndexesByShard(int start, int end) {
        // 使用与 HASH_MOD 分表算法相同的 Java String.hashCode 计算方式。
        Map<Integer, List<Integer>> result = new LinkedHashMap<>();
        for (int index = start; index <= end; index++) {
            String username = username(index);
            int shard = (int) (Math.abs((long) username.hashCode()) % SHARDING_COUNT);
            result.computeIfAbsent(shard, ignored -> new ArrayList<>()).add(index);
        }
        return result;
    }

    /**
     * 写入生成文件的说明信息。
     *
     * @param writer SQL 文件写入器
     * @param start 起始用户编号
     * @param end 结束用户编号
     * @throws Exception 文件写入失败时抛出异常
     */
    private static void writeHeader(BufferedWriter writer, int start, int end) throws Exception {
        // 标注账号范围和统一密码，便于执行前人工复核。
        writer.write("-- Load-test users " + username(start) + " to " + username(end));
        writer.newLine();
        writer.write("-- Password: LoadTest@123456");
        writer.newLine();
        writer.write("-- Generated for username-sharded t_user and t_passenger tables");
        writer.newLine();
        writer.newLine();
    }

    /**
     * 写入一个物理分片中的用户数据。
     *
     * @param writer SQL 文件写入器
     * @param tableIndex 物理表编号
     * @param userIndexes 当前分片的用户编号
     * @throws Exception 文件写入失败时抛出异常
     */
    private static void writeUsers(BufferedWriter writer, int tableIndex, List<Integer> userIndexes) throws Exception {
        // 使用固定主键和 INSERT IGNORE，使同一初始化文件可以安全重复执行。
        writer.write("INSERT IGNORE INTO `t_user_" + tableIndex + "` ");
        writer.write("(id, username, password, real_name, region, id_type, user_type, verify_status, deletion_time, create_time, update_time, del_flag)");
        writer.newLine();
        writer.write("VALUES");
        writer.newLine();
        for (int position = 0; position < userIndexes.size(); position++) {
            int index = userIndexes.get(position);
            String suffix = position + 1 == userIndexes.size() ? ";" : ",";
            writer.write("    (" + (USER_ID_BASE + index) + ", '" + username(index)
                    + "', 'LoadTest@123456', 'Load User " + String.format("%04d", index)
                    + "', '0', 0, 0, 0, 0, NOW(), NOW(), 0)" + suffix);
            writer.newLine();
        }
        writer.newLine();
    }

    /**
     * 写入一个物理分片中的乘车人数据。
     *
     * @param writer SQL 文件写入器
     * @param tableIndex 物理表编号
     * @param userIndexes 当前分片的用户编号
     * @throws Exception 文件写入失败时抛出异常
     */
    private static void writePassengers(BufferedWriter writer, int tableIndex, List<Integer> userIndexes) throws Exception {
        // 每个压测账号创建一个同分片乘车人，满足 JMeter 的购票前置查询。
        writer.write("INSERT IGNORE INTO `t_passenger_" + tableIndex + "` ");
        writer.write("(id, username, real_name, id_type, discount_type, create_date, verify_status, create_time, update_time, del_flag)");
        writer.newLine();
        writer.write("VALUES");
        writer.newLine();
        for (int position = 0; position < userIndexes.size(); position++) {
            int index = userIndexes.get(position);
            String suffix = position + 1 == userIndexes.size() ? ";" : ",";
            writer.write("    (" + (PASSENGER_ID_BASE + index) + ", '" + username(index)
                    + "', 'Load User " + String.format("%04d", index)
                    + "', 0, 0, NOW(), 0, NOW(), NOW(), 0)" + suffix);
            writer.newLine();
        }
        writer.newLine();
    }

    /**
     * 格式化压测用户名。
     *
     * @param index 用户编号
     * @return loadtest 前缀的四位编号用户名
     */
    private static String username(int index) {
        // 1001 到 5000 均保持与现有 JMeter 脚本相同的四位编号格式。
        return String.format("loadtest%04d", index);
    }
}
