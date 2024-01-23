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
package org.thingsboard.server.service.alarm.rule;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.rule.engine.api.TbAlarmRuleStateService;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.HasProfileId;
import org.thingsboard.server.common.data.alarm.rule.AlarmRule;
import org.thingsboard.server.common.data.alarm.rule.filter.AlarmRuleEntityFilter;
import org.thingsboard.server.common.data.asset.AssetProfile;
import org.thingsboard.server.common.data.id.AlarmRuleId;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.id.HasId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.util.CollectionsUtil;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TbMsgCallback;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.common.util.ProtoUtils;
import org.thingsboard.server.dao.alarm.rule.AlarmRuleService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.ToTbAlarmRuleStateServiceMsg;
import org.thingsboard.server.queue.TbQueueConsumer;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.queue.discovery.TbApplicationEventListener;
import org.thingsboard.server.queue.discovery.event.PartitionChangeEvent;
import org.thingsboard.server.queue.provider.TbAlarmRulesQueueFactory;
import org.thingsboard.server.queue.util.TbRuleEngineComponent;
import org.thingsboard.server.service.alarm.rule.state.PersistedEntityState;
import org.thingsboard.server.service.alarm.rule.store.AlarmRuleEntityStateStore;
import org.thingsboard.server.service.profile.TbAssetProfileCache;
import org.thingsboard.server.service.profile.TbDeviceProfileCache;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

import static org.thingsboard.server.common.data.util.CollectionsUtil.diffSets;

@Slf4j
@Service
@TbRuleEngineComponent
@RequiredArgsConstructor
public class DefaultTbAlarmRuleStateService extends TbApplicationEventListener<PartitionChangeEvent> implements TbAlarmRuleStateService {

    @Value("${queue.ar.poll-interval:25}")
    private long pollDuration;
    @Value("${queue.ar.pack-processing-timeout:60000}")
    private long packProcessingTimeout;

    private final TbAlarmRuleContext ctx;
    private final AlarmRuleService alarmRuleService;
    private final AlarmRuleEntityStateStore stateStore;
    private final PartitionService partitionService;
    //    private final RelationService relationService;
    private final TbDeviceProfileCache deviceProfileCache;
    private final TbAssetProfileCache assetProfileCache;

    //    private final TbQueueProducerProvider producerProvider;
    private final TbAlarmRulesQueueFactory queueFactory;
    private volatile ExecutorService consumerExecutor;
    private volatile ListeningExecutorService consumerLoopExecutor;
    private volatile TbQueueConsumer<TbProtoQueueMsg<ToTbAlarmRuleStateServiceMsg>> consumer;
    //    private volatile TbQueueProducer<TbProtoQueueMsg<ToTbAlarmRuleStateServiceMsg>> producer;
    private volatile boolean stopped = false;

    private ScheduledExecutorService scheduler;

    private final Map<EntityId, EntityState> entityStates = new ConcurrentHashMap<>();
    private final Map<TenantId, Map<AlarmRuleId, AlarmRule>> rules = new ConcurrentHashMap<>();
    private final Map<AlarmRuleId, Set<EntityId>> myEntityStateIdsPerAlarmRule = new ConcurrentHashMap<>();
    private final Map<TenantId, Set<EntityId>> myEntityStateIds = new ConcurrentHashMap<>();

    private final Set<TopicPartitionInfo> myPartitions = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(ThingsBoardThreadFactory.forName("alarm-rule-service"));

        consumerExecutor = Executors.newSingleThreadExecutor(ThingsBoardThreadFactory.forName("ar-consumer"));
        consumerLoopExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor(ThingsBoardThreadFactory.forName("ar-consumer-loop")));
//        producer = producerProvider.getTbAlarmRulesMsgProducer();
        consumer = queueFactory.createToAlarmRulesMsgConsumer();

        scheduler.scheduleAtFixedRate(this::harvestAlarms, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    private void destroy() {
        stopped = true;
        if (consumer != null) {
            consumer.unsubscribe();
        }
        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
        }
        if (consumerLoopExecutor != null) {
            consumerLoopExecutor.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void process(TbContext tbContext, TbMsg msg) throws Exception {
        TbAlarmRuleRequestCtx requestCtx = new TbAlarmRuleRequestCtx();
        requestCtx.setRuleChainId(tbContext.getSelf().getRuleChainId());
        requestCtx.setRuleNodeId(tbContext.getSelfId());
        requestCtx.setDebugMode(tbContext.getSelf().isDebugMode());
        doProcess(requestCtx, tbContext.getTenantId(), msg);
    }

    @Override
    public void process(TenantId tenantId, TbMsg msg) throws Exception {
        doProcess(null, tenantId, msg);
    }

    private void doProcess(TbAlarmRuleRequestCtx requestCtx, TenantId tenantId, TbMsg msg) throws Exception {
        EntityState entityState = getOrCreateEntityState(tenantId, msg.getOriginator());
        if (entityState != null) {
            entityState.process(requestCtx, msg);
        }
    }

    @Override
    public <T extends HasProfileId<? extends EntityId>> void processEntityUpdated(TenantId tenantId, EntityId entityId, T entity) {
        EntityState entityState = entityStates.get(entityId);
        if (entityState != null) {
            entityState.getLock().lock();
            try {
                EntityId currentProfileId = entityState.getProfileId();
                if (!currentProfileId.equals(entity.getProfileId())) {
                    List<AlarmRule> oldAlarmRules = entityState.getAlarmRules();
                    List<AlarmRule> newAlarmRules = getAlarmRulesForEntity(tenantId, entityId);

                    List<AlarmRule> toAdd = CollectionsUtil.diffLists(oldAlarmRules, newAlarmRules);
                    List<AlarmRuleId> toRemoveIds = CollectionsUtil.diffLists(newAlarmRules, oldAlarmRules).stream()
                            .map(AlarmRule::getId)
                            .collect(Collectors.toList());

                    toAdd.forEach(entityState::addAlarmRule);
                    entityState.removeAlarmRules(toRemoveIds);
                    toRemoveIds.forEach(alarmRuleId -> myEntityStateIdsPerAlarmRule.get(alarmRuleId).remove(entityId));

                    if (entityState.isEmpty()) {
                        entityStates.remove(entityId);
                        myEntityStateIds.get(tenantId).remove(entityId);
                        stateStore.remove(tenantId, entityId);
                    }
                }
            } finally {
                entityState.getLock().unlock();
            }
        }
    }

    @Override
    public void processEntityDeleted(TenantId tenantId, EntityId entityId) {
        EntityState state = entityStates.remove(entityId);
        if (state != null) {
            myEntityStateIdsPerAlarmRule.values().forEach(ids -> ids.remove(entityId));
            myEntityStateIds.get(tenantId).remove(entityId);
            stateStore.remove(tenantId, entityId);
        }
    }

    @Override
    public void createAlarmRule(TenantId tenantId, AlarmRuleId alarmRuleId) {
        AlarmRule alarmRule = alarmRuleService.findAlarmRuleById(tenantId, alarmRuleId);
        if (alarmRule.isEnabled()) {
            addAlarmRule(tenantId, alarmRule);
        }
    }

    @Override
    public void updateAlarmRule(TenantId tenantId, AlarmRuleId alarmRuleId) {
        AlarmRule alarmRule = alarmRuleService.findAlarmRuleById(tenantId, alarmRuleId);
        if (alarmRule.isEnabled()) {
            Map<AlarmRuleId, AlarmRule> tenantRules = rules.get(tenantId);
            if (tenantRules != null) {
                if (tenantRules.containsKey(alarmRuleId)) {
                    tenantRules.put(alarmRule.getId(), alarmRule);

                    Set<EntityId> stateIds = myEntityStateIdsPerAlarmRule.get(alarmRule.getId());

                    stateIds.forEach(stateId -> {
                        EntityState entityState = entityStates.get(stateId);
                        try {
                            entityState.updateAlarmRule(alarmRule);
                        } catch (Exception e) {
                            log.error("[{}] [{}] Failed to update alarm rule!", tenantId, alarmRule.getName(), e);
                        }
                    });
                } else {
                    addAlarmRule(tenantId, alarmRule);
                }
            }
        } else {
            deleteAlarmRule(tenantId, alarmRuleId);
        }
    }

    private void addAlarmRule(TenantId tenantId, AlarmRule alarmRule) {
        Map<AlarmRuleId, AlarmRule> tenantRules = rules.get(tenantId);
        if (tenantRules != null) {
            tenantRules.put(alarmRule.getId(), alarmRule);
            myEntityStateIds.get(tenantId).stream()
                    .filter(entityId -> isEntityMatches(tenantId, entityId, alarmRule))
                    .forEach(entityId -> {
                                EntityState entityState = entityStates.get(entityId);
                                myEntityStateIdsPerAlarmRule.computeIfAbsent(alarmRule.getId(), key -> ConcurrentHashMap.newKeySet()).add(entityId);
                                entityState.addAlarmRule(alarmRule);
                            }
                    );
        }
    }

    @Override
    public void deleteAlarmRule(TenantId tenantId, AlarmRuleId alarmRuleId) {
        Map<AlarmRuleId, AlarmRule> tenantRules = rules.get(tenantId);

        if (tenantRules == null) {
            return;
        }

        tenantRules.remove(alarmRuleId);

        Set<EntityId> stateIds = myEntityStateIdsPerAlarmRule.remove(alarmRuleId);

        if (stateIds != null) {
            stateIds.forEach(id -> {
                EntityState entityState = entityStates.get(id);
                Lock lock = entityState.getLock();
                try {
                    lock.lock();
                    entityState.removeAlarmRule(alarmRuleId);
                    if (entityState.isEmpty()) {
                        entityStates.remove(entityState.getEntityId());
                        myEntityStateIds.get(tenantId).remove(entityState.getEntityId());
                        stateStore.remove(tenantId, id);
                    }
                } finally {
                    lock.unlock();
                }
            });
        }
    }

    @Override
    public void deleteTenant(TenantId tenantId) {
        Map<AlarmRuleId, AlarmRule> tenantRules = rules.get(tenantId);
        if (tenantRules != null) {
            tenantRules.keySet().forEach(alarmRuleId -> deleteAlarmRule(tenantId, alarmRuleId));
            rules.remove(tenantId);
        }
    }

    @Override
    protected void onTbApplicationEvent(PartitionChangeEvent event) {
        if (ServiceType.TB_ALARM_RULES_EXECUTOR.equals(event.getServiceType())) {
            Set<TopicPartitionInfo> addedPartitions = diffSets(myPartitions, event.getPartitions());
            Set<TopicPartitionInfo> removedPartitions = diffSets(event.getPartitions(), myPartitions);

            myPartitions.removeAll(removedPartitions);
            myPartitions.addAll(event.getPartitions());

            log.info("calculated removedPartitions: {}", removedPartitions);

            List<EntityId> toRemove = new ArrayList<>();
            entityStates.values().forEach(entityState -> {
                TenantId tenantId = entityState.getTenantId();
                EntityId entityId = entityState.getEntityId();
                if (!isLocalEntity(tenantId, entityId)) {
                    toRemove.add(entityId);
                }
            });

            log.info("calculated removedEntityStates: {}", toRemove.size());


            toRemove.forEach(entityStates::remove);
            myEntityStateIdsPerAlarmRule.values().forEach(ids -> toRemove.forEach(ids::remove));

            log.info("calculated addedPartitions: {}", addedPartitions);

            AtomicInteger addedEntityStates = new AtomicInteger(0);

            addedPartitions.forEach(tpi -> {
                        List<PersistedEntityState> states = stateStore.getAll(tpi);
                        addedEntityStates.addAndGet(states.size());
                        states.forEach(ares -> createEntityState(ares.getTenantId(), ares.getEntityId(), ares));
                    }
            );

            log.info("calculated addedEntityStates: {}", addedEntityStates.get());

            consumer.subscribe(event.getPartitions());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(value = 2)
    public void onApplicationEvent(ApplicationReadyEvent event) {
        consumerExecutor.execute(() -> consumerLoop(consumer));
    }

    private void consumerLoop(TbQueueConsumer<TbProtoQueueMsg<ToTbAlarmRuleStateServiceMsg>> consumer) {
        while (!stopped && !consumer.isStopped()) {
            List<ListenableFuture<?>> futures = new ArrayList<>();
            try {
                List<TbProtoQueueMsg<ToTbAlarmRuleStateServiceMsg>> msgs = consumer.poll(pollDuration);
                if (msgs.isEmpty()) {
                    continue;
                }
                for (TbProtoQueueMsg<ToTbAlarmRuleStateServiceMsg> msgWrapper : msgs) {
                    TransportProtos.ToTbAlarmRuleStateServiceMsg msg = msgWrapper.getValue();
                    ListenableFuture<?> future = consumerLoopExecutor.submit(() -> {
                        try {
                            processMessage(msg);
                        } catch (Exception e) {
                            log.warn("Failed to process alarm rule msg! [{}]", msg, e);
                        }
                    });
                    futures.add(future);
                }
                try {
                    Futures.allAsList(futures).get(packProcessingTimeout, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    log.info("Timeout for processing the alarm rules tasks.", e);
                }
                consumer.commit();
            } catch (Exception e) {
                if (!stopped) {
                    log.warn("Failed to obtain alarm rules requests from queue.", e);
                    try {
                        Thread.sleep(pollDuration);
                    } catch (InterruptedException e2) {
                        log.trace("Failed to wait until the server has capacity to handle new alarm rules messages", e2);
                    }
                }
            }
        }
        log.info("TB Alarm Rules request consumer stopped.");
    }

    private void processMessage(ToTbAlarmRuleStateServiceMsg msgProto) throws Exception {
        TenantId tenantId = TenantId.fromUUID(new UUID(msgProto.getTenantIdMSB(), msgProto.getTenantIdLSB()));

        if (msgProto.hasEntityUpdateMsg()) {
            TransportProtos.EntityUpdateMsg entityUpdateMsg = msgProto.getEntityUpdateMsg();
            switch (entityUpdateMsg.getEntityUpdateCase()) {
                case DEVICE -> {
                    var device = ProtoUtils.fromProto(entityUpdateMsg.getDevice());
                    processEntityUpdated(tenantId, device.getId(), device);
                }
                case ASSET -> {
                    var asset = ProtoUtils.fromProto(entityUpdateMsg.getAsset());
                    processEntityUpdated(tenantId, asset.getId(), asset);
                }
            }
        } else if (msgProto.hasEntityDeleteMsg()) {
            TransportProtos.EntityDeleteMsg entityDeleteMsg = msgProto.getEntityDeleteMsg();
            EntityId entityId = EntityIdFactory.getByTypeAndUuid(entityDeleteMsg.getEntityType(),
                    new UUID(entityDeleteMsg.getEntityIdMSB(), entityDeleteMsg.getEntityIdLSB()));
            processEntityDeleted(tenantId, entityId);
        } else {
            TbMsg tbMsg = TbMsg.fromBytes(msgProto.getQueueName(), msgProto.getTbMsg().toByteArray(), TbMsgCallback.EMPTY);
            if (msgProto.hasAlarmRuleRequest()) {
                TransportProtos.TbAlarmRuleRequestCtxProto alarmRuleRequest = msgProto.getAlarmRuleRequest();

                RuleChainId ruleChainId = new RuleChainId(new UUID(alarmRuleRequest.getRuleChainIdMSB(), alarmRuleRequest.getRuleChainIdLSB()));
                RuleNodeId ruleNodeId = new RuleNodeId(new UUID(alarmRuleRequest.getRuleNodeIdMSB(), alarmRuleRequest.getRuleNodeIdLSB()));
                TbAlarmRuleRequestCtx ctx = new TbAlarmRuleRequestCtx();
                ctx.setRuleChainId(ruleChainId);
                ctx.setRuleNodeId(ruleNodeId);
                ctx.setDebugMode(alarmRuleRequest.getIsDebug());
                doProcess(ctx, tenantId, tbMsg);
            } else {
                process(tenantId, tbMsg);
            }
        }
    }

    private void harvestAlarms() {
        long ts = System.currentTimeMillis();
        for (EntityState state : entityStates.values()) {
            try {
                state.harvestAlarms(ts);
            } catch (Exception e) {
                log.warn("[{}] [{}] Failed to harvest alarms.", state.getTenantId(), state.getEntityId());
            }
        }
    }

    private EntityState getOrCreateEntityState(TenantId tenantId, EntityId msgOriginator) {
        EntityState entityState = entityStates.get(msgOriginator);

        if (entityState != null) {
            return entityState;
        }

        List<AlarmRule> filteredRules = getAlarmRulesForEntity(tenantId, msgOriginator);

        if (filteredRules.isEmpty()) {
            return null;
        }

//        Map<EntityId, List<AlarmRule>> entityAlarmRules = new HashMap<>();
//
//        filteredRules.forEach(alarmRule -> {
//            List<EntityId> targetEntities = getTargetEntities(tenantId, msgOriginator, alarmRule.getConfiguration().getAlarmTargetEntity());
//            targetEntities.forEach(targetEntityId -> entityAlarmRules.computeIfAbsent(targetEntityId, key -> new ArrayList<>()).add(alarmRule));
//        });

//        return entityAlarmRules
//                .entrySet()
//                .stream()
//                .map(entry -> getOrCreateEntityState(tenantId, entry.getKey(), entry.getValue(), null))
//                .collect(Collectors.toList());
        return getOrCreateEntityState(tenantId, msgOriginator, filteredRules, null);
    }

    private List<AlarmRule> getAlarmRulesForEntity(TenantId tenantId, EntityId entityId) {
        return getOrFetchAlarmRules(tenantId)
                .stream()
                .filter(rule -> isEntityMatches(tenantId, entityId, rule))
                .collect(Collectors.toList());
    }

    private EntityState getOrCreateEntityState(TenantId tenantId, EntityId targetEntityId, List<AlarmRule> alarmRules, PersistedEntityState persistedEntityState) {
        EntityState entityState = entityStates.get(targetEntityId);
        if (entityState == null) {
            entityState = new EntityState(tenantId, targetEntityId, getProfileId(tenantId, targetEntityId), ctx, new EntityRulesState(alarmRules), persistedEntityState);
            myEntityStateIds.computeIfAbsent(tenantId, key -> ConcurrentHashMap.newKeySet()).add(targetEntityId);
            entityStates.put(targetEntityId, entityState);
            alarmRules.forEach(alarmRule -> myEntityStateIdsPerAlarmRule.computeIfAbsent(alarmRule.getId(), key -> ConcurrentHashMap.newKeySet()).add(targetEntityId));
        } else {
            for (AlarmRule alarmRule : alarmRules) {
                Set<EntityId> entityIds = myEntityStateIdsPerAlarmRule.get(alarmRule.getId());
                if (entityIds == null || !entityIds.contains(targetEntityId)) {
                    myEntityStateIdsPerAlarmRule.computeIfAbsent(alarmRule.getId(), key -> ConcurrentHashMap.newKeySet()).add(targetEntityId);
                    entityState.addAlarmRule(alarmRule);
                }
            }
        }

        return entityState;
    }

    private EntityId getProfileId(TenantId tenantId, EntityId entityId) {
        HasId<? extends EntityId> profile;
        switch (entityId.getEntityType()) {
            case ASSET:
                profile = assetProfileCache.get(tenantId, (AssetId) entityId);
                break;
            case DEVICE:
                profile = deviceProfileCache.get(tenantId, (DeviceId) entityId);
                break;
            default:
                throw new IllegalArgumentException("Wrong entity type: " + entityId.getEntityType());
        }
        return profile.getId();
    }

    private void createEntityState(TenantId tenantId, EntityId entityId, PersistedEntityState persistedEntityState) {
        Set<AlarmRuleId> alarmRuleIds =
                persistedEntityState.getAlarmStates().keySet().stream().map(id -> new AlarmRuleId(UUID.fromString(id))).collect(Collectors.toSet());

        List<AlarmRule> filteredRules =
                getOrFetchAlarmRules(tenantId)
                        .stream()
                        .filter(rule -> alarmRuleIds.contains(rule.getId()))
                        .collect(Collectors.toList());

        getOrCreateEntityState(tenantId, entityId, filteredRules, persistedEntityState);
    }

    private Collection<AlarmRule> getOrFetchAlarmRules(TenantId tenantId) {
        return rules.computeIfAbsent(tenantId, key -> {
            Map<AlarmRuleId, AlarmRule> map = new HashMap<>();
            fetchAlarmRules(key).forEach(alarmRule -> {
                map.put(alarmRule.getId(), alarmRule);
            });
            return map;
        }).values();
    }

    private List<AlarmRule> fetchAlarmRules(TenantId tenantId) {
        List<AlarmRule> alarmRules = new ArrayList<>();

        PageLink pageLink = new PageLink(1024);
        PageData<AlarmRule> pageData;

        do {
            pageData = alarmRuleService.findEnabledAlarmRules(tenantId, pageLink);

            alarmRules.addAll(pageData.getData());

            pageLink = pageLink.nextPageLink();
        } while (pageData.hasNext());

        return alarmRules;
    }

//    private List<EntityId> getTargetEntities(TenantId tenantId, EntityId originator, AlarmRuleTargetEntity targetEntity) {
//        switch (targetEntity.getType()) {
//            case ORIGINATOR:
//                return Collections.singletonList(originator);
//            case SINGLE_ENTITY:
//                return Collections.singletonList(((AlarmRuleSpecifiedTargetEntity) targetEntity).getEntityId());
//            case RELATION:
//                AlarmRuleRelationTargetEntity relationTargetEntity = (AlarmRuleRelationTargetEntity) targetEntity;
//                if (EntitySearchDirection.FROM == relationTargetEntity.getDirection()) {
//                    List<EntityRelation> relations = relationService.findByToAndType(tenantId, originator, relationTargetEntity.getRelationType(), RelationTypeGroup.COMMON);
//                    return relations.stream().map(EntityRelation::getFrom).collect(Collectors.toList());
//                } else {
//                    List<EntityRelation> relations = relationService.findByFromAndType(tenantId, originator, relationTargetEntity.getRelationType(), RelationTypeGroup.COMMON);
//                    return relations.stream().map(EntityRelation::getTo).collect(Collectors.toList());
//                }
//        }
//        return Collections.emptyList();
//    }

    private boolean isEntityMatches(TenantId tenantId, EntityId entityId, AlarmRule alarmRule) {
        List<AlarmRuleEntityFilter> sourceEntityFilters = alarmRule.getConfiguration().getSourceEntityFilters();

        for (AlarmRuleEntityFilter filter : sourceEntityFilters) {
            if (isEntityMatches(tenantId, entityId, filter)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEntityMatches(TenantId tenantId, EntityId entityId, AlarmRuleEntityFilter filter) {
        switch (filter.getType()) {
            case DEVICE_TYPE:
                if (entityId.getEntityType() == EntityType.DEVICE) {
                    DeviceProfile deviceProfile = deviceProfileCache.get(tenantId, (DeviceId) entityId);
                    return deviceProfile != null && filter.isEntityMatches(deviceProfile.getId());
                }
                break;
            case ASSET_TYPE:
                if (entityId.getEntityType() == EntityType.ASSET) {
                    AssetProfile assetProfile = assetProfileCache.get(tenantId, (AssetId) entityId);
                    return assetProfile != null && filter.isEntityMatches(assetProfile.getId());
                }
                break;
            default:
                return filter.isEntityMatches(entityId);
        }
        return false;
    }

    private boolean isLocalEntity(TenantId tenantId, EntityId entityId) {
        return partitionService.resolve(ServiceType.TB_ALARM_RULES_EXECUTOR, tenantId, entityId).isMyPartition();
    }
}
