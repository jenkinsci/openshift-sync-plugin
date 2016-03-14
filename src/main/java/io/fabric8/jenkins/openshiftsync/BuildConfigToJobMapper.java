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
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

public class BuildConfigToJobMapper {

  public static Job<WorkflowJob, WorkflowRun> mapBuildConfigToJob(BuildConfig bc, String defaultNamespace) {
    GitSCM scm = new GitSCM(bc.getSpec().getSource().getGit().getUri());

    FlowDefinition flowDefinition = new CpsScmFlowDefinition(scm, "Jenkinsfile");

    WorkflowJob job = new WorkflowJob(Jenkins.getInstance(), jobName(bc, defaultNamespace));
    job.setDefinition(flowDefinition);

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

}
