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

import com.thoughtworks.xstream.annotations.XStreamOmitField;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Constants.ANNOTATION_JENKINS_JOB_URI;

/**
 * Listens to {@link WorkflowJob} objects being updated via the web console or Jenkins REST API and replicating
 * the changes back to the OpenShift {@link BuildConfig} for the case where folks edit inline Jenkinsfile flows
 * inside the Jenkins UI
 */
@Extension
public class PipelineJobListener extends ItemListener {
  @XStreamOmitField
  private final Logger logger = Logger.getLogger(getClass().getName());

  private String server;
  private String defaultNamespace;

  @XStreamOmitField
  private OpenShiftClient openShiftClient;

  public PipelineJobListener() {
    init();
  }

  @DataBoundConstructor
  public PipelineJobListener(String server, String defaultNamespace) {
    this.server = server;
    this.defaultNamespace = defaultNamespace;
    init();
  }

  private void init() {
    openShiftClient = OpenShiftUtils.createOpenShiftClient(server);
    defaultNamespace = OpenShiftUtils.getNamespaceOrUseDefault(defaultNamespace,openShiftClient);
  }

  @Override
  public void onCreated(Item item) {
    super.onCreated(item);
    upsertItem(item);
  }

  @Override
  public void onUpdated(Item item) {
    super.onUpdated(item);
    upsertItem(item);
  }

  @Override
  public void onDeleted(Item item) {
    super.onDeleted(item);
    if (item instanceof WorkflowJob) {
      WorkflowJob job = (WorkflowJob) item;
      NamespaceName buildName = OpenShiftUtils.buildConfigNameFromJenkinsJobName(job.getName(), defaultNamespace);
      logger.info("Deleting BuildConfig " + buildName);

      String namespace =  buildName.getNamespace();
      String buildConfigName = buildName.getName();
      try {
        openShiftClient.buildConfigs().inNamespace(namespace).withName(buildConfigName).delete();
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to delete BuildConfig in namespace: " + namespace + " for name: " + buildConfigName);
      }
    }
  }

  public void upsertItem(Item item) {
    if (item instanceof WorkflowJob) {
      WorkflowJob job = (WorkflowJob) item;
      if (!BuildConfigWatcher.isOpenShiftUpdatingJob()) {
        logger.info("Updated WorkflowJob " + job.getDisplayName() + " replicating changes to OpenShift");
        upsertBuildConfigForJob(job);
      }
    }
  }

  private void upsertBuildConfigForJob(WorkflowJob job) {
    NamespaceName buildName = OpenShiftUtils.buildConfigNameFromJenkinsJobName(job.getName(), defaultNamespace);
    String namespace =  buildName.getNamespace();
    String buildConfigName = buildName.getName();
    BuildConfig buildConfig = null;
    try {
      buildConfig = openShiftClient.buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to find BuildConfig in namespace: " + namespace + " for name: " + buildConfigName);
    }

    boolean create = false;
    if (buildConfig == null) {
      create = true;

      buildConfig = new BuildConfigBuilder().
         withNewMetadata().
         withName(buildConfigName).
         withNamespace(namespace).
         addToAnnotations(ANNOTATION_JENKINS_JOB_URI, job.getUrl()).
         endMetadata().
         withNewSpec().
         withNewStrategy().
         withType("JenkinsPipeline").
         withNewJenkinsPipelineStrategy().
         endJenkinsPipelineStrategy().
         endStrategy().
         endSpec().
         build();
    }
    if (BuildConfigToJobMapper.updateBuildConfigFromJob(job, buildConfig)) {
      if (create) {
        logger.warning("Creating BuildConfig in namespace: " + namespace + " with name: " + buildConfigName);
        try {
          openShiftClient.buildConfigs().inNamespace(namespace).withName(buildConfigName).create(buildConfig);
        } catch (Exception e) {
          logger.log(Level.WARNING, "Failed to create BuildConfig: " + NamespaceName.create(buildConfig) + ". " + e, e);
        }
      } else {
        logger.warning("Updating BuildConfig in namespace: " + namespace + " with name: " + buildConfigName);
        try {
          openShiftClient.buildConfigs().inNamespace(namespace).withName(buildConfigName).replace(buildConfig);
        } catch (Exception e) {
          logger.log(Level.WARNING, "Failed to update BuildConfig: " + NamespaceName.create(buildConfig) + ". " + e, e);
        }
      }
    }
  }
}
