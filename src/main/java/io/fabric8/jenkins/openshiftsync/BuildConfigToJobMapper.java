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

import hudson.model.Job;
import hudson.plugins.git.GitSCM;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.Map;
import java.util.logging.Logger;

public class BuildConfigToJobMapper {
  private static final Logger LOGGER = Logger.getLogger(BuildConfigToJobMapper.class.getName());

  public static final String EXTERNAL_BUILD_STRATEGY = "External";
  public static final String DEFAULT_JENKINS_FILEPATH = "Jenkinsfile";

  public static Job<WorkflowJob, WorkflowRun> mapBuildConfigToJob(BuildConfig bc, String defaultNamespace) {
    if (!isJenkinsBuildConfig(bc)) {
      return null;
    }

    WorkflowJob job = new WorkflowJob(Jenkins.getInstance(), jobName(bc, defaultNamespace));

    // Is this a Jenkinsfile from Git SCM?
    if (bc.getSpec().getStrategy().getExternalStrategy().getJenkinsPipelineStrategy().getJenkinsfile() == null) {
      if (bc.getSpec().getSource() != null &&
      bc.getSpec().getSource().getGit() != null &&
      bc.getSpec().getSource().getGit().getUri() != null){
        String jenkinsfilePath = DEFAULT_JENKINS_FILEPATH;
        if (bc.getSpec().getStrategy().getExternalStrategy().getJenkinsPipelineStrategy().getJenkinsfilePath() != null) {
          jenkinsfilePath = bc.getSpec().getStrategy().getExternalStrategy().getJenkinsPipelineStrategy().getJenkinsfilePath();
        }
        GitSCM scm = new GitSCM(bc.getSpec().getSource().getGit().getUri());

        job.setDefinition(new CpsScmFlowDefinition(scm, jenkinsfilePath));
      } else {
        LOGGER.warning("BuildConfig does not contain source repository information - cannot map BuildConfig to Jenkins job");
        return null;
      }
    } else {
      job.setDefinition(new CpsFlowDefinition(bc.getSpec().getStrategy().getExternalStrategy().getJenkinsPipelineStrategy().getJenkinsfile()));
    }
    return job;
  }

  public static String jobName(BuildConfig bc, String defaultNamespace) {
    String namespace = bc.getMetadata().getNamespace();
    String name = bc.getMetadata().getName();
    if (namespace == null || namespace.length() == 0 || namespace.equals(defaultNamespace)) {
      return name;
    }
    return namespace + "-" + name;
  }

  static boolean isJenkinsBuildConfig(BuildConfig bc) {
    if (EXTERNAL_BUILD_STRATEGY.equalsIgnoreCase(bc.getSpec().getStrategy().getType()) &&
      bc.getSpec().getStrategy().getExternalStrategy() != null &&
      bc.getSpec().getStrategy().getExternalStrategy().getJenkinsPipelineStrategy() != null) {
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

}
