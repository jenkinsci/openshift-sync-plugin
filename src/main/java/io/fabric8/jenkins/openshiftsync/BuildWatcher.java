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
import io.fabric8.openshift.api.model.BuildList;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isCreatedByJenkins;

public class BuildWatcher implements Watcher<Build> {
  private final Logger logger = Logger.getLogger(getClass().getName());
  private final String defaultNamespace;

  private static final Object lock = new Object();

  public BuildWatcher(String defaultNamespace) {
    this.defaultNamespace = defaultNamespace;
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
    if (!isCreatedByJenkins(build)) {
      synchronized (lock) {
        String jobName = OpenShiftUtils.jenkinsJobName(build, defaultNamespace);
        if (jobName != null && !jobName.isEmpty()) {
          Job job = JenkinsUtils.getJob(jobName);
          if (job == null) {
            logger.warning("Could not find Jenkins job `" + jobName + "` for OpenShift Build " + NamespaceName.create(build));
          } else {
            JenkinsUtils.triggerJob(job);
          }
        } else {
          logger.warning("Could not find a Jenkins job for OpenShift Build " + NamespaceName.create(build));
        }
      }
    }
  }
}
