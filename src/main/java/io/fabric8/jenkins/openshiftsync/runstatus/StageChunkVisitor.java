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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.NotExecutedNodeAction;
import org.jenkinsci.plugins.workflow.actions.QueueItemAction;
import org.jenkinsci.plugins.workflow.actions.TagsAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.MemoryFlowChunk;
import org.jenkinsci.plugins.workflow.graphanalysis.StandardChunkVisitor;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

/**
 * Walks the flow graph backwards from the current heads and turns each stage
 * chunk into a {@link StageNodeExt}, with its status and timing.
 * <p>
 * The chunk boundaries come from {@link ForkScanner#visitSimpleChunks}, so the
 * list of stages (flat, including stages inside parallel branches) is the one
 * the Pipeline Stage View REST API produced.
 */
final class StageChunkVisitor extends StandardChunkVisitor {
    /** Tag written by Declarative Pipeline on a stage skipped by a {@code when} condition. */
    private static final String STAGE_STATUS_TAG = "STAGE_STATUS";
    private static final String SKIPPED_FOR_CONDITIONAL = "SKIPPED_FOR_CONDITIONAL";

    private final ArrayDeque<StageNodeExt> stages = new ArrayDeque<>();
    private final WorkflowRun run;
    private FlowNode firstExecuted;
    private int atomNodeCount;
    private boolean isLastChunk = true;

    StageChunkVisitor(@NonNull WorkflowRun run) {
        this.run = run;
    }

    Collection<StageNodeExt> getStages() {
        return Collections.unmodifiableCollection(stages);
    }

    @Override
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification = "MemoryFlowChunk nodes are null after a reset")
    protected void handleChunkDone(@NonNull MemoryFlowChunk chunk) {
        FlowNode last = chunk.getLastNode() != null ? chunk.getLastNode() : chunk.getFirstNode();
        StatusExt status;
        long startTime = 0;
        long duration = 0;
        long pause = 0;
        if (firstExecuted == null) {
            status = StatusExt.NOT_EXECUTED;
        } else {
            status = status(firstExecuted, last, chunk.getNodeAfter());
            startTime = TimingAction.getStartTime(firstExecuted);
            if (NotExecutedNodeAction.isExecuted(last)) {
                long[] timing = timing(chunk.getPauseTimeMillis(), firstExecuted, last, chunk.getNodeAfter());
                duration = timing[0];
                pause = timing[1];
            }
        }

        FlowNode first = chunk.getFirstNode();
        StageNodeExt stage = new StageNodeExt();
        stage.setId(first.getId());
        stage.setName(first.getDisplayName());
        stage.setExecNode("");
        stage.set_links(new FlowNodeExt.FlowNodeLinks());
        stage.get_links().initSelf(Urls.describe(first));
        stage.setStatus(status);
        ErrorAction error = last.getError();
        if (status != StatusExt.NOT_EXECUTED && error != null) {
            stage.setError(ErrorExt.create(error));
        }
        stage.setStartTimeMillis(startTime);
        stage.setDurationMillis(duration);
        stage.setPauseDurationMillis(pause);
        stage.setParameterDescription(ArgumentsAction.getStepArgumentsAsString(first));
        stage.setStageFlowNodeCount(Math.min(StageNodeExt.MAX_CHILD_NODES, atomNodeCount));
        stages.push(stage);
    }

    @Override
    protected void resetChunk(@NonNull MemoryFlowChunk chunk) {
        super.resetChunk(chunk);
        firstExecuted = null;
        atomNodeCount = 0;
    }

    @Override
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification = "MemoryFlowChunk nodes are null after a reset")
    public void chunkStart(@NonNull FlowNode startNode, @CheckForNull FlowNode beforeBlock, @NonNull ForkScanner scanner) {
        if (NotExecutedNodeAction.isExecuted(startNode)) {
            firstExecuted = startNode;
        }
        if (chunk.getLastNode() == null) {
            // empty chunk, just a marker with nothing after it
            chunk.setLastNode(startNode);
        }
        super.chunkStart(startNode, beforeBlock, scanner);
    }

    @Override
    public void chunkEnd(@NonNull FlowNode endNode, @CheckForNull FlowNode afterBlock, @NonNull ForkScanner scanner) {
        super.chunkEnd(endNode, afterBlock, scanner);
        atomNodeCount = 0;
        chunk.setPauseTimeMillis(0);
        firstExecuted = null;
        // a legacy marker stage includes its last node
        if (!(endNode instanceof BlockEndNode)) {
            atomNode(null, endNode, afterBlock, scanner);
        }
    }

    @Override
    public void atomNode(@CheckForNull FlowNode before, @NonNull FlowNode atomNode, @CheckForNull FlowNode after, @NonNull ForkScanner scan) {
        if (NotExecutedNodeAction.isExecuted(atomNode)) {
            firstExecuted = atomNode;
        }
        chunk.setPauseTimeMillis(chunk.getPauseTimeMillis() + PauseAction.getPauseDuration(atomNode));
        if (atomNode instanceof AtomNode) {
            atomNodeCount++;
        }
    }

    @Override
    public void parallelEnd(@NonNull FlowNode parallelStartNode, @NonNull FlowNode parallelEndNode, @NonNull ForkScanner scanner) {
        if (isLastChunk) {
            // for an incomplete parallel, use the end of the parallel as the chunk boundary,
            // branches are visited in declared order rather than time order
            chunk.setLastNode(parallelEndNode);
        }
        isLastChunk = false;
    }

    /**
     * Status of a stage, in order of precedence: not executed, still running
     * (or waiting on input), the error that ended it, the worst warning inside
     * it, success.
     */
    private StatusExt status(@NonNull FlowNode firstNode, @NonNull FlowNode lastNode, @CheckForNull FlowNode after) {
        FlowExecution exec = run.getExecution();
        if (exec == null) {
            return StatusExt.NOT_EXECUTED;
        }
        if (!NotExecutedNodeAction.isExecuted(lastNode) || isSkippedForConditional(firstNode)) {
            return StatusExt.NOT_EXECUTED;
        }
        boolean lastChunk = after == null || exec.isCurrentHead(lastNode);
        if (lastChunk) {
            if (!run.isBuilding()) {
                // final chunk of a completed build
                return StatusExt.valueOf(run.getResult());
            }
            if (exec.getCurrentHeads().size() > 1 && lastNode instanceof BlockEndNode) {
                BlockStartNode start = ((BlockEndNode<?>) lastNode).getStartNode();
                if (start.getAction(ThreadNameAction.class) != null) {
                    // a finished parallel branch while other branches are still running
                    if (lastNode.getError() != null) {
                        return StatusExt.FAILED;
                    }
                    WarningAction warning = worstWarningBetween(start, lastNode);
                    return warning != null ? StatusExt.valueOf(warning.getResult()) : StatusExt.SUCCESS;
                }
            }
            if (lastNode instanceof StepNode && lastNode instanceof BlockStartNode
                    && lastNode.getAction(QueueItemAction.class) != null) {
                return QueueItemAction.getNodeState(lastNode) == QueueItemAction.QueueState.CANCELLED
                        ? StatusExt.ABORTED : StatusExt.IN_PROGRESS;
            }
            PauseAction pauseAction = lastNode.getAction(PauseAction.class);
            if (pauseAction != null && "Input".equals(pauseAction.getCause()) && PendingInput.isPendingInput(run)) {
                return StatusExt.PAUSED_PENDING_INPUT;
            }
            return StatusExt.IN_PROGRESS;
        }
        ErrorAction error = lastNode.getError();
        if (error != null) {
            return StatusExt.valueOf(error);
        }
        WarningAction warning = worstWarningBetween(firstNode, lastNode);
        if (warning != null) {
            return StatusExt.valueOf(warning.getResult());
        }
        return StatusExt.SUCCESS;
    }

    /** Returns {total duration, pause duration} in milliseconds. */
    private long[] timing(long pauseMillis, @NonNull FlowNode firstNode, @NonNull FlowNode lastNode, @CheckForNull FlowNode after) {
        FlowExecution exec = run.getExecution();
        if (exec == null) {
            return new long[] {0, 0};
        }
        long endTime = after != null ? TimingAction.getStartTime(after) : System.currentTimeMillis();
        if (lastNode instanceof BlockEndNode
                && ((BlockEndNode<?>) lastNode).getStartNode().getAction(ThreadNameAction.class) != null) {
            // completed parallel branch, use the block end time
            endTime = TimingAction.getStartTime(lastNode);
        }
        long startTime = firstNode instanceof FlowStartNode ? run.getStartTimeInMillis() : TimingAction.getStartTime(firstNode);
        if (after == null && exec.isComplete()) {
            endTime = run.getDuration() + run.getStartTimeInMillis();
        }
        long total = endTime - startTime;
        return new long[] {total, Math.min(Math.abs(pauseMillis), total)};
    }

    private static boolean isSkippedForConditional(FlowNode node) {
        TagsAction tags = node.getAction(TagsAction.class);
        return tags != null && SKIPPED_FOR_CONDITIONAL.equals(tags.getTagValue(STAGE_STATUS_TAG));
    }

    /** The worst {@link WarningAction} on any node between start (exclusive) and end (inclusive). */
    @CheckForNull
    private static WarningAction worstWarningBetween(@NonNull FlowNode start, @NonNull FlowNode end) {
        DepthFirstScanner scanner = new DepthFirstScanner();
        if (!scanner.setup(end, Collections.singletonList(start))) {
            return null;
        }
        WarningAction worst = null;
        for (FlowNode node : scanner) {
            WarningAction warning = node.getPersistentAction(WarningAction.class);
            if (warning != null && (worst == null || warning.getResult().isWorseThan(worst.getResult()))) {
                worst = warning;
            }
        }
        return worst;
    }
}
