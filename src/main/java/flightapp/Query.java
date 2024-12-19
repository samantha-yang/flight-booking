package flightapp;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Runs queries against a back-end database
 */
public class Query extends QueryAbstract {
  //
  // Canned queries
  //
  private static final String FLIGHT_CAPACITY_SQL = "SELECT capacity FROM Flights WHERE fid = ?";
  private PreparedStatement flightCapacityStmt;

  // Clear tables
  private static final String CLEAR_RESERVE = "DELETE FROM Reservations_yangsam;";
  private PreparedStatement clearReserves;

  private static final String CLEAR_USERS = "DELETE FROM Users_yangsam;";
  private PreparedStatement clearUsers;

  // Create user
  private static final String CREATE_USER_SQL = "INSERT INTO Users_yangsam (username, hashedPassword, balance) VALUES (?, ?, ?)";
  private PreparedStatement createUserStatement;

  // Login user
  private static final String CHECK_USER_SQL = "SELECT username FROM Users_yangsam WHERE LOWER(username) = LOWER(?)";
  private PreparedStatement checkUserStatement;

  private static final String GET_PASSWORD_SQL = "SELECT hashedPassword FROM Users_yangsam WHERE LOWER(username) = LOWER(?)";
  private PreparedStatement getPasswordStatement;

  // Search itinerary
  private static final String SEARCH_SQL1 = "SELECT fid, day_of_month, carrier_id, flight_num, origin_city, dest_city, actual_time, capacity, price "
                                          + "FROM Flights "
                                          + "WHERE origin_city = ? AND dest_city = ? AND day_of_month = ? AND canceled = 0 "
                                          + "ORDER BY actual_time ASC, fid ASC";
  private PreparedStatement searchStatement1;

  private static final String SEARCH_SQL2 = "SELECT F1.fid AS fid1, F1.day_of_month AS day1, F1.carrier_id AS carrier1, F1.flight_num AS flight_num1, F1.origin_city AS origin1, "
                                          + "F1.dest_city AS dest1, F1.actual_time AS time1, F1.capacity AS space1, F1.price AS price1, "
                                          + "F2.fid AS fid2, F2.day_of_month AS day2, F2.carrier_id AS carrier2, F2.flight_num AS flight_num2, F2.origin_city AS origin2, "
                                          + "F2.dest_city AS dest2, F2.actual_time AS time2, F2.capacity AS space2, F2.price AS price2 "
                                          + "FROM Flights AS F1, Flights AS F2 "
                                          + "WHERE F1.origin_city = ? AND F2.dest_city = ? AND F1.dest_city = F2.origin_city "
                                          + "AND F1.day_of_month = ? AND F2.day_of_month = ? AND F1.canceled = 0 AND F2.canceled = 0 "
                                          + "ORDER BY (F1.actual_time + F2.actual_time) ASC, F1.fid ASC, F2.fid ASC";
  private PreparedStatement searchStatement2;

  // Booking
  private static final String SAME_DAY_SQL = "SELECT * FROM Flights AS F INNER JOIN Reservations_yangsam R ON F.fid = R.fid1 "
                                           + "OR F.fid = R.fid2 WHERE day_of_month = ? AND username = ?";
  private PreparedStatement sameDayStatement;

  private static final String CAPACITY_SQL = "SELECT F.capacity - COUNT(R.res_id) AS num_seats "
                                           + "FROM Flights as F LEFT JOIN Reservations_yangsam R ON F.fid = R.fid1 OR F.fid = R.fid2 "
                                           + "WHERE F.fid = ? GROUP BY F.capacity";
  private PreparedStatement capacityStatement;

  private static final String RESERVATION_SQL = "SELECT COUNT(*) AS reserve_num FROM Reservations_yangsam";
  private PreparedStatement reservationStatement;

  private static final String MAKE_RESERVATION_SQL = "INSERT INTO Reservations_yangsam (res_id, paid, username, fid1, fid2) VALUES (?, ?, ?, ?, ?)";
  private PreparedStatement makeReservationStatement;

  // Pay
  private static final String VERIFY_SQL = "SELECT R.paid, R.fid1, R.fid2, F1.price AS price1, F2.price AS price2 "
                                         + "FROM Reservations_yangsam AS R "
                                         + "LEFT JOIN Flights F1 ON R.fid1 = F1.fid "
                                         + "LEFT JOIN Flights F2 ON R.fid2 = F2.fid "
                                         + "WHERE R.res_id = ? AND R.username = ?";
  private PreparedStatement verifyStatement;

  private static final String BALANCE_SQL = "SELECT balance FROM Users_yangsam WHERE username = ?";
  private PreparedStatement balanceStatement;

  private static final String UPDATE_BAL_SQL = "UPDATE Users_yangsam SET balance = balance - ? WHERE username = ?";
  private PreparedStatement updateBalStatement;

  private static final String UPDATE_RESERVE_SQL = "UPDATE Reservations_yangsam SET paid = 1 WHERE res_id = ?";
  private PreparedStatement updateReserveStatement;

  // Reservations
  private static final String FIND_RESERVE_SQL = "SELECT R.res_id, R.paid, F1.fid AS fid1, F1.day_of_month AS day1, F1.carrier_id AS carrier1, "
                                               + "F1.flight_num AS flight_num1, F1.origin_city AS origin1, F1.dest_city AS dest1, "
                                               + "F1.actual_time AS time1, F1.capacity AS capacity1, F1.price AS price1, "
                                               + "F2.fid AS fid2, F2.day_of_month AS day2, F2.carrier_id AS carrier2, "
                                               + "F2.flight_num AS flight_num2, F2.origin_city AS origin2, F2.dest_city AS dest2, "
                                               + "F2.actual_time AS time2, F2.capacity AS capacity2, F2.price AS price2 "
                                               + "FROM Reservations_yangsam AS R "
                                               + "LEFT JOIN Flights F1 ON R.fid1 = F1.fid "
                                               + "LEFT JOIN Flights F2 ON R.fid2 = F2.fid "
                                               + "WHERE R.username = ? "
                                               + "ORDER BY R.res_id ASC";
  private PreparedStatement findReserveStatement;

  //
  // Instance variables
  //

  private String user_name; // user's created username
  private boolean loggedIn; // indicates if user is logged in or not
  private List<Itinerary> itineraries; // booking itineraries

  protected Query() throws SQLException, IOException {
    this.user_name = "";
    this.loggedIn = false;
    this.itineraries = null;
    prepareStatements();
  }

  /**
   * Clear the data in any custom tables created.
   * 
   * WARNING! Do not drop any tables and do not clear the flights table.
   */
  public void clearTables() {
    try {
      // Clear all tables made
      clearReserves.executeUpdate();
      clearUsers.executeUpdate();

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /*
   * prepare all the SQL statements in this method.
   */
  private void prepareStatements() throws SQLException {
    flightCapacityStmt = conn.prepareStatement(FLIGHT_CAPACITY_SQL);

    // Clear table
    clearReserves = conn.prepareStatement(CLEAR_RESERVE);
    clearUsers = conn.prepareStatement(CLEAR_USERS);
    
    // Create
    createUserStatement = conn.prepareStatement(CREATE_USER_SQL);

    // Login
    checkUserStatement = conn.prepareStatement(CHECK_USER_SQL);
    getPasswordStatement = conn.prepareStatement(GET_PASSWORD_SQL);

    // Search
    searchStatement1 = conn.prepareStatement(SEARCH_SQL1);
    searchStatement2 = conn.prepareStatement(SEARCH_SQL2);

    // Booking
    sameDayStatement = conn.prepareStatement(SAME_DAY_SQL);
    capacityStatement = conn.prepareStatement(CAPACITY_SQL);
    reservationStatement = conn.prepareStatement(RESERVATION_SQL);
    makeReservationStatement = conn.prepareStatement(MAKE_RESERVATION_SQL);

    // Pay
    verifyStatement = conn.prepareStatement(VERIFY_SQL);
    balanceStatement = conn.prepareStatement(BALANCE_SQL);
    updateBalStatement = conn.prepareStatement(UPDATE_BAL_SQL);
    updateReserveStatement = conn.prepareStatement(UPDATE_RESERVE_SQL);

    // Reservations
    findReserveStatement = conn.prepareStatement(FIND_RESERVE_SQL);
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_login(String username, String password) {
    if (loggedIn) {
      return "User already logged in\n";
    }

    try {
      // Check if username exists
      checkUserStatement.setString(1, username);
      try (ResultSet rs = checkUserStatement.executeQuery()) {
        if (!rs.next()) {
          return "Login failed\n";
        }
      }

      // Get hashed password
      getPasswordStatement.setString(1, username);
      try (ResultSet rs = getPasswordStatement.executeQuery()) {
        if (rs.next()) {
          byte[] storedHash = rs.getBytes("hashedPassword");

          // If password matches
          if (PasswordUtils.plaintextMatchesSaltedHash(password, storedHash)) {
            this.user_name = username;
            this.loggedIn = true;
            this.itineraries = null;
            return "Logged in as " + this.user_name + "\n";
          } else {
            return "Login failed\n";
          }
        } else {
          return "Login failed\n";
        }
      }
    } catch (SQLException e) {
      return "Login failed\n";
    }
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_createCustomer(String username, String password, int initAmount) {
    if (initAmount < 0) {
      return "Failed to create user\n";
    }

    try {
      // Check for existing username
      checkUserStatement.setString(1, username);
      try (ResultSet rs = checkUserStatement.executeQuery()) {
        if (rs.next()) {
          return "Failed to create user\n";
        }
      }

      // Generate salted hash
      byte[] saltedHash = PasswordUtils.saltAndHashPassword(password);

      // Create new user in table
      createUserStatement.setString(1, username);
      createUserStatement.setBytes(2, saltedHash);
      createUserStatement.setInt(3, initAmount);
      createUserStatement.executeUpdate();

      // Set username
      this.user_name = username;

      return "Created user " + this.user_name + "\n";
    } catch (SQLException e) {
      return "Failed to create user\n";
    }
}

  /* See QueryAbstract.java for javadoc */
  public String transaction_search(String originCity, String destinationCity, 
                                   boolean directFlight, int dayOfMonth,
                                   int numberOfItineraries) {

    try {
      // Prioritize direct flights
      List<Itinerary> direct = searchDirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries);
      List<Itinerary> indirect = new ArrayList<>();

      if (!directFlight) {
        indirect = searchIndirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries - direct.size());
      }

      // Combine the direct and indirect itineraries
      List<Itinerary> iti = new ArrayList<>();
      iti.addAll(direct);
      iti.addAll(indirect);

      // If no itineraries were found
      if (iti.size() == 0) {
        return "No flights match your selection\n";
      }
      
      // Sort the itineraries by time
      Collections.sort(iti);
      
      // Set itineraries to the itineraries found for later use
      this.itineraries = iti;
      
      // Format itineraries
      String result = "";
      for (int i = 0; i < iti.size(); i++) {
        iti.get(i).setID(i);
        result += iti.get(i).toString();
      }
      return result;

    } catch (SQLException e) {
      return "Failed to search\n";
    }
  }

  // Use to find direct flights and store them
  private List<Itinerary> searchDirectFlights(String origin_city, String dest_city, int dayOfMonth, int num_itinerary) throws SQLException {
    searchStatement1.clearParameters();
    searchStatement1.setString(1, origin_city);
    searchStatement1.setString(2, dest_city);
    searchStatement1.setInt(3, dayOfMonth);

    // Search for direct flights and store as itineraries
    List<Itinerary> itinerary = new ArrayList<>();
    try (ResultSet rs = searchStatement1.executeQuery()) {
      while (rs.next() && itinerary.size() < num_itinerary) {
        // Get values and create flight to store as itinerary
        int id = rs.getInt("fid");
        int day = rs.getInt("day_of_month");
        String carrier = rs.getString("carrier_id");
        String flight_num = rs.getString("flight_num");
        String origin = rs.getString("origin_city");
        String dest = rs.getString("dest_city");
        int time = rs.getInt("actual_time");
        int space = rs.getInt("capacity");
        int price = rs.getInt("price");

        Flight flight = new Flight(id, day, carrier, flight_num, origin, dest, time, space, price);
        
        // Store itinerary in list
        Itinerary flight_info = new Itinerary(flight, null);
        flight_info.addToFlights(flight);
        itinerary.add(flight_info);
      }
    }
    return itinerary;
  }

  // Used to find indirect flights and store them
  private List<Itinerary> searchIndirectFlights(String origin_city, String dest_city, int dayOfMonth, int num_itinerary) throws SQLException {
    searchStatement2.clearParameters();
    searchStatement2.setString(1, origin_city);
    searchStatement2.setString(2, dest_city);
    searchStatement2.setInt(3, dayOfMonth);
    searchStatement2.setInt(4, dayOfMonth);

    List<Itinerary> itinerary = new ArrayList<>();
    try (ResultSet rs = searchStatement2.executeQuery()) {
      while (rs.next() && itinerary.size() < num_itinerary) {
        // Get values and create flight1 to store as itinerary
        int id1 = rs.getInt("fid1");
        int day1 = rs.getInt("day1");
        String carrier1 = rs.getString("carrier1");
        String flight_num1 = rs.getString("flight_num1");
        String origin1 = rs.getString("origin1");
        String dest1 = rs.getString("dest1");
        int time1 = rs.getInt("time1");
        int space1 = rs.getInt("space1");
        int price1 = rs.getInt("price1");

        Flight flight1 = new Flight(id1, day1, carrier1, flight_num1, origin1, dest1, time1, space1, price1);
        
        // Get values and create flight2 to store as itinerary
        int id2 = rs.getInt("fid2");
        int day2 = rs.getInt("day2");
        String carrier2 = rs.getString("carrier2");
        String flight_num2 = rs.getString("flight_num2");
        String origin2 = rs.getString("origin2");
        String dest2 = rs.getString("dest2");
        int time2 = rs.getInt("time2");
        int space2 = rs.getInt("space2");
        int price2 = rs.getInt("price2");
        
        Flight flight2 = new Flight(id2, day2, carrier2, flight_num2, origin2, dest2, time2, space2, price2);

        // Store itinerary in list
        Itinerary flight_info = new Itinerary(flight1, flight2);
        flight_info.addToFlights(flight1);
        flight_info.addToFlights(flight2);
        itinerary.add(flight_info);
      }
    }
    return itinerary;
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_book(int itineraryId) {
    // Check if user is logged in
    if (!loggedIn) {
      return "Cannot book reservations, not logged in\n";
    }

    // Check if itineraries are null
    if (itineraries == null) {
      return "No such itinerary " + itineraryId + "\n";
    }

    if (itineraryId >= itineraries.size()) {
      return "No such itinerary " + itineraryId + "\n";
    }

    // Get itinerary
    Itinerary itinerary = itineraries.get(itineraryId);
    if (itinerary == null) {
      return "No such itinerary " + itineraryId + "\n";
    }

    // Initialize reservation ID and flight type
    int reservationId = 0;
    int direct = 1;

    try {
      conn.setAutoCommit(false);

      // Check if user has already booked a flight on the same day
      for (Flight flight : itinerary.flights) {
        sameDayStatement.setInt(1, flight.dayOfMonth);
        sameDayStatement.setString(2, user_name);
        try (ResultSet rs = sameDayStatement.executeQuery()) {
          if (rs.next()) {
            conn.rollback();
            return "You cannot book two flights in the same day\n";
          }
        }
      }

      // Check capacity of flight1 and flight2 (if there is a flight2)
      for (Flight flight : itinerary.flights) {
        capacityStatement.setInt(1, flight.fid);
          try (ResultSet rs = capacityStatement.executeQuery()) {
          if (rs.next()) {
            int num_seats = rs.getInt("num_seats");
            if (num_seats <= 0) {
              conn.rollback();
              return "Booking failed\n";
            }
          }
        }
      }

      // Get the next reservation ID and keep track of it
      try (ResultSet rs = reservationStatement.executeQuery()) {
        if (rs.next() && rs.getInt("reserve_num") > 0) {
          reservationId = rs.getInt("reserve_num") + 1;
        } else {
          reservationId = 1;
        }
      } catch (SQLException e) {
        conn.rollback();
        return "Booking failed\n";
      }

      // Create flights to make a reservation
      Flight f1 = itinerary.flight1;
      Flight f2 = itinerary.flight2;

      // If there is a second flight, indicate indirect flight
      if (f2 != null) {
        direct = 0;
      }

      // Make reservation to update
      makeReservationStatement.setInt(1, reservationId);
      makeReservationStatement.setInt(2, 0);
      makeReservationStatement.setString(3, user_name);
      makeReservationStatement.setInt(4, f1.fid);
      
      // Flight is indirect, set flight2 ID
      if (direct == 0) {
        makeReservationStatement.setInt(5, f2.fid);
      // Flight is direct
      } else {
        makeReservationStatement.setNull(5, java.sql.Types.INTEGER);
      }

      // Update reservation and commit
      makeReservationStatement.executeUpdate();
      conn.commit();
      return "Booked flight(s), reservation ID: " + reservationId + "\n";
      
    } catch (SQLException e1) {
        // If deadlock
        if (isDeadlock(e1)) {
        try {
          conn.rollback();
          return transaction_book(itineraryId); // Retry if there is a deadlock
        } catch (SQLException e2) {
          e2.printStackTrace();
        }
      // Not deadlock
      } else {
        try {
          conn.rollback();
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }

      e1.printStackTrace();
      return "Booking failed\n";

    } finally {
      try {
        conn.setAutoCommit(true);
      } catch (SQLException e) {
        e.printStackTrace();
      }
    }
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_pay(int reservationId) {
    // Check if user is logged in
    if (!loggedIn) {
      return "Cannot pay, not logged in\n";
    }
    
    // Check if valid reservation ID
    if (reservationId < 0) {
      return "Cannot find unpaid reservation " + reservationId + " under user: " + user_name + "\n";
    }

    try {
      conn.setAutoCommit(false);

      // Check if reservation exists and not paid for yet
      verifyStatement.setInt(1, reservationId);
      verifyStatement.setString(2, user_name);
      try (ResultSet rs = verifyStatement.executeQuery()) {
        if (!rs.next() || rs.getInt("paid") == 1) {
          conn.rollback();
          return "Cannot find unpaid reservation " + reservationId + " under user: " + user_name + "\n";
        }

        int totalCost = rs.getInt("price1") + Math.max(rs.getInt("price2"), 0);

        // Check if user has enough money in balance
        balanceStatement.setString(1, user_name);
        try (ResultSet balanceRs = balanceStatement.executeQuery()) {
          if (!balanceRs.next()) {
            conn.rollback();
            return "User balance check failed\n";
          }

          int balance = balanceRs.getInt("balance");
          if (balance < totalCost) {
            conn.rollback();
            return "User has only " + balance + " in account but itinerary costs " + totalCost + "\n";
          }

          // If user has enough, update their balance
          updateBalStatement.setInt(1, totalCost);
          updateBalStatement.setString(2, user_name);
          updateBalStatement.executeUpdate();

          // Update the reservation as paid
          updateReserveStatement.setInt(1, reservationId);
          updateReserveStatement.executeUpdate();

          conn.commit();
          return "Paid reservation: " + reservationId + " remaining balance: " + (balance - totalCost) + "\n";
        }
      }
    } catch (SQLException e) {
      try {
        conn.rollback();
      } catch (SQLException rollE) {
        rollE.printStackTrace();
      }

      e.printStackTrace();
      return "Failed to pay for reservation " + reservationId + "\n";

    } finally {
      checkDanglingTransaction();
    }
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_reservations() {
    // Check if user is logged in
    if (!loggedIn) {
      return "Cannot view reservations, not logged in\n";
    }

    // Resulting reservations string
    String result = "";
    boolean reservations = false;

    try {
      findReserveStatement.setString(1, user_name);
      ResultSet rs = findReserveStatement.executeQuery();

      while (rs.next()) {
        reservations = true;
        int resId = rs.getInt("res_id");
        boolean paid = rs.getInt("paid") == 1;

        // Get flight 1
        int fid1 = rs.getInt("fid1");
        int day1 = rs.getInt("day1");
        String carrier1 = rs.getString("carrier1");
        String flightNum1 = rs.getString("flight_num1");
        String origin1 = rs.getString("origin1");
        String dest1 = rs.getString("dest1");
        int time1 = rs.getInt("time1");
        int capacity1 = rs.getInt("capacity1");
        int price1 = rs.getInt("price1");

        // Create flight1
        Flight flight1 = new Flight(fid1, day1, carrier1, flightNum1, origin1, dest1, time1, capacity1, price1);

        result += "Reservation " + resId + " paid: " + paid + ":\n";
        result += flight1.toString() + "\n";

        // Check for second flight
        int fid2 = rs.getInt("fid2");
        if (fid2 != 0) {
          int day2 = rs.getInt("day2");
          String carrier2 = rs.getString("carrier2");
          String flightNum2 = rs.getString("flight_num2");
          String origin2 = rs.getString("origin2");
          String dest2 = rs.getString("dest2");
          int time2 = rs.getInt("time2");
          int capacity2 = rs.getInt("capacity2");
          int price2 = rs.getInt("price2");

          // Create flight2
          Flight flight2 = new Flight(fid2, day2, carrier2, flightNum2, origin2, dest2, time2, capacity2, price2);
          result += flight2.toString() + "\n";
        }
      }

      // No reservations found for the user
      if (!reservations) {
        return "No reservations found\n";
      }
      return result;

    } catch (SQLException e) {
      e.printStackTrace();
      return "Failed to retrieve reservations\n";
    }
  }

  /**
   * Example utility function that uses prepared statements
   */
  private int checkFlightCapacity(int fid) throws SQLException {
    flightCapacityStmt.clearParameters();
    flightCapacityStmt.setInt(1, fid);

    ResultSet results = flightCapacityStmt.executeQuery();
    results.next();
    int capacity = results.getInt("capacity");
    results.close();

    return capacity;
  }

  /**
   * Utility function to determine whether an error was caused by a deadlock
   */
  private static boolean isDeadlock(SQLException e) {
    return e.getErrorCode() == 1205;
  }

  /**
   * A class to store information about a single flight
   *
   * TODO(hctang): move this into QueryAbstract
   */
  class Flight {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String flightNum;
    public String originCity;
    public String destCity;
    public int time;
    public int capacity;
    public int price;

    Flight(int id, int day, String carrier, String fnum, String origin, String dest, int tm,
           int cap, int pri) {
      fid = id;
      dayOfMonth = day;
      carrierId = carrier;
      flightNum = fnum;
      originCity = origin;
      destCity = dest;
      time = tm;
      capacity = cap;
      price = pri;
    }
    
    @Override
    public String toString() {
      return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: "
          + flightNum + " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time
          + " Capacity: " + capacity + " Price: " + price;
    }
  }
  
  class Itinerary implements Comparable<Itinerary>{
    private Flight flight1;
    private Flight flight2;
    private List<Flight> flights;
    private int id;
    private int time;

    Itinerary(Flight f1, Flight f2){
      this.flight1 = f1;
      this.flight2 = f2;
      this.flights = new ArrayList<>();
      this.id = 0;
      this.time = 0;
    }

    // Add to flight lists
    void addToFlights(Flight flight){
      this.flights.add(flight);
      this.time += flight.time;
    }

    // Set flight ID
    void setID(int id){
      this.id = id;
    }
    
    // Compare flight times and IDs for sorting
    public int compareTo(Itinerary other){
    // Compare flight times
    if (this.time != other.time) {
      return this.time - other.time;
    }

    // Compare first flight IDs if times are equal
    if (this.flight1.fid != other.flight1.fid) {
      return this.flight1.fid - other.flight1.fid;
    }

    // Compare the second flight ID if both first IDs are equal
    int thisFid2 = Integer.MAX_VALUE;
    int otherFid2 = Integer.MAX_VALUE;
    if (this.flight2 != null) {
      thisFid2 = this.flight2.fid;
    }
    if (other.flight2 != null) {
      otherFid2 = other.flight2.fid;
    }

    return thisFid2 - otherFid2;

    }

    @Override
    public String toString(){
      String result = "";
      for(int i = 0; i < flights.size(); i++){
        Flight flight = flights.get(i);
        result += flight.toString() + "\n";
      }
      return "Itinerary " + this.id + ": " + flights.size() + " flight(s), " + this.time + " minutes\n" + result;
    }
  }

}