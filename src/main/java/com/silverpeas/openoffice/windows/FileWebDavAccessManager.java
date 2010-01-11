/**
 * Copyright (C) 2000 - 2009 Silverpeas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * As a special exception to the terms and conditions of version 3.0 of
 * the GPL, you may redistribute this Program in connection with Free/Libre
 * Open Source Software ("FLOSS") applications as described in Silverpeas's
 * FLOSS exception.  You should have recieved a copy of the text describing
 * the FLOSS exception, and it is also available here:
 * "http://repository.silverpeas.com/legal/licensing"
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.silverpeas.openoffice.windows;

import com.silverpeas.openoffice.*;
import com.silverpeas.openoffice.util.MessageUtil;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.StringTokenizer;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ProgressMonitor;
import javax.swing.ProgressMonitorInputStream;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpConnectionManager;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.client.methods.LockMethod;
import org.apache.jackrabbit.webdav.client.methods.PutMethod;
import org.apache.jackrabbit.webdav.client.methods.UnLockMethod;
import org.apache.jackrabbit.webdav.lock.Scope;
import org.apache.jackrabbit.webdav.lock.Type;

/**
 * This class manage download and upload of documents using webdav protocol.
 * 
 * @author Ludovic Bertin
 *
 */
public class FileWebDavAccessManager {

  private String userName;
  private String password;
  private String lockToken = null;
  static Logger logger = Logger.getLogger(
          FileWebDavAccessManager.class.getName());

  /**
   * The AccessManager is inited with authentication info to avoid login prompt
   *
   * @param auth	authentication info
   */
  public FileWebDavAccessManager(AuthenticationInfo auth) {
    this.userName = auth.getLogin();
    this.password = auth.getPassword();
  }

  public HttpClient initConnection(URL url) {
    HostConfiguration hostConfig = new HostConfiguration();
    hostConfig.setHost(url.getHost());
    HttpConnectionManager connectionManager =
            new MultiThreadedHttpConnectionManager();
    HttpConnectionManagerParams params = new HttpConnectionManagerParams();
    int maxHostConnections = 20;
    params.setMaxConnectionsPerHost(hostConfig, maxHostConnections);
    connectionManager.setParams(params);
    HttpClient client = new HttpClient(connectionManager);
    Credentials creds = new UsernamePasswordCredentials(userName, password);
    client.getState().setCredentials(AuthScope.ANY, creds);
    client.setHostConfiguration(hostConfig);
    return client;
  }

  /**
   * Retrieve the file from distant URL to local temp file.
   *
   * @param url	document url
   *
   * @return	full path of local temp file
   *
   * @throws HttpException
   * @throws IOException
   */
  public String retrieveFile(String url) throws HttpException, IOException,
          URISyntaxException {
    URI uri = getURI(url);
    URL formattedUrl = new URL(uri.getEscapedURI());
    HttpClient client = initConnection(formattedUrl);
    logger.log(Level.INFO, MessageUtil.getMessage("info.webdav.locking")
            + ' ' + uri.getEscapedURI());
    //Let's lock the file
    LockMethod lockMethod = new LockMethod(uri.getEscapedURI(),
            Scope.EXCLUSIVE, Type.WRITE, userName, 600000l, false);
    client.executeMethod(lockMethod);
    if (lockMethod.succeeded()) {
      lockToken = lockMethod.getLockToken();
    } else {
      throw new IOException(MessageUtil.getMessage("error.webdav.locking")
              + ' ' + lockMethod.getStatusCode() + " - "
              + lockMethod.getStatusText());
    }
    logger.log(Level.INFO, MessageUtil.getMessage("info.webdav.locked") + ' '
            + lockToken);
    GetMethod method = new GetMethod();
    method.setURI(uri);
    client.executeMethod(method);
    if (method.getStatusCode() != 200) {
      throw new IOException(MessageUtil.getMessage("error.get.remote.file")
              + ' ' + method.getStatusCode() + " - " + method.getStatusText());
    }
    String fileName = formattedUrl.getFile();
    fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
    fileName = URLDecoder.decode(fileName, "UTF-8");
    ProgressMonitorInputStream is = new ProgressMonitorInputStream(null,
            MessageUtil.getMessage("downloading.remote.file") + " " + fileName,
            new BufferedInputStream(method.getResponseBodyAsStream()));
    fileName = fileName.replace(' ', '_');
    ProgressMonitor monitor = is.getProgressMonitor();
    monitor.setMaximum(new Long(method.getResponseContentLength()).intValue());
    File tempDir = new File(System.getProperty("java.io.tmpdir"), "silver-"
            + System.currentTimeMillis());
    tempDir.mkdirs();
    File tmpFile = new File(tempDir, fileName);
    FileOutputStream fos = new FileOutputStream(tmpFile);
    byte[] data = new byte[64];
    int c = 0;
    while ((c = is.read(data)) > -1) {
      fos.write(data, 0, c);
    }
    fos.close();
    logger.log(Level.INFO, MessageUtil.getMessage("info.webdav.file.locally.saved") + ' '
            + tmpFile.getAbsolutePath());
    return tmpFile.getAbsolutePath();
  }

  /**
   * Push back file into remote location using webdav.
   *
   * @param tmpFilePath			full path of local temp file
   * @param url					remote url
   *
   * @throws HttpException
   * @throws IOException
   */
  public void pushFile(String tmpFilePath, String url) throws HttpException,
          IOException,
          MalformedURLException,
          UnsupportedEncodingException,
          URISyntaxException,
          URIException {
    /*
     * Build URL object to extract host
     */
    URI uri = getURI(url);
    HttpClient client = initConnection(new URL(uri.getEscapedURI()));

    /*
     * Checks if file still exists
     */
    GetMethod method = new GetMethod();
    method.setURI(uri);
    client.executeMethod(method);
    if (method.getStatusCode() == 200) {
      PutMethod putMethod = new PutMethod(uri.getEscapedURI());
      logger.log(Level.INFO, MessageUtil.getMessage("info.webdav.put")
              + ' ' + tmpFilePath);
      File file = new File(tmpFilePath);
      ProgressMonitorInputStream is = new ProgressMonitorInputStream(null,
              MessageUtil.getMessage("uploading.remote.file") + " " + file.getName(),
              new BufferedInputStream(new FileInputStream(file)));
      ProgressMonitor monitor = is.getProgressMonitor();
      monitor.setMaximum(new Long(file.length()).intValue());
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] data = new byte[64];
      int c = 0;
      while ((c = is.read(data)) > -1) {
        baos.write(data, 0, c);
      }
      RequestEntity requestEntity = new ByteArrayRequestEntity(baos.toByteArray());
      putMethod.setRequestEntity(requestEntity);
      putMethod.setRequestHeader(PutMethod.HEADER_LOCK_TOKEN, lockToken);
      client.executeMethod(putMethod);
      if (putMethod.succeeded()) {
        logger.log(Level.INFO, MessageUtil.getMessage("info.file.updated"));
        logger.log(Level.INFO, MessageUtil.getMessage("info.webdav.unlocking")
                + ' ' + uri.getEscapedURI());
        //Let's unlock the file
        UnLockMethod unlockMethod = new UnLockMethod(uri.getEscapedURI(), lockToken);
        client.executeMethod(unlockMethod);
        if (unlockMethod.getStatusCode() != 200 && unlockMethod.getStatusCode() != 204) {
          logger.log(Level.INFO, MessageUtil.getMessage("error.webdav.unlocking") + ' '
                  + unlockMethod.getStatusCode());
        }
        try {
          unlockMethod.checkSuccess();
          logger.log(Level.INFO, MessageUtil.getMessage("info.webdav.unlocked"));
        } catch (DavException ex) {
          logger.log(Level.SEVERE,
                  MessageUtil.getMessage("error.webdav.unlocking"), ex);
          throw new IOException(MessageUtil.getMessage("error.webdav.unlocking"),
                  ex);
        }
        // delete temp file
        file.delete();
        file.getParentFile().delete();
        logger.log(Level.INFO, MessageUtil.getMessage("info.file.deleted"));
        logger.log(Level.INFO, MessageUtil.getMessage("info.ok"));
      } else {
        throw new IOException(MessageUtil.getMessage("error.put.remote.file")
                + " - " + putMethod.getStatusCode() + " - "
                + putMethod.getStatusText());
      }
    } else {
      logger.log(Level.SEVERE, MessageUtil.getMessage("error.remote.file"));
      throw new IOException(MessageUtil.getMessage("error.remote.file"));
    }
  }

  public static String encodeUrl(String url) throws UnsupportedEncodingException {
    int count = 0;
    String urlToBeEncoded = url.replaceAll("%20", " ");
    StringTokenizer tokenizer = new StringTokenizer(urlToBeEncoded, "/", true);
    StringBuilder buffer = new StringBuilder();
    while (tokenizer.hasMoreTokens()) {
      String token = tokenizer.nextToken();
      if (count < 4 || "/".equals(token)) {
        buffer.append(token);
      } else {
        buffer.append(URLEncoder.encode(token, "UTF-8"));
      }
      count++;
    }
    String resultingUrl = buffer.toString();
    return resultingUrl.replace('+', ' ').replaceAll(" ", "%20");
  }

  private static URI getURI(String url) throws URIException {
    return new URI(url.replaceAll("%20", " "), false, "UTF-8");
  }
}
