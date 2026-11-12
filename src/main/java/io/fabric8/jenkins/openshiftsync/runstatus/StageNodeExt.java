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

import com.fasterxml.jackson.annotation.JsonIgnore;

/** A stage in the status JSON. */
public class StageNodeExt extends FlowNodeExt {
    /** Same cap the stage view REST API applied to its per-stage node list. */
    static final int MAX_CHILD_NODES = 100;

    private int stageFlowNodeCount;

    /**
     * Number of step nodes in the stage, capped at {@value #MAX_CHILD_NODES}.
     * Only used to decide when to update the Build, never serialised.
     */
    @JsonIgnore
    public int getStageFlowNodeCount() {
        return stageFlowNodeCount;
    }

    @JsonIgnore
    public void setStageFlowNodeCount(int stageFlowNodeCount) {
        this.stageFlowNodeCount = stageFlowNodeCount;
    }
}
