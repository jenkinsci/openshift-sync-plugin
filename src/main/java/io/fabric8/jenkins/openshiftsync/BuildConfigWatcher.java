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
import hudson.model.Job;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import jenkins.model.Jenkins;
import org.apache.tools.ant.filters.StringInputStream;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.jobName;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.mapBuildConfigToJob;

public class BuildConfigWatcher implements Watcher<BuildConfig> {

  public static final String EXTERNAL_BUILD_STRATEGY = "External";
  private final Logger logger = Logger.getLogger(getClass().getName());
  private final String defaultNamespace;

  public BuildConfigWatcher(String defaultNamespace) {
    this.defaultNamespace = defaultNamespace;
  }

  @Override
  public void onClose(KubernetesClientException e) {
    if (e != null) {
      logger.warning(e.toString());
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  public void onInitialBuildConfigs(BuildConfigList buildConfigs) {
    List<BuildConfig> items = buildConfigs.getItems();
    if (items != null) {
      for (BuildConfig buildConfig : items) {
        try {
          upsertJob(buildConfig);
        } catch (IOException e) {
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
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private void upsertJob(BuildConfig buildConfig) throws IOException {
    if (isJenkinsBuildConfig(buildConfig)) {
      String jobName = jobName(buildConfig, defaultNamespace);
      Job jobFromBuildConfig = mapBuildConfigToJob(buildConfig, defaultNamespace);
      InputStream jobStream = new StringInputStream(new XStream2().toXML(jobFromBuildConfig));

      Job job = Jenkins.getInstance().getItem(jobName, Jenkins.getInstance(), Job.class);
      if (job == null) {
        Jenkins.getInstance().createProjectFromXML(
          jobName,
          jobStream
        );
      } else {
        Source source = new StreamSource(jobStream);
        job.updateByXml(source);
        job.save();
      }
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private void modifyJob(BuildConfig buildConfig) throws IOException, InterruptedException {
    if (isJenkinsBuildConfig(buildConfig)) {
      upsertJob(buildConfig);
      return;
    }

    String jobName = jobName(buildConfig, defaultNamespace);
    Job job = Jenkins.getInstance().getItem(jobName, Jenkins.getInstance(), Job.class);
    if (job != null) {
      job.delete();
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private void deleteJob(BuildConfig buildConfig) throws IOException, InterruptedException {
    String jobName = jobName(buildConfig, defaultNamespace);
    Job job = Jenkins.getInstance().getItem(jobName, Jenkins.getInstance(), Job.class);
    if (job != null) {
      job.delete();
    }
  }

  private boolean isJenkinsBuildConfig(BuildConfig buildConfig) {
    if (EXTERNAL_BUILD_STRATEGY.equalsIgnoreCase(buildConfig.getSpec().getStrategy().getType())) {
      return true;
    }
    ObjectMeta metadata = buildConfig.getMetadata();
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
