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

import com.cloudbees.workflow.rest.external.AtomFlowNodeExt;
import com.cloudbees.workflow.rest.external.FlowNodeExt;
import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.api.model.Build;
import jenkins.util.Timer;
import org.apache.commons.httpclient.HttpStatus;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_BUILD_URI;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_LOG_URL;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_STATUS_JSON;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.maybeScheduleNext;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.formatTimestamp;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/**
 * Listens to Jenkins Job build {@link Run} start and stop then ensure there's a suitable {@link Build} object in
 * OpenShift thats updated correctly with the current status, logsURL and metrics
 */
@Extension
public class BuildSyncRunListener extends RunListener<Run> {
  private static final Logger logger = Logger.getLogger(BuildSyncRunListener.class.getName());

  private long pollPeriodMs = 1000;
  private String namespace;

  private transient Set<Run> runsToPoll = new CopyOnWriteArraySet<>();

  private transient AtomicBoolean timerStarted = new AtomicBoolean(false);

  public BuildSyncRunListener() {
    init();
  }

  @DataBoundConstructor
  public BuildSyncRunListener(long pollPeriodMs) {
    this.pollPeriodMs = pollPeriodMs;
    init();
  }

  /**
   * Joins all the given strings, ignoring nulls so that they form a URL with / between the paths without a // if the
   * previous path ends with / and the next path starts with / unless a path item is blank
   *
   * @param strings the sequence of strings to join
   * @return the strings concatenated together with / while avoiding a double // between non blank strings.
   */
  public static String joinPaths(String... strings) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < strings.length; i++) {
      sb.append(strings[i]);
      if (i < strings.length - 1) {
        sb.append("/");
      }
    }
    String joined = sb.toString();

    // And normalize it...
    return joined
      .replaceAll("/+", "/")
      .replaceAll("/\\?", "?")
      .replaceAll("/#", "#")
      .replaceAll(":/", "://");
  }

  private void init() {
    namespace = OpenShiftUtils.getNamespaceOrUseDefault(namespace, getOpenShiftClient());
  }

  @Override
  public synchronized void onStarted(Run run, TaskListener listener) {
    if (shouldPollRun(run)) {
      try {
        BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
        if (cause != null) {
          // TODO This should be a link to the OpenShift console.
          run.setDescription(cause.getShortDescription());
        }
      } catch (IOException e) {
        logger.log(WARNING, "Cannot set build description: " + e);
      }
      if (runsToPoll.add(run)) {
        logger.info("starting polling build " + run.getUrl());
      }
      checkTimerStarted();
    } else {
      logger.fine("not polling polling build " + run.getUrl() + " as its not a WorkflowJob");
    }
    super.onStarted(run, listener);
  }

  protected void checkTimerStarted() {
    if (timerStarted.compareAndSet(false, true)) {
      Runnable task = new SafeTimerTask() {
        @Override
        protected void doRun() throws Exception {
          pollLoop();
        }
      };
      Timer.get().scheduleAtFixedRate(task, pollPeriodMs, pollPeriodMs, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public synchronized void onCompleted(Run run, @Nonnull TaskListener listener) {
    if (shouldPollRun(run)) {
      runsToPoll.remove(run);
      pollRun(run);
      logger.info("onCompleted " + run.getUrl());
      maybeScheduleNext(((WorkflowRun) run).getParent());
    }
    super.onCompleted(run, listener);
  }

  @Override
  public synchronized void onDeleted(Run run) {
    if (shouldPollRun(run)) {
      runsToPoll.remove(run);
      pollRun(run);
      logger.info("onDeleted " + run.getUrl());
      maybeScheduleNext(((WorkflowRun) run).getParent());
    }
    super.onDeleted(run);
  }

  @Override
  public synchronized void onFinalized(Run run) {
    if (shouldPollRun(run)) {
      runsToPoll.remove(run);
      pollRun(run);
      logger.info("onFinalized " + run.getUrl());
    }
    super.onFinalized(run);
  }

  protected synchronized void pollLoop() {
    for (Run run : runsToPoll) {
      pollRun(run);
    }
  }

  protected synchronized void pollRun(Run run) {
    if (!(run instanceof WorkflowRun)) {
      throw new IllegalStateException("Cannot poll a non-workflow run");
    }

    RunExt wfRunExt = RunExt.create((WorkflowRun) run);

    try {
      upsertBuild(run, wfRunExt);
    } catch (KubernetesClientException e) {
      if (e.getCode() == HttpStatus.SC_UNPROCESSABLE_ENTITY) {
        runsToPoll.remove(run);
        logger.log(WARNING, "Cannot update status: {0}", e.getMessage());
        return;
      }
      throw e;
    }
  }

  private void upsertBuild(Run run, RunExt wfRunExt) {
    if (run == null) {
      return;
    }

    BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
    if (cause == null) {
      return;
    }

    String rootUrl = OpenShiftUtils.getJenkinsURL(getOpenShiftClient(), namespace);
    String buildUrl = joinPaths(rootUrl, run.getUrl());
    String logsUrl = joinPaths(buildUrl, "/consoleText");

    if (!wfRunExt.get_links().self.href.matches("^https?://.*$")) {
      wfRunExt.get_links().self.setHref(joinPaths(rootUrl, wfRunExt.get_links().self.href));
    }
    for (StageNodeExt stage : wfRunExt.getStages()) {
      FlowNodeExt.FlowNodeLinks links = stage.get_links();
      if (!links.self.href.matches("^https?://.*$")) {
        links.self.setHref(joinPaths(rootUrl, links.self.href));
      }
      if (links.getLog() != null && !links.getLog().href.matches("^https?://.*$")) {
        links.getLog().setHref(joinPaths(rootUrl, links.getLog().href));
      }
      for (AtomFlowNodeExt node : stage.getStageFlowNodes()) {
        FlowNodeExt.FlowNodeLinks nodeLinks = node.get_links();
        if (!nodeLinks.self.href.matches("^https?://.*$")) {
          nodeLinks.self.setHref(joinPaths(rootUrl, nodeLinks.self.href));
        }
        if (nodeLinks.getLog() != null && !nodeLinks.getLog().href.matches("^https?://.*$")) {
          nodeLinks.getLog().setHref(joinPaths(rootUrl, nodeLinks.getLog().href));
        }
      }
    }

    String json;
    try {
      json = new ObjectMapper().writeValueAsString(wfRunExt);
    } catch (JsonProcessingException e) {
      logger.log(SEVERE, "Failed to serialize workflow run. " + e, e);
      return;
    }

    String phase = runToBuildPhase(run);

    long started = getStartTime(run);
    String startTime = null;
    String completionTime = null;
    if (started > 0) {
      startTime = formatTimestamp(started);

      long duration = getDuration(run);
      if (duration > 0) {
        completionTime = formatTimestamp(started + duration);
      }
    }

    logger.log(FINE, "Patching build {0}/{1}: setting phase to {2}", new Object[]{cause.getNamespace(), cause.getName(), phase});
    getOpenShiftClient().builds().inNamespace(cause.getNamespace()).withName(cause.getName()).edit()
      .editMetadata()
      .addToAnnotations(OPENSHIFT_ANNOTATIONS_JENKINS_STATUS_JSON, json)
      .addToAnnotations(OPENSHIFT_ANNOTATIONS_JENKINS_BUILD_URI, buildUrl)
      .addToAnnotations(OPENSHIFT_ANNOTATIONS_JENKINS_LOG_URL, logsUrl)
      .endMetadata()
      .editStatus()
      .withPhase(phase)
      .withStartTimestamp(startTime)
      .withCompletionTimestamp(completionTime)
      .endStatus()
      .done();
  }

  private long getStartTime(Run run) {
    return run.getStartTimeInMillis();
  }

  private long getDuration(Run run) {
    return run.getDuration();
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
    return run instanceof WorkflowRun && run.getCause(BuildCause.class) != null;
  }
}
