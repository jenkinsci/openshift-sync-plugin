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

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BuildTrigger extends Trigger<Job<?, ?>> {
  @Extension
  public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
  private static final Logger logger = Logger.getLogger(BuildTrigger.class.getName());

  private transient BuildConfigProjectProperty buildConfigProjectProperty;

  @DataBoundConstructor
  public BuildTrigger() {
  }

  @Override
  public void start(Job<?, ?> job, boolean newInstance) {
    super.start(job, newInstance);

    this.buildConfigProjectProperty = job.getProperty(BuildConfigProjectProperty.class);
    if (this.buildConfigProjectProperty == null) {
      return;
    }

    DESCRIPTOR.addBuildConfigTrigger(buildConfigProjectProperty.getBuildConfigUid(), super.job);
  }

  @Override
  public void stop() {
    String name = super.job != null ? super.job.getFullName() : "NOT STARTED";
    logger.log(Level.INFO, "Stopping the OpenShift Build trigger for project {0}", name);
    if (super.job != null) {
      this.buildConfigProjectProperty = super.job.getProperty(BuildConfigProjectProperty.class);
      if (this.buildConfigProjectProperty != null) {
        String buildConfigUid = this.buildConfigProjectProperty.getBuildConfigUid();
        if (!StringUtils.isEmpty(buildConfigUid)) {
          DESCRIPTOR.removeBuildConfigTrigger(buildConfigUid, super.job);
        }
      }
    }
    super.stop();
  }

  @Override
  public void run() {
    logger.log(Level.FINE, "Using Build watch as trigger, so not running trigger");
    return;
  }

  public static DescriptorImpl getDscp() {
    return DESCRIPTOR;
  }

  public static final class DescriptorImpl extends TriggerDescriptor {
    /**
     * map of jobs (by the build config uid);  No need to keep the projects from shutdown to startup.
     * New triggers will register here, and ones that are stopping will remove themselves.
     */
    private transient Map<String, Job> buildConfigJobs;

    public DescriptorImpl() {
      load();
      if (buildConfigJobs == null) {
        buildConfigJobs = new ConcurrentHashMap<>();
      }
      save();
    }

    @Override
    public boolean isApplicable(Item item) {
      return item instanceof Job;
    }

    @Override
    public String getDisplayName() {
      return "OpenShift Jenkins Pipeline Builder";
    }

    private void addBuildConfigTrigger(String buildConfigUid, Job job) {
      if (job == null || StringUtils.isEmpty(buildConfigUid)) {
        return;
      }
      logger.log(Level.FINE, "Adding [{0}] to build config [{1}]", new String[]{job.getFullName(), buildConfigUid});

      buildConfigJobs.put(buildConfigUid, job);
    }

    private void removeBuildConfigTrigger(String buildConfigUid, Job job) {
      logger.log(Level.FINE, "Removing [{0}] from build config [{1}]", new Object[]{job.getFullName(), buildConfigUid});
      buildConfigJobs.remove(buildConfigUid);
    }

    public Job getJobFromBuildConfigUid(String buildConfigUid) {
      logger.log(Level.FINE, "Retrieving triggers for build config [{0}]", new String[]{buildConfigUid});

      Job job = buildConfigJobs.get(buildConfigUid);
      if (job != null) {
        logger.log(Level.FINE, "Found project [{0}] for build config uid [{1}]", new Object[]{job.getFullName(), buildConfigUid});
      }

      return job;
    }
  }
}
