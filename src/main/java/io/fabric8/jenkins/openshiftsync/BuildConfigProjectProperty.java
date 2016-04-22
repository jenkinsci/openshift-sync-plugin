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
import jenkins.model.ParameterizedJobMixIn;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.xstream2;

/**
 * Stores the OpenShift Build Config related project properties.
 *
 * - Namespace
 * - Build config name
 * - Build config uid
 */
public class BuildConfigProjectProperty extends JobProperty<Job<?, ?>> {

  // The build config this job relates to.
  private BuildConfig buildConfig;

  @DataBoundConstructor
  public BuildConfigProjectProperty(String buildConfigAsXML) {
    if (StringUtils.isNotBlank(buildConfigAsXML)) {
      this.buildConfig = (BuildConfig) xstream2().fromXML(buildConfigAsXML);
    }
  }

  public BuildConfigProjectProperty(BuildConfig buildConfig) {
    this.buildConfig = buildConfig;
  }

  public BuildConfig getBuildConfig() {
    return buildConfig;
  }

  public String getBuildConfigAsXML() {
    if (buildConfig == null) {
      return null;
    }
    return xstream2().toXML(buildConfig);
  }

  @Extension
  public static final class DescriptorImpl extends JobPropertyDescriptor {
    public static final String OPENSHIFT_BUILDCONFIG_BLOCK_NAME = "openshiftBuildConfig";

    public boolean isApplicable(Class<? extends Job> jobType) {
      return ParameterizedJobMixIn.ParameterizedJob.class.isAssignableFrom(jobType);
    }
  }
}
