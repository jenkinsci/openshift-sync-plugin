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

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildBuilder;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildPhases.CANCELLED;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.PENDING;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.BuildWatcher.buildAdded;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_BUILD_STATUS_FIELD;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_BUILD_CONFIG_NAME;
import static io.fabric8.jenkins.openshiftsync.CredentialsUtils.updateSourceCredentials;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.updateOpenShiftBuildPhase;
import static org.apache.commons.lang.StringUtils.isBlank;

/**
 */
public class JenkinsUtils {

  private static final Logger LOGGER = Logger.getLogger(JenkinsUtils.class.getName());

  public static Job getJob(String job) {
    TopLevelItem item = Jenkins.getActiveInstance().getItem(job);
    if (item instanceof Job) {
      return (Job) item;
    }
    return null;
  }

  public static Run getRun(String jobName, String buildName) {
    Job job = getJob(jobName);
    if (job != null) {
      return job.getBuild(buildName);
    }
    return null;
  }

  public static Run getRun(BuildName buildName) {
    return getRun(buildName.getJobName(), buildName.getBuildName());
  }

  public static String getRootUrl() {
    // TODO is there a better place to find this?
    String root = Jenkins.getActiveInstance().getRootUrl();
    if (root == null || root.length() == 0) {
      root = "http://localhost:8080/";
    }
    return root;
  }

  public synchronized static void triggerJob(WorkflowJob job, Build build) throws IOException {
    String buildConfigName = build.getStatus().getConfig().getName();
    if (isBlank(buildConfigName)) {
      return;
    }

    BuildConfigProjectProperty bcProp = job.getProperty(BuildConfigProjectProperty.class);
    if (bcProp == null) {
      return;
    }

    switch (bcProp.getBuildRunPolicy()) {
      case SERIAL_LATEST_ONLY:
        cancelQueuedBuilds(job, bcProp.getUid());
        if (job.isBuilding()) {
          return;
        }
        break;
      case SERIAL:
        if (job.isInQueue() || job.isBuilding()) {
          return;
        }
        break;
      default:
    }

    BuildConfig buildConfig = getOpenShiftClient().buildConfigs().inNamespace(build.getMetadata().getNamespace()).withName(buildConfigName).get();
    if (buildConfig == null) {
      return;
    }

    updateSourceCredentials(buildConfig);

    if (job.scheduleBuild2(0, new CauseAction(new BuildCause(build, bcProp.getUid()))) != null) {
      updateOpenShiftBuildPhase(build, PENDING);
    }
  }

  public synchronized static void cancelBuild(WorkflowJob job, Build build) {
    if (!cancelQueuedBuild(job, build)) {
      cancelRunningBuild(job, build);
    }
    try {
      updateOpenShiftBuildPhase(build, CANCELLED);
    } catch (Exception e) {
      throw e;
    }
  }

  private static WorkflowRun getRun(WorkflowJob job, String buildUid) {
    for (WorkflowRun run : job.getBuilds()) {
      BuildCause cause = run.getCause(BuildCause.class);
      if (cause != null && cause.getUid().equals(buildUid)) {
        return run;
      }
    }
    return null;
  }

  private static boolean cancelRunningBuild(WorkflowJob job, Build build) {
    String buildUid = build.getMetadata().getUid();
    WorkflowRun run = getRun(job, buildUid);
    if (run != null && run.isBuilding()) {
      terminateRun(run);
      return true;
    }
    return false;
  }

  private static boolean cancelNotYetStartedBuild(WorkflowJob job, Build build) {
    String buildUid = build.getMetadata().getUid();
    WorkflowRun run = getRun(job, buildUid);
    if (run != null && run.hasntStartedYet()) {
      terminateRun(run);
      return true;
    }
    return false;
  }

  private static void cancelNotYetStartedBuilds(WorkflowJob job, String bcUid) {
    cancelQueuedBuilds(job, bcUid);
    for (WorkflowRun run : job.getBuilds()) {
      if (run != null && run.hasntStartedYet()) {
        BuildCause cause = run.getCause(BuildCause.class);
        if (cause != null && cause.getBuildConfigUid().equals(bcUid)) {
          terminateRun(run);
        }
      }
    }
  }

  private static void terminateRun(final WorkflowRun run) {
    run.doTerm();
    Timer.get().schedule(new Runnable() {
      @Override
      public void run() {
        run.doKill();
      }
    }, 5, TimeUnit.SECONDS);
  }

  public static boolean cancelQueuedBuild(WorkflowJob job, Build build) {
    String buildUid = build.getMetadata().getUid();
    Queue buildQueue = Jenkins.getActiveInstance().getQueue();
    for (Queue.Item item : buildQueue.getItems()) {
      for (Cause cause : item.getCauses()) {
        if (cause instanceof BuildCause && ((BuildCause) cause).getUid().equals(buildUid)) {
          buildQueue.cancel(item);
          return true;
        }
      }
    }
    return cancelNotYetStartedBuild(job, build);
  }

  public static void cancelQueuedBuilds(WorkflowJob job, String bcUid) {
    Queue buildQueue = Jenkins.getActiveInstance().getQueue();
    for (Queue.Item item : buildQueue.getItems()) {
      for (Cause cause : item.getCauses()) {
        if (cause instanceof BuildCause) {
          BuildCause buildCause = (BuildCause) cause;
          if (buildCause.getBuildConfigUid().equals(bcUid)) {
            Build build = new BuildBuilder()
              .withNewMetadata()
              .withNamespace(buildCause.getNamespace())
              .withName(buildCause.getName())
              .and().build();
            cancelQueuedBuild(job, build);
          }
        }
      }
    }
  }

  public static WorkflowJob getJobFromBuild(Build build) {
    String buildConfigName = build.getStatus().getConfig().getName();
    if (StringUtils.isEmpty(buildConfigName)) {
      return null;
    }
    BuildConfig buildConfig = getOpenShiftClient().buildConfigs().inNamespace(build.getMetadata().getNamespace()).withName(buildConfigName).get();
    if (buildConfig == null) {
      return null;
    }
    return BuildTrigger.DESCRIPTOR.getJobFromBuildConfigUid(buildConfig.getMetadata().getUid());
  }

  public static WorkflowJob getJobFromBuildConfigUid(String bcUid) {
    return BuildTrigger.DESCRIPTOR.getJobFromBuildConfigUid(bcUid);
  }

  public static void maybeScheduleNext(WorkflowJob job) {
    BuildConfigProjectProperty bcp = job.getProperty(BuildConfigProjectProperty.class);
    if (bcp == null) {
      return;
    }
    List<Build> builds = getOpenShiftClient().builds().inNamespace(bcp.getNamespace())
      .withField(OPENSHIFT_BUILD_STATUS_FIELD, BuildPhases.NEW).withLabel(OPENSHIFT_LABELS_BUILD_CONFIG_NAME, bcp.getName()).list().getItems();
    Collections.sort(builds, new Comparator<Build>() {
      @Override
      public int compare(Build b1, Build b2) {
        return Long.compare(
          Long.parseLong(b1.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)),
          Long.parseLong(b2.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER))
        );
      }
    });

    handleBuildList(job, builds, bcp);
  }

  public static void handleBuildList(WorkflowJob job, List<Build> builds, BuildConfigProjectProperty buildConfigProjectProperty) {
    if (builds.isEmpty()) {
      return;
    }
    boolean isSerialLatestOnly = SERIAL_LATEST_ONLY.equals(buildConfigProjectProperty.getBuildRunPolicy());
    if (isSerialLatestOnly) {
      cancelNotYetStartedBuilds(job, buildConfigProjectProperty.getUid());
    }
    for (int i = 0; i < builds.size(); i++) {
      Build b = builds.get(i);
      if (isSerialLatestOnly && i < builds.size() - 1) {
        cancelQueuedBuild(job, b);
        updateOpenShiftBuildPhase(b, CANCELLED);
        continue;
      }
      try {
        buildAdded(b);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
