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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** A HAL style link, serialised as {@code {"href": "..."}}. */
public class Link {
    @SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "Serialised as a public field to keep the JSON shape.")
    public String href;

    public static Link newLink(String href) {
        return new Link().setHref(href);
    }

    public Link setHref(String href) {
        this.href = href;
        return this;
    }
}
