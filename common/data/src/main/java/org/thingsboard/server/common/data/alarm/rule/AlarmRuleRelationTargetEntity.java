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
package org.thingsboard.server.common.data.alarm.rule;

import lombok.Data;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.relation.EntitySearchDirection;

@Data
public class AlarmRuleRelationTargetEntity implements AlarmRuleTargetEntity {

    private static final long serialVersionUID = 6279050141475715964L;

    private final EntitySearchDirection direction;

    private final String relationType;

    @Override
    public AlarmRuleTargetEntityType getType() {
        return AlarmRuleTargetEntityType.RELATION;
    }

    @Override
    public EntityId getTargetEntity(EntityId entityId) {
        throw new RuntimeException("Not implemented");
    }
}
