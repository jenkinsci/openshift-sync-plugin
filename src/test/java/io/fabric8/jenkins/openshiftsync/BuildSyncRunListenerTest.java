package io.fabric8.jenkins.openshiftsync;

import com.cloudbees.workflow.rest.external.AtomFlowNodeExt;
import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import org.junit.jupiter.api.Test;

import static io.fabric8.jenkins.openshiftsync.BuildSyncRunListener.asJSON;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BuildSyncRunListenerTest {

    @Test
    void testRunExtToJSON() throws Exception {
        StageNodeExt sn = new StageNodeExt();
        sn.setStageFlowNodes(singletonList(new AtomFlowNodeExt()));
        RunExt re = new RunExt();
        re.setStages(singletonList(sn));
        String json = asJSON(re);
        assertFalse(json.contains("stageFlowNodes"), "json should not contain 'stageFlowNodes'");
    }

}
