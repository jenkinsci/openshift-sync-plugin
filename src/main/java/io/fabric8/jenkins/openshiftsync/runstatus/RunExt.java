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

import com.fasterxml.jackson.annotation.JsonInclude;
import hudson.model.Result;
import hudson.scm.ChangeLogSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jenkins.model.StandardArtifactManager;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/**
 * The run written to the {@code openshift.io/jenkins-status-json} Build
 * annotation.
 * <p>
 * This reproduces the JSON format that the Pipeline Stage View REST API
 * ({@code RunExt} in the pipeline-rest-api plugin) produced, which the
 * OpenShift console reads. Field declaration order is the JSON property order.
 */
public class RunExt {
    private RunLinks _links;
    private String id;
    private String name;
    private StatusExt status;
    private long startTimeMillis;
    private long endTimeMillis;
    private long durationMillis;
    private long queueDurationMillis;
    private long pauseDurationMillis;
    private List<StageNodeExt> stages;

    public RunLinks get_links() {
        return _links;
    }

    public void set_links(RunLinks _links) {
        this._links = _links;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public StatusExt getStatus() {
        return status;
    }

    public void setStatus(StatusExt status) {
        this.status = status;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public void setStartTimeMillis(long startTimeMillis) {
        this.startTimeMillis = startTimeMillis;
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    public void setEndTimeMillis(long endTimeMillis) {
        this.endTimeMillis = endTimeMillis;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public long getQueueDurationMillis() {
        return queueDurationMillis;
    }

    public void setQueueDurationMillis(long queueDurationMillis) {
        this.queueDurationMillis = queueDurationMillis;
    }

    public long getPauseDurationMillis() {
        return pauseDurationMillis;
    }

    public void setPauseDurationMillis(long pauseDurationMillis) {
        this.pauseDurationMillis = pauseDurationMillis;
    }

    public List<StageNodeExt> getStages() {
        return stages;
    }

    public void setStages(List<StageNodeExt> stages) {
        this.stages = stages;
    }

    public static final class RunLinks extends Links {
        private Link changesets;
        private Link pendingInputActions;
        private Link nextPendingInputAction;
        private Link artifacts;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Link getChangesets() {
            return changesets;
        }

        public void setChangesets(Link changesets) {
            this.changesets = changesets;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Link getPendingInputActions() {
            return pendingInputActions;
        }

        public void setPendingInputActions(Link pendingInputActions) {
            this.pendingInputActions = pendingInputActions;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Link getNextPendingInputAction() {
            return nextPendingInputAction;
        }

        public void setNextPendingInputAction(Link nextPendingInputAction) {
            this.nextPendingInputAction = nextPendingInputAction;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Link getArtifacts() {
            return artifacts;
        }

        public void setArtifacts(Link artifacts) {
            this.artifacts = artifacts;
        }
    }

    /** Builds the status of a run, including its stages. */
    public static RunExt create(WorkflowRun run) {
        FlowExecution execution = run.getExecution();
        RunExt runExt = new RunExt();
        runExt.set_links(new RunLinks());
        runExt.get_links().initSelf(Urls.runApi(run) + "describe");
        runExt.setId(run.getId());
        runExt.setName(run.getDisplayName());
        runExt.setStatus(runStatus(run));
        runExt.setStartTimeMillis(run.getStartTimeInMillis());
        runExt.setStages(new ArrayList<>());

        if (execution != null) {
            if (hasChanges(run)) {
                runExt.get_links().setChangesets(Link.newLink(Urls.runApi(run) + "changesets"));
            }
            if (PendingInput.isPendingInput(run)) {
                runExt.get_links().setPendingInputActions(Link.newLink(Urls.runApi(run) + "pendingInputActions"));
                runExt.get_links().setNextPendingInputAction(Link.newLink(Urls.runApi(run) + "nextPendingInputAction"));
            }
            boolean mightHaveArtifacts = !(run.getArtifactManager() instanceof StandardArtifactManager) || run.getHasArtifacts();
            if (mightHaveArtifacts) {
                runExt.get_links().setArtifacts(Link.newLink(Urls.runApi(run) + "artifacts"));
            }

            StageChunkVisitor visitor = new StageChunkVisitor(run);
            ForkScanner.visitSimpleChunks(execution.getCurrentHeads(), visitor, new StageChunkFinder());
            List<StageNodeExt> stages = new ArrayList<>(visitor.getStages());
            stages.sort(STAGE_ORDER);
            runExt.setStages(stages);
        }

        long now = System.currentTimeMillis();
        if (runExt.getStatus() == StatusExt.IN_PROGRESS || runExt.getStatus() == StatusExt.PAUSED_PENDING_INPUT) {
            runExt.setEndTimeMillis(now);
        } else {
            runExt.setEndTimeMillis(run.getStartTimeInMillis() + run.getDuration());
        }
        if (run.hasntStartedYet()) {
            runExt.setQueueDurationMillis(now - run.getTimeInMillis());
        } else {
            runExt.setQueueDurationMillis(Math.max(0, run.getStartTimeInMillis() - run.getTimeInMillis()));
        }
        runExt.setDurationMillis(Math.max(0, runExt.getEndTimeMillis() - runExt.getStartTimeMillis()));
        return runExt;
    }

    private static StatusExt runStatus(WorkflowRun run) {
        FlowExecution execution = run.getExecution();
        if (execution == null) {
            return StatusExt.NOT_EXECUTED;
        } else if (execution.getCauseOfFailure() != null) {
            return StatusExt.valueOf(execution.getCauseOfFailure());
        } else if (execution.isComplete()) {
            Result r = run.getResult();
            if (r == null) {
                List<FlowNode> heads = execution.getCurrentHeads();
                if (!heads.isEmpty() && heads.get(0) instanceof FlowEndNode) {
                    r = ((FlowEndNode) heads.get(0)).getResult();
                }
            }
            return StatusExt.valueOf(r);
        } else if (PendingInput.isPendingInput(run)) {
            return StatusExt.PAUSED_PENDING_INPUT;
        }
        return StatusExt.IN_PROGRESS;
    }

    private static boolean hasChanges(WorkflowRun run) {
        for (ChangeLogSet<? extends ChangeLogSet.Entry> changeset : run.getChangeSets()) {
            if (!changeset.isEmptySet()) {
                return true;
            }
        }
        return false;
    }

    /** Start time, then numeric node id, then name. */
    static final Comparator<StageNodeExt> STAGE_ORDER = (s1, s2) -> {
        int compare = Long.compare(s1.getStartTimeMillis(), s2.getStartTimeMillis());
        if (compare != 0) {
            return compare;
        }
        try {
            compare = Integer.compare(Integer.parseInt(s1.getId()), Integer.parseInt(s2.getId()));
            if (compare != 0) {
                return compare;
            }
        } catch (NumberFormatException e) {
            // fall through to the name
        }
        return Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(s1.getName(), s2.getName());
    };
}
