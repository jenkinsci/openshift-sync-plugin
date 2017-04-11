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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.api.model.BuildConfig;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.updateBuildConfigFromJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static org.apache.commons.lang.StringUtils.isNotBlank;

/**
 * Listens to {@link WorkflowJob} objects being updated via the web console or Jenkins REST API and replicating
 * the changes back to the OpenShift {@link BuildConfig} for the case where folks edit inline Jenkinsfile flows
 * inside the Jenkins UI
 */
@Extension
public class PipelineJobListener extends ItemListener {
  private static final Logger logger = Logger.getLogger(PipelineJobListener.class.getName());

  private String server;
  private String[] namespaces;

  public PipelineJobListener() {
    init();
  }

  @DataBoundConstructor
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public PipelineJobListener(String server, String[] namespaces) {
    this.server = server;
    this.namespaces = namespaces;
    init();
  }

  private void init() {
    namespaces = OpenShiftUtils.getNamespaceOrUseDefault(namespaces, getOpenShiftClient());
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
      if (job.getProperty(BuildConfigProjectProperty.class) != null
        && isNotBlank(job.getProperty(BuildConfigProjectProperty.class).getNamespace())
        && isNotBlank(job.getProperty(BuildConfigProjectProperty.class).getName())) {

        NamespaceName buildName = OpenShiftUtils.buildConfigNameFromJenkinsJobName(job.getName(), job.getProperty(BuildConfigProjectProperty.class).getNamespace());
        logger.info("Deleting BuildConfig " + buildName);

        String namespace = buildName.getNamespace();
        String buildConfigName = buildName.getName();
        BuildConfig buildConfig = getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
        if (buildConfig != null) {
          try {
            getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).delete();
          } catch (KubernetesClientException e) {
            if (HTTP_NOT_FOUND != e.getCode()) {
              logger.log(Level.WARNING, "Failed to delete BuildConfig in namespace: " + namespace + " for name: " + buildConfigName, e);
            }
          } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to delete BuildConfig in namespace: " + namespace + " for name: " + buildConfigName, e);
          } finally {
            removeJobWithBuildConfig(buildConfig);
          }
        }
      }
    }
  }

  public void upsertItem(Item item) {
    if (item instanceof WorkflowJob) {
      WorkflowJob job = (WorkflowJob) item;
      if (job.getProperty(BuildConfigProjectProperty.class) != null
        && StringUtils.isNotBlank(job.getProperty(BuildConfigProjectProperty.class).getNamespace())
        && StringUtils.isNotBlank(job.getProperty(BuildConfigProjectProperty.class).getName())) {
        logger.info("Updated WorkflowJob " + job.getDisplayName() + " replicating changes to OpenShift");
        upsertBuildConfigForJob(job);
      }
    }
  }

  // TODO handle syncing created jobs back to a new OpenShift BuildConfig
  private void upsertBuildConfigForJob(WorkflowJob job) {
    BuildConfigProjectProperty buildConfigProjectProperty = job.getProperty(BuildConfigProjectProperty.class);
    if (buildConfigProjectProperty == null || buildConfigProjectProperty.getNamespace() == null || buildConfigProjectProperty.getName() == null || buildConfigProjectProperty.getUid() == null) {
      return;
    }

    BuildConfig jobBuildConfig = buildConfigProjectProperty.getBuildConfig();
    if (jobBuildConfig == null) {
      logger.log(Level.WARNING, "Failed to find BuildConfig in namespace: " + buildConfigProjectProperty.getNamespace() + " for name: " + buildConfigProjectProperty.getName());
      return;
    }
    updateBuildConfigFromJob(job, jobBuildConfig);

    try {
      getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(jobBuildConfig.getMetadata().getNamespace()).withName(jobBuildConfig.getMetadata().getName()).cascading(false).replace(jobBuildConfig);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to update BuildConfig: " + NamespaceName.create(jobBuildConfig) + ". " + e, e);
    }
  }
}
