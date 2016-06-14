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
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildList;

import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.getJobFromBuild;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_GONE;

public class CancelledBuildWatcher implements Watcher<Build> {
  private static final Logger logger = Logger.getLogger(CancelledBuildWatcher.class.getName());
  private final String namespace;
  private Watch buildsWatch;

  public CancelledBuildWatcher(String defaultNamespace) {
    this.namespace = defaultNamespace;
  }

  public void start() {
    final BuildList builds;
    if (namespace != null && !namespace.isEmpty()) {
      builds = getOpenShiftClient().builds().inNamespace(namespace).withField("status", BuildPhases.RUNNING).list();
      buildsWatch = getOpenShiftClient().builds().inNamespace(namespace).withResourceVersion(builds.getMetadata().getResourceVersion()).watch(this);
    } else {
      builds = getOpenShiftClient().builds().withField("status", BuildPhases.RUNNING).list();
      buildsWatch = getOpenShiftClient().builds().withResourceVersion(builds.getMetadata().getResourceVersion()).watch(this);
    }
  }

  public void stop() {
    if (buildsWatch != null) {
      buildsWatch.close();
      buildsWatch = null;
    }
  }

  @Override
  public void onClose(KubernetesClientException e) {
    if (e != null) {
      logger.warning(e.toString());

      if (e.getStatus() != null && e.getStatus().getCode() == HTTP_GONE) {
        stop();
        start();
      }
    }
  }

  @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
  @Override
  public void eventReceived(Action action, Build build) {
    try {
      switch (action) {
        case MODIFIED:
          buildModified(build);
          break;
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Caught: " + e, e);
    }
  }

  private void buildModified(Build build) {
    if (Boolean.TRUE.equals(build.getStatus().getCancelled())) {
      Job job = getJobFromBuild(build);
      if (job != null) {
        JenkinsUtils.cancelBuild(job, build);
      }
    }
  }

}
