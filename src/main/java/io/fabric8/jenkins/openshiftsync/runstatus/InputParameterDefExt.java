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
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.ParameterDefinition;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import java.util.HashMap;
import java.util.Map;

/** A parameter of a pending {@code input} step. */
public class InputParameterDefExt {
    private final String type;
    private final String name;
    private final String description;
    private final Map<String, Object> definition;

    public InputParameterDefExt(@NonNull ParameterDefinition definition) {
        this.type = definition.getType();
        this.name = definition.getName();
        this.description = definition.getDescription();
        this.definition = toDefinitionMap(definition);
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDescription() {
        return description;
    }

    public Map<String, Object> getDefinition() {
        return definition;
    }

    static Map<String, Object> toDefinitionMap(@NonNull ParameterDefinition definition) {
        Map<String, Object> definitionMap = new HashMap<>();
        if (definition instanceof BooleanParameterDefinition) {
            definitionMap.put("defaultVal", ((BooleanParameterDefinition) definition).isDefaultValue());
        } else if (definition instanceof StringParameterDefinition) {
            definitionMap.put("defaultVal", ((StringParameterDefinition) definition).getDefaultValue());
        } else if (definition instanceof PasswordParameterDefinition) {
            definitionMap.put("defaultVal", ((PasswordParameterDefinition) definition).getDefaultValue());
        } else if (definition instanceof ChoiceParameterDefinition) {
            StringParameterValue defaultParameterValue = ((ChoiceParameterDefinition) definition).getDefaultParameterValue();
            if (defaultParameterValue != null) {
                definitionMap.put("defaultVal", defaultParameterValue.getValue());
            }
            definitionMap.put("choices", ((ChoiceParameterDefinition) definition).getChoices());
        }
        return definitionMap;
    }
}
