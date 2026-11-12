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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;

/** The {@code error} object of a stage. */
public class ErrorExt {
    private String message;
    private String type;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @SuppressFBWarnings(value = {"DCN_NULLPOINTER_EXCEPTION", "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"},
            justification = "Deserialisation can leave a null error, and Groovy exceptions can throw from getMessage(), GROOVY-8936")
    public static ErrorExt create(@NonNull ErrorAction errorAction) {
        ErrorExt errorExt = new ErrorExt();
        Throwable throwable = errorAction.getError();
        if (throwable != null) {
            try {
                errorExt.setMessage(throwable.getMessage());
            } catch (NullPointerException npe) {
                errorExt.setMessage("No message: NullPointerException on .getMessage()");
            }
            errorExt.setType(throwable.getClass().getName());
        } else {
            errorExt.setMessage("No message: null Throwable on error");
            errorExt.setType("No type: null Throwable on error");
        }
        return errorExt;
    }
}
