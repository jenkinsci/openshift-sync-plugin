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
package io.fabric8.jenkins.openshiftsync.systest;

import io.fabric8.arquillian.kubernetes.Session;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.assertj.core.api.Condition;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;

@ExtendWith(ArquillianExtension.class)
class JenkinsOpenShiftSysTest {

  @ArquillianResource
  private KubernetesClient client;

  @ArquillianResource
  private Session session;

  @Test
  void testAppProvisionsRunningPods() {
    installInitialBuildConfigs();

    assertThat(client).pods()
      .runningStatus()
      .filterNamespace(session.getNamespace())
      .haveAtLeast(1, new Condition<Pod>() {
        @Override
        public boolean matches(Pod podSchema) {
          return true;
        }
      });

    System.out.println("Now asserting that we have Jenkins Jobs!");
  }

  protected void installInitialBuildConfigs() {
    System.out.println("Starting BuildConfigs");
  }
}
