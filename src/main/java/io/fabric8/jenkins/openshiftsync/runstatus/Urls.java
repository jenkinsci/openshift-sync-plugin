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
package io.fabric8.jenkins.openshiftsync.runstatus;

import java.io.IOException;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Builds the relative URLs found in the status JSON. They keep the format the
 * Pipeline Stage View REST API used ({@code .../wfapi/...}), so the JSON is
 * unchanged. Those endpoints only answer when the Pipeline Stage View plugin
 * is installed.
 */
final class Urls {
    private static final String WFAPI = "wfapi";

    private Urls() {
    }

    /** {@code /job/x/1/wfapi/} */
    static String runApi(WorkflowRun run) {
        return fullItemUrl(run.getUrl()) + WFAPI + "/";
    }

    /** {@code /job/x/1/execution/node/5/wfapi/describe} */
    static String describe(FlowNode node) {
        try {
            return fullItemUrl(node.getUrl()) + WFAPI + "/describe";
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected error getting URL for a Stage FlowNode.", e);
        }
    }

    static String fullItemUrl(String itemUrl) {
        String rootUrl = rootUrl();
        if (!itemUrl.endsWith("/")) {
            itemUrl += "/";
        }
        return rootUrl.endsWith("/") ? rootUrl + itemUrl : rootUrl + "/" + itemUrl;
    }

    private static String rootUrl() {
        StaplerRequest2 currentRequest = Stapler.getCurrentRequest2();
        return currentRequest != null ? currentRequest.getContextPath() : "/";
    }
}
