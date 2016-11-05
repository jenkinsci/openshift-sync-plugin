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
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import io.fabric8.openshift.api.model.BuildConfig;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;

/**
 * Stores the OpenShift Build Config related project properties.
 *
 * - Namespace
 * - Build config name
 * - Build config uid
 */
public class BuildConfigProjectProperty extends JobProperty<Job<?, ?>> {

  // The build config uid this job relates to.
  private String uid;

  private String namespace;

  private String name;

  private String resourceVersion;


  @DataBoundConstructor
  public BuildConfigProjectProperty(String namespace, String name, String uid, String resourceVersion) {
    this.namespace = namespace;
    this.name = name;
    this.uid = uid;
    this.resourceVersion = resourceVersion;
  }

  public BuildConfigProjectProperty(BuildConfig bc) {
    this(
      bc.getMetadata().getNamespace(),
      bc.getMetadata().getName(),
      bc.getMetadata().getUid(),
      bc.getMetadata().getResourceVersion(),
    );
  }

  public String getUid() {
    return uid;
  }

  public BuildConfig getBuildConfig() {
    BuildConfig bc = getOpenShiftClient().buildConfigs().inNamespace(namespace).withName(name).get();
    if (bc != null && bc.getMetadata().getUid().equals(uid)) {
      return bc;
    }
    return null;
  }

  public String getName() {
    return name;
  }

  public String getNamespace() {
    return namespace;
  }

  public String getResourceVersion() {
    return resourceVersion;
  }

  public void setResourceVersion(String resourceVersion) {
    this.resourceVersion = resourceVersion;
  }

  public String getContextDir() {
    return contextDir;
  }

  @Extension
  public static final class DescriptorImpl extends JobPropertyDescriptor {
    public boolean isApplicable(Class<? extends Job> jobType) {
      return WorkflowJob.class.isAssignableFrom(jobType);
    }
  }
}
