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

import com.cloudbees.workflow.rest.external.RunExt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.fabric8.openshift.api.model.Build;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Constants.ANNOTATION_JENKINS_BUILD_URI;
import static io.fabric8.jenkins.openshiftsync.Constants.ANNOTATION_JENKINS_LOG_URL;
import static io.fabric8.jenkins.openshiftsync.Constants.ANNOTATION_JENKINS_STATUS_JSON;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.formatTimestamp;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;

/**
 * Listens to Jenkins Job build {@link Run} start and stop then ensure there's a suitable {@link Build} object in
 * OpenShift thats updated correctly with the current status, logsURL and metrics
 */
@Extension
public class BuildSyncRunListener extends RunListener<Run> {

  private static final Logger logger = Logger.getLogger(BuildSyncRunListener.class.getName());

  private long pollPeriodMs = 2000;
  private String defaultNamespace;

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

  private void init() {
    defaultNamespace = OpenShiftUtils.getNamespaceOrUseDefault(defaultNamespace, getOpenShiftClient());
  }

  @Override
  public void onStarted(Run run, TaskListener listener) {
    if (shouldPollRun(run)) {
      try {
        BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
        if (cause != null && cause.getBuild() != null) {
          // TODO This should be a link to the OpenShift console.
          run.setDescription(
            cause.getBuild().getMetadata().getNamespace() + "/" + cause.getBuild().getMetadata().getName()
          );
        }
      } catch (IOException e) {
        logger.log(Level.WARNING, "Cannot set build description: " + e);
      }
      checkTimerStarted();
      if (runsToPoll.add(run)) {
        logger.info("starting polling build " + run.getUrl());
      }
    } else {
      logger.fine("not polling polling build " + run.getUrl() + " as its not a WorkflowJob");
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
    runsToPoll.remove(run);
    pollRun(run);
    logger.info("onCompleted " + run.getUrl());
    super.onCompleted(run, listener);
  }

  @Override
  public void onDeleted(Run run) {
    runsToPoll.remove(run);
    pollRun(run);
    logger.info("onDeleted " + run.getUrl());
    super.onDeleted(run);

    // TODO should we remove the OpenShift Build too?
  }

  @Override
  public void onFinalized(Run run) {
    pollRun(run);
    logger.info("onFinalized " + run.getUrl());
    super.onFinalized(run);
  }

  protected void pollLoop() {
    for (Run run : runsToPoll) {
      pollRun(run);
    }
  }

  protected void pollRun(Run run) {
    if (!(run instanceof WorkflowRun)) {
      throw new IllegalStateException("Cannot poll a non-workflow run");
    }

    RunExt wfRunExt = RunExt.create((WorkflowRun) run);

    try {
      String json = new ObjectMapper().writeValueAsString(wfRunExt);
      upsertBuild(run, json);
    } catch (JsonProcessingException e) {
      logger.log(Level.WARNING, "Failed to serialize workflow run. " + e, e);
    }
  }

  private void upsertBuild(Run run, String json) {
    if (run == null) {
      return;
    }

    BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
    if (cause == null || cause.getBuild() == null) {
      return;
    }

    final Build build = cause.getBuild();

    String rootUrl = OpenShiftUtils.getJenkinsURL(getOpenShiftClient(), defaultNamespace);
    String logsUrl = joinPaths(rootUrl, run.getUrl(), "/consoleText");

    String name = build.getMetadata().getName();

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

    if (logger.isLoggable(Level.FINE)) {
      logger.fine("generated build in namespace " + defaultNamespace + " with name: " + name + " phase: " + build.getStatus().getPhase() + " data: " + build);
    }

    logger.info("replacing build in namespace " + defaultNamespace + " with name: " + name + " phase: " + build.getStatus().getPhase());
    getOpenShiftClient().builds().inNamespace(defaultNamespace).withName(name).edit()
      .editMetadata()
        .addToAnnotations(ANNOTATION_JENKINS_STATUS_JSON, json)
        .addToAnnotations(ANNOTATION_JENKINS_BUILD_URI, run.getUrl())
        .addToAnnotations(ANNOTATION_JENKINS_LOG_URL, logsUrl)
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
