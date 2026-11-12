package io.fabric8.jenkins.openshiftsync.runstatus;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Result;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Pins the JSON written to the {@code openshift.io/jenkins-status-json} and
 * {@code openshift.io/jenkins-pending-input-actions-json} Build annotations.
 * Timing values are replaced with 0 before comparing.
 */
@WithJenkins
class RunExtTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
    }

    private static String json(Object o) throws Exception {
        return new ObjectMapper().writeValueAsString(o).replaceAll("\"(\\w+Millis)\":\\d+", "\"$1\":0");
    }

    private WorkflowRun build(String name, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p.scheduleBuild2(0).waitForStart();
    }

    private WorkflowRun interrupted(String name, Result result) throws Exception {
        WorkflowRun b = build(name, "stage('ok') { echo 'a' }\nstage('wait') { sleep 60 }");
        j.waitForMessage("Sleeping for", b);
        b.getExecutor().interrupt(result);
        j.assertBuildStatus(result, j.waitForCompletion(b));
        return b;
    }

    private static final String INTERRUPTED_ERROR =
            "\"error\":{\"message\":null,\"type\":\"org.jenkinsci.plugins.workflow.steps.FlowInterruptedException\"},";

    private static String interruptedJson(String job, String status, String stageError) {
        return "{\"_links\":{\"self\":{\"href\":\"/job/" + job + "/1/wfapi/describe\"}},"
                + "\"id\":\"1\",\"name\":\"#1\",\"status\":\"" + status + "\","
                + "\"startTimeMillis\":0,\"endTimeMillis\":0,\"durationMillis\":0,\"queueDurationMillis\":0,\"pauseDurationMillis\":0,"
                + "\"stages\":["
                + "{\"_links\":{\"self\":{\"href\":\"/job/" + job + "/1/execution/node/4/wfapi/describe\"}},"
                + "\"id\":\"4\",\"name\":\"ok\",\"execNode\":\"\",\"status\":\"SUCCESS\","
                + "\"startTimeMillis\":0,\"durationMillis\":0,\"pauseDurationMillis\":0},"
                + "{\"_links\":{\"self\":{\"href\":\"/job/" + job + "/1/execution/node/9/wfapi/describe\"}},"
                + "\"id\":\"9\",\"name\":\"wait\",\"execNode\":\"\",\"status\":\"" + status + "\","
                + stageError
                + "\"startTimeMillis\":0,\"durationMillis\":0,\"pauseDurationMillis\":0}]}";
    }

    @Test
    void interruptedWithFailure() throws Exception {
        WorkflowRun b = interrupted("failure", Result.FAILURE);
        assertThat(json(RunExt.create(b)), is(interruptedJson("failure", "FAILED", INTERRUPTED_ERROR)));
    }

    @Test
    void interruptedWithNotBuilt() throws Exception {
        WorkflowRun b = interrupted("notbuilt", Result.NOT_BUILT);
        assertThat(json(RunExt.create(b)), is(interruptedJson("notbuilt", "NOT_EXECUTED", "")));
    }

    @Test
    void interruptedWithAborted() throws Exception {
        WorkflowRun b = interrupted("aborted", Result.ABORTED);
        assertThat(json(RunExt.create(b)), is(interruptedJson("aborted", "ABORTED", INTERRUPTED_ERROR)));
    }

    @Test
    void stages() throws Exception {
        WorkflowRun b = build("stages", "stage('A') { echo 'a' }\n"
                + "stage('P') { parallel x: { stage('X') { echo 'x' } }, y: { stage('Y') { unstable 'meh' } } }\n"
                + "stage('Outer') { stage('Inner') { echo 'i' } }\n"
                + "stage('F') { error 'boom' }\n"
                + "stage('never') { echo 'n' }");
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        String prefix = "{\"_links\":{\"self\":{\"href\":\"/job/stages/1/execution/node/";
        String times = "\"startTimeMillis\":0,\"durationMillis\":0,\"pauseDurationMillis\":0}";
        RunExt run = RunExt.create(b);
        List<String> stages = new ArrayList<>();
        for (StageNodeExt stage : run.getStages()) {
            stages.add(json(stage));
        }
        // "never" did not start, so it is not listed; P keeps the status the stage view API gave it
        assertThat(String.join("\n", stages), is(String.join("\n",
                prefix + "4/wfapi/describe\"}},\"id\":\"4\",\"name\":\"A\",\"execNode\":\"\",\"status\":\"SUCCESS\"," + times,
                prefix + "9/wfapi/describe\"}},\"id\":\"9\",\"name\":\"P\",\"execNode\":\"\",\"status\":\"SUCCESS\"," + times,
                prefix + "15/wfapi/describe\"}},\"id\":\"15\",\"name\":\"X\",\"execNode\":\"\",\"status\":\"SUCCESS\"," + times,
                prefix + "17/wfapi/describe\"}},\"id\":\"17\",\"name\":\"Y\",\"execNode\":\"\",\"status\":\"UNSTABLE\"," + times,
                prefix + "30/wfapi/describe\"}},\"id\":\"30\",\"name\":\"Outer\",\"execNode\":\"\",\"status\":\"SUCCESS\"," + times,
                prefix + "32/wfapi/describe\"}},\"id\":\"32\",\"name\":\"Inner\",\"execNode\":\"\",\"status\":\"SUCCESS\"," + times,
                prefix + "39/wfapi/describe\"}},\"id\":\"39\",\"name\":\"F\",\"execNode\":\"\",\"status\":\"FAILED\","
                        + "\"error\":{\"message\":\"boom\",\"type\":\"hudson.AbortException\"}," + times)));
        assertThat(run.getStatus(), is(StatusExt.FAILED));
    }

    @Test
    void pendingInput() throws Exception {
        WorkflowRun b = build("paused", "stage('before') { echo 'x' }\n"
                + "stage('Approve') { input id: 'Ok-1', message: 'Proceed?', ok: 'Go', parameters: ["
                + "string(name: 'S', defaultValue: 'd', description: 'desc'), "
                + "booleanParam(name: 'B', defaultValue: true), "
                + "choice(name: 'C', choices: ['x', 'y'])] }");
        InputAction inputAction = null;
        for (int i = 0; i < 300; i++) {
            inputAction = b.getAction(InputAction.class);
            if (inputAction != null && !inputAction.getExecutions().isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(json(RunExt.create(b)), is("{\"_links\":{\"self\":{\"href\":\"/job/paused/1/wfapi/describe\"},"
                + "\"pendingInputActions\":{\"href\":\"/job/paused/1/wfapi/pendingInputActions\"},"
                + "\"nextPendingInputAction\":{\"href\":\"/job/paused/1/wfapi/nextPendingInputAction\"}},"
                + "\"id\":\"1\",\"name\":\"#1\",\"status\":\"PAUSED_PENDING_INPUT\","
                + "\"startTimeMillis\":0,\"endTimeMillis\":0,\"durationMillis\":0,\"queueDurationMillis\":0,\"pauseDurationMillis\":0,"
                + "\"stages\":["
                + "{\"_links\":{\"self\":{\"href\":\"/job/paused/1/execution/node/4/wfapi/describe\"}},"
                + "\"id\":\"4\",\"name\":\"before\",\"execNode\":\"\",\"status\":\"SUCCESS\","
                + "\"startTimeMillis\":0,\"durationMillis\":0,\"pauseDurationMillis\":0},"
                + "{\"_links\":{\"self\":{\"href\":\"/job/paused/1/execution/node/9/wfapi/describe\"}},"
                + "\"id\":\"9\",\"name\":\"Approve\",\"execNode\":\"\",\"status\":\"PAUSED_PENDING_INPUT\","
                + "\"startTimeMillis\":0,\"durationMillis\":0,\"pauseDurationMillis\":0}]}"));

        List<PendingInputActionsExt> pending = new ArrayList<>();
        for (InputStepExecution execution : inputAction.getExecutions()) {
            pending.add(PendingInputActionsExt.create(execution, b));
        }
        assertThat(json(pending), is("[{\"id\":\"Ok-1\",\"proceedText\":\"Go\",\"message\":\"Proceed?\",\"inputs\":["
                + "{\"type\":\"StringParameterDefinition\",\"name\":\"S\",\"description\":\"desc\",\"definition\":{\"defaultVal\":\"d\"}},"
                + "{\"type\":\"BooleanParameterDefinition\",\"name\":\"B\",\"definition\":{\"defaultVal\":true}},"
                + "{\"type\":\"ChoiceParameterDefinition\",\"name\":\"C\",\"definition\":{\"defaultVal\":\"x\",\"choices\":[\"x\",\"y\"]}}],"
                + "\"proceedUrl\":\"/job/paused/1/wfapi/inputSubmit?inputId=Ok-1\","
                + "\"abortUrl\":\"/job/paused/1/input/Ok-1/abort\","
                + "\"redirectApprovalUrl\":\"/job/paused/1/input/\"}]"));

        b.getExecutor().interrupt(Result.ABORTED);
        j.waitForCompletion(b);
    }
}
