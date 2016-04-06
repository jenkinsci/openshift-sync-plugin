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

import hudson.model.Cause;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildSpec;
import org.apache.commons.lang.StringUtils;

public class BuildCause extends Cause {

  private final Build build;

  public BuildCause(Build build) {
    this.build = build;
  }

  public Build getBuild() {
    return build;
  }

  @Override
  public String getShortDescription() {
    StringBuilder sb = new StringBuilder("OpenShift Build ")
      .append(build.getMetadata().getNamespace()).append("/").append(build.getMetadata().getName());

    BuildSpec spec = build.getSpec();
    if (spec != null) {
      BuildSource source = spec.getSource();
      if (source != null && source.getGit() != null && StringUtils.isNotBlank(source.getGit().getUri())) {
        sb.append(" from ").append(source.getGit().getUri());
        if (spec.getRevision() != null
          && spec.getRevision().getGit() != null
          && StringUtils.isNotBlank(spec.getRevision().getGit().getCommit())) {
          sb.append(", commit ").append(spec.getRevision().getGit().getCommit());
        }
      }
    }

    return sb.toString();
  }
}
