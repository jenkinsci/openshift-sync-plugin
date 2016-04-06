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
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.client.OpenShiftClient;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BuildWatcher implements Watcher<Build> {
  private static final Logger logger = Logger.getLogger(BuildWatcher.class.getName());

  private final OpenShiftClient openShiftClient;

  public BuildWatcher(OpenShiftClient openShiftClient) {
    this.openShiftClient = openShiftClient;
  }

  @Override
  public void onClose(KubernetesClientException e) {
    if (e != null) {
      logger.warning(e.toString());
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  public void onInitialBuilds(BuildList buildList) {
    List<Build> items = buildList.getItems();
    if (items != null) {
      for (Build build : items) {
        try {
          buildAdded(build);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
  @Override
  public void eventReceived(Action action, Build build) {
    try {
      switch (action) {
        case ADDED:
          buildAdded(build);
          break;
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Caught: " + e, e);
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private void buildAdded(Build build) throws IOException {
    String buildConfigName = build.getStatus().getConfig().getName();
    if (StringUtils.isEmpty(buildConfigName)) {
      return;
    }
    BuildConfig buildConfig = openShiftClient.buildConfigs().inNamespace(build.getMetadata().getNamespace()).withName(buildConfigName).get();
    if (buildConfig == null) {
      return;
    }
    Job job = BuildTrigger.DESCRIPTOR.getJobFromBuildConfigUid(buildConfig.getMetadata().getUid());
    if (job != null) {
      JenkinsUtils.triggerJob(job, build);
    }
  }
}
