import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class QueryG35Orders {

    private static final String JDBC_SUFFIX =
            "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "192.168.126.100";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 3306;
        String user = args.length > 2 ? args[2] : "root";
        String password = args.length > 3 ? args[3] : "root";
        String departure = args.length > 4 ? args[4] : "北京南";
        String arrival = args.length > 5 ? args[5] : "杭州东";
        long trainId = args.length > 6 ? Long.parseLong(args[6]) : 1L;

        long total = 0L;
        long pending = 0L;
        long paid = 0L;
        long closed = 0L;
        List<String> shardSummaries = new ArrayList<>();

        for (int db = 0; db <= 1; db++) {
            String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/12306_order_" + db + JDBC_SUFFIX;
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
                for (int table = db * 16; table < (db + 1) * 16; table++) {
                    String sql = "SELECT status, COUNT(*) cnt FROM t_order_" + table
                            + " WHERE train_id = ? AND departure = ? AND arrival = ? GROUP BY status";
                    long shardTotal = 0L;
                    long shardPending = 0L;
                    long shardPaid = 0L;
                    long shardClosed = 0L;
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setLong(1, trainId);
                        statement.setString(2, departure);
                        statement.setString(3, arrival);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                int status = resultSet.getInt("status");
                                long cnt = resultSet.getLong("cnt");
                                shardTotal += cnt;
                                total += cnt;
                                if (status == 0) {
                                    pending += cnt;
                                    shardPending += cnt;
                                } else if (status == 10) {
                                    paid += cnt;
                                    shardPaid += cnt;
                                } else if (status == 30) {
                                    closed += cnt;
                                    shardClosed += cnt;
                                }
                            }
                        }
                    }
                    if (shardTotal > 0) {
                        shardSummaries.add("db=" + db
                                + ", table=t_order_" + table
                                + ", total=" + shardTotal
                                + ", pending=" + shardPending
                                + ", paid=" + shardPaid
                                + ", closed=" + shardClosed);
                    }
                }
            }
        }

        System.out.println("trainId=" + trainId + ", departure=" + departure + ", arrival=" + arrival);
        System.out.println("total=" + total + ", pending=" + pending + ", paid=" + paid + ", closed=" + closed);
        System.out.println("shards:");
        shardSummaries.forEach(System.out::println);
    }
}
