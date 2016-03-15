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

import io.fabric8.kubernetes.client.Config;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;

/**
 */
public class OpenShiftUtils {
  public static DefaultOpenShiftClient createOpenShiftClient(String serverUrl) {
    OpenShiftConfigBuilder configBuilder = new OpenShiftConfigBuilder();
    if (serverUrl != null && !serverUrl.isEmpty()) {
      configBuilder.withMasterUrl(serverUrl);
    }
    Config config = configBuilder.build();
    return new DefaultOpenShiftClient(config);
  }
}
