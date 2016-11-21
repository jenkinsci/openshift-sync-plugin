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
import hudson.BulkChange;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.util.XStream2;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;
import org.apache.tools.ant.filters.StringInputStream;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.mapBuildConfigToFlow;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.maybeScheduleNext;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isJenkinsBuildConfig;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobDisplayName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.parseResourceVersion;
import static java.net.HttpURLConnection.HTTP_GONE;

/**
 * Watches {@link BuildConfig} objects in OpenShift and for WorkflowJobs we ensure there is a
 * suitable Jenkins Job object defined with the correct configuration
 */
public class BuildConfigWatcher implements Watcher<BuildConfig> {
  private final Logger logger = Logger.getLogger(getClass().getName());
  private final String namespace;
  private Watch buildConfigWatch;

  public BuildConfigWatcher(String namespace) {
    this.namespace = namespace;
  }

  public void start(final Callable<Void> completionCallback) {
    final BuildConfigList buildConfigs;
    if (namespace != null && !namespace.isEmpty()) {
      buildConfigs = getOpenShiftClient().buildConfigs().inNamespace(namespace).list();
      buildConfigWatch = getOpenShiftClient().buildConfigs().inNamespace(namespace).withResourceVersion(buildConfigs.getMetadata().getResourceVersion()).watch(this);
    } else {
      buildConfigs = getOpenShiftClient().buildConfigs().inAnyNamespace().list();
      buildConfigWatch = getOpenShiftClient().buildConfigs().withResourceVersion(buildConfigs.getMetadata().getResourceVersion()).watch(this);
    }

    // lets process the initial state
    logger.info("Now handling startup build configs!!");
    // lets do this in a background thread to avoid errors like:
    //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
    Runnable task = new SafeTimerTask() {
      @Override
      public void doRun() {
        logger.info("loading initial BuildConfigs resources");

        try {
          onInitialBuildConfigs(buildConfigs);
          logger.info("loaded initial BuildConfigs resources");
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Failed to load initial BuildConfigs: " + e, e);
        }

        if (completionCallback != null) {
          Timer.get().schedule(completionCallback, 100, TimeUnit.MILLISECONDS);
        }
      }
    };
    // lets give jenkins a while to get started ;)
    Timer.get().schedule(task, 1, TimeUnit.SECONDS);
  }

  public void stop() {
    if (buildConfigWatch != null) {
      buildConfigWatch.close();
      buildConfigWatch = null;
    }
  }

  @Override
  public void onClose(KubernetesClientException e) {
    if (e != null) {
      logger.warning(e.toString());

      if (e.getStatus() != null && e.getStatus().getCode() == HTTP_GONE) {
        stop();
        start(null);
      }
    }
  }

  private void onInitialBuildConfigs(BuildConfigList buildConfigs) {
    List<BuildConfig> items = buildConfigs.getItems();
    if (items != null) {
      for (BuildConfig buildConfig : items) {
        try {
          upsertJob(buildConfig);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }

  @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
  @Override
  public void eventReceived(Watcher.Action action, BuildConfig buildConfig) {
    try {
      switch (action) {
        case ADDED:
          upsertJob(buildConfig);
          break;
        case DELETED:
          deleteJob(buildConfig);
          break;
        case MODIFIED:
          modifyJob(buildConfig);
          break;
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Caught: " + e, e);
    }
  }

  private void upsertJob(final BuildConfig buildConfig) throws Exception {
    if (isJenkinsBuildConfig(buildConfig)) {
      ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
        @Override
        public Void call() throws Exception {
          String jobName = jenkinsJobName(buildConfig);
          WorkflowJob job = BuildTrigger.getDscp().getJobFromBuildConfigUid(buildConfig.getMetadata().getUid());
          boolean newJob = job == null;
          if (newJob) {
            job = new WorkflowJob(Jenkins.getActiveInstance(), jobName);
          }
          BulkChange bk = new BulkChange(job);

          job.setDisplayName(jenkinsJobDisplayName(buildConfig));

          FlowDefinition flowFromBuildConfig = mapBuildConfigToFlow(buildConfig);
          if (flowFromBuildConfig == null) {
            return null;
          }

          job.setDefinition(flowFromBuildConfig);

          BuildTrigger trigger = new BuildTrigger();
          if (!job.getTriggers().containsKey(trigger.getDescriptor())) {
            job.addTrigger(trigger);
          }

          String existingBuildRunPolicy = null;

          BuildConfigProjectProperty buildConfigProjectProperty = job.getProperty(BuildConfigProjectProperty.class);
          if (buildConfigProjectProperty != null) {
            existingBuildRunPolicy = buildConfigProjectProperty.getBuildRunPolicy();
            long updatedBCResourceVersion = parseResourceVersion(buildConfig);
            long oldBCResourceVersion = parseResourceVersion(buildConfigProjectProperty.getResourceVersion());
            BuildConfigProjectProperty newProperty = new BuildConfigProjectProperty(buildConfig);
            if (updatedBCResourceVersion <= oldBCResourceVersion &&
              newProperty.getUid().equals(buildConfigProjectProperty.getUid()) &&
              newProperty.getNamespace().equals(buildConfigProjectProperty.getNamespace()) &&
              newProperty.getName().equals(buildConfigProjectProperty.getName()) &&
              newProperty.getBuildRunPolicy().equals(buildConfigProjectProperty.getBuildRunPolicy())
              ) {
              return null;
            }
            buildConfigProjectProperty.setUid(newProperty.getUid());
            buildConfigProjectProperty.setNamespace(newProperty.getNamespace());
            buildConfigProjectProperty.setName(newProperty.getName());
            buildConfigProjectProperty.setResourceVersion(newProperty.getResourceVersion());
            buildConfigProjectProperty.setBuildRunPolicy(newProperty.getBuildRunPolicy());
          } else {
            job.addProperty(
              new BuildConfigProjectProperty(buildConfig)
            );
          }

          job.setConcurrentBuild(
            !(buildConfig.getSpec().getRunPolicy().equals(SERIAL) ||
              buildConfig.getSpec().getRunPolicy().equals(SERIAL_LATEST_ONLY))
          );

          InputStream jobStream = new StringInputStream(new XStream2().toXML(job));

          if (newJob) {
            Jenkins.getActiveInstance().createProjectFromXML(
              jobName,
              jobStream
            ).save();
            logger.info("Created job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
          } else {
            Source source = new StreamSource(jobStream);
            job.updateByXml(source);
            job.save();
            logger.info("Updated job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
            if (existingBuildRunPolicy != null && !existingBuildRunPolicy.equals(buildConfigProjectProperty.getBuildRunPolicy())) {
              maybeScheduleNext(job);
            }
          }
          bk.commit();
          return null;
        }
      });
    }
  }

  private void modifyJob(BuildConfig buildConfig) throws Exception {
    if (isJenkinsBuildConfig(buildConfig)) {
      upsertJob(buildConfig);
      return;
    }

    // no longer a Jenkins build so lets delete it if it exists
    deleteJob(buildConfig);
  }

  private void deleteJob(final BuildConfig buildConfig) throws Exception {
    final Job job = BuildTrigger.getDscp().getJobFromBuildConfigUid(buildConfig.getMetadata().getUid());
    if (job != null) {
      ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
        @Override
        public Void call() throws Exception {
          job.delete();
          Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
          return null;
        }
      });
    }
  }
}
