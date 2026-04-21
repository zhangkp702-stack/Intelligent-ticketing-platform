import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class QueryG35SeatBitmap {

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
        int seatType = args.length > 7 ? Integer.parseInt(args[7]) : 2;

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/12306_ticket" + JDBC_SUFFIX;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            List<String> stations = queryStations(connection, trainId);
            long requestMask = buildRequestMask(stations, departure, arrival);
            long available = countAvailableSeats(connection, trainId, seatType, requestMask);
            long occupied = countOccupiedSeats(connection, trainId, seatType, requestMask);

            System.out.println("trainId=" + trainId + ", departure=" + departure + ", arrival=" + arrival + ", seatType=" + seatType);
            System.out.println("stations=" + stations);
            System.out.println("requestMask=" + requestMask);
            System.out.println("availableSeats=" + available);
            System.out.println("occupiedOrConflictSeats=" + occupied);
        }
    }

    private static List<String> queryStations(Connection connection, long trainId) throws Exception {
        String sql = "SELECT departure, arrival FROM t_train_station_relation WHERE train_id = ? ORDER BY departure_time ASC";
        List<String> stations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, trainId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String dep = resultSet.getString("departure");
                    String arr = resultSet.getString("arrival");
                    if (stations.isEmpty()) {
                        stations.add(dep);
                    }
                    if (!stations.contains(arr)) {
                        stations.add(arr);
                    }
                }
            }
        }
        return stations;
    }

    private static long countAvailableSeats(Connection connection, long trainId, int seatType, long requestMask) throws Exception {
        String sql = "SELECT COUNT(*) FROM t_seat WHERE train_id = ? AND seat_type = ? AND (occupy_bitmap & ?) = 0";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, trainId);
            statement.setInt(2, seatType);
            statement.setLong(3, requestMask);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long countOccupiedSeats(Connection connection, long trainId, int seatType, long requestMask) throws Exception {
        String sql = "SELECT COUNT(*) FROM t_seat WHERE train_id = ? AND seat_type = ? AND (occupy_bitmap & ?) != 0";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, trainId);
            statement.setInt(2, seatType);
            statement.setLong(3, requestMask);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long buildRequestMask(List<String> stations, String departure, String arrival) {
        int departureIndex = stations.indexOf(departure);
        int arrivalIndex = stations.indexOf(arrival);
        if (departureIndex < 0 || arrivalIndex < 0 || departureIndex >= arrivalIndex) {
            throw new IllegalArgumentException("invalid departure/arrival: " + departure + " -> " + arrival);
        }
        long mask = 0L;
        for (int i = departureIndex; i < arrivalIndex; i++) {
            mask |= (1L << i);
        }
        return mask;
    }
}
