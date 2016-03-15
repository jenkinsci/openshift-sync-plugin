package io.fabric8.jenkins.openshiftsync;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class JoinPathsTest {
  @Test
  public void testJoinPaths() throws Exception {
    assertJoinPaths("http://localhost:8080/job/cheese/12/wfapi/describe", "http://localhost:8080/", "job/cheese/12/", "/wfapi/describe");
    assertJoinPaths("http://localhost:8080/job/cheese/12/wfapi/describe", "http://localhost:8080/", "/job/cheese/12/", "/wfapi/describe");
    assertJoinPaths("http://localhost:8080/job/cheese/12/wfapi/describe", "http://localhost:8080", "job/cheese/12/", "wfapi/describe");
  }

  private void assertJoinPaths(String expected, String... strings) {
    String actual = BuildSyncRunListener.joinPaths(strings);
    assertEquals("Join strings: " + Arrays.asList(strings), expected, actual);
  }

}