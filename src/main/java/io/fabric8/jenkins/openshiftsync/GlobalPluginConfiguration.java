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
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.openshift.api.model.BuildConfigList;
import io.fabric8.openshift.api.model.BuildList;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespaceOrUseDefault;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;

@Extension
public class GlobalPluginConfiguration extends GlobalConfiguration {

  private static final Logger logger = Logger.getLogger(GlobalPluginConfiguration.class.getName());

  private boolean enabled = true;

  private String server;

  private String namespace;

  private transient Watch buildConfigWatch;

  private transient Watch buildWatch;

  @DataBoundConstructor
  public GlobalPluginConfiguration(boolean enable, String server, String namespace) {
    this.enabled = enable;
    this.server = server;
    this.namespace = namespace;
    configChange();
  }

  public GlobalPluginConfiguration() {
    load();
    configChange();
  }

  public static GlobalPluginConfiguration get() {
    return GlobalConfiguration.all().get(GlobalPluginConfiguration.class);
  }

  @Override
  public String getDisplayName() {
    return "OpenShift Jenkins Sync";
  }

  @Override
  public boolean configure(StaplerRequest req, JSONObject json) throws hudson.model.Descriptor.FormException {
    req.bindJSON(this, json);
    save();
    configChange();
    return true;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getServer() {
    return server;
  }

  public void setServer(String server) {
    this.server = server;
  }

  public String getNamespace() {
    return namespace;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  private void configChange() {
    if (namespace == null) {
      namespace = System.getenv("KUBERNETES_NAMESPACE");
    }
    logger.info("using default kubernetes namespace: " + namespace);

    if (!enabled) {
      if (buildConfigWatch != null) {
        buildConfigWatch.close();
      }
      if (buildWatch != null) {
        buildWatch.close();
      }
      OpenShiftUtils.shutdownOpenShiftClient();
      return;
    }
    if (enabled) {
      OpenShiftUtils.initializeOpenShiftClient(server);
      this.namespace = getNamespaceOrUseDefault(namespace, getOpenShiftClient());

      final BuildConfigWatcher buildConfigWatcher = new BuildConfigWatcher(namespace);
      final BuildWatcher buildWatcher = new BuildWatcher(getOpenShiftClient());
      final BuildConfigList buildConfigs;
      final BuildList builds;
      if (namespace != null && !namespace.isEmpty()) {
        buildConfigs = getOpenShiftClient().buildConfigs().inNamespace(namespace).list();
        buildConfigWatch = getOpenShiftClient().buildConfigs().inNamespace(namespace).withResourceVersion(buildConfigs.getMetadata().getResourceVersion()).watch(buildConfigWatcher);
        builds = getOpenShiftClient().builds().inNamespace(namespace).withField("status", BuildPhases.NEW).list();
        buildWatch = getOpenShiftClient().builds().inNamespace(namespace).withField("status", BuildPhases.NEW).withResourceVersion(builds.getMetadata().getResourceVersion()).watch(buildWatcher);
      } else {
        buildConfigs = getOpenShiftClient().buildConfigs().inAnyNamespace().list();
        buildConfigWatch = getOpenShiftClient().buildConfigs().inAnyNamespace().withResourceVersion(buildConfigs.getMetadata().getResourceVersion()).watch(buildConfigWatcher);
        builds = getOpenShiftClient().builds().inAnyNamespace().withField("status", BuildPhases.NEW).list();
        buildWatch = getOpenShiftClient().builds().inAnyNamespace().withField("status", BuildPhases.NEW).withResourceVersion(builds.getMetadata().getResourceVersion()).watch(buildWatcher);
      }

      // lets process the initial state
      logger.info("Now handling startup build configs!!");
      // lets do this in a background thread to avoid errors like:
      //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
      Runnable task = new Runnable() {
        @Override
        public void run() {
          logger.info("Waiting for Jenkins to be started");
          while (true) {
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins != null) {
              if (jenkins.isAcceptingTasks()) {
                break;
              }
            }
            try {
              Thread.sleep(500);
            } catch (InterruptedException e) {
              // ignore
            }
          }
          logger.info("loading initial BuildConfigs resources");

          try {
            buildConfigWatcher.onInitialBuildConfigs(buildConfigs);
            logger.info("loaded initial BuildConfigs resources");
          } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load initial BuildConfigs: " + e, e);
          }
          try {
            buildWatcher.onInitialBuilds(builds);
            logger.info("loaded initial Builds resources");
          } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load initial Builds: " + e, e);
          }
        }
      };
      // lets give jenkins a while to get started ;)
      Timer.get().schedule(task, 500, TimeUnit.MILLISECONDS);
    }
  }

}
