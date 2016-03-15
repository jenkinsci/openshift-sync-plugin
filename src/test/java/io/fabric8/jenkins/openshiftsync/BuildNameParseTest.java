/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 */
public class BuildNameParseTest {
  @Test
  public void testParseBuildName() throws Exception {
    assertBuildNameParse("job/cheese/123/", "cheese", "123");
    assertBuildNameParse("/job/cheese/123/", "cheese", "123");
  }

  public static BuildName assertBuildNameParse(String url, String expectedJob, String expectedBuild) {
    BuildName buildName = BuildName.parseBuildUrl(url);
    assertEquals("Job name for url `" + url+ "`", expectedJob, buildName.getJobName());
    assertEquals("Build name for url `" + url+ "`", expectedBuild, buildName.getBuildName());
    return buildName;
  }

}
