/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.tez.dag.api.client;

import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.client.urlconnection.HttpURLConnectionFactory;
import com.sun.jersey.client.urlconnection.URLConnectionClientHandler;
import com.sun.jersey.json.impl.provider.entity.JSONRootElementProvider;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.hadoop.security.authentication.client.ConnectionConfigurator;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticatedURL;
import org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticator;
import org.apache.hadoop.security.token.delegation.web.KerberosDelegationTokenAuthenticator;
import org.apache.hadoop.security.token.delegation.web.PseudoDelegationTokenAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.tez.client.FrameworkClient;
import org.apache.tez.common.ATSConstants;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.records.DAGProtos.DAGStatusProto;
import org.apache.tez.dag.api.records.DAGProtos.DAGStatusStateProto;
import org.apache.tez.dag.api.records.DAGProtos.ProgressProto;
import org.apache.tez.dag.api.records.DAGProtos.StringProgressPairProto;
import org.apache.tez.dag.api.records.DAGProtos.TezCounterGroupProto;
import org.apache.tez.dag.api.records.DAGProtos.TezCounterProto;
import org.apache.tez.dag.api.records.DAGProtos.TezCountersProto;
import org.apache.tez.dag.api.records.DAGProtos.VertexStatusProto;
import org.apache.tez.dag.api.records.DAGProtos.VertexStatusStateProto;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;


@Private
public class DAGClientTimelineImpl extends DAGClient {
  private static final Logger LOG = LoggerFactory.getLogger(DAGClientTimelineImpl.class);

  private static final String FILTER_BY_FIELDS = "primaryfilters,otherinfo";
  private static final String HTTPS_SCHEME = "https://";
  private static final String HTTP_SCHEME = "http://";
  private static Client httpClient = null;
  private final ApplicationId appId;
  private final String dagId;
  private final FrameworkClient frameworkClient;
  private final UserGroupInformation authUgi;
  private final String doAsUser;
  private final DelegationTokenAuthenticator authenticator;
  private final DelegationTokenAuthenticatedURL.Token token;
  private final ConnectionConfigurator connConfigurator;
  private final static int DEFAULT_SOCKET_TIMEOUT = 30 * 1000; // 30 seconds

  private Map<String, VertexTaskStats> vertexTaskStatsCache = null;

  @VisibleForTesting
  protected String baseUri;

  public DAGClientTimelineImpl(ApplicationId appId, String dagId, TezConfiguration conf,
                               FrameworkClient frameworkClient)
      throws TezException, IOException {
    this.appId = appId;
    this.dagId = dagId;
    this.frameworkClient = frameworkClient;

    String scheme;
    String webAppAddress;
    if (webappHttpsOnly(conf)) {
      scheme = HTTPS_SCHEME;
      webAppAddress = conf.get(ATSConstants.TIMELINE_SERVICE_WEBAPP_HTTPS_ADDRESS_CONF_NAME);
    } else {
      scheme = HTTP_SCHEME;
      webAppAddress = conf.get(ATSConstants.TIMELINE_SERVICE_WEBAPP_HTTP_ADDRESS_CONF_NAME);
    }
    if (webAppAddress == null) {
      throw new TezException("Failed to get ATS webapp address");
    }

    baseUri = Joiner.on("").join(scheme, webAppAddress, ATSConstants.RESOURCE_URI_BASE);

    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    UserGroupInformation realUgi = ugi.getRealUser();
    if (realUgi != null) {
      authUgi = realUgi;
      doAsUser = ugi.getShortUserName();
    } else {
      authUgi = ugi;
      doAsUser = null;
    }


    if (UserGroupInformation.isSecurityEnabled()) {
      authenticator = new KerberosDelegationTokenAuthenticator();
    } else {
      authenticator = new PseudoDelegationTokenAuthenticator();
    }

    connConfigurator = newConnConfigurator(conf);
    authenticator.setConnectionConfigurator(connConfigurator);
    token = new DelegationTokenAuthenticatedURL.Token();
  }

  @Override
  public String getExecutionContext() {
    return "Executing on YARN cluster with App id " + appId;
  }

  @Override
  protected ApplicationReport getApplicationReportInternal() {
    ApplicationReport appReport = null;
    try {
      appReport = frameworkClient.getApplicationReport(appId);
    } catch (YarnException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("error getting application report", e);
      }
    } catch (IOException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("error getting application report", e);
      }
    }
    return appReport;
  }

  @Override
  public DAGStatus getDAGStatus(@Nullable Set<StatusGetOpts> statusOptions)
      throws IOException, TezException {
    final String url = String.format("%s/%s/%s?fields=%s", baseUri, ATSConstants.TEZ_DAG_ID, dagId,
        FILTER_BY_FIELDS);
    try {
      DAGStatusProto.Builder statusBuilder;
      final JSONObject jsonRoot = getJsonRootEntity(url);

      statusBuilder = parseDagStatus(jsonRoot, statusOptions);
      if (statusBuilder == null) {
        throw new TezException("Failed to get DagStatus from ATS");
      }

      return new DAGStatus(statusBuilder, DagStatusSource.TIMELINE);
    } catch (JSONException je) {
      throw new TezException("Failed to parse DagStatus json from YARN Timeline", je);
    }
  }

  @Override
  public VertexStatus getVertexStatus(String vertexName, Set<StatusGetOpts> statusOptions)
      throws IOException, TezException {
    final String url = String.format(
        "%s/%s?primaryFilter=%s:%s&secondaryFilter=vertexName:%s&fields=%s", baseUri,
        ATSConstants.TEZ_VERTEX_ID, ATSConstants.TEZ_DAG_ID, dagId, vertexName, FILTER_BY_FIELDS);

    try {
      VertexStatusProto.Builder statusBuilder;
      final JSONObject jsonRoot = getJsonRootEntity(url);
      JSONArray entitiesNode = jsonRoot.optJSONArray(ATSConstants.ENTITIES);
      if (entitiesNode == null || entitiesNode.length() != 1) {
        throw new TezException("Failed to get vertex status YARN Timeline");
      }
      JSONObject vertexNode = entitiesNode.getJSONObject(0);

      statusBuilder = parseVertexStatus(vertexNode, statusOptions);
      if (statusBuilder == null) {
        throw new TezException("Failed to parse vertex status from YARN Timeline");
      }

      return new VertexStatus(statusBuilder);
    } catch (JSONException je) {
      throw new TezException("Failed to parse VertexStatus json from YARN Timeline", je);
    }
  }

  @Override
  public void tryKillDAG() throws IOException, TezException {
    throw new TezException("tryKillDAG is unsupported for DAGClientTimelineImpl");
  }

  @Override
  public DAGStatus waitForCompletion() throws IOException, TezException, InterruptedException {
    return getDAGStatus(null);
  }

  @Override
  public DAGStatus waitForCompletionWithStatusUpdates(
      @Nullable Set<StatusGetOpts> statusGetOpts) throws IOException, TezException,
      InterruptedException {
    return getDAGStatus(statusGetOpts);
  }

 @Override
  public void close() throws IOException {
    if (httpClient != null) {
      httpClient.destroy();
      httpClient = null;
    }
  }

  private DAGStatusProto.Builder parseDagStatus(JSONObject jsonRoot, Set<StatusGetOpts> statusOptions)
      throws JSONException, TezException {
    final JSONObject otherInfoNode = jsonRoot.getJSONObject(ATSConstants.OTHER_INFO);

    DAGStatusProto.Builder dagStatusBuilder = DAGStatusProto.newBuilder();

    final String status = otherInfoNode.optString(ATSConstants.STATUS);
    final String diagnostics = otherInfoNode.optString(ATSConstants.DIAGNOSTICS);
    if (status.equals("")) {
      return null;
    }

    dagStatusBuilder.setState(dagStateProtoMap.get(status))
        .addAllDiagnostics(Collections.singleton(diagnostics));

    if (statusOptions != null && statusOptions.contains(StatusGetOpts.GET_COUNTERS)) {
      final TezCountersProto.Builder tezCounterBuilder;
      final JSONObject countersNode = otherInfoNode.optJSONObject(ATSConstants.COUNTERS);
      tezCounterBuilder = parseDagCounters(countersNode);
      if (tezCounterBuilder != null) {
        dagStatusBuilder.setDagCounters(tezCounterBuilder);
      }
    }

    final Map<String, VertexTaskStats> vertexTaskStatsMap = parseTaskStatsForVertexes();
    if (vertexTaskStatsMap.size() > 0) {
      ProgressProto.Builder dagProgressBuilder = getProgressBuilder(vertexTaskStatsMap, null);
      dagStatusBuilder.setDAGProgress(dagProgressBuilder);

      List<StringProgressPairProto> vertexProgressBuilder =
          new ArrayList<StringProgressPairProto>(vertexTaskStatsMap.size());
      for (Map.Entry<String, VertexTaskStats> v : vertexTaskStatsMap.entrySet()) {
        StringProgressPairProto vertexProgressProto = StringProgressPairProto
            .newBuilder()
            .setKey(v.getKey())
            .setProgress(getProgressBuilder(vertexTaskStatsMap, v.getKey()))
            .build();
        vertexProgressBuilder.add(vertexProgressProto);
      }
      dagStatusBuilder.addAllVertexProgress(vertexProgressBuilder);
    }

    return dagStatusBuilder;
  }

  private ProgressProto.Builder getProgressBuilder(Map<String, VertexTaskStats> vertexTaskStatsMap,
                                                   String vertexName) {
    int failedTaskCount = 0;
    int killedTaskCount = 0;
    int runningTaskCount = 0;
    int succeededTaskCount = 0;
    int totalCount = 0;

    for (Map.Entry<String, VertexTaskStats> v : vertexTaskStatsMap.entrySet()) {
      if (vertexName == null || vertexName.equals(v.getKey())) {
        final VertexTaskStats taskStats = v.getValue();
        totalCount += taskStats.numTaskCount;
        succeededTaskCount += taskStats.succeededTaskCount;
        killedTaskCount += taskStats.killedTaskCount;
        failedTaskCount += taskStats.failedTaskCount;
        runningTaskCount += (taskStats.numTaskCount - taskStats.completedTaskCount);
      }
    }

    ProgressProto.Builder progressBuilder = ProgressProto.newBuilder();
    progressBuilder.setTotalTaskCount(totalCount);
    progressBuilder.setRunningTaskCount(runningTaskCount);
    progressBuilder.setSucceededTaskCount(succeededTaskCount);
    progressBuilder.setKilledTaskCount(killedTaskCount);
    progressBuilder.setFailedTaskCount(failedTaskCount);
    return progressBuilder;
  }

  private VertexStatusProto.Builder parseVertexStatus(JSONObject jsonRoot,
                                                      Set<StatusGetOpts> statusOptions)
      throws JSONException {
    final JSONObject otherInfoNode = jsonRoot.getJSONObject(ATSConstants.OTHER_INFO);
    final VertexStatusProto.Builder vertexStatusBuilder = VertexStatusProto.newBuilder();

    final String status = otherInfoNode.optString(ATSConstants.STATUS);
    final String diagnostics = otherInfoNode.optString(ATSConstants.DIAGNOSTICS);
    if (status.equals("")) {
      return null;
    }

    vertexStatusBuilder.setState(vertexStateProtoMap.get(status))
        .addAllDiagnostics(Collections.singleton(diagnostics));

    int numRunningTasks = otherInfoNode.optInt(ATSConstants.NUM_TASKS) -
        otherInfoNode.optInt(ATSConstants.NUM_COMPLETED_TASKS);
    ProgressProto.Builder progressBuilder = ProgressProto.newBuilder();
    progressBuilder.setTotalTaskCount(otherInfoNode.optInt(ATSConstants.NUM_TASKS));
    progressBuilder.setRunningTaskCount(numRunningTasks);
    progressBuilder.setSucceededTaskCount(otherInfoNode.optInt(ATSConstants.NUM_SUCCEEDED_TASKS));
    progressBuilder.setKilledTaskCount(otherInfoNode.optInt(ATSConstants.NUM_KILLED_TASKS));
    progressBuilder.setFailedTaskCount(otherInfoNode.optInt(ATSConstants.NUM_FAILED_TASKS));
    vertexStatusBuilder.setProgress(progressBuilder);

    if (statusOptions != null && statusOptions.contains(StatusGetOpts.GET_COUNTERS)) {
      final TezCountersProto.Builder tezCounterBuilder;
      final JSONObject countersNode = otherInfoNode.optJSONObject(ATSConstants.COUNTERS);
      tezCounterBuilder = parseDagCounters(countersNode);
      if (tezCounterBuilder != null) {
        vertexStatusBuilder.setVertexCounters(tezCounterBuilder);
      }
    }

    return vertexStatusBuilder;
  }

  private TezCountersProto.Builder parseDagCounters(JSONObject countersNode)
      throws JSONException {
    if (countersNode == null) {
      return null;
    }

    TezCountersProto.Builder countersProto = TezCountersProto.newBuilder();
    final JSONArray counterGroupNodes = countersNode.optJSONArray(ATSConstants.COUNTER_GROUPS);
    if (counterGroupNodes != null) {
      final int numCounterGroups = counterGroupNodes.length();

      for (int i = 0; i < numCounterGroups; i++) {
        TezCounterGroupProto.Builder counterGroupBuilder =
            parseCounterGroup(counterGroupNodes.optJSONObject(i));
        if (counterGroupBuilder != null) {
          countersProto.addCounterGroups(counterGroupBuilder);
        }
      }
    }

    return countersProto;
  }

  private TezCounterGroupProto.Builder parseCounterGroup(JSONObject counterGroupNode)
      throws JSONException {

    if (counterGroupNode == null) {
      return null;
    }

    TezCounterGroupProto.Builder counterGroup = TezCounterGroupProto.newBuilder();

    final String groupName = counterGroupNode.optString(ATSConstants.COUNTER_GROUP_NAME);
    final String groupDisplayName = counterGroupNode.optString(
        ATSConstants.COUNTER_GROUP_DISPLAY_NAME);
    final JSONArray counterNodes = counterGroupNode.optJSONArray(ATSConstants.COUNTERS);
    final int numCounters = counterNodes.length();

    List<TezCounterProto> counters = new ArrayList<TezCounterProto>(numCounters);

    for (int i = 0; i < numCounters; i++) {
      final JSONObject counterNode = counterNodes.getJSONObject(i);
      final String counterName = counterNode.getString(ATSConstants.COUNTER_NAME);
      final String counterDisplayName = counterNode.getString(ATSConstants.COUNTER_DISPLAY_NAME);
      final long counterValue = counterNode.getLong(ATSConstants.COUNTER_VALUE);

      counters.add(
          TezCounterProto.newBuilder()
              .setName(counterName)
              .setDisplayName(counterDisplayName)
              .setValue(counterValue)
              .build());
    }

    return counterGroup.setName(groupName)
        .setDisplayName(groupDisplayName)
        .addAllCounters(counters);
  }

  @VisibleForTesting
  protected Map<String, VertexTaskStats> parseTaskStatsForVertexes()
      throws TezException, JSONException {

    if (vertexTaskStatsCache == null) {
      final String url = String.format("%s/%s?primaryFilter=%s:%s&fields=%s", baseUri,
          ATSConstants.TEZ_VERTEX_ID, ATSConstants.TEZ_DAG_ID, dagId, FILTER_BY_FIELDS);

      final JSONObject jsonRoot = getJsonRootEntity(url);
      final JSONArray vertexNodes = jsonRoot.optJSONArray(ATSConstants.ENTITIES);

      if (vertexNodes != null) {
        final int numVertexNodes = vertexNodes.length();
        Map<String, VertexTaskStats> vertexTaskStatsMap =
            new HashMap<String, VertexTaskStats>(numVertexNodes);
        for (int i = 0; i < numVertexNodes; i++) {
          final JSONObject vertexNode = vertexNodes.getJSONObject(i);
          final JSONObject otherInfoNode = vertexNode.getJSONObject(ATSConstants.OTHER_INFO);
          final String vertexName = otherInfoNode.getString(ATSConstants.VERTEX_NAME);
          final VertexTaskStats vertexTaskStats =
              new VertexTaskStats(otherInfoNode.optInt(ATSConstants.NUM_TASKS),
                  otherInfoNode.optInt(ATSConstants.NUM_COMPLETED_TASKS),
                  otherInfoNode.optInt(ATSConstants.NUM_SUCCEEDED_TASKS),
                  otherInfoNode.optInt(ATSConstants.NUM_KILLED_TASKS),
                  otherInfoNode.optInt(ATSConstants.NUM_FAILED_TASKS));
          vertexTaskStatsMap.put(vertexName, vertexTaskStats);
        }
        vertexTaskStatsCache = vertexTaskStatsMap;
      }
    }
    return vertexTaskStatsCache;
  }

  @VisibleForTesting
  protected JSONObject getJsonRootEntity(String url) throws TezException {
    try {
      WebResource wr = getHttpClient().resource(url);
      ClientResponse response = wr.accept(MediaType.APPLICATION_JSON_TYPE)
          .type(MediaType.APPLICATION_JSON_TYPE)
          .get(ClientResponse.class);

      final ClientResponse.Status clientResponseStatus = response.getClientResponseStatus();
      if (clientResponseStatus != ClientResponse.Status.OK) {
        if (clientResponseStatus == ClientResponse.Status.UNAUTHORIZED) {
          httpClient = null;
        }
        throw new TezException("Failed to get response from YARN Timeline: url: " + url +
          " error: " + clientResponseStatus);
      }

      return response.getEntity(JSONObject.class);
    } catch (ClientHandlerException e) {
      throw new TezException("Error processing response from YARN Timeline", e);
    } catch (UniformInterfaceException e) {
      throw new TezException("Error accessing content from YARN Timeline - unexpected response", e);
    } catch (IllegalArgumentException e) {
      throw new TezException("Error accessing content from YARN Timeline - invalid url", e);
    } catch (IOException e) {
      throw new TezException("Error getting http client connection", e);
    }
  }

  @VisibleForTesting
  protected static class VertexTaskStats {
    final int numTaskCount;
    final int completedTaskCount;
    final int succeededTaskCount;
    final int killedTaskCount;
    final int failedTaskCount;

    public VertexTaskStats(int numTaskCount, int completedTaskCount, int succeededTaskCount,
                           int killedTaskCount, int failedTaskCount) {
      this.numTaskCount = numTaskCount;
      this.completedTaskCount = completedTaskCount;
      this.succeededTaskCount = succeededTaskCount;
      this.killedTaskCount = killedTaskCount;
      this.failedTaskCount = failedTaskCount;
    }
  }

  private boolean webappHttpsOnly(Configuration conf) throws TezException {
    try {
      Class<?> yarnConfiguration = Class.forName("org.apache.hadoop.yarn.conf.YarnConfiguration");
      final Method useHttps = yarnConfiguration.getMethod("useHttps", Configuration.class);
      return (Boolean)useHttps.invoke(null, conf);
    } catch (ClassNotFoundException e) {
      throw new TezException(e);
    } catch (InvocationTargetException e) {
      throw new TezException(e);
    } catch (NoSuchMethodException e) {
      throw new TezException(e);
    } catch (IllegalAccessException e) {
      throw new TezException(e);
    }
  }

  protected Client getHttpClient() throws IOException, TezException {
    if (httpClient == null) {
      if (UserGroupInformation.isSecurityEnabled()) {
        final UserGroupInformation currentUser = UserGroupInformation.getCurrentUser();
        try {
          final Token<?> delegationToken = getDelegationToken(currentUser.getUserName());
          currentUser.addToken(delegationToken);
        } catch (UndeclaredThrowableException e) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("exception getting httpclient token", e);
          }
        }
      }

      ClientConfig clientConfig = new DefaultClientConfig(JSONRootElementProvider.App.class);
      HttpURLConnectionFactory connectionFactory = new TimelineURLConnectionFactory();
      httpClient = new Client(new URLConnectionClientHandler(connectionFactory), clientConfig);
    }
    return httpClient;
  }

  private Token<?> getDelegationToken(final String renewer) throws
      IOException, TezException {
    authUgi.checkTGTAndReloginFromKeytab();
    try {
      return authUgi.doAs(new PrivilegedExceptionAction<Token<?>>() {
        @Override
        public Token<?> run() throws IOException, AuthenticationException {
          try {
            URI resURI = URI.create(baseUri);
            DelegationTokenAuthenticatedURL authUrl =
                new DelegationTokenAuthenticatedURL(authenticator, connConfigurator);
            return (Token) authUrl.getDelegationToken(resURI.toURL(), token, renewer, doAsUser);
          } catch (IllegalArgumentException e) {
            throw new IOException("invalid url " + baseUri, e);
          }
        }
      });
    } catch (InterruptedException e) {
      throw new TezException(e);
    }
  }

  private class TimelineURLConnectionFactory implements HttpURLConnectionFactory {

    @Override
    public HttpURLConnection getHttpURLConnection(final URL url) throws IOException {
      try {
        return new DelegationTokenAuthenticatedURL(
            authenticator, connConfigurator).openConnection(url, token,
            doAsUser);
      } catch (UndeclaredThrowableException e) {
        throw new IOException(e.getCause());
      } catch (AuthenticationException ae) {
        throw new IOException(ae);
      }
    }

  }

  private static ConnectionConfigurator newConnConfigurator(Configuration conf) {
    try {
      return newSslConnConfigurator(DEFAULT_SOCKET_TIMEOUT, conf);
    } catch (Exception e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Cannot load customized ssl related configuration. " +
            "Fallback to system-generic settings.", e);
      }
      return DEFAULT_TIMEOUT_CONN_CONFIGURATOR;
    }
  }

  private static final ConnectionConfigurator DEFAULT_TIMEOUT_CONN_CONFIGURATOR =
      new ConnectionConfigurator() {
        @Override
        public HttpURLConnection configure(HttpURLConnection conn)
            throws IOException {
          setTimeouts(conn, DEFAULT_SOCKET_TIMEOUT);
          return conn;
        }
      };

  private static ConnectionConfigurator newSslConnConfigurator(final int timeout, Configuration conf)
      throws IOException, GeneralSecurityException {
    final SSLFactory factory;
    final SSLSocketFactory sf;
    final HostnameVerifier hv;

    factory = new SSLFactory(SSLFactory.Mode.CLIENT, conf);
    factory.init();
    sf = factory.createSSLSocketFactory();
    hv = factory.getHostnameVerifier();

    return new ConnectionConfigurator() {
      @Override
      public HttpURLConnection configure(HttpURLConnection conn)
          throws IOException {
        if (conn instanceof HttpsURLConnection) {
          HttpsURLConnection c = (HttpsURLConnection) conn;
          c.setSSLSocketFactory(sf);
          c.setHostnameVerifier(hv);
        }
        setTimeouts(conn, timeout);
        return conn;
      }
    };
  }

  private static void setTimeouts(URLConnection connection, int socketTimeout) {
    connection.setConnectTimeout(socketTimeout);
    connection.setReadTimeout(socketTimeout);
  }

  private static final Map<String, DAGStatusStateProto> dagStateProtoMap =
      Collections.unmodifiableMap(new HashMap<String, DAGStatusStateProto>() {{
        put("NEW", DAGStatusStateProto.DAG_SUBMITTED);
        put("INITED", DAGStatusStateProto.DAG_SUBMITTED);
        put("RUNNING", DAGStatusStateProto.DAG_RUNNING);
        put("SUCCEEDED", DAGStatusStateProto.DAG_SUCCEEDED);
        put("FAILED", DAGStatusStateProto.DAG_FAILED);
        put("KILLED", DAGStatusStateProto.DAG_KILLED);
        put("ERROR", DAGStatusStateProto.DAG_ERROR);
        put("TERMINATING", DAGStatusStateProto.DAG_TERMINATING);
        put("COMMITTING", DAGStatusStateProto.DAG_COMMITTING);
  }});

  private static final Map<String, VertexStatusStateProto> vertexStateProtoMap =
      Collections.unmodifiableMap(new HashMap<String, VertexStatusStateProto>() {{
        put("NEW", VertexStatusStateProto.VERTEX_NEW);
        put("INITIALIZING", VertexStatusStateProto.VERTEX_INITIALIZING);
        put("RECOVERING", VertexStatusStateProto.VERTEX_RECOVERING);
        put("INITED", VertexStatusStateProto.VERTEX_INITED);
        put("RUNNING", VertexStatusStateProto.VERTEX_RUNNING);
        put("SUCCEEDED", VertexStatusStateProto.VERTEX_SUCCEEDED);
        put("FAILED", VertexStatusStateProto.VERTEX_FAILED);
        put("KILLED", VertexStatusStateProto.VERTEX_KILLED);
        put("ERROR", VertexStatusStateProto.VERTEX_ERROR);
        put("TERMINATING", VertexStatusStateProto.VERTEX_TERMINATING);
        put("COMMITTING", VertexStatusStateProto.VERTEX_COMMITTING);
      }});


  @Override
  public DAGStatus getDAGStatus(@Nullable Set<StatusGetOpts> statusOptions,
      long timeout) throws IOException, TezException {
    return getDAGStatus(statusOptions);
  }

}
