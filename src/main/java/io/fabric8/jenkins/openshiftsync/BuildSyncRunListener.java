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

import com.cloudbees.workflow.rest.external.AtomFlowNodeExt;
import com.cloudbees.workflow.rest.external.FlowNodeExt;
import com.cloudbees.workflow.rest.external.PendingInputActionsExt;
import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import com.cloudbees.workflow.rest.external.StatusExt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import hudson.Extension;
import hudson.PluginManager;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildFluent;
import io.fabric8.openshift.api.model.DoneableBuild;
import io.jenkins.blueocean.rest.factory.BlueRunFactory;
import io.jenkins.blueocean.rest.model.BluePipelineNode;
import io.jenkins.blueocean.rest.model.BlueRun;
import io.jenkins.blueocean.rest.model.BlueRun.BlueRunResult;
import jenkins.model.Jenkins;
import jenkins.util.Timer;

import org.apache.commons.httpclient.HttpStatus;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_BUILD_URI;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_LOG_URL;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_NAMESPACE;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_PENDING_INPUT_ACTION_JSON;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_STATUS_JSON;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.maybeScheduleNext;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.formatTimestamp;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/**
 * Listens to Jenkins Job build {@link Run} start and stop then ensure there's a
 * suitable {@link Build} object in OpenShift thats updated correctly with the
 * current status, logsURL and metrics
 */
@Extension
public class BuildSyncRunListener extends RunListener<Run> {
    private static final Logger logger = Logger
            .getLogger(BuildSyncRunListener.class.getName());

    private long pollPeriodMs = 1000 * 5;  // 5 seconds
    private long delayPollPeriodMs = 1000; // 1 seconds
    private static final long maxDelay = 30000;

    private transient ConcurrentLinkedQueue<Run> runsToPoll = new ConcurrentLinkedQueue<>();

    private transient AtomicBoolean timerStarted = new AtomicBoolean(false);

    public BuildSyncRunListener() {
    }

    @DataBoundConstructor
    public BuildSyncRunListener(long pollPeriodMs) {
        this.pollPeriodMs = pollPeriodMs;
    }

    /**
     * Joins all the given strings, ignoring nulls so that they form a URL with
     * / between the paths without a // if the previous path ends with / and the
     * next path starts with / unless a path item is blank
     *
     * @param strings
     *            the sequence of strings to join
     * @return the strings concatenated together with / while avoiding a double
     *         // between non blank strings.
     */
    public static String joinPaths(String... strings) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strings.length; i++) {
            sb.append(strings[i]);
            if (i < strings.length - 1) {
                sb.append("/");
            }
        }
        String joined = sb.toString();

        // And normalize it...
        return joined.replaceAll("/+", "/").replaceAll("/\\?", "?")
                .replaceAll("/#", "#").replaceAll(":/", "://");
    }

    @Override
    public void onStarted(Run run, TaskListener listener) {
        if (shouldPollRun(run)) {
            try {
                BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
                if (cause != null) {
                    // TODO This should be a link to the OpenShift console.
                    run.setDescription(cause.getShortDescription());
                }
            } catch (IOException e) {
                logger.log(WARNING, "Cannot set build description: " + e);
            }
            if (runsToPoll.add(run)) {
                logger.info("starting polling build " + run.getUrl());
            }
            checkTimerStarted();
        } else {
            logger.fine("not polling polling build " + run.getUrl()
                    + " as its not a WorkflowJob");
        }
        super.onStarted(run, listener);
    }

    protected void checkTimerStarted() {
        if (timerStarted.compareAndSet(false, true)) {
            Runnable task = new SafeTimerTask() {
                @Override
                protected void doRun() throws Exception {
                    pollLoop();
                }
            };
            Timer.get().scheduleAtFixedRate(task, delayPollPeriodMs, pollPeriodMs,
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onCompleted(Run run, @Nonnull TaskListener listener) {
        if (shouldPollRun(run)) {
            runsToPoll.remove(run);
            pollRun(run);
            logger.info("onCompleted " + run.getUrl());
            maybeScheduleNext(((WorkflowRun) run).getParent());
        }
        super.onCompleted(run, listener);
    }

    @Override
    public void onDeleted(Run run) {
        if (shouldPollRun(run)) {
            runsToPoll.remove(run);
            pollRun(run);
            logger.info("onDeleted " + run.getUrl());
            maybeScheduleNext(((WorkflowRun) run).getParent());
        }
        super.onDeleted(run);
    }

    @Override
    public void onFinalized(Run run) {
        if (shouldPollRun(run)) {
            runsToPoll.remove(run);
            pollRun(run);
            logger.info("onFinalized " + run.getUrl());
        }
        super.onFinalized(run);
    }

    protected void pollLoop() {
        while (!runsToPoll.isEmpty()) {
            pollRun(runsToPoll.poll());
        }
    }

    protected synchronized void pollRun(Run run) {
        if (!(run instanceof WorkflowRun)) {
            throw new IllegalStateException("Cannot poll a non-workflow run");
        }

        RunExt wfRunExt = RunExt.create((WorkflowRun) run);

        // try blue run
        BlueRun blueRun = null;
        try {
            blueRun = BlueRunFactory.getRun(run, null);
        } catch (Throwable t) {
            // use of info is intentional ... in case the blue ocean deps get
            // bumped
            // by another dependency vs. our bumping it explicitly, I want to
            // find out quickly that we need to switch methods again
            logger.log(Level.WARNING, "pollRun", t);
        }

        try {
            upsertBuild(run, wfRunExt, blueRun);
        } catch (KubernetesClientException e) {
            if (e.getCode() == HttpStatus.SC_UNPROCESSABLE_ENTITY) {
                logger.log(WARNING, "Cannot update status: {0}", e.getMessage());
                return;
            }
        }
    }

    private boolean shouldUpdateOpenShiftBuild(BuildCause cause,
            int latestStageNum, int latestNumFlowNodes, StatusExt status) {
        long currTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        logger.fine(String.format(
                "shouldUpdateOpenShiftBuild curr time %s last update %s curr stage num %s last stage num %s"
                        + "curr flow num %s last flow num %s status %s",
                String.valueOf(currTime),
                String.valueOf(cause.getLastUpdateToOpenshift()),
                String.valueOf(latestStageNum),
                String.valueOf(cause.getNumStages()),
                String.valueOf(latestNumFlowNodes),
                String.valueOf(cause.getNumFlowNodes()), status.toString()));

        // if we have not updated in maxDelay time, update
        if (currTime > (cause.getLastUpdateToOpenshift() + maxDelay)) {
            return true;
        }

        // if the num of stages has changed, update
        if (cause.getNumStages() != latestStageNum) {
            return true;
        }

        // if the num of flow nodes has changed, update
        if (cause.getNumFlowNodes() != latestNumFlowNodes) {
            return true;
        }

        // if the run is in some sort of terminal state, update
        if (status != StatusExt.IN_PROGRESS) {
            return true;
        }

        return false;
    }

    private void upsertBuild(Run run, RunExt wfRunExt, BlueRun blueRun) {
        if (run == null) {
            return;
        }

        BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
        if (cause == null) {
            return;
        }

        String namespace = OpenShiftUtils.getNamespacefromPodInputs();
        if (namespace == null)
            namespace = cause.getNamespace();
        String rootUrl = OpenShiftUtils.getJenkinsURL(
                getAuthenticatedOpenShiftClient(), namespace);
        String buildUrl = joinPaths(rootUrl, run.getUrl());
        String logsUrl = joinPaths(buildUrl, "/consoleText");
        String logsConsoleUrl = joinPaths(buildUrl, "/console");
        String logsBlueOceanUrl = null;
        try {
            // there are utility functions in the blueocean-dashboard plugin
            // which construct
            // the entire blueocean URI; however, attempting to pull that in as
            // a maven dependency was untenable from an injected test
            // perspective;
            // so we are leveraging reflection;
            Jenkins jenkins = Jenkins.getInstance();
            // NOTE, the excessive null checking is to keep `mvn findbugs:gui`
            // quiet
            if (jenkins != null) {
                PluginManager pluginMgr = jenkins.getPluginManager();
                if (pluginMgr != null) {
                    ClassLoader cl = pluginMgr.uberClassLoader;
                    if (cl != null) {
                        Class weburlbldr = cl
                                .loadClass("org.jenkinsci.plugins.blueoceandisplayurl.BlueOceanDisplayURLImpl");
                        Constructor ctor = weburlbldr.getConstructor();
                        Object displayURL = ctor.newInstance();
                        Method getRunURLMethod = weburlbldr.getMethod(
                                "getRunURL", hudson.model.Run.class);
                        Object blueOceanURI = getRunURLMethod.invoke(
                                displayURL, run);
                        logsBlueOceanUrl = blueOceanURI.toString();
                        logsBlueOceanUrl = logsBlueOceanUrl.replaceAll(
                                "http://unconfigured-jenkins-location/", "");
                        if (logsBlueOceanUrl.startsWith("http://")
                                || logsBlueOceanUrl.startsWith("https://"))
                            // still normalize string
                            logsBlueOceanUrl = joinPaths("", logsBlueOceanUrl);
                        else
                            logsBlueOceanUrl = joinPaths(rootUrl,
                                    logsBlueOceanUrl);
                    }
                }
            }
        } catch (Throwable t) {
            if (logger.isLoggable(Level.FINE))
                logger.log(Level.FINE, "upsertBuild", t);
        }

        Map<String, BlueRunResult> blueRunResults = new HashMap<String, BlueRunResult>();
        if (blueRun != null) {
            Iterator<BluePipelineNode> iter = blueRun.getNodes().iterator();
            while (iter.hasNext()) {
                BluePipelineNode node = iter.next();
                if (node != null) {
                    blueRunResults.put(node.getDisplayName(), node.getResult());
                }
            }
        }
        boolean pendingInput = false;
        if (!wfRunExt.get_links().self.href.matches("^https?://.*$")) {
            wfRunExt.get_links().self.setHref(joinPaths(rootUrl,
                    wfRunExt.get_links().self.href));
        }
        int newNumStages = wfRunExt.getStages().size();
        int newNumFlowNodes = 0;
        List<StageNodeExt> validStageList = new ArrayList<StageNodeExt>();
        for (StageNodeExt stage : wfRunExt.getStages()) {
            // the StatusExt.getStatus() cannot be trusted for declarative
            // pipeline;
            // for example, skipped steps/stages will be marked as complete;
            // we leverage the blue ocean state machine to determine this
            BlueRunResult result = blueRunResults.get(stage.getName());
            if (result != null && result == BlueRunResult.NOT_BUILT) {
                logger.info("skipping stage "
                        + stage.getName()
                        + " for the status JSON for pipeline run "
                        + run.getDisplayName()
                        + " because it was not executed (most likely because of a failure in another stage)");
                continue;
            }
            validStageList.add(stage);

            FlowNodeExt.FlowNodeLinks links = stage.get_links();
            if (!links.self.href.matches("^https?://.*$")) {
                links.self.setHref(joinPaths(rootUrl, links.self.href));
            }
            if (links.getLog() != null
                    && !links.getLog().href.matches("^https?://.*$")) {
                links.getLog().setHref(joinPaths(rootUrl, links.getLog().href));
            }
            newNumFlowNodes = newNumFlowNodes
                    + stage.getStageFlowNodes().size();
            for (AtomFlowNodeExt node : stage.getStageFlowNodes()) {
                FlowNodeExt.FlowNodeLinks nodeLinks = node.get_links();
                if (!nodeLinks.self.href.matches("^https?://.*$")) {
                    nodeLinks.self.setHref(joinPaths(rootUrl,
                            nodeLinks.self.href));
                }
                if (nodeLinks.getLog() != null
                        && !nodeLinks.getLog().href.matches("^https?://.*$")) {
                    nodeLinks.getLog().setHref(
                            joinPaths(rootUrl, nodeLinks.getLog().href));
                }
            }

            StatusExt status = stage.getStatus();
            if (status != null && status.equals(StatusExt.PAUSED_PENDING_INPUT)) {
                pendingInput = true;
            }
        }
        // override stages in case declarative has fooled base pipeline support
        wfRunExt.setStages(validStageList);

        boolean needToUpdate = this.shouldUpdateOpenShiftBuild(cause,
                newNumStages, newNumFlowNodes, wfRunExt.getStatus());
        if (!needToUpdate) {
            return;
        }

        String json;
        try {
            json = new ObjectMapper().writeValueAsString(wfRunExt);
        } catch (JsonProcessingException e) {
            logger.log(SEVERE, "Failed to serialize workflow run. " + e, e);
            return;
        }

        String pendingActionsJson = null;
        if (pendingInput && run instanceof WorkflowRun) {
            pendingActionsJson = getPendingActionsJson((WorkflowRun) run);
        }

        String phase = runToBuildPhase(run);

        long started = getStartTime(run);
        String startTime = null;
        String completionTime = null;
        if (started > 0) {
            startTime = formatTimestamp(started);

            long duration = getDuration(run);
            if (duration > 0) {
                completionTime = formatTimestamp(started + duration);
            }
        }

        logger.log(FINE, "Patching build {0}/{1}: setting phase to {2}",
                new Object[] { cause.getNamespace(), cause.getName(), phase });
        try {
            BuildFluent.MetadataNested<DoneableBuild> builder = getAuthenticatedOpenShiftClient()
                    .builds()
                    .inNamespace(cause.getNamespace())
                    .withName(cause.getName())
                    .edit()
                    .editMetadata()
                    .addToAnnotations(
                            OPENSHIFT_ANNOTATIONS_JENKINS_STATUS_JSON, json)
                    .addToAnnotations(OPENSHIFT_ANNOTATIONS_JENKINS_BUILD_URI,
                            buildUrl)
                    .addToAnnotations(OPENSHIFT_ANNOTATIONS_JENKINS_LOG_URL,
                            logsUrl)
                    .addToAnnotations(
                            Constants.OPENSHIFT_ANNOTATIONS_JENKINS_CONSOLE_LOG_URL,
                            logsConsoleUrl)
                    .addToAnnotations(
                            Constants.OPENSHIFT_ANNOTATIONS_JENKINS_BLUEOCEAN_LOG_URL,
                            logsBlueOceanUrl);

            String jenkinsNamespace = System.getenv("KUBERNETES_NAMESPACE");
            if (jenkinsNamespace != null && !jenkinsNamespace.isEmpty()) {
                builder.addToAnnotations(
                        OPENSHIFT_ANNOTATIONS_JENKINS_NAMESPACE,
                        jenkinsNamespace);
            }
            if (pendingActionsJson != null && !pendingActionsJson.isEmpty()) {
                builder.addToAnnotations(
                        OPENSHIFT_ANNOTATIONS_JENKINS_PENDING_INPUT_ACTION_JSON,
                        pendingActionsJson);
            }
            builder.endMetadata().editStatus().withPhase(phase)
                    .withStartTimestamp(startTime)
                    .withCompletionTimestamp(completionTime).endStatus().done();
        } catch (KubernetesClientException e) {
            if (HTTP_NOT_FOUND == e.getCode()) {
                runsToPoll.remove(run);
            } else {
                throw e;
            }
        }

        cause.setNumFlowNodes(newNumFlowNodes);
        cause.setNumStages(newNumStages);
        cause.setLastUpdateToOpenshift(TimeUnit.NANOSECONDS.toMillis(System
                .nanoTime()));
    }

    // annotate the Build with pending input JSON so consoles can do the
    // Proceed/Abort stuff if they want
    private String getPendingActionsJson(WorkflowRun run) {
        List<PendingInputActionsExt> pendingInputActions = new ArrayList<PendingInputActionsExt>();
        InputAction inputAction = run.getAction(InputAction.class);

        if (inputAction != null) {
            List<InputStepExecution> executions = inputAction.getExecutions();
            if (executions != null && !executions.isEmpty()) {
                for (InputStepExecution inputStepExecution : executions) {
                    pendingInputActions.add(PendingInputActionsExt.create(
                            inputStepExecution, run));
                }
            }
        }
        try {
            return new ObjectMapper().writeValueAsString(pendingInputActions);
        } catch (JsonProcessingException e) {
            logger.log(SEVERE, "Failed to serialize pending actions. " + e, e);
            return null;
        }
    }

    private long getStartTime(Run run) {
        return run.getStartTimeInMillis();
    }

    private long getDuration(Run run) {
        return run.getDuration();
    }

    private String runToBuildPhase(Run run) {
        if (run != null && !run.hasntStartedYet()) {
            if (run.isBuilding()) {
                return BuildPhases.RUNNING;
            } else {
                Result result = run.getResult();
                if (result != null) {
                    if (result.equals(Result.SUCCESS)) {
                        return BuildPhases.COMPLETE;
                    } else if (result.equals(Result.ABORTED)) {
                        return BuildPhases.CANCELLED;
                    } else if (result.equals(Result.FAILURE)) {
                        return BuildPhases.FAILED;
                    } else if (result.equals(Result.UNSTABLE)) {
                        return BuildPhases.FAILED;
                    } else {
                        return BuildPhases.PENDING;
                    }
                }
            }
        }
        return BuildPhases.NEW;
    }

    /**
     * Returns true if we should poll the status of this run
     *
     * @param run
     *            the Run to test against
     * @return true if the should poll the status of this build run
     */
    protected boolean shouldPollRun(Run run) {
        return run instanceof WorkflowRun
                && run.getCause(BuildCause.class) != null
                && GlobalPluginConfiguration.get().isEnabled();
    }
}
