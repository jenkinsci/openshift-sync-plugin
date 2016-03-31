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
import io.fabric8.openshift.api.model.BuildStatus;
import io.fabric8.openshift.api.model.BuildStatusBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Constants.ANNOTATION_JENKINS_BUILD_URI;
import static io.fabric8.jenkins.openshiftsync.Constants.ANNOTATION_JENKINS_LOG_URL;
import static io.fabric8.jenkins.openshiftsync.Constants.ANNOTATION_JENKINS_STATUS_JSON;
import static io.fabric8.jenkins.openshiftsync.Constants.ANNOTATION_OPENSHIFT_BUILD_NUMBER;

/**
 * Listens to Jenkins Job build {@link Run} start and stop then ensure there's a suitable {@link Build} object in
 * OpenShift thats updated correctly with the current status, logsURL and metrics
 */
@Extension
public class BuildSyncRunListener extends RunListener<Run> {

  @XStreamOmitField
  private final Logger logger = Logger.getLogger(getClass().getName());

  @XStreamOmitField
  private final DateTimeFormatter dateFormatter = ISODateTimeFormat.dateTime();

  private long pollPeriodMs = 2000;
  private String server;
  private String defaultNamespace;

  @XStreamOmitField
  private OpenShiftClient openShiftClient;

  @XStreamOmitField
  private Set<String> urlsToPoll = new CopyOnWriteArraySet<>();
  @XStreamOmitField
  private AtomicBoolean timerStarted = new AtomicBoolean(false);

  public BuildSyncRunListener() {
    init();
  }

  @DataBoundConstructor
  public BuildSyncRunListener(String server, String defaultNamespace, long pollPeriodMs) {
    this.server = server;
    this.defaultNamespace = defaultNamespace;
    this.pollPeriodMs = pollPeriodMs;
    init();
  }

  private void init() {
    openShiftClient = OpenShiftUtils.createOpenShiftClient(server);
    defaultNamespace = OpenShiftUtils.getNamespaceOrUseDefault(defaultNamespace,openShiftClient);
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

  private synchronized void upsertBuild(BuildName jobAndBuildName, Run run, String json, String url) {
    // TODO should we check if we can find the buildConfig and if not do we need to split by namespace + name?
    String buildConfigName = jobAndBuildName.getJobName();

    BuildList buildList = openShiftClient.builds().inNamespace(defaultNamespace).withLabel(Constants.LABEL_BUILDCONFIG, buildConfigName).list();
    Build found = null;
    Build openshiftBuild = null;
    int buildCount = 0;
    List<Build> items = Collections.EMPTY_LIST;
    if (buildList != null) {
      items = buildList.getItems();
    }
    buildCount = items.size();
    for (Build build : items) {
      if (OpenShiftUtils.openShiftBuildMapsToJenkinsBuild(jobAndBuildName, build, url)) {
        found = build;
      } else if (OpenShiftUtils.isJenkinsBuildCreatedByOpenShift(build, defaultNamespace)) {
        openshiftBuild = build;
      }
    }

    int buildNumber = buildCount + 1;
    String buildName = buildConfigName + "-" + buildNumber;
    String buildNumberText = "" + buildNumber;

    String rootUrl = OpenShiftUtils.getJenkinsURL(openShiftClient, defaultNamespace);
    String logsUrl = joinPaths(rootUrl, url, "/consoleText");

    boolean create = false;
    if (found == null && openshiftBuild != null) {
      found = openshiftBuild;
      logger.info("reusing OpenShift Build created by OpenShift for the Jenkins Build status " + NamespaceName.create(found));
    }
    if (found == null) {
      create = true;
      BuildConfig buildConfig;
      try {
        buildConfig = openShiftClient.buildConfigs().inNamespace(defaultNamespace).withName(buildConfigName).get();
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to find BuildConfig for namespace " + defaultNamespace + " buildName + " + buildConfigName + ". " + e, e);
        return;
      }
      if (buildConfig == null) {
        logger.warning("No BuildConfig for namespace " + defaultNamespace + " buildName + " + buildConfigName);
        return;
      }
      BuildSpec buildSpec = createBuildSpec(buildConfig);
      found = new BuildBuilder().
        withNewMetadata().
        withName(buildName).
        withNamespace(defaultNamespace).
        addToLabels(Constants.LABEL_BUILDCONFIG, buildConfigName).
        addToLabels(Constants.LABEL_OPENSHIFT_BUILD_CONFIG_NAME, buildConfigName).
        addToAnnotations(ANNOTATION_OPENSHIFT_BUILD_NUMBER, buildNumberText).
        addToAnnotations(ANNOTATION_JENKINS_BUILD_URI, url).
        addToAnnotations(ANNOTATION_JENKINS_LOG_URL, logsUrl).
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

    // if this Build was created by OpenShift lets add any missing annotations
    if (!annotations.containsKey(ANNOTATION_OPENSHIFT_BUILD_NUMBER)) {
      annotations.put(ANNOTATION_OPENSHIFT_BUILD_NUMBER, buildNumberText);
    }
    if (!annotations.containsKey(ANNOTATION_JENKINS_BUILD_URI)) {
      annotations.put(ANNOTATION_JENKINS_BUILD_URI, url);
    }
    if (!annotations.containsKey(ANNOTATION_JENKINS_LOG_URL)) {
      annotations.put(ANNOTATION_JENKINS_LOG_URL, logsUrl);
    }
    String name = metadata.getName();

    if (run == null) {
      run = JenkinsUtils.getRun(jobAndBuildName);
      if (run == null) {
        logger.warning("Could not find Jenkins Job Run for " + jobAndBuildName);
      }
    }

    BuildStatus status = updateBuildStatus(found.getStatus(), run);
    found.setStatus(status);

    if (logger.isLoggable(Level.FINE)) {
      logger.fine("generated build in namespace " + defaultNamespace + " with name: " + name + " phase: " + found.getStatus().getPhase() + " data: " + found);
    }

    if (create) {
      logger.info("creating build in namespace " + defaultNamespace + " with name: " + name + " phase: " + found.getStatus().getPhase());
      openShiftClient.builds().inNamespace(defaultNamespace).withName(name).create(found);
    } else {
      logger.info("replacing build in namespace " + defaultNamespace + " with name: " + name + " phase: " + found.getStatus().getPhase());
      openShiftClient.builds().inNamespace(defaultNamespace).withName(name).replace(found);
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

  private BuildStatus updateBuildStatus(BuildStatus buildStatus, Run run) {
    BuildStatusBuilder builder;
    if (buildStatus != null) {
      builder = new BuildStatusBuilder(buildStatus);
    } else {
      builder = new BuildStatusBuilder();
    }

    if (run != null) {
      builder.withPhase(runToBuildPhase(run));
      long startTime = run.getStartTimeInMillis();
      if (startTime > 0) {
        DateTime dateTime = new DateTime(startTime);
        builder.withStartTimestamp(dateFormatter.print(dateTime));
      }
      long duration = run.getDuration();
      if (duration > 0) {
        DateTime dateTime = new DateTime(startTime + duration);
        builder.withCompletionTimestamp(dateFormatter.print(dateTime));
      }
    }
    return builder.build();
  }

  private String runToBuildPhase(Run run) {
    if (run != null && !run.hasntStartedYet()) {
      if (run.isBuilding()) {
        return BuildPhases.RUNNING;
      } else {
        Result result = run.getResult();
        if (result != null) {
          if (result.equals(Result.SUCCESS)) {
            return BuildPhases.COMPLETE;
          } else if (result.equals(Result.ABORTED)) {
            return BuildPhases.CANCELLED;
          } else if (result.equals(Result.FAILURE)) {
            return BuildPhases.FAILED;
          } else if (result.equals(Result.UNSTABLE)) {
            return BuildPhases.FAILED;
          } else {
            return BuildPhases.PENDING;
          }
        }
      }
    }
    return BuildPhases.NEW;
  }

  /**
   * Returns true if we should poll the status of this run
   *
   * @param run the Run to test against
   * @return true if the should poll the status of this build run
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
