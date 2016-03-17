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
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;

import java.util.Map;

import static io.fabric8.jenkins.openshiftsync.Constants.ANNOTATION_JENKINS_BUILD_URL;

/**
 */
public class OpenShiftUtils {

  /**
   * Returns a newly created client for OpenShift from the given optional server URL
   */
  public static OpenShiftClient createOpenShiftClient(String serverUrl) {
    OpenShiftConfigBuilder configBuilder = new OpenShiftConfigBuilder();
    if (serverUrl != null && !serverUrl.isEmpty()) {
      configBuilder.withMasterUrl(serverUrl);
    }
    Config config = configBuilder.build();
    return new DefaultOpenShiftClient(config);
  }

  /**
   * Returns true if this is an OpenShift BuildConfig which should be mirrored to
   * a Jenkins Job
   */
  public static boolean isJenkinsBuildConfig(BuildConfig bc) {
    if (BuildConfigToJobMapper.EXTERNAL_BUILD_STRATEGY.equalsIgnoreCase(bc.getSpec().getStrategy().getType()) &&
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

  /**
   * Returns true if this OpenShift Build was created by Jenkins (rather than created by OpenShift
   * to trigger a new external build)
   */
  public static boolean isCreatedByJenkins(Build build) {
    ObjectMeta metadata = build.getMetadata();
    if (metadata != null) {
      Map<String, String> annotations = metadata.getAnnotations();
      if (annotations != null) {
        if (annotations.get(Constants.ANNOTATION_JENKINS_BUILD_URL) != null) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns the jenkins job name for the given BuildConfig and default namespace
   */
  public static String jenkinsJobName(BuildConfig bc, String defaultNamespace) {
    String namespace = bc.getMetadata().getNamespace();
    String name = bc.getMetadata().getName();
    return jenkinsJobName(namespace, name, defaultNamespace);
  }

  /**
   * Returns the jenkins job name for the given namespace and build config name and default namesapce
   */
  public static String jenkinsJobName(String namespace, String buildConfigName, String defaultNamespace) {
    if (namespace == null || namespace.length() == 0 || namespace.equals(defaultNamespace)) {
      return buildConfigName;
    }
    return namespace + "-" + buildConfigName;
  }

  /**
   * Returns the jenkins job name for the given build and the default namespace
   * by finding the BuildConfig name on the Build via labels
   */
  public static String jenkinsJobName(Build build, String defaultNamespace) {
    String namespace = null;
    String buildConfigName = null;
    ObjectMeta metadata = build.getMetadata();
    if (metadata != null) {
      namespace = metadata.getNamespace();
      Map<String, String> labels = metadata.getLabels();
      if (labels != null) {
        buildConfigName = labels.get(Constants.LABEL_BUILDCONFIG);
        if (buildConfigName == null || buildConfigName.length() == 0) {
          buildConfigName = labels.get(Constants.LABEL_OPENSHIFT_BUILD_CONFIG_NAME);
        }
      }
    }
    if (buildConfigName != null) {
      return jenkinsJobName(namespace, buildConfigName, defaultNamespace);
    }
    return null;
  }

  public static String getNamespaceOrUseDefault(String currentNamepace, OpenShiftClient client) {
    String namespace = currentNamepace;
    if (namespace == null || namespace.isEmpty()) {
      namespace = client.getNamespace();
      if (namespace == null || namespace.isEmpty()) {
        namespace = "default";
      }
    }
    return namespace;
  }

  /**
   * Returns true if this OpenShift Build maps to the given Jenkins job build
   */
  public static boolean openShiftBuildMapsToJenkinsBuild(BuildName buildName, Build build, String url) {
    ObjectMeta metadata = build.getMetadata();
    if (metadata != null) {
      Map<String, String> annotations = metadata.getAnnotations();
      if (annotations != null) {
        String anotherUrl = annotations.get(ANNOTATION_JENKINS_BUILD_URL);
        if (anotherUrl != null && anotherUrl.equals(url)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Return true if this build was created by OpenShift and maps to a Jenkins
   * based BuildConfig but has not yet been updated by Jenkins to associate to a build Run
   */
  public static boolean isJenkinsBuildCreatedByOpenShift(Build build, String defaultNamespace) {
    ObjectMeta metadata = build.getMetadata();
    if (metadata != null) {
      Map<String, String> annotations = metadata.getAnnotations();
      if (annotations != null) {
        String anotherUrl = annotations.get(ANNOTATION_JENKINS_BUILD_URL);
        if (anotherUrl == null || anotherUrl.length() == 0) {
          // lets get the BuildCOnfig name and check that maps to a Jenkins job
          String jobName = OpenShiftUtils.jenkinsJobName(build, defaultNamespace);
          if (jobName != null && !jobName.isEmpty()) {
            Job job = JenkinsUtils.getJob(jobName);
            if (job != null) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }
}
