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
import hudson.model.Run;
import hudson.model.TopLevelItem;
import io.fabric8.openshift.api.model.Build;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.logging.Logger;

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
}
