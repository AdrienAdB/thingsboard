/**
 * Copyright © 2016-2024 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.rule.engine.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.rule.engine.api.EmptyNodeConfiguration;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.data.util.TbPair;
import org.thingsboard.server.common.msg.TbMsg;

import java.util.concurrent.ExecutionException;

import static org.thingsboard.server.common.data.DataConstants.ACTIVITY_EVENT;
import static org.thingsboard.server.common.data.DataConstants.INACTIVITY_EVENT;

@Slf4j
@RuleNode(
        type = ComponentType.ACTION,
        name = "alarm rules",
        customRelations = true,
        version = 1,
        relationTypes = {"Alarm Created", "Alarm Updated", "Alarm Severity Updated", "Alarm Cleared", "Success", "Failure"},
        configClazz = EmptyNodeConfiguration.class,
        nodeDescription = "Process device or asset messages based on alarm rules.",
        nodeDetails = "Create and clear alarms based on alarm rules. The output relation type is either " +
                "'Alarm Created', 'Alarm Updated', 'Alarm Severity Updated' and 'Alarm Cleared' or simply 'Success' if no alarms were affected.",
        uiResources = {"static/rulenode/rulenode-core-config.js"},
        configDirective = "tbNodeEmptyConfig"
)
public class TbDeviceProfileNode implements TbNode {

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) throws ExecutionException, InterruptedException {
        try {
            switch (msg.getType()) {
                case "POST_TELEMETRY_REQUEST":
                case "POST_ATTRIBUTES_REQUEST":
                case ACTIVITY_EVENT:
                case INACTIVITY_EVENT:
                    ctx.getAlarmRuleStateService().process(ctx, msg);
                    break;
            }
            ctx.tellSuccess(msg);
        } catch (Exception e) {
            ctx.tellFailure(msg, e);
        }
    }

    @Override
    public TbPair<Boolean, JsonNode> upgrade(int fromVersion, JsonNode oldConfiguration) throws TbNodeException {
        boolean hasChanges = false;
        switch (fromVersion) {
            case 0:
                if (oldConfiguration.has("persistAlarmRulesState") || oldConfiguration.has("fetchAlarmRulesStateOnStart")) {
                    hasChanges = true;
                    ((ObjectNode) oldConfiguration).remove("persistAlarmRulesState");
                    ((ObjectNode) oldConfiguration).remove("fetchAlarmRulesStateOnStart");
                }
                break;
            default:
                break;
        }
        return new TbPair<>(hasChanges, oldConfiguration);
    }
}
