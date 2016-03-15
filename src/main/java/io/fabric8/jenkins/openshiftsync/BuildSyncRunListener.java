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

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildBuilder;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.BuildSpec;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 */
@Extension
public class BuildSyncRunListener extends RunListener<Run> {
  public static final String ANNOTATION_PHASE = "openshift.io/phase";
  public static final String ANNOTATION_JENKINS_BUILD_URL = "openshift.io/jenkins-build-uri";
  public static final String ANNOTATION_JENKINS_STATUS_JSON = "openshift.io/jenkins-status-json";
  public static final String ANNOTATION_OPENSHIFT_BUILD_NUMBER = "openshift.io/build.number";

  public static final String LABEL_BUILDCONFIG = "buildconfig";
  public static final String LABEL_OPENSHIFT_BUILD_CONFIG_NAME = "openshift.io/build-config.name";

  @XStreamOmitField
  private final Logger logger = Logger.getLogger(getClass().getName());

  private long pollPeriodMs = 2000;
  private String server;
  private String namespace;

  @XStreamOmitField
  private OpenShiftClient openShiftClient;

  @XStreamOmitField
  private Set<String> urlsToPoll = new CopyOnWriteArraySet<>();
  @XStreamOmitField
  private AtomicBoolean timerStarted = new AtomicBoolean(false);

  public BuildSyncRunListener() {
    openShiftClient = init();
  }

  @DataBoundConstructor
  public BuildSyncRunListener(String server, String namespace, long pollPeriodMs) {
    this.server = server;
    this.namespace = namespace;
    this.pollPeriodMs = pollPeriodMs;
  }

  private OpenShiftClient init() {
    OpenShiftClient openShiftClient;
    openShiftClient = OpenShiftUtils.createOpenShiftClient(server);
    return openShiftClient;
  }

  @Override
  public void onStarted(Run run, TaskListener listener) {
    String url = run.getUrl();
    if (shouldPollRun(run)) {
      checkTimerStarted();
      if (urlsToPoll.add(url)) {
        logger.info("starting polling build " + url);
      }
    }
    super.onStarted(run, listener);
  }

  protected void checkTimerStarted() {
    if (timerStarted.compareAndSet(false, true)) {
      Runnable task = new Runnable() {
        @Override
        public void run() {
          pollLoop();
        }
      };
      Timer.get().scheduleAtFixedRate(task, pollPeriodMs, pollPeriodMs, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void onCompleted(Run run, @Nonnull TaskListener listener) {
    String url = run.getUrl();
    urlsToPoll.remove(url);
    pollRun(url, run);
    logger.info("onCompleted " + url);
    super.onCompleted(run, listener);
  }

  @Override
  public void onDeleted(Run run) {
    String url = run.getUrl();
    urlsToPoll.remove(url);
    pollRun(url, run);
    logger.info("onDeleted " + url);
    super.onDeleted(run);

    // TODO should we remove the OpenShift Build too?
  }

  @Override
  public void onFinalized(Run run) {
    String url = run.getUrl();
    urlsToPoll.remove(url);
    pollRun(url, run);
    logger.info("onFinalized " + url);
    super.onFinalized(run);
  }


  protected void pollLoop() {
    for (String url : urlsToPoll) {
      pollRun(url, null);
    }
  }


  protected void pollRun(String url, Run run) {
    BuildName buildName = BuildName.parseBuildUrl(url);

    String root = JenkinsUtils.getRootUrl();

    String fullUrl = joinPaths(root, url, "/wfapi/describe");
    logger.info("Polling URL: " + fullUrl + " for " + buildName);

    OkHttpClient client = new OkHttpClient();
    Request request = new Request.Builder()
      .url(fullUrl)
      .build();

    try {
      Response response = client.newCall(request).execute();
      String json = response.body().string();
      upsertBuild(buildName, run, json, url);
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to poll " + fullUrl + ". " + e, e);
    }
  }

  private void upsertBuild(BuildName buildName, Run run, String json, String url) {
    String namespace = getNamespaceOrDefault();
    // TODO should we check if we can find the buildConfig and if not do we need to split by namespace + name?
    String buildConfigName = buildName.getJobName();

    BuildList buildList = openShiftClient.builds().inNamespace(namespace).withLabel(LABEL_BUILDCONFIG, buildConfigName).list();
    Build found = null;
    int buildCount = 0;
    if (buildList != null) {
      List<Build> items = buildList.getItems();
      buildCount = items.size();
      for (Build build : items) {
        if (openShiftBuildMapsToJenkinsBuild(buildName, build, url)) {
          found = build;
        }
      }
    }

    boolean create = false;
    if (found == null) {
      create = true;
      int buildNumber = buildCount + 1;
      String name = buildConfigName + "-" + buildNumber;
      BuildConfig buildConfig;
      try {
        buildConfig = openShiftClient.buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to find BuildConfig for namespace " + namespace + " name + " + buildConfigName + ". " + e, e);
        return;
      }
      if (buildConfig == null) {
        logger.warning("No BuildConfig for namespace " + namespace + " name + " + buildConfigName);
        return;
      }
      BuildSpec buildSpec = createBuildSpec(buildConfig);
      found = new BuildBuilder().
        withNewMetadata().
        withName(name).
        withNamespace(namespace).
        addToLabels(LABEL_BUILDCONFIG, buildConfigName).
        addToLabels(LABEL_OPENSHIFT_BUILD_CONFIG_NAME, buildConfigName).
        addToAnnotations(ANNOTATION_OPENSHIFT_BUILD_NUMBER, "" + buildNumber).
        addToAnnotations(ANNOTATION_JENKINS_BUILD_URL, url).
        endMetadata().
        withNewSpecLike(buildSpec).
        endSpec().
        build();
    }

    // lets store the status as an annotation
    ObjectMeta metadata = found.getMetadata();
    if (metadata == null) {
      metadata = new ObjectMeta();
      found.setMetadata(metadata);
    }
    Map<String, String> annotations = metadata.getAnnotations();
    if (annotations == null) {
      annotations = new HashMap<>();
      metadata.setAnnotations(annotations);
    }
    annotations.put(ANNOTATION_JENKINS_STATUS_JSON, json);
    String name = metadata.getName();

    String phase = BuildPhases.NEW;
    if (run == null) {
      run = JenkinsUtils.getRun(buildName);
      if (run == null) {
        logger.warning("Could not find Jenkins Job Run for " + buildName);
      }
    }
    if (run != null) {
      if (!run.hasntStartedYet()) {
        if (run.isBuilding()) {
          phase = BuildPhases.RUNNING;
        } else {
          Result result = run.getResult();
          if (result != null) {
            if (result.equals(Result.SUCCESS)) {
              phase = BuildPhases.COMPLETE;
            } else if (result.equals(Result.ABORTED)) {
              phase = BuildPhases.CANCELLED;
            } else if (result.equals(Result.FAILURE)) {
              phase = BuildPhases.FAILED;
            } else if (result.equals(Result.UNSTABLE)) {
              phase = BuildPhases.FAILED;
            } else {
              phase = BuildPhases.PENDING;
            }
          }
        }
      }
    }
/*
    // TODO looks like we cannot update the status phase as OpenShift barfs

    BuildStatus status = found.getStatus();
    if (status == null) {
      status = new BuildStatus();
    }
    status.setPhase(phase);
*/
    annotations.put(ANNOTATION_PHASE, phase);

    if (create) {
      logger.info("creating build in namespace " + namespace + " with name: " + name + " phase: " + phase);
      openShiftClient.builds().inNamespace(namespace).withName(name).create(found);
    } else {
      // lets clear the status as it fails if we try to update it!
      found.setStatus(null);
      logger.info("replacing build in namespace " + namespace + " with name: " + name + " phase: " + phase);
      openShiftClient.builds().inNamespace(namespace).withName(name).replace(found);
    }
  }

  private BuildSpec createBuildSpec(BuildConfig buildConfig) {
    BuildSpec answer = new BuildSpec();
    BuildConfigSpec spec = buildConfig.getSpec();
    if (spec != null) {
      answer.setCompletionDeadlineSeconds(spec.getCompletionDeadlineSeconds());
      answer.setOutput(spec.getOutput());
      answer.setPostCommit(spec.getPostCommit());
      answer.setResources(spec.getResources());
      answer.setRevision(spec.getRevision());
      answer.setServiceAccount(spec.getServiceAccount());
      answer.setSource(spec.getSource());
      answer.setStrategy(spec.getStrategy());
    }
    return answer;
  }

  private String getNamespaceOrDefault() {
    String namespace = this.namespace;
    if (namespace == null || namespace.isEmpty()) {
      namespace = openShiftClient.getNamespace();
      if (namespace == null || namespace.isEmpty()) {
        namespace = "default";
      }
    }
    return namespace;
  }

  /**
   * Returns true if this OpenShift Build maps to the given Jenkins job build
   */
  private boolean openShiftBuildMapsToJenkinsBuild(BuildName buildName, Build build, String url) {
    ObjectMeta metadata = build.getMetadata();
    if (metadata != null) {
      Map<String, String> annotations = metadata.getAnnotations();
      if (annotations != null) {
        String anotherUrl = annotations.get(ANNOTATION_JENKINS_BUILD_URL);
        if (anotherUrl != null && anotherUrl.equals(url)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns true if we should poll the status of this run
   *
   * @param run the Run to test against
   */
  protected boolean shouldPollRun(Run run) {
    return run instanceof WorkflowRun;
  }


  /**
   * Joins all the given strings, ignoring nulls so that they form a URL with / between the paths without a // if the
   * previous path ends with / and the next path starts with / unless a path item is blank
   *
   * @param strings the sequence of strings to join
   * @return the strings concatenated together with / while avoiding a double // between non blank strings.
   */
  public static String joinPaths(String... strings) {
    StringBuilder buffer = new StringBuilder();
    for (String string : strings) {
      if (string == null) {
        continue;
      }
      if (buffer.length() > 0) {
        boolean bufferEndsWithSeparator = buffer.toString().endsWith("/");
        boolean stringStartsWithSeparator = string.startsWith("/");
        if (bufferEndsWithSeparator) {
          if (stringStartsWithSeparator) {
            string = string.substring(1);
          }
        } else {
          if (!stringStartsWithSeparator) {
            buffer.append("/");
          }
        }
      }
      buffer.append(string);
    }
    return buffer.toString();
  }
}
