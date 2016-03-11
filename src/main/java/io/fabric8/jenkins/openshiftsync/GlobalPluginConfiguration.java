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
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.logging.Logger;

@Extension
public class GlobalPluginConfiguration extends GlobalConfiguration {

  private final Logger logger = Logger.getLogger(getClass().getName());

  private boolean enabled = false;

  private String server;

  private OpenShiftClient openShiftClient = null;

  private Watch watch;

  @DataBoundConstructor
  public GlobalPluginConfiguration(boolean enable, String server) {
    this.enabled = enable;
    this.server = server;
    configChange();
  }

  public GlobalPluginConfiguration() {
    load();
    openShiftClient = null;
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

  private void configChange() {
    if (!enabled) {
      if (watch != null) {
        watch.close();
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
      watch = openShiftClient.buildConfigs().watch(new Watcher<BuildConfig>() {
        @Override
        public void eventReceived(Action action, BuildConfig buildConfig) {
          logger.info(buildConfig.toString());
        }

        @Override
        public void onClose(KubernetesClientException e) {
          if (e != null) {
            logger.warning(e.toString());
          }
        }
      });
    }
  }

}
