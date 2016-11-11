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
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildList;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.getJobFromBuild;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.triggerJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.cancelOpenShiftBuild;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_GONE;

public class NewBuildWatcher implements Watcher<Build> {
  private static final Logger logger = Logger.getLogger(NewBuildWatcher.class.getName());
  private final String namespace;
  private Watch buildsWatch;

  public NewBuildWatcher(String defaultNamespace) {
    this.namespace = defaultNamespace;
  }

  public void start() {
    final BuildList builds;
    if (namespace != null && !namespace.isEmpty()) {
      builds = getOpenShiftClient().builds().inNamespace(namespace).withField("status", BuildPhases.NEW).list();
      buildsWatch = getOpenShiftClient().builds().inNamespace(namespace).withField("status", BuildPhases.NEW).withResourceVersion(builds.getMetadata().getResourceVersion()).watch(this);
    } else {
      builds = getOpenShiftClient().builds().withField("status", BuildPhases.NEW).list();
      buildsWatch = getOpenShiftClient().builds().withField("status", BuildPhases.NEW).withResourceVersion(builds.getMetadata().getResourceVersion()).watch(this);
    }

    // lets process the initial state
    logger.info("Now handling startup builds!!");
    // lets do this in a background thread to avoid errors like:
    //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
    Runnable task = new SafeTimerTask() {
      @Override
      public void doRun() {
        logger.info("Waiting for Jenkins to be started");
        while (true) {
          if (Jenkins.getActiveInstance().isAcceptingTasks()) {
            break;
          }
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            // ignore
          }
        }
        logger.info("loading initial Builds resources");

        try {
          onInitialBuilds(builds);
          logger.info("loaded initial Builds resources");
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Failed to load initial Builds: " + e, e);
        }
      }
    };
    // lets give jenkins a while to get started ;)
    Timer.get().schedule(task, 500, TimeUnit.MILLISECONDS);
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

  public synchronized void onInitialBuilds(BuildList buildList) {

    List<Build> items = buildList.getItems();
    if (items != null) {

      Collections.sort(items, new Comparator<Build>() {
        @Override
        public int compare(Build b1, Build b2) {
          return Long.compare(
            Long.parseLong(b1.getMetadata().getAnnotations().get("openshift.io/build.number")),
            Long.parseLong(b2.getMetadata().getAnnotations().get("openshift.io/build.number"))
          );
        }
      });

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

  public static synchronized void buildAdded(Build build) throws IOException {
    if (build.getStatus() != null && Boolean.TRUE.equals(build.getStatus().getCancelled())) {
      cancelOpenShiftBuild(build);
      return;
    }

    WorkflowJob job = getJobFromBuild(build);
    if (job != null) {
      triggerJob(job, build);
    }
  }

}
