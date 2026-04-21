import java.sql.*;
public class QueryPassenger {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://192.168.126.100:3306/12306_user_0?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    try (Connection c = DriverManager.getConnection(url, "root", "root");
         Statement s = c.createStatement()) {
      ResultSet rs = s.executeQuery("select id, username, real_name, id_type, id_card, discount_type, phone, create_date, verify_status, del_flag from t_passenger_0 where username='loadtest0001'");
      while (rs.next()) {
        System.out.println(rs.getLong("id") + "," + rs.getString("username") + "," + rs.getString("real_name") + "," + rs.getInt("id_type") + "," + rs.getString("id_card") + "," + rs.getInt("discount_type") + "," + rs.getString("phone") + "," + rs.getTimestamp("create_date") + "," + rs.getInt("verify_status") + "," + rs.getInt("del_flag"));
      }
    }
  }
}
