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
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.StageAction;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.graphanalysis.ChunkFinder;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

/**
 * Finds stages: block scoped {@code stage('x') { }} steps, whose start node
 * carries a {@link LabelAction}, and legacy {@code stage 'x'} markers.
 * Parallel branches also carry a {@link LabelAction} but are not
 * {@code stage} steps, so they are not stages.
 */
final class StageChunkFinder implements ChunkFinder {

    @Override
    public boolean isStartInsideChunk() {
        return true;
    }

    @Override
    public boolean isChunkStart(@NonNull FlowNode current, @CheckForNull FlowNode previous) {
        if (current instanceof BlockEndNode) {
            return false;
        }
        if (current instanceof StepNode && (current instanceof AtomNode || current instanceof BlockStartNode)) {
            if (!isStageStep(current)) {
                return false;
            }
            if (current instanceof BlockStartNode) {
                return current.getAction(LabelAction.class) != null;
            }
        }
        return current.getAction(StageAction.class) != null;
    }

    @Override
    public boolean isChunkEnd(@NonNull FlowNode current, @CheckForNull FlowNode previous) {
        if (current instanceof BlockEndNode && current instanceof StepNode && isStageStep(current)) {
            // a block scoped stage creates two nested blocks, the outer one has the label
            return ((BlockEndNode<?>) current).getStartNode().getAction(LabelAction.class) != null;
        }
        if (previous != null) {
            return isChunkStart(previous, null);
        }
        return false;
    }

    private static boolean isStageStep(FlowNode node) {
        StepDescriptor descriptor = ((StepNode) node).getDescriptor();
        return descriptor != null && "stage".equals(descriptor.getFunctionName());
    }
}
