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
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.concurrent.Callable;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespaceOrUseDefault;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;

@Extension
public class GlobalPluginConfiguration extends GlobalConfiguration {

  private boolean enabled = true;

  private String server;

  private String namespace;

  private transient NewBuildWatcher newBuildWatcher;

  private transient CancelledBuildWatcher cancelledBuildWatcher;

  private transient BuildConfigWatcher buildConfigWatcher;

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
    if (!enabled) {
      if (buildConfigWatcher != null) {
        buildConfigWatcher.stop();
      }
      if (newBuildWatcher != null) {
        newBuildWatcher.stop();
      }
      if (cancelledBuildWatcher != null) {
        cancelledBuildWatcher.stop();
      }
      OpenShiftUtils.shutdownOpenShiftClient();
      return;
    }
    if (enabled) {
      OpenShiftUtils.initializeOpenShiftClient(server);
      this.namespace = getNamespaceOrUseDefault(namespace, getOpenShiftClient());

      buildConfigWatcher = new BuildConfigWatcher(namespace);
      buildConfigWatcher.start(new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          newBuildWatcher = new NewBuildWatcher(namespace);
          newBuildWatcher.start();
          cancelledBuildWatcher = new CancelledBuildWatcher(namespace);
          cancelledBuildWatcher.start();

          return null;
        }
      });
    }
  }

}
