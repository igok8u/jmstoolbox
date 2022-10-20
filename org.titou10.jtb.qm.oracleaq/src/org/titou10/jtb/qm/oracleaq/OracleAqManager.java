/*
 * Copyright (C) 2015 Denis Forveille titou10.titou10@gmail.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.titou10.jtb.qm.oracleaq;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.titou10.jtb.config.gen.SessionDef;
import org.titou10.jtb.jms.qm.DestinationData;
import org.titou10.jtb.jms.qm.JMSPropertyKind;
import org.titou10.jtb.jms.qm.QManager;
import org.titou10.jtb.jms.qm.QManagerProperty;
import org.titou10.jtb.jms.qm.QueueData;
import org.titou10.jtb.jms.qm.TopicData;

import oracle.jdbc.driver.OracleDriver;
import oracle.jms.AQjmsFactory;

/**
 * 
 * Implements Oracle Advanced Queuing (AQ) Q Provider
 * 
 * @author Denis Forveille
 * @author Ihar Kuzniatsou
 *
 */
public class OracleAqManager extends QManager {
   private static final Logger                     log               = LoggerFactory.getLogger(OracleAqManager.class);

   // private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss:SSS");
   private static final String                     CR                = "\n";

   private final static String                     P_SID             = "sid";
   private final static String                     P_DRIVER_TYPE     = "driverType";

   private static final String                     URL_OCI           = "jdbc:oracle:%s:@(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=%s)(PORT=%s)(CONNECT_DATA=(SID=%s))))";
   private static final String                     URL_THIN          = "jdbc:oracle:thin:@%s:%s:%s";

   private static final String                     C_NAME            = "NAME";
   private static final String                     C_RECIPIENTS      = "RECIPIENTS";
   private static final String                     C_QUEUE_TYPE      = "QUEUE_TYPE";
   private static final String                     V_QUEUE_MARKER    = "SINGLE";
   private static final String                     V_EXCEPTION_QUEUE = "EXCEPTION_QUEUE";

   private static final String                     QUERY_GET_DEST    = "select qu.name" +
                                                                       "      ,qu.recipients" +
                                                                       "      ,qu.queue_type" +
                                                                       "  from user_queues qu" +
                                                                       "      ,user_queue_tables qt" +
                                                                       " where qt.queue_table = qu.queue_table" +
                                                                       "   and qt.object_type like 'SYS.AQ$_JMS%'";

   private static final String                     HELP_TEXT;

   private List<QManagerProperty>                  parameters        = new ArrayList<>();

   private final Map<Integer, java.sql.Connection> jdbcConnections   = new HashMap<>();

   // -----------
   // Constructor
   // -----------

   public OracleAqManager() {
      log.debug("Instantiate OracleAQ");

      parameters.add(new QManagerProperty(P_SID, true, JMSPropertyKind.STRING, false, "SID", "ORCLCDB"));
      parameters
               .add(new QManagerProperty(P_DRIVER_TYPE, true, JMSPropertyKind.STRING, false, "driver (thin, kprb, oci8 )", "thin"));
   }

   // ------------------
   // Business Interface
   // ------------------

   @Override
   public Connection connect(SessionDef sessionDef, boolean showSystemObjects, String clientID) throws Exception {
      log.info("connecting to {} - {}", sessionDef.getName(), clientID);

      var mapProperties = extractProperties(sessionDef);

      var sid = mapProperties.get(P_SID);
      var driverType = mapProperties.get(P_DRIVER_TYPE);

      var url = "";
      switch (driverType) {
         case "oci":
         case "oci8":
            url = String.format(URL_OCI, driverType, sessionDef.getHost(), sessionDef.getPort(), sid);
            break;
         case "thin":
            url = String.format(URL_THIN, sessionDef.getHost(), sessionDef.getPort(), sid);
            break;
         default:
            throw new Exception("Unsupported driver type: " + driverType);
      }

      log.debug("url: {}", url);

      // Load DB Driver
      DriverManager.registerDriver(new OracleDriver());

      // ------------------
      // Get JMS Connection
      // ------------------
      final ConnectionFactory connectionFactory = AQjmsFactory.getConnectionFactory(url, null);
      Connection jmsConnection = connectionFactory.createConnection(sessionDef.getActiveUserid(), sessionDef.getActivePassword());
      jmsConnection.start();

      log.info("connected to {} - {}", sessionDef.getName(), jmsConnection.getClientID());

      // Store per connection related data
      jdbcConnections.put(jmsConnection.hashCode(),
                          DriverManager.getConnection(url, sessionDef.getActiveUserid(), sessionDef.getActivePassword()));

      return jmsConnection;
   }

   @Override
   public DestinationData discoverDestinations(Connection jmsConnection, boolean showSystemObjects) throws SQLException {
      log.debug("discoverDestinations : {} - {}", jmsConnection, showSystemObjects);

      var hash = jmsConnection.hashCode();
      var jdbcConnection = jdbcConnections.get(hash);

      SortedSet<QueueData> queues = new TreeSet<>();
      SortedSet<TopicData> topics = new TreeSet<>();
      var name = "";
      var recipients = "";
      var queueType = "";

      // Read database to get Destinations
      PreparedStatement preparedStatement = jdbcConnection.prepareStatement(QUERY_GET_DEST);
      ResultSet resultSet = preparedStatement.executeQuery();
      while (resultSet.next()) {
         name = resultSet.getString(C_NAME);
         recipients = resultSet.getString(C_RECIPIENTS);
         queueType = resultSet.getString(C_QUEUE_TYPE);

         if (!showSystemObjects && queueType.equals(V_EXCEPTION_QUEUE)) {
            continue;
         }

         if (V_QUEUE_MARKER.equals(recipients)) {
            log.debug("Found Queue '{}'.", name);
            queues.add(new QueueData(name));
         } else {
            log.debug("Found Topic '{}'.", name);
            topics.add(new TopicData(name));
         }

      }
      preparedStatement.close();

      return new DestinationData(queues, topics);
   }

   @Override
   public void close(Connection jmsConnection) throws JMSException {
      Integer hash = jmsConnection.hashCode();
      var jdbcConnection = jdbcConnections.get(hash);

      try {
         jmsConnection.close();
      } catch (JMSException e) {
         log.warn("Exception occured while closing JMS connection. Ignore it. Msg={}", e.getMessage());
      }

      try {
         jdbcConnection.close();
      } catch (SQLException e) {
         log.warn("Exception occured while closing JDBC connection. Ignore it. Msg={}", e.getMessage());
      }

      jdbcConnections.remove(hash);
   }

   @Override
   public List<QManagerProperty> getQManagerProperties() {
      return parameters;
   }

   @Override
   public String getHelpText() {
      return HELP_TEXT;
   }

   static {
      var sb = new StringBuilder(2048);
      sb.append("Extra JARS :").append(CR);
      sb.append("------------").append(CR);
      sb.append("No extra jar is needed as JMSToolBox is bundled with the latest Apache ActiveMQ jars").append(CR);
      sb.append(CR);
      sb.append("Connection:").append(CR);
      sb.append("-----------").append(CR);
      sb.append("Host          : Oracle AQ server host name").append(CR);
      sb.append("Port          : Oracle AQ server port (ie 1521)").append(CR);
      sb.append("User/Password : User allowed to connect to Oracle AQ").append(CR);
      sb.append(CR);
      sb.append("Properties values:").append(CR);
      sb.append("------------------").append(CR);
      sb.append("sid                : sid.").append(CR);
      sb.append("driverType         : 'thin', 'oci' , 'oci8'").append(CR);

      HELP_TEXT = sb.toString();
   }
}
