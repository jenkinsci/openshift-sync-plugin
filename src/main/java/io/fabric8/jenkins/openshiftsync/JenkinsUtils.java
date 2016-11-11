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
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildBuilder;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.IOException;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.CredentialsUtils.updateSourceCredentials;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.cancelOpenShiftBuild;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
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
        cancelQueuedBuilds(bcProp.getUid());
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

    Cause cause = new BuildCause(build, bcProp.getUid());
    job.scheduleBuild(cause);
  }

  public synchronized static void cancelBuild(Job job, Build build) {
    boolean cancelledQueuedBuild = cancelQueuedBuild(build);
    if (!cancelledQueuedBuild) {
      cancelRunningBuild(job, build);
    }
    cancelOpenShiftBuild(build);
  }

  private static boolean cancelRunningBuild(Job job, Build build) {
    String buildUid = build.getMetadata().getUid();

    for (Object obj : job.getBuilds()) {
      if (obj instanceof WorkflowRun) {
        final WorkflowRun b = (WorkflowRun) obj;
        BuildCause cause = b.getCause(BuildCause.class);
        if (cause != null && cause.getUid().equals(buildUid)) {
          b.doTerm();
          return true;
        }
      }
    }

    return false;
  }

  public static boolean cancelQueuedBuild(Build build) {
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
    return false;
  }

  public static void cancelQueuedBuilds(String bcUid) {
    Queue buildQueue = Jenkins.getActiveInstance().getQueue();
    for (Queue.Item item : buildQueue.getItems()) {
      for (Cause cause : item.getCauses()) {
        if (cause instanceof BuildCause) {
          BuildCause buildCause = (BuildCause) cause;
          if (buildCause.getBuildConfigUid().equals(bcUid)) {
            if (buildQueue.cancel(item)) {
              cancelOpenShiftBuild(
                new BuildBuilder()
                  .withNewMetadata()
                  .withNamespace(buildCause.getNamespace())
                  .withName(buildCause.getName())
                  .and().build()
              );
            }
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
}
