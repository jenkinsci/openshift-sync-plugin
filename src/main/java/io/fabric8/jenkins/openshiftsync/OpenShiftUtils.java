/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerStatus;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.Version;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildStatus;
import io.fabric8.openshift.api.model.GitBuildSource;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.api.model.RouteSpec;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;

import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.NEW;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.PENDING;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.RUNNING;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_DEFAULT_NAMESPACE;
import static java.util.logging.Level.FINE;

/**
 */
public class OpenShiftUtils {
  private final static Logger logger = Logger.getLogger(OpenShiftUtils.class.getName());

  private static OpenShiftClient openShiftClient;

  private static final DateTimeFormatter dateFormatter = ISODateTimeFormat.dateTimeNoMillis();

  /**
   * Initializes an {@link OpenShiftClient}
   *
   * @param serverUrl the optional URL of where the OpenShift cluster API server is running
   */
  public synchronized static void initializeOpenShiftClient(String serverUrl) {
    OpenShiftConfigBuilder configBuilder = new OpenShiftConfigBuilder();
    if (serverUrl != null && !serverUrl.isEmpty()) {
      configBuilder.withMasterUrl(serverUrl);
    }
    Config config = configBuilder.build();
    config.setUserAgent("openshift-sync-plugin-" + Jenkins.getInstance().getPluginManager().getPlugin("openshift-sync").getVersion() + "/fabric8-" +Version.clientVersion());
    openShiftClient = new DefaultOpenShiftClient(config);
  }

  public synchronized static OpenShiftClient getOpenShiftClient() {
    return openShiftClient;
  }

  // Get the current OpenShiftClient and configure to use the current Oauth token.
  public synchronized static OpenShiftClient getAuthenticatedOpenShiftClient() {
    if(openShiftClient != null){
      String token = CredentialsUtils.getCurrentToken();
      if(token.length() > 0) {
        openShiftClient.getConfiguration().setOauthToken(token);
      }
    }

    return openShiftClient;
  }

  public synchronized static void shutdownOpenShiftClient() {
    if (openShiftClient != null) {
      openShiftClient.close();
      openShiftClient = null;
    }
  }

  /**
   * Checks if a {@link BuildConfig} relates to a Jenkins build
   *
   * @param bc the BuildConfig
   * @return true if this is an OpenShift BuildConfig which should be mirrored to
   * a Jenkins Job
   */
  public static boolean isJenkinsBuildConfig(BuildConfig bc) {
    if (BuildConfigToJobMapper.JENKINS_PIPELINE_BUILD_STRATEGY.equalsIgnoreCase(bc.getSpec().getStrategy().getType()) &&
      bc.getSpec().getStrategy().getJenkinsPipelineStrategy() != null) {
      return true;
    }

    ObjectMeta metadata = bc.getMetadata();
    if (metadata != null) {
      Map<String, String> annotations = metadata.getAnnotations();
      if (annotations != null) {
        if (annotations.get("fabric8.link.jenkins.job/label") != null) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Finds the Jenkins job name for the given {@link BuildConfig}.
   *
   * @param bc the BuildConfig
   * @return the jenkins job name for the given BuildConfig
   */
  public static String jenkinsJobName(BuildConfig bc) {
    String namespace = bc.getMetadata().getNamespace();
    String name = bc.getMetadata().getName();
    return jenkinsJobName(namespace, name);
  }

  /**
   * Creates the Jenkins Job name for the given buildConfigName
   *
   * @param namespace the namespace of the build
   * @param buildConfigName the name of the {@link BuildConfig} in in the namespace
   * @return the jenkins job name for the given namespace and name
   */
  public static String jenkinsJobName(String namespace, String buildConfigName) {
    return namespace + "-" + buildConfigName;
  }

  /**
   * Finds the Jenkins job display name for the given {@link BuildConfig}.
   *
   * @param bc the BuildConfig
   * @return the jenkins job display name for the given BuildConfig
   */
  public static String jenkinsJobDisplayName(BuildConfig bc) {
    String namespace = bc.getMetadata().getNamespace();
    String name = bc.getMetadata().getName();
    return jenkinsJobDisplayName(namespace, name);
  }

  /**
   * Creates the Jenkins Job display name for the given buildConfigName
   *
   * @param namespace the namespace of the build
   * @param buildConfigName the name of the {@link BuildConfig} in in the namespace
   * @return the jenkins job display name for the given namespace and name
   */
  public static String jenkinsJobDisplayName(String namespace, String buildConfigName) {
    return namespace + "/" + buildConfigName;
  }

  /**
   * Gets the current namespace running Jenkins inside or returns a reasonable default
   *
   * @param configuredNamespaces the optional configured namespace(s)
   * @param client the OpenShift client
   * @return the default namespace using either the configuration value, the default namespace on the client or "default"
   */
  public static String[] getNamespaceOrUseDefault(String[] configuredNamespaces, OpenShiftClient client) {
    String[] namespaces = configuredNamespaces;
    if (namespaces==null || namespaces.length==0) {
      namespaces = new String[]{client.getNamespace()};
      if (StringUtils.isBlank(namespaces[0])) {
        namespaces = new String[]{OPENSHIFT_DEFAULT_NAMESPACE};
      }
    }
    return namespaces;
  }

  /**
   * Returns the public URL of the given service
   *
   * @param openShiftClient the OpenShiftClient to use
   * @param defaultProtocolText the protocol text part of a URL such as <code>http://</code>
   * @param namespace the Kubernetes namespace
   * @param serviceName the service name
   * @return the external URL of the service
   */
  public static String getExternalServiceUrl(OpenShiftClient openShiftClient, String defaultProtocolText, String namespace, String serviceName) {
    try {
      RouteList routes = openShiftClient.routes().inNamespace(namespace).list();
      for (Route route : routes.getItems()) {
        RouteSpec spec = route.getSpec();
        if (spec != null && spec.getTo() != null && "Service".equalsIgnoreCase(spec.getTo().getKind()) && serviceName.equalsIgnoreCase(spec.getTo().getName())) {
          String host = spec.getHost();
          if (host != null && host.length() > 0) {
            if (spec.getTls() != null) {
              return "https://" + host;
            }
            return "http://" + host;
          }
        }
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Could not find Route for service " + namespace + "/" + serviceName + ". " + e, e);
    }
    // lets try the portalIP instead
    try {
      Service service = openShiftClient.services().inNamespace(namespace).withName(serviceName).get();
      if (service != null) {
        ServiceSpec spec = service.getSpec();
        if (spec != null) {
          String host = spec.getClusterIP();
          if (host != null && host.length() > 0) {
            return defaultProtocolText + host;
          }
        }
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Could not find Route for service " + namespace + "/" + serviceName + ". " + e, e);
    }

    // let's try the Jenkins configured root url
    // this can be needed if someone is not using 'jenkins'
    // as the service name.
    String rootUrl = Jenkins.getInstance().getRootUrl();
    if (StringUtils.isNotEmpty(rootUrl)) {
      return rootUrl;
    }

    // lets default to the service DNS name
    return defaultProtocolText + serviceName;
  }

  /**
   * Calculates the external URL to access Jenkins
   *
   * @param namespace the namespace Jenkins is runing inside
   * @param openShiftClient              the OpenShift client
   * @return the external URL to access Jenkins
   */
  public static String getJenkinsURL(OpenShiftClient openShiftClient, String namespace) {
    return getExternalServiceUrl(openShiftClient, "http://", namespace ,"jenkins");
  }

  /**
   * Lazily creates the GitSource if need be then updates the git URL
   * @param buildConfig the BuildConfig to update
   * @param gitUrl the URL to the git repo
   * @param ref the git ref (commit/branch/etc) for the build
   */
  public static void updateGitSourceUrl(BuildConfig buildConfig, String gitUrl, String ref) {
    BuildConfigSpec spec = buildConfig.getSpec();
    if (spec == null) {
      spec = new BuildConfigSpec();
      buildConfig.setSpec(spec);
    }
    BuildSource source = spec.getSource();
    if (source == null) {
      source = new BuildSource();
      spec.setSource(source);
    }
    source.setType("Git");
    GitBuildSource gitSource = source.getGit();
    if (gitSource == null) {
      gitSource = new GitBuildSource();
      source.setGit(gitSource);
    }
    gitSource.setUri(gitUrl);
    gitSource.setRef(ref);
  }

  public static void updateOpenShiftBuildPhase(Build build, String phase) {
    logger.log(FINE, "setting build to {0} in namespace {1}/{2}", new Object[]{phase, build.getMetadata().getNamespace(), build.getMetadata().getName()});
    getAuthenticatedOpenShiftClient().builds().inNamespace(build.getMetadata().getNamespace()).withName(build.getMetadata().getName())
      .edit()
      .editStatus().withPhase(phase).endStatus()
      .done();
  }

  /**
   * Maps a Jenkins Job name to an ObjectShift BuildConfig name
   *
   * @return the namespaced name for the BuildConfig
   * @param jobName the job to associate to a BuildConfig name
   * @param namespace the default namespace that Jenkins is running inside
   */
  public static NamespaceName buildConfigNameFromJenkinsJobName(String jobName, String namespace) {
    // TODO lets detect the namespace separator in the jobName for cases where a jenkins is used for
    // BuildConfigs in multiple namespaces?
    return new NamespaceName(namespace, jobName);
  }

  public static long parseResourceVersion(HasMetadata obj) {
    return parseResourceVersion(obj.getMetadata().getResourceVersion());
  }

  public static long parseResourceVersion(String resourceVersion) {
    try {
      return Long.parseLong(resourceVersion);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public static String formatTimestamp(long timestamp) {
    return dateFormatter.print(new DateTime(timestamp));
  }

  public static long parseTimestamp(String timestamp) {
    return dateFormatter.parseMillis(timestamp);
  }

  public static boolean isResourceWithoutStateEqual(HasMetadata oldObj, HasMetadata newObj) {
    try {
      byte[] oldDigest = MessageDigest.getInstance("MD5").digest(dumpWithoutRuntimeStateAsYaml(oldObj).getBytes(StandardCharsets.UTF_8));
      byte[] newDigest = MessageDigest.getInstance("MD5").digest(dumpWithoutRuntimeStateAsYaml(newObj).getBytes(StandardCharsets.UTF_8));
      return Arrays.equals(oldDigest, newDigest);
    } catch (NoSuchAlgorithmException | JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public static String dumpWithoutRuntimeStateAsYaml(HasMetadata obj) throws JsonProcessingException {
    ObjectMapper statelessMapper = new ObjectMapper(new YAMLFactory());
    statelessMapper.addMixInAnnotations(ObjectMeta.class, ObjectMetaMixIn.class);
    statelessMapper.addMixInAnnotations(ReplicationController.class, StatelessReplicationControllerMixIn.class);
    return statelessMapper.writeValueAsString(obj);
  }

  public static boolean isCancellable(BuildStatus buildStatus) {
    String phase = buildStatus.getPhase();
    return phase.equals(NEW) || phase.equals(PENDING) || phase.equals(RUNNING);
  }

  public static boolean isNew(BuildStatus buildStatus) {
    return buildStatus.getPhase().equals(NEW);
  }

  public static boolean isCancelled(BuildStatus status) {
    return status != null && status.getCancelled() != null && Boolean.TRUE.equals(status.getCancelled());
  }

  abstract class StatelessReplicationControllerMixIn extends ReplicationController {
    @JsonIgnore
    private ReplicationControllerStatus status;

    StatelessReplicationControllerMixIn() {
    }

    @JsonIgnore
    public abstract ReplicationControllerStatus getStatus();
  }

  abstract class ObjectMetaMixIn extends ObjectMeta {
    @JsonIgnore
    private String creationTimestamp;
    @JsonIgnore
    private String deletionTimestamp;
    @JsonIgnore
    private Long generation;
    @JsonIgnore
    private String resourceVersion;
    @JsonIgnore
    private String selfLink;
    @JsonIgnore
    private String uid;

    ObjectMetaMixIn() {
    }

    @JsonIgnore
    public abstract String getCreationTimestamp();

    @JsonIgnore
    public abstract String getDeletionTimestamp();

    @JsonIgnore
    public abstract Long getGeneration();

    @JsonIgnore
    public abstract String getResourceVersion();

    @JsonIgnore
    public abstract String getSelfLink();

    @JsonIgnore
    public abstract String getUid();
  }
}
