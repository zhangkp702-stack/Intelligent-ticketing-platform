import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class ExportValidLoadUsers {

    private static final String JDBC_SUFFIX = "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    private static final String USER = "root";
    private static final String PASSWORD = "root";
    private static final String LOAD_USER_PREFIX = "loadtest";
    private static final String LOAD_PASSWORD = "LoadTest@123456";

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "192.168.126.100";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 3306;
        Path output = Path.of(args.length > 2 ? args[2] : "tests/locust/users_valid.csv");

        Map<String, List<String>> validUsers = new TreeMap<>();
        merge(validUsers, loadValidUsers(host, port, "12306_user_0", 0, 15));
        merge(validUsers, loadValidUsers(host, port, "12306_user_1", 16, 31));

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("username,password,passengerIds");
            writer.newLine();
            for (Map.Entry<String, List<String>> entry : validUsers.entrySet()) {
                String username = entry.getKey();
                writer.write(username);
                writer.write(",");
                writer.write(LOAD_PASSWORD);
                writer.write(",");
                writer.write(entry.getValue().stream().sorted().collect(Collectors.joining("|")));
                writer.newLine();
            }
        }

        System.out.println("Exported valid users: " + validUsers.size());
        System.out.println("Output file: " + output.toAbsolutePath());
    }

    private static Map<String, List<String>> loadValidUsers(String host, int port, String dbName, int startTable, int endTable) throws Exception {
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName + JDBC_SUFFIX;
        Set<String> userTableUsers = new TreeSet<>();
        Map<String, List<String>> passengerIdsByUser = new TreeMap<>();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            for (int tableIndex = startTable; tableIndex <= endTable; tableIndex++) {
                collectUsernames(statement, "t_user_" + tableIndex, userTableUsers);
                collectPassengerIds(statement, "t_passenger_" + tableIndex, passengerIdsByUser);
            }
        }

        Map<String, List<String>> result = new TreeMap<>();
        for (String username : userTableUsers) {
            List<String> passengerIds = passengerIdsByUser.get(username);
            if (passengerIds != null && !passengerIds.isEmpty()) {
                result.put(username, passengerIds);
            }
        }
        return result;
    }

    private static void collectUsernames(Statement statement, String tableName, Set<String> container) throws Exception {
        String sql = "SELECT username FROM " + tableName + " WHERE username LIKE '" + LOAD_USER_PREFIX + "%'";
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                container.add(resultSet.getString(1));
            }
        }
    }

    private static void collectPassengerIds(Statement statement, String tableName, Map<String, List<String>> container) throws Exception {
        String sql = "SELECT username, id FROM " + tableName + " WHERE username LIKE '" + LOAD_USER_PREFIX + "%'";
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                String username = resultSet.getString("username");
                String passengerId = resultSet.getString("id");
                container.computeIfAbsent(username, each -> new ArrayList<>()).add(passengerId);
            }
        }
    }

    private static void merge(Map<String, List<String>> target, Map<String, List<String>> source) {
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            target.computeIfAbsent(entry.getKey(), each -> new ArrayList<>()).addAll(entry.getValue());
        }
    }
}
