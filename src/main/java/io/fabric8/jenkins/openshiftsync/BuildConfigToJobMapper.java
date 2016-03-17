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
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildStrategy;
import io.fabric8.openshift.api.model.JenkinsPipelineBuildStrategy;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.logging.Logger;

public class BuildConfigToJobMapper {
  public static final String JENKINS_PIPELINE_BUILD_STRATEGY = "JenkinsPipeline";
  public static final String DEFAULT_JENKINS_FILEPATH = "Jenkinsfile";
  private static final Logger LOGGER = Logger.getLogger(BuildConfigToJobMapper.class.getName());

  public static Job<WorkflowJob, WorkflowRun> mapBuildConfigToJob(BuildConfig bc, String defaultNamespace) {
    if (!OpenShiftUtils.isJenkinsBuildConfig(bc)) {
      return null;
    }

    WorkflowJob job = new WorkflowJob(Jenkins.getInstance(), OpenShiftUtils.jenkinsJobName(bc, defaultNamespace));

    BuildConfigSpec spec = bc.getSpec();
    BuildSource source = null;
    String jenkinsfile = null;
    String jenkinsfilePath = null;
    if (spec != null) {
      source = spec.getSource();
      BuildStrategy strategy = spec.getStrategy();
      if (strategy != null) {
        JenkinsPipelineBuildStrategy jenkinsPipelineStrategy = strategy.getJenkinsPipelineStrategy();
        if (jenkinsPipelineStrategy != null) {
          jenkinsfile = jenkinsPipelineStrategy.getJenkinsfile();
          jenkinsfilePath = jenkinsPipelineStrategy.getJenkinsfilePath();
        }
      }
    }
    if (jenkinsfile == null) {
      // Is this a Jenkinsfile from Git SCM?
      if (source != null &&
        source.getGit() != null &&
        source.getGit().getUri() != null) {
        if (jenkinsfilePath == null) {
          jenkinsfilePath = DEFAULT_JENKINS_FILEPATH;
        }
        GitSCM scm = new GitSCM(source.getGit().getUri());
        job.setDefinition(new CpsScmFlowDefinition(scm, jenkinsfilePath));
      } else {
        LOGGER.warning("BuildConfig does not contain source repository information - cannot map BuildConfig to Jenkins job");
        return null;
      }
    } else {
      job.setDefinition(new CpsFlowDefinition(jenkinsfile));
    }
    return job;
  }

}
