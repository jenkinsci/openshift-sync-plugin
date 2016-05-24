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
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import hudson.util.XStream2;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.logging.Logger;

import static hudson.model.Result.ABORTED;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.cancelOpenShiftBuild;

/**
 */
public class JenkinsUtils {
  private final static Logger logger = Logger.getLogger(JenkinsUtils.class.getName());

  public static Job getJob(String job) {
    Jenkins jenkins = Jenkins.getInstance();
    if (jenkins != null) {
      TopLevelItem item = jenkins.getItem(job);
      if (item instanceof Job) {
        return (Job) item;
      }
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
    String root = null;
    Jenkins jenkins = Jenkins.getInstance();
    if (jenkins != null) {
      root = jenkins.getRootUrl();
    }
    if (root == null || root.length() == 0) {
      root = "http://localhost:8080/jenkins/";
    }
    return root;
  }

  public static void triggerJob(Job job, Build build) {
    Cause cause = new BuildCause(build);
    if (job instanceof WorkflowJob) {
      WorkflowJob workflowJob = (WorkflowJob) job;
      workflowJob.scheduleBuild(cause);
    }
  }

  public static void cancelBuild(Job job, Build build) {
    String buildUid = build.getMetadata().getUid();
    Jenkins jenkins = Jenkins.getInstance();
    if (jenkins != null) {
      Queue buildQueue = jenkins.getQueue();
      for (Queue.Item item : buildQueue.getItems()) {
        for (Cause cause : item.getCauses()) {
          if (cause instanceof BuildCause && ((BuildCause) cause).getBuild().getMetadata().getUid().equals(buildUid)) {
            buildQueue.cancel(item);
            cancelOpenShiftBuild(build);
            return;
          }
        }
      }
      for (Object obj : job.getNewBuilds()) {
        if (obj instanceof WorkflowRun) {
          WorkflowRun b = (WorkflowRun) obj;
          BuildCause cause = b.getCause(BuildCause.class);
          if (cause != null && cause.getBuild().getMetadata().getUid().equals(buildUid)) {
            Executor e = b.getExecutor();
            if (e != null) {
              e.interrupt(ABORTED);
              cancelOpenShiftBuild(build);
              return;
            }
          }
        }
      }
    }
  }

  public static XStream2 xstream2() {
    XStream2 xs = new XStream2();
    xs.aliasType("OpenShiftBuildConfig", BuildConfig.class);
    xs.addCompatibilityAlias(BuildConfig.class.getName(), BuildConfig.class);
    xs.addCompatibilityAlias("OpenShiftBuildConfig", BuildConfig.class);
    return xs;
  }
}
