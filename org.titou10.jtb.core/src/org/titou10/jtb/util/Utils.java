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
package org.titou10.jtb.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Queue;
import javax.jms.TextMessage;
import javax.jms.Topic;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.URIUtil;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuItem;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TableColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.titou10.jtb.jms.model.JTBMessageTemplate;
import org.titou10.jtb.jms.util.JTBDeliveryMode;
import org.titou10.jtb.ui.navigator.NodeAbstract;
import org.titou10.jtb.ui.navigator.NodeFolder;

/**
 * Class that holds various utility methods
 *
 * @author Denis Forveille
 *
 */
public final class Utils {

   private static final Logger  log                       = LoggerFactory.getLogger(Utils.class);

   private static final int     EXT_LENGTH                = Constants.JTB_TEMPLATE_FILE_EXTENSION.length();

   private static final String  TMP_DIR                   = System.getProperty("java.io.tmpdir");
   private static final boolean IS_WINDOWS                = Platform.getOS().startsWith("win");

   private static final Long    LONG_ZERO                 = 0L;

   private static final String  DEFAULT_CONTINUATION_MARK = "�";

   // ---------------------------
   // IFilestore Utils
   // ---------------------------

   public static String getNameWithoutExt(String templateName) {
      if (templateName == null) {
         return null;
      }
      if (templateName.endsWith(Constants.JTB_TEMPLATE_FILE_EXTENSION)) {
         return templateName.substring(0, templateName.length() - EXT_LENGTH);
      } else {
         return templateName;
      }
   }

   public static IFileStore getFileStoreFromFilename(String fileName) {
      try {
         return EFS.getStore(URIUtil.toURI(fileName));
      } catch (CoreException e) {
         // DF Should never occur..
         log.error("exception occurred when getting fileStore " + fileName, e);
         return null;
      }
   }

   public static boolean isFileStoreGrandChildOfParent(IFileStore parentFileStore, IFileStore childFileStore) {
      if ((parentFileStore == null) || (childFileStore == null)) {
         return false;
      }
      if (parentFileStore.equals(childFileStore)) {
         return true;
      }
      IFileStore x = childFileStore;
      while (x.getParent() != null) {
         if (x.equals(parentFileStore)) {
            return true;
         }
         x = x.getParent();
      }
      return false;
   }

   // ---------------------------
   // JMS Message Utility
   // ---------------------------
   public static String getDestinationName(Destination destination) throws JMSException {
      if (destination == null) {
         return null;
      }
      if (destination instanceof Queue) {
         return ((Queue) destination).getQueueName();
      }
      if (destination instanceof Topic) {
         return ((Topic) destination).getTopicName();
      }
      return null;
   }

   private static final String JMS_TS_SPACER        = " [";
   private static final String JMS_TS_SPACER_REGEXP = Pattern.quote(JMS_TS_SPACER);
   private static final String JMS_TS               = "%tY-%<tm-%<td %<tH:%<tM:%<tS.%<tL";
   private static final String JMS_TS_WITH_LONG     = "%s" + JMS_TS_SPACER + JMS_TS + "]";

   public static String formatTimestamp(long ts, boolean withLong) {
      if (ts == 0) {
         return "";
      }
      Date d = new Date(ts);
      return withLong ? String.format(JMS_TS_WITH_LONG, ts, d) : String.format(JMS_TS, ts, d);
   }

   public static Long extractLongFromTimestamp(Object o) {
      if (o == null) {
         return LONG_ZERO;
      }
      if ((o instanceof String) && (Utils.isNotEmpty((String) o))) {
         // Extract the long value
         String[] s = ((String) o).split(JMS_TS_SPACER_REGEXP);
         if (s.length > 0) {
            return Long.parseLong(s[0]);
         }
      }
      return LONG_ZERO;
   }

   private static final String  JMS_DELIVERY_MODE               = "%s (%d)";
   private static final Pattern JMS_DELIVERY_MODE_SPACER_REGEXP = Pattern.compile("\\(([0-9]+)\\)");

   public static String formatJTBDeliveryMode(JTBDeliveryMode jmsDeliveryMode) {
      if (jmsDeliveryMode == null) {
         return "";
      }
      return String.format(JMS_DELIVERY_MODE, jmsDeliveryMode.name(), jmsDeliveryMode.intValue());
   }

   public static Integer extractJTBDeliveryMode(Object o) {
      // Extract the int value
      Matcher m = JMS_DELIVERY_MODE_SPACER_REGEXP.matcher((String) o);
      m.find();
      return Integer.parseInt(m.group(1));

   }

   // ---------------------------
   // Validate JMS Property Names
   // ---------------------------

   // Doc: https://jakarta.ee/specifications/messaging/2.0/apidocs/

   public static boolean isValidJMSPropertyName(String s, List<String> metaJMSPropertyNames) {
      if (isEmpty(s)) {
         return false;
      }

      if (!Character.isJavaIdentifierStart(s.charAt(0))) {
         return false;
      }
      for (int i = 1; i < s.length(); i++) {
         if (!Character.isJavaIdentifierPart(s.charAt(i))) {
            return false;
         }
      }

      // JMSX* properties are only OK if they are in the list of the extra properties for the Queue Manager
      if (s.startsWith("JMSX")) {
         if (metaJMSPropertyNames == null) {
            return true;
         }

         return metaJMSPropertyNames.stream().anyMatch(propertyName -> propertyName.equals(s));
      }

      // The JMS API reserves the JMS_<vendor>_<name> property name prefix for provider-specific properties.
      if ((s.startsWith("JMS") && (!s.startsWith("JMS_")))) {
         return false;

      }
      return true;
   }

   // ----------------------------
   // Enable/Disable Menu safe way
   // ----------------------------
   public static boolean enableMenu(MMenuItem menuItem) {
      if (menuItem != null) {
         menuItem.setVisible(true);
      }
      return true;

   }

   public static boolean disableMenu(MMenuItem menuItem) {
      if (menuItem != null) {
         menuItem.setVisible(false);
      }
      return false;
   }

   // Check if the Node in Session Browser is the "Queue" Folder
   public static boolean isQueueFolder(NodeAbstract nodeAbstract) {
      if (nodeAbstract == null) {
         return false;
      }

      if (nodeAbstract instanceof NodeFolder) {
         if (nodeAbstract.getName().equals(Constants.NODE_FOLDER_QUEUES_NAME)) {
            return true;
         }
      }
      return false;
   }

   // ---------------------------
   // Save/Read Payload
   // ---------------------------

   public static String exportPayloadToOS(Shell shell,
                                          JTBMessageTemplate jtbMessageTemplate,
                                          String payloadText,
                                          byte[] payloadBytes,
                                          Map<String, Object> payloadMap) throws IOException, JMSException {

      switch (jtbMessageTemplate.getJtbMessageType()) {
         case TEXT:
            return writePayloadToOS((TextMessage) null, shell, payloadText, jtbMessageTemplate);

         case BYTES:
            return writePayloadToOS((BytesMessage) null, shell, payloadBytes, jtbMessageTemplate);

         case MAP:
            return writePayloadToOS((MapMessage) null, shell, payloadMap, jtbMessageTemplate);

         default:
            return null;
      }
   }

   // Drag & Drop + "Export Payload" to OS
   public static String writePayloadToOS(TextMessage textMessage) throws IOException, JMSException {
      return writePayloadToOS(textMessage, null, null, null);
   }

   public static String writePayloadToOS(TextMessage textMessage, Shell shell) throws IOException, JMSException {
      return writePayloadToOS(textMessage, shell, null, null);
   }

   private static String writePayloadToOS(TextMessage textMessage,
                                          Shell shell,
                                          String payloadText,
                                          JTBMessageTemplate jtbMessageTemplate) throws IOException, JMSException {

      String suggestedFileName;
      if (jtbMessageTemplate == null) {
         suggestedFileName = buildFileName("payload", ".txt", textMessage.getJMSCorrelationID(), textMessage.getJMSMessageID());
      } else {
         suggestedFileName = buildFileName("payload",
                                           ".txt",
                                           jtbMessageTemplate.getJmsCorrelationID(),
                                           jtbMessageTemplate.getJmsMessageID());
      }
      log.debug("fileName={}", suggestedFileName);

      String p;
      if (payloadText == null) {
         p = textMessage.getText();
      } else {
         p = payloadText;
      }

      byte[] b;
      if (p != null) {
         b = p.getBytes();
      } else {
         b = new byte[0];
      }

      if (shell == null) {
         return createAndWriteTempFile(suggestedFileName, b, null);
      }

      FileDialog dlg = openFileDialog(shell, SWT.SAVE, suggestedFileName);
      String fn = dlg.open();
      if (fn != null) {
         StringBuffer sb2 = new StringBuffer(256);
         sb2.append(dlg.getFilterPath());
         sb2.append(File.separator);
         sb2.append(dlg.getFileName());
         String choosenFileName = sb2.toString();
         log.debug("choosenFileName={}", choosenFileName);

         Files.write(Paths.get(choosenFileName), b);

         return choosenFileName;
      }
      return null;
   }

   // Drag & Drop + "Export Payload" to OS
   public static String writePayloadToOS(BytesMessage bytesMessage) throws IOException, JMSException {
      return writePayloadToOS(bytesMessage, null, null, null);
   }

   public static String writePayloadToOS(BytesMessage bytesMessage, Shell shell) throws IOException, JMSException {
      return writePayloadToOS(bytesMessage, shell, null, null);
   }

   private static String writePayloadToOS(BytesMessage bytesMessage,
                                          Shell shell,
                                          byte[] payloadBytes,
                                          JTBMessageTemplate jtbMessageTemplate) throws IOException, JMSException {

      String suggestedFileName;
      if (jtbMessageTemplate == null) {
         suggestedFileName = buildFileName("payload", ".bin", bytesMessage.getJMSCorrelationID(), bytesMessage.getJMSMessageID());
      } else {
         suggestedFileName = buildFileName("payload",
                                           ".bin",
                                           jtbMessageTemplate.getJmsCorrelationID(),
                                           jtbMessageTemplate.getJmsMessageID());
      }

      log.debug("fileName={}", suggestedFileName);

      byte[] b = payloadBytes;
      if (payloadBytes == null) {
         b = new byte[(int) bytesMessage.getBodyLength()];
         bytesMessage.reset();
         bytesMessage.readBytes(b);
      }

      if (shell == null) {
         return createAndWriteTempFile(suggestedFileName, b, null);
      }

      FileDialog dlg = openFileDialog(shell, SWT.SAVE, suggestedFileName);
      String fn = dlg.open();
      if (fn != null) {
         StringBuffer sb2 = new StringBuffer(256);
         sb2.append(dlg.getFilterPath());
         sb2.append(File.separator);
         sb2.append(dlg.getFileName());
         String choosenFileName = sb2.toString();

         if ((b != null) && (b.length > 0)) {
            Files.write(Paths.get(choosenFileName), b);
         }
         return choosenFileName;
      }

      return null;
   }

   // Drag & Drop + "Export Payload" to OS

   public static String writePayloadToOS(MapMessage mapMessage) throws IOException, JMSException {
      return writePayloadToOS(mapMessage, null, null, null);
   }

   public static String writePayloadToOS(MapMessage mapMessage, Shell shell) throws IOException, JMSException {
      return writePayloadToOS(mapMessage, shell, null, null);
   }

   @SuppressWarnings("rawtypes")
   private static String writePayloadToOS(MapMessage mapMessage,
                                          Shell shell,
                                          Map<String, Object> payloadMap,
                                          JTBMessageTemplate jtbMessageTemplate) throws IOException, JMSException {

      String suggestedFileName;
      if (jtbMessageTemplate == null) {
         suggestedFileName = buildFileName("payload", ".txt", mapMessage.getJMSCorrelationID(), mapMessage.getJMSMessageID());
      } else {
         suggestedFileName = buildFileName("payload",
                                           ".txt",
                                           jtbMessageTemplate.getJmsCorrelationID(),
                                           jtbMessageTemplate.getJmsMessageID());
      }
      log.debug("fileName={}", suggestedFileName);

      List<String> lines = new ArrayList<>();
      if (payloadMap != null) {
         for (Entry<String, Object> e : payloadMap.entrySet()) {
            lines.add(e.getKey() + "=" + e.getValue());
         }
      } else {
         Enumeration mapNames = mapMessage.getMapNames();
         while (mapNames.hasMoreElements()) {
            String key = (String) mapNames.nextElement();
            lines.add(key + "=" + mapMessage.getObject(key));
         }
      }

      if (shell == null) {
         return createAndWriteTempFile(suggestedFileName, null, lines);
      }

      FileDialog dlg = openFileDialog(shell, SWT.SAVE, suggestedFileName);
      String fn = dlg.open();
      if (fn != null) {
         StringBuffer sb2 = new StringBuffer(256);
         sb2.append(dlg.getFilterPath());
         sb2.append(File.separator);
         sb2.append(dlg.getFileName());
         String choosenFileName = sb2.toString();
         log.debug("fileName={}", suggestedFileName);
         Files.write(Paths.get(choosenFileName), lines);

         return choosenFileName;
      }

      return null;
   }

   public static String buildFileName(String baseName, String extension, String correlationID, String messageID) {
      // Build save file name..
      StringBuilder sb = new StringBuilder(256);
      sb.append(baseName);
      sb.append("_");
      if (messageID != null) {
         sb.append(messageID);
      } else {
         if ((correlationID != null) && (correlationID.trim().length() > 0)) {
            sb.append(correlationID);
         } else {
            sb.append("msg");
         }
      }
      sb.append(extension);
      return sb.toString().replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
   }

   public static String createAndWriteTempFile(String fileName, byte[] b, List<String> lines) throws IOException {

      File temp = new File(TMP_DIR + File.separator + fileName);
      if (temp.exists()) {
         temp.delete();
      }
      temp.deleteOnExit();

      if (lines == null) {
         Files.write(temp.toPath(), b);
      } else {
         Files.write(temp.toPath(), lines);
      }

      return temp.getCanonicalPath();
   }

   public static byte[] readFileBytes(Shell shell) throws IOException {
      FileDialog fileDialog = new FileDialog(shell);
      fileDialog.setText("Select File");
      String selected = fileDialog.open();
      if (selected == null) {
         return null;
      }

      // Read File into byte[]
      return Files.readAllBytes(Paths.get(selected));
   }

   private static FileDialog openFileDialog(Shell shell, int mode, String suggestedFileName) {
      FileDialog dlg = new FileDialog(shell, mode);
      dlg.setText("Save payload as...");
      dlg.setFileName(suggestedFileName);
      dlg.setOverwrite(true);
      return dlg;
   }

   // ------------------
   // TableViwer Helpers
   // ------------------

   // Resize the tableviewer except the Nth column
   public static void resizeTableViewer(TableViewer tv, int colToExclude) {
      int i = 1;
      for (TableColumn tc : tv.getTable().getColumns()) {
         if (i == colToExclude) {
            continue;
         }
         i++;
         tc.pack();
      }
   }

   public static void resizeTableViewerAll(TableViewer tv) {
      for (TableColumn tc : tv.getTable().getColumns()) {
         tc.pack();
      }
   }

   // Resize the tableviewer except the last column
   public static void resizeTableViewer(TableViewer tv) {
      resizeTableViewer(tv, tv.getTable().getColumns().length);
   }

   // ------------------
   // Charsets
   // ------------------

   public static String[] getCharsets() {
      Set<String> setCharset = new LinkedHashSet<>();
      setCharset.add(getDefaultCharset());
      setCharset.addAll(Charset.availableCharsets().keySet());
      return setCharset.toArray(new String[setCharset.size()]);
   }

   public static int getIndexOfCharset(String[] charsets, String charset) {
      return Arrays.asList(charsets).indexOf(charset);
   }

   public static String getDefaultCharset() {
      return Constants.CHARSET_DEFAULT + Charset.defaultCharset() + ")";
   }

   // ------------------
   // Various
   // ------------------

   public static boolean isWindows() {
      return IS_WINDOWS;
   }

   public static Throwable getCause(Throwable e) {
      Throwable cause = null;
      Throwable result = e;

      while ((null != (cause = result.getCause())) && (result != cause)) {
         result = cause;
      }
      return result;
   }

   public static boolean isTrue(final Boolean b) {
      if (b == null) {
         return false;
      }
      return b;
   }

   public static boolean isFalse(final Boolean b) {
      return !isTrue(b);
   }

   public static boolean isEmpty(final Object o) {
      return (o == null) || (o.toString().trim().length() == 0);
   }

   public static boolean isNotEmpty(final Object o) {
      return !isEmpty(o);
   }

   public static boolean isEmpty(final String s) {
      return (s == null) || (s.trim().length() == 0);
   }

   public static boolean isNotEmpty(final String s) {
      return !isEmpty(s);
   }

   public static boolean isEmpty(final byte[] b) {
      return (b == null) || (b.length == 0);
   }

   public static boolean isNotEmpty(final byte[] b) {
      return !isEmpty(b);
   }

   public static boolean isEmpty(final List<?> l) {
      return (l == null) || l.isEmpty();
   }

   public static boolean isNotEmpty(final List<?> l) {
      return !isEmpty(l);
   }

   public static boolean isEmpty(final Map<?, ?> m) {
      return (m == null) || m.isEmpty();
   }

   public static boolean isNotEmpty(final Map<?, ?> m) {
      return !isEmpty(m);
   }

   public static boolean isNullorEmpty(final Collection<?> c) {
      return (c == null) || c.isEmpty();
   }

   public static boolean containsOneElement(final Collection<?> c) {
      if (c == null) {
         return false;
      }
      return c.size() == 1;
   }

   public static boolean nullOrMoreThanOne(final Collection<?> c) {
      if (c == null) {
         return true;
      }
      return c.size() != 1;
   }

   public static String stringShortener(String string, int max) {
      return (string.length() <= max) ? string : string.substring(0, max - 1) + DEFAULT_CONTINUATION_MARK;
   }

   // ------------------
   // Pure Utility Class
   // ------------------
   private Utils() {
      // NOP
   }

}
