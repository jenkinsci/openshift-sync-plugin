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

/**
 * Common fields of a flow node in the status JSON. Field declaration order is
 * the JSON property order and is part of the published format.
 */
public class FlowNodeExt {
    private FlowNodeLinks _links;
    private String id;
    private String name;
    private String execNode;
    private StatusExt status;
    private ErrorExt error;
    private String parameterDescription;
    private long startTimeMillis;
    private long durationMillis;
    private long pauseDurationMillis;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public FlowNodeLinks get_links() {
        return _links;
    }

    public void set_links(FlowNodeLinks _links) {
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getExecNode() {
        return execNode;
    }

    public void setExecNode(String execNode) {
        this.execNode = execNode;
    }

    public StatusExt getStatus() {
        return status;
    }

    public void setStatus(StatusExt status) {
        this.status = status;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ErrorExt getError() {
        return error;
    }

    public void setError(ErrorExt error) {
        this.error = error;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getParameterDescription() {
        return parameterDescription;
    }

    public void setParameterDescription(String parameterDescription) {
        this.parameterDescription = parameterDescription;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public void setStartTimeMillis(long startTimeMillis) {
        this.startTimeMillis = startTimeMillis;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public long getPauseDurationMillis() {
        return pauseDurationMillis;
    }

    public void setPauseDurationMillis(long pauseDurationMillis) {
        this.pauseDurationMillis = pauseDurationMillis;
    }

    public static final class FlowNodeLinks extends Links {
        private Link log;
        private Link console;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Link getLog() {
            return log;
        }

        public void setLog(Link log) {
            this.log = log;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Link getConsole() {
            return console;
        }

        public void setConsole(Link console) {
            this.console = console;
        }
    }
}
