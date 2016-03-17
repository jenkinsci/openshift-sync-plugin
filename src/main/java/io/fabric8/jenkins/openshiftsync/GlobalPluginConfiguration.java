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

import com.thoughtworks.xstream.annotations.XStreamOmitField;
import hudson.Extension;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.openshift.api.model.BuildConfigList;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import jenkins.model.GlobalConfiguration;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespaceOrUseDefault;

@Extension
public class GlobalPluginConfiguration extends GlobalConfiguration {

  @XStreamOmitField
  private final Logger logger = Logger.getLogger(getClass().getName());

  private boolean enabled = true;

  private String server;

  private String namespace;

  @XStreamOmitField
  private OpenShiftClient openShiftClient = null;

  @XStreamOmitField
  private Watch buildConfigWatch;

  @XStreamOmitField
  private Watch buildWatch;

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
      if (openShiftClient != null) {
        openShiftClient.close();
        openShiftClient = null;
      }
      return;
    }
    if (enabled) {
      OpenShiftConfigBuilder configBuilder = new OpenShiftConfigBuilder();
      if (server != null && !server.isEmpty()) {
        configBuilder.withMasterUrl(server);
      }
      Config config = configBuilder.build();
      openShiftClient = new DefaultOpenShiftClient(config);
      this.namespace = getNamespaceOrUseDefault(namespace, openShiftClient);

      final BuildConfigWatcher buildConfigWatcher = new BuildConfigWatcher(namespace);
      final BuildWatcher buildWatcher = new BuildWatcher(namespace);
      final BuildConfigList buildConfigs;
      final BuildList builds;
      if (namespace != null && !namespace.isEmpty()) {
        buildConfigWatch = openShiftClient.buildConfigs().inNamespace(namespace).watch(buildConfigWatcher);
        buildWatch = openShiftClient.builds().inNamespace(namespace).watch(buildWatcher);
        buildConfigs = openShiftClient.buildConfigs().inNamespace(namespace).list();
        builds = openShiftClient.builds().inNamespace(namespace).list();
      } else {
        buildConfigWatch = openShiftClient.buildConfigs().inAnyNamespace().watch(buildConfigWatcher);
        buildWatch = openShiftClient.builds().inAnyNamespace().watch(buildWatcher);
        buildConfigs = openShiftClient.buildConfigs().inAnyNamespace().list();
        builds = openShiftClient.builds().inAnyNamespace().list();
      }

      // lets process the initial state
      logger.info("Now handling startup build configs!!");
      if (buildConfigs != null) {
        // lets do this in a background thread to avoid errors like:
        //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
        Runnable task = new Runnable() {
          @Override
          public void run() {
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
        Timer.get().schedule(task, 100, TimeUnit.MILLISECONDS);
      }
    }
  }

}
