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

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;

/**
 * Status values written to the {@code openshift.io/jenkins-status-json} Build
 * annotation. The names are part of the published JSON and must not change.
 */
public enum StatusExt {
    NOT_EXECUTED,
    ABORTED,
    SUCCESS,
    IN_PROGRESS,
    PAUSED_PENDING_INPUT,
    FAILED,
    UNSTABLE;

    /**
     * Maps a build result. Unknown or {@code null} results map to
     * {@link #NOT_EXECUTED}.
     */
    public static StatusExt valueOf(Result r) {
        if (r == Result.NOT_BUILT) {
            return NOT_EXECUTED;
        } else if (r == Result.ABORTED) {
            return ABORTED;
        } else if (r == Result.FAILURE) {
            return FAILED;
        } else if (r == Result.UNSTABLE) {
            return UNSTABLE;
        } else if (r == Result.SUCCESS) {
            return SUCCESS;
        }
        return NOT_EXECUTED;
    }

    /**
     * Maps an error. A {@link FlowInterruptedException} maps to the result it
     * carries, anything else is {@link #FAILED}.
     */
    public static StatusExt valueOf(Throwable t) {
        if (t instanceof FlowInterruptedException) {
            return valueOf(((FlowInterruptedException) t).getResult());
        }
        return FAILED;
    }

    public static StatusExt valueOf(ErrorAction errorAction) {
        return errorAction == null ? SUCCESS : valueOf(errorAction.getError());
    }
}
