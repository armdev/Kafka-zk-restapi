package org.gnuhpc.bigdata.service.impl;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import kafka.admin.*;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.cluster.Broker;
import kafka.common.OffsetAndMetadata;
import kafka.common.Topic;
import kafka.common.TopicAndPartition;
import kafka.coordinator.GroupOverview;
import kafka.coordinator.GroupTopicPartition;
import kafka.javaapi.OffsetRequest;
import kafka.javaapi.OffsetResponse;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.server.ConfigType;
import kafka.utils.ZKGroupTopicDirs;
import kafka.utils.ZkUtils;
import lombok.extern.log4j.Log4j;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.gnuhpc.bigdata.CollectionConvertor;
import org.gnuhpc.bigdata.componet.OffsetStorage;
import org.gnuhpc.bigdata.constant.ConsumerState;
import org.gnuhpc.bigdata.constant.ConsumerType;
import org.gnuhpc.bigdata.constant.GeneralResponseState;
import org.gnuhpc.bigdata.model.*;
import org.gnuhpc.bigdata.service.IKafkaAdminService;
import org.gnuhpc.bigdata.task.FetchOffSetFromZKResult;
import org.gnuhpc.bigdata.task.FetchOffsetFromZKTask;
import org.gnuhpc.bigdata.utils.KafkaUtils;
import org.gnuhpc.bigdata.utils.ZookeeperUtils;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import scala.Option;
import scala.Tuple2;
import scala.collection.JavaConverters;
import scala.collection.Seq;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

/**
 * Created by gnuhpc on 2017/7/17.
 */

@Service
@Log4j
@Validated
public class KafkaAdminService implements IKafkaAdminService {

    private static final int channelSocketTimeoutMs = 600;
    private static final int channelRetryBackoffMs = 600;
    private static final String DEFAULTCP = "kafka-rest-consumergroup";
    private static final String CONSUMERPATHPREFIX = "/consumers/";
    private static final String OFFSETSPATHPREFIX = "/offsets/";
    @Autowired
    private ZookeeperUtils zookeeperUtils;

    @Autowired
    private KafkaUtils kafkaUtils;

    @Autowired
    private OffsetStorage storage;

    private AdminClient kafkaAdminClient;

    //For AdminUtils use
    private ZkUtils zkUtils;

    //For zookeeper connection
    private CuratorFramework zkClient;

    //For Json serialized
    private Gson gson;

    private scala.Option<String> NONE = scala.Option.apply(null);

    @PostConstruct
    private void init() {
        this.zkUtils = zookeeperUtils.getZkUtils();
        this.zkClient = zookeeperUtils.getCuratorClient();
        GsonBuilder builder = new GsonBuilder();
        builder.registerTypeAdapter(DateTime.class, (JsonDeserializer<DateTime>) (jsonElement, type, jsonDeserializationContext) -> new DateTime(jsonElement.getAsJsonPrimitive().getAsLong()));

        this.gson = builder.create();
        this.kafkaAdminClient = kafkaUtils.getKafkaAdminClient();
    }

    @Override
    public TopicMeta createTopic(TopicDetail topic, String reassignStr) {
        if (StringUtils.isEmpty(topic.getName())) {
            throw new InvalidTopicException("Empty topic name");
        }

        if (Topic.hasCollisionChars(topic.getName())) {
            throw new InvalidTopicException("Invalid topic name");
        }

        if (Strings.isNullOrEmpty(reassignStr) && topic.getPartitions() <= 0) {
            throw new InvalidTopicException("Number of partitions must be larger than 0");
        }
        Topic.validate(topic.getName());


        if (Strings.isNullOrEmpty(reassignStr)) {
            AdminUtils.createTopic(zkUtils,
                    topic.getName(), topic.getPartitions(), topic.getFactor(),
                    topic.getProp(), RackAwareMode.Enforced$.MODULE$);
        } else {
            List<String> argsList = new ArrayList<>();
            argsList.add("--topic");
            argsList.add(topic.getName());

            if (topic.getProp().stringPropertyNames().size() != 0) {
                argsList.add("--config");

                for (String key : topic.getProp().stringPropertyNames()) {
                    argsList.add(key + "=" + topic.getProp().get(key));
                }
            }
            argsList.add("--replica-assignment");
            argsList.add(reassignStr);

            TopicCommand.createTopic(zkUtils, new TopicCommand.TopicCommandOptions(argsList.stream().toArray(String[]::new)));
        }

        return describeTopic(topic.getName());
    }

    @Override
    public List<String> listTopics() {
        return CollectionConvertor.seqConvertJavaList(zkUtils.getAllTopics());
    }

    @Override
    public List<TopicBrief> listTopicBrief() {
        KafkaConsumer consumer = createNewConsumer(DEFAULTCP);
        Map<String, List<PartitionInfo>> topicMap = consumer.listTopics();
        return topicMap.entrySet().parallelStream().map(e -> {
                    String topic = e.getKey();
                    long isrCount = e.getValue().parallelStream().flatMap(pi -> Arrays.stream(pi.replicas())).count();
                    long replicateCount = e.getValue().parallelStream().flatMap(pi -> Arrays.stream(pi.inSyncReplicas())).count();
                    return new TopicBrief(topic, e.getValue().size(), isrCount / replicateCount);
                }
        ).collect(toList());
    }

    @Override
    public boolean existTopic(String topicName) {
        return AdminUtils.topicExists(zkUtils, topicName);
    }

    @Override
    public List<BrokerInfo> listBrokers() {
        List<Broker> brokerList = CollectionConvertor.seqConvertJavaList(zkUtils.getAllBrokersInCluster());
        return brokerList.parallelStream().collect(Collectors.toMap(Broker::id, Broker::rack)).entrySet().parallelStream()
                .map(entry -> {
                    String brokerInfoStr = null;
                    try {
                        brokerInfoStr = new String(
                                zkClient.getData().forPath(ZkUtils.BrokerIdsPath() + "/" + entry.getKey())
                        );
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    BrokerInfo brokerInfo = gson.fromJson(brokerInfoStr, BrokerInfo.class);
                    if (entry.getValue().isEmpty())
                        brokerInfo.setRack("");
                    else {
                        brokerInfo.setRack(entry.getValue().get());
                    }
                    brokerInfo.setId(entry.getKey());
                    return brokerInfo;
                }).collect(toList());
    }

    @Override
    public TopicMeta describeTopic(String topicName) {
        KafkaConsumer consumer = createNewConsumer(DEFAULTCP);
        TopicMeta topicMeta = new TopicMeta(topicName);
        List<PartitionInfo> tmList = consumer.partitionsFor(topicName);
        topicMeta.setPartitionCount(tmList.size());
        topicMeta.setReplicationFactor(tmList.get(0).replicas().length);
        topicMeta.setTopicCustomConfigs(getTopicPropsFromZk(topicName));
        topicMeta.setTopicPartitionInfos(tmList.parallelStream().map(
                tm -> {
                    TopicPartitionInfo topicPartitionInfo = new TopicPartitionInfo();
                    topicPartitionInfo.setLeader(tm.leader().host());
                    topicPartitionInfo.setIsr(Arrays.stream(tm.inSyncReplicas()).map(node -> node.host()).collect(toList()));
                    topicPartitionInfo.setPartitionId(tm.partition());
                    topicPartitionInfo.setReplicas(Arrays.stream(tm.replicas()).map(node -> node.host()).collect(toList()));
                    topicPartitionInfo.setIn_sync();
                    topicPartitionInfo.setStartOffset(getBeginningOffset(tm.leader(), tm.topic(), tm.partition()));
                    topicPartitionInfo.setEndOffset(getEndOffset(tm.leader(), tm.topic(), tm.partition()));
                    topicPartitionInfo.setMessageAvailable();
                    return topicPartitionInfo;

                }).collect(toList())
        );

        Collections.sort(topicMeta.getTopicPartitionInfos());

        return topicMeta;
    }

    @Override
    public GeneralResponse deleteTopic(String topic) {
        log.warn("Delete topic " + topic);
        AdminUtils.deleteTopic(zkUtils, topic);

        //Wait for a while
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (existTopic(topic)) {
            throw new ApiException("Delete topic failed for unknown reason!");
        }
        return new GeneralResponse(GeneralResponseState.success, topic + " has been deleted.");
    }

    @Override
    public Properties createTopicConf(String topic, Properties prop) {
        Properties configs = getTopicPropsFromZk(topic);
        configs.putAll(prop);
        AdminUtils.changeTopicConfig(zkUtils, topic, configs);
        log.info("Create config for topic: " + topic + "Configs:" + configs);
        return getTopicPropsFromZk(topic);
    }

    @Override
    public Properties deleteTopicConf(String topic, List<String> deleteProps) {
        // compile the final set of configs
        Properties configs = getTopicPropsFromZk(topic);
        deleteProps.stream().forEach(config -> configs.remove(config));
        AdminUtils.changeTopicConfig(zkUtils, topic, configs);
        log.info("Delete config for topic: " + topic);
        return getTopicPropsFromZk(topic);
    }

    @Override
    public Properties updateTopicConf(String topic, Properties prop) {
        AdminUtils.changeTopicConfig(zkUtils, topic, prop);
        return getTopicPropsFromZk(topic);
    }

    @Override
    public Properties getTopicConf(String topic) {
        return getTopicPropsFromZk(topic);
    }

    @Override
    public Properties getTopicConfByKey(String topic, String key) {
        String value = String.valueOf(AdminUtils.fetchEntityConfig(zkUtils, ConfigType.Topic(), topic).get(key));
        Properties returnProps = new Properties();
        if (!value.equals("null")) {
            returnProps.setProperty(key, value);
            return returnProps;
        } else
            return null;
    }

    @Override
    public boolean deleteTopicConfByKey(String topic, String key) {
        Properties configs = getTopicPropsFromZk(topic);
        configs.remove(key);
        AdminUtils.changeTopicConfig(zkUtils, topic, configs);
        return getTopicPropsFromZk(topic).get(key) == null;
    }

    @Override
    public Properties updateTopicConfByKey(String topic, String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);
        String validValue = String.valueOf(updateTopicConf(topic, props).get(key));
        if (!validValue.equals("null") && validValue.equals(value)) {
            return props;
        } else {
            throw new ApiException("Update Topic Config failed: " + key + ":" + value);
        }
    }

    @Override
    public Properties createTopicConfByKey(String topic, String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);
        String validValue = String.valueOf(createTopicConf(topic, props).get(key));
        if (!validValue.equals("null") && validValue.equals(value)) {
            return props;
        } else {
            throw new ApiException("Update Topic Config failed: " + key + ":" + value);
        }

    }

    @Override
    public TopicMeta addPartition(String topic, AddPartition addPartition) {
        List<MetadataResponse.PartitionMetadata> partitionMataData = AdminUtils.fetchTopicMetadataFromZk(topic, zkUtils).partitionMetadata();
        int numPartitions = partitionMataData.size();
        int numReplica = partitionMataData.get(0).replicas().size();
        List partitionIdList = partitionMataData.stream().map(p -> String.valueOf(p.partition())).collect(toList());
        String assignmentStr = addPartition.getReplicaAssignment();
        String toBeSetReplicaAssignmentStr = "";

        if (assignmentStr != null && !assignmentStr.equals("")) {
            //Check out of index ids in replica assignment string
            String[] ids = addPartition.getReplicaAssignment().split(",|:");
            if (Arrays.stream(ids).filter(id -> !partitionIdList.contains(id)).count() != 0) {
                throw new InvalidTopicException("Topic " + topic + ": manual reassignment str has wrong id!");
            }

            //Check if any ids duplicated in one partition in replica assignment
            String[] assignPartitions = addPartition.getReplicaAssignment().split(",");
            if (Arrays.stream(assignPartitions).filter(p ->
                    Arrays.stream(p.split(":")).collect(Collectors.toSet()).size()
                            != p.split(":").length).count()
                    != 0) {
                throw new InvalidTopicException("Topic " + topic + ": manual reassignment str has duplicated id in one partition!");
            }

            String replicaStr = Strings.repeat("0:", numReplica).replaceFirst(".$", ",");
            toBeSetReplicaAssignmentStr = Strings.repeat(replicaStr, numPartitions) + addPartition.getReplicaAssignment();
        } else {
            toBeSetReplicaAssignmentStr = "";
        }

        AdminUtils.addPartitions(zkUtils, topic, addPartition.getNumPartitionsAdded() + numPartitions,
                toBeSetReplicaAssignmentStr, true,
                RackAwareMode.Enforced$.MODULE$);

        return describeTopic(topic);
    }

    //Return <Current partition replica assignment, Proposed partition reassignment>
    @Override
    public List<String> generateReassignPartition(ReassignWrapper reassignWrapper) {
        Seq brokerSeq = JavaConverters.asScalaBufferConverter(reassignWrapper.getBrokers()).asScala().toSeq();
        //<Proposed partition reassignment，Current partition replica assignment>
        Tuple2 resultTuple2 = ReassignPartitionsCommand.generateAssignment(zkUtils, brokerSeq, reassignWrapper.generateReassignJsonString(), false);
        List<String> result = new ArrayList<>();
        result.add(zkUtils.formatAsReassignmentJson((scala.collection.Map<TopicAndPartition, Seq<Object>>) resultTuple2._2()));
        result.add(zkUtils.formatAsReassignmentJson((scala.collection.Map<TopicAndPartition, Seq<Object>>) resultTuple2._1()));

        return result;
    }

    @Override
    public Map<TopicAndPartition, Integer> executeReassignPartition(String reassignStr) {
        ReassignPartitionsCommand.executeAssignment(
                zkUtils,
                reassignStr
        );
        return checkReassignStatus(reassignStr);
    }

    @Override
    public Map<TopicAndPartition, Integer> checkReassignStatus(String reassignStr) {
        Map<TopicAndPartition, Seq<Object>> partitionsToBeReassigned = JavaConverters.mapAsJavaMapConverter(
                zkUtils.parsePartitionReassignmentData(reassignStr)).asJava();

        Map<TopicAndPartition, Seq<Object>> partitionsBeingReassigned = JavaConverters.mapAsJavaMapConverter(
                zkUtils.getPartitionsBeingReassigned()).asJava().entrySet().stream().collect(
                Collectors.toMap(
                        Map.Entry::getKey,
                        data -> data.getValue().newReplicas()
                ));


        java.util.Map<TopicAndPartition, ReassignmentStatus> reassignedPartitionsStatus =
                partitionsToBeReassigned.entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey,
                        pbr -> ReassignPartitionsCommand.checkIfPartitionReassignmentSucceeded(
                                zkUtils,
                                pbr.getKey(),
                                pbr.getValue(),
                                JavaConverters.mapAsScalaMapConverter(partitionsToBeReassigned).asScala(),
                                JavaConverters.mapAsScalaMapConverter(partitionsBeingReassigned).asScala()
                        )
                ));


        return reassignedPartitionsStatus.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                r -> r.getValue().status()
        ));
    }

    private List<String> listAllOldConsumerGroups() {
        return CollectionConvertor.seqConvertJavaList(zkUtils.getConsumerGroups());
    }

    private List<String> listOldConsumerGroupsByTopic(String topic) throws Exception {

        List<String> consumersFromZk = zkClient.getChildren().forPath(ZkUtils.ConsumersPath());
        List<String> cList = new ArrayList<>();

        for (String consumer : consumersFromZk) {
            String path = ZkUtils.ConsumersPath() + "/" + consumer + "/offsets";
            if (zkClient.checkExists().forPath(path) != null) {
                if (zkClient.getChildren().forPath(path).size() != 0) {
                    if (!Strings.isNullOrEmpty(topic)) {
                        if (zkClient.getChildren().forPath(path).stream().filter(p -> p.equals(topic)).count() != 0)
                            cList.add(consumer);
                    } else {
                        cList.add(consumer);
                    }
                }
            }
        }

        return cList;

        //May cause keeperexception, deprecated
        //return JavaConverters.asJavaCollectionConverter(zkUtils.getAllConsumerGroupsForTopic(topic)).asJavaCollection().stream().collect(toList());
    }

    private List<String> listAllNewConsumerGroups() {
        List activeGroups = CollectionConvertor.seqConvertJavaList(kafkaAdminClient.listAllConsumerGroupsFlattened()).stream()
                .map(GroupOverview::groupId).collect(toList());
        List usedTobeGroups = storage.getMap().entrySet().stream().map(Map.Entry::getKey).collect(toList());

        //Merge two lists
        activeGroups.removeAll(usedTobeGroups);
        activeGroups.addAll(usedTobeGroups);
        Collections.sort(activeGroups);
        return activeGroups;
    }

    private List<String> listNewConsumerGroupsByTopic(String topic) {
        AdminClient.ConsumerSummary cs;
        String t;
        List<String> consumersList = listAllNewConsumerGroups();

        Map<String, String> consumerTopicMap = new HashMap<>();
        for (String c : consumersList) {
            List<AdminClient.ConsumerSummary> consumerSummaryList = CollectionConvertor.listConvertJavaList(kafkaAdminClient.describeConsumerGroup(c));
            if (consumerSummaryList.size() > 0) {
                cs = consumerSummaryList.get(0);
                t = CollectionConvertor.listConvertJavaList(cs.assignment()).stream().collect(toList()).get(0).topic();
                consumerTopicMap.put(c, t);
            }
        }
        List<java.util.Map.Entry<String, String>> consumerEntryList = consumerTopicMap.entrySet().stream().collect(
                Collectors.groupingBy(
                        Map.Entry::getValue,
                        Collectors.toList()
                )
        ).getOrDefault(topic, new ArrayList<>());

        return consumerEntryList.stream().map(Map.Entry::getKey).collect(Collectors.toList());
    }

    @Override
    public Map<String, List<ConsumerGroupDesc>> describeOldCG(String consumerGroup, String topic) {
        Map<String, List<ConsumerGroupDesc>> result = new HashMap<>();
        List<ConsumerGroupDesc> cgdList = new ArrayList<>();
        Map<Integer, Long> fetchOffSetFromZKResultList = new HashMap<>();

        List<String> topicList = CollectionConvertor.seqConvertJavaList(zkUtils.getTopicsByConsumerGroup(consumerGroup));
        if (topicList.size() == 0) {
            return null;
        }

        if (!Strings.isNullOrEmpty(topic)) {
            topicList = Collections.singletonList(topic);
        }

        for (String t : topicList) {
            List<TopicAndPartition> topicPartitions = getTopicPartitions(t);
            ZKGroupTopicDirs groupDirs = new ZKGroupTopicDirs(consumerGroup, t);
            Map<Integer, String> ownerPartitionMap = topicPartitions.stream().collect(Collectors.toMap(
                    TopicAndPartition::partition,
                    tp -> {
                        Option<String> owner = zkUtils.readDataMaybeNull(groupDirs.consumerOwnerDir() + "/" + tp.partition())._1;
                        if (owner != NONE) {
                            return owner.get();
                        } else {
                            return "none";
                        }
                    }
                    )
            );

            ExecutorService executor = Executors.newCachedThreadPool();

            List<FetchOffsetFromZKTask> taskList = topicPartitions.stream().map(
                    tp -> new FetchOffsetFromZKTask(zookeeperUtils, tp.topic(), consumerGroup, tp.partition()))
                    .collect(toList());
            List<Future<FetchOffSetFromZKResult>> resultList = null;

            try {
                resultList = executor.invokeAll(taskList);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            executor.shutdown();

            for (int i = 0; i < resultList.size(); i++) {
                Future<FetchOffSetFromZKResult> future = resultList.get(i);
                try {
                    FetchOffSetFromZKResult offsetResult = future.get();
                    fetchOffSetFromZKResultList.put(
                            offsetResult.getParition(),
                            offsetResult.getOffset());
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }

            TopicMeta topicMeta = describeTopic(t);

            cgdList.addAll(setCGD(fetchOffSetFromZKResultList, ownerPartitionMap, t, consumerGroup, topicMeta));
            Collections.sort(cgdList);
            result.put(t, cgdList);
        }


        return result;
    }

    private List<ConsumerGroupDesc> setCGD(
            Map<Integer, Long> fetchOffSetFromZKResultList,
            Map<Integer, String> ownerPartitionMap,
            String topic, String consumerGroup, TopicMeta topicMeta) {
        List<ConsumerGroupDesc> cgdList = new ArrayList<>();
        return ownerPartitionMap.entrySet().stream().map(op -> {
            ConsumerGroupDesc cgd = new ConsumerGroupDesc();
            cgd.setGroupName(consumerGroup);
            cgd.setTopic(topic);
            cgd.setPartitionId(op.getKey());
            cgd.setCurrentOffset(fetchOffSetFromZKResultList.get(op.getKey()));
            cgd.setLogEndOffset(
                    topicMeta.getTopicPartitionInfos().stream()
                            .filter(tpi -> tpi.getPartitionId() == op.getKey()).findFirst().get().getEndOffset());
            cgd.setLag();
            if (op.getValue().equals("none")) {
                cgd.setConsumerId("-");
                cgd.setHost("-");
                cgd.setState(ConsumerState.PENDING);
            } else {
                cgd.setConsumerId(op.getValue());
                cgd.setHost(op.getValue().replace(consumerGroup + "_", ""));
                cgd.setState(ConsumerState.RUNNING);
            }
            cgd.setType(ConsumerType.OLD);
            return cgd;
        }).collect(toList());
    }

    @Override
    public Map<String, List<ConsumerGroupDesc>> describeNewCG(String consumerGroup, String topic) {
        if (!isNewConsumerGroup(consumerGroup)) {
            return Collections.emptyMap();
        }

        Map<GroupTopicPartition, OffsetAndMetadata> cgdMap = storage.get(consumerGroup);

        AdminClient adminClient = AdminClient.create(kafkaUtils.getProp());
        List<AdminClient.ConsumerSummary> consumerSummaryList =
                CollectionConvertor.listConvertJavaList(adminClient.describeConsumerGroup(consumerGroup));

        return setCGD(cgdMap, consumerSummaryList, consumerGroup, topic);
    }

    private Map<String, List<ConsumerGroupDesc>> setCGD(
            Map<GroupTopicPartition, OffsetAndMetadata> storageMap,
            List<AdminClient.ConsumerSummary> consumerSummaryList,
            String consumerGroup,
            String topic) {
        Map<String, List<ConsumerGroupDesc>> result = new HashedMap();
        List<ConsumerGroupDesc> cgdList;
        ConsumerGroupDesc cgd;
        ConsumerState state;
        List<String> topicList = storageMap.entrySet().stream()
                .map(e -> e.getKey().topicPartition().topic()).distinct()
                .filter(e -> {
                    if (Strings.isNullOrEmpty(topic)) {
                        return true;
                    } else {
                        return e.equals(topic);
                    }
                })
                .collect(toList());

        for (String t : topicList) {
            //First get the information of this topic
            Map<GroupTopicPartition, OffsetAndMetadata> topicStorage = storageMap.entrySet().stream().filter(e -> e.getKey().topicPartition().topic().equals(t))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (consumerSummaryList.size() == 0) {
                cgdList = new ArrayList<>();
                state = ConsumerState.PENDING;
                TopicMeta topicMeta = describeTopic(t);
                Map<Integer, Long> partitionEndOffsetMap = topicMeta.getTopicPartitionInfos().stream()
                        .collect(Collectors.toMap(
                                tpi -> tpi.getPartitionId(),
                                tpi -> tpi.getEndOffset()
                                )
                        );
                //Second get the current offset of each partition in this topic
                Map<Integer, Long> partitionCurrentOffsetMap = topicStorage.entrySet().stream()
                        .filter(e -> e.getKey().topicPartition().topic().equals(t))
                        .collect(Collectors.toMap(
                                e -> e.getKey().topicPartition().partition(),
                                e -> e.getValue().offset()
                        ));

                for (Map.Entry<GroupTopicPartition, OffsetAndMetadata> storage : topicStorage.entrySet()) {
                    cgd = new ConsumerGroupDesc();
                    cgd.setGroupName(consumerGroup);
                    cgd.setTopic(t);
                    cgd.setConsumerId("-");
                    cgd.setPartitionId(storage.getKey().topicPartition().partition());
                    cgd.setCurrentOffset(partitionCurrentOffsetMap.get(cgd.getPartitionId()));
                    cgd.setLogEndOffset(partitionEndOffsetMap.get(cgd.getPartitionId()));
                    cgd.setLag();
                    cgd.setHost("-");
                    cgd.setState(state);
                    cgd.setType(ConsumerType.NEW);
                    cgdList.add(cgd);
                }
            } else {
                cgdList = new ArrayList<>();
                state = ConsumerState.RUNNING;
                for (AdminClient.ConsumerSummary cs : consumerSummaryList) {
                    //First get the end offset of each partition in a topic
                    List<TopicPartition> assignment = CollectionConvertor.listConvertJavaList(cs.assignment());
                    TopicMeta topicMeta = describeTopic(t);
                    Map<Integer, Long> partitionEndOffsetMap = topicMeta.getTopicPartitionInfos()
                            .stream().collect(Collectors.toMap(
                                    tpi -> tpi.getPartitionId(),
                                    tpi -> tpi.getEndOffset()
                                    )
                            );
                    //Second get the current offset of each partition in this topic
                    Map<Integer, Long> partitionCurrentOffsetMap = topicStorage.entrySet().stream()
                            .filter(e -> e.getKey().topicPartition().topic().equals(t))
                            .collect(Collectors.toMap(
                                    e -> e.getKey().topicPartition().partition(),
                                    e -> e.getValue().offset()
                            ));

                    for (TopicPartition tp : assignment) {
                        cgd = new ConsumerGroupDesc();
                        cgd.setGroupName(consumerGroup);
                        cgd.setTopic(tp.topic());
                        cgd.setPartitionId(tp.partition());
                        cgd.setCurrentOffset(partitionCurrentOffsetMap.get(tp.partition()));
                        cgd.setLogEndOffset(partitionEndOffsetMap.get(tp.partition()));
                        cgd.setLag();
                        cgd.setConsumerId(cs.clientId());
                        cgd.setHost(cs.clientHost());
                        cgd.setState(state);
                        cgd.setType(ConsumerType.NEW);
                        cgdList.add(cgd);
                    }
                }
            }

            result.put(t, cgdList);
        }


        return result;
    }

    @Override
    public String getMessage(String topic, int partition, long offset, String decoder, String avroSchema) {
        KafkaConsumer consumer = createNewConsumer(DEFAULTCP);
        TopicPartition tp = new TopicPartition(topic, partition);
        long beginningOffset = getBeginningOffset(topic, partition);
        long endOffset = getEndOffset(topic, partition);
        if (offset < beginningOffset || offset >= endOffset) {
            log.error(offset + " error");
            throw new ApiException(
                    "offsets must be between " + String.valueOf(beginningOffset
                            + " and " + endOffset
                    )
            );
        }
        consumer.assign(Arrays.asList(tp));
        consumer.seek(tp, offset);

        String last;

        while (true) {
            ConsumerRecords<String, String> crs = consumer.poll(channelRetryBackoffMs);
            if (crs.count() != 0) {
                Iterator<ConsumerRecord<String, String>> it = crs.iterator();

                ConsumerRecord<String, String> initCr = it.next();
                last = initCr.value() + String.valueOf(initCr.offset());
                while (it.hasNext()) {
                    ConsumerRecord<String, String> cr = it.next();
                    last = cr.value() + String.valueOf(cr.offset());
                }
                break;
            }
        }
        return last;
    }

    @Override
    public GeneralResponse resetOffset(String topic, int partition, String consumerGroup, String type, String offset) {
        long offsetToBeReset;
        long beginningOffset = getBeginningOffset(topic, partition);
        long endOffset = getEndOffset(topic, partition);

        if (isConsumerGroupActive(consumerGroup, type)) {
            throw new ApiException("Assignments can only be reset if the group " + consumerGroup + " is inactive");
        }

        //if type is new or the consumergroup itself is new
        if ((!Strings.isNullOrEmpty(type) && type.equals("new")) && isNewConsumerGroup(consumerGroup)) {
            KafkaConsumer consumer = createNewConsumer(consumerGroup);
            TopicPartition tp = new TopicPartition(topic, partition);
            if (offset.equals("earliest")) {
                consumer.seekToBeginning(Arrays.asList(tp));
            } else if (offset.equals("latest")) {
                consumer.seekToEnd(Arrays.asList(tp));
            } else {
                if (Long.parseLong(offset) <= beginningOffset || Long.parseLong(offset) > endOffset) {
                    log.error(offset + " error");
                    throw new ApiException(
                            "offsets must be between " + String.valueOf(beginningOffset
                                    + " and " + endOffset
                            )
                    );
                }
                offsetToBeReset = Long.parseLong(offset);
                consumer.assign(Arrays.asList(tp));
                consumer.seek(tp, offsetToBeReset);
            }
            consumer.commitSync();
        }

        //if type is old or the consumer group itself is old
        if ((!Strings.isNullOrEmpty(type) && type.equals("old")) && isOldConsumerGroup(consumerGroup)) {
            if (offset.equals("earliest")) {
                offset = String.valueOf(beginningOffset);
            } else if (offset.equals("latest")) {
                offset = String.valueOf(endOffset);
            }
            try {
                if (Long.parseLong(offset) <= beginningOffset || Long.parseLong(offset) > endOffset) {
                    log.error(offset + " error");
                    throw new ApiException(
                            "offsets must be between " + String.valueOf(beginningOffset
                                    + " and " + endOffset
                            )
                    );
                }
                zkUtils.zkClient().writeData(
                        "/consumers/" + consumerGroup + "/offsets/" + topic + "/" + partition,
                        offset);
            } catch (Exception e) {
                new ApiException("Wrote Data" +
                        offset + " to " + "/consumers/" + consumerGroup +
                        "/offsets/" + topic + "/" + partition);
            }
        }
        return new GeneralResponse(GeneralResponseState.success, "Reset the offset successfully!");
    }

    @Override
    public Map<String, Map<Integer, java.lang.Long>> getLastCommitTime(String consumerGroup, String topic) {
        Map<String, Map<Integer, java.lang.Long>> result = new ConcurrentHashMap<>();

        //Get Old Consumer commit time
        try {
            Map<Integer, java.lang.Long> oldConsumerOffsetMap = new ConcurrentHashMap<>();
            if (zkClient.checkExists().forPath(CONSUMERPATHPREFIX + consumerGroup) != null
                    && zkClient.checkExists().forPath(CONSUMERPATHPREFIX + consumerGroup + OFFSETSPATHPREFIX + topic) != null) {
                List<String> offsets = zkClient.getChildren().forPath(CONSUMERPATHPREFIX + consumerGroup + OFFSETSPATHPREFIX + topic);
                for (String offset : offsets) {
                    Integer id = Integer.valueOf(offset);
                    long mtime = zkClient.checkExists().forPath(CONSUMERPATHPREFIX + consumerGroup + OFFSETSPATHPREFIX + topic + "/" + offset).getMtime();
                    oldConsumerOffsetMap.put(id, mtime);
                }

                result.put("old", oldConsumerOffsetMap);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }


        //Get New consumer commit time, from offset storage instance
        if (storage.get(consumerGroup) != null) {
            Map<GroupTopicPartition, OffsetAndMetadata> storageResult = storage.get(consumerGroup);
            result.put("new", (storageResult.entrySet().parallelStream().filter(s -> s.getKey().topicPartition().topic().equals(topic))
                            .collect(
                                    Collectors.toMap(
                                            s -> s.getKey().topicPartition().partition(),
                                            s -> s.getValue().commitTimestamp()
                                    )
                            )
                    )
            );
        }

        return result;
    }

    @Override
    public GeneralResponse deleteConsumerGroup(String consumerGroup) {
        if (!AdminUtils.deleteConsumerGroupInZK(zkUtils, consumerGroup)) {
            throw new ApiException(consumerGroup + " has not been deleted for some reason");
        }
        return new GeneralResponse(GeneralResponseState.success, consumerGroup + " has been deleted.");
    }

    @Override
    public Map<String, List<String>> listAllConsumerGroups() {
        Map<String, List<String>> result = new HashMap<>();

        List<String> oldCGList = listAllOldConsumerGroups();
        if (oldCGList.size() != 0) {
            result.put("old", oldCGList);
        }

        List<String> newCGList = listAllNewConsumerGroups();
        if (newCGList.size() != 0) {
            result.put("new", newCGList);
        }

        return result;
    }

    @Override
    public Map<String, List<String>> listConsumerGroupsByTopic(String topic) {
        Map<String, List<String>> result = new HashMap<>();

        List<String> oldCGList = null;
        try {
            oldCGList = listOldConsumerGroupsByTopic(topic);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (oldCGList.size() != 0) {
            result.put("old", oldCGList);
        }

        List<String> newCGList = null;
        try {
            newCGList = listNewConsumerGroupsByTopic(topic);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (newCGList.size() != 0) {
            result.put("new", newCGList);
        }

        return result;
    }

    private List<TopicAndPartition> getTopicPartitions(String t) {
        List<TopicAndPartition> tpList = new ArrayList<>();
        List<String> l = Arrays.asList(t);
        java.util.Map<String, Seq<Object>> tpMap = JavaConverters.mapAsJavaMapConverter(zkUtils.getPartitionsForTopics(JavaConverters.asScalaIteratorConverter(l.iterator()).asScala().toSeq())).asJava();
        if (tpMap != null) {
            ArrayList<Object> partitionLists = new ArrayList<>(JavaConverters.seqAsJavaListConverter(tpMap.get(t)).asJava());
            tpList = partitionLists.stream().map(p -> new TopicAndPartition(t, (Integer) p)).collect(toList());
        }
        return tpList;
    }

    private Properties getTopicPropsFromZk(String topic) {
        return AdminUtils.fetchEntityConfig(zkUtils, ConfigType.Topic(), topic);
    }


    private KafkaConsumer createNewConsumer(String consumerGroup) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUtils.getKafkaConfig().getBrokers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getCanonicalName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getCanonicalName());

        return new KafkaConsumer(properties);
    }

    private long getOffsets(Node leader, String topic, int partitionId, long time) {
        TopicAndPartition topicAndPartition = new TopicAndPartition(topic, partitionId);

        SimpleConsumer consumer = new SimpleConsumer(
                leader.host(),
                leader.port(),
                10000,
                1024,
                "Kafka-zk-simpleconsumer"
        );

        PartitionOffsetRequestInfo partitionOffsetRequestInfo = new PartitionOffsetRequestInfo(time, 10000);
        OffsetRequest offsetRequest = new OffsetRequest(ImmutableMap.of(topicAndPartition, partitionOffsetRequestInfo), kafka.api.OffsetRequest.CurrentVersion(), consumer.clientId());
        OffsetResponse offsetResponse = consumer.getOffsetsBefore(offsetRequest);

        if (offsetResponse.hasError()) {
            short errorCode = offsetResponse.errorCode(topic, partitionId);
            log.warn(format("Offset response has error: %d", errorCode));
            throw new ApiException("could not fetch data from Kafka, error code is '" + errorCode + "'");
        }

        long[] offsets = offsetResponse.offsets(topic, partitionId);
        return offsets[0];
    }

    private long getOffsets(PartitionInfo partitionInfo, long time) {
        return getOffsets(partitionInfo.leader(), partitionInfo.topic(), partitionInfo.partition(), time);
    }

    public long getBeginningOffset(String topic, int partitionId) {
        return getOffsets(getLeader(topic, partitionId), topic, partitionId, kafka.api.OffsetRequest.EarliestTime());
    }

    public long getEndOffset(String topic, int partitionId) {
        return getOffsets(getLeader(topic, partitionId), topic, partitionId, kafka.api.OffsetRequest.LatestTime());
    }

    private long getBeginningOffset(Node leader, String topic, int partitionId) {
        return getOffsets(leader, topic, partitionId, kafka.api.OffsetRequest.EarliestTime());
    }

    private long getEndOffset(Node leader, String topic, int partitionId) {
        return getOffsets(leader, topic, partitionId, kafka.api.OffsetRequest.LatestTime());
    }

    private Node getLeader(String topic, int partitionId) {
        KafkaConsumer consumer = createNewConsumer(DEFAULTCP);
        List<PartitionInfo> tmList = consumer.partitionsFor(topic);

        PartitionInfo partitionInfo = tmList.stream().filter(pi -> pi.partition() == partitionId).findFirst().get();
        consumer.close();
        return partitionInfo.leader();
    }

    public boolean isOldConsumerGroup(String consumerGroup) {
        return listAllOldConsumerGroups().indexOf(consumerGroup) != -1;
    }

    public boolean isNewConsumerGroup(String consumerGroup) {
        Map<GroupTopicPartition, OffsetAndMetadata> cgdMap = storage.get(consumerGroup);

        //Active Consumergroup or Dead ConsumerGroup is OK
        return (listAllNewConsumerGroups().indexOf(consumerGroup) != -1) || ((cgdMap != null && cgdMap.size() != 0));
    }

    private boolean isConsumerGroupActive(String consumerGroup, String type) {
        if (type.equals("new")) {
            return CollectionConvertor.seqConvertJavaList(kafkaAdminClient.listAllConsumerGroupsFlattened()).stream()
                    .map(GroupOverview::groupId).filter(c -> c.equals(consumerGroup)).count() == 1;
        } else if (type.equals("old")) {
            return AdminUtils.isConsumerGroupActive(zkUtils, consumerGroup);
        } else {
            throw new ApiException("Unknown type " + type);
        }
    }
}
