package io.fabric8.jenkins.openshiftsync;

import io.fabric8.jenkins.openshiftsync.runstatus.RunExt;
import io.fabric8.jenkins.openshiftsync.runstatus.StageNodeExt;
import org.junit.Test;

import static io.fabric8.jenkins.openshiftsync.BuildSyncRunListener.asJSON;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertFalse;

public class BuildSyncRunListenerTest {

  @Test
  public void testRunExtToJSON() throws Exception {
    StageNodeExt sn = new StageNodeExt();
    sn.setStageFlowNodeCount(1);
    RunExt re = new RunExt();
    re.setStages(singletonList(sn));
    String json = asJSON(re);
    assertFalse("json should not contain 'stageFlowNodes'", json.contains("stageFlowNodes"));
    assertFalse("json should not contain 'stageFlowNodeCount'", json.contains("stageFlowNodeCount"));
  }
}
