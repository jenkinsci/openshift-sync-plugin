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
import hudson.Util;
import hudson.model.ParameterDefinition;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;

/**
 * An entry of the {@code openshift.io/jenkins-pending-input-actions-json}
 * Build annotation. Field declaration order is the JSON property order.
 */
public class PendingInputActionsExt {
    private String id;
    private String proceedText;
    private String message;
    private List<InputParameterDefExt> inputs;
    private String proceedUrl;
    private String abortUrl;
    private String redirectApprovalUrl;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getProceedText() {
        return proceedText;
    }

    public void setProceedText(String proceedText) {
        this.proceedText = proceedText;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<InputParameterDefExt> getInputs() {
        return inputs;
    }

    public void setInputs(List<InputParameterDefExt> inputs) {
        this.inputs = inputs;
    }

    public String getProceedUrl() {
        return proceedUrl;
    }

    public void setProceedUrl(String proceedUrl) {
        this.proceedUrl = proceedUrl;
    }

    public String getAbortUrl() {
        return abortUrl;
    }

    public void setAbortUrl(String abortUrl) {
        this.abortUrl = abortUrl;
    }

    public String getRedirectApprovalUrl() {
        return redirectApprovalUrl;
    }

    public void setRedirectApprovalUrl(String redirectApprovalUrl) {
        this.redirectApprovalUrl = redirectApprovalUrl;
    }

    public static PendingInputActionsExt create(InputStepExecution execution, WorkflowRun run) {
        PendingInputActionsExt ext = new PendingInputActionsExt();
        String inputId = execution.getId();
        String runUrl = Urls.fullItemUrl(run.getUrl());
        ext.setId(inputId);
        ext.setProceedText(execution.getInput().getOk());
        ext.setMessage(execution.getInput().getMessage());
        ext.setInputs(inputParameters(inputId, run));
        ext.setProceedUrl(Urls.runApi(run) + "inputSubmit?inputId=" + URLEncoder.encode(inputId, StandardCharsets.UTF_8));
        ext.setAbortUrl(runUrl + "input/" + Util.rawEncode(inputId) + "/abort");
        ext.setRedirectApprovalUrl(runUrl + "input/");
        return ext;
    }

    private static List<InputParameterDefExt> inputParameters(String inputId, WorkflowRun run) {
        InputAction inputAction = run.getAction(InputAction.class);
        if (inputAction != null) {
            try {
                InputStepExecution execution = inputAction.getExecution(inputId);
                if (execution != null) {
                    List<InputParameterDefExt> inputParameters = new ArrayList<>();
                    for (ParameterDefinition definition : execution.getInput().getParameters()) {
                        inputParameters.add(new InputParameterDefExt(definition));
                    }
                    return inputParameters;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (TimeoutException e) {
                throw new IllegalStateException(e);
            }
        }
        return Collections.emptyList();
    }
}
