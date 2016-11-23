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
import hudson.model.Computer;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.concurrent.Callable;
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

  private transient BuildWatcher buildWatcher;

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
      if (buildWatcher != null) {
        buildWatcher.stop();
      }
      OpenShiftUtils.shutdownOpenShiftClient();
      return;
    }
    try {
      OpenShiftUtils.initializeOpenShiftClient(server);
      this.namespace = getNamespaceOrUseDefault(namespace, getOpenShiftClient());

      Runnable task = new SafeTimerTask() {
        @Override
        protected void doRun() throws Exception {
          logger.info("Waiting for Jenkins to be started");
          while (true) {
            Computer[] computers = Jenkins.getActiveInstance().getComputers();
            boolean ready = false;
            for (Computer c : computers) {
              // Jenkins.isAcceptingTasks() results in hudson.model.Node.isAcceptingTasks() getting called, and that always returns true;
              // the Computer.isAcceptingTasks actually introspects various Jenkins data structures to determine readiness
              if (c.isAcceptingTasks()) {
                ready = true;
                break;
              }
            }
            if (ready) {
              break;
            }
            try {
              Thread.sleep(500);
            } catch (InterruptedException e) {
              // ignore
            }
          }

          buildConfigWatcher = new BuildConfigWatcher(namespace);
          buildConfigWatcher.start(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
              buildWatcher = new BuildWatcher(namespace);
              buildWatcher.start();

              return null;
            }
          });
        }
      };
      // lets give jenkins a while to get started ;)
      Timer.get().schedule(task, 1,TimeUnit.SECONDS);
    } catch (KubernetesClientException e) {
      if (e.getCause() != null) {
        logger.log(Level.SEVERE, "Failed to configure OpenShift Jenkins Sync Plugin: " +  e.getCause());
      } else {
        logger.log(Level.SEVERE, "Failed to configure OpenShift Jenkins Sync Plugin: " +  e);
      }
    }
  }

}
