/**
 * Copyright (C) 2016 Red Hat, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 *
 */
class BuildNameParseTest {

    @Test
    void testParseBuildName() {
        assertBuildNameParse("job/cheese/123/", "cheese", "123");
        assertBuildNameParse("/job/cheese/123/", "cheese", "123");
    }

    private static void assertBuildNameParse(String url, String expectedJob, String expectedBuild) {
        BuildName buildName = BuildName.parseBuildUrl(url);
        assertEquals(expectedJob, buildName.getJobName(), "Job name for url `" + url + "`");
        assertEquals(expectedBuild, buildName.getBuildName(), "Build name for url `" + url + "`");
    }

}
